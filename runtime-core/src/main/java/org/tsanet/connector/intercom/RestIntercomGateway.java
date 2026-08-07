package org.tsanet.connector.intercom;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Intercom REST client for the annotation pipeline. Write shapes are the ones the
 * 2026-08-04 spike verified live on the dev workspace (docs/decisions.md D5): custom
 * ticket attributes are keyed by attribute NAME via PUT /tickets/{id}; admin notes are
 * POST /tickets/{id}/reply {message_type: note}; datetime attributes take epoch
 * seconds (probed 2026-08-05, round-tripped). D3 rule baked in: after the attribute
 * write, the ticket is read back and the token must be present — Intercom answers 200
 * while silently ignoring unknown fields, so the write is not done until it reads back.
 *
 * <p>If the full attribute write is rejected 4xx, ONE degraded retry drops the
 * "Respond By" field (the isolated format risk) so the token — the field Phase 2
 * routes on — still lands; the note carries everything either way.
 */
public class RestIntercomGateway implements IntercomGateway {

    private static final Logger log = LoggerFactory.getLogger(RestIntercomGateway.class);

    static final String ATTR_TOKEN = "TSANet Token";
    static final String ATTR_PARTNER = "Partner";
    static final String ATTR_RESPOND_BY = "Respond By";

    private final RestTemplate http;
    private final String baseUrl;
    private final String adminId;

