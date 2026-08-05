package org.tsanet.connector.sdk;

import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.TsaNetApiSession;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tsanet.connector.tsanet.TsanetGateway;

/**
 * Minimal Connect SDK binding proving the module wiring and the pin
 * (docs/decisions.md D6). A session is a pure function of (environment, credentials);
 * per-workspace session caching by credential fingerprint arrives in Phase 1, ported
 * from the pattern validated in the SDK demo work. Not a Spring component on purpose:
 * Phase 1 wires it explicitly per member, and nothing in any canvas path may call it
 * (docs/decisions.md D2).
 */
public final class SdkTsanetGateway implements TsanetGateway {

    private static final Logger log = LoggerFactory.getLogger(SdkTsanetGateway.class);

    private final Supplier<TsaNetApiSession> sessions;

    public SdkTsanetGateway(Supplier<TsaNetApiSession> sessions) {
        this.sessions = sessions;
    }

    /** Password-mode factory; OAuth client-credentials mode arrives with Phase 1 wiring. */
    public static SdkTsanetGateway withPassword(String apiBaseUrl, String sqlitePath, String username,
            String password) {
        return new SdkTsanetGateway(
                () -> TsaNetApi.initialize(TsaNetApiConfiguration.of(apiBaseUrl, sqlitePath, username, password)));
    }

    @Override
    public boolean healthy() {
        // Phase 1 replaces this with a real credential probe (a test-flagged case, not
        // /v1/me, which is not role-gated and proves nothing about case rights).
        try {
            return sessions.get() != null;
        } catch (RuntimeException e) {
            log.warn("SDK session construction failed: {}", e.getMessage());
            return false;
        }
    }
}
