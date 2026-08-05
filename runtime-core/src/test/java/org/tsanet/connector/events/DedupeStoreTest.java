package org.tsanet.connector.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DedupeStoreTest {

    @Test
    void duplicateIdsAreReported() {
        DedupeStore store = new DedupeStore(10);
        assertTrue(store.firstSighting("a"));
        assertFalse(store.firstSighting("a"));
    }

    @Test
    void nullOrBlankIdsNeverBlock() {
        DedupeStore store = new DedupeStore(10);
        assertTrue(store.firstSighting(null));
        assertTrue(store.firstSighting(null));
        assertTrue(store.firstSighting(""));
    }

    /** The documented accepted risk (docs/decisions.md D4): eviction past the bound re-accepts. */
    @Test
    void evictionPastCapacityReAccepts() {
        DedupeStore store = new DedupeStore(2);
        assertTrue(store.firstSighting("a"));
        assertTrue(store.firstSighting("b"));
        assertFalse(store.firstSighting("a")); // refreshes a; b is now least-recent
        assertTrue(store.firstSighting("c"));  // evicts b
        assertTrue(store.firstSighting("b")); // b was evicted, so it reads as new again
    }
}
