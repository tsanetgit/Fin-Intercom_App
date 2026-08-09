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
    void prefixIsCaseTolerant() {
        assertNull(AdminSignatures.verify(Long.toString(V_TS),
                "SHA256=9872526fb9f10765d3587246dafe069fe0627d2c4cdfbe62620d7a0ccea01c4c", "GET",
                "/admin/info", new byte[0], "vector-secret-1", V_TS));
    }
}
