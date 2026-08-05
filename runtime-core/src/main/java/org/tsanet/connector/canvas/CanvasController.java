package org.tsanet.connector.canvas;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.tsanet.connector.canvas.OutboundFlowState.Status;
import org.tsanet.connector.events.EventQueue;
import org.tsanet.connector.events.InboundEvent;
import org.tsanet.connector.intercom.AnnotationHandler;
import org.tsanet.connector.intercom.SubmissionLink;
import org.tsanet.connector.intercom.SubmissionLinkTable;
import org.tsanet.connector.tsanet.GatewayException;
import org.tsanet.connector.tsanet.GatewayTimeoutException;
import org.tsanet.connector.tsanet.TsanetGateway;
import org.tsanet.connector.tsanet.dto.FormField;
import org.tsanet.connector.tsanet.dto.PartnerHit;
import org.tsanet.connector.tsanet.dto.ProcessForm;
import org.tsanet.connector.tsanet.dto.SubmissionDraft;
import org.tsanet.connector.tsanet.dto.SubmissionResult;

/**
 * The outbound submission flow (Phase 1 slice 1). Budget rule (docs/decisions.md D2,
 * amended): at most ONE bounded gateway call per canvas response, no in-handler
 * retries; read timeouts render a retry path, and a submit timeout goes through the
 * findSubmission reconcile before any resubmission, because submits are not idempotent
 * and the Connect API rejects no duplicates (D2: reconcile is best-effort). Flow state
 * lives server-side behind an opaque pointer in stored_data (D8); the submission's
 * lifecycle is OutboundFlowState.Status, and every handler that could create or orphan
 * a case is gated on it server-side — client-supplied component ids are never trusted
 * to prove a reconcile happened.
 */
@RestController
public class CanvasController {

    private static final Logger log = LoggerFactory.getLogger(CanvasController.class);

    private final FlowStateStore flows;
    private final TsanetGateway gateway;
    private final SubmissionLinkTable links;
    private final EventQueue queue;

    public CanvasController(FlowStateStore flows, TsanetGateway gateway, SubmissionLinkTable links,
            EventQueue queue) {
        this.flows = flows;
        this.gateway = gateway;
        this.links = links;
        this.queue = queue;
    }

    @PostMapping("/canvas/initialize")
    public Map<String, Object> initialize(@RequestBody Map<String, Object> payload) {
        return home(flows.create(), surface(payload), null);
    }

    @PostMapping("/canvas/submit")
    public Map<String, Object> submit(@RequestBody Map<String, Object> payload) {
        String surface = surface(payload);
        OutboundFlowState state = flows.find(storedFlowId(payload));
        if (state == null) {
            return home(flows.create(), surface, "That panel session expired; starting over.");
        }
        String component = String.valueOf(payload.getOrDefault("component_id", ""));
        Map<String, String> inputs = inputValues(payload);

        if ("back_home".equals(component)) {
            Status status = state.status();
            if (status == Status.SUBMITTING || status == Status.UNRESOLVED) {
                // Starting over here would mint a fresh flow id == fresh
                // internalCaseNumber and orphan the possibly-created case.
                return renderByStatus(state, surface, null);
            }
            // New flow id on purpose: internalCaseNumber == flowId, one per submission.
            return home(flows.create(), surface, null);
        }
        if ("check_status".equals(component)) {
            return doCheckStatus(state, surface, payload);
        }
        if (state.status() != Status.DRAFT) {
            // Search/select/submit from a stale view of a flow whose submission is in
            // flight, unresolved, or landed: render the truth instead of acting.
            return renderByStatus(state, surface, null);
        }
        if ("search".equals(component)) {
            return doSearch(state, surface, inputs.getOrDefault("q", "").trim());
        }
        if (component.startsWith("select:")) {
            return doSelect(state, surface, component.substring("select:".length()));
        }
        if ("submit_case".equals(component) || "retry_submit".equals(component)) {
            return doSubmit(state, payload, inputs, "retry_submit".equals(component));
        }
        // Unknown component (live Canvas Kit shapes may surprise us, D8): re-render the
        // current step rather than destroying the flow and the agent's typed work.
        return renderByStatus(state, surface, null);
    }

    // ---- steps ----

