package org.tsanet.connector.inbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tsanet.connector.canvas.CanvasKit;
import org.tsanet.connector.intercom.CaseState;
import org.tsanet.connector.intercom.SubmissionLink;
import org.tsanet.connector.intercom.SubmissionLinkTable;
import org.tsanet.connector.tsanet.GatewayException;
import org.tsanet.connector.tsanet.GatewayTimeoutException;
import org.tsanet.connector.tsanet.TsanetGateway;
import org.tsanet.connector.tsanet.dto.EngineerIdentity;

/**
 * The agent's view of an INBOUND partner case, and the three ways to answer it.
 *
 * <p>Rendered from the link's recorded {@link CaseState}, never from what was last
 * clicked: a second panel open on the same ticket shows what actually holds. Every
 * action moves the state by compare-and-set before calling the partner API, so two
 * panels cannot both answer — the loser re-renders instead.
 *
 * <p>The engineer identity defaults to the acting teammate but stays editable, because
 * the member's registered domains are not knowable here. When the API rejects an
 * off-domain address, its problem+json detail is rendered beside the email field and
 * the form keeps what was typed, so the fix is one correction and a resubmit.
 */
@Component
public class InboundPanel {

    private static final Logger log = LoggerFactory.getLogger(InboundPanel.class);

    static final String ACCEPT = "inbound_accept";
    static final String REJECT = "inbound_reject";
    static final String INFO = "inbound_info";
    static final String SUBMIT_ACCEPT = "inbound_accept_submit";
    static final String SUBMIT_REJECT = "inbound_reject_submit";
    static final String SUBMIT_INFO = "inbound_info_submit";
    static final String BACK = "inbound_back";
    static final String FIELD_NAME = "engineer_name";
    static final String FIELD_EMAIL = "engineer_email";
    static final String FIELD_PHONE = "engineer_phone";
    static final String FIELD_TEXT = "response_text";

    private final SubmissionLinkTable links;
    private final TsanetGateway tsanet;

    public InboundPanel(SubmissionLinkTable links, TsanetGateway tsanet) {
        this.links = links;
        this.tsanet = tsanet;
    }

    /** True when this component id belongs to the inbound panel. */
    public static boolean owns(String componentId) {
        return componentId != null && componentId.startsWith("inbound_");
    }

    /** The case view for this ticket, or empty when the ticket is not an inbound case. */
    public Optional<Map<String, Object>> render(Map<String, Object> payload) {
        return link(payload).map(link -> statusCanvas(link, null));
    }

    /** Handles an inbound component, or empty when the ticket is no longer routable. */
    public Optional<Map<String, Object>> submit(Map<String, Object> payload, String componentId,
            Map<String, String> inputs) {
        Optional<SubmissionLink> found = link(payload);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SubmissionLink link = found.get();
        return Optional.of(switch (componentId) {
            case ACCEPT -> formCanvas(link, ACCEPT, "Accept this collaboration",
                    "Next steps for the partner (required)", payload, inputs, null);
            case REJECT -> formCanvas(link, REJECT, "Decline this collaboration",
                    "Reason the partner will read (required)", payload, inputs, null);
            case INFO -> formCanvas(link, INFO, "Request more information",
                    "What do you need from the partner? (required)", payload, inputs, null);
            case SUBMIT_ACCEPT -> respond(link, ACCEPT, payload, inputs);
            case SUBMIT_REJECT -> respond(link, REJECT, payload, inputs);
            case SUBMIT_INFO -> respond(link, INFO, payload, inputs);
            default -> statusCanvas(link, null);
        });
    }

    private Optional<SubmissionLink> link(Map<String, Object> payload) {
        String ticketId = nestedId(payload.get("ticket"));
        if (ticketId == null) {
            return Optional.empty();
        }
        // Only cases WE anchored as inbound tickets get this panel; an outbound
        // submission's own support ticket keeps the partner-search flow.
        return links.byTicketId(ticketId)
                .filter(l -> l.internalCaseNumber() != null
                        && l.internalCaseNumber().startsWith(InboundRequestHandler.INBOUND_INTERNAL_PREFIX));
    }

    private Map<String, Object> statusCanvas(SubmissionLink link, String notice) {
        CaseState state = links.caseState(link.token());
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(CanvasKit.header("Partner collaboration"));
        if (notice != null) {
            components.add(CanvasKit.error(notice));
        }
        components.add(CanvasKit.muted("From: " + orDash(link.partnerDisplay())));
        if (link.respondBy() != null && !link.respondBy().isBlank()) {
            components.add(CanvasKit.muted("Respond by: " + link.respondBy()));
        }
        if (link.testCase()) {
            components.add(CanvasKit.muted("Test case: no partner SLA or notifications."));
        }
        components.add(CanvasKit.muted("Status: " + label(state)
                + (link.caseNumber() == null ? "" : " (our ref " + link.caseNumber() + ")")));
        components.add(CanvasKit.divider());
        if (state == CaseState.PENDING) {
            components.add(CanvasKit.button(ACCEPT, "Accept"));
            components.add(CanvasKit.button(INFO, "Request information"));
            components.add(CanvasKit.button(REJECT, "Decline"));
        } else {
            // Answered cases offer nothing: a second response would reach the partner
            // twice, and notes/closure are later slices.
            components.add(CanvasKit.muted("This case has been answered."
                    + " Replies and closure arrive in a later release."));
        }
        return CanvasKit.canvas(components, Map.of());
    }

