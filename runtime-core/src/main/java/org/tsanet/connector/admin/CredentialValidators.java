package org.tsanet.connector.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Live credential probes (docs/decisions.md D21). Each validator stages a credential
 * IN MEMORY for exactly one platform round-trip and reports a verdict with the
 * platform-echoed identity, so a valid-but-wrong credential is visible to the
 * operator. Nothing here applies anything: the deployment's secret store stays the
 * single source of truth, and these verdicts are what make applying through it safe.
 *
 * <p>Hygiene rules, deliberately repetitive at every seam: the staged value is never
 * logged, never persisted, never echoed into any response field, and failure details
 * summarize the PLATFORM's answer (status code, error class), never the input.
 */
@Component
public class CredentialValidators {

    public record Declared(String name, List<String> fields) {
    }

    public record Verdict(boolean valid, String probeTarget, long latencyMs, String identity, String detail) {
    }

    /**
     * Names the adapter recognizes but genuinely cannot probe: signing secrets have
     * no probeable counterparty, and a "syntactically plausible" verdict would be
     * false confidence wearing a checkmark. The admin secret's exclusion also dodges
     * the rotate-yourself-into-lockout problem entirely.
     */
    private static final Set<String> KNOWN_NOT_VALIDATABLE =
            Set.of("intercom-client-secret", "tsanet-webhook-secret", "admin-api-secret");

    private static final String UA = "tsanet-intercom-connector-admin/1.0";

    private final Environment env;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public CredentialValidators(Environment env, ObjectMapper mapper) {
        this.env = env;
        this.mapper = mapper;
    }

    /** Dynamic on purpose: a credential is declared only when its probe target is configured. */
    public List<Declared> declared() {
        List<Declared> out = new ArrayList<>();
        out.add(new Declared("intercom-access-token", List.of("accessToken")));
        if (!tsanetBaseUrl().isBlank()) {
            out.add(new Declared("tsanet-credential", List.of("principal", "secret")));
        }
        return out;
    }

    public boolean knownButNotValidatable(String name) {
        return KNOWN_NOT_VALIDATABLE.contains(name);
    }

    public Verdict validate(String name, Map<String, String> values) {
        return switch (name) {
            case "intercom-access-token" -> probeIntercom(values.get("accessToken"));
            case "tsanet-credential" -> probeTsanet(values.get("principal"), values.get("secret"));
            default -> throw new IllegalArgumentException("undeclared credential: " + name);
        };
    }

    private Verdict probeIntercom(String token) {
        String base = env.getProperty("connector.intercom.api-base-url", "https://api.intercom.io")
                .replaceAll("/+$", "");
        String target = "GET " + base + "/me";
        long start = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/me"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("User-Agent", UA).GET().build(), HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (r.statusCode() == 200) {
                JsonNode me = mapper.readTree(r.body());
                String app = me.path("app").path("name").asText("");
                String idCode = me.path("app").path("id_code").asText("");
                String admin = me.path("email").asText(me.path("name").asText(""));
                String identity = (app.isBlank() ? admin : app + (idCode.isBlank() ? "" : " (app " + idCode + ")")
                        + (admin.isBlank() ? "" : ", admin " + admin));
                return new Verdict(true, target, ms, identity, null);
            }
            return new Verdict(false, target, ms, null, "platform answered HTTP " + r.statusCode());
        } catch (Exception e) {
            return failure(target, start, e);
        }
    }

    private Verdict probeTsanet(String principal, String secret) {
        String base = tsanetBaseUrl().replaceAll("/+$", "");
        String target = "POST " + base + "/v1/login";
        long start = System.nanoTime();
        try {
            String body = mapper.writeValueAsString(Map.of("username", principal, "password", secret));
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/login"))
                    .timeout(Duration.ofSeconds(8))
                    .header("content-type", "application/json")
                    .header("User-Agent", UA)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (r.statusCode() != 200) {
                return new Verdict(false, target, ms, null, "platform answered HTTP " + r.statusCode());
            }
            String accessToken = mapper.readTree(r.body()).path("accessToken").asText("");
            if (accessToken.isBlank()) {
                return new Verdict(false, target, ms, null, "login answered 200 without an accessToken");
            }
            // Identity echo only: /v1/me is NOT role-gated, so this names the account
            // without proving it can perform business operations - and says so.
            String identity = "(identity unavailable)";
            try {
                HttpResponse<String> me = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/me"))
                        .timeout(Duration.ofSeconds(8))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("User-Agent", UA).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (me.statusCode() == 200) {
                    JsonNode who = mapper.readTree(me.body());
                    identity = who.path("companyName").asText(who.path("name").asText("(unnamed)"))
                            + " - identity only; /v1/me does not prove business-role authorization";
                }
            } catch (Exception identityOnly) {
                // The login already proved the credential; a failed identity read
                // stays an annotation, never a verdict change.
            }
            return new Verdict(true, target, ms, identity, null);
        } catch (Exception e) {
            return failure(target, start, e);
        }
    }

    private String tsanetBaseUrl() {
        return env.getProperty("connector.tsanet.sdk.api-base-url", "");
    }

    private static Verdict failure(String target, long startNanos, Exception e) {
        // Network-class messages (hosts, timeouts) are safe to surface.
        // IllegalArgumentException is NOT: HttpRequest.Builder embeds the offending
        // header VALUE in its message, which for the Authorization header is the
        // staged credential. The controller rejects control characters before any
        // probe, so this branch is belt-and-braces, but the echo path stays closed
        // even if a new probe implementation forgets that rule.
        String detail = e instanceof IllegalArgumentException
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return new Verdict(false, target, (System.nanoTime() - startNanos) / 1_000_000, null, detail);
    }
}
