package org.tsanet.connector.canvas;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sidebar app. Budget rule (docs/decisions.md D2): handlers here perform ZERO
 * outbound calls; verify (filter), read context, respond. Measured platform timeout is
 * ~10s and a slow submit strands the agent on a spinner, so anything slower than an
 * in-memory computation must go through the event queue and reach the panel on a later
 * render. TsanetGateway is deliberately not referenced from this package in Phase 0.
 */
@RestController
public class CanvasController {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    @PostMapping("/canvas/initialize")
    public Map<String, Object> initialize(@RequestBody Map<String, Object> payload) {
        return statusCanvas(payload, "Panel initialized");
    }

    @PostMapping("/canvas/submit")
    public Map<String, Object> submit(@RequestBody Map<String, Object> payload) {
        Object component = payload.get("component_id");
        return statusCanvas(payload, component == null ? "Submitted" : "Received: " + component);
    }

    private static Map<String, Object> statusCanvas(Map<String, Object> payload, String action) {
        // Ticket renders carry a top-level `ticket` object; conversation renders omit it
        // (verified live 2026-08-04, docs/decisions.md D5).
        String surface = payload.get("ticket") != null ? "ticket" : "conversation";
        return CanvasKit.canvas(List.of(
                CanvasKit.header("TSANet Connect"),
                CanvasKit.muted(action + " on a " + surface + " at " + LocalTime.now().format(CLOCK)
                        + ". Runtime skeleton: partner search and case flows arrive in Phase 1."),
                CanvasKit.button("ping", "Ping the runtime")));
    }
}
