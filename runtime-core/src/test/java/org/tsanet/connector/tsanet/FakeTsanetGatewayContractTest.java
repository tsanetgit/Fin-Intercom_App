package org.tsanet.connector.tsanet;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

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
}
