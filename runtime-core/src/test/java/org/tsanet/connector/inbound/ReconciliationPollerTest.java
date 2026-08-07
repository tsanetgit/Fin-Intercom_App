package org.tsanet.connector.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tsanet.connector.intercom.FakeIntercomGateway;
import org.tsanet.connector.intercom.InMemorySubmissionLinkTable;
import org.tsanet.connector.intercom.SubmissionLink;
import org.tsanet.connector.intercom.SubmissionLinkTable;
import org.tsanet.connector.tsanet.FakeTsanetGateway;
import org.tsanet.connector.tsanet.GatewayTimeoutException;
import org.tsanet.connector.tsanet.dto.InboundCase;

/**
 * The poller's contract, which is mostly about what it must NOT do: create a second
 * ticket for a case push already handled, and advance its cursor past anything it did
 * not successfully turn into a ticket.
 */
class ReconciliationPollerTest {

    private static final String TYPE = "4561712";

    private final FakeTsanetGateway tsanet = new FakeTsanetGateway(new MockEnvironment());
    private final FakeIntercomGateway intercom = new FakeIntercomGateway(new MockEnvironment());
    private final SubmissionLinkTable links = new InMemorySubmissionLinkTable();
    private final PollerStateStore state = new PollerStateStore.InMemory();

    private ReconciliationPoller poller(String ticketType) {
        InboundProperties props = new InboundProperties(ticketType, true,
                Duration.ofHours(24), Duration.ofSeconds(120));
        return new ReconciliationPoller(tsanet,
                new InboundRequestHandler(tsanet, intercom, links, props), links, state, props);
    }

    private static InboundCase inbound(String token, Instant updatedAt) {
        return new InboundCase(token, InboundCase.Direction.INBOUND, InboundCase.Status.OPEN,
                "Test sdk2", "Replica lag", "described", null, "HIGH", true, updatedAt);
    }

    @Test
    void aMissedCaseBecomesATicketAndMovesTheCursor() {
        Instant at = Instant.now().minus(Duration.ofMinutes(5));
        tsanet.seedInbound(inbound("inbound-missed", at));

        poller(TYPE).cycle();

        assertTrue(links.byToken("inbound-missed").isPresent(),
                "the whole point: a case push never delivered must become a ticket");
        assertEquals(Optional.of(at), state.cursor(),
                "the cursor advances to the case's own updatedAt — the SERVER's clock");
    }

    @Test
    void aCaseAlreadyLinkedIsNotTicketedTwice() {
        // The common path by a wide margin: push handled it, the poller sees it again
        // inside the overlap window. A second ticket here would be the duplicate-ticket
        // defect the whole inbound design exists to prevent, reintroduced by its own
        // safety net.
        Instant at = Instant.now().minus(Duration.ofMinutes(5));
        links.record(new SubmissionLink("inbound-known", "inbound:inbound-known", null, null,
                "ticket-1", "Test sdk2", null, true, null, Instant.now()));
        tsanet.seedInbound(inbound("inbound-known", at));

        poller(TYPE).cycle();

        assertEquals("ticket-1", links.byToken("inbound-known").orElseThrow().ticketId(),
                "the existing link must be untouched, not re-anchored");
        assertEquals(0, intercom.createCalls(), "no platform write for an already-linked case");
    }

    @Test
    void aFailedListLeavesTheCursorExactlyWhereItWas() {
        // Advancing over a window that was never read is how a case is lost forever.
        state.cursor(Instant.parse("2026-08-01T00:00:00Z"));
        tsanet.failListWith(new GatewayTimeoutException("injected list failure"));

        ReconciliationPoller p = poller(TYPE);
        p.cycle();

        assertEquals(Optional.of(Instant.parse("2026-08-01T00:00:00Z")), state.cursor(),
                "a failed cycle must not move the mark");
        assertEquals(1, p.consecutiveFailures());
        tsanet.failListWith(null);
    }

