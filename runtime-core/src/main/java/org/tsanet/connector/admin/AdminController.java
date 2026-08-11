package org.tsanet.connector.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tsanet.connector.config.ConnectorProperties;
import org.tsanet.connector.events.EventQueue;
import org.tsanet.connector.inbound.InboundProperties;
import org.tsanet.connector.inbound.PollerStateStore;
import org.tsanet.connector.inbound.ReconciliationPoller;
import org.tsanet.connector.intercom.SubmissionLinkTable;

/**
 * The version-1 read surface of the adapter admin contract
 * (Connect_Gateway docs/admin-contract.md; docs/decisions.md D20). Reporting only:
 * nothing here triggers work — the status endpoint READS the poller's counters and the
 * stored cursor, it never runs a cycle (D16 owns when cycles happen). Authentication
 * happens upstream in {@code SignatureVerificationFilter}; by the time a request
 * reaches this controller it carried a valid admin signature.
 */
@RestController
public class AdminController {

    static final int ADMIN_CONTRACT_VERSION = 1;
    private static final int DEFAULT_TAIL = 100;

    /**
     * Non-secret configuration surfaced by /admin/config. Curated, not enumerated: the
     * property sources contain the whole process environment, and dumping that is how
     * secrets leak. A key added to the config must be placed on the right side of this
     * split deliberately.
     */
    private static final List<String> CONFIG_PROPERTIES = List.of(
            "connector.tsanet.mode",
            "connector.intercom.mode",
            "connector.links.store",
            "connector.links.sqlite-path",
            "connector.inbound.reconcile-enabled",
            "connector.inbound.reconcile-interval-ms",
            "connector.inbound.reconcile-lookback",
            "connector.inbound.reconcile-overlap",
            "connector.inbound.collaboration-ticket-type-id",
            "connector.inbound.resolved-state-id",
            "connector.public-base-url",
            "connector.max-body-bytes",
            "server.port");

    /** Secrets surfaced as presence markers only; values never appear in any response. */
    private static final List<String> SECRET_PROPERTIES = List.of(
            "connector.admin-api-secret",
            "connector.intercom-client-secret",
            "connector.tsanet-webhook-secret",
            "connector.intercom.access-token",
            "gateway.tsanet.sdk.secret");

    private final ConnectorProperties properties;
    private final InboundProperties inbound;
    private final Environment env;
    private final SubmissionLinkTable links;
    private final PollerStateStore pollerState;
    private final ReconciliationPoller poller;
    private final EventQueue queue;
    private final RingBufferLogAppender ring;
    private final CredentialValidators validators;
    private final Instant startedAt = Instant.now();

    public AdminController(ConnectorProperties properties, InboundProperties inbound, Environment env,
            SubmissionLinkTable links, PollerStateStore pollerState, ReconciliationPoller poller,
            EventQueue queue, RingBufferLogAppender ring, CredentialValidators validators) {
        this.properties = properties;
        this.inbound = inbound;
        this.env = env;
        this.links = links;
        this.pollerState = pollerState;
        this.poller = poller;
        this.queue = queue;
        this.ring = ring;
        this.validators = validators;
    }

    @GetMapping("/admin/info")
    public Map<String, Object> info() {
        String version = getClass().getPackage().getImplementationVersion();
        Map<String, Object> out = new LinkedHashMap<>();
        // Same identity /healthz reports; keep the two literals in step.
        out.put("service", "tsanet-intercom-connector");
        out.put("version", version == null || version.isBlank() ? "dev" : version);
        out.put("platform", "intercom");
        out.put("deploymentShape", "tsanet-hosted");
        out.put("adminContractVersion", ADMIN_CONTRACT_VERSION);
        out.put("features", List.of("credential-validate"));
        out.put("validatableCredentials", validators.declared().stream()
                .map(d -> Map.of("name", d.name(), "fields", d.fields())).toList());
        return out;
    }

    @GetMapping("/admin/status")
    public Map<String, Object> status() {
        Map<String, Object> reconcile = new LinkedHashMap<>();
        reconcile.put("enabled", inbound.reconcileEnabled());
        reconcile.put("consecutiveFailures", poller.consecutiveFailures());
        reconcile.put("consecutiveHeld", poller.consecutiveHeld());
        reconcile.put("cursor", pollerState.cursor().map(Instant::toString).orElse(null));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tsanetMode", env.getProperty("connector.tsanet.mode", "fake"));
        details.put("intercomMode", env.getProperty("connector.intercom.mode", "fake"));
        details.put("linksStore", env.getProperty("connector.links.store", "memory"));
        details.put("linkCount", links.size());
        details.put("queueDepth", queue.size());
        details.put("reconcile", reconcile);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("startedAt", startedAt.toString());
        out.put("details", details);
        return out;
    }

