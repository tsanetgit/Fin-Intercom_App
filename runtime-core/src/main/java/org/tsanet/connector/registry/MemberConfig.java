package org.tsanet.connector.registry;

/**
 * Per-workspace member configuration. Skeleton shape only; Phase 1 adds the ticket
 * type id, team routing, and a reference (never the value) to the member's TSANet
 * credentials in the secret store.
 */
public record MemberConfig(String workspaceId, String memberName, String tsanetEnvironment) {
}
