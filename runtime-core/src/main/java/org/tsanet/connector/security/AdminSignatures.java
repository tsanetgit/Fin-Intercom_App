package org.tsanet.connector.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Verifier for the admin scheme of the adapter admin contract
 * (Connect_Gateway docs/admin-contract.md; docs/decisions.md D20, D21).
 *
 * <p>Distinct from the three body-HMAC schemes on purpose: admin reads are GETs with
 * empty bodies, so a signature over the body alone would be one static value per
 * secret - a captured header would replay against every admin GET forever. The
 * canonical string binds timestamp, method, request-target (path plus raw query,
 * exactly as sent) and the body hash; write-class requests additionally bind a
 * caller-generated NONCE as the fourth line (contract vector 4), burned exactly once
 * by {@link NonceRegistry} - and burned only AFTER the signature verifies, so
 * unauthenticated garbage cannot consume registry capacity.
 *
 * <p>The canonical string's timestamp line is the header value VERBATIM, not the
 * parsed number - the client signed the string it sent, and re-serializing the parse
 * would unverifiably accept representations the client never signed. The nonce line
 * is likewise the header verbatim.
 */
final class AdminSignatures {

    static final String TIMESTAMP_HEADER = "X-Admin-Timestamp";
    static final String SIGNATURE_HEADER = "X-Admin-Signature";
    static final String NONCE_HEADER = "X-Admin-Nonce";
    static final long FRESHNESS_SECONDS = 300;
    private static final String PREFIX = "sha256=";

    private AdminSignatures() {
    }

    /**
     * Read-class verification (four-line canonical). @return {@code null} when
     * verified, else the contract error code. Check order is pinned by the contract
     * and asserted by its live suite: timestamp validity, freshness, secret
     * presence, then (and only then) any HMAC work.
     */
    static String verify(String timestampHeader, String signatureHeader, String method, String requestTarget,
            byte[] body, String secret, long nowEpochSeconds) {
        String failed = preChecks(timestampHeader, secret, nowEpochSeconds);
        if (failed != null) {
            return failed;
        }
        String canonical = timestampHeader + "\n" + method.toUpperCase() + "\n" + requestTarget + "\n"
                + sha256Hex(body);
        return checkSignature(signatureHeader, canonical, secret);
    }

    /**
     * Write-class verification (five-line canonical, nonce burned on success). The
     * nonce presence check precedes the HMAC (cheap before expensive, and the error
     * distinguishes "you forgot the write protocol" from "your signature is wrong"),
     * but registry acceptance runs LAST so only a fully verified request can burn or
     * be refused as a replay.
     */
    static String verifyWriteClass(String timestampHeader, String signatureHeader, String nonceHeader,
            String method, String requestTarget, byte[] body, String secret, long nowEpochSeconds,
            NonceRegistry nonces) {
        String failed = preChecks(timestampHeader, secret, nowEpochSeconds);
        if (failed != null) {
            return failed;
        }
        if (nonceHeader == null || nonceHeader.isBlank()) {
            return "invalid_nonce";
        }
        String canonical = timestampHeader + "\n" + method.toUpperCase() + "\n" + requestTarget + "\n"
                + nonceHeader + "\n" + sha256Hex(body);
        String signature = checkSignature(signatureHeader, canonical, secret);
        if (signature != null) {
            return signature;
        }
        return nonces.accept(nonceHeader, nowEpochSeconds);
    }

    private static String preChecks(String timestampHeader, String secret, long nowEpochSeconds) {
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
        return null;
    }

    private static String checkSignature(String signatureHeader, String canonical, String secret) {
        if (signatureHeader == null) {
            return "invalid_signature";
        }
        String provided = signatureHeader.trim();
        if (!provided.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return "invalid_signature";
        }
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
