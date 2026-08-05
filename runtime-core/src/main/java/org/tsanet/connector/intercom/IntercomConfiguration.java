package org.tsanet.connector.intercom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the real Intercom gateway when {@code connector.intercom.mode=rest}; the fake
 * is the default elsewhere and refuses the prod profile, so prod without this
 * configured fails at startup. Single env-configured workspace for the pilot;
 * per-workspace token resolution arrives with Phase 2 inbound (docs/decisions.md D10).
 */
@Configuration
@ConditionalOnProperty(name = "connector.intercom.mode", havingValue = "rest")
@EnableConfigurationProperties(IntercomConfiguration.IntercomProperties.class)
public class IntercomConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IntercomConfiguration.class);

    @ConfigurationProperties(prefix = "connector.intercom")
    public record IntercomProperties(String mode, String accessToken, String adminId, String apiBaseUrl) {

        public IntercomProperties {
            if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
                apiBaseUrl = "https://api.intercom.io";
            }
        }

        /** Never prints the token: Spring renders these in binding-failure messages. */
        @Override
        public String toString() {
            return "IntercomProperties[mode=" + mode + ", accessToken=***, adminId=" + adminId
                    + ", apiBaseUrl=" + apiBaseUrl + "]";
        }
    }

    @Bean
    IntercomGateway restIntercomGateway(IntercomProperties props) {
        if (props.accessToken() == null || props.accessToken().isBlank()) {
            throw new IllegalStateException("connector.intercom.access-token is required when"
                    + " connector.intercom.mode=rest; refusing to start half-configured");
        }
        RestIntercomGateway gateway = RestIntercomGateway.create(
                props.apiBaseUrl(), props.accessToken(), props.adminId());
        log.info("Intercom gateway wired: base={} healthy={}", props.apiBaseUrl(), gateway.healthy());
        return gateway;
    }
}
