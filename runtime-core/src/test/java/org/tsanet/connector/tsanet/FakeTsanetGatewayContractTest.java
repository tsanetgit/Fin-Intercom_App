package org.tsanet.connector.tsanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tsanet.connector.tsanet.dto.InboundCase;

class FakeTsanetGatewayContractTest extends TsanetGatewayContractTest {

    private final FakeTsanetGateway fake = new FakeTsanetGateway(new MockEnvironment());

    @Override
    protected TsanetGateway gateway() {
        return fake;
    }

    @Override
    protected String knownInboundToken() {
        return "inbound-contract";
    }

    @Test
    void fetchByTokenDistinguishesDirectionAndPropagatesFailures() {
        // Direction decides whether a push event creates a ticket at all, so the fake
        // must be able to produce both, and an injected read failure must throw rather
        // than read as "no such case".
        assertTrue(fake.fetchByToken("outbound-1").orElseThrow().direction()
                == org.tsanet.connector.tsanet.dto.InboundCase.Direction.OUTBOUND);
        assertThrows(GatewayException.class, () -> fake.fetchByToken("failfetch-1"));
        assertThrows(GatewayTimeoutException.class, () -> fake.fetchByToken("timeoutfetch-1"));
    }

    @Test
    void injectedFailuresAreThrown() {
        assertThrows(GatewayException.class, () -> fake.searchPartners("failsearch anything"));
        assertThrows(GatewayException.class,
                () -> fake.form(fake.searchPartners("broken").get(0)));
    }

    @Test
    void unsupportedFixtureCarriesUnsupportedField() {
        var form = fake.form(fake.searchPartners("unsupported").get(0));
        assertTrue(form.fields().stream().anyMatch(f -> !f.supported()));
    }

    @Test
    void theCursorActuallyExcludesOlderCases() {
        // The non-vacuous half of the contract's inboundListHonoursTheCursor. That one
        // can only assert "a future cursor returns nothing", which an implementation
        // with NO inbound cases satisfies without filtering anything — and empty is the
        // fake's default state, so the first version passed while the fake ignored its
        // cursor entirely (probed). Seeding two cases either side of a boundary is what
        // makes the assertion capable of failing.
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        Instant recent = Instant.parse("2026-06-01T00:00:00Z");
        fake.seedInbound(inbound("cursor-old"), old);
        fake.seedInbound(inbound("cursor-recent"), recent);

        assertEquals(2, fake.listInboundUpdatedSince(null).size(), "no cursor means everything");

        List<InboundCase> between = fake.listInboundUpdatedSince(old.plusSeconds(1));
        assertEquals(1, between.size(), "a cursor between the two must exclude the older one");
        assertEquals("cursor-recent", between.get(0).token());

        // INCLUSIVE at the boundary, deliberately: a case stamped exactly at the cursor
        // is returned. That is the safe direction — the poller subtracts an overlap so a
        // boundary case is re-offered rather than skipped, and re-offering is free
        // (the caller skips anything already linked) while skipping loses a partner case.
        assertEquals(2, fake.listInboundUpdatedSince(old).size(),
                "a case stamped exactly at the cursor must be included, not skipped");

        assertTrue(fake.listInboundUpdatedSince(recent.plusSeconds(1)).isEmpty(),
                "a cursor after both must exclude both");
    }

    @Test
    void anOutboundCaseIsNeverListedAsInbound() {
        // Seeded through the same door as an inbound case, so the only thing keeping it
        // out of the feed is the direction filter itself. A ticket created for one of
        // our own submissions is the worst outcome available to the poller.
        fake.seedInbound(new InboundCase("outbound-seeded", InboundCase.Direction.OUTBOUND,
                        InboundCase.Status.OPEN, "Test sdk2", "ours", "we sent this",
                        null, "MEDIUM", true),
                Instant.parse("2026-06-01T00:00:00Z"));
        assertTrue(fake.listInboundUpdatedSince(null).stream()
                        .noneMatch(c -> "outbound-seeded".equals(c.token())),
                "an OUTBOUND case reached the inbound feed");
    }

    @Test
    void listFailuresPropagateRatherThanReadingAsNoWork() {
        // Empty and failed mean opposite things to the poller: empty advances the
        // cursor, failed must not. Swallowing the failure here would make the poller
        // skip a window it never actually read.
        fake.failListWith(new GatewayTimeoutException("injected list timeout"));
        assertThrows(GatewayTimeoutException.class, () -> fake.listInboundUpdatedSince(null));
        fake.failListWith(null);
    }

    private static InboundCase inbound(String token) {
        return new InboundCase(token, InboundCase.Direction.INBOUND, InboundCase.Status.OPEN,
                "Test sdk2", "seeded", "seeded case", null, "MEDIUM", true);
    }
}
