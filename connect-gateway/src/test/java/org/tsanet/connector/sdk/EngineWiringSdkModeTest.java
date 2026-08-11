package org.tsanet.connector.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.tsanet.connector.tsanet.TsanetGateway;

/**
 * The offline seam test the swap must not lose (replaces the deleted
 * SdkGatewayConfigurationTest's wiring half): proves EngineImport reaches the
 * published engine's configuration, the application.yml bridge carries
 * connector.tsanet.mode into gateway.tsanet.mode, and the engine's conditional
 * activates - the exact glue only THIS repo can test. A typo in the bridge key or
 * EngineImport drifting out of the scanned package fails here, offline, instead of
 * in the live E2E or production.
 */
@SpringBootTest(properties = {
    "connector.tsanet.mode=sdk",
    "gateway.tsanet.sdk.api-base-url=http://127.0.0.1:1",
    "gateway.tsanet.sdk.auth-mode=password",
    "gateway.tsanet.sdk.principal=wiring-test",
    "gateway.tsanet.sdk.secret=wiring-test",
    "gateway.tsanet.sdk.sqlite-path=target/engine-wiring-test.db",
})
class EngineWiringSdkModeTest {

    @Autowired
    private TsanetGateway gateway;

    @Test
    void sdkModeWiresThePublishedEngineGateway() {
        assertThat(gateway).isInstanceOf(org.tsanet.gateway.engine.SdkTsanetGateway.class);
    }
}