    @GetMapping("/admin/urls")
    public Map<String, Object> urls(HttpServletRequest request) {
        String base = null;
        String source;
        String configured = properties.publicBaseUrl();
        String forwardedHost = firstValue(request.getHeader("X-Forwarded-Host"));
        String host = firstValue(request.getHeader("Host"));
        if (configured != null && !configured.isBlank()) {
            base = configured.replaceAll("/+$", "");
            source = "configured";
        } else if (forwardedHost != null) {
            String proto = firstValue(request.getHeader("X-Forwarded-Proto"));
            base = (proto == null ? "https" : proto) + "://" + forwardedHost;
            source = "forwarded";
        } else if (host != null) {
            base = request.getScheme() + "://" + host;
            source = "host";
        } else {
            source = "unresolved";
        }

        List<Map<String, String>> sheet = new ArrayList<>();
        sheet.add(url(base, "canvas_initialize", "/canvas/initialize",
                "Intercom Developer Hub, Canvas Kit (For teammates): Initialize flow webhook URL"));
        sheet.add(url(base, "canvas_submit", "/canvas/submit",
                "Intercom Developer Hub, Canvas Kit (For teammates): Submit flow webhook URL"));
        sheet.add(url(base, "intercom_webhook", "/webhooks/intercom",
                "Intercom Developer Hub, Webhooks: endpoint URL (topics must be explicitly subscribed;"
                        + " permissions alone deliver nothing)"));
        sheet.add(url(base, "tsanet_webhook", "/webhooks/tsanet",
                "TSANet Connect v2 webhook subscription: the url field of POST /v2/webhooks (the secret is"
                        + " minted by TSANet and returned exactly once, at creation)"));
        sheet.add(url(base, "healthz", "/healthz", "Uptime probes and warm-up checks (unauthenticated)"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseUrl", base);
        out.put("baseUrlSource", source);
        out.put("urls", sheet);
        return out;
    }

    @GetMapping("/admin/config")
    public Map<String, Object> config() {
        Map<String, Object> propertiesOut = new LinkedHashMap<>();
        for (String key : CONFIG_PROPERTIES) {
            propertiesOut.put(key, env.getProperty(key, ""));
        }
        Map<String, Object> secretsOut = new LinkedHashMap<>();
        for (String key : SECRET_PROPERTIES) {
            String value = env.getProperty(key);
            secretsOut.put(key, Map.of("present", value != null && !value.isBlank()));
        }
        return Map.of("properties", propertiesOut, "secrets", secretsOut);
    }

    @GetMapping("/admin/logs/tail")
    public ResponseEntity<Map<String, Object>> logsTail(
            @RequestParam(name = "n", required = false) String n) {
        Integer parsed = parseN(n, ring.lineCapacity());
        if (parsed == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_n"));
        }
        return ResponseEntity.ok(Map.of("lines", ring.tailLines(parsed)));
    }

    @GetMapping("/admin/audit/tail")
    public ResponseEntity<Map<String, Object>> auditTail(
            @RequestParam(name = "n", required = false) String n) {
        Integer parsed = parseN(n, ring.auditCapacity());
        if (parsed == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_n"));
        }
        return ResponseEntity.ok(Map.of("events", ring.tailAudit(parsed)));
    }

    private static Map<String, String> url(String base, String name, String path, String pastedInto) {
        return Map.of("name", name, "url", base == null ? path : base + path, "pastedInto", pastedInto);
    }

    /** First element of a possibly comma-joined header, or null when absent/blank. */
    private static String firstValue(String header) {
        if (header == null) {
            return null;
        }
        String first = header.split(",", 2)[0].trim();
        return first.isEmpty() ? null : first;
    }

    /**
     * Null on anything outside [1, max]; the contract's wrong-but-well-formed input is
     * a number that parses and is still invalid (0, negative, over capacity).
     */
    private static Integer parseN(String raw, int max) {
        if (raw == null) {
            return Math.min(DEFAULT_TAIL, max);
        }
        try {
            int value = Integer.parseInt(raw);
            return value >= 1 && value <= max ? value : null;
        } catch (NumberFormatException notInteger) {
            return null;
        }
    }
}
