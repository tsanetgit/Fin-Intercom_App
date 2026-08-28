package org.tsanet.connector.canvas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.tsanet.connector.intercom.SubmissionLink;
import org.tsanet.connector.intercom.SubmissionLinkTable;

/**
 * The dispatch seam between the controller and {@link org.tsanet.connector.inbound.InboundPanel}
 * — the code no other test executes. InboundPanelTest calls {@code panel.submit()}
 * directly, which is how #33 shipped: the panel's logic was tested and correct while the
 * controller path to it was broken, because inbound canvases store no flow pointer and
 * the expired-flow guard ran first. Every request here is byte-shaped as Intercom sends
 * it: the click's {@code current_canvas.stored_data} is whatever the previous canvas
 * returned, verbatim.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
})
class InboundPanelDispatchTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SubmissionLinkTable links;

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
            if (component.has("label")) {
                sb.append(component.get("label").asText()).append('\n');
            }
        }
        return sb.toString();
    }

    private String click(String ticketId, String componentId, JsonNode currentCanvas) throws Exception {
        return "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                + "\"ticket\":{\"id\":\"" + ticketId + "\"},"
                + "\"component_id\":\"" + componentId + "\","
                + "\"current_canvas\":{\"stored_data\":" + currentCanvas.at("/canvas/stored_data") + "},"
                + "\"input_values\":{}}";
    }

    @Test
    void anInboundClickReachesThePanelWithoutAnOutboundFlow() throws Exception {
        // "inbound:" is InboundRequestHandler.INBOUND_INTERNAL_PREFIX (package-private);
        // InboundPanel keys ownership on it, so this literal is the routing contract.
        links.record(new SubmissionLink("tok-dispatch-1", "inbound:tok-dispatch-1", "w1", null,
                "TICKET-DISPATCH", "Partner X", null, true, null, Instant.now()));

        JsonNode view = post("/canvas/initialize",
                "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                        + "\"ticket\":{\"id\":\"TICKET-DISPATCH\"}}");
        assertTrue(allText(view).contains("Partner collaboration"),
                "precondition: initialize renders the inbound case view");

        JsonNode clicked = post("/canvas/submit", click("TICKET-DISPATCH", "inbound_accept", view));
        String text = allText(clicked);
        assertTrue(text.contains("Accept this collaboration"),
                "the Accept click must reach the panel's form, not the expired-session home: " + text);
        assertTrue(!text.contains("panel session expired"),
                "an inbound click needs no outbound flow and must not be treated as expired");
    }

    @Test
    void anInboundComponentOnAnUnroutableTicketStillFallsThroughToHome() throws Exception {
        JsonNode view = post("/canvas/initialize",
                "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                        + "\"ticket\":{\"id\":\"TICKET-NEVER-LINKED\"}}");
        // Not an inbound case, so initialize rendered the outbound home; a stale
        // inbound click against it must keep the pre-#33 fallthrough: panel declines,
        // no flow, expired-session home.
        JsonNode clicked = post("/canvas/submit",
                "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                        + "\"ticket\":{\"id\":\"TICKET-NEVER-LINKED\"},"
                        + "\"component_id\":\"inbound_accept\","
                        + "\"current_canvas\":{\"stored_data\":{}},"
                        + "\"input_values\":{}}");
        assertTrue(allText(clicked).contains("panel session expired"),
                "an inbound component on a ticket the panel does not own falls back to the outbound flow path");
        assertTrue(view.at("/canvas/stored_data/flow_id").isTextual(),
                "precondition check: the unlinked ticket got the outbound home with a flow pointer");
    }
}
