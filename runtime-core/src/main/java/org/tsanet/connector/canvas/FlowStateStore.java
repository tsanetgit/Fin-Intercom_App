package org.tsanet.connector.canvas;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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

    private final Clock clock;

    // Named Flow, not Entry: inside the anonymous LinkedHashMap subclass the simple
    // name Entry binds to the inherited Map.Entry on javac 26+, which silently breaks
    // the removeEldestEntry override (compile error there, name clash). javac 21
    // resolved it the other way; the distinct name compiles on both.
    private final Map<String, Flow> flows = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Flow> eldest) {
            return size() > CAPACITY && evictable(eldest.getValue().state());
        }
    };

    public FlowStateStore() {
        this(Clock.systemUTC());
    }

    FlowStateStore(Clock clock) {
        this.clock = clock;
    }

    private record Flow(OutboundFlowState state, Instant createdAt) {
    }

    /**
     * Only the eldest entry is ever considered, so a protected eldest lets the map
     * exceed CAPACITY until it resolves. Reaching SUBMITTING/UNRESOLVED requires a
     * real gateway submit, so that growth is bounded by actual incidents; panel
     * renders only mint evictable DRAFT flows.
     */
    private static boolean evictable(OutboundFlowState state) {
        OutboundFlowState.Status status = state.status();
        return status != OutboundFlowState.Status.SUBMITTING
                && status != OutboundFlowState.Status.UNRESOLVED;
    }

    public synchronized OutboundFlowState create() {
        OutboundFlowState state = new OutboundFlowState(UUID.randomUUID().toString());
        flows.put(state.flowId(), new Flow(state, Instant.now(clock)));
        return state;
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
