package org.tsanet.connector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All secrets arrive via environment (see application.yml); empty values mean the
 * corresponding endpoints fail closed with 401 (docs/decisions.md D1).
 */
@ConfigurationProperties(prefix = "connector")
public record ConnectorProperties(
        String intercomClientSecret,
        String tsanetWebhookSecret,
        int maxBodyBytes,
        int dedupeCapacity,
        int queueCapacity) {

    public ConnectorProperties {
        if (maxBodyBytes <= 0) {
            maxBodyBytes = 1_048_576;
        }
        if (dedupeCapacity <= 0) {
            dedupeCapacity = 10_000;
        }
        if (queueCapacity <= 0) {
            queueCapacity = 10_000;
        }
    }
}
