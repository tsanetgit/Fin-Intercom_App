package org.tsanet.connector.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Proves {@code SdkCacheRetentionSweep}'s stacked conditionals actually gate bean
 * registration, not just that its own logic behaves once constructed (that half is
 * {@link SdkCacheRetentionSweepTest}). A mistake in the conditionals means the bean
 * silently never runs in production with no compile-time signal — the "green CI,
 * dead feature" shape this codebase has hit before (docs/decisions.md D11) — so
 * it's proven here, not assumed. Sets {@code gateway.tsanet.mode} directly rather
 * than {@code connector.tsanet.mode}: the YAML bridge between them is
 * {@link EngineWiringSdkModeTest}'s job, not this test's.
 */
class CacheRetentionSweepWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SdkCacheRetentionSweep.class);

    @Test
    void registersUnderSdkModeWithNonBlankRetention() {
        runner.withPropertyValues(
                "gateway.tsanet.mode=sdk",
                "connector.sdk-cache.retention=30d",
                "gateway.tsanet.sdk.sqlite-path=target/cache-retention-wiring-sdk.db")
                .run(ctx -> assertThat(ctx).hasSingleBean(SdkCacheRetentionSweep.class));
    }

    @Test
    void doesNotRegisterWithBlankRetention() {
        runner.withPropertyValues(
                "gateway.tsanet.mode=sdk",
                "gateway.tsanet.sdk.sqlite-path=target/cache-retention-wiring-blank.db")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SdkCacheRetentionSweep.class));
    }

    @Test
    void doesNotRegisterOutsideSdkMode() {
        runner.withPropertyValues(
                "gateway.tsanet.mode=fake",
                "connector.sdk-cache.retention=30d",
                "gateway.tsanet.sdk.sqlite-path=target/cache-retention-wiring-fake.db")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SdkCacheRetentionSweep.class));
    }
}
