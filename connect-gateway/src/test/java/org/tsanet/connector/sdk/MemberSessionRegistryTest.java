package org.tsanet.connector.sdk;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The credential-fingerprint pattern from the SDK demo's EnvironmentService: one
 * (session + gateway) pair per member key, reused while the fingerprint matches,
 * rebuilt when credentials change, dropped on invalidate. Session construction opens
 * a real SQLite store (no network — login is lazy), so paths live under @TempDir.
 */
class MemberSessionRegistryTest {

    @TempDir
    Path tmp;

    private MemberSessionRegistry.MemberBinding binding(String secret) {
        return new MemberSessionRegistry.MemberBinding(
                "https://stub.invalid",
                tmp.resolve("member-" + UUID.randomUUID() + ".db").toString(),
                new MemberSessionRegistry.TsanetCredentials("password", "api@example.invalid", secret,
                        null, null),
                new SdkTsanetGateway.Config("https://stub.invalid", true, 1500, 4000));
    }

    @Test
    void sameCredentialsReuseTheSamePair() {
        MemberSessionRegistry registry = new MemberSessionRegistry();
        MemberSessionRegistry.MemberBinding binding = binding("secret-1");
        SdkTsanetGateway first = registry.gatewayFor("w1", binding);
        SdkTsanetGateway second = registry.gatewayFor("w1", binding);
        assertSame(first, second, "an unchanged fingerprint must reuse the cached pair");
    }

    @Test
    void changedSecretRebuildsThePair() {
        MemberSessionRegistry registry = new MemberSessionRegistry();
        SdkTsanetGateway first = registry.gatewayFor("w1", binding("secret-1"));
        SdkTsanetGateway second = registry.gatewayFor("w1", binding("secret-2"));
        assertNotSame(first, second, "rotated credentials are a DIFFERENT session, not a re-login");
    }

    @Test
    void membersNeverShareAPair() {
        MemberSessionRegistry registry = new MemberSessionRegistry();
        MemberSessionRegistry.MemberBinding binding = binding("secret-1");
        SdkTsanetGateway w1 = registry.gatewayFor("w1", binding);
        SdkTsanetGateway w2 = registry.gatewayFor("w2", binding);
        assertNotSame(w1, w2, "each member owns its ApiClient; sharing would bleed credentials");
    }

    @Test
    void changedEnvironmentRebuildsThePair() {
        // A session is a pure function of (environment, credentials); repointing the
        // base URL with unchanged credentials must not return a session aimed at the
        // old target (QA round-3 note).
        MemberSessionRegistry registry = new MemberSessionRegistry();
        MemberSessionRegistry.TsanetCredentials credentials =
                new MemberSessionRegistry.TsanetCredentials("password", "api@example.invalid", "s", null, null);
        SdkTsanetGateway.Config config = new SdkTsanetGateway.Config("https://a.invalid", true, 1500, 4000);
        SdkTsanetGateway first = registry.gatewayFor("w1", new MemberSessionRegistry.MemberBinding(
                "https://a.invalid", tmp.resolve("env-a.db").toString(), credentials, config));
        SdkTsanetGateway second = registry.gatewayFor("w1", new MemberSessionRegistry.MemberBinding(
                "https://b.invalid", tmp.resolve("env-b.db").toString(), credentials, config));
        assertNotSame(first, second);
    }

    @Test
    void toStringNeverPrintsTheSecret() {
        MemberSessionRegistry.MemberBinding binding = binding("hunter2-super-secret");
        org.junit.jupiter.api.Assertions.assertFalse(binding.toString().contains("hunter2"),
                "records render in logs and binding failures; the secret must be masked");
        org.junit.jupiter.api.Assertions.assertFalse(binding.credentials().toString().contains("hunter2"));
    }

    @Test
    void invalidateForcesARebuild() {
        MemberSessionRegistry registry = new MemberSessionRegistry();
        MemberSessionRegistry.MemberBinding binding = binding("secret-1");
        SdkTsanetGateway first = registry.gatewayFor("w1", binding);
        registry.invalidate("w1");
        assertNotSame(first, registry.gatewayFor("w1", binding));
    }
}
