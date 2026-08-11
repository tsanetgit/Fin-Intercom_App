package org.tsanet.connector.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tsanet.connector.events.InboundEvent;
import org.tsanet.connector.intercom.CaseState;
import org.tsanet.connector.intercom.FakeIntercomGateway;
import org.tsanet.connector.intercom.InMemorySubmissionLinkTable;
import org.tsanet.connector.intercom.SubmissionLink;
import org.tsanet.connector.intercom.SubmissionLinkTable;
import org.tsanet.connector.tsanet.FakeTsanetGateway;
import org.tsanet.connector.tsanet.dto.InboundCase;
import org.tsanet.connector.webhooks.TsanetCloudEvent;

/**
 * Partner response surfacing: a verdict lands on the ticket exactly once per
 * TRANSITION, however many deliveries carry it.
 *
 * <p>The shape under test is the live Veritas accept (2026-08-11): six events in one
 * second for one verdict. Most of these probes assert what must NOT happen — a
 * duplicate note, a surfaced echo of our own action, a guessed state id, a dedup
 * record written before the platform writes landed.
 */
class PartnerResponseHandlerTest {

    private static final String TOKEN = "veritas-e2e";
    private static final String TICKET = "215475371079018";
    private static final String IN_PROGRESS = "9619797";
    private static final String WAITING = "9619798";
    private static final String RESOLVED = "9619799";
    private static final String PARTNER = "Veritas Technologies | Veritas Global Support";

    private final FakeTsanetGateway tsanet = new FakeTsanetGateway(new MockEnvironment());
    private final FakeIntercomGateway intercom = new FakeIntercomGateway(new MockEnvironment());
    private final SubmissionLinkTable links = new InMemorySubmissionLinkTable();

    private PartnerResponseHandler handler() {
        return new PartnerResponseHandler(tsanet, intercom, links,
                InboundProperties.ofStateIds("4561712", IN_PROGRESS, WAITING, RESOLVED));
    }

    private static InboundEvent response(String token, TsanetCloudEvent.Type type) {
        return new InboundEvent(InboundEvent.Source.TSANET, type.wireValue(), token,
                Instant.now(), Map.of());
    }

    private static InboundEvent responseCreated(String token) {
        return response(token, TsanetCloudEvent.Type.RESPONSE_CREATED);
    }

    private static InboundEvent responseUpdated(String token) {
        return response(token, TsanetCloudEvent.Type.RESPONSE_UPDATED);
    }

    private void outboundLink(String token, String ticketId) {
        links.record(new SubmissionLink(token, "out:" + token, "nxdyoagx", ticketId,
                ticketId, PARTNER, null, true, "agent@example.com", Instant.now()));
        links.anchor(token, ticketId);
    }

    private void fetchAnswers(String token, InboundCase.Status status) {
        fetchAnswers(token, InboundCase.Direction.OUTBOUND, status);
    }

    private void fetchAnswers(String token, InboundCase.Direction direction,
            InboundCase.Status status) {
        tsanet.seedFetch(new InboundCase(token, direction, status, "Veritas Technologies",
                "DB down", "desc", "2026-08-12T12:00:00Z", "HIGH", true));
    }

    @Test
    void anAcceptMovesTheTicketToInProgressAndNotesTheVerdict() {
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Status.ACCEPTED);

        handler().handle(responseCreated(TOKEN));

