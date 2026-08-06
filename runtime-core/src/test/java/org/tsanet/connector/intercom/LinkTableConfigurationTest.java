package org.tsanet.connector.intercom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.Map;

/**
 * Which store gets wired, and which misconfigurations are refused at startup. These run
 * the real {@code @ConditionalOnProperty} conditions rather than calling the factory
 * methods directly — the conditions are the part that decides, and a test that bypasses
 * them cannot see a value that matches neither.
 */
class LinkTableConfigurationTest {

    @TempDir
    Path dir;

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(LinkTableConfiguration.class);

    @Test
    void theDefaultIsTheMemoryStore() {
        runner.run(context -> assertThat(context).getBean(SubmissionLinkTable.class)
                .isInstanceOf(InMemorySubmissionLinkTable.class));
    }

    @Test
    void sqliteModeWiresTheDurableStore() {
        runner.withPropertyValues("connector.links.store=sqlite",
                        "connector.links.sqlite-path=" + dir.resolve("links.db"))
                .run(context -> assertThat(context).getBean(SubmissionLinkTable.class)
                        .isInstanceOf(SqliteSubmissionLinkTable.class));
    }

    @Test
    void theConditionsMatchCaseInsensitivelyAndTheValidationFollowsThem() {
        // Measured, not assumed: @ConditionalOnProperty compares with equalsIgnoreCase,
        // so SQLITE really does select the durable store and rejecting it would refuse a
        // working deployment. This test is the receipt the validation rule cites.
        runner.withPropertyValues("connector.links.store=SQLITE",
                        "connector.links.sqlite-path=" + dir.resolve("upper.db"))
                .run(context -> assertThat(context).getBean(SubmissionLinkTable.class)
                        .isInstanceOf(SqliteSubmissionLinkTable.class));
    }

    @Test
    void aStoreValueThatSelectsNoBeanIsRejectedByName() {
        // The wrong-but-well-formed inputs: a stray trailing space from an env var, an
        // empty value (which beats the yml default), and a plausible store that does not
        // exist here. The conditions do not trim, so each of these matches neither one;
        // without this check startup fails with a bean-not-found error naming no cause.
        for (String value : new String[] {"sqlite ", "", "postgres"}) {
            // Raw property source, NOT withPropertyValues: that helper trims its "k=v"
            // strings, so the trailing-space case would arrive as a clean "sqlite" and
            // this test would pass while measuring nothing.
            withRawStore(value).run(context -> assertThat(context).hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("must be exactly 'memory' or 'sqlite'")
                    .hasStackTraceContaining("'" + value + "'"));
        }
    }

    private ApplicationContextRunner withRawStore(String value) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("raw-store",
                        Map.of("connector.links.store", value))));
    }

    @Test
    void theVolatileStoreRefusesTheProdProfile() {
        // Same discipline as the fake gateways: the volatile store must be impossible to
        // ship, whether it was chosen explicitly or inherited as the default.
        prod().run(context -> assertThat(context).hasFailed().getFailure()
                .hasStackTraceContaining("would orphan every open case"));
        prod().withPropertyValues("connector.links.store=memory")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("would orphan every open case"));
    }

    @Test
    void prodRefusesARelativeSqlitePathAndAcceptsAnAbsoluteOne() {
        // data/links.db is the shipped default and is exactly the silent case: it looks
        // durable, resolves against the container working directory, and dies with the
        // container like the maps it replaced.
        prod().withPropertyValues("connector.links.store=sqlite",
                        "connector.links.sqlite-path=data/links.db")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("must be absolute under the prod profile"));

        prod().withPropertyValues("connector.links.store=sqlite",
                        "connector.links.sqlite-path=" + dir.resolve("prod-links.db").toAbsolutePath())
                .run(context -> assertThat(context).getBean(SubmissionLinkTable.class)
                        .isInstanceOf(SqliteSubmissionLinkTable.class));
    }

    private ApplicationContextRunner prod() {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"));
    }
}
