package org.tsanet.connector.intercom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opening a database written by the PREVIOUS shipped schema.
 *
 * <p>This is the normal restart, not an edge case: D11/D12 put the file on storage that
 * outlives the container, so every deploy meets a database created by the version
 * before it. {@code CREATE TABLE IF NOT EXISTS} does nothing to an existing table, so
 * without column evolution a new column makes every operation fail "no such column"
 * while the connector still boots healthy and logs a correct link count — worse than
 * the volatile store the durable one replaced.
 *
 * <p>The old schema below is a FROZEN LITERAL copied from merged main, deliberately not
 * derived from the current constants. Deriving it would make this test pass by
 * construction the moment the constants change, which is the same-code-restart trap
 * that let the gap through (the existing restart test reopens a file with the same code
 * version, so it pins restart and not upgrade).
 */
class SqliteSchemaUpgradeTest {

    /** Verbatim from main at 0e5505b — do not regenerate from the current DDL. */
    private static final String SCHEMA_BEFORE_CASE_STATE = """
            CREATE TABLE IF NOT EXISTS submission_link (
              seq                  INTEGER PRIMARY KEY AUTOINCREMENT,
              token                TEXT NOT NULL UNIQUE,
              internal_case_number TEXT NOT NULL,
              workspace_id         TEXT,
              conversation_id      TEXT,
              ticket_id            TEXT,
              partner_display      TEXT,
              respond_by           TEXT,
              test_case            INTEGER NOT NULL,
              submitted_by         TEXT,
              submitted_at         TEXT,
              annotation_state     TEXT NOT NULL
            )""";

    private static Path writeOldDatabase(Path dir) throws Exception {
        Path file = dir.resolve("links.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement s = c.createStatement()) {
            s.executeUpdate(SCHEMA_BEFORE_CASE_STATE);
            s.executeUpdate("INSERT INTO submission_link (token, internal_case_number, workspace_id,"
                    + " conversation_id, ticket_id, partner_display, respond_by, test_case,"
                    + " submitted_by, submitted_at, annotation_state)"
                    + " VALUES ('TSA-old', 'flow-old', 'w1', 'conv-old', 'api-old', 'NetApp',"
                    + " '2026-08-08T12:00:00Z', 1, 'agent@member.example',"
                    + " '2026-08-06T10:00:00Z', 'NONE')");
        }
        return file;
    }

    @Test
    void aDatabaseFromThePreviousReleaseKeepsWorking(@TempDir Path dir) throws Exception {
        Path file = writeOldDatabase(dir);

        SqliteSubmissionLinkTable table = new SqliteSubmissionLinkTable(file.toString());

        // Reads that touch the NEW columns are the ones that used to throw.
        SubmissionLink existing = table.byToken("TSA-old").orElseThrow();
        assertEquals("NetApp", existing.partnerDisplay());
        assertEquals("flow-old", existing.internalCaseNumber());
        assertEquals("api-old", table.byTicketId("api-old").orElseThrow().ticketId());
        assertEquals(CaseState.PENDING, table.caseState("TSA-old"),
                "a row written before the column existed defaults to PENDING");

        // And so are the writes: record() failed outright, which meant a new inbound
        // case could not even be claimed.
        assertTrue(table.record(new SubmissionLink("TSA-new", "inbound:TSA-new", "w1", null,
                null, "Partner Co", null, true, null, Instant.now())));
        assertTrue(table.caseState("TSA-new", CaseState.PENDING, CaseState.ACCEPTED));
        table.caseNumber("TSA-new", "9900123");
        assertEquals("9900123", table.byToken("TSA-new").orElseThrow().caseNumber());
        assertEquals(2, table.size());
    }

    @Test
    void evolvingTwiceIsANoOp(@TempDir Path dir) throws Exception {
        Path file = writeOldDatabase(dir);
        new SqliteSubmissionLinkTable(file.toString());

        // A second boot must not try to add the columns again, and must see the data.
        SqliteSubmissionLinkTable second = new SqliteSubmissionLinkTable(file.toString());
        assertEquals(1, second.size());
        assertEquals(CaseState.PENDING, second.caseState("TSA-old"));
    }

    @Test
    void aFreshFileNeedsNoEvolution(@TempDir Path dir) throws Exception {
        SqliteSubmissionLinkTable table =
                new SqliteSubmissionLinkTable(dir.resolve("fresh.db").toString());
        assertTrue(table.record(new SubmissionLink("TSA-fresh", "inbound:TSA-fresh", "w1", null,
                null, "Partner Co", null, true, null, Instant.now())));
        assertEquals(CaseState.PENDING, table.caseState("TSA-fresh"));
    }
}
