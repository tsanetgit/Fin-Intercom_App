package org.tsanet.connector.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.tsanet.connector.tsanet.FakeTsanetGateway;
import org.tsanet.connector.tsanet.TsanetGateway;

/**
 * The other half of the wiring proof: fake mode must NOT activate the engine even
 * with EngineImport on the classpath - the bridge propagates the mode, and the
 * engine's own conditional stands down.
 */
@SpringBootTest(properties = {"connector.tsanet.mode=fake"})
class EngineWiringFakeModeTest {

    @Autowired
    private TsanetGateway gateway;

    @Test
    void fakeModeKeepsTheFakeAndTheEngineStandsDown() {
        assertThat(gateway).isInstanceOf(FakeTsanetGateway.class);
    }
}
