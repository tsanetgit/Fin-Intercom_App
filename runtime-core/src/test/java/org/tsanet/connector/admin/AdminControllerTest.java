package org.tsanet.connector.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Payload shapes and redaction of the v1 admin read surface (docs/decisions.md D20).
 * The secret VALUES set here are distinctive strings asserted absent from every
 * response body — the wrong-but-well-formed output for a redaction rule is a response
 * that carries the secret in some field the assertion did not think to name, so the
 * check runs against the whole body, not a JSON path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.admin-api-secret=adm-secret-value-1a2b3c",
        "connector.intercom-client-secret=ics-secret-value-4d5e6f",
        "connector.tsanet-webhook-secret=twh-secret-value-7g8h9i",
        "connector.public-base-url=https://adapter.example.test",
})
class AdminControllerTest {

    private static final String ADMIN_SECRET = "adm-secret-value-1a2b3c";

    @Autowired
    private MockMvc mvc;

    private static MockHttpServletRequestBuilder signedGet(String target) throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        StringBuilder bodyHex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(new byte[0])) {
            bodyHex.append(String.format("%02x", b));
        }
        String canonical = ts + "\n" + "GET" + "\n" + target + "\n" + bodyHex;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(ADMIN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sig = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))) {
            sig.append(String.format("%02x", b));
        }
        return get(target).header("X-Admin-Timestamp", ts).header("X-Admin-Signature", sig.toString());
    }

    @Test
    void infoAnswersIdentity() throws Exception {
        mvc.perform(signedGet("/admin/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("tsanet-intercom-connector"))
                .andExpect(jsonPath("$.platform").value("intercom"))
                .andExpect(jsonPath("$.deploymentShape").value("tsanet-hosted"))
                .andExpect(jsonPath("$.adminContractVersion").value(1))
                .andExpect(jsonPath("$.version").isString());
    }

    @Test
    void statusReportsTheDetailsCore() throws Exception {
        mvc.perform(signedGet("/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.startedAt").isString())
                .andExpect(jsonPath("$.details.tsanetMode").value("fake"))
                .andExpect(jsonPath("$.details.intercomMode").value("fake"))
                .andExpect(jsonPath("$.details.linkCount").isNumber())
                .andExpect(jsonPath("$.details.queueDepth").isNumber())
                .andExpect(jsonPath("$.details.reconcile.enabled").value(true))
                .andExpect(jsonPath("$.details.reconcile.consecutiveFailures").isNumber())
                .andExpect(jsonPath("$.details.reconcile.consecutiveHeld").isNumber());
    }

    @Test
    void urlsUseTheConfiguredBaseAndNameEveryPasteTarget() throws Exception {
        mvc.perform(signedGet("/admin/urls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("https://adapter.example.test"))
                .andExpect(jsonPath("$.baseUrlSource").value("configured"))
                .andExpect(jsonPath("$.urls[?(@.name=='canvas_initialize')].url")
                        .value("https://adapter.example.test/canvas/initialize"))
                .andExpect(jsonPath("$.urls[?(@.name=='canvas_submit')].url")
                        .value("https://adapter.example.test/canvas/submit"))
                .andExpect(jsonPath("$.urls[?(@.name=='intercom_webhook')].url")
                        .value("https://adapter.example.test/webhooks/intercom"))
                .andExpect(jsonPath("$.urls[?(@.name=='tsanet_webhook')].url")
                        .value("https://adapter.example.test/webhooks/tsanet"))
                .andExpect(jsonPath("$.urls[*].pastedInto").isNotEmpty());
    }

    @Test
    void configMarksSecretsPresentWithoutEverPrintingThem() throws Exception {
        String body = mvc.perform(signedGet("/admin/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties['connector.links.store']").value("memory"))
                .andExpect(jsonPath("$.secrets['connector.admin-api-secret'].present").value(true))
                .andExpect(jsonPath("$.secrets['connector.intercom-client-secret'].present").value(true))
                .andExpect(jsonPath("$.secrets['connector.tsanet-webhook-secret'].present").value(true))
                .andExpect(jsonPath("$.secrets['connector.intercom.access-token'].present").value(false))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("adm-secret-value-1a2b3c"), "admin secret leaked into /admin/config");
        assertFalse(body.contains("ics-secret-value-4d5e6f"), "intercom secret leaked into /admin/config");
        assertFalse(body.contains("twh-secret-value-7g8h9i"), "webhook secret leaked into /admin/config");
    }

    @Test
    void logsTailReturnsRecentLinesAndAuditTailOnlyTheConvention() throws Exception {
        LoggerFactory.getLogger("admin.ring.probe").info("plain ring probe line");
        LoggerFactory.getLogger("admin.ring.probe").info("tsanet_audit event=admin_ring_probe");

        String logs = mvc.perform(signedGet("/admin/logs/tail?n=50"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(logs.contains("plain ring probe line"), "general ring missed a root-logger line");
        assertTrue(logs.contains("tsanet_audit event=admin_ring_probe"), "general ring missed an audit line");

        String audit = mvc.perform(signedGet("/admin/audit/tail?n=50"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(audit.contains("tsanet_audit event=admin_ring_probe"), "audit ring missed the convention");
        assertFalse(audit.contains("plain ring probe line"), "audit ring must carry only tsanet_audit lines");
    }

    @Test
    void invalidNIsRejectedNotClamped() throws Exception {
        mvc.perform(signedGet("/admin/logs/tail?n=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_n"));
        mvc.perform(signedGet("/admin/logs/tail?n=999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_n"));
        mvc.perform(signedGet("/admin/audit/tail?n=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_n"));
    }
}
