package org.tsanet.connector.sdk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.tsanet.connector.tsanet.TsanetGateway;

/**
 * Startup wiring is what unit tests cannot catch: property binding (kebab-case to the
 * record constructor), the mode gate, and the fail-fasts. Offline-safe — session
 * construction opens SQLite only (login is lazy), and the warmup health probe is
 * deadline-bounded and never fails boot.
 */
class SdkGatewayConfigurationTest {

    @TempDir
    Path tmp;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(SdkGatewayConfiguration.class));

    private ApplicationContextRunner fullyConfigured() {
        return runner.withPropertyValues(
                "connector.tsanet.mode=sdk",
                "connector.tsanet.sdk.api-base-url=https://stub.invalid",
                "connector.tsanet.sdk.auth-mode=password",
                "connector.tsanet.sdk.principal=api@example.invalid",
                "connector.tsanet.sdk.secret=not-a-real-secret",
                "connector.tsanet.sdk.sqlite-path=" + tmp.resolve("wire.db"));
    }

    @Test
    void modeSdkWithFullPropertiesWiresTheGateway() {
        fullyConfigured().run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null,
                    () -> "context must start: " + ctx.getStartupFailure());
            assertNotNull(ctx.getBean(TsanetGateway.class));
        });
    }

    @Test
    void missingPropertyFailsStartupNamingTheProperty() {
        runner.withPropertyValues(
                "connector.tsanet.mode=sdk",
                "connector.tsanet.sdk.api-base-url=https://stub.invalid",
                "connector.tsanet.sdk.auth-mode=password",
                "connector.tsanet.sdk.principal=api@example.invalid",
                "connector.tsanet.sdk.sqlite-path=" + tmp.resolve("wire.db"))
                .run(ctx -> {
                    assertNotNull(ctx.getStartupFailure(), "half-configured sdk mode must refuse to start");
                    String chain = String.valueOf(ctx.getStartupFailure())
                            + causeChain(ctx.getStartupFailure());
                    assertTrue(chain.contains("connector.tsanet.sdk.secret"),
                            "the failure must name the missing property");
                });
    }

    @Test
    void clientCredentialsModeRequiresEntraFields() {
        // healthy() only LOGS auth failures, so a half-configured client_credentials
        // deployment must be caught at startup, not at the first agent interaction.
        runner.withPropertyValues(
                "connector.tsanet.mode=sdk",
                "connector.tsanet.sdk.api-base-url=https://stub.invalid",
                "connector.tsanet.sdk.auth-mode=client_credentials",
                "connector.tsanet.sdk.principal=client-id",
                "connector.tsanet.sdk.secret=client-secret",
                "connector.tsanet.sdk.sqlite-path=" + tmp.resolve("cc.db"))
                .run(ctx -> {
                    assertNotNull(ctx.getStartupFailure());
                    assertTrue((String.valueOf(ctx.getStartupFailure()) + causeChain(ctx.getStartupFailure()))
                            .contains("connector.tsanet.sdk.entra-tenant-id"));
                });
    }

    @Test
    void propertiesToStringMasksTheSecret() {
        SdkGatewayConfiguration.SdkGatewayProperties props =
                new SdkGatewayConfiguration.SdkGatewayProperties("https://stub.invalid", "password",
                        "api@example.invalid", "hunter2-super-secret", null, null, "x.db", null, null, null);
        assertTrue(props.toString().contains("secret=***"));
        assertFalse(props.toString().contains("hunter2"));
    }

    @Test
    void fakeModeLeavesTheConfigurationInert() {
        runner.withPropertyValues("connector.tsanet.mode=fake").run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null);
            assertFalse(ctx.getBeansOfType(TsanetGateway.class).size() > 0,
                    "no SDK gateway bean may exist outside mode=sdk");
        });
    }

    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            sb.append(" | ").append(cause.getMessage());
        }
        return sb.toString();
    }
}