    RestIntercomGateway(RestTemplate http, String baseUrl, String adminId) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.adminId = adminId;
    }

    public static RestIntercomGateway create(String baseUrl, String accessToken, String configuredAdminId) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1_000);
        factory.setReadTimeout(3_000);
        RestTemplate http = new RestTemplate(new BufferingClientHttpRequestFactory(factory));
        http.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().setBearerAuth(accessToken);
            request.getHeaders().set("Accept", "application/json");
            return execution.execute(request, body);
        });
        String adminId = configuredAdminId;
        if (adminId == null || adminId.isBlank()) {
            // One less knob to misconfigure: the token's own admin identity works for
            // authoring notes. Resolved once at wiring time.
            Map<?, ?> me = http.getForObject(baseUrl + "/me", Map.class);
            Object id = me == null ? null : me.get("id");
            if (id == null) {
                throw new IntercomException("GET /me returned no admin id; set connector.intercom.admin-id");
            }
            adminId = String.valueOf(id);
        }
        return new RestIntercomGateway(http, baseUrl, adminId);
    }

    @Override
    public AnnotationOutcome annotate(SubmissionLink link) {
        if (link.ticketAnchored()) {
            AnnotationOutcome outcome = writeTicketAttributes(link);
            verifyTokenLanded(link);
            postNote("/tickets/" + link.ticketId() + "/reply", link);
            return outcome;
        }
        postNote("/conversations/" + link.conversationId() + "/reply", link);
        return AnnotationOutcome.FULL;
    }

    private AnnotationOutcome writeTicketAttributes(SubmissionLink link) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put(ATTR_TOKEN, link.token());
        attrs.put(ATTR_PARTNER, link.partnerDisplay());
        Long respondByEpoch = toEpochSeconds(link.respondBy());
        boolean parseDropped = link.respondBy() != null && !link.respondBy().isBlank()
                && respondByEpoch == null;
        if (respondByEpoch != null) {
            attrs.put(ATTR_RESPOND_BY, respondByEpoch);
        }
        try {
            put(link.ticketId(), attrs);
            if (parseDropped) {
                // A respond-by the case CARRIES but we could not parse is the
                // wire-format-changed signal DEGRADED exists for — same outcome and
                // same audit line whether the drop happened client- or server-side.
                log.warn("tsanet_audit event=annotation_degraded ticket={} dropped={} reason=unparseable",
                        link.ticketId(), ATTR_RESPOND_BY);
                return AnnotationOutcome.DEGRADED;
            }
            // ABSENT respond-by means nothing applicable was dropped: FULL.
            return AnnotationOutcome.FULL;
        } catch (HttpClientErrorException rejected) {
            if (respondByEpoch == null) {
                throw fail("attributes", rejected);
            }
            log.warn("tsanet_audit event=annotation_degraded ticket={} dropped={} status={}",
                    link.ticketId(), ATTR_RESPOND_BY, rejected.getStatusCode().value());
            attrs.remove(ATTR_RESPOND_BY);
            try {
                put(link.ticketId(), attrs);
                return AnnotationOutcome.DEGRADED;
            } catch (RestClientResponseException stillRejected) {
                throw fail("attributes-degraded", stillRejected);
            }
        } catch (RestClientResponseException error) {
            throw fail("attributes", error);
        }
    }

    private void put(String ticketId, Map<String, Object> attrs) {
        http.put(baseUrl + "/tickets/" + ticketId, Map.of("ticket_attributes", attrs));
    }

    /** D3: a 200 means nothing until the value reads back. */
    private void verifyTokenLanded(SubmissionLink link) {
        Map<?, ?> ticket;
        try {
            ticket = http.getForObject(baseUrl + "/tickets/" + link.ticketId(), Map.class);
        } catch (RestClientResponseException e) {
            throw fail("read-back", e);
        }
        Object attrs = ticket == null ? null : ticket.get("ticket_attributes");
        Object token = attrs instanceof Map<?, ?> map ? map.get(ATTR_TOKEN) : null;
        if (!link.token().equals(token)) {
            throw new IntercomException("attribute write did not read back (ticket " + link.ticketId()
                    + "); the workspace may have renamed the '" + ATTR_TOKEN + "' attribute");
        }
    }

    private void postNote(String path, SubmissionLink link) {
        try {
            http.postForObject(baseUrl + path, Map.of(
                    "message_type", "note",
                    "type", "admin",
                    "admin_id", adminId,
                    "body", noteHtml(link)), Map.class);
        } catch (RestClientResponseException e) {
            throw fail("note", e);
        }
    }

    static String noteHtml(SubmissionLink link) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p>TSANet collaboration case opened with ").append(escape(link.partnerDisplay()))
                .append(".</p><p>Token: ").append(escape(link.token()));
        if (link.respondBy() != null) {
            sb.append("<br/>Partner respond-by: ").append(escape(link.respondBy()));
        }
        if (link.testCase()) {
            sb.append("<br/>Test submission: no partner SLA or notifications.");
        }
        if (link.submittedBy() != null && !link.submittedBy().isBlank()) {
            sb.append("<br/>Submitted by: ").append(escape(link.submittedBy()));
        }
        sb.append("</p>");
        return sb.toString();
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static Long toEpochSeconds(String respondBy) {
        if (respondBy == null || respondBy.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(respondBy).toEpochSecond();
        } catch (RuntimeException notIso) {
            // A format surprise must degrade the annotation, never fail it.
            return null;
        }
    }

    @Override
    public boolean healthy() {
        try {
            return http.getForObject(baseUrl + "/me", Map.class) != null;
        } catch (RuntimeException e) {
            log.warn("intercom_unhealthy reason={}", e.getMessage());
            return false;
        }
    }

    /**
     * Contact used for every inbound ticket. Tickets require exactly one contact even
     * for Back-office types (D5), and the partner is not an Intercom user, so a stable
     * synthetic one stands in. Found by external_id before create so redeliveries and
     * later cases reuse it rather than littering the workspace with duplicates.
     */
    static final String SYNTHETIC_CONTACT_EXTERNAL_ID = "tsanet-partner";
    static final String SYNTHETIC_CONTACT_EMAIL = "tsanet-partner@example.invalid";

    @Override
    public CreatedTicket createCollaborationTicket(InboundTicketRequest request) {
        String contactId = syntheticContactId();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("_default_title_", title(request));
        attrs.put("_default_description_", request.description());
        attrs.put(ATTR_TOKEN, request.token());
        attrs.put(ATTR_PARTNER, request.partnerCompany());
        Long respondByEpoch = toEpochSeconds(request.respondBy());
        if (respondByEpoch != null) {
            attrs.put(ATTR_RESPOND_BY, respondByEpoch);
        }
        Map<String, Object> body = Map.of(
                "ticket_type_id", request.ticketTypeId(),
                "contacts", List.of(Map.of("id", contactId)),
                "ticket_attributes", attrs);
        Map<?, ?> created;
        try {
            created = http.postForObject(baseUrl + "/tickets", body, Map.class);
        } catch (RestClientResponseException e) {
            throw fail("ticket-create", e);
        }
        Object id = created == null ? null : created.get("id");
        if (id == null) {
            // No id means no anchor, and the caller must be able to release its claim
            // and let a redelivery retry — reporting success here would strand the
            // case with a claimed token and no ticket.
            throw new IntercomException("Intercom accepted the ticket create but returned no id");
        }
        String ticketId = String.valueOf(id);
        verifyTokenLanded(ticketId, request.token());
        // The agent-facing number, captured HERE because it is only in the create
        // response and a later read would cost a round trip. Absent means we quote
        // nothing rather than quoting the API handle, which no agent can search.
        Object number = created.get("ticket_id");
        log.info("tsanet_audit event=inbound_ticket_created ticket={} number={} token={}"
                        + " partner={} test={}",
                ticketId, number, request.token(), request.partnerCompany(), request.testCase());
        return new CreatedTicket(ticketId, number == null ? null : String.valueOf(number));
    }

    /** A test case must be unmistakable on the agent's screen, not just in an attribute. */
    private static String title(InboundTicketRequest request) {
        String summary = request.summary() == null || request.summary().isBlank()
                ? "TSANet collaboration request"
                : request.summary();
        return request.testCase() ? "[TEST] " + summary : summary;
    }

    /**
     * The synthetic contact, created once and reused. Search-then-create races only
     * with itself: a duplicate contact is untidy, never wrong, and the ticket claim
     * upstream is what guarantees one ticket per case.
     */
    private String syntheticContactId() {
        try {
            Map<?, ?> found = http.postForObject(baseUrl + "/contacts/search", Map.of(
                    "query", Map.of("field", "external_id", "operator", "=",
                            "value", SYNTHETIC_CONTACT_EXTERNAL_ID)), Map.class);
            Object data = found == null ? null : found.get("data");
            if (data instanceof List<?> rows && !rows.isEmpty()
                    && rows.get(0) instanceof Map<?, ?> row && row.get("id") != null) {
                return String.valueOf(row.get("id"));
            }
        } catch (RestClientResponseException e) {
            // A failed search is not a failed create: fall through and let the create
            // decide. Intercom rejects a duplicate external_id, which surfaces there.
            log.warn("intercom_http_error op=contact-search status={}", e.getStatusCode().value());
        }
        Map<?, ?> created;
        try {
            created = http.postForObject(baseUrl + "/contacts", Map.of(
                    "role", "user",
                    "external_id", SYNTHETIC_CONTACT_EXTERNAL_ID,
                    "email", SYNTHETIC_CONTACT_EMAIL,
                    "name", "TSANet Partner (synthetic)"), Map.class);
        } catch (RestClientResponseException e) {
            throw fail("contact-create", e);
        }
        Object id = created == null ? null : created.get("id");
        if (id == null) {
            throw new IntercomException("Intercom accepted the contact create but returned no id");
        }
        return String.valueOf(id);
    }

    /** D3 again: the create's 200 means nothing until the token reads back. */
    private void verifyTokenLanded(String ticketId, String token) {
        Map<?, ?> ticket;
        try {
            ticket = http.getForObject(baseUrl + "/tickets/" + ticketId, Map.class);
        } catch (RestClientResponseException e) {
            throw fail("ticket-read-back", e);
        }
        Object attrs = ticket == null ? null : ticket.get("ticket_attributes");
        Object landed = attrs instanceof Map<?, ?> map ? map.get(ATTR_TOKEN) : null;
        // Compare as text: a JSON layer that coerces the value to a non-String would
        // otherwise fail this read-back, release the claim, and loop redelivery
        // forever — the safety net becoming the outage.
        if (landed == null || !token.equals(String.valueOf(landed))) {
            throw new IntercomException("ticket " + ticketId + " was created but the '" + ATTR_TOKEN
                    + "' attribute did not read back; the workspace may have renamed it");
        }
    }

    private IntercomException fail(String op, RestClientResponseException e) {
        log.warn("intercom_http_error op={} status={}", op, e.getStatusCode().value());
        return new IntercomException("Intercom rejected the " + op + " write (HTTP "
                + e.getStatusCode().value() + ")");
    }
}
