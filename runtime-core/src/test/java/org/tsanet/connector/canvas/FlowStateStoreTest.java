package org.tsanet.connector.canvas;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The store boundary of the flow-status machine: neither capacity pressure nor the
 * TTL may sever a SUBMITTING/UNRESOLVED flow, because its id is the only copy of the
 * internalCaseNumber that can ever reconcile the possibly-created case.
 */
class FlowStateStoreTest {

    private static final int OVER_CAPACITY = 1002; // store capacity is 1000

    @Test
    void draftEldestIsEvictedUnderCapacityPressure() {
        FlowStateStore store = new FlowStateStore();
        OutboundFlowState draft = store.create();
        for (int i = 0; i < OVER_CAPACITY; i++) {
            store.create();
        }
        assertNull(store.find(draft.flowId()), "an idle DRAFT eldest must be evictable");
    }

    @Test
    void unresolvedEldestSurvivesCapacityPressure() {
        FlowStateStore store = new FlowStateStore();
        OutboundFlowState unresolved = store.create();
        unresolved.beginSubmit();
        unresolved.submitUnresolved();
        for (int i = 0; i < OVER_CAPACITY; i++) {
            store.create();
        }
        assertNotNull(store.find(unresolved.flowId()),
                "evicting an unresolved flow would orphan its possibly-created case");
    }

    @Test
    void protectedEldestDoesNotJamEvictionForOthers() {
        // tsanetgit/Fin-Intercom_App#3: removeEldestEntry only ever consulted THE
        // eldest, so one abandoned UNRESOLVED flow at the LRU end disabled eviction
        // entirely (QA probe: an early DRAFT survived 2000 inserts over capacity).
        // Eviction must walk PAST protected flows, not stop at them.
        FlowStateStore store = new FlowStateStore();
        OutboundFlowState unresolved = store.create(); // eldest, protected
        unresolved.beginSubmit();
        unresolved.submitUnresolved();
        OutboundFlowState draft = store.create(); // second-eldest, evictable
        for (int i = 0; i < OVER_CAPACITY; i++) {
            store.create();
        }
        assertNull(store.find(draft.flowId()),
                "a protected eldest must not disable eviction for evictable flows behind it");
        assertNotNull(store.find(unresolved.flowId()),
                "the protected flow itself still survives");
    }

    @Test
    void ttlExpiresDraftButNeverUnresolvedFlows() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-05T00:00:00Z"));
        FlowStateStore store = new FlowStateStore(clock);

        OutboundFlowState draft = store.create();
        OutboundFlowState unresolved = store.create();
        unresolved.beginSubmit();
        unresolved.submitUnresolved();

        clock.advance(Duration.ofMinutes(31)); // past the 30-minute TTL

        assertNull(store.find(draft.flowId()), "an idle DRAFT flow expires normally");
        assertNotNull(store.find(unresolved.flowId()),
                "the TTL must not sever the reconcile key of an unresolved submission");
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
