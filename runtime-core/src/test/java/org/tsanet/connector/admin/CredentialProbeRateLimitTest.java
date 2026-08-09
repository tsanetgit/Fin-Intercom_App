package org.tsanet.connector.admin;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The per-name probe bound in its own context, at the production default: repeated
 * failed logins against a real identity provider can trip account lockout, so the
 * second rapid probe of one name must be refused without reaching the platform.
 * (The probe here targets an unroutable base, which is fine: the bound is claimed
 * BEFORE the probe, so the second request never even attempts the network.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.admin-api-secret=rate-limit-test-secret",
        // Unroutable: the first probe fails fast locally instead of touching the real
        // platform; the second must be refused before any network attempt at all.
        "connector.intercom.api-base-url=http://127.0.0.1:1",
})
class CredentialProbeRateLimitTest {

    @Autowired
    private MockMvc mvc;

    private static MockHttpServletRequestBuilder signedPost(String target, String body) throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        String nonce = "rl-nonce-" + System.nanoTime();
        StringBuilder bodyHex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))) {
            bodyHex.append(String.format("%02x", b));
        }
        String canonical = ts + "\nPOST\n" + target + "\n" + nonce + "\n" + bodyHex;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("rate-limit-test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sig = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))) {
            sig.append(String.format("%02x", b));
        }
        return MockMvcRequestBuilders.post(target)
                .header("X-Admin-Timestamp", ts)
                .header("X-Admin-Nonce", nonce)
                .header("X-Admin-Signature", sig.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void aSecondRapidProbeOfOneNameIsRefusedBeforeThePlatform() throws Exception {
        String target = "/admin/credentials/intercom-access-token/validate";
        String body = "{\"accessToken\":\"rate-limit-probe\"}";
        mvc.perform(signedPost(target, body)).andExpect(status().isOk());
        mvc.perform(signedPost(target, body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"));
    }
}
