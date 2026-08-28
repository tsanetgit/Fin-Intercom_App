package org.tsanet.connector.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Tickets-only mode (#65): with the flag on, plain-conversation surfaces get the
 * static refusal (no flow created, nothing to click), ticket surfaces get the normal
 * outbound flow, and pre-existing conversation flows are refused on EVERY component —
 * check_status included — as chosen behavior, not an accident. The flag-off
 * conversation regression lives in {@link OutboundFlowTest}, whose fixtures are
 * conversation-surface payloads under the default (off) flag.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
        "connector.tickets-only=true",
})
class TicketsOnlyModeTest {

    private static final String NOTICE = "available from tickets only";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FlowStateStore flows;

    @Autowired
    private org.tsanet.connector.intercom.SubmissionLinkTable links;

    private final ObjectMapper json = new ObjectMapper();

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-intercom-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(body)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private JsonNode post(String path, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String response = mvc.perform(MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sign(bytes))
                        .content(bytes))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static String allText(JsonNode canvas) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<JsonNode> it = canvas.at("/canvas/content/components").values().iterator(); it.hasNext(); ) {
            JsonNode component = it.next();
            if (component.has("text")) {
                sb.append(component.get("text").asText()).append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean hasButtons(JsonNode canvas) {
        for (Iterator<JsonNode> it = canvas.at("/canvas/content/components").values().iterator(); it.hasNext(); ) {
            if ("button".equals(it.next().path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void conversationInitializeGetsTheNoticeAndMintsNoFlow() throws Exception {
        JsonNode canvas = post("/canvas/initialize",
                "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"}}");
        assertTrue(allText(canvas).contains(NOTICE), "conversation surface must get the refusal");
        assertFalse(hasButtons(canvas), "the refusal is static: nothing to click");
        // No flow pointer is the observable for "no flow minted": every real canvas
        // carries stored_data.flow_id (CanvasKit.canvas is the only builder and the
        // home canvas always stores one); the refusal deliberately has none.
        assertTrue(canvas.at("/canvas/stored_data/flow_id").isMissingNode(), "no flow pointer");
    }

    @Test
    void ticketInitializeStillGetsTheOutboundFlow() throws Exception {
        JsonNode canvas = post("/canvas/initialize",
                "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                        + "\"ticket\":{\"id\":\"t1\"},\"conversation\":{\"id\":\"t1\"}}");
        assertFalse(allText(canvas).contains(NOTICE), "ticket surface is unaffected by the flag");
        assertFalse(canvas.at("/canvas/stored_data/flow_id").isMissingNode(),
                "ticket surface mints a normal outbound flow");
    }

    @Test
    void conversationSubmitIsRefusedOnEveryComponentIncludingCheckStatus() throws Exception {
        // A pre-existing conversation flow (created before the flag turned on, e.g.
        // pre-deploy): every component is refused, check_status included — pinned as
        // chosen behavior. The guard must not touch the flow itself.
        String flowId = flows.create().flowId();
        for (String component : new String[] {"search", "check_status", "back_home"}) {
            JsonNode canvas = post("/canvas/submit",
                    "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                            + "\"component_id\":\"" + component + "\","
                            + "\"current_canvas\":{\"stored_data\":{\"flow_id\":\"" + flowId + "\"}},"
                            + "\"input_values\":{}}");
            assertTrue(allText(canvas).contains(NOTICE), component + " must be refused on a conversation");
        }
        assertEquals(OutboundFlowState.Status.DRAFT, flows.find(flowId).status(),
                "the refusal is non-destructive: the flow survives untouched");
    }

    @Test
    void inboundTicketPanelStillRendersUnderTheFlag() throws Exception {
        // A partner case WE anchored (internalCaseNumber carries the inbound prefix,
        // "inbound:" — package-private in InboundRequestHandler, literal here): the
        // inbound panel must win over both the flag and the outbound flow. Structural
        // by orElseGet ordering; pinned so a reordering cannot ship silently.
        links.record(new org.tsanet.connector.intercom.SubmissionLink(
                "tok-inb", "inbound:tok-inb", "w1", "ticket-9", "ticket-9",
                "Partner Co", "2026-09-01T00:00:00Z", true, "probe", java.time.Instant.now()));
        JsonNode canvas = post("/canvas/initialize",
                "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                        + "\"ticket\":{\"id\":\"ticket-9\"},\"conversation\":{\"id\":\"ticket-9\"}}");
        assertFalse(allText(canvas).contains(NOTICE), "inbound panel, not the refusal");
        assertTrue(allText(canvas).contains("From: Partner Co"), "the case view must render");
    }

    @Test
    void ticketSubmitStillWorks() throws Exception {
        String flowId = flows.create().flowId();
        JsonNode canvas = post("/canvas/submit",
                "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                        + "\"component_id\":\"search\",\"ticket\":{\"id\":\"t1\"},"
                        + "\"current_canvas\":{\"stored_data\":{\"flow_id\":\"" + flowId + "\"}},"
                        + "\"input_values\":{\"q\":\"\"}}");
        assertFalse(allText(canvas).contains(NOTICE), "ticket surface submit is unaffected");
    }
}
