package org.tsanet.connector.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Pins the verifier to the signing vectors embedded in Connect_Gateway's
 * docs/admin-contract.md — the vectors, not any implementation, are the cross-repo
 * source of truth, so these constants are copied from the document rather than derived
 * from any signer. Also pins the check ORDER the contract promises (timestamp validity,
 * freshness, secret presence, signature), because the live conformance suite asserts
 * it observably.
 */
class AdminSignaturesTest {

    private static final String V1_SIG = "sha256=9872526fb9f10765d3587246dafe069fe0627d2c4cdfbe62620d7a0ccea01c4c";
    private static final String V2_SIG = "sha256=eb7c353df791e3fdcef3b56f61350d3d603cf3389ce092db374707cdbc16f1d9";
    private static final String V3_SIG = "sha256=0900c07c775f87db97a64c58383c608f59450abf42343a678721e41c4020b7f2";
    private static final long V_TS = 1754700000L;

    @Test
    void vector1EmptyBodyGetVerifies() {
        assertNull(AdminSignatures.verify(Long.toString(V_TS), V1_SIG, "GET", "/admin/info",
                new byte[0], "vector-secret-1", V_TS + 10));
    }

    @Test
    void vector2BindsTheQueryString() {
        assertNull(AdminSignatures.verify(Long.toString(V_TS), V2_SIG, "GET", "/admin/logs/tail?n=50",
                new byte[0], "vector-secret-1", V_TS + 10));
        // Wrong-but-well-formed: same path, one digit of query changed, same valid-hex
        // signature. A verifier that ignored the query would accept this.
        assertEquals("invalid_signature",
                AdminSignatures.verify(Long.toString(V_TS), V2_SIG, "GET", "/admin/logs/tail?n=51",
                        new byte[0], "vector-secret-1", V_TS + 10));
    }

