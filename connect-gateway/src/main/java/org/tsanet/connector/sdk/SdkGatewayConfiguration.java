package org.tsanet.connector.sdk;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.tsanet.connector.tsanet.TsanetGateway;

/**
 * Wires the SDK-backed gateway when {@code connector.tsanet.mode=sdk} (the fake is the
 * default elsewhere and refuses the prod profile, so prod without this configured
 * fails at startup rather than running on fixtures). Single env-configured member
 * ("default") for the pilot; multi-member is a registry call away, not a code change.
 */
@Configuration
@ConditionalOnProperty(name = "connector.tsanet.mode", havingValue = "sdk")
@EnableConfigurationProperties(SdkGatewayConfiguration.SdkGatewayProperties.class)
public class SdkGatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SdkGatewayConfiguration.class);

    /**
     * All values arrive via environment (see application.yml). testSubmission defaults
     * TRUE: creating real partner cases is the deliberate, per-deployment opt-in for
     * the pilot, never a default (upstream facade hardcodes true — connect-sdk-demo#6 —
     * this is the controllable replacement).
     */
    @ConfigurationProperties(prefix = "connector.tsanet.sdk")
    public record SdkGatewayProperties(String apiBaseUrl, String authMode, String principal,
            String secret, String entraTenantId, String entraAudience, String sqlitePath,
            Boolean testSubmission, Long readDeadlineMillis, Long submitDeadlineMillis) {

        public SdkGatewayProperties {
            if (testSubmission == null) {
                testSubmission = Boolean.TRUE;
            }
        }
    }

    @Bean(destroyMethod = "")
    MemberSessionRegistry memberSessionRegistry() {
        return new MemberSessionRegistry();
    }

    @Bean
    TsanetGateway sdkTsanetGateway(MemberSessionRegistry registry, SdkGatewayProperties props) {
        require(props.apiBaseUrl(), "connector.tsanet.sdk.api-base-url");
        require(props.authMode(), "connector.tsanet.sdk.auth-mode");
        require(props.principal(), "connector.tsanet.sdk.principal");
        require(props.secret(), "connector.tsanet.sdk.secret");
        require(props.sqlitePath(), "connector.tsanet.sdk.sqlite-path");
        Path sqliteParent = Path.of(props.sqlitePath()).toAbsolutePath().normalize().getParent();
        if (sqliteParent != null && !Files.isDirectory(sqliteParent) ) {
            try {
                Files.createDirectories(sqliteParent);
            } catch (Exception e) {
                throw new IllegalStateException("connector.tsanet.sdk.sqlite-path parent directory "
                        + sqliteParent + " cannot be created; refusing to start with an unusable"
                        + " SDK store path", e);
            }
        }
        MemberSessionRegistry.TsanetCredentials credentials = new MemberSessionRegistry.TsanetCredentials(
                props.authMode(), props.principal(), props.secret(),
                props.entraTenantId(), props.entraAudience());
        SdkTsanetGateway.Config config = new SdkTsanetGateway.Config(
                props.apiBaseUrl(),
                props.testSubmission(),
                props.readDeadlineMillis() == null ? 0 : props.readDeadlineMillis(),
                props.submitDeadlineMillis() == null ? 0 : props.submitDeadlineMillis());
        SdkTsanetGateway gateway = registry.gatewayFor("default", new MemberSessionRegistry.MemberBinding(
                props.apiBaseUrl(), props.sqlitePath(), credentials, config));
        // Deadline-bounded (1500ms) probe: warms the token so the first canvas read
        // does not pay login inside its own budget, and surfaces bad credentials in
        // the startup log instead of the first agent interaction. Never fails boot.
        log.info("SDK gateway wired for member 'default': base={} authMode={} testSubmission={} healthy={}",
                props.apiBaseUrl(), props.authMode(), props.testSubmission(), gateway.healthy());
        return gateway;
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required when connector.tsanet.mode=sdk;"
                    + " refusing to start half-configured");
        }
    }
}
