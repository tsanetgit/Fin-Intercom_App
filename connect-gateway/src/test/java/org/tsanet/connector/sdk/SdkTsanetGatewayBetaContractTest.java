package org.tsanet.connector.sdk;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.tsanet.connector.tsanet.TsanetGateway;
import org.tsanet.connector.tsanet.TsanetGatewayContractTest;
import org.tsanet.connector.tsanet.dto.SubmissionDraft;

/**
 * The slice-2 exit criterion: the SDK-backed gateway passes the SAME contract the fake
 * passes, against TSANet BETA with the sdk1/sdk2 test members (sdk1 submits to sdk2).
 * Env-gated so CI's secretless sdk job skips it green; run locally with the tsanet-ops
 * .env loaded. Every run creates test-flagged (testSubmission=true) sdk1->sdk2 cases —
 * that is what those members exist for.
 *
 * <p>The injected-submit-timeout scenario is fake-only (nothing injects faults into
 * BETA); its semantics are covered by FakeTsanetGatewayContractTest and the deadline
 * unit tests here.
 */
@EnabledIfEnvironmentVariable(named = "TSANET_SDK1_API_USER", matches = ".+")
class SdkTsanetGatewayBetaContractTest extends TsanetGatewayContractTest {

    private static MemberSessionRegistry registry;
    private static SdkTsanetGateway gateway;

    @BeforeAll
    static void connectToBeta() {
        registry = new MemberSessionRegistry();
        gateway = registry.gatewayFor("beta-sdk1", new MemberSessionRegistry.MemberBinding(
                env("TSANET_SDK1_API_URL"),
                "target/beta-sdk1-" + UUID.randomUUID() + ".db",
                new MemberSessionRegistry.TsanetCredentials(
                        "password", env("TSANET_SDK1_API_USER"), env("TSANET_SDK1_API_PASSWORD"),
                        null, null),
                new SdkTsanetGateway.Config(env("TSANET_SDK1_API_URL"), true, 1500, 4000)));
    }

    @AfterAll
    static void disconnect() {
        if (registry != null) {
            registry.invalidate("beta-sdk1");
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when TSANET_SDK1_API_USER is present");
        }
        return value;
    }

    @Override
    protected TsanetGateway gateway() {
        return gateway;
    }

    @Override
    protected String knownPartnerQuery() {
        String name = System.getenv("TSANET_SDK2_COMPANY_NAME");
        return name == null || name.isBlank() ? "sdk2" : name;
    }

    @Override
    protected boolean supportsInjectedSubmitTimeout() {
        return false;
    }

    /** Submitter must be on sdk1's registered email domain (TSANet business rule). */
    @Override
    protected SubmissionDraft draft(String internalCaseNumber, String documentId, String summary) {
        return new SubmissionDraft(internalCaseNumber, documentId, summary,
                "Automated slice-2 contract run against BETA (test-flagged).", Map.of(),
                "SDK One Contract", env("TSANET_SDK1_API_USER"));
    }
}