    private Map<String, Object> doSearch(OutboundFlowState state, String surface, String query) {
        if (query.isEmpty()) {
            return home(state, surface, "Enter a partner name or tag to search.");
        }
        List<PartnerHit> hits;
        try {
            hits = gateway.searchPartners(query);
        } catch (GatewayException e) {
            return home(state, surface, "Partner search failed (" + e.getMessage() + "); try again.");
        }
        if (hits.isEmpty()) {
            return home(state, surface, "No partners matched \"" + query + "\".");
        }
        state.hits(hits);
        return resultsCanvas(state, hits.size() + " result(s) for \"" + query + "\"");
    }

    /** Payload-size guard (D8's motivation): more hits than this asks for a narrower search. */
    private static final int MAX_RESULT_BUTTONS = 12;

    private Map<String, Object> resultsCanvas(OutboundFlowState state, String subtitle) {
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(CanvasKit.header("Select a partner"));
        if (subtitle != null) {
            components.add(CanvasKit.muted(subtitle));
        }
        List<PartnerHit> hits = state.hits();
        // Buttons carry the partner's identity (companyId|departmentId), not a list
        // index: a stale results canvas in another view can never select a DIFFERENT
        // partner than the label that was clicked (QA round-1 regression note).
        int shown = Math.min(hits.size(), MAX_RESULT_BUTTONS);
        for (int i = 0; i < shown; i++) {
            PartnerHit hit = hits.get(i);
            components.add(CanvasKit.button("select:" + contentKey(hit), hit.display()));
        }
        if (hits.size() > shown) {
            components.add(CanvasKit.muted("Showing the first " + shown + " of " + hits.size()
                    + " matches; refine your search to see the rest."));
        }
        components.add(CanvasKit.divider());
        components.add(CanvasKit.button("back_home", "Start over"));
        return CanvasKit.canvas(components, pointer(state));
    }

    private static String contentKey(PartnerHit hit) {
        return hit.companyId() + "|" + (hit.departmentId() == null ? "" : hit.departmentId());
    }

    private Map<String, Object> doSelect(OutboundFlowState state, String surface, String key) {
        PartnerHit partner = state.hits().stream()
                .filter(hit -> contentKey(hit).equals(key))
                .findFirst()
                .orElse(null);
        if (partner == null) {
            // Wrong-but-well-formed input: a syntactically valid key that is not in
            // the CURRENT list (stale canvas, or a fabricated id). Re-render the
            // current results rather than acting or dead-ending.
            return state.hits().isEmpty()
                    ? home(state, surface, "That selection is no longer valid; search again.")
                    : resultsCanvas(state, "That selection is no longer in the results; pick from the current list.");
        }
        ProcessForm form;
        try {
            form = gateway.form(partner);
        } catch (GatewayException e) {
            return home(state, surface,
                    "Couldn't load the form for " + partner.display() + " (" + e.getMessage() + "); try again.");
        }
        List<FormField> unsupported = form.unsupportedFields();
        if (!unsupported.isEmpty()) {
            String types = unsupported.stream()
                    .map(f -> f.label() + " [" + f.type() + "]")
                    .collect(Collectors.joining(", "));
            return CanvasKit.canvas(List.of(
                    CanvasKit.header("Form not supported yet"),
                    CanvasKit.error(partner.display() + "'s process form uses field types this connector"
                            + " doesn't support yet: " + types + ". File a connector issue; meanwhile this"
                            + " case can be submitted via the TSANet web app."),
                    CanvasKit.button("back_home", "Start over")), pointer(state));
        }
        synchronized (state) {
            PartnerHit previous = state.partner();
            if (previous == null || !sameTarget(previous, partner)) {
                // A different partner means a different process form; values typed for
                // the old form must not pre-fill (or ride into a submission against)
                // the new one.
                state.clearFieldValues();
            }
            // Set as a pair under the monitor: form must always match partner.
            state.partner(partner);
            state.form(form);
            return formCanvas(state, null);
        }
    }

    private static boolean sameTarget(PartnerHit a, PartnerHit b) {
        return Objects.equals(a.companyId(), b.companyId())
                && Objects.equals(a.departmentId(), b.departmentId());
    }

