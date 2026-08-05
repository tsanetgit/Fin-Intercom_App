package org.tsanet.connector.webhooks;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.tsanet.connector.config.ConnectorProperties;
import org.tsanet.connector.events.DedupeStore;
import org.tsanet.connector.events.EventQueue;
import org.tsanet.connector.events.InboundEvent;

/**
 * Intercom notification intake. Contract (docs/decisions.md D4): ack immediately
 * (Intercom allows 5s, retries once, throttles on 429), dedupe by notification id,
 * queue for asynchronous handling.
 */
@RestController
public class IntercomWebhookController {

    private final EventQueue queue;
    private final DedupeStore dedupe;

    public IntercomWebhookController(EventQueue queue, ConnectorProperties properties) {
        this.queue = queue;
        this.dedupe = new DedupeStore(properties.dedupeCapacity());
    }

    @PostMapping("/webhooks/intercom")
    public Map<String, Object> receive(@RequestBody Map<String, Object> payload) {
        String id = asString(payload.get("id"));
        String topic = asString(payload.get("topic"));
        boolean fresh = dedupe.firstSighting(id);
        if (fresh) {
            queue.offer(new InboundEvent(InboundEvent.Source.INTERCOM, topic, id, Instant.now(), payload));
        }
        return Map.of("ok", true, "duplicate", !fresh);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
