package org.tsanet.connector.events;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded LRU of seen event ids. Replay of an id evicted past the bound is re-accepted:
 * accepted risk, because downstream handling must be idempotent anyway
 * (docs/decisions.md D4).
 */
public final class DedupeStore {

    private final Map<String, Boolean> seen;

    public DedupeStore(int capacity) {
        this.seen = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Returns true when the id is new (and records it); false when already seen. */
    public synchronized boolean firstSighting(String id) {
        if (id == null || id.isBlank()) {
            return true;
        }
        return seen.put(id, Boolean.TRUE) == null;
    }
}
