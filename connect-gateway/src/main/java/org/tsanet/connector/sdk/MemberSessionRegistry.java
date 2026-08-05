package org.tsanet.connector.sdk;

import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.auth.AccountAuthConfig;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.PasswordAuthConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One SDK session and one {@link SdkTsanetGateway} per member, keyed by the Intercom
 * workspace (the multi-tenant seam, mirroring runtime-core's WorkspaceRegistry). Ported
 * from the pattern validated in the SDK demo's EnvironmentService: the SDK takes auth
 * at construction and offers no re-login, so a session is a pure function of
 * (environment, credentials) — the cache stores a SHA-256 credentials fingerprint and
 * rebuilds when it stops matching, and changed credentials yield a DIFFERENT session
 * rather than a logged-out one.
 *
 * <p>The gateway is cached WITH its session on purpose: each gateway owns its own
 * ApiClient, and pairing them per member key is what guarantees no ApiClient — a
 * stateful object — is ever shared across members (cross-tenant credential bleed;
 * docs/decisions.md D9).
 */
public final class MemberSessionRegistry {

    /** Credential material for one member. Mode: {@code password} or {@code client_credentials}. */
    public record TsanetCredentials(String mode, String principal, String secret,
            String entraTenantId, String entraAudience) {

        public TsanetCredentials {
            if (mode == null || mode.isBlank()) {
                throw new IllegalArgumentException("auth mode is required (password | client_credentials)");
            }
            mode = mode.toLowerCase(Locale.ROOT);
            if (!"password".equals(mode) && !"client_credentials".equals(mode)) {
                throw new IllegalArgumentException("unknown auth mode: " + mode);
            }
        }

        AccountAuthConfig toAuthConfig() {
            if ("client_credentials".equals(mode)) {
                // tokenUrl and scope stay null: the SDK derives the Entra token
                // endpoint from the tenant and the {audience}/.default scope.
                return new ClientCredentialsAuthConfig(entraTenantId, null, principal, secret,
                        entraAudience, null);
            }
            return new PasswordAuthConfig(principal, secret);
        }

        /** Never prints the secret: records auto-generate toString, and Spring binding
         * failures / actuator dumps render component values (QA round-3 note). */
        @Override
        public String toString() {
            return "TsanetCredentials[mode=" + mode + ", principal=" + principal
                    + ", secret=***, entraTenantId=" + entraTenantId
                    + ", entraAudience=" + entraAudience + "]";
        }
    }

    /** Everything needed to build one member's session + gateway. */
    public record MemberBinding(String apiBaseUrl, String sqlitePath, TsanetCredentials credentials,
            SdkTsanetGateway.Config gatewayConfig) {

        @Override
        public String toString() {
            return "MemberBinding[apiBaseUrl=" + apiBaseUrl + ", sqlitePath=" + sqlitePath
                    + ", credentials=" + credentials + "]"; // credentials masks its secret
        }
    }

    private record Holder(TsaNetApiSession session, SdkTsanetGateway gateway, String fingerprint) {
    }

    private final Map<String, Holder> members = new ConcurrentHashMap<>();

    /**
     * The member's gateway, reusing the cached (session + gateway) pair while the
     * credential fingerprint is unchanged and rebuilding both when it is not.
     * compute() holds the bin lock across construction so there is exactly one
     * session per key even though construction is the expensive side.
     */
    public SdkTsanetGateway gatewayFor(String memberKey, MemberBinding binding) {
        String fingerprint = fingerprint(binding);
        return members.compute(memberKey, (key, existing) -> {
            if (existing != null && existing.fingerprint().equals(fingerprint)) {
                return existing;
            }
            if (existing != null) {
                existing.gateway().close();
            }
            TsaNetApiSession session = TsaNetApi.initialize(new TsaNetApiConfiguration(
                    binding.apiBaseUrl(),
                    binding.sqlitePath(),
                    binding.credentials().principal(),
                    binding.credentials().toAuthConfig()));
            return new Holder(session, SdkTsanetGateway.create(session, binding.gatewayConfig()), fingerprint);
        }).gateway();
    }

    /**
     * Drops the member's cached pair so the next request rebuilds it (credential
     * rotation). TODO(multi-member): close() interrupts the old gateway's in-flight
     * calls and later use of a closed gateway surfaces a raw RejectedExecutionException
     * from its executor; when rotation becomes a live operation, drain instead.
     */
    public void invalidate(String memberKey) {
        Holder removed = members.remove(memberKey);
        if (removed != null) {
            removed.gateway().close();
        }
    }

    /**
     * Hashed so the cache key never holds a second plaintext copy of the secret. The
     * ENVIRONMENT is part of the fingerprint, not just the credentials: a session is a
     * pure function of (environment, credentials), so repointing api-base-url or
     * rotating the Entra tenant with an unchanged secret must rebuild rather than
     * return a cached session aimed at the old target (QA round-3 note).
     */
    private static String fingerprint(MemberBinding binding) {
        TsanetCredentials credentials = binding.credentials();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {
                    credentials.mode(), credentials.principal(), credentials.secret(),
                    credentials.entraTenantId(), credentials.entraAudience(),
                    binding.apiBaseUrl(), binding.sqlitePath() }) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to fingerprint credentials", e);
        }
    }
}
