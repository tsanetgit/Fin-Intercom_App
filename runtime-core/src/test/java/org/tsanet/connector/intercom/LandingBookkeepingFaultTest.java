package org.tsanet.connector.intercom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * QA round-2 blocker: recordLanded used to run inside the gateway try, so a
 * bookkeeping throw was misreported as "the gateway threw outside the taxonomy", the
 * agent saw "Submission status unknown" for a case that definitively landed, and the
 * link + annotation were lost with no audit line. The fix isolates bookkeeping: the
 * confirmation must render regardless, the loss must cost exactly the bookkeeping,
 * and the audited token is the manual re-drive key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
})
class LandingBookkeepingFaultTest {

    @TestConfiguration
    static class FaultyTableConfig {
        @Bean
        @Primary
        FaultyLinkTable faultyLinkTable() {
            return new FaultyLinkTable();
        }
    }

    /** Throws on the next record() when armed; a plain in-memory table otherwise. */
    static final class FaultyLinkTable extends InMemorySubmissionLinkTable {
        volatile boolean throwOnNextRecord;

        @Override
        public boolean record(SubmissionLink link) {
            if (throwOnNextRecord) {
                throwOnNextRecord = false;
                throw new IllegalStateException("injected bookkeeping failure");
            }
            return super.record(link);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FaultyLinkTable links;

    @Autowired
    private FakeIntercomGateway intercom;

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
                + "\"conversation\":{\"id\":42},"
                + "\"component_id\":\"" + componentId + "\","
                + "\"current_canvas\":{\"stored_data\":{\"flow_id\":\"" + flowId + "\"}},"
                + "\"input_values\":" + inputValuesJson + "}";
    }

    @Test
    void bookkeepingFailureCostsExactlyTheBookkeeping() throws Exception {
        String home = post("/canvas/initialize", "{\"workspace_id\":\"w1\"}");
        String flow = home.replaceAll("(?s).*\"flow_id\":\"([^\"]+)\".*", "$1");
        post("/canvas/submit", submitBody(flow, "search", "{\"q\":\"netapp\"}"));
        post("/canvas/submit", submitBody(flow, "select:1042|512", "{}"));

        links.throwOnNextRecord = true;
        String confirmed = post("/canvas/submit", submitBody(flow, "submit_case",
                "{\"summary\":\"s\",\"description\":\"d\",\"f_88\":\"SN\",\"f_89\":\"High\"}"));
        assertTrue(confirmed.contains("Case submitted"),
                "the case landed; a bookkeeping throw must never be reported as a gateway fault");
        assertTrue(!confirmed.contains("Submission status unknown"));

        // The loss is exactly the bookkeeping: no link, no annotation ever enqueued —
        // and the flow itself stays healthy (LANDED renders its confirmation).
        assertTrue(links.byInternalCaseNumber(flow).isEmpty(), "the link was lost (audited)");
        assertTrue(intercom.annotated().stream().noneMatch(l -> flow.equals(l.internalCaseNumber())),
                "no annotation may be enqueued for the lost link");
        String again = post("/canvas/submit", submitBody(flow, "submit_case",
                "{\"summary\":\"s\",\"description\":\"d\",\"f_88\":\"SN\",\"f_89\":\"High\"}"));
        assertTrue(again.contains("Case submitted"));
    }
}
