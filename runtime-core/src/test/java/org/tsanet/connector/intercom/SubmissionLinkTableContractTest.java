package org.tsanet.connector.intercom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The link-table contract both stores must pass: the in-memory table and the durable
 * SQLite one (docs/decisions.md D11). Routing correctness is the whole point of this
 * table, so the semantics live here rather than in either implementation's own tests —
 * a divergence between the two is a case that cannot be routed home, and this is what
 * makes it a test failure instead of a production symptom.
 */
public abstract class SubmissionLinkTableContractTest {

    protected abstract SubmissionLinkTable table();

    protected static SubmissionLink link(String token, String internal, String conversation) {
        return new SubmissionLink(token, internal, "w1", conversation, "t-9", "Partner Co",
                "2026-08-06T12:00:00Z", true, "agent@tsanet.org", Instant.parse("2026-08-05T20:00:00Z"));
    }

    @Test
    void indexesAnswerAllThreeRoutingDirections() {
        table().record(link("TSA-1", "flow-1", "conv-1"));
        assertEquals("TSA-1", table().byToken("TSA-1").orElseThrow().token());
        assertEquals("TSA-1", table().byInternalCaseNumber("flow-1").orElseThrow().token());
        assertEquals("TSA-1", table().byConversationId("conv-1").get(0).token(),
                "Phase-2 inbound-from-Intercom routes by conversation id");
    }

    @Test
    void firstRecordWinsOnReplay() {
        table().record(link("TSA-2", "flow-2", "conv-2"));
        table().annotationState("TSA-2", AnnotationState.FULL);
        table().record(new SubmissionLink("TSA-2", "flow-2", "OTHER", "conv-2", null, "Other",
                null, false, null, Instant.parse("2026-08-05T21:00:00Z")));
        assertEquals("w1", table().byToken("TSA-2").orElseThrow().workspaceId(),
                "a replayed record must not overwrite the original link");
        assertEquals(AnnotationState.FULL, table().annotationState("TSA-2"),
                "a replayed record must not reset annotation progress");
        assertEquals(1, table().size(), "a replay is not a second link");
    }

    @Test
    void oneConversationCanAnchorSeveralCasesNewestFirst() {
        // QA round-2: putIfAbsent modeled this one-to-one, so the first case owned
        // the conversation forever and later cases were unroutable from Intercom.
        table().record(link("TSA-a", "flow-a", "conv-shared"));
        table().record(link("TSA-b", "flow-b", "conv-shared"));
        List<SubmissionLink> found = table().byConversationId("conv-shared");
        assertEquals(2, found.size());
        assertEquals("TSA-b", found.get(0).token(), "newest first");
        assertEquals("TSA-a", found.get(1).token());
    }

    @Test
    void aReplayedTokenDoesNotAcquireNewSecondaryKeys() {
        // The replay case above re-records identical keys, which cannot tell an index
        // that was skipped from one that was rewritten to the same value. Same token,
        // DIFFERENT internal case number and conversation: the whole row is dropped, so
        // the new keys must route nowhere. A store that indexed secondaries
        // unconditionally would answer a lookup with a link that disagrees with it.
        table().record(link("TSA-r", "flow-orig", "conv-orig"));
        table().record(link("TSA-r", "flow-new", "conv-new"));
        assertEquals(1, table().size());
        assertTrue(table().byInternalCaseNumber("flow-new").isEmpty(),
                "a dropped replay must not register its internal case number");
        assertTrue(table().byConversationId("conv-new").isEmpty(),
                "a dropped replay must not register its conversation");
        assertEquals("TSA-r", table().byInternalCaseNumber("flow-orig").orElseThrow().token());
        assertEquals("TSA-r", table().byConversationId("conv-orig").get(0).token());
    }

    @Test
    void duplicateInternalCaseNumberAnswersWithTheFirstRecorded() {
        // Two tokens CAN share an internal case number: that is D2's residual duplicate
        // window, a submit that timed out client-side after the case was created and was
        // then re-submitted. Wrong-but-well-formed input for this index — a store that
        // answers with whichever row it happens to reach first would reconcile a flow
        // against a different case than the one it reconciled against a moment earlier.
        table().record(link("TSA-first", "flow-dup", "conv-dup"));
        table().record(link("TSA-second", "flow-dup", "conv-dup"));
        assertEquals("TSA-first", table().byInternalCaseNumber("flow-dup").orElseThrow().token(),
                "the first recorded token owns the internal case number");
    }

    @Test
    void annotationStateDefaultsToNoneAndSticks() {
        assertEquals(AnnotationState.NONE, table().annotationState("absent"));
        table().record(link("TSA-3", "flow-3", "conv-3"));
        assertEquals(AnnotationState.NONE, table().annotationState("TSA-3"));
        table().annotationState("TSA-3", AnnotationState.DEGRADED);
        assertEquals(AnnotationState.DEGRADED, table().annotationState("TSA-3"));
        assertTrue(table().size() == 1);
    }

    @Test
    void writingAnnotationStateForAnUnknownTokenIsANoOp() {
        table().annotationState("never-recorded", AnnotationState.FULL);
        assertEquals(AnnotationState.NONE, table().annotationState("never-recorded"));
        assertEquals(0, table().size(), "a state write must not conjure a link");
    }

    @Test
    void absentKeysAnswerEmpty() {
        table().record(link("TSA-4", "flow-4", "conv-4"));
        assertTrue(table().byToken("nope").isEmpty());
        assertTrue(table().byInternalCaseNumber("nope").isEmpty());
        assertTrue(table().byConversationId("nope").isEmpty());
        // Null keys reach these lookups for real: a webhook payload missing its token, a
        // ticketless link with no conversation. Every lookup answers empty rather than
        // throwing, because a NPE here kills the event drain.
        assertTrue(table().byToken(null).isEmpty());
        assertTrue(table().byInternalCaseNumber(null).isEmpty());
        assertTrue(table().byConversationId(null).isEmpty());
    }

    @Test
    void everyFieldSurvivesRecordAndReadIncludingNulls() {
        // A ticketless, never-answered link: the optional fields are exactly the ones a
        // store is most likely to drop or coerce, and respondBy/submittedAt feed the
        // agent-visible confirmation.
        SubmissionLink sparse = new SubmissionLink("TSA-5", "flow-5", "w2", null, null,
                "Sparse Partner", null, false, null, Instant.parse("2026-08-06T01:02:03.004Z"));
        table().record(sparse);
        SubmissionLink read = table().byToken("TSA-5").orElseThrow();
        assertEquals(sparse, read, "a link must read back exactly as recorded");
        assertNull(read.ticketId());
        assertEquals(Instant.parse("2026-08-06T01:02:03.004Z"), read.submittedAt(),
                "submittedAt must survive with millisecond precision");

        SubmissionLink full = link("TSA-6", "flow-6", "conv-6");
        table().record(full);
        assertEquals(full, table().byToken("TSA-6").orElseThrow());
        assertTrue(table().byToken("TSA-6").orElseThrow().ticketAnchored());
    }
}
