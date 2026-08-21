package org.tsanet.connector.tsanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link SdkCacheRetentionSweep#sweep(Instant)} directly against a temp sqlite
 * file carrying the upstream schema. The DDL below REPLICATES the relevant subset of
 * connect-library's DatabaseInitializer (com.tsanet.api.storage) — replicated, not
 * imported, because runtime-core carries no com.tsanet dependency by rule (D6). If the
 * upstream schema changes shape, this test's DDL and the sweep's SQL move together;
 * the upstream file is the reference.
 */
class SdkCacheRetentionSweepTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
    private static final String OLD = NOW.minus(Duration.ofDays(45)).toString();
    private static final String FRESH = NOW.minus(Duration.ofDays(2)).toString();

    @TempDir
    Path dir;

    private Path db;
    private SdkCacheRetentionSweep sweep;

    @BeforeEach
    void setUp() throws Exception {
        db = dir.resolve("connect-sdk.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE collaboration_request (id INTEGER PRIMARY KEY, status TEXT,"
                    + " summary TEXT, token TEXT NOT NULL UNIQUE, updated_at TEXT, fetched_at TEXT NOT NULL)");
            s.execute("CREATE TABLE case_note (id INTEGER PRIMARY KEY, case_token TEXT NOT NULL,"
                    + " summary TEXT, description TEXT, token TEXT NOT NULL UNIQUE, fetched_at TEXT NOT NULL)");
            s.execute("CREATE TABLE case_response (id INTEGER NOT NULL, case_token TEXT NOT NULL,"
                    + " next_steps TEXT, fetched_at TEXT NOT NULL, PRIMARY KEY (id, case_token))");
            s.execute("CREATE TABLE attachment_config (case_token TEXT PRIMARY KEY,"
                    + " submitter_company_id INTEGER, fetched_at TEXT NOT NULL)");
            s.execute("CREATE TABLE attachment_forward_result (row_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " case_token TEXT NOT NULL, description TEXT, file_name TEXT, forwarded_at TEXT NOT NULL)");
            s.execute("CREATE TABLE webhook_inbound_event (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " event_type TEXT NOT NULL, request_token TEXT NOT NULL, received_at TEXT NOT NULL,"
                    + " signature_valid INTEGER NOT NULL, cache_synced INTEGER NOT NULL, raw_payload TEXT NOT NULL)");
        }
        sweep = new SdkCacheRetentionSweep("30d", db.toString());
    }

    private void seedCase(String token, String status, String updatedAt, String fetchedAt) throws Exception {
        exec("INSERT INTO collaboration_request (status, summary, token, updated_at, fetched_at)"
                + " VALUES (?,?,?,?,?)", status, "case " + token, token, updatedAt, fetchedAt);
    }

    private void seedChildren(String token, String age) throws Exception {
        exec("INSERT INTO case_note (case_token, summary, description, token, fetched_at)"
                + " VALUES (?,?,?,?,?)", token, "note", "text", "note-" + token, age);
        exec("INSERT INTO case_response (id, case_token, next_steps, fetched_at) VALUES (1,?,?,?)",
                token, "steps", age);
        exec("INSERT INTO attachment_config (case_token, submitter_company_id, fetched_at) VALUES (?,?,?)",
                token, "1", age);
        exec("INSERT INTO attachment_forward_result (case_token, description, file_name, forwarded_at)"
                + " VALUES (?,?,?,?)", token, "file", "log.txt", age);
    }

    private void exec(String sql, String... args) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private List<String> column(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    void terminalOldCasesEvictWithChildren_freshAndOpenSurvive() throws Exception {
        seedCase("gone-closed", "CLOSED", OLD, OLD);
        seedChildren("gone-closed", OLD);
        seedCase("gone-rejected", "REJECTED", null, OLD); // updated_at NULL: fetched_at decides
        seedCase("stay-fresh", "CLOSED", FRESH, FRESH);
        seedChildren("stay-fresh", FRESH);
        seedCase("stay-open", "OPEN", OLD, OLD); // OPEN never ages out, however old
        seedChildren("stay-open", OLD);

        SdkCacheRetentionSweep.SweepResult r = sweep.sweep(NOW);

        assertEquals(2, r.cases(), "both terminal-old cases evict");
        assertEquals(1, r.notes());
        assertEquals(1, r.responses());
        assertEquals(1, r.attachConfigs());
        assertEquals(1, r.attachForwards());
        assertEquals(List.of("stay-fresh", "stay-open"),
                column("SELECT token FROM collaboration_request ORDER BY token"));
        assertEquals(List.of("stay-fresh", "stay-open"),
                column("SELECT case_token FROM case_note ORDER BY case_token"),
                "children of survivors are untouched");
    }

    @Test
    void coalesceOrderIsPinned_updatedAtWinsOverOldFetchedAt() throws Exception {
        // fetched_at is ancient but the case was UPDATED recently: updated_at must
        // decide (COALESCE order), so the case survives.
        seedCase("recently-updated", "CLOSED", FRESH, OLD);
        SdkCacheRetentionSweep.SweepResult r = sweep.sweep(NOW);
        assertEquals(0, r.cases(), "COALESCE(updated_at, fetched_at): fresh updated_at wins");
        assertEquals(List.of("recently-updated"), column("SELECT token FROM collaboration_request"));
    }

    @Test
    void orphanChildrenAgeOut_freshOrphansSurvive() throws Exception {
        exec("INSERT INTO case_note (case_token, summary, description, token, fetched_at)"
                + " VALUES (?,?,?,?,?)", "no-parent", "note", "text", "orphan-old", OLD);
        exec("INSERT INTO case_note (case_token, summary, description, token, fetched_at)"
                + " VALUES (?,?,?,?,?)", "no-parent-2", "note", "text", "orphan-fresh", FRESH);
        SdkCacheRetentionSweep.SweepResult r = sweep.sweep(NOW);
        assertEquals(1, r.orphanNotes(), "old orphan evicts");
        assertEquals(List.of("orphan-fresh"), column("SELECT token FROM case_note"),
                "the fresh orphan survives (mid-fetch protection)");
    }

    @Test
    void webhookEventsAgeOutByReceivedAt() throws Exception {
        exec("INSERT INTO webhook_inbound_event (event_type, request_token, received_at,"
                + " signature_valid, cache_synced, raw_payload) VALUES (?,?,?,1,1,?)",
                "created", "t1", OLD, "{}");
        exec("INSERT INTO webhook_inbound_event (event_type, request_token, received_at,"
                + " signature_valid, cache_synced, raw_payload) VALUES (?,?,?,1,1,?)",
                "created", "t2", FRESH, "{}");
        SdkCacheRetentionSweep.SweepResult r = sweep.sweep(NOW);
        assertEquals(1, r.events());
        assertEquals(List.of("t2"), column("SELECT request_token FROM webhook_inbound_event"));
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
        // The guard must run BEFORE getConnection: sqlite-jdbc creates files on connect,
        // and a fake-mode deployment must not grow db litter.
        assertFalse(Files.exists(absent));
        assertTrue(Files.exists(db), "the real db is untouched by the other instance");
    }

    @Test
    void partialFailureKeepsCommittedPhasesAndReturnsNull() throws Exception {
        // Phase 3 (webhook events) fails by construction: the table is missing. Phases
        // 1 and 2 committed before the failure, so their deletes MUST survive — the
        // reality the failure audit line reports committed_* counts for (QA on #69).
        seedCase("gone-closed", "CLOSED", OLD, OLD);
        seedChildren("gone-closed", OLD);
        exec("INSERT INTO case_note (case_token, summary, description, token, fetched_at)"
                + " VALUES (?,?,?,?,?)", "no-parent", "note", "text", "orphan-old", OLD);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("DROP TABLE webhook_inbound_event");
        }

        assertNull(sweep.sweep(NOW), "a failed run reports failure");

        assertEquals(List.of(), column("SELECT token FROM collaboration_request"),
                "phase 1 committed before the phase-3 failure: the terminal case is gone");
        assertEquals(List.of(), column("SELECT token FROM case_note"),
                "phase 2 committed too: children and the aged orphan are gone");
    }
}
