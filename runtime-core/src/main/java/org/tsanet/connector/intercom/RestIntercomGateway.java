package org.tsanet.connector.intercom;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
        if (respondByEpoch != null) {
            attrs.put(ATTR_RESPOND_BY, respondByEpoch);
        }
        try {
            put(link.ticketId(), attrs);
            // No respond-by on the case (or unparseable) means nothing applicable was
            // dropped: that is FULL, not DEGRADED — degradation is strictly a write
            // that had to shed a field to get through.
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

    private IntercomException fail(String op, RestClientResponseException e) {
        log.warn("intercom_http_error op={} status={}", op, e.getStatusCode().value());
        return new IntercomException("Intercom rejected the " + op + " write (HTTP "
                + e.getStatusCode().value() + ")");
    }
}
