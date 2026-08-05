package org.tsanet.connector.canvas;

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
 * or evicted flow simply restarts from the home canvas.
 */
@Component
public class FlowStateStore {

    private static final int CAPACITY = 1000;
    private static final Duration TTL = Duration.ofMinutes(30);

    // Named Flow, not Entry: inside the anonymous LinkedHashMap subclass the simple
    // name Entry binds to the inherited Map.Entry on javac 26+, which silently breaks
    // the removeEldestEntry override (compile error there, name clash). javac 21
    // resolved it the other way; the distinct name compiles on both.
    private final Map<String, Flow> flows = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Flow> eldest) {
            return size() > CAPACITY;
        }
    };

    private record Flow(OutboundFlowState state, Instant createdAt) {
    }

    public synchronized OutboundFlowState create() {
        OutboundFlowState state = new OutboundFlowState(UUID.randomUUID().toString());
        flows.put(state.flowId(), new Flow(state, Instant.now()));
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
        if (entry.createdAt().plus(TTL).isBefore(Instant.now())) {
            flows.remove(flowId);
            return null;
        }
        return entry.state();
    }
}
