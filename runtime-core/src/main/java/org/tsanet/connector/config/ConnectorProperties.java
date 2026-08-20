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
        int queueCapacity,
        String adminApiSecret,
        String publicBaseUrl,
        /*
         * Hardened-profile mode (#65): refuse NEW outbound collaborations from plain
         * conversations, so a workspace that drops the "Write conversations" grant
         * gets a designed canvas refusal instead of 401-failed annotations after a
         * partner already has the case. Gates the canvas entry points only; the two
         * link-creation sites are the canvas submit path (gated) and the inbound
         * handler (ticket-anchored by construction), enumerated in the #65 PR.
         */
        boolean ticketsOnly) {

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
