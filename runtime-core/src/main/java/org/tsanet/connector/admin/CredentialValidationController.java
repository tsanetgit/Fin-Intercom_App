package org.tsanet.connector.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /admin/credentials/{name}/validate (docs/decisions.md D21): probe a staged
 * credential live, answer a verdict, apply NOTHING. Write-class - the filter has
 * already verified the five-line signature and burned the nonce by the time a
 * request reaches this controller.
 *
 * <p>The audit line carries name, verdict and latency, NEVER any staged value; the
 * per-name rate bound exists because a login probe against a real identity provider
 * can trip account lockout under repeated failures.
 */
@RestController
public class CredentialValidationController {

    private static final Logger log = LoggerFactory.getLogger(CredentialValidationController.class);

    private final CredentialValidators validators;
    private final Duration minProbeInterval;
    private final Map<String, Instant> lastProbe = new ConcurrentHashMap<>();

    public CredentialValidationController(CredentialValidators validators,
            @org.springframework.beans.factory.annotation.Value(
                    "${connector.admin.probe-min-interval-ms:10000}") long minProbeIntervalMs) {
        this.validators = validators;
        this.minProbeInterval = Duration.ofMillis(minProbeIntervalMs);
    }

    @PostMapping("/admin/credentials/{name}/validate")
    public ResponseEntity<Map<String, Object>> validate(@PathVariable("name") String name,
            @RequestBody(required = false) Map<String, String> body) {
        CredentialValidators.Declared declared = validators.declared().stream()
                .filter(d -> d.name().equals(name)).findFirst().orElse(null);
        if (declared == null) {
            return validators.knownButNotValidatable(name)
                    ? ResponseEntity.badRequest().body(Map.of("error", "not_validatable"))
                    : ResponseEntity.status(404).body(Map.of("error", "unknown_credential"));
        }
        if (body == null || !body.keySet().equals(Set.copyOf(declared.fields()))
                || body.values().stream().anyMatch(v -> v == null || v.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
        }
        // The bound is claimed BEFORE the probe so two concurrent submissions cannot
        // both reach the platform inside one interval.
        Instant now = Instant.now();
        Instant previous = lastProbe.put(name, now);
        if (previous != null && Duration.between(previous, now).compareTo(minProbeInterval) < 0) {
            lastProbe.put(name, previous);
            return ResponseEntity.status(429).body(Map.of("error", "rate_limited"));
        }

        CredentialValidators.Verdict verdict = validators.validate(name, body);
        log.info("tsanet_audit event=credential_validated name={} valid={} latency_ms={}",
                name, verdict.valid(), verdict.latencyMs());

        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("target", verdict.probeTarget());
        probe.put("latencyMs", verdict.latencyMs());
        probe.put("identity", verdict.identity());
        if (verdict.detail() != null) {
            probe.put("detail", verdict.detail());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("valid", verdict.valid());
        out.put("checkedAt", now.toString());
        out.put("probe", probe);
        out.put("note", verdict.valid()
                ? "Validated against the live platform. Apply via the deploy tooling; this verdict changed nothing."
                : "The platform rejected the staged credential. Nothing was changed.");
        return ResponseEntity.ok(out);
    }
}
