package org.tsanet.connector.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Signature schemes per docs/decisions.md D1. Every signature here is computed in the
 * test from the exact bytes posted, so charset or serialization drift fails loudly.
 * Tamper tests are the wrong-but-well-formed probes: valid hex, wrong body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
        "connector.max-body-bytes=1024",
})
class EndpointSecurityTest {

    @Autowired
    private MockMvc mvc;

    private static final byte[] CANVAS_BODY = "{\"workspace_id\":\"w1\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HOOK_BODY = "{\"topic\":\"ping\",\"id\":\"n1\"}".getBytes(StandardCharsets.UTF_8);

    private static String hmacHex(String algo, String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance(algo);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algo));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(body)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void healthzIsOpenAndUnsigned() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void canvasRejectsUnsigned() throws Exception {
        mvc.perform(post("/canvas/initialize").contentType(MediaType.APPLICATION_JSON).content(CANVAS_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_signature"));
    }

    @Test
    void canvasAcceptsBareHexSha256() throws Exception {
        String sig = hmacHex("HmacSHA256", "test-intercom-secret", CANVAS_BODY);
        mvc.perform(post("/canvas/initialize").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sig).content(CANVAS_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void canvasRejectsSignatureOfDifferentBody() throws Exception {
        String sigOfOtherBody = hmacHex("HmacSHA256", "test-intercom-secret",
                "{\"workspace_id\":\"TAMPERED\"}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/canvas/initialize").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sigOfOtherBody).content(CANVAS_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void intercomWebhookRequiresSha1Prefix() throws Exception {
        String hex = hmacHex("HmacSHA1", "test-intercom-secret", HOOK_BODY);
        mvc.perform(post("/webhooks/intercom").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature", "sha1=" + hex).content(HOOK_BODY))
                .andExpect(status().isOk());
        // Bare hex is not the observed Intercom format; must be rejected.
        mvc.perform(post("/webhooks/intercom").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature", hex).content(HOOK_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void intercomWebhookRejectsTamper() throws Exception {
        String sig = "sha1=" + hmacHex("HmacSHA1", "test-intercom-secret",
                "{\"topic\":\"ping\",\"id\":\"OTHER\"}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/webhooks/intercom").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature", sig).content(HOOK_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tsanetWebhookAcceptsPrefixedAndBareUntilFormatConfirmed() throws Exception {
        String hex = hmacHex("HmacSHA256", "test-tsanet-secret", HOOK_BODY);
        mvc.perform(post("/webhooks/tsanet").contentType(MediaType.APPLICATION_JSON)
                        .header("x-hub-signature-256", "sha256=" + hex).content(HOOK_BODY))
                .andExpect(status().isOk());
        mvc.perform(post("/webhooks/tsanet").contentType(MediaType.APPLICATION_JSON)
                        .header("x-hub-signature-256", hex).content(HOOK_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void tsanetWebhookRejectsWrongSecret() throws Exception {
        String hex = hmacHex("HmacSHA256", "the-wrong-secret", HOOK_BODY);
        mvc.perform(post("/webhooks/tsanet").contentType(MediaType.APPLICATION_JSON)
                        .header("x-hub-signature-256", "sha256=" + hex).content(HOOK_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oversizedBodyIsRejectedBeforeVerification() throws Exception {
        byte[] big = new byte[2048];
        mvc.perform(post("/canvas/initialize").contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void adminFailsClosedWhenNoAdminSecretIsConfigured() throws Exception {
        // This context sets no connector.admin-api-secret: a fresh, well-formed admin
        // request must still be refused (D20 — no admin secret means no admin plane).
        String ts = Long.toString(java.time.Instant.now().getEpochSecond());
        mvc.perform(get("/admin/info")
                        .header("X-Admin-Timestamp", ts)
                        .header("X-Admin-Signature", "sha256=" + "0".repeat(64)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("secret_not_configured"));
    }
}
