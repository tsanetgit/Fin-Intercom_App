package org.tsanet.connector.registry;

import java.util.Optional;

/**
 * Maps an Intercom workspace id to its member configuration. Intercom webhooks are
 * app-level (one endpoint receives every installed workspace), so this lookup is the
 * multi-tenant seam of the runtime.
 */
public interface WorkspaceRegistry {

    Optional<MemberConfig> find(String workspaceId);

    void put(MemberConfig config);
}