    @Test
    void vector3BindsTheBody() {
        byte[] body = "{\"value\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        assertNull(AdminSignatures.verify("1754700042", V3_SIG, "POST", "/admin/credentials/intercom",
                body, "vector-secret-2", 1754700052L));
        assertEquals("invalid_signature",
                AdminSignatures.verify("1754700042", V3_SIG, "POST", "/admin/credentials/intercom",
                        "{\"value\":\"y\"}".getBytes(StandardCharsets.UTF_8), "vector-secret-2", 1754700052L));
    }

    @Test
    void checkOrderIsTimestampFreshnessSecretSignature() {
        // Missing, then malformed, timestamps — before anything else is looked at.
        assertEquals("invalid_timestamp", AdminSignatures.verify(null, V1_SIG, "GET", "/admin/info",
                new byte[0], "vector-secret-1", V_TS));
        assertEquals("invalid_timestamp", AdminSignatures.verify("not-a-number", V1_SIG, "GET", "/admin/info",
                new byte[0], "vector-secret-1", V_TS));
        // Stale with a garbage signature: stale wins, proving freshness precedes HMAC.
        assertEquals("stale_timestamp", AdminSignatures.verify(Long.toString(V_TS), "sha256=" + "0".repeat(64),
                "GET", "/admin/info", new byte[0], "vector-secret-1", V_TS + 3600));
        // Fresh but no secret configured: fail closed before any HMAC.
        assertEquals("secret_not_configured", AdminSignatures.verify(Long.toString(V_TS), V1_SIG, "GET",
                "/admin/info", new byte[0], " ", V_TS));
        // Fresh, secret present, header missing or unprefixed.
        assertEquals("invalid_signature", AdminSignatures.verify(Long.toString(V_TS), null, "GET",
                "/admin/info", new byte[0], "vector-secret-1", V_TS));
        assertEquals("invalid_signature", AdminSignatures.verify(Long.toString(V_TS),
                "9872526fb9f10765d3587246dafe069fe0627d2c4cdfbe62620d7a0ccea01c4c", "GET",
                "/admin/info", new byte[0], "vector-secret-1", V_TS));
    }

    @Test
    void freshnessIsSymmetric() {
        // A future timestamp beyond the window is as stale as a past one (clock skew
        // cuts both ways).
        assertEquals("stale_timestamp", AdminSignatures.verify(Long.toString(V_TS + 3600), V1_SIG, "GET",
                "/admin/info", new byte[0], "vector-secret-1", V_TS));
        assertNull(AdminSignatures.verify(Long.toString(V_TS), V1_SIG, "GET", "/admin/info",
                new byte[0], "vector-secret-1", V_TS + AdminSignatures.FRESHNESS_SECONDS));
    }

    @Test
    void vector4WriteClassVerifiesAndBurnsExactlyOnce() {
        NonceRegistry nonces = new NonceRegistry();
        byte[] body = "{\"accessToken\":\"probe-me\"}".getBytes(StandardCharsets.UTF_8);
        String sig = "sha256=aaeb4e1843b3585f5626219f00483cf2fc485fb933e0ac545135b673a49c4fde";
        assertNull(AdminSignatures.verifyWriteClass("1754700100", sig, "6f1d2a90c3b44e58", "POST",
                "/admin/credentials/intercom-access-token/validate", body, "vector-secret-2",
                1754700110L, nonces));
        assertEquals("replayed_nonce",
                AdminSignatures.verifyWriteClass("1754700100", sig, "6f1d2a90c3b44e58", "POST",
                        "/admin/credentials/intercom-access-token/validate", body, "vector-secret-2",
                        1754700110L, nonces));
    }

    @Test
    void aMissingNonceOnAWriteIsItsOwnError() {
        assertEquals("invalid_nonce",
                AdminSignatures.verifyWriteClass("1754700100", "sha256=" + "0".repeat(64), null, "POST",
                        "/admin/credentials/x/validate", new byte[0], "vector-secret-2", 1754700100L,
                        new NonceRegistry()));
    }

    @Test
    void anInvalidSignatureDoesNotBurnTheNonce() {
        // Burning before verification would let unauthenticated garbage consume
        // registry capacity AND pre-burn a nonce a legitimate caller is about to use.
        NonceRegistry nonces = new NonceRegistry();
        byte[] body = "{\"accessToken\":\"probe-me\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("invalid_signature",
                AdminSignatures.verifyWriteClass("1754700100", "sha256=" + "0".repeat(64), "6f1d2a90c3b44e58",
                        "POST", "/admin/credentials/intercom-access-token/validate", body,
                        "vector-secret-2", 1754700110L, nonces));
        assertNull(AdminSignatures.verifyWriteClass("1754700100",
                "sha256=aaeb4e1843b3585f5626219f00483cf2fc485fb933e0ac545135b673a49c4fde",
                "6f1d2a90c3b44e58", "POST", "/admin/credentials/intercom-access-token/validate", body,
                "vector-secret-2", 1754700110L, nonces),
                "the nonce survived the failed attempt and works for the real caller");
    }

    @Test
    void aReadClassSignatureNeverPassesAWrite() {
        // Wrong-but-well-formed: valid 4-line signature for the same request, sent
        // with a nonce header. The nonce must PARTICIPATE, not decorate.
        NonceRegistry nonces = new NonceRegistry();
        byte[] body = "{\"accessToken\":\"probe-me\"}".getBytes(StandardCharsets.UTF_8);
        String readSig = "sha256=" + hmacHexForTest("vector-secret-2",
                "1754700100\nPOST\n/admin/credentials/intercom-access-token/validate\n"
                        + "4bcdb69d051520cbe3cc0ccd159243c0038a6479279909ae255c69bd76d3a4ae");
        assertEquals("invalid_signature",
                AdminSignatures.verifyWriteClass("1754700100", readSig, "6f1d2a90c3b44e58", "POST",
                        "/admin/credentials/intercom-access-token/validate", body, "vector-secret-2",
                        1754700110L, nonces));
    }

    private static String hmacHexForTest(String secret, String canonical) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void prefixIsCaseTolerant() {
        assertNull(AdminSignatures.verify(Long.toString(V_TS),
                "SHA256=9872526fb9f10765d3587246dafe069fe0627d2c4cdfbe62620d7a0ccea01c4c", "GET",
                "/admin/info", new byte[0], "vector-secret-1", V_TS));
    }
}
