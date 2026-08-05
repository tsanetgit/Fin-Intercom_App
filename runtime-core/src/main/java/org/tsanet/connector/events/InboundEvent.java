package org.tsanet.connector.events;

import java.time.Instant;
import java.util.Map;

/**
 * A queued event awaiting asynchronous handling. Despite the name it also carries the
 * connector's own deferred work (Source.CONNECTOR, e.g. the annotation pipeline) —
 * renaming the record is deliberately deferred (docs/decisions.md D10).
 */
public record InboundEvent(Source source, String kind, String id, Instant receivedAt, Map<String, Object> payload) {

    public enum Source { INTERCOM, TSANET, CONNECTOR }
}
