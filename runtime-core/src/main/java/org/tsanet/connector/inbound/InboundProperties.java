package org.tsanet.connector.inbound;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Workspace-specific configuration for inbound cases.
 *
 * <p>{@code collaborationTicketTypeId} is the Back-office ticket type that carries the
 * TSANet attributes (token, partner, respond-by). It is per-workspace — the dev
 * workspace's id is not the pilot's — so it has no default: an unset value refuses to
 * create rather than creating tickets of the wrong type, because the type is what
 * every later slice reads attributes from.
 */
@ConfigurationProperties(prefix = "connector.inbound")
public record InboundProperties(String collaborationTicketTypeId) {
}