    private Map<String, Object> formCanvas(SubmissionLink link, String action, String title,
            String textLabel, Map<String, Object> payload, Map<String, String> inputs, String error) {
        EngineerIdentity engineer = engineer(payload, inputs);
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(CanvasKit.header(title));
        components.add(CanvasKit.muted("Partner: " + orDash(link.partnerDisplay())));
        components.add(CanvasKit.input(FIELD_NAME, "Your name (required)", null, engineer.name()));
        components.add(CanvasKit.input(FIELD_EMAIL, "Your email (required)", null, engineer.email()));
        if (error != null) {
            // Beside the email field on purpose: the email-domain rule is the rejection
            // an agent can actually act on, and it is unreadable from a status code.
            components.add(CanvasKit.error(error));
        }
        components.add(CanvasKit.input(FIELD_PHONE, "Your phone", null, engineer.phone()));
        components.add(CanvasKit.textarea(FIELD_TEXT, textLabel, null, inputs.get(FIELD_TEXT)));
        components.add(CanvasKit.divider());
        components.add(CanvasKit.button(submitIdFor(action), title));
        components.add(CanvasKit.button(BACK, "Back"));
        return CanvasKit.canvas(components, Map.of());
    }

    private Map<String, Object> respond(SubmissionLink link, String action,
            Map<String, Object> payload, Map<String, String> inputs) {
        EngineerIdentity engineer = engineer(payload, inputs);
        String text = inputs.getOrDefault(FIELD_TEXT, "");
        List<String> missing = new ArrayList<>();
        if (blank(engineer.name())) {
            missing.add("your name");
        }
        if (blank(engineer.email())) {
            missing.add("your email");
        }
        if (blank(text)) {
            missing.add("the message to the partner");
        }
        if (!missing.isEmpty()) {
            return formCanvas(link, action, titleFor(action), textLabelFor(action), payload, inputs,
                    "Missing required: " + String.join(", ", missing));
        }

        CaseState next = stateFor(action);
        // Claim the transition BEFORE calling the partner: two panels open on one
        // ticket must not both answer, and the loser re-renders what holds.
        if (!links.caseState(link.token(), CaseState.PENDING, next)) {
            log.info("tsanet_audit event=inbound_response_superseded token={} attempted={}",
                    link.token(), action);
            return statusCanvas(link, "This case was already answered in another panel.");
        }
        try {
            switch (action) {
                case ACCEPT -> {
                    String caseNumber = link.caseNumber() == null ? link.ticketId() : link.caseNumber();
                    tsanet.approve(link.token(), engineer, caseNumber, text);
                    links.caseNumber(link.token(), caseNumber);
                }
                case REJECT -> tsanet.reject(link.token(), engineer, text);
                default -> tsanet.requestInformation(link.token(), engineer, text);
            }
        } catch (GatewayTimeoutException ambiguous) {
            // The partner may already have it. The state stays moved on purpose:
            // offering the action again is what would answer twice.
            log.warn("tsanet_audit event=inbound_response_unresolved token={} action={}",
                    link.token(), action);
            return statusCanvas(link, "The response may or may not have reached the partner."
                    + " Check TSANet Connect before acting again.");
        } catch (GatewayException rejected) {
            // Definitive: nothing reached the partner, so give the state back and let
            // them fix it. This is where the email-domain rejection lands.
            links.caseState(link.token(), next, CaseState.PENDING);
            log.warn("tsanet_audit event=inbound_response_rejected token={} action={}",
                    link.token(), action);
            return formCanvas(link, action, titleFor(action), textLabelFor(action), payload, inputs,
                    rejected.getMessage());
        }
        log.info("tsanet_audit event=inbound_response_sent token={} action={} engineer={}",
                link.token(), action, engineer.email());
        return statusCanvas(links.byToken(link.token()).orElse(link), null);
    }

    /** Typed values win over the teammate default, so a correction survives a re-render. */
    private static EngineerIdentity engineer(Map<String, Object> payload, Map<String, String> inputs) {
        Map<?, ?> admin = payload.get("admin") instanceof Map<?, ?> m ? m : Map.of();
        return new EngineerIdentity(
                firstNonBlank(inputs.get(FIELD_NAME), string(admin.get("name"))),
                firstNonBlank(inputs.get(FIELD_EMAIL), string(admin.get("email"))),
                inputs.get(FIELD_PHONE));
    }

    private static String submitIdFor(String action) {
        return switch (action) {
            case ACCEPT -> SUBMIT_ACCEPT;
            case REJECT -> SUBMIT_REJECT;
            default -> SUBMIT_INFO;
        };
    }

    private static String titleFor(String action) {
        return switch (action) {
            case ACCEPT -> "Accept this collaboration";
            case REJECT -> "Decline this collaboration";
            default -> "Request more information";
        };
    }

    private static String textLabelFor(String action) {
        return switch (action) {
            case ACCEPT -> "Next steps for the partner (required)";
            case REJECT -> "Reason the partner will read (required)";
            default -> "What do you need from the partner? (required)";
        };
    }

    private static CaseState stateFor(String action) {
        return switch (action) {
            case ACCEPT -> CaseState.ACCEPTED;
            case REJECT -> CaseState.REJECTED;
            default -> CaseState.INFO_REQUESTED;
        };
    }

    private static String label(CaseState state) {
        return switch (state) {
            case PENDING -> "awaiting your response";
            case ACCEPTED -> "accepted";
            case REJECTED -> "declined";
            case INFO_REQUESTED -> "information requested";
        };
    }

    private static String nestedId(Object o) {
        return o instanceof Map<?, ?> map && map.get("id") != null ? String.valueOf(map.get("id")) : null;
    }

    private static String string(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