    private Map<String, Object> formCanvas(OutboundFlowState state, String notice) {
        ProcessForm form = state.form();
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(CanvasKit.header(state.partner().display()));
        if (notice != null) {
            components.add(CanvasKit.error(notice));
        }
        if (form.adminNote() != null && !form.adminNote().isBlank()) {
            components.add(CanvasKit.muted("Partner note: " + form.adminNote()));
        }
        if (form.escalationInstructions() != null && !form.escalationInstructions().isBlank()) {
            components.add(CanvasKit.muted("Escalation: " + form.escalationInstructions()));
        }
        components.add(CanvasKit.input("summary", "Problem summary (required)", "One line", state.values().get("summary")));
        components.add(CanvasKit.textarea("description", "Problem description (required)", "What happened, impact, versions",
                state.values().get("description")));
        for (FormField field : form.fields()) {
            if (!field.rendered()) {
                continue; // HIDDEN rides the form template server-side, never the canvas
            }
            String id = "f_" + field.fieldId();
            String label = field.label() + (field.required() ? " (required)" : "");
            if (field.selectShaped()) {
                components.add(CanvasKit.singleSelect(id, label, field.options(), state.values().get(id)));
            } else if (field.textareaShaped()) {
                components.add(CanvasKit.textarea(id, label, null, state.values().get(id)));
            } else {
                components.add(CanvasKit.input(id, label, null, state.values().get(id)));
            }
        }
        components.add(CanvasKit.divider());
        components.add(CanvasKit.button("submit_case", "Submit to partner"));
        components.add(CanvasKit.button("back_home", "Start over"));
        return CanvasKit.canvas(components, pointer(state));
    }

    private Map<String, Object> doSubmit(OutboundFlowState state, Map<String, Object> payload,
            Map<String, String> inputs, boolean retry) {
        SubmissionDraft draft = null;
        Map<String, Object> renderedUnderLock = null;
        boolean lostForm = false;
        // One monitor section covers merge -> validate -> claim -> snapshot, so a
        // concurrent select cannot swap the form between validation and the draft, and
        // no value can arrive after validation has read them (QA round-2 lock-scope
        // note). The gateway call stays outside the lock; contention is per-flow.
        // NO store call may run in here: flows.create()/find() hold the STORE monitor,
        // and opposing acquisition orders are a connector-wide deadlock (QA round-3) —
        // so the lost-form restart is only DECIDED here and minted after release.
        synchronized (state) {
            if (state.form() == null || state.partner() == null) {
                lostForm = true;
            } else {
                if (!retry) {
                    state.values().putAll(inputs);
                }
                List<String> missing = new ArrayList<>();
                if (blank(state.values().get("summary"))) {
                    missing.add("Problem summary");
                }
                if (blank(state.values().get("description"))) {
                    missing.add("Problem description");
                }
                for (FormField field : state.form().fields()) {
                    // Only rendered fields can be typed; a required HIDDEN field is
                    // the form template's job, not the agent's.
                    if (field.rendered() && field.required()
                            && blank(state.values().get("f_" + field.fieldId()))) {
                        missing.add(field.label());
                    }
                }
                if (!missing.isEmpty()) {
                    renderedUnderLock = formCanvas(state, "Missing required: " + String.join(", ", missing));
                } else if (!state.beginSubmit()) {
                    // The one legal submit is claiming DRAFT -> SUBMITTING; losing the
                    // claim means another view already submitted (or the outcome is known).
                    renderedUnderLock = renderByStatus(state, surface(payload), null);
                } else {
                    draft = draftFrom(state, payload);
                }
            }
        }
        if (lostForm) {
            return home(flows.create(), surface(payload), "That panel session lost its form; starting over.");
        }
        if (renderedUnderLock != null) {
            return renderedUnderLock;
        }
        SubmissionResult result;
        try {
            result = gateway.submit(draft);
        } catch (GatewayTimeoutException e) {
            // If a concurrent lookup landed the flow first, this renders one stale
            // pending canvas; the next click self-corrects via renderByStatus.
            state.submitUnresolved();
            return pendingCanvas(state);
        } catch (GatewayException e) {
            // Per TsanetGateway.submit's contract, reaching here means the API
            // definitively rejected the request before a case could exist.
            state.submitFailed();
            return formCanvas(state, "Submission failed (" + e.getMessage() + "); fix and resubmit.");
        } catch (RuntimeException e) {
            // tsanetgit/Fin-Intercom_App#2: an exception outside the gateway taxonomy
            // is by definition an ambiguous outcome (the taxonomy's own rule). Without
            // this, the flow stays SUBMITTING forever — a state whose only exit needs a
            // lookup to FIND a case — and the panel is dead for the conversation.
            log.error("gateway.submit threw outside the taxonomy; treating as ambiguous (flow {})",
                    state.flowId(), e);
            state.submitUnresolved();
            return pendingCanvas(state);
        }
        // The case landed; bookkeeping runs OUTSIDE the gateway try so its failures
        // can never masquerade as gateway faults or park a landed flow (QA round-2).
        if (state.landed(result)) {
            recordLandedSafely(state, payload);
        }
        return confirmation(state);
    }

