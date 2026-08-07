package org.tsanet.connector.inbound;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Chooses where the reconciliation cursor lives, following the SAME
 * {@code connector.links.store} switch as the link table rather than introducing a
 * second storage axis. One decision, one place to get it right — and it keeps the cursor
 * on the same volume as the links it is a cursor over.
 *
 * <p><b>The in-memory cursor is permitted under prod, and the in-memory link table is
 * not.</b> That looks inconsistent and is deliberate: the failures are not comparable.
 * Losing links orphans every open case with no way to recover them. Losing the cursor
 * costs one wider query, because the poller re-scans the lookback window and its
 * prefilter skips everything already linked. Refusing to start over a volatile cursor
 * would be a guard out of proportion to its risk.
 */
@Configuration
public class PollerStateConfiguration {

    @Bean
    @ConditionalOnProperty(name = "connector.links.store", havingValue = "memory",
            matchIfMissing = true)
    PollerStateStore inMemoryPollerStateStore() {
        return new PollerStateStore.InMemory();
    }

    @Bean
    @ConditionalOnProperty(name = "connector.links.store", havingValue = "sqlite")
    PollerStateStore sqlitePollerStateStore(LinkPathProperties props, Environment environment) {
        String path = props.sqlitePath();
        if (path == null || path.isBlank()) {
            // Same message shape as the link table's own refusal: the operator selected
            // a durable store and gave it nowhere to live.
            throw new IllegalStateException("connector.links.sqlite-path is required when"
                    + " connector.links.store=sqlite");
        }
        return new SqlitePollerStateStore(path);
    }

    /**
     * Reads the link store's path without depending on
     * {@code LinkTableConfiguration.LinkProperties}, which is package-private to the
     * intercom package. Binding the one field needed here beats widening that type's
     * visibility for a neighbour.
     */
    @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "connector.links")
    public record LinkPathProperties(String sqlitePath) {
    }
}
