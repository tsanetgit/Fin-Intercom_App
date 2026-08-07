package org.tsanet.connector.webhooks;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.tsanet.connector.events.EventQueue;
import org.tsanet.connector.events.InboundEvent;

/**
 * TSANet Connect push intake. Fast-acks and queues; handling is asynchronous and
 * {@code ReconciliationPoller} is the backstop that makes a dropped event recoverable
 * rather than lost (docs/decisions.md D4, D16). Signature verified by the filter (D1).
 *
 * <p>This endpoint answering 200 on EVERY branch is what makes that backstop necessary:
 * TSANet treats 4xx as permanent and never retries it, so a 4xx here would guarantee the
 * loss rather than risk it — and a 200 means no redelivery is coming either.
 *
 * <p>Speaks v2 CloudEvents only (D13). The queued event carries the CloudEvents type
 * as its kind and the case token as its id, which is what the handlers key on.
 */
@RestController
public class TsanetWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TsanetWebhookController.class);

    private final EventQueue queue;

    public TsanetWebhookController(EventQueue queue) {
        this.queue = queue;
    }

    @PostMapping("/webhooks/tsanet")
    public Map<String, Object> receive(@RequestBody Map<String, Object> payload) {
        Optional<TsanetCloudEvent> parsed = TsanetCloudEvent.parse(payload);
        if (parsed.isEmpty()) {
            // Refused rather than queued. An unparseable body is a wrong-version or
            // wrong-shape registration, and queueing it would bury that as an event
            // no handler ever claims. Still a 200: redelivery cannot fix the shape,
            // and TSANet's retry budget is better spent elsewhere.
            log.error("tsanet_audit event=push_unroutable reason=not_a_v2_cloudevent keys={}",
                    payload == null ? "null" : payload.keySet());
            return Map.of("ok", true, "routed", false);
        }
        TsanetCloudEvent event = parsed.get();
        queue.offer(new InboundEvent(InboundEvent.Source.TSANET, event.type().wireValue(),
                event.requestToken(), Instant.now(), payload));
        log.info("tsanet_audit event=push_received type={} token={} cloudevent_id={}",
                event.type().wireValue(), event.requestToken(), event.id());
        return Map.of("ok", true, "routed", true);
    }
}
