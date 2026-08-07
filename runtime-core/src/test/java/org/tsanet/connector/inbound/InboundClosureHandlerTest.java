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
import org.tsanet.connector.webhooks.TsanetCloudEvent;

/**
 * Inbound closure: the partner closed the case, so the ticket mirroring it resolves.
 *
 * <p>Most of these assert what must NOT happen — a second write on redelivery, a
 * recorded CLOSED when the ticket never moved, a guessed state id.
 */
class InboundClosureHandlerTest {

    private static final String TOKEN = "inbound-closed";
    private static final String TICKET = "215475371561486";
    private static final String RESOLVED = "9619799";

    private final FakeIntercomGateway intercom = new FakeIntercomGateway(new MockEnvironment());
    private final SubmissionLinkTable links = new InMemorySubmissionLinkTable();

    private InboundClosureHandler handler(String stateId) {
        return new InboundClosureHandler(intercom, links,
                InboundProperties.ofTicketTypeAndResolvedState("4561712", stateId));
    }

    private static InboundEvent closed(String token) {
        return new InboundEvent(InboundEvent.Source.TSANET,
                TsanetCloudEvent.Type.REQUEST_CLOSED.wireValue(), token, Instant.now(), Map.of());
    }

    private void linkAnchored(String token, String ticketId) {
        links.record(new SubmissionLink(token, "inbound:" + token, null, null,
                ticketId, "Test sdk2", null, true, null, Instant.now()));
        links.anchor(token, ticketId);
    }

    @Test
    void aPartnerClosureResolvesTheTicketAndRecordsIt() {
        linkAnchored(TOKEN, TICKET);

        handler(RESOLVED).handle(closed(TOKEN));

        assertEquals(RESOLVED, intercom.stateOf(TICKET),
                "the whole point: the ticket mirroring a closed case must resolve");
        assertEquals(CaseState.CLOSED, links.caseState(TOKEN));
    }

    @Test
    void closureIsIdempotentUnderRedelivery() {
        // TSANet redelivers on any non-2xx and the drain can replay, so this arrives
        // more than once. A second write is not merely wasteful — it is a second
        // partner-visible-surface mutation with nothing guarding it.
        linkAnchored(TOKEN, TICKET);
        InboundClosureHandler h = handler(RESOLVED);
        h.handle(closed(TOKEN));
        intercom.failNextStateMove = true;   // a second move would throw and be visible

        h.handle(closed(TOKEN));

        assertEquals(CaseState.CLOSED, links.caseState(TOKEN));
        assertTrue(intercom.failNextStateMove,
                "the injected failure must still be armed — proof no second move was attempted");
    }

    @Test
    void aFailedResolveDoesNotRecordClosed() {
        // The ordering that matters. Recording CLOSED after a failed platform write
        // would make the next delivery a no-op and strand the ticket open forever, while
        // telling an agent the case is closed.
        linkAnchored(TOKEN, TICKET);
        intercom.failNextStateMove = true;

        handler(RESOLVED).handle(closed(TOKEN));

        assertNull(intercom.stateOf(TICKET), "the ticket never moved");
        assertEquals(CaseState.PENDING, links.caseState(TOKEN),
                "so the state must stay un-closed, leaving a redelivery able to fix it");
    }

    @Test
    void anUnconfiguredStateIdRefusesRatherThanGuessing() {
        // State ids are per-workspace and a wrong one may not 404 — it may name a
        // different state that happens to exist in this workspace.
        linkAnchored(TOKEN, TICKET);

        handler("").handle(closed(TOKEN));

        // NOT CALLED AT ALL, not merely "no state resulted". Asserting the outcome alone
        // passes even without the guard, because the gateway rejects a blank id itself —
        // so the weaker assertion cannot tell a refusal from a rejected attempt (probed).
        assertEquals(0, intercom.stateMoveCalls(),
                "an unconfigured state id must stop the handler before it calls Intercom");
        assertNull(intercom.stateOf(TICKET));
        assertEquals(CaseState.PENDING, links.caseState(TOKEN));
    }

    @Test
    void aCaseWeNeverTicketedIsLeftAlone() {
        // Creating a ticket now, only to close it, would put a dead item in a queue.
        handler(RESOLVED).handle(closed("never-seen"));
        assertEquals(0, intercom.stateMoveCalls(), "no link means no platform call at all");
    }

    @Test
    void anUnanchoredClaimIsNotResolved() {
        // The create is still in flight; there is no ticket to move yet.
        links.record(new SubmissionLink(TOKEN, "inbound:" + TOKEN, null, null,
                null, "Test sdk2", null, true, null, Instant.now()));

        handler(RESOLVED).handle(closed(TOKEN));

        assertEquals(0, intercom.stateMoveCalls(), "no anchor means no ticket to move");
        assertEquals(CaseState.PENDING, links.caseState(TOKEN));
    }

    @Test
    void closureAppliesToAnAlreadyAnsweredCase() {
        // A partner can close a case we accepted. The recorded ACCEPTED must not block
        // the close — the compare-and-set moves from whatever state currently holds,
        // which is why closure does not CAS from PENDING like the panel does.
        linkAnchored(TOKEN, TICKET);
        assertTrue(links.caseState(TOKEN, CaseState.PENDING, CaseState.ACCEPTED));

        handler(RESOLVED).handle(closed(TOKEN));

        assertEquals(RESOLVED, intercom.stateOf(TICKET));
        assertEquals(CaseState.CLOSED, links.caseState(TOKEN),
                "closure moves from ACCEPTED too, not only from PENDING");
    }

    @Test
    void theHandlerClaimsOnlyClosureEvents() {
        InboundClosureHandler h = handler(RESOLVED);
        assertTrue(h.handles(closed(TOKEN)));
        assertFalse(h.handles(new InboundEvent(InboundEvent.Source.TSANET,
                        TsanetCloudEvent.Type.REQUEST_CREATED.wireValue(), TOKEN, Instant.now(), Map.of())),
                "guard-create owns created; two handlers claiming it would race");
        assertFalse(h.handles(new InboundEvent(InboundEvent.Source.INTERCOM,
                TsanetCloudEvent.Type.REQUEST_CLOSED.wireValue(), TOKEN, Instant.now(), Map.of())),
                "an Intercom-sourced event of the same kind is not a TSANet closure");
    }
}
