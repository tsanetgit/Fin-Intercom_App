package org.tsanet.connector.intercom;

import java.nio.file.Paths;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Chooses the link store: {@code connector.links.store=memory} (default, for tests and
 * local development) or {@code sqlite} (durable, docs/decisions.md D12).
 *
 * <p>Every way of ending up without a durable store in production is refused at startup,
 * because none of them announces itself later: the failure mode is not a wrong answer but
 * an empty one — after a restart every open case is unroutable and inbound updates go
 * nowhere. So: the memory store refuses the prod profile (as the fake gateways do), a
 * store value that selects no bean is rejected by name rather than surfacing as a
 * NoSuchBeanDefinitionException, and under prod the SQLite path must be absolute, since a
 * relative one resolves against the container working directory — the overlay filesystem
 * D12 warns about, wearing a durable store's name.
 */
@Configuration
@EnableConfigurationProperties(LinkTableConfiguration.LinkProperties.class)
public class LinkTableConfiguration {

    @ConfigurationProperties(prefix = "connector.links")
    public record LinkProperties(String store, String sqlitePath) {

        public LinkProperties {
            if (store == null) {
                store = "memory"; // property absent: matchIfMissing wires the memory store
            }
            // Mirrors the @ConditionalOnProperty conditions below exactly, because those
            // are what actually select a bean: they compare case-INSENSITIVELY (verified
            // in LinkTableConfigurationTest, not assumed) and they do not trim, so
            // "SQLITE" wires the durable store while "sqlite " from an env var matches
            // neither condition. Validating any stricter would reject a config that works;
            // any looser, and a value that selects nothing surfaces as a bean-not-found
            // error naming no cause.
            if (!store.equalsIgnoreCase("memory") && !store.equalsIgnoreCase("sqlite")) {
                throw new IllegalStateException("connector.links.store must be exactly 'memory' or"
                        + " 'sqlite'; got '" + store + "', which selects no link store at all");
            }
        }
    }

    @Bean
    @ConditionalOnProperty(name = "connector.links.store", havingValue = "memory", matchIfMissing = true)
    SubmissionLinkTable inMemorySubmissionLinkTable(Environment environment) {
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException("InMemorySubmissionLinkTable is active under the prod"
                    + " profile; a restart would orphan every open case. Set connector.links.store=sqlite");
        }
        return new InMemorySubmissionLinkTable();
    }

    @Bean
    @ConditionalOnProperty(name = "connector.links.store", havingValue = "sqlite")
    SubmissionLinkTable sqliteSubmissionLinkTable(LinkProperties props, Environment environment) {
        String path = props.sqlitePath();
        if (path != null && !path.isBlank() && environment.matchesProfiles("prod")
                && !Paths.get(path).isAbsolute()) {
            throw new IllegalStateException("connector.links.sqlite-path must be absolute under the"
                    + " prod profile; '" + path + "' resolves against the working directory, which on"
                    + " a container filesystem is as volatile as the in-memory store");
        }
        return new SqliteSubmissionLinkTable(path); // blank path refuses there, with its own message
    }
}
