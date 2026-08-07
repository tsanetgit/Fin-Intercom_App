package org.tsanet.connector.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The durable cursor store, which is what production actually runs.
 *
 * <p>It had no tests and no executions at all when it was written — the whole poller
 * suite ran on the in-memory store, so the branch that ships was the branch nobody had
 * ever exercised (#22 QA). This covers the round trip, the corrupt-value fallback, and
 * the wiring: that {@code connector.links.store=sqlite} actually selects this bean and
 * that the nested {@code LinkPathProperties} is scanned, neither of which any unit test
 * of the class itself can tell you.
 */
class SqlitePollerStateStoreTest {

    @TempDir
    static Path dir;

    @Test
    void aCursorSurvivesAReopen() {
        // The entire point of the durable store: a restart must not re-scan.
        Path db = dir.resolve("roundtrip.db");
        Instant mark = Instant.parse("2026-08-07T12:34:56.789Z");
        new SqlitePollerStateStore(db.toString()).cursor(mark);

        assertEquals(Optional.of(mark), new SqlitePollerStateStore(db.toString()).cursor(),
                "a fresh instance on the same file must read the mark back, to the fraction");
    }

    @Test
    void anAbsentCursorIsEmptyNotEpoch() {
        // Empty is what makes the poller fall back to a BOUNDED lookback. Answering
        // epoch here would make a first run ask an unpaginated endpoint for every case
        // that has ever existed.
        assertTrue(new SqlitePollerStateStore(dir.resolve("fresh.db").toString()).cursor().isEmpty());
    }

    @Test
    void theLatestWriteWins() {
        Path db = dir.resolve("upsert.db");
        SqlitePollerStateStore store = new SqlitePollerStateStore(db.toString());
        store.cursor(Instant.parse("2026-08-01T00:00:00Z"));
        store.cursor(Instant.parse("2026-08-02T00:00:00Z"));
        assertEquals(Optional.of(Instant.parse("2026-08-02T00:00:00Z")), store.cursor(),
                "the ON CONFLICT upsert must replace, not accumulate rows");
    }

    @Test
    void anUnparseableCursorFallsBackInsteadOfWedgingEveryCycle() throws SQLException {
        // A corrupt value must read as ABSENT, not throw: absent falls back to the
        // lookback and keeps working, while throwing would wedge every future cycle on
        // one bad row — worse than a re-scan guard-create already absorbs.
        Path db = dir.resolve("corrupt.db");
        new SqlitePollerStateStore(db.toString()).cursor(Instant.now());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE poller_state SET value = 'not-a-timestamp'");
        }
        assertTrue(new SqlitePollerStateStore(db.toString()).cursor().isEmpty(),
                "a corrupt mark reads as absent so the poller keeps running on the lookback");
    }

    @Test
    void aBlankPathIsRefusedRatherThanDefaulted() {
        // The operator chose a durable store and gave it nowhere to live. Silently
        // picking a path would put the cursor somewhere nobody mounted.
        assertThrows(IllegalStateException.class, () -> new SqlitePollerStateStore("  "));
    }

    /**
     * The wiring, which no unit test can reach: that {@code store=sqlite} selects the
     * SQLite bean rather than the in-memory one, and that the nested
     * {@code LinkPathProperties} record is actually scanned and bound.
     */
    @SpringBootTest
    static class SqliteStoreIsWiredUnderTheSqliteProfile {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registry.add("connector.tsanet.mode", () -> "fake");
            registry.add("connector.intercom.mode", () -> "fake");
            registry.add("connector.links.store", () -> "sqlite");
            registry.add("connector.links.sqlite-path", () -> dir.resolve("wired.db").toString());
            registry.add("connector.inbound.reconcile-interval-ms", () -> "3600000");
            registry.add("connector.inbound.reconcile-initial-delay-ms", () -> "3600000");
        }

        @Autowired
        private PollerStateStore store;

        @Test
        void theDurableStoreIsSelected() {
            assertEquals(SqlitePollerStateStore.class, store.getClass(),
                    "store=sqlite must select the durable cursor, not the in-memory one —"
                            + " prod runs this branch");
        }
    }
}
