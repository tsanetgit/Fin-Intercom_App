package org.tsanet.connector.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * That {@code connector.links.store=sqlite} actually selects the durable cursor bean,
 * and that the nested {@code LinkPathProperties} record is scanned and bound.
 *
 * <p><b>Top-level, and named {@code *Test}, on purpose.</b> This started as a static
 * nested class inside {@code SqlitePollerStateStoreTest} and therefore NEVER RAN:
 * Surefire selects by class-file name, and
 * {@code SqlitePollerStateStoreTest$SqliteStoreIsWiredUnderTheSqliteProfile} matches
 * none of its include patterns. A clean run produces no report for it at all.
 *
 * <p>Worse, it looked verified. Running {@code -Dtest='SqlitePollerStateStoreTest*'}
 * DOES select it by wildcard, so a targeted run reported "Tests run: 1" and left a
 * surefire report behind — which a later inspection then read as evidence it executes
 * normally. The check passed under a harness nobody uses. That is the same vacuous
 * coverage this PR twice claims to be guarding against, and the same shape as the sdk CI
 * job that reported success while running nothing (#22 QA round 2).
 *
 * <p>It also read the outer class's static {@code @TempDir}, which as an independent
 * container would have been null. Own {@code @TempDir} here.
 */
@SpringBootTest
class SqlitePollerStateWiringTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("connector.tsanet.mode", () -> "fake");
        registry.add("connector.intercom.mode", () -> "fake");
        registry.add("connector.links.store", () -> "sqlite");
        registry.add("connector.links.sqlite-path", () -> dir.resolve("wired.db").toString());
        // Far enough out that no cycle fires during the test.
        registry.add("connector.inbound.reconcile-interval-ms", () -> "3600000");
        registry.add("connector.inbound.reconcile-initial-delay-ms", () -> "3600000");
    }

    @Autowired
    private PollerStateStore store;

    @Test
    void theDurableStoreIsSelectedUnderTheSqliteProfile() {
        assertEquals(SqlitePollerStateStore.class, store.getClass(),
                "store=sqlite must select the durable cursor rather than the in-memory one —"
                        + " this is the branch production runs, and nothing else asserts it");
    }
}
