package org.tsanet.connector.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.tsanet.connector.admin.RingBufferLogAppender;

/**
 * The validate endpoint against stub platforms that verify what the adapter actually
 * sends. The hygiene probe is load-bearing: a distinctive staged value must appear
 * NOWHERE in the admin log ring after a probe - the class of regression this catches
 * is someone adding an innocent-looking log line with the request body in it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.admin-api-secret=validate-test-secret",
        // Several tests probe the same credential name; the production bound gets its
        // own dedicated context in CredentialProbeRateLimitTest.
        "connector.admin.probe-min-interval-ms=0",
})
class CredentialValidationTest {

    private static final String ADMIN_SECRET = "validate-test-secret";
    private static final String GOOD_INTERCOM_TOKEN = "good-intercom-token-9x8y7z";
    private static final String GOOD_TOKEN_NO_APP = "good-token-without-app-object";
    private static final String STAGED_MARKER = "staged-value-canary-51f9e2";

    private static final HttpServer STUB;

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/me", exchange -> {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                boolean ok = ("Bearer " + GOOD_INTERCOM_TOKEN).equals(auth);
                boolean appless = ("Bearer " + GOOD_TOKEN_NO_APP).equals(auth);
                byte[] body = (ok
                        ? "{\"type\":\"admin\",\"email\":\"stub-admin@example.test\",\"app\":{\"name\":\"Stub Workspace\",\"id_code\":\"stub123\"}}"
                        : appless
                        ? "{\"type\":\"admin\",\"email\":\"lonely-admin@example.test\"}"
                        : "{\"errors\":[{\"code\":\"unauthorized\"}]}").getBytes(StandardCharsets.UTF_8);
                ok = ok || appless;
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(ok ? 200 : 401, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            STUB.createContext("/v1/login", exchange -> {
                String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean ok = req.contains("\"good-principal\"") && req.contains("\"good-secret\"");
                byte[] body = (ok ? "{\"accessToken\":\"stub-token\"}" : "{\"detail\":\"bad\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(ok ? 200 : 401, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            STUB.createContext("/v1/me", exchange -> {
                byte[] body = "{\"companyName\":\"Stub Member\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            STUB.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void stubUrls(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        registry.add("connector.intercom.api-base-url", () -> base);
        registry.add("gateway.tsanet.sdk.api-base-url", () -> base);
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RingBufferLogAppender ring;

    private static MockHttpServletRequestBuilder signedPost(String target, String body) throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        String nonce = "test-nonce-" + System.nanoTime();
        StringBuilder bodyHex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))) {
            bodyHex.append(String.format("%02x", b));
        }
        String canonical = ts + "\nPOST\n" + target + "\n" + nonce + "\n" + bodyHex;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(ADMIN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sig = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))) {
            sig.append(String.format("%02x", b));
        }
        return post(target)
                .header("X-Admin-Timestamp", ts)
                .header("X-Admin-Nonce", nonce)
                .header("X-Admin-Signature", sig.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void aValidCredentialAnswersIdentityAndAppliesNothing() throws Exception {
        mvc.perform(signedPost("/admin/credentials/intercom-access-token/validate",
                        "{\"accessToken\":\"" + GOOD_INTERCOM_TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.probe.identity").value(
                        org.hamcrest.Matchers.containsString("Stub Workspace")))
                .andExpect(jsonPath("$.note").value(
                        org.hamcrest.Matchers.containsString("this verdict changed nothing")));
    }

    @Test
    void anApplessMeResponseFallsBackToTheAdminIdentity() throws Exception {
        // The previously untested branch: a /me without an app object must identify
        // by the admin alone, not render blank or misattach the app suffixes.
        mvc.perform(signedPost("/admin/credentials/intercom-access-token/validate",
                        "{\"accessToken\":\"" + GOOD_TOKEN_NO_APP + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.probe.identity").value("lonely-admin@example.test"));
    }

    @Test
    void anInvalidCredentialIsAVerdictWithThePlatformsAnswer() throws Exception {
        String body = mvc.perform(signedPost("/admin/credentials/tsanet-credential/validate",
                        "{\"principal\":\"wrong\",\"secret\":\"" + STAGED_MARKER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.probe.detail").value(
                        org.hamcrest.Matchers.containsString("401")))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(STAGED_MARKER), "the staged value must never be echoed");
    }

    @Test
    void theStagedValueNeverReachesTheLogs() throws Exception {
        mvc.perform(signedPost("/admin/credentials/intercom-access-token/validate",
                        "{\"accessToken\":\"" + STAGED_MARKER + "-intercom\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
        String allLogs = String.join("\n", ring.tailLines(1000));
        assertFalse(allLogs.contains(STAGED_MARKER), "a staged credential leaked into the log ring");
        assertTrue(allLogs.contains("event=credential_validated"),
                "the audit line itself must exist (name/verdict/latency, no value)");
    }

    @Test
    void tsanetIdentityIsLabeledIdentityOnly() throws Exception {
        // Waits out the per-name rate bound from the earlier tsanet probe if needed:
        // distinct names per test keep this from firing in practice.
        mvc.perform(signedPost("/admin/credentials/tsanet-credential/validate",
                        "{\"principal\":\"good-principal\",\"secret\":\"good-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.probe.identity").value(
                        org.hamcrest.Matchers.containsString("identity only")));
    }

    @Test
    void aControlCharacterValueIsRejectedBeforeAnyProbeAndNeverEchoed() throws Exception {
        // The advisor-found echo path: a CR/LF token makes HttpRequest.Builder throw
        // an IllegalArgumentException whose MESSAGE contains the value. The wrong-but-
        // well-formed input here is a syntactically legal JSON string with a newline.
        String cr = STAGED_MARKER + "\\nInjected: yes";
        String body = mvc.perform(signedPost("/admin/credentials/intercom-access-token/validate",
                        "{\"accessToken\":\"" + cr + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(STAGED_MARKER), "the rejected value must not be echoed either");
    }

    @Test
    void wrongFieldsAreInvalidRequestNotAProbe() throws Exception {
        mvc.perform(signedPost("/admin/credentials/intercom-access-token/validate",
                        "{\"token\":\"wrong-field-name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void signingSecretsAreNotValidatableAndSaySo() throws Exception {
        mvc.perform(signedPost("/admin/credentials/intercom-client-secret/validate", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("not_validatable"));
    }

    @Test
    void unknownNamesAre404() throws Exception {
        mvc.perform(signedPost("/admin/credentials/no-such-thing/validate", "{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_credential"));
    }

    @Test
    void theInfoEndpointAdvertisesTheFeatureAndTheForms() throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        StringBuilder bodyHex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(new byte[0])) {
            bodyHex.append(String.format("%02x", b));
        }
        String canonical = ts + "\nGET\n/admin/info\n" + bodyHex;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(ADMIN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sig = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))) {
            sig.append(String.format("%02x", b));
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/info")
                        .header("X-Admin-Timestamp", ts)
                        .header("X-Admin-Signature", sig.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[0]").value("credential-validate"))
                .andExpect(jsonPath("$.validatableCredentials[?(@.name=='intercom-access-token')].fields[0]")
                        .value("accessToken"))
                .andExpect(jsonPath("$.validatableCredentials[?(@.name=='tsanet-credential')].fields[0]")
                        .value("principal"));
    }
}
