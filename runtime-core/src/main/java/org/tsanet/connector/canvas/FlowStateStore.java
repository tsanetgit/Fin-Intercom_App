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

    private final Map<String, Entry> flows = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > CAPACITY;
        }
    };

    private record Entry(OutboundFlowState state, Instant createdAt) {
    }

    public synchronized OutboundFlowState create() {
        OutboundFlowState state = new OutboundFlowState(UUID.randomUUID().toString());
        flows.put(state.flowId(), new Entry(state, Instant.now()));
        return state;
    }

    public synchronized OutboundFlowState find(String flowId) {
        if (flowId == null) {
            return null;
        }
        Entry entry = flows.get(flowId);
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
