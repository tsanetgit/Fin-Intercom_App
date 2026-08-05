package org.tsanet.connector.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tsanet.connector.events.EventQueue;

/** The only unauthenticated route (docs/decisions.md D1). */
@RestController
public class HealthController {

    private final EventQueue queue;

    public HealthController(EventQueue queue) {
        this.queue = queue;
    }

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "tsanet-intercom-connector", "queued", queue.size());
    }
}
