package org.tsanet.connector.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Hmacs {

    private Hmacs() {
    }

    static String hmacHex(String algorithm, String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            return hex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure: " + algorithm, e);
        }
    }

    /** Constant-time comparison of two hex strings, case-insensitive. */
    static boolean constantTimeHexEquals(String expectedHex, String providedHex) {
        if (expectedHex == null || providedHex == null) {
            return false;
        }
        byte[] a = expectedHex.toLowerCase().getBytes(StandardCharsets.UTF_8);
        byte[] b = providedHex.toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
