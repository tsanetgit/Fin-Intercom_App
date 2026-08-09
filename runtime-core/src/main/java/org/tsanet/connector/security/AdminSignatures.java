package org.tsanet.connector.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Verifier for the admin scheme of the adapter admin contract
 * (Connect_Gateway docs/admin-contract.md, version 1; docs/decisions.md D20).
 *
 * <p>Distinct from the three body-HMAC schemes on purpose: admin reads are GETs with
 * empty bodies, so a signature over the body alone would be one static value per
 * secret — a captured header would replay against every admin GET forever. The
 * canonical string here binds timestamp, method, request-target (path plus raw query,
 * exactly as sent) and the body hash, and freshness bounds the replay window to the
 * contract's 300 seconds. Replay of the identical read inside that window is accepted
 * and recorded in the contract; the surface is read-only.
 *
 * <p>The canonical string's timestamp line is the header value VERBATIM, not the parsed
 * number — the client signed the string it sent, and re-serializing the parse would
 * unverifiably accept representations the client never signed.
 */
final class AdminSignatures {

    static final String TIMESTAMP_HEADER = "X-Admin-Timestamp";
    static final String SIGNATURE_HEADER = "X-Admin-Signature";
    static final long FRESHNESS_SECONDS = 300;
    private static final String PREFIX = "sha256=";

    private AdminSignatures() {
    }

    /**
     * @return {@code null} when verified, else the contract error code. Check order is
     *     pinned by the contract and asserted by its live suite: timestamp validity,
     *     freshness, secret presence, then (and only then) any HMAC work.
     */
    static String verify(String timestampHeader, String signatureHeader, String method, String requestTarget,
            byte[] body, String secret, long nowEpochSeconds) {
        if (timestampHeader == null) {
            return "invalid_timestamp";
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException notInteger) {
            return "invalid_timestamp";
        }
        if (Math.abs(nowEpochSeconds - timestamp) > FRESHNESS_SECONDS) {
            return "stale_timestamp";
        }
        if (secret == null || secret.isBlank()) {
            return "secret_not_configured";
        }
        if (signatureHeader == null) {
            return "invalid_signature";
        }
        String provided = signatureHeader.trim();
        if (!provided.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return "invalid_signature";
        }
        String canonical = timestampHeader + "\n" + method.toUpperCase() + "\n" + requestTarget + "\n"
                + sha256Hex(body);
        String expected = Hmacs.hmacHex("HmacSHA256", secret, canonical.getBytes(StandardCharsets.UTF_8));
        return Hmacs.constantTimeHexEquals(expected, provided.substring(PREFIX.length()))
                ? null
                : "invalid_signature";
    }

    private static String sha256Hex(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
