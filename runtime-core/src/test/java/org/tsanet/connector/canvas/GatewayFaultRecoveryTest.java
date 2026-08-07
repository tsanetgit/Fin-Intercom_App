package org.tsanet.connector.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.tsanet.connector.tsanet.TsanetGateway;
import org.tsanet.connector.tsanet.dto.PartnerHit;
import org.tsanet.connector.tsanet.dto.ProcessForm;
import org.tsanet.connector.tsanet.dto.SubmissionDraft;
import org.tsanet.connector.tsanet.dto.SubmissionResult;

/**
 * tsanetgit/Fin-Intercom_App#2: an exception outside the gateway taxonomy used to
 * unwind past doSubmit's catches, stranding the flow in SUBMITTING — a state whose
 * only exit requires a lookup to FIND a case. This probes the fix end to end: the
 * unmapped exception parks the flow UNRESOLVED, the reconcile finds nothing and
 * re-arms, and the retry reaches the gateway and lands.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
})
class GatewayFaultRecoveryTest {

    @TestConfiguration
    static class FaultyGatewayConfig {
        @Bean
        @Primary
        FaultyGateway faultyGateway() {
            return new FaultyGateway();
        }
    }

    /** First submit throws a plain RuntimeException (outside the taxonomy); later ones land. */
    static final class FaultyGateway implements TsanetGateway {
        @Override
        public java.util.Optional<org.tsanet.connector.tsanet.dto.InboundCase> fetchByToken(String token) {
            return java.util.Optional.empty(); // not exercised by this test's flow
        }


        final AtomicInteger submits = new AtomicInteger();
        private final Map<String, SubmissionResult> store = new ConcurrentHashMap<>();

        @Override
        public boolean healthy() {
            return true;
        }

        @Override
        public List<PartnerHit> searchPartners(String query) {
            return List.of(new PartnerHit("Fault Co", "Fault Co", null, "43", "512"));
        }

        @Override
        public ProcessForm form(PartnerHit partner) {
            return new ProcessForm("512", null, null, List.of());
        }

        @Override
        public SubmissionResult submit(SubmissionDraft draft) {
            if (submits.incrementAndGet() == 1) {
                throw new IllegalStateException("mapper blew up: simulated unmapped SDK fault");
            }
            SubmissionResult result = new SubmissionResult(
                    "TSA-fault", Instant.now().plus(2, ChronoUnit.HOURS).toString(), true);
            store.put(draft.internalCaseNumber(), result);
            return result;
        }

        @Override
        public Optional<SubmissionResult> findSubmission(String internalCaseNumber) {
            return Optional.ofNullable(store.get(internalCaseNumber));
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FaultyGateway gateway;

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-intercom-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(body)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String post(String path, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sign(bytes))
                        .content(bytes))
                .andReturn().getResponse().getContentAsString();
    }

    private String submitBody(String flowId, String componentId, String inputValuesJson) {
        return "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                + "\"component_id\":\"" + componentId + "\","
                + "\"current_canvas\":{\"stored_data\":{\"flow_id\":\"" + flowId + "\"}},"
                + "\"input_values\":" + inputValuesJson + "}";
    }

    @Test
    void unmappedSubmitExceptionLeavesTheFlowRecoverable() throws Exception {
        String home = post("/canvas/initialize", "{\"workspace_id\":\"w1\"}");
        String flow = home.replaceAll("(?s).*\"flow_id\":\"([^\"]+)\".*", "$1");
        post("/canvas/submit", submitBody(flow, "search", "{\"q\":\"fault\"}"));
        post("/canvas/submit", submitBody(flow, "select:43|512", "{}"));

        String afterFault = post("/canvas/submit",
                submitBody(flow, "submit_case", "{\"summary\":\"s\",\"description\":\"d\"}"));
        assertTrue(afterFault.contains("Submission status unknown"),
                "an unmapped exception must park the flow UNRESOLVED, not strand it SUBMITTING");
        assertEquals(1, gateway.submits.get());

        String reconciled = post("/canvas/submit", submitBody(flow, "check_status", "{}"));
        assertTrue(reconciled.contains("retry_submit"),
                "the reconcile that found nothing must re-arm a retry");

        String confirmed = post("/canvas/submit", submitBody(flow, "retry_submit", "{}"));
        assertTrue(confirmed.contains("Case submitted"),
                "the retry must reach the gateway and land");
        assertEquals(2, gateway.submits.get());
    }
}
