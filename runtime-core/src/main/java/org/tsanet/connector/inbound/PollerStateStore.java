package org.tsanet.connector.inbound;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where the reconciliation cursor lives between cycles.
 *
 * <p>Deliberately NOT part of {@code SubmissionLinkTable}. That interface's contract
 * test pins a keyed surface where every method answers a null key rather than throwing,
 * and a cursor is not a link — grafting it on would widen a contract that exists to say
 * something narrow and precise about routing.
 *
 * <p><b>Losing the cursor is survivable; losing links is not.</b> That asymmetry is why
 * this store is allowed an in-memory implementation while the link table refuses one
 * under prod. A restart with no cursor re-scans the lookback window, and every case in
 * it that already has a link is skipped by the poller's own prefilter — so the cost is
 * one wider query, not a duplicate ticket and not a lost case. Durability here buys a
 * cheaper first cycle, not correctness.
 */
public interface PollerStateStore {

    /**
     * The high-water mark from the last CLEAN cycle, or empty on a store that has never
     * recorded one.
     *
     * <p>Empty is meaningfully different from "epoch": the poller turns empty into
     * {@code now - lookback}, a bounded window. Answering epoch here would make a first
     * run ask for every case that has ever existed, against an endpoint with no
     * pagination.
     */
    Optional<Instant> cursor();

    /**
     * Records a new high-water mark. Only ever called after a cycle in which every
     * candidate reached a terminal outcome — see {@link ReconciliationPoller}.
     */
    void cursor(Instant value);

    /**
     * Volatile cursor for development and tests. Safe by the asymmetry in the class
     * javadoc: a lost cursor costs a re-scan, which guard-create absorbs.
     */
    final class InMemory implements PollerStateStore {

        private final AtomicReference<Instant> value = new AtomicReference<>();

        @Override
        public Optional<Instant> cursor() {
            return Optional.ofNullable(value.get());
        }

        @Override
        public void cursor(Instant next) {
            value.set(next);
        }
    }
}