    private Map<String, Object> pendingCanvas(OutboundFlowState state) {
        return CanvasKit.canvas(List.of(
                CanvasKit.header("Submission status unknown"),
                CanvasKit.error("The request timed out but may still have created the case."
                        + " Check the status before doing anything else; a blind retry or a fresh"
                        + " start can duplicate the partner case."),
                CanvasKit.button("check_status", "Check status")), pointer(state));
    }

    private Map<String, Object> inProgressCanvas(OutboundFlowState state) {
        return CanvasKit.canvas(List.of(
                CanvasKit.header("Submission in progress"),
                CanvasKit.muted("This flow is being submitted in another view right now."
                        + " Check the status in a moment."),
                CanvasKit.button("check_status", "Check status")), pointer(state));
    }

    private Map<String, Object> notFoundCanvas(OutboundFlowState state) {
        return CanvasKit.canvas(List.of(
                CanvasKit.header("No case found yet"),
                CanvasKit.muted("No case was found for this submission's reference. If the timeout"
                        + " just happened, the original request may still be processing — checking"
                        + " again shortly is safest. Retrying will create the case if none exists."),
                CanvasKit.button("retry_submit", "Retry submission"),
                CanvasKit.button("check_status", "Check again"),
                CanvasKit.button("back_home", "Start over")), pointer(state));
    }

    private Map<String, Object> doCheckStatus(OutboundFlowState state, String surface,
            Map<String, Object> payload) {
        if (state.status() == Status.LANDED) {
            return confirmation(state);
        }
        try {
            return gateway.findSubmission(state.flowId())
                    .map(result -> {
                        if (state.landed(result)) {
                            recordLandedSafely(state, payload);
                        }
                        return confirmation(state);
                    })
                    .orElseGet(() -> {
                        if (state.reconcileNotFound()) {
                            return notFoundCanvas(state);
                        }
                        return switch (state.status()) {
                            case LANDED -> confirmation(state);
                            case SUBMITTING -> inProgressCanvas(state);
                            // DRAFT here means no submit ever ran, or an earlier lookup
                            // already re-armed: render the current step with a neutral
                            // truth, never copy that invites a "retry" that would be a
                            // FIRST submission (QA round-2 note).
                            default -> renderByStatus(state, surface,
                                    "No case exists for this flow yet.");
                        };
                    });
        } catch (GatewayException e) {
            return CanvasKit.canvas(List.of(
                    CanvasKit.header("Submission status unknown"),
                    CanvasKit.error("Status check failed (" + e.getMessage() + "); try again."),
                    CanvasKit.button("check_status", "Check status")), pointer(state));
        }
    }

    private Map<String, Object> renderByStatus(OutboundFlowState state, String surface, String notice) {
        return switch (state.status()) {
            case LANDED -> confirmation(state);
            case UNRESOLVED -> pendingCanvas(state);
            case SUBMITTING -> inProgressCanvas(state);
            case DRAFT -> state.form() != null ? formCanvas(state, notice)
                    : !state.hits().isEmpty() ? resultsCanvas(state, notice)
                    : home(state, surface, notice);
        };
    }

    private Map<String, Object> confirmation(OutboundFlowState state) {
        SubmissionResult result = state.result();
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(CanvasKit.header("Case submitted"));
        components.add(CanvasKit.muted("Partner: " + state.partner().display()));
        components.add(CanvasKit.muted("Token: " + result.token()));
        if (result.respondBy() != null) {
            components.add(CanvasKit.muted("Partner respond-by: " + result.respondBy()));
        }
        if (result.testCase()) {
            components.add(CanvasKit.muted("Test submission: no partner SLA or notifications."));
        }
        components.add(CanvasKit.divider());
        components.add(CanvasKit.button("back_home", "New collaboration"));
        return CanvasKit.canvas(components, pointer(state));
    }

    private Map<String, Object> home(OutboundFlowState state, String surface, String notice) {
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(CanvasKit.header("TSANet Connect"));
        if (notice != null) {
            components.add(CanvasKit.error(notice));
        }
        components.add(CanvasKit.muted("Start a partner collaboration from this " + surface + "."));
        components.add(CanvasKit.input("q", "Partner name or tag", "e.g. NetApp, storage", null));
        components.add(CanvasKit.button("search", "Search partners"));
        return CanvasKit.canvas(components, pointer(state));
    }

    // ---- landing bookkeeping ----

