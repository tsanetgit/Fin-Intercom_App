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
