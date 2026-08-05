package org.tsanet.connector.canvas;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Server-side flow state behind the opaque pointer carried in canvas stored_data
 * (docs/decisions.md D8): stored_data holds only {flow_id}, so Intercom never carries
 * our state and size/tampering questions disappear. Bounded LRU with a TTL; an expired
 * or evicted flow simply restarts from the home canvas — EXCEPT flows whose submission
 * is in flight or unresolved, which hold the only copy of the internalCaseNumber that
 * can ever reconcile the case. Severing one by capacity or timer is the same failure
 * QA finding 3 named for the back_home button, so those flows are exempt from both;
 * the reconcile window is otherwise bounded by process lifetime (persistence is a D7
 * non-goal until Phase 1-2 give the store real backing).
 */
@Component
public class FlowStateStore {

    private static final int CAPACITY = 1000;
    private static final Duration TTL = Duration.ofMinutes(30);

    private static final Logger log = LoggerFactory.getLogger(FlowStateStore.class);

    private final Clock clock;

    // Eviction happens in evictOverCapacity(), NOT via removeEldestEntry:
    // tsanetgit/Fin-Intercom_App#3 — that hook consults only THE eldest entry, so one
    // abandoned protected flow parked at the LRU end (never re-accessed, so never
    // leaving the eldest slot under access order) disabled eviction for the entire
    // store while initialize mints a DRAFT per panel render.
    private final Map<String, Flow> flows = new LinkedHashMap<>(16, 0.75f, true);

    public FlowStateStore() {
        this(Clock.systemUTC());
    }

    FlowStateStore(Clock clock) {
        this.clock = clock;
    }

    private record Flow(OutboundFlowState state, Instant createdAt) {
    }

    /**
     * SUBMITTING/UNRESOLVED flows hold the only copy of the reconcile key, so they are
     * never evicted or expired; the store may exceed CAPACITY by the count of such
     * flows. Reaching those states requires a real gateway submit, so the PROTECTED
     * population is bounded by actual incidents; DRAFT/LANDED flows evict normally,
     * and map size stays capacity-bounded because eviction walks past protected
     * entries instead of stopping at them.
     */
    private static boolean evictable(OutboundFlowState state) {
        OutboundFlowState.Status status = state.status();
        return status != OutboundFlowState.Status.SUBMITTING
                && status != OutboundFlowState.Status.UNRESOLVED;
    }

    public synchronized OutboundFlowState create() {
        OutboundFlowState state = new OutboundFlowState(UUID.randomUUID().toString());
        flows.put(state.flowId(), new Flow(state, Instant.now(clock)));
        evictOverCapacity();
        return state;
    }

    /** Walks from the eldest, evicting the first evictable entries until within capacity. */
    private void evictOverCapacity() {
        Iterator<Flow> it = flows.values().iterator();
        while (flows.size() > CAPACITY && it.hasNext()) {
            if (evictable(it.next().state())) {
                it.remove();
            }
        }
        if (flows.size() > CAPACITY) {
            log.warn("flow store over capacity: size={} capacity={} — every remaining flow is"
                    + " SUBMITTING/UNRESOLVED (protected); investigate stuck submissions",
                    flows.size(), CAPACITY);
        }
    }

    public synchronized OutboundFlowState find(String flowId) {
        if (flowId == null) {
            return null;
        }
        Flow entry = flows.get(flowId);
        if (entry == null) {
            return null;
        }
        if (entry.createdAt().plus(TTL).isBefore(Instant.now(clock)) && evictable(entry.state())) {
            flows.remove(flowId);
            return null;
        }
        return entry.state();
    }
}
