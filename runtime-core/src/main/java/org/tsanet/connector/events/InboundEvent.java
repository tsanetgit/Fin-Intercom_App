package org.tsanet.connector.events;

import java.time.Instant;
import java.util.Map;

/** A received platform event, queued for asynchronous handling. */
public record InboundEvent(Source source, String kind, String id, Instant receivedAt, Map<String, Object> payload) {

    public enum Source { INTERCOM, TSANET }
}
