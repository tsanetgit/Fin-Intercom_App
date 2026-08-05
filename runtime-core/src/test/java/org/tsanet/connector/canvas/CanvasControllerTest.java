package org.tsanet.connector.canvas;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
})
class CanvasControllerTest {

    @Autowired
    private MockMvc mvc;

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-intercom-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(body)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void initializeReturnsCanvasComponents() throws Exception {
        byte[] body = "{\"workspace_id\":\"w1\",\"conversation\":{\"id\":1}}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/canvas/initialize").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sign(body)).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canvas.content.components").isNotEmpty())
                .andExpect(jsonPath("$.canvas.content.components[0].text").value("TSANet Connect"));
    }

    @Test
    void detectsTicketSurfaceFromTopLevelTicketKey() throws Exception {
        byte[] ticket = "{\"workspace_id\":\"w1\",\"ticket\":{\"id\":9}}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/canvas/initialize").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sign(ticket)).content(ticket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canvas.content.components[1].text").value(containsString("on a ticket")));

        byte[] conversation = "{\"workspace_id\":\"w1\"}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/canvas/initialize").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sign(conversation)).content(conversation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canvas.content.components[1].text").value(containsString("on a conversation")))
                .andExpect(jsonPath("$.canvas.content.components[1].text").value(not(containsString("on a ticket"))));
    }

    @Test
    void submitEchoesComponentId() throws Exception {
        byte[] body = "{\"workspace_id\":\"w1\",\"component_id\":\"ping\"}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/canvas/submit").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sign(body)).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canvas.content.components[1].text").value(containsString("Received: ping")));
    }
}
