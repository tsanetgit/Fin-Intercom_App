package org.tsanet.connector.canvas;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final org.tsanet.connector.inbound.InboundPanel inbound;
    private final org.tsanet.connector.config.ConnectorProperties properties;

    public CanvasController(FlowStateStore flows, TsanetGateway gateway, SubmissionLinkTable links,
            EventQueue queue, org.tsanet.connector.inbound.InboundPanel inbound,
            org.tsanet.connector.config.ConnectorProperties properties) {
        this.flows = flows;
        this.gateway = gateway;
        this.links = links;
        this.queue = queue;
        this.inbound = inbound;
        this.properties = properties;
    }

    @PostMapping("/canvas/initialize")
    public Map<String, Object> initialize(@RequestBody Map<String, Object> payload) {
        // A ticket we created for a partner's case shows that case; everything else
        // gets the outbound partner search — unless tickets-only mode (#65) is on and
        // this is a plain conversation, which gets the static refusal instead of a
        // flow. The guard lives in the orElseGet so inbound rendering is untouched,
        // and no flow is ever created for a refused surface.
        return inbound.render(payload).orElseGet(() ->
                properties.ticketsOnly() && "conversation".equals(surface(payload))
                        ? ticketsOnlyNotice()
                        : home(flows.create(), surface(payload), null));
    }

    @PostMapping("/canvas/submit")
    public Map<String, Object> submit(@RequestBody Map<String, Object> payload) {
        String surface = surface(payload);
        String component = String.valueOf(payload.getOrDefault("component_id", ""));
        Map<String, String> inputs = inputValues(payload);

        // BEFORE the flow lookup, not after: inbound components never have (or need) an
        // outbound flow — InboundPanel canvases store no flow pointer at all — so
        // resolving the flow first sent every live inbound click to the expired-session
        // home and the panel's buttons were unreachable (#33). The id namespaces are
        // disjoint (inbound_* vs the outbound set), so nothing outbound can be shadowed.
        if (org.tsanet.connector.inbound.InboundPanel.owns(component)) {
            Optional<Map<String, Object>> answered = inbound.submit(payload, component, inputs);
            if (answered.isPresent()) {
                return answered.get();
            }
        }

        // Tickets-only mode (#65): every OUTBOUND component on a conversation surface
        // gets the refusal — including stale panels and check_status on pre-existing
        // conversation flows, deliberately (the flag is deploy-time; a workspace that
        // turned it on wants no conversation-side interaction, and the guard mutates
        // nothing, so turning the flag off restores every flow untouched). Inbound
        // components were dispatched above and are never affected.
        if (properties.ticketsOnly() && "conversation".equals(surface)) {
            return ticketsOnlyNotice();
        }

        OutboundFlowState state = flows.find(storedFlowId(payload));
        if (state == null) {
            return home(flows.create(), surface, "That panel session expired; starting over.");
        }

        if ("back_home".equals(component)) {
            Status status = state.status();
            if (status == Status.SUBMITTING || status == Status.UNRESOLVED) {
                // Starting over here would mint a fresh flow id == fresh
                // internalCaseNumber and orphan the possibly-created case.
                return renderByStatus(state, payload, surface, null);
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
            return renderByStatus(state, payload, surface, null);
        }
        if ("search".equals(component)) {
            return doSearch(state, surface, inputs.getOrDefault("q", "").trim());
        }
        if (component.startsWith("select:")) {
            return doSelect(state, payload, surface, component.substring("select:".length()));
        }
        if ("submit_case".equals(component) || "retry_submit".equals(component)) {
            return doSubmit(state, payload, inputs, "retry_submit".equals(component));
        }
        // Unknown component (live Canvas Kit shapes may surprise us, D8): re-render the
        // current step rather than destroying the flow and the agent's typed work.
        return renderByStatus(state, payload, surface, null);
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

    private Map<String, Object> doSelect(OutboundFlowState state, Map<String, Object> payload,
            String surface, String key) {
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
        // Detected and logged, NOT refused (#49). Canvas Kit bounds a select at 2..11
        // options; what Intercom does past that is not yet observed, so refusing here
        // could break a partner form that renders fine today. This line is the signal
        // that tells us whether any real partner form is out of range at all — the
        // question the render probe has to answer before this can become a refusal.
        List<FormField> outOfRange = form.optionBoundViolations();
        if (!outOfRange.isEmpty()) {
            String detail = outOfRange.stream()
                    .map(f -> f.label() + "[" + f.optionCount() + "]")
                    .collect(Collectors.joining(", "));
            log.warn("tsanet_audit event=form_option_bound_exceeded partner={} fields={}"
                    + " bound={}..{} action=rendered_anyway",
                    partner.display(), detail,
                    FormField.MIN_SELECT_OPTIONS, FormField.MAX_SELECT_OPTIONS);
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
            return formCanvas(state, payload, null);
        }
    }

    private static boolean sameTarget(PartnerHit a, PartnerHit b) {
        return Objects.equals(a.companyId(), b.companyId())
                && Objects.equals(a.departmentId(), b.departmentId());
    }

    /** Reserved input ids on the outbound form; the f_ prefix keeps real form fields clear of them. */
    static final String FIELD_SUBMITTER_NAME = "submitter_name";
    static final String FIELD_SUBMITTER_EMAIL = "submitter_email";

    private Map<String, Object> formCanvas(OutboundFlowState state, Map<String, Object> payload, String notice) {
        ProcessForm form = state.form();
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(CanvasKit.header(state.partner().display()));
        if (notice != null) {
            components.add(CanvasKit.error(notice));
        }
        // Prefilled from the acting teammate but editable (D19; the inbound engineer
        // identity's rule): the Connect API rejects submitter emails off the member's
        // registered domain, and the domains are not knowable here. The notice slot
        // above is where that rejection renders — directly beside the fields that fix it.
        components.add(CanvasKit.input(FIELD_SUBMITTER_NAME, "Your name (required)", null,
                submitterName(state, payload)));
        components.add(CanvasKit.input(FIELD_SUBMITTER_EMAIL, "Your email (required)", null,
                submitterEmail(state, payload)));
        addNoteComponents(components, "Partner note: ", form.adminNote());
        addNoteComponents(components, "Escalation: ", form.escalationInstructions());
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
                components.add(CanvasKit.dropdown(id, label, field.options(), state.values().get(id)));
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

    // ---- partner note HTML -> canvas text ----
    // TSANet process forms deliver adminNote/escalationInstructions as partner-authored
    // HTML; Intercom canvas text renders ONLY markdown links [text](url) and **bold**,
    // never HTML (Canvas Kit Text reference, verified live 2026-08-05). Converted at
    // this presentation boundary; the gateway keeps the fields verbatim.

    /** Payload guard, same motivation as MAX_RESULT_BUTTONS: one component per paragraph. */
    private static final int MAX_NOTE_PARAGRAPHS = 8;

    private static final Pattern DROP_WITH_CONTENT = Pattern.compile(
            "(?is)<script\\b[^>]*>.*?</script>|<style\\b[^>]*>.*?</style>|<!--.*?-->");
    private static final Pattern ANCHOR = Pattern.compile(
            "(?is)<a\\b[^>]*?href\\s*=\\s*(?:\"(?<dq>[^\"]*)\"|'(?<sq>[^']*)'|(?<uq>[^\\s>]+))[^>]*>(?<text>.*?)</a>");
    private static final Pattern BREAK_TAGS = Pattern.compile(
            "(?i)</?(?:p|br|li|div|tr|ul|ol|table|h[1-6])\\b[^>]*/?>");
    private static final Pattern BOLD_OPEN = Pattern.compile("(?i)<(?:strong|b)\\b[^>]*>");
    private static final Pattern BOLD_CLOSE = Pattern.compile("(?i)</(?:strong|b)\\s*>");
    private static final Pattern OTHER_TAGS = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]{1,6}|[0-9]{1,7});");
    /**
     * The character-class half of parity with SdkTsanetGateway.problemDetail's
     * cleanup (neutralizeBrackets carries the bracket half): invisible formatting
     * and control characters (U+202E RTL override, zero-width spaces, line/para
     * separators, C1 controls — (?U) keeps Cntrl from collapsing to ASCII) must
     * not reach an agent card, where they can visually reorder an escalation
     * note. \r\n\t are excluded only because the next normalization turns them
     * into spaces and \n is later reserved as the break sentinel.
     */
    private static final Pattern FORMAT_CHARS = Pattern.compile(
            "(?U)[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}&&[^\\r\\n\\t]]+");

    private static void addNoteComponents(List<Map<String, Object>> components, String prefix, String html) {
        List<String> paragraphs = htmlNoteParagraphs(html);
        for (int i = 0; i < paragraphs.size(); i++) {
            // Prefix only the first paragraph; an empty conversion emits nothing at
            // all (a raw-non-blank note like <p>&nbsp;</p> must not render a bare label).
            components.add(CanvasKit.muted(i == 0 ? prefix + paragraphs.get(i) : paragraphs.get(i)));
        }
    }

    /**
     * Ordering is load-bearing: tags are handled before entities so decoded
     * markup stays literal text, and entities before bracket neutralization so
     * &#91;x&#93;(url) cannot smuggle live link syntax past the scheme check.
     * Generated links are substituted last, after neutralization, so they are
     * the only markdown link syntax that can reach the canvas.
     */
    static List<String> htmlNoteParagraphs(String html) {
        if (html == null) {
            return List.of();
        }
        // Identity fast-path: plain text has nothing to convert or neutralize.
        // Raw newlines/tabs disqualify so every output honors the one-paragraph-
        // per-line invariant the slow path establishes; formatting/control
        // characters (NUL included) disqualify so the strip below cannot be
        // bypassed by skipping the slow path.
        if (html.indexOf('<') < 0 && html.indexOf('&') < 0
                && html.indexOf('[') < 0 && html.indexOf(']') < 0
                && html.indexOf('\n') < 0 && html.indexOf('\r') < 0 && html.indexOf('\t') < 0
                && !FORMAT_CHARS.matcher(html).find()) {
            String trimmed = html.trim();
            return trimmed.isEmpty() ? List.of() : List.of(trimmed);
        }
        // NUL is the placeholder alphabet below; partner text must not supply it.
        String s = html.replace("\u0000", "");
        // Before any sentinel exists, so hrefs and anchor text are covered too.
        s = FORMAT_CHARS.matcher(s).replaceAll(" ");
        s = DROP_WITH_CONTENT.matcher(s).replaceAll(" ");
        // Raw newlines are HTML whitespace; \n is reserved as the break sentinel.
        s = s.replaceAll("[\\r\\n\\t]+", " ");
        List<String> links = new ArrayList<>();
        Matcher anchor = ANCHOR.matcher(s);
        StringBuilder rewritten = new StringBuilder();
        while (anchor.find()) {
            String href = anchor.group("dq") != null ? anchor.group("dq")
                    : anchor.group("sq") != null ? anchor.group("sq") : anchor.group("uq");
            links.add(markdownLink(href, cleanLinkText(anchor.group("text"))));
            anchor.appendReplacement(rewritten,
                    Matcher.quoteReplacement("\u0000L" + (links.size() - 1) + "\u0000"));
        }
        anchor.appendTail(rewritten);
        s = rewritten.toString();
        s = BREAK_TAGS.matcher(s).replaceAll("\n");
        s = BOLD_OPEN.matcher(s).replaceAll("\u0000B\u0000");
        s = BOLD_CLOSE.matcher(s).replaceAll("\u0000/B\u0000");
        s = OTHER_TAGS.matcher(s).replaceAll(" ");
        s = decodeEntities(s);
        s = neutralizeBrackets(s);
        // Whitespace hugging a bold delimiter (e.g. <strong>x:&nbsp;&nbsp;</strong>)
        // moves outside the pair: a strict markdown parser will not close ** across a
        // space. An emptied pair is dropped rather than emitting a bare "****".
        s = s.replaceAll("\u0000B\u0000(\\s+)", "$1\u0000B\u0000")
                .replaceAll("(\\s+)\u0000/B\u0000", "\u0000/B\u0000$1")
                .replace("\u0000B\u0000\u0000/B\u0000", "")
                .replace("\u0000B\u0000", "**").replace("\u0000/B\u0000", "**");
        for (int i = 0; i < links.size(); i++) {
            s = s.replace("\u0000L" + i + "\u0000", links.get(i));
        }
        List<String> paragraphs = new ArrayList<>();
        for (String part : s.split("\n+")) {
            String paragraph = part.replaceAll("[ \\t]{2,}", " ").trim();
            if (!paragraph.isEmpty()) {
                paragraphs.add(paragraph);
            }
        }
        if (paragraphs.size() > MAX_NOTE_PARAGRAPHS) {
            int dropped = paragraphs.size() - MAX_NOTE_PARAGRAPHS;
            paragraphs = new ArrayList<>(paragraphs.subList(0, MAX_NOTE_PARAGRAPHS));
            paragraphs.add("(note truncated; " + dropped
                    + " more paragraph(s) in the TSANet web app)");
        }
        return paragraphs;
    }

    private static String markdownLink(String href, String text) {
        // Decode before the scheme check so &amp; in query strings survives and an
        // entity-obfuscated scheme fails closed against the allowlist.
        String url = decodeEntities(href == null ? "" : href).trim();
        String scheme = url.toLowerCase(Locale.ROOT);
        boolean allowed = scheme.startsWith("http://") || scheme.startsWith("https://")
                || scheme.startsWith("mailto:");
        if (!allowed) {
            // Partner-authored non-web scheme (javascript:, data:, ...): keep the
            // words, drop the target.
            return text;
        }
        if (text.isEmpty()) {
            text = neutralizeBrackets(url);
        }
        String target = suffixTarget(url, scheme);
        // These four break markdown link parsing mid-URL; any whitespace ends it —
        // including a newline minted by an entity like &#10;, so encode all of \s.
        // (?U) so Unicode spaces (U+00A0, U+3000) encode instead of riding raw.
        String encoded = url.replace("(", "%28").replace(")", "%29")
                .replace("[", "%5B").replace("]", "%5D").replaceAll("(?U)\\s", "%20");
        String link = "[" + text + "](" + encoded + ")";
        // QA design note on #6, Shawn's call: show where a link goes, because the
        // text and the target can diverge freely within the allowlist. Skipped when
        // the text already shows it (autolinks, bare-address mailtos).
        if (target.isEmpty() || text.equalsIgnoreCase(neutralizeBrackets(url))
                || text.equalsIgnoreCase(target)) {
            return link;
        }
        return link + " (" + target + ")";
    }

    /**
     * The agent-visible target for the link suffix: the host for http(s), the
     * address for mailto, empty for anything unparseable (never emit "()").
     * Userinfo splits on the LAST "@" — that is the anti-phish point:
     * https://microsoft.com@evil.example must show evil.example.
     */
    private static String suffixTarget(String url, String scheme) {
        if (scheme.startsWith("mailto:")) {
            String address = url.substring("mailto:".length());
            int query = address.indexOf('?');
            if (query >= 0) {
                address = address.substring(0, query);
            }
            return neutralizeBrackets(stripWhitespace(address));
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return "";
        }
        String authority = url.substring(schemeEnd + 3).split("[/?#]", 2)[0];
        int userinfo = authority.lastIndexOf('@');
        if (userinfo >= 0) {
            authority = authority.substring(userinfo + 1);
        }
        // Ports are noise for the deception check; the host is the point. Strip
        // only a real trailing port so a portless IPv6 literal keeps its colons.
        authority = authority.startsWith("[")
                ? authority.replaceFirst("(?<=\\]):\\d+$", "")
                : authority.replaceFirst(":\\d+$", "");
        return neutralizeBrackets(stripWhitespace(authority));
    }

    /** No whitespace may survive into the suffix: the target rides in body text,
     * where an entity-minted \n (&#10; is the one decodable control) would let
     * the final paragraph split truncate the very host this suffix exists to
     * show. (?U) is load-bearing — without it \s is ASCII-only and a U+00A0 or
     * U+3000 splits the suffix into two tokens, the deception class the suffix
     * exists to defeat (PR #8 QA round). Hosts and addresses contain no
     * legitimate whitespace, so strip. */
    private static String stripWhitespace(String s) {
        return s.replaceAll("(?U)\\s+", "");
    }

    private static String cleanLinkText(String inner) {
        String text = OTHER_TAGS.matcher(inner).replaceAll(" ");
        text = neutralizeBrackets(decodeEntities(text));
        // ALL whitespace collapses (link text is single-line by construction): an
        // entity-minted \n here would otherwise let the final paragraph split cut
        // the generated link apart — the exact thing the ordering rules promise
        // cannot happen.
        return text.replaceAll("\\s+", " ").trim();
    }

    /** Categories the FORMAT_CHARS strip removes; entities must not re-mint them. */
    private static boolean invisibleClass(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static String decodeEntities(String s) {
        Matcher numeric = NUMERIC_ENTITY.matcher(s);
        StringBuilder out = new StringBuilder();
        while (numeric.find()) {
            String digits = numeric.group(1);
            String replacement = "";
            try {
                int codePoint = digits.startsWith("x") || digits.startsWith("X")
                        ? Integer.parseInt(digits.substring(1), 16)
                        : Integer.parseInt(digits);
                // Surrogates stay excluded: isValidCodePoint(0xD800) is true but
                // toChars would emit a lone surrogate — ill-formed UTF-16 that can
                // break the canvas JSON downstream. Entities must not mint what the
                // raw-input FORMAT_CHARS strip removes (NUL and the placeholder
                // alphabet included, they are CONTROL); \n alone stays decodable so
                // &#10; keeps splitting body paragraphs. Refused-because-invisible
                // becomes a space, mirroring the raw strip, so &#9; layout does not
                // merge the words around it (PR #8 QA round); malformed and
                // surrogate entities still vanish entirely.
                if (Character.isValidCodePoint(codePoint)
                        && !(codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                    replacement = codePoint == '\n' || !invisibleClass(codePoint)
                            ? new String(Character.toChars(codePoint))
                            : " ";
                }
            } catch (NumberFormatException ignored) {
                // malformed entity: drop it
            }
            numeric.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        numeric.appendTail(out);
        // &amp; strictly last, one pass: &amp;lt; must yield "&lt;", never "<".
        return out.toString()
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /** The bracket half of the defense SdkTsanetGateway.problemDetail applies to
     * agent-visible text (FORMAT_CHARS carries the character-class half): literal
     * bracket syntax in untrusted text must not become a live markdown link. */
    private static String neutralizeBrackets(String s) {
        return s.replace("[", "(").replace("]", ")");
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
                // Resolved like the render prefill: a blank typed value with a present
                // teammate default is valid, matching the inbound engineer identity.
                if (blank(submitterName(state, payload))) {
                    missing.add("Your name");
                }
                if (blank(submitterEmail(state, payload))) {
                    missing.add("Your email");
                }
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
                    renderedUnderLock = formCanvas(state, payload, "Missing required: " + String.join(", ", missing));
                } else if (!state.beginSubmit()) {
                    // The one legal submit is claiming DRAFT -> SUBMITTING; losing the
                    // claim means another view already submitted (or the outcome is known).
                    renderedUnderLock = renderByStatus(state, payload, surface(payload), null);
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
            return formCanvas(state, payload, "Submission failed (" + e.getMessage() + "); fix and resubmit.");
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
                            default -> renderByStatus(state, payload, surface,
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

    private Map<String, Object> renderByStatus(OutboundFlowState state, Map<String, Object> payload,
            String surface, String notice) {
        return switch (state.status()) {
            case LANDED -> confirmation(state);
            case UNRESOLVED -> pendingCanvas(state);
            case SUBMITTING -> inProgressCanvas(state);
            case DRAFT -> state.form() != null ? formCanvas(state, payload, notice)
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

    /** The acting teammate's login, on purpose — the link audits who drove the
     * submission, while the draft's (possibly edited) submitter is what TSANet shows. */
    private static String adminEmail(Map<String, Object> payload) {
        return adminString(payload, "email");
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
        String name = submitterName(state, payload);
        String email = submitterEmail(state, payload);
        return new SubmissionDraft(
                state.flowId(),
                state.form().documentId(),
                state.values().get("summary"),
                state.values().get("description"),
                fieldValues,
                name == null ? "" : name,
                email == null ? "" : email,
                // The partner whose form this is: select sets the two as a pair under the
                // flow monitor, and doSubmit builds the draft inside it. The engine needs it
                // to pick the partner's encryption key (tsanetgit/Connect_Gateway#51); one
                // without end-to-end encryption ignores it.
                state.partner().companyId());
    }

    /**
     * Typed values win over the teammate default, so a correction survives a re-render
     * (the inbound engineer identity's rule, D19 for the outbound side). Reads the
     * typed value from server-side state, not the request's inputs, so the identity
     * also rides retry_submit — whose canvas has no input fields to echo it.
     */
    private static String submitterName(OutboundFlowState state, Map<String, Object> payload) {
        return firstNonBlank(state.values().get(FIELD_SUBMITTER_NAME), adminString(payload, "name"));
    }

    private static String submitterEmail(OutboundFlowState state, Map<String, Object> payload) {
        return firstNonBlank(state.values().get(FIELD_SUBMITTER_EMAIL), adminString(payload, "email"));
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String adminString(Map<String, Object> payload, String key) {
        return payload.get("admin") instanceof Map<?, ?> admin && admin.get(key) != null
                ? String.valueOf(admin.get(key)) : null;
    }

    private static Map<String, Object> pointer(OutboundFlowState state) {
        return Map.of("flow_id", state.flowId());
    }

    private static String surface(Map<String, Object> payload) {
        return payload.get("ticket") != null ? "ticket" : "conversation";
    }

    /**
     * Tickets-only refusal (#65): static, buttonless, and non-destructive — it neither
     * creates nor touches a flow, so disabling the flag restores everything as it was.
     * stored_data is an empty map, not null (CanvasKit.canvas is deliberately the only
     * builder and Map.of rejects nulls); nothing round-trips from a buttonless canvas.
     */
    private static Map<String, Object> ticketsOnlyNotice() {
        return CanvasKit.canvas(List.of(
                CanvasKit.header("Partner collaboration"),
                CanvasKit.muted("Partner collaboration is available from tickets only. "
                        + "Open or create a ticket to start a new collaboration.")),
                Map.of());
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
