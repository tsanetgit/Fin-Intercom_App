package org.tsanet.connector.events;

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

/**
 * Asserts on the response's duplicate flag rather than queue depth: the drain worker
 * consumes queued events immediately, so queue size is racy by design.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
})
class IntercomWebhookDedupeTest {

    @Autowired
    private MockMvc mvc;

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("test-intercom-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        StringBuilder sb = new StringBuilder("sha1=");
        for (byte b : mac.doFinal(body)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void redeliveryOfSameNotificationIdIsFlaggedDuplicate() throws Exception {
        byte[] body = "{\"topic\":\"ticket.created\",\"id\":\"notif-77\"}".getBytes(StandardCharsets.UTF_8);
        String sig = sign(body);
        mvc.perform(post("/webhooks/intercom").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature", sig).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
        mvc.perform(post("/webhooks/intercom").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature", sig).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
    }
}