    /**
     * First-landing bookkeeping (exactly once per flow, guarded by landed()'s return):
     * record the routing link, audit the submission, and enqueue the Intercom
     * annotation - which runs on the event drain, never inside this canvas response
     * (D2 budget). No case content in the audit line or the link (D10).
     */
    /**
     * A bookkeeping failure must cost exactly the bookkeeping, audited — never the
     * agent's confirmation (the case DID land), never a fake gateway fault, and never
     * a silent hole in the manual re-drive path. landed()'s first-transition flag is
     * already consumed here, so this is the one shot: the audit line is the recovery
     * key when it misses.
     */
    private void recordLandedSafely(OutboundFlowState state, Map<String, Object> payload) {
        try {
            recordLanded(state, payload);
        } catch (RuntimeException e) {
            SubmissionResult result = state.result();
            log.error("tsanet_audit event=link_bookkeeping_failed token={} flow={} ticket={}",
                    result == null ? null : result.token(), state.flowId(),
                    nestedId(payload.get("ticket")), e);
        }
    }

    private void recordLanded(OutboundFlowState state, Map<String, Object> payload) {
        SubmissionResult result = state.result();
        PartnerHit partner = state.partner();
        SubmissionLink link = new SubmissionLink(
                result.token(),
                state.flowId(),
                stringOrNull(payload.get("workspace_id")),
                nestedId(payload.get("conversation")),
                nestedId(payload.get("ticket")),
                partner == null ? null : partner.display(),
                result.respondBy(),
                result.testCase(),
                adminEmail(payload),
                Instant.now());
        links.record(link);
        log.info("tsanet_audit event=case_submitted token={} workspace={} conversation={} ticket={}"
                        + " partner={} test={}",
                link.token(), link.workspaceId(), link.conversationId(), link.ticketId(),
                link.partnerDisplay(), link.testCase());
        boolean queued = queue.offer(new InboundEvent(InboundEvent.Source.CONNECTOR,
                AnnotationHandler.KIND, link.token(), Instant.now(),
                Map.of("token", link.token(), "attempt", 1)));
        if (!queued) {
            // The audit line + link table are the manual re-drive path (D10); a full
            // queue must never eat an annotation silently.
            log.error("tsanet_audit event=annotation_enqueue_failed token={} ticket={}",
                    link.token(), link.ticketId());
        }
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nestedId(Object o) {
        return o instanceof Map<?, ?> map && map.get("id") != null ? String.valueOf(map.get("id")) : null;
    }

    private static String adminEmail(Map<String, Object> payload) {
        return payload.get("admin") instanceof Map<?, ?> admin && admin.get("email") != null
                ? String.valueOf(admin.get("email")) : null;
    }

    // ---- plumbing ----

    private SubmissionDraft draftFrom(OutboundFlowState state, Map<String, Object> payload) {
        // Only fields on the CURRENT form may ride into the submission; input_values is
        // client-supplied and values can hold residue from earlier canvases.
        Set<String> formKeys = state.form().fields().stream()
                .map(f -> "f_" + f.fieldId())
                .collect(Collectors.toSet());
        Map<String, String> fieldValues = state.values().entrySet().stream()
                .filter(e -> formKeys.contains(e.getKey()))
                .collect(Collectors.toMap(e -> e.getKey().substring(2), Map.Entry::getValue));
        Map<?, ?> admin = payload.get("admin") instanceof Map<?, ?> m ? m : Map.of();
        Object name = admin.get("name");
        Object email = admin.get("email");
        return new SubmissionDraft(
                state.flowId(),
                state.form().documentId(),
                state.values().get("summary"),
                state.values().get("description"),
                fieldValues,
                name == null ? "" : String.valueOf(name),
                email == null ? "" : String.valueOf(email));
    }

    private static Map<String, Object> pointer(OutboundFlowState state) {
        return Map.of("flow_id", state.flowId());
    }

    private static String surface(Map<String, Object> payload) {
        return payload.get("ticket") != null ? "ticket" : "conversation";
    }

    private static String storedFlowId(Map<String, Object> payload) {
        if (payload.get("current_canvas") instanceof Map<?, ?> canvas
                && canvas.get("stored_data") instanceof Map<?, ?> stored
                && stored.get("flow_id") != null) {
            return String.valueOf(stored.get("flow_id"));
        }
        return null;
    }

    private static Map<String, String> inputValues(Map<String, Object> payload) {
        if (payload.get("input_values") instanceof Map<?, ?> raw) {
            return raw.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .collect(Collectors.toMap(e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue())));
        }
        return Map.of();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
