package org.tsanet.connector.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tsanet.api.storage.DatabaseInitializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/**
 * Drives {@link SdkCacheRetentionSweep#sweep(Instant)} against the REAL upstream
 * schema ({@link DatabaseInitializer}) — connect-gateway carries connect-library
 * directly now (pom.xml), so there is no hand-replicated schema to drift and no
 * compat test needed to keep one honest (that was the deleted
 * SdkCacheSweepSchemaCompatTest's whole job). What's exercised here is this
 * wrapper's OWN responsibility: config parsing, the missing-file guard, delegating
 * to {@code CacheRetention.sweep()} correctly, and surviving a
 * {@code RetentionSweepException} without throwing. The sweep's own SQL and
 * transaction semantics are Connect_SDK's own tested surface, not re-proven here.
 */
class SdkCacheRetentionSweepTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
    private static final String OLD = NOW.minus(Duration.ofDays(45)).toString();
    private static final String FRESH = NOW.minus(Duration.ofDays(2)).toString();

    @TempDir
    Path dir;

    private Path db;
    private JdbcTemplate jdbc;
    private SdkCacheRetentionSweep sweep;

    @BeforeEach
    void setUp() {
        db = dir.resolve("connect-sdk.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + db);
        jdbc = new JdbcTemplate(dataSource);
        DatabaseInitializer.createSchema(jdbc);
        sweep = new SdkCacheRetentionSweep("30d", db.toString());
    }

    private void seedCase(String token, String status, String updatedAt, String fetchedAt) {
        jdbc.update("INSERT INTO collaboration_request (status, summary, token, updated_at, fetched_at)"
                + " VALUES (?,?,?,?,?)", status, "case " + token, token, updatedAt, fetchedAt);
    }

    private List<String> tokens(String sql) {
        return jdbc.queryForList(sql, String.class);
    }

    @Test
    void delegatesToUpstreamSweep_terminalOldEvicts_freshAndOpenSurvive() {
        seedCase("gone-closed", "CLOSED", OLD, OLD);
        seedCase("stay-fresh", "CLOSED", FRESH, FRESH);
        seedCase("stay-open", "OPEN", OLD, OLD); // OPEN never ages out, however old
        jdbc.update("INSERT INTO attachment_forward_result (case_token, description, file_name, forwarded_at)"
                + " VALUES (?,?,?,?)", "gone-closed", "file", "log.txt", OLD);

        var r = sweep.sweep(NOW);

        assertEquals(1, r.cases(), "only the terminal-old case evicts");
        assertEquals(List.of("stay-fresh", "stay-open"),
                tokens("SELECT token FROM collaboration_request ORDER BY token"));
        assertEquals(List.of("gone-closed"), tokens("SELECT case_token FROM attachment_forward_result"),
                "narrowed scope (round-3 QA on Connect_SDK#66): never evicted, not even for a doomed case");
    }

    @Test
    void blankOrBrokenRetentionMeansNoop() {
        assertNull(new SdkCacheRetentionSweep("", db.toString()).sweep(NOW));
        assertNull(new SdkCacheRetentionSweep("not-a-duration", db.toString()).sweep(NOW));
        assertNull(new SdkCacheRetentionSweep("0s", db.toString()).sweep(NOW));
    }

    @Test
    void missingFileIsSkippedAndNeverCreated() {
        Path absent = dir.resolve("nope").resolve("connect-sdk.db");
        assertNull(new SdkCacheRetentionSweep("30d", absent.toString()).sweep(NOW));
        assertFalse(Files.exists(absent));
        assertTrue(Files.exists(db), "the real db is untouched by the other instance");
    }

    @Test
    void midSweepFailureIsCaughtNotThrown_partialPhaseCommitSurvives() {
        // webhook-events is the second and last upstream phase; dropping its table
        // fails the sweep after phase 1 (terminal-cases) already committed.
        seedCase("gone-closed", "CLOSED", OLD, OLD);
        jdbc.execute("DROP TABLE webhook_inbound_event");

        assertNull(sweep.sweep(NOW), "a failed run is caught here, not thrown to the scheduler thread");

        assertEquals(List.of(), tokens("SELECT token FROM collaboration_request"),
                "phase 1 committed before the failure -- CacheRetention's own rollback contract, not this wrapper's");
    }
}
