package org.tsanet.connector.sdk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-operation deadline-expiry counters (docs/decisions.md D2: the counter exists to
 * validate the 1500ms read figure against real BETA partners). Surfaced through the
 * structured WARN log key {@code tsanet_gateway_timeout} on every expiry; a healthz
 * field can lift these totals later without renaming anything.
 */
public final class GatewayTimeouts {

    private final Map<String, AtomicLong> byOp = new ConcurrentHashMap<>();

    /** Records one expiry for {@code op} and returns the new running total. */
    public long record(String op) {
        return byOp.computeIfAbsent(op, k -> new AtomicLong()).incrementAndGet();
    }

    public long total(String op) {
        AtomicLong counter = byOp.get(op);
        return counter == null ? 0 : counter.get();
    }
}
