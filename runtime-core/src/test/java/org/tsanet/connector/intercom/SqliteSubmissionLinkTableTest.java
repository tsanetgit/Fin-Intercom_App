package org.tsanet.connector.intercom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The durable store against the shared contract, plus the properties that only a durable
 * store can have: surviving a reopen, and failing loudly rather than losing a link
 * (docs/decisions.md D11).
 */
class SqliteSubmissionLinkTableTest extends SubmissionLinkTableContractTest {

    @TempDir
    Path dir;

    private SqliteSubmissionLinkTable table;

    @BeforeEach
    void open() {
        table = new SqliteSubmissionLinkTable(path().toString());
    }

    private Path path() {
        return dir.resolve("nested").resolve("links.db");
    }

    @Override
    protected SubmissionLinkTable table() {
        return table;
    }

    /** A restart: same file, brand-new instance, nothing carried over in memory. */
    private SqliteSubmissionLinkTable reopen() {
        return new SqliteSubmissionLinkTable(path().toString());
    }

    @Test
    void everyRoutingDirectionSurvivesARestart() {
        table.record(link("TSA-p1", "flow-p1", "conv-shared"));
        table.record(link("TSA-p2", "flow-p2", "conv-shared"));
        table.annotationState("TSA-p1", AnnotationState.DEGRADED);

        SubmissionLinkTable restarted = reopen();

        assertEquals("TSA-p1", restarted.byToken("TSA-p1").orElseThrow().token());
        assertEquals("TSA-p1", restarted.byInternalCaseNumber("flow-p1").orElseThrow().token());
        List<SubmissionLink> conversation = restarted.byConversationId("conv-shared");
        assertEquals(List.of("TSA-p2", "TSA-p1"), conversation.stream().map(SubmissionLink::token).toList(),
                "newest-first ordering must survive the restart, not just the rows");
        assertEquals(2, restarted.size());
        // The one mutable field, written by a separate UPDATE: easiest thing to lose.
        assertEquals(AnnotationState.DEGRADED, restarted.annotationState("TSA-p1"),
                "annotation progress must survive; NONE here would re-annotate and duplicate the note");
        assertEquals(AnnotationState.NONE, restarted.annotationState("TSA-p2"));
    }

    @Test
    void aSparseLinkSurvivesARestartFieldForField() {
        SubmissionLink sparse = new SubmissionLink("TSA-p3", "flow-p3", "w2", null, null,
                "Sparse Partner", null, false, null, Instant.parse("2026-08-06T01:02:03.004Z"));
        table.record(sparse);
        assertEquals(sparse, reopen().byToken("TSA-p3").orElseThrow(),
                "nulls and the Instant must round-trip through TEXT storage unchanged");
    }

    // aMalformedLinkThrowsInsteadOfVanishing lived here and is now
    // aMalformedLinkIsRejectedWithNothingRecorded in the contract: it was asserting a
    // property of every store, in the one place where the sibling store could not fail
    // it — and the sibling store did fail it, leaving a link in one index and out of the
    // other. It still pins ON CONFLICT DO NOTHING over INSERT OR IGNORE here, since the
    // contract runs against this store too.

    @Test
    void anUnreadableAnnotationStateThrowsRatherThanReadingAsNone() throws SQLException {
        // Mapping an unknown value to NONE would silently re-run a completed annotation.
        table.record(link("TSA-p6", "flow-p6", "conv-p6"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path());
                Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE submission_link SET annotation_state = 'PARTIAL' WHERE token = 'TSA-p6'");
        }
        assertThrows(IllegalArgumentException.class, () -> table.annotationState("TSA-p6"));
    }

    @Test
    void aBlankPathIsRefusedAtConstruction() {
        // store=sqlite with no path configured is a deployment that believes it is durable
        // and is not; failing at startup is the only honest answer.
        assertThrows(IllegalStateException.class, () -> new SqliteSubmissionLinkTable(""));
        assertThrows(IllegalStateException.class, () -> new SqliteSubmissionLinkTable(null));
    }

    @Test
    void missingParentDirectoriesAreCreated() {
        assertTrue(path().toFile().exists(), "the store creates its own nested directory");
    }
}
