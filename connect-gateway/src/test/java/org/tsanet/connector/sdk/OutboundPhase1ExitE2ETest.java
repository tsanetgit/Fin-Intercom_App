package org.tsanet.connector.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.tsanet.connector.ConnectorApplication;
import org.tsanet.connector.intercom.AnnotationState;
import org.tsanet.connector.intercom.SubmissionLink;
import org.tsanet.connector.intercom.SubmissionLinkTable;

/**
 * THE PHASE-1 EXIT (tsanetgit/Member-Integrations#50): an outbound case submitted
 * from the Fin/Intercom side to a BETA partner end-to-end, repeatably. Real dev
 * workspace (self-provisioned "TSANet Collaboration" ticket + synthetic contact),
 * real signed canvas HTTP against the booted connector (mode=sdk + intercom
 * mode=rest), real BETA case (sdk1 -> sdk2, test-flagged), and the annotation read
 * back from Intercom — including the named datetime-format assertion for "Respond
 * By" that D5 promises.
 *
 * <p>Env-gated on BOTH credential sets; CI (secretless) skips it green. Each run
 * creates one test-flagged BETA case and one dev-workspace ticket (title-tagged
 * "E2E slice-3", accumulating deliberately — the dev workspace exists for this).
 */
@EnabledIfEnvironmentVariable(named = "TSANET_SDK1_API_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "INTERCOM_DEV_ACCESS_TOKEN", matches = ".+")
@SpringBootTest(classes = ConnectorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboundPhase1ExitE2ETest {

    private static final String INTERCOM_BASE = "https://api.intercom.io";
    private static final String SYNTHETIC_CONTACT_EMAIL = "tsanet-partner@example.invalid";

    @DynamicPropertySource
    static void wireRealGateways(DynamicPropertyRegistry registry) {
        // connector.tsanet.mode stays the operator switch (stands the Fake down);
        // the engine's own config binds gateway.tsanet.* since the Fin swap (#42).
        registry.add("connector.tsanet.mode", () -> "sdk");
        registry.add("gateway.tsanet.sdk.api-base-url", () -> System.getenv("TSANET_SDK1_API_URL"));
        registry.add("gateway.tsanet.sdk.auth-mode", () -> "password");
        registry.add("gateway.tsanet.sdk.principal", () -> System.getenv("TSANET_SDK1_API_USER"));
        registry.add("gateway.tsanet.sdk.secret", () -> System.getenv("TSANET_SDK1_API_PASSWORD"));
        registry.add("gateway.tsanet.sdk.sqlite-path",
                () -> "target/e2e-sdk1-" + UUID.randomUUID() + ".db");
        registry.add("connector.intercom.mode", () -> "rest");
        registry.add("connector.intercom.access-token", () -> System.getenv("INTERCOM_DEV_ACCESS_TOKEN"));
        registry.add("connector.intercom-client-secret", () -> System.getenv("INTERCOM_DEV_CLIENT_SECRET"));
        registry.add("connector.tsanet-webhook-secret", () -> "e2e-unused");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SubmissionLinkTable links;

    private final ObjectMapper json = new ObjectMapper();
    private final RestTemplate http = new RestTemplate();

    // ---- Intercom helper (dev workspace provisioning + verification) ----

    private JsonNode intercom(HttpMethod method, String path, String body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(System.getenv("INTERCOM_DEV_ACCESS_TOKEN"));
        headers.set("Accept", "application/json");
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        String response = http.exchange(INTERCOM_BASE + path, method,
                new HttpEntity<>(body, headers), String.class).getBody();
        return json.readTree(response == null ? "{}" : response);
    }

    private String ticketTypeId() throws Exception {
        JsonNode types = intercom(HttpMethod.GET, "/ticket_types", null);
        for (Iterator<JsonNode> it = types.path("data").values().iterator(); it.hasNext(); ) {
            JsonNode type = it.next();
            if ("TSANet Collaboration".equals(type.path("name").asText())) {
                return type.path("id").asText();
            }
        }
        throw new IllegalStateException("dev workspace has no 'TSANet Collaboration' ticket type;"
                + " re-run the spike provisioning");
    }

    private String contactId() throws Exception {
        JsonNode found = intercom(HttpMethod.POST, "/contacts/search",
                "{\"query\":{\"field\":\"email\",\"operator\":\"=\",\"value\":\""
                        + SYNTHETIC_CONTACT_EMAIL + "\"}}");
        JsonNode first = found.path("data").path(0);
        if (!first.isMissingNode() && first.hasNonNull("id")) {
            return first.path("id").asText();
        }
        JsonNode created = intercom(HttpMethod.POST, "/contacts",
                "{\"role\":\"user\",\"email\":\"" + SYNTHETIC_CONTACT_EMAIL + "\"}");
        return created.path("id").asText();
    }

    private String createTicket(String typeId, String contactId) throws Exception {
        JsonNode ticket = intercom(HttpMethod.POST, "/tickets",
                "{\"ticket_type_id\":\"" + typeId + "\","
                        + "\"contacts\":[{\"id\":\"" + contactId + "\"}],"
                        + "\"ticket_attributes\":{"
                        + "\"_default_title_\":\"E2E slice-3 exit run\","
                        + "\"_default_description_\":\"Automated Phase-1 exit; test-flagged BETA case.\"}}");
        String id = ticket.path("id").asText(null);
        assertNotNull(id, "ticket creation must return an id");
        return id;
    }

    // ---- signed canvas driving ----

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(System.getenv("INTERCOM_DEV_CLIENT_SECRET")
                .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(body)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private JsonNode canvas(String path, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Body-Signature", sign(bytes));
        String response = http.exchange("http://127.0.0.1:" + port + path, HttpMethod.POST,
                new HttpEntity<>(bytes, headers), String.class).getBody();
        return json.readTree(response == null ? "{}" : response);
    }

    private String canvasBody(String ticketId, String flowId, String componentId, String inputValuesJson) {
        String context = "\"workspace_id\":\"" + System.getenv().getOrDefault("INTERCOM_DEV_WORKSPACE", "nxdyoagx")
                + "\",\"admin\":{\"name\":\"E2E Driver\",\"email\":\""
                + System.getenv("TSANET_SDK1_API_USER") + "\"},"
                + "\"conversation\":{\"id\":" + ticketId + "},"
                + "\"ticket\":{\"id\":\"" + ticketId + "\"}";
        if (flowId == null) {
            return "{" + context + "}";
        }
        return "{" + context + ",\"component_id\":\"" + componentId + "\","
                + "\"current_canvas\":{\"stored_data\":{\"flow_id\":\"" + flowId + "\"}},"
                + "\"input_values\":" + inputValuesJson + "}";
    }

    private static String componentIdStartingWith(JsonNode canvas, String prefix) {
        for (Iterator<JsonNode> it = canvas.at("/canvas/content/components").values().iterator(); it.hasNext(); ) {
            JsonNode component = it.next();
            if (component.has("id") && component.get("id").asText().startsWith(prefix)) {
                return component.get("id").asText();
            }
        }
        return null;
    }

    /**
     * Fills every required partner-form field the way an agent would: the partner's
     * live form is not ours to assume, so values derive from the rendered components.
     */
    private static String requiredFieldValues(JsonNode formCanvas) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<JsonNode> it = formCanvas.at("/canvas/content/components").values().iterator(); it.hasNext(); ) {
            JsonNode component = it.next();
            String id = component.path("id").asText("");
            String label = component.path("label").asText("");
            if (!id.startsWith("f_") || !label.contains("(required)")) {
                continue;
            }
            String value;
            if ("dropdown".equals(component.path("type").asText())) {
                value = component.at("/options/0/id").asText("");
            } else if (label.toLowerCase().contains("email")) {
                value = "e2e-customer@example.com";
            } else if (label.toLowerCase().contains("phone")) {
                value = "+1 555 0100";
            } else {
                value = "E2E value";
            }
            sb.append(",\"").append(id).append("\":\"").append(value).append("\"");
        }
        return sb.toString();
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

    @Test
    void outboundCaseFromFinSideToBetaPartnerEndToEnd() throws Exception {
        String ticketId = createTicket(ticketTypeId(), contactId());

        JsonNode home = canvas("/canvas/initialize", canvasBody(ticketId, null, null, null));
        String flow = home.at("/canvas/stored_data/flow_id").asText();
        assertTrue(!flow.isBlank());

        String partnerQuery = System.getenv().getOrDefault("TSANET_SDK2_COMPANY_NAME", "sdk2");
        JsonNode results = canvas("/canvas/submit", canvasBody(ticketId, flow, "search",
                "{\"q\":\"" + partnerQuery + "\"}"));
        String selectKey = componentIdStartingWith(results, "select:");
        assertNotNull(selectKey, "BETA partner search must yield a selectable hit for " + partnerQuery);

        JsonNode form = canvas("/canvas/submit", canvasBody(ticketId, flow, selectKey, "{}"));
        JsonNode confirmed = canvas("/canvas/submit", canvasBody(ticketId, flow, "submit_case",
                "{\"summary\":\"E2E slice-3 exit run\",\"description\":\"Automated Phase-1 exit"
                        + " submission; test-flagged.\"" + requiredFieldValues(form) + "}"));
        assertTrue(allText(confirmed).contains("Case submitted"),
                "the canvas must confirm the BETA submission: " + allText(confirmed));

        SubmissionLink link = links.byInternalCaseNumber(flow).orElseThrow();
        assertEquals(ticketId, link.ticketId());
        assertTrue(link.testCase(), "the E2E must never create a non-test-flagged partner case");
        assertNotNull(link.token());

        long deadline = System.currentTimeMillis() + 20_000;
        while (links.annotationState(link.token()) == AnnotationState.NONE
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
        }
        assertEquals(AnnotationState.FULL, links.annotationState(link.token()),
                "annotation must drain to FULL against the real dev workspace");

        JsonNode ticket = intercom(HttpMethod.GET, "/tickets/" + ticketId, null);
        JsonNode attrs = ticket.path("ticket_attributes");
        assertEquals(link.token(), attrs.path("TSANet Token").asText(),
                "D3 read-back: the token must be on the ticket");
        assertEquals(link.partnerDisplay(), attrs.path("Partner").asText());
        if (link.respondBy() != null) {
            // THE named datetime-format assertion D5 promises: the VALUE round-trips
            // as epoch seconds, not merely some value being present.
            assertEquals(java.time.OffsetDateTime.parse(link.respondBy()).toEpochSecond(),
                    attrs.path("Respond By").asLong(),
                    "Respond By must round-trip as epoch seconds when the case carries one");
        }
    }
}
