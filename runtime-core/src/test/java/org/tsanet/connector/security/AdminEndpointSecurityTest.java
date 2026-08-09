package org.tsanet.connector.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The admin scheme through the real filter (docs/decisions.md D20). Signatures here are
 * computed by this test from the contract's canonical-string definition — independently
 * of {@code AdminSignatures} — so agreement means the filter implements the contract,
 * not merely that verifier and signer share a bug.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.admin-api-secret=test-admin-secret",
})
class AdminEndpointSecurityTest {

    @Autowired
    private MockMvc mvc;

    private static String sign(String secret, String timestamp, String method, String target, byte[] body)
            throws Exception {
        StringBuilder bodyHex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(body)) {
            bodyHex.append(String.format("%02x", b));
        }
        String canonical = timestamp + "\n" + method + "\n" + target + "\n" + bodyHex;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sig = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))) {
            sig.append(String.format("%02x", b));
        }
        return sig.toString();
    }

    private static MockHttpServletRequestBuilder signedGet(String target) throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        return get(target)
                .header("X-Admin-Timestamp", ts)
                .header("X-Admin-Signature", sign("test-admin-secret", ts, "GET", target, new byte[0]));
    }

    @Test
    void adminRejectsUnsigned() throws Exception {
        mvc.perform(get("/admin/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_timestamp"));
    }

    @Test
    void staleTimestampWinsOverTheSignature() throws Exception {
        String stale = Long.toString(Instant.now().getEpochSecond() - 3600);
        mvc.perform(get("/admin/info")
                        .header("X-Admin-Timestamp", stale)
                        .header("X-Admin-Signature", "sha256=" + "0".repeat(64)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("stale_timestamp"));
    }

    @Test
    void aSignatureDoesNotTransferToAnotherPath() throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(get("/admin/status")
                        .header("X-Admin-Timestamp", ts)
                        .header("X-Admin-Signature",
                                sign("test-admin-secret", ts, "GET", "/admin/info", new byte[0])))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_signature"));
    }

    @Test
    void aSignatureBindsTheQueryString() throws Exception {
        // Wrong-but-well-formed: signed the path, sent the path plus a query. A filter
        // that fed only the URI to the verifier would accept this.
        String ts = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(get("/admin/logs/tail?n=5")
                        .header("X-Admin-Timestamp", ts)
                        .header("X-Admin-Signature",
                                sign("test-admin-secret", ts, "GET", "/admin/logs/tail", new byte[0])))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_signature"));
    }

    @Test
    void aSignedRequestReachesTheController() throws Exception {
        mvc.perform(signedGet("/admin/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminContractVersion").value(1))
                .andExpect(jsonPath("$.service").value("tsanet-intercom-connector"));
    }

    @Test
    void aSignedQueryRequestVerifies() throws Exception {
        mvc.perform(signedGet("/admin/logs/tail?n=5"))
                .andExpect(status().isOk());
    }
}
