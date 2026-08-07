package org.tsanet.connector.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tsanet.connector.events.InboundEvent;
import org.tsanet.connector.intercom.FakeIntercomGateway;
import org.tsanet.connector.intercom.InMemorySubmissionLinkTable;
import org.tsanet.connector.intercom.SubmissionLinkTable;
import org.tsanet.connector.tsanet.FakeTsanetGateway;
import org.tsanet.connector.webhooks.TsanetCloudEvent;

/**
 * The product-shaped probes for the first inbound slice: a partner's case becomes
 * exactly one ticket, and stays exactly one however the event arrives.
 */
class InboundRequestHandlerTest {

    private static final String TYPE_ID = "4561712";

    private final FakeTsanetGateway tsanet = new FakeTsanetGateway(new MockEnvironment());
    private final FakeIntercomGateway intercom = new FakeIntercomGateway(new MockEnvironment());
    private final SubmissionLinkTable links = new InMemorySubmissionLinkTable();
    private final InboundRequestHandler handler =
            new InboundRequestHandler(tsanet, intercom, links, new InboundProperties(TYPE_ID));

    private static InboundEvent created(String token) {
        return new InboundEvent(InboundEvent.Source.TSANET,
                TsanetCloudEvent.Type.REQUEST_CREATED.wireValue(), token, Instant.now(), Map.of());
    }

    @Test
    void aPartnerCaseBecomesATicketAndIsRoutableByToken() {
        handler.handle(created("inbound-1"));

        assertEquals(1, intercom.createCalls());
        String ticketId = intercom.ticketsByToken().get("inbound-1");
        assertEquals(ticketId, links.byToken("inbound-1").orElseThrow().ticketId(),
                "the link must carry the anchor, or later events cannot find the ticket");
        assertEquals("NetApp", links.byToken("inbound-1").orElseThrow().partnerDisplay());
    }

    @Test
    void aRedeliveredEventDoesNotCreateASecondTicket() {
        handler.handle(created("inbound-2"));
        handler.handle(created("inbound-2"));
        handler.handle(created("inbound-2"));

        assertEquals(1, intercom.createCalls(), "redelivery must not duplicate the partner's case");
    }

    @Test
    void concurrentDeliveriesOfTheSameEventCreateExactlyOneTicket() throws Exception {
        // The defect this design exists to prevent: check-then-create lets two
        // deliveries both find no link and both create. Claim-before-create makes the
        // token's uniqueness the arbiter instead.
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Callable<Void>> all = new java.util.ArrayList<>();
            for (int i = 0; i < racers; i++) {
                all.add(() -> {
                    handler.handle(created("inbound-race"));
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(all)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, intercom.createCalls(),
                "exactly one racer may create the ticket, whatever the interleaving");
    }

    @Test
    void aFailedCreateReleasesTheClaimSoRedeliveryCanRetry() {
        intercom.failNextCreate = true;
        handler.handle(created("inbound-3"));
        assertTrue(links.byToken("inbound-3").isEmpty(),
                "a claim outliving a failed create would mark the token handled forever");

        handler.handle(created("inbound-3"));
        assertEquals(2, intercom.createCalls(), "the retry must be allowed to try again");
        assertFalse(links.byToken("inbound-3").isEmpty(), "and the retry must land the link");
    }

    @Test
    void ourOwnOutboundCaseIsNotTurnedIntoATicket() {
        // Push fires for both directions. A case we submitted already has its anchor;
        // creating a ticket for it would duplicate the agent's own work.
        handler.handle(created("outbound-1"));
        assertEquals(0, intercom.createCalls());
        assertTrue(links.byToken("outbound-1").isEmpty());
    }

    @Test
    void aFailedReadClaimsNothing() {
        // Nothing is claimed before the read succeeds, so a failure — including the
        // ambiguous kind — leaves the token free for redelivery.
        handler.handle(created("failfetch-1"));
        handler.handle(created("timeoutfetch-1"));
        assertEquals(0, intercom.createCalls());
        assertTrue(links.byToken("failfetch-1").isEmpty());
        assertTrue(links.byToken("timeoutfetch-1").isEmpty());
    }

    @Test
    void anUnknownTokenAndAnUnconfiguredTypeBothRefuseToCreate() {
        handler.handle(created("no-such-case"));
        assertEquals(0, intercom.createCalls());

        InboundRequestHandler unconfigured =
                new InboundRequestHandler(tsanet, intercom, links, new InboundProperties("  "));
        unconfigured.handle(created("inbound-9"));
        assertEquals(0, intercom.createCalls(),
                "a missing ticket type must refuse, not create a ticket of the wrong type");
    }

    @Test
    void onlyTheCreatedEventIsClaimed() {
        // The other four push types belong to later slices; claiming them now would
        // silently swallow events this slice cannot handle.
        assertTrue(handler.handles(created("inbound-1")));
        for (TsanetCloudEvent.Type type : TsanetCloudEvent.Type.values()) {
            if (type != TsanetCloudEvent.Type.REQUEST_CREATED) {
                assertFalse(handler.handles(new InboundEvent(InboundEvent.Source.TSANET,
                        type.wireValue(), "inbound-1", Instant.now(), Map.of())));
            }
        }
        assertFalse(handler.handles(new InboundEvent(InboundEvent.Source.INTERCOM,
                TsanetCloudEvent.Type.REQUEST_CREATED.wireValue(), "inbound-1", Instant.now(), Map.of())));
    }
}
