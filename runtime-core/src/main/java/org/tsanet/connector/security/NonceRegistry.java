package org.tsanet.connector.security;

import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The write-class replay guard (docs/decisions.md D21; contract vector 4). A nonce is
 * accepted exactly once within the freshness window; a second sight is a replay.
 *
 * <p>Bounded, and it REJECTS when full rather than evicting: evict-oldest would fail
 * open, because flooding the set ages out precisely the nonce an attacker wants to
 * replay. In-memory on purpose - the adapter is single-replica by design - with the
 * contract-stated consequence that a restart reopens at most one freshness window of
 * replay exposure for nonces seen before it.
 */
@Component
public class NonceRegistry {

    private static final Logger log = LoggerFactory.getLogger(NonceRegistry.class);

    static final int CAPACITY = 10_000;
    private static final long TTL_SECONDS = AdminSignatures.FRESHNESS_SECONDS;

    private final Map<String, Long> seen = new HashMap<>();

    /**
     * @return {@code null} when the nonce is accepted (and now burned), else the
     *     contract error code.
     */
    public synchronized String accept(String nonce, long nowEpochSeconds) {
        if (nonce == null || nonce.isBlank()) {
            return "invalid_nonce";
        }
        sweep(nowEpochSeconds);
        Long seenAt = seen.get(nonce);
        if (seenAt != null) {
            return "replayed_nonce";
        }
        if (seen.size() >= CAPACITY) {
            // Fail closed: refusing a legitimate write is recoverable (retry with a
            // fresh nonce after the window drains); accepting a replay is not.
            log.warn("nonce registry at capacity ({}); refusing writes until the window drains", CAPACITY);
            return "invalid_nonce";
        }
        seen.put(nonce, nowEpochSeconds);
        return null;
    }

    private void sweep(long nowEpochSeconds) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (nowEpochSeconds - it.next().getValue() > TTL_SECONDS) {
                it.remove();
            }
        }
    }

    public synchronized int size() {
        return seen.size();
    }

    /** Package-private: lets a test prove reject-on-full without 10k inserts. */
    synchronized void fillForTest(int entries, long nowEpochSeconds) {
        for (int i = 0; i < entries; i++) {
            seen.put("fill-" + i + "-" + Instant.ofEpochSecond(nowEpochSeconds), nowEpochSeconds);
        }
    }
}
