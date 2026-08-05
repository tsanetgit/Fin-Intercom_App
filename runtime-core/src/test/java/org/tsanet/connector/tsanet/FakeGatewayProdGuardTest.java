package org.tsanet.connector.tsanet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The fake gateway must be impossible to ship (advisor requirement, slice 1). */
class FakeGatewayProdGuardTest {

    @Test
    void refusesToStartUnderProdProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new FakeTsanetGateway(prod).refuseProd());
    }

    @Test
    void startsAnywhereElse() {
        assertDoesNotThrow(() -> new FakeTsanetGateway(new MockEnvironment()).refuseProd());
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertDoesNotThrow(() -> new FakeTsanetGateway(dev).refuseProd());
    }
}