    @Test
    void oneFailingCaseHoldsTheCursorForTheWholeCycle() {
        // The subtle one. A cycle that created three tickets and failed the fourth must
        // NOT advance, even though most of it succeeded: the mark is a single high-water
        // point, so advancing past the failure is exactly as lossy as advancing past
        // everything.
        Instant early = Instant.now().minus(Duration.ofMinutes(10));
        Instant late = Instant.now().minus(Duration.ofMinutes(1));
        // Already linked, so it needs no create and DOES contribute its updatedAt to the
        // cycle's high-water mark — this is what makes the assertion sharp: there is a
        // perfectly good value to advance to, and the cycle must still refuse.
        links.record(new SubmissionLink("inbound-early", "inbound:inbound-early", null, null,
                "ticket-early", "Test sdk2", null, true, null, Instant.now()));
        tsanet.seedInbound(inbound("inbound-early", early));
        tsanet.seedInbound(inbound("inbound-failcreate", late));
        // Only one create happens this cycle (the linked case is skipped), so this
        // targets the failing case deterministically rather than by ordering luck.
        intercom.failNextCreate = true;

        ReconciliationPoller p = poller(TYPE);
        p.cycle();

        assertTrue(links.byToken("inbound-failcreate").isEmpty(),
                "a failed create must release its claim, leaving the token retryable");
        assertTrue(state.cursor().isEmpty(),
                "a dirty cycle must not advance the cursor, not even to the part that worked");
        assertEquals(1, p.consecutiveFailures());
    }

    @Test
    void anUnconfiguredTicketTypeStopsTheCycleInsteadOfFloodingPerCase() {
        // Without the gate this emits one error line PER CASE, every cycle, forever.
        tsanet.seedInbound(inbound("inbound-a", Instant.now()));
        tsanet.seedInbound(inbound("inbound-b", Instant.now()));

        poller("").cycle();

        assertTrue(links.byToken("inbound-a").isEmpty());
        assertTrue(state.cursor().isEmpty(), "an unconfigured poller must not move the mark either");
    }

    @Test
    void anEmptyWindowDoesNotAdvanceTheCursorToNow() {
        // Advancing to "now" on an empty result would step over any case whose
        // updatedAt lands between the query and the write.
        state.cursor(Instant.parse("2026-08-01T00:00:00Z"));

        poller(TYPE).cycle();

        assertEquals(Optional.of(Instant.parse("2026-08-01T00:00:00Z")), state.cursor(),
                "nothing observed means nothing to advance to");
    }

    @Test
    void disablingTheGuardStopsTheCycleEntirely() {
        tsanet.seedInbound(inbound("inbound-disabled", Instant.now()));
        InboundProperties off = new InboundProperties(TYPE, false, null, null);
        new ReconciliationPoller(tsanet,
                new InboundRequestHandler(tsanet, intercom, links, off), links, state, off).cycle();
        assertTrue(links.byToken("inbound-disabled").isEmpty());
    }

    @Test
    void theCursorIsQueriedWithOverlapSubtracted() {
        // The overlap is the difference between "re-list a case harmlessly" and "skip a
        // case whose timestamp sat on the boundary". Asserted by observing the cursor
        // the gateway is actually asked for, not by reading the arithmetic.
        Instant stored = Instant.parse("2026-08-06T12:00:00Z");
        state.cursor(stored);
        RecordingGateway recording = new RecordingGateway();
        InboundProperties props = new InboundProperties(TYPE, true,
                Duration.ofHours(24), Duration.ofSeconds(120));
        new ReconciliationPoller(recording,
                new InboundRequestHandler(recording, intercom, links, props), links, state, props)
                .cycle();
        assertEquals(stored.minusSeconds(120), recording.asked,
                "the query must reach back past the stored mark by exactly the overlap");
    }

    @Test
    void aFirstRunAsksForABoundedWindowNotEpoch() {
        // The list endpoint has no pagination, so an unbounded first query is the one
        // way this component can hurt the API it is protecting.
        RecordingGateway recording = new RecordingGateway();
        InboundProperties props = new InboundProperties(TYPE, true,
                Duration.ofHours(24), Duration.ofSeconds(120));
        new ReconciliationPoller(recording,
                new InboundRequestHandler(recording, intercom, links, props), links, state, props)
                .cycle();
        assertFalse(recording.asked.isBefore(Instant.now().minus(Duration.ofHours(25))),
                "a first run must ask for the bounded lookback, not the beginning of time");
    }

    /** Captures the cursor the poller actually asks for. */
    private static final class RecordingGateway extends FakeTsanetGateway {
        private Instant asked;

        RecordingGateway() {
            super(new MockEnvironment());
        }

        @Override
        public List<InboundCase> listInboundUpdatedSince(Instant updatedAfter) {
            this.asked = updatedAfter;
            return List.of();
        }
    }
}
