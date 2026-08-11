package org.tsanet.connector.intercom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The link-table contract both stores must pass: the in-memory table and the durable
 * SQLite one (docs/decisions.md D12). Routing correctness is the whole point of this
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
    void recordReportsWhoWonTheClaim() {
        // Both stores are first-write-wins, but a caller that cannot SEE which write
        // won has to check-then-create, and two concurrent deliveries of the same
        // inbound event then both find nothing and both create a platform object.
        // The token's uniqueness is the arbiter; this return value is how a handler
        // reads the verdict.
        assertTrue(table().record(link("TSA-claim", "flow-claim", "conv-claim")),
                "the first write inserts and must say so");
        assertFalse(table().record(link("TSA-claim", "flow-other", "conv-other")),
                "a second write on the same token must report that it did NOT insert");
        assertEquals("flow-claim", table().byToken("TSA-claim").orElseThrow().internalCaseNumber(),
                "and the first write must still be the one stored");
    }

    @Test
    void aClaimCanBeReleasedButAnAnchoredLinkNeverIs() {
        // releaseClaim exists for a handler whose platform write failed after winning
        // the claim: without it the token stays marked handled forever and the
        // partner's case is lost in silence. It must NOT be able to delete a link that
        // already has an anchor, because that is a live route home.
        assertTrue(table().record(new SubmissionLink("TSA-claimed", "inbound:TSA-claimed",
                null, null, null, "NetApp", null, true, null, java.time.Instant.now())));
        assertTrue(table().releaseClaim("TSA-claimed"), "an unanchored claim releases");
        assertTrue(table().byToken("TSA-claimed").isEmpty());
        assertFalse(table().releaseClaim("TSA-claimed"), "releasing twice is a no-op, not a crash");
        assertFalse(table().releaseClaim(null));

        table().record(link("TSA-anchored", "flow-anchored", "conv-anchored"));
        assertFalse(table().releaseClaim("TSA-anchored"),
                "a link with an anchor is a real route home and must survive");
        assertTrue(table().byToken("TSA-anchored").isPresent());
    }

    @Test
    void anchorAttachesTheTicketAfterTheClaim() {
        // record() is first-write-wins, so an anchor discovered after the claim cannot
        // arrive by re-recording; this is the only path that carries it.
        assertTrue(table().record(new SubmissionLink("TSA-anchor", "inbound:TSA-anchor",
                null, null, null, "NetApp", null, true, null, java.time.Instant.now())));
        table().anchor("TSA-anchor", "tkt-77");
        assertEquals("tkt-77", table().byToken("TSA-anchor").orElseThrow().ticketId());
        // And an anchored link can no longer be released.
        assertFalse(table().releaseClaim("TSA-anchor"));
        table().anchor("TSA-nonexistent", "tkt-99"); // no such token: a no-op, never a crash
        table().anchor(null, null);
    }

    @Test
    void aBlankAnchorIsNotAnAnchorAndAnchoringIsWriteOnce() {
        // Both rules exist so releaseClaim's guard means the same thing in both
        // stores. A stored blank id made them disagree: the map store would release
        // such a link and the SQL store would refuse, so in prod the token stayed
        // claimed forever and no ticket was ever created — the exact silent loss
        // releaseClaim exists to prevent.
        assertTrue(table().record(new SubmissionLink("TSA-blank", "inbound:TSA-blank",
                null, null, null, "NetApp", null, true, null, java.time.Instant.now())));
        table().anchor("TSA-blank", "   ");
        assertNull(table().byToken("TSA-blank").orElseThrow().ticketId(),
                "a blank id must not be stored as an anchor");
        assertTrue(table().releaseClaim("TSA-blank"),
                "and the link must still be releasable, in EVERY store");

        assertTrue(table().record(new SubmissionLink("TSA-once", "inbound:TSA-once",
                null, null, null, "NetApp", null, true, null, java.time.Instant.now())));
        table().anchor("TSA-once", "tkt-first");
        table().anchor("TSA-once", "tkt-second");
        assertEquals("tkt-first", table().byToken("TSA-once").orElseThrow().ticketId(),
                "re-pointing an anchored link would silently move a live case");
    }

    @Test
    void byTicketIdRefusesAnAmbiguousTicketRatherThanPicking() {
        // One support ticket can legitimately anchor SEVERAL cases: an agent may
        // escalate one customer problem to two partners. Answering with either one
        // would let a response flow reply to the wrong partner, so ambiguity is empty.
        table().record(link("TSA-one", "flow-one", "conv-1"));
        assertEquals("TSA-one", table().byTicketId("t-9").orElseThrow().token());

        table().record(link("TSA-two", "flow-two", "conv-2"));  // same ticket id, t-9
        assertTrue(table().byTicketId("t-9").isEmpty(),
                "two cases on one ticket is ambiguous; picking one answers the wrong partner");
        assertTrue(table().byTicketId(null).isEmpty());
        assertTrue(table().byTicketId("no-such-ticket").isEmpty());
    }

    @Test
    void caseStateMovesOnlyFromTheExpectedState() {
        // Two panels open on one ticket must not both respond to the partner. The
        // loser of the compare-and-set re-renders what actually holds.
        table().record(link("TSA-state", "flow-state", "conv-state"));
        assertEquals(CaseState.PENDING, table().caseState("TSA-state"));
        assertTrue(table().caseState("TSA-state", CaseState.PENDING, CaseState.ACCEPTED));
        assertEquals(CaseState.ACCEPTED, table().caseState("TSA-state"));

        assertFalse(table().caseState("TSA-state", CaseState.PENDING, CaseState.REJECTED),
                "a stale panel must not be able to reject an accepted case");
        assertEquals(CaseState.ACCEPTED, table().caseState("TSA-state"));

        assertEquals(CaseState.PENDING, table().caseState("no-such-token"),
                "an unknown token is PENDING, never a crash");
        assertFalse(table().caseState("no-such-token", CaseState.PENDING, CaseState.ACCEPTED));
        assertFalse(table().caseState(null, CaseState.PENDING, CaseState.ACCEPTED));
    }

    @Test
    void theCaseNumberWeQuotedIsRecorded() {
        table().record(link("TSA-num", "flow-num", "conv-num"));
        assertNull(table().byToken("TSA-num").orElseThrow().caseNumber());
        table().caseNumber("TSA-num", "121556608");
        assertEquals("121556608", table().byToken("TSA-num").orElseThrow().caseNumber());
        table().caseNumber("TSA-num", "  ");  // blank never overwrites what we sent
        assertEquals("121556608", table().byToken("TSA-num").orElseThrow().caseNumber());
    }

    @Test
    void aSurfacedStatusIsRecordedPerTransitionAndReadsBack() {
        table().record(link("TSA-surf", "flow-surf", "conv-surf"));
        assertNull(table().surfacedStatus("TSA-surf"),
                "null IS the nothing-surfaced-yet value; a default here would dedup the"
                        + " first real verdict into silence");
        table().surfacedStatus("TSA-surf", "ACCEPTED");
        assertEquals("ACCEPTED", table().surfacedStatus("TSA-surf"));
        table().surfacedStatus("TSA-surf", "INFORMATION");  // a transition overwrites
        assertEquals("INFORMATION", table().surfacedStatus("TSA-surf"));
        table().surfacedStatus("TSA-surf", "  ");  // blank never erases a real record
        assertEquals("INFORMATION", table().surfacedStatus("TSA-surf"));
        table().surfacedStatus("no-such-token", "ACCEPTED");  // missing row: a no-op
        assertNull(table().surfacedStatus("no-such-token"));
    }

    @Test
    void absentKeysAnswerEmpty() {
        table().record(link("TSA-4", "flow-4", "conv-4"));
        assertTrue(table().byToken("nope").isEmpty());
        assertTrue(table().byInternalCaseNumber("nope").isEmpty());
        assertTrue(table().byConversationId("nope").isEmpty());
    }

    @Test
    void everyKeyedEntryPointAnswersANullKeyTheWayItAnswersAnAbsentOne() {
        // Null keys reach this table for real: a webhook payload missing its token, a
        // ticketless link with no conversation. An NPE here kills the event drain, so
        // every entry point that takes a key answers rather than throws — and this test
        // covers the WHOLE keyed surface deliberately. Pinning some of it is what let
        // the two stores diverge on annotationState while byConversationId agreed: the
        // guard was applied at three sites of one defect class and the other two were
        // invisible precisely because nothing here asked.
        table().record(link("TSA-n", "flow-n", "conv-n"));
        table().annotationState("TSA-n", AnnotationState.FULL);

        assertTrue(table().byToken(null).isEmpty());
        assertTrue(table().byInternalCaseNumber(null).isEmpty());
        assertTrue(table().byConversationId(null).isEmpty());
        assertEquals(AnnotationState.NONE, table().annotationState(null),
                "a null token has no state, the same answer an unknown token gets");
        assertNull(table().surfacedStatus(null),
                "a null token has no surfaced status, the same answer an unknown token gets");

        // The write arity asserted by its EFFECT, not by "it did not throw": a store
        // that swallowed the null and wrote somewhere would pass the weaker check.
        table().annotationState(null, AnnotationState.DEGRADED);
        assertEquals(AnnotationState.FULL, table().annotationState("TSA-n"),
                "a null-token write must not reach a real link's state");
        assertEquals(AnnotationState.NONE, table().annotationState(null));
        table().surfacedStatus(null, "ACCEPTED");
        assertNull(table().surfacedStatus("TSA-n"),
                "a null-token surfaced-status write must not reach a real link");
        assertEquals(1, table().size(), "a null-token write must not conjure a link");
    }

    @Test
    void aNullAnnotationStateIsRefusedRatherThanStored() {
        // The one asymmetry in the null story, and the reason it is not "nulls are
        // always benign": a null STATE is not a missing key, it is a value outside the
        // enum. A store that accepts it answers null from a method whose contract is
        // "never null" — silent corruption, and worse than the throw, since the drain
        // reads this to decide whether an annotation already ran.
        table().record(link("TSA-ns", "flow-ns", "conv-ns"));
        table().annotationState("TSA-ns", AnnotationState.FULL);
        assertThrows(NullPointerException.class, () -> table().annotationState("TSA-ns", null));
        assertEquals(AnnotationState.FULL, table().annotationState("TSA-ns"),
                "a refused write must not have moved the state on the way out");
    }

    @Test
    void aMalformedLinkIsRejectedWithNothingRecorded() {
        // record() is the exception to the null story above: the two keys a link is
        // routed by are refused rather than answered, because a link that cannot be
        // found later is a partner case with no route home. Wrong-but-well-formed at
        // the Java level — a real SubmissionLink — rejected at the store.
        //
        // Rejected ATOMICALLY is the load-bearing half: a store that validates its keys
        // one at a time as it writes them leaves the link in the first index and out of
        // the second, which is a token that resolves to a case reconcile cannot find.
        // size() and byToken are what catch that today (measured: a validate-as-it-writes
        // mutant fails the size assertion below). The two SECONDARY-index assertions are
        // prospective and cannot fail against either current store — every secondary
        // lookup in the map store resolves through byToken, so a dangling entry is masked,
        // and the SQL store keeps one row. They are kept for a store that maintains its
        // indexes independently, which is what Postgres at GA would be. See D12; do not
        // read them as covering the case above.
        //
        // IllegalStateException specifically, not any RuntimeException: an unguarded
        // map store raises NPE from its own first put, which would satisfy the looser
        // check while measuring nothing. The type is how a deliberate rejection is told
        // apart from an accidental one. (For the SQL store this is also what pins
        // ON CONFLICT DO NOTHING over INSERT OR IGNORE, which would swallow the NOT
        // NULL violation and return normally — see SqliteSubmissionLinkTable.)
        SubmissionLink noToken = new SubmissionLink(null, "flow-m1", "w1", "conv-m1", "t-1",
                "Partner Co", null, true, null, Instant.parse("2026-08-06T02:00:00Z"));
        assertThrows(IllegalStateException.class, () -> table().record(noToken));
        assertEquals(0, table().size(), "nothing was written");
        assertTrue(table().byInternalCaseNumber("flow-m1").isEmpty(),
                "a rejected link must not survive in the internal-case index");
        assertTrue(table().byConversationId("conv-m1").isEmpty(),
                "a rejected link must not survive in the conversation index");

        SubmissionLink noInternal = new SubmissionLink("TSA-m2", null, "w1", "conv-m2", "t-1",
                "Partner Co", null, true, null, Instant.parse("2026-08-06T02:00:00Z"));
        assertThrows(IllegalStateException.class, () -> table().record(noInternal));
        assertEquals(0, table().size(), "nothing was written");
        assertTrue(table().byToken("TSA-m2").isEmpty(),
                "a rejected link must not survive in the token index");
        assertTrue(table().byConversationId("conv-m2").isEmpty(),
                "a rejected link must not survive in the conversation index");
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
