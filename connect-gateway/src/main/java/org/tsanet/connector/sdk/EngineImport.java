package org.tsanet.connector.sdk;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Bridges the published gateway engine into this application's component scan.
 *
 * <p>The engine's configuration lives in {@code org.tsanet.gateway.engine}, outside
 * {@code org.tsanet.connector}'s default scan, and the artifact deliberately ships no
 * auto-configuration registration - embedding it is an explicit act. This class IS that
 * act: it sits inside the scanned package and imports the engine configuration, whose
 * own {@code gateway.tsanet.mode=sdk} conditional still decides whether it activates
 * (application.yml bridges that from {@code connector.tsanet.mode}, so the operator
 * surface is unchanged).
 */
@Configuration
@Import(org.tsanet.gateway.engine.SdkGatewayConfiguration.class)
public class EngineImport {
}
