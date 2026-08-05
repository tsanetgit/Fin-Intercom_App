package org.tsanet.connector.registry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory registry for the skeleton and the single-workspace pilot. */
@Component
public class InMemoryWorkspaceRegistry implements WorkspaceRegistry {

    private final Map<String, MemberConfig> byWorkspace = new ConcurrentHashMap<>();

    @Override
    public Optional<MemberConfig> find(String workspaceId) {
        return Optional.ofNullable(byWorkspace.get(workspaceId));
    }

    @Override
    public void put(MemberConfig config) {
        byWorkspace.put(config.workspaceId(), config);
    }
}
