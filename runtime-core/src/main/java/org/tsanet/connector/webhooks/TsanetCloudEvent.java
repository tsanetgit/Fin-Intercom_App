package org.tsanet.connector.webhooks;

import java.util.Map;
import java.util.Optional;

/**
 * A TSANet Connect push event in the v2 CloudEvents envelope.
 *
 * <p><b>v2 only, deliberately.</b> BETA and PROD both accept v1 and v2 registrations,
 * and v1 is removed in the first part of 2027 — inside this connector's first year —
 * so carrying v1 parsing would be dead code on arrival. v2 also supplies the
 * {@code id}, which is the only event-level idempotency key that exists: v1's
 * {@code {eventType, requestToken, timestamp}} has none. Existing members migrate
 * separately (tsanetgit/Zendesk_App#101); that is not a reason for new work to speak
 * both dialects.
 *
 * <p>Shape per {@code webhook-asyncapi.yaml} on {@code beta}: {@code specversion},
 * {@code id}, {@code source}, {@code type}, {@code time}, and {@code data} carrying
 * {@code requestToken}.
 */
public record TsanetCloudEvent(String id, Type type, String requestToken) {

    /** The five push types the async spec defines. */
    public enum Type {
        REQUEST_CREATED("org.tsanet.connect.collaboration-request.created"),
        REQUEST_CLOSED("org.tsanet.connect.collaboration-request.closed"),
        NOTE_CREATED("org.tsanet.connect.collaboration-request.note.created"),
        RESPONSE_CREATED("org.tsanet.connect.collaboration-request.response.created"),
        RESPONSE_UPDATED("org.tsanet.connect.collaboration-request.response.updated");

        private final String wireValue;

        Type(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        static Optional<Type> fromWire(String value) {
            for (Type t : values()) {
                if (t.wireValue.equals(value)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Parses a delivered body, or empty when it is not a v2 event this connector can
     * route.
     *
     * <p>Empty is returned rather than a partially-populated event on purpose: the
     * previous intake read v1 field names, so a v2 delivery yielded null type and null
     * token and was queued anyway — an event that can never be handled and never
     * reports why. A body that does not parse is refused at the door and logged by the
     * caller, which surfaces a wrong-version registration on the first delivery rather
     * than as silence.
     */
    public static Optional<TsanetCloudEvent> parse(Map<String, Object> body) {
        if (body == null) {
            return Optional.empty();
        }
        Optional<Type> type = Type.fromWire(string(body.get("type")));
        String token = body.get("data") instanceof Map<?, ?> data
                ? string(data.get("requestToken"))
                : null;
        if (type.isEmpty() || token == null || token.isBlank()) {
            return Optional.empty();
        }
        // id is required by the CloudEvents spec, but an event that arrives without one
        // is still routable — the token carries idempotency for creation. Absence is
        // surfaced as null rather than rejected.
        return Optional.of(new TsanetCloudEvent(string(body.get("id")), type.get(), token));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