        assertEquals(IN_PROGRESS, intercom.stateOf(TICKET),
                "D17: a partner accept moves the ticket to In progress");
        assertEquals(1, intercom.noteCalls());
        assertTrue(intercom.notes().get(0).contains("accepted"),
                "the note names the verdict: " + intercom.notes());
        assertEquals("ACCEPTED", links.surfacedStatus(TOKEN));
        assertEquals(CaseState.PENDING, links.caseState(TOKEN),
                "caseState belongs to the inbound panel and closure — surfacing a partner"
                        + " response must never write it");
    }

    @Test
    void aBurstSurfacesTheVerdictExactlyOnce() {
        // The live shape: one verdict, five status-bearing deliveries in one second.
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Status.ACCEPTED);
        PartnerResponseHandler h = handler();

        h.handle(responseCreated(TOKEN));
        h.handle(responseUpdated(TOKEN));
        h.handle(responseUpdated(TOKEN));
        h.handle(responseUpdated(TOKEN));
        h.handle(responseUpdated(TOKEN));

        assertEquals(1, intercom.stateMoveCalls(),
                "== 1, not 'was moved': five identical moves would also leave the right state");
        assertEquals(1, intercom.noteCalls(),
                "== 1, not 'a note exists': the burst is exactly what duplicates notes");
    }

    @Test
    void anInformationRequestMovesToWaitingAndSurfacesAgainAfterAccept() {
        // Two REAL transitions surface twice — dedup is per transition, not once-ever.
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Status.INFORMATION);
        PartnerResponseHandler h = handler();

        h.handle(responseCreated(TOKEN));
        assertEquals(WAITING, intercom.stateOf(TICKET));
        assertTrue(intercom.notes().get(0).contains("requested more information"));

        fetchAnswers(TOKEN, InboundCase.Status.ACCEPTED);   // the partner then accepts
        h.handle(responseUpdated(TOKEN));

        assertEquals(IN_PROGRESS, intercom.stateOf(TICKET));
        assertEquals(2, intercom.noteCalls(), "a second real transition is a second note");
        assertEquals("ACCEPTED", links.surfacedStatus(TOKEN));
    }

    @Test
    void aDeclineResolvesTheTicket() {
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Status.REJECTED);

        handler().handle(responseCreated(TOKEN));

        assertEquals(RESOLVED, intercom.stateOf(TICKET), "D17: REJECTED is terminal for us");
        assertTrue(intercom.notes().get(0).contains("declined"));
        assertEquals("REJECTED", links.surfacedStatus(TOKEN));
    }

    @Test
    void anInboundEchoSurfacesNothing() {
        // On an inbound case every response event is an echo of OUR panel action;
        // "Partner accepted" would attribute our own click to the partner.
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Direction.INBOUND, InboundCase.Status.ACCEPTED);

        handler().handle(responseCreated(TOKEN));

        assertEquals(0, intercom.stateMoveCalls(), "NOT CALLED, not merely no state");
        assertEquals(0, intercom.noteCalls());
        assertNull(links.surfacedStatus(TOKEN));
    }

    @Test
    void anUnconfiguredIdRefusesOnlyItsOwnStatuses() {
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Status.INFORMATION);
        PartnerResponseHandler h = new PartnerResponseHandler(tsanet, intercom, links,
                InboundProperties.ofStateIds("4561712", IN_PROGRESS, null, RESOLVED));

        h.handle(responseCreated(TOKEN));
        assertEquals(0, intercom.stateMoveCalls(),
                "an unset waiting id must stop INFORMATION before any platform call");
        assertNull(links.surfacedStatus(TOKEN),
                "nothing surfaced, so nothing recorded — a later transition still fires");

        fetchAnswers(TOKEN, InboundCase.Status.ACCEPTED);
        h.handle(responseUpdated(TOKEN));
        assertEquals(IN_PROGRESS, intercom.stateOf(TICKET),
                "the statuses whose ids ARE configured keep working");
    }

    @Test
    void aFailedStateMoveRecordsNothingSoRedeliveryRetries() {
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Status.ACCEPTED);
        PartnerResponseHandler h = handler();
        intercom.failNextStateMove = true;

        h.handle(responseCreated(TOKEN));
        assertEquals(0, intercom.noteCalls(), "no note on a ticket whose state never moved");
        assertNull(links.surfacedStatus(TOKEN), "recording here would dedup the retry into silence");

        h.handle(responseUpdated(TOKEN));   // the redelivery IS the recovery surface
        assertEquals(IN_PROGRESS, intercom.stateOf(TICKET));
        assertEquals(1, intercom.noteCalls());
        assertEquals("ACCEPTED", links.surfacedStatus(TOKEN));
    }

    @Test
    void aFailedNoteLeavesTheWholeSurfaceRetryable() {
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Status.ACCEPTED);
        PartnerResponseHandler h = handler();
        intercom.failNextNote = true;

        h.handle(responseCreated(TOKEN));
        assertEquals(IN_PROGRESS, intercom.stateOf(TICKET), "the state moved before the note failed");
        assertNull(links.surfacedStatus(TOKEN), "an unrecorded surface is what lets redelivery retry");

        h.handle(responseUpdated(TOKEN));
        assertEquals(2, intercom.stateMoveCalls(),
                "the retry repeats the state move — same target id, idempotent in effect");
        assertEquals(1, intercom.notes().size(), "and the note lands exactly once");
        assertEquals("ACCEPTED", links.surfacedStatus(TOKEN));
    }

    @Test
    void aFailedRecordWriteDuplicatesTheNoteOnRedeliveryAndSaysSo() {
        // The design's one admitted residual (D22): both platform writes landed, the
        // dedup record did not. A redelivery re-runs the whole surface — including a
        // SECOND note — and this pins that as KNOWN behavior rather than a surprise.
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Status.ACCEPTED);
        SubmissionLinkTable recordFailsOnce = new DelegatingLinkTable(links) {
            private boolean failed;

            @Override
            public void surfacedStatus(String token, String status) {
                if (!failed) {
                    failed = true;
                    throw new IllegalStateException("cannot record the surfaced status");
                }
                super.surfacedStatus(token, status);
            }
        };
        PartnerResponseHandler h = new PartnerResponseHandler(tsanet, intercom, recordFailsOnce,
                InboundProperties.ofStateIds("4561712", IN_PROGRESS, WAITING, RESOLVED));

        h.handle(responseCreated(TOKEN));
        assertEquals(1, intercom.noteCalls(), "the note landed before the record failed");
        assertNull(links.surfacedStatus(TOKEN), "and the failed record left nothing behind");

        h.handle(responseUpdated(TOKEN));   // redelivery, the only recovery surface

        assertEquals(2, intercom.noteCalls(),
                "the admitted residual: an unrecorded surface re-posts the note");
        assertEquals(2, intercom.stateMoveCalls(),
                "and repeats the state move — same target id, idempotent in effect");
        assertEquals("ACCEPTED", links.surfacedStatus(TOKEN), "the retry completes the record");
    }

    @Test
    void aClosedFetchDefersToTheClosureHandler() {
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Status.CLOSED);

        handler().handle(responseCreated(TOKEN));

        assertEquals(0, intercom.stateMoveCalls(),
                "REQUEST_CLOSED owns closure; a raced response fetch must not do its job");
        assertEquals(0, intercom.noteCalls());
        assertNull(links.surfacedStatus(TOKEN));
    }

    @Test
    void anOpenFetchRecordsNothingSoALaterDeliveryCanSurface() {
        // The raced read: the response event arrived, the fetch does not show the
        // verdict yet. Recording OPEN would dedup the NEXT delivery into silence.
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Status.OPEN);
        PartnerResponseHandler h = handler();

        h.handle(responseCreated(TOKEN));
        assertEquals(0, intercom.stateMoveCalls());
        assertNull(links.surfacedStatus(TOKEN));

        fetchAnswers(TOKEN, InboundCase.Status.ACCEPTED);   // the verdict becomes visible
        h.handle(responseUpdated(TOKEN));
        assertEquals(IN_PROGRESS, intercom.stateOf(TICKET));
    }

    @Test
    void unmappedStatusesRefuseAndRecordNothing() {
        outboundLink(TOKEN, TICKET);
        fetchAnswers(TOKEN, InboundCase.Status.PENDINGACTION);

        handler().handle(responseCreated(TOKEN));

        assertEquals(0, intercom.stateMoveCalls(),
                "PENDINGACTION is deliberately unmapped (D17): moving the ticket would be"
                        + " invisibly wrong");
        assertEquals(0, intercom.noteCalls());
        assertNull(links.surfacedStatus(TOKEN));
    }

    @Test
    void aCaseWeNeverLinkedIsIgnored() {
        handler().handle(responseCreated("never-linked"));
        assertEquals(0, intercom.stateMoveCalls(), "no link means no platform call at all");
        assertEquals(0, intercom.noteCalls());
    }

    @Test
    void aTicketlessLinkIsSkippedNotSurfaced() {
        // An outbound submit from a plain Inbox conversation has no ticket to move —
        // the KNOWN partial coverage named on #45, pinned here so widening it is a
        // deliberate act rather than drift.
        links.record(new SubmissionLink(TOKEN, "out:" + TOKEN, "nxdyoagx", "conv-1",
                null, PARTNER, null, true, "agent@example.com", Instant.now()));
        fetchAnswers(TOKEN, InboundCase.Status.ACCEPTED);

        handler().handle(responseCreated(TOKEN));

        assertEquals(0, intercom.stateMoveCalls());
        assertEquals(0, intercom.noteCalls());
        assertNull(links.surfacedStatus(TOKEN));
    }

    @Test
    void theHandlerClaimsOnlyTsanetResponseEvents() {
        PartnerResponseHandler h = handler();
        assertTrue(h.handles(responseCreated(TOKEN)));
        assertTrue(h.handles(responseUpdated(TOKEN)));
        assertFalse(h.handles(new InboundEvent(InboundEvent.Source.TSANET,
                        TsanetCloudEvent.Type.REQUEST_CREATED.wireValue(), TOKEN, Instant.now(), Map.of())),
                "guard-create owns created; two handlers claiming it would race");
        assertFalse(h.handles(new InboundEvent(InboundEvent.Source.INTERCOM,
                        TsanetCloudEvent.Type.RESPONSE_CREATED.wireValue(), TOKEN, Instant.now(), Map.of())),
                "an Intercom-sourced event of the same kind is not a TSANet response");
    }
}
