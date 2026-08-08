package org.tsanet.connector.intercom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The outbound pipeline end to end against both fakes: a ticket-surface submission
 * lands, the link is recorded with the anchor from the landing request's payload, the
 * annotation drains asynchronously (polled — the D2 budget forbids it inside the
 * canvas response), and a second landing of the same flow changes nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
})
class AnnotationFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SubmissionLinkTable links;

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

    /** Ticket-surface payload: top-level ticket key rides every request type (spike log). */
    private String submitBody(String flowId, String componentId, String inputValuesJson) {
        return "{\"workspace_id\":\"w-test\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                + "\"conversation\":{\"id\":888111},"
                + "\"ticket\":{\"id\":\"999123\"},"
                + "\"component_id\":\"" + componentId + "\","
                + "\"current_canvas\":{\"stored_data\":{\"flow_id\":\"" + flowId + "\"}},"
                + "\"input_values\":" + inputValuesJson + "}";
    }

    @Test
    void landingRecordsTheLinkAndAnnotatesAsynchronously() throws Exception {
        String home = post("/canvas/initialize", "{\"workspace_id\":\"w-test\",\"ticket\":{\"id\":\"999123\"}}");
        String flow = home.replaceAll("(?s).*\"flow_id\":\"([^\"]+)\".*", "$1");
        post("/canvas/submit", submitBody(flow, "search", "{\"q\":\"netapp\"}"));
        post("/canvas/submit", submitBody(flow, "select:1042|512", "{}"));
        String confirmed = post("/canvas/submit", submitBody(flow, "submit_case",
                "{\"summary\":\"Latency spike\",\"description\":\"desc\",\"f_88\":\"SN-1\",\"f_89\":\"High\"}"));
        assertTrue(confirmed.contains("Case submitted"));

        SubmissionLink link = links.byInternalCaseNumber(flow).orElseThrow();
        assertEquals("w-test", link.workspaceId());
        assertEquals("888111", link.conversationId(), "anchor comes from the landing request's payload");
        assertEquals("999123", link.ticketId());
        assertEquals("NetApp | Storage Division", link.partnerDisplay());
        assertEquals("agent@tsanet.org", link.submittedBy());
        assertTrue(link.testCase(), "the fake mints test-flagged results");

        long deadline = System.currentTimeMillis() + 5_000;
        while (links.annotationState(link.token()) == AnnotationState.NONE
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(AnnotationState.FULL, links.annotationState(link.token()),
                "the annotation must drain asynchronously to FULL");
        assertEquals(1, intercom.attempts(link.token()));

        // A stale view landing the same flow again must change nothing.
        String again = post("/canvas/submit", submitBody(flow, "submit_case",
                "{\"summary\":\"Latency spike\",\"description\":\"desc\",\"f_88\":\"SN-1\",\"f_89\":\"High\"}"));
        assertTrue(again.contains("Case submitted"));
        assertEquals(1, intercom.attempts(link.token()), "exactly one annotation per flow");
    }

    @Test
    void conversationSurfaceLinksWithoutATicket() throws Exception {
        String body = "{\"workspace_id\":\"w-test\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                + "\"conversation\":{\"id\":777222}}";
        String home = post("/canvas/initialize", body);
        String flow = home.replaceAll("(?s).*\"flow_id\":\"([^\"]+)\".*", "$1");
        String convBody = "{\"workspace_id\":\"w-test\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                + "\"conversation\":{\"id\":777222},"
                + "\"component_id\":\"%s\","
                + "\"current_canvas\":{\"stored_data\":{\"flow_id\":\"" + flow + "\"}},"
                + "\"input_values\":%s}";
        post("/canvas/submit", convBody.formatted("search", "{\"q\":\"netapp\"}"));
        post("/canvas/submit", convBody.formatted("select:1042|512", "{}"));
        String confirmed = post("/canvas/submit", convBody.formatted("submit_case",
                "{\"summary\":\"s\",\"description\":\"d\",\"f_88\":\"SN\",\"f_89\":\"Low\"}"));
        assertTrue(confirmed.contains("Case submitted"));

        SubmissionLink link = links.byInternalCaseNumber(flow).orElseThrow();
        assertEquals("777222", link.conversationId());
        assertTrue(link.ticketId() == null, "conversation surface has no ticket anchor");
        assertEquals(link, links.byConversationId("777222").get(0),
                "inbound-from-Intercom routing must find the link by conversation");
    }
}
