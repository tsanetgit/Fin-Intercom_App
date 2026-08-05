package org.tsanet.connector.webhooks;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.tsanet.connector.events.EventQueue;
import org.tsanet.connector.events.InboundEvent;

/**
 * TSANet Connect push intake. Payloads are thin ({eventType, requestToken, noteToken?})
 * and carry no unique event id, so there is no dedupe here; downstream handling is
 * idempotent and the reconciliation poller is the source of truth
 * (docs/decisions.md D4). Signature verified by the filter (D1).
 */
@RestController
public class TsanetWebhookController {

    private final EventQueue queue;

    public TsanetWebhookController(EventQueue queue) {
        this.queue = queue;
    }

    @PostMapping("/webhooks/tsanet")
    public Map<String, Object> receive(@RequestBody Map<String, Object> payload) {
        String kind = payload.get("eventType") == null ? null : payload.get("eventType").toString();
        String token = payload.get("requestToken") == null ? null : payload.get("requestToken").toString();
        queue.offer(new InboundEvent(InboundEvent.Source.TSANET, kind, token, Instant.now(), payload));
        return Map.of("ok", true);
    }
}
