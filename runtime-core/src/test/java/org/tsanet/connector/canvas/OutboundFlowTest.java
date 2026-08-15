package org.tsanet.connector.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
import org.tsanet.connector.tsanet.FakeTsanetGateway;

/**
 * Walks the whole outbound flow, signed, against the fake gateway. Every step asserts
 * the flow pointer survives the transition (the advisor's echo-forward warning: losing
 * stored_data between canvases is the classic Canvas Kit bug).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
})
class OutboundFlowTest {

    @Autowired
    private MockMvc mvc;

    /** The fake gateway bean, read directly to observe what the flow actually submitted. */
    @Autowired
    private FakeTsanetGateway gateway;

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

    private JsonNode initialize() throws Exception {
        return post("/canvas/initialize", "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"}}");
    }

    private JsonNode submit(String flowId, String componentId, String inputValuesJson) throws Exception {
        return post("/canvas/submit", "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                + "\"component_id\":\"" + componentId + "\","
                + "\"current_canvas\":{\"stored_data\":{\"flow_id\":\"" + flowId + "\"}},"
                + "\"input_values\":" + inputValuesJson + "}");
    }

    private static String flowId(JsonNode canvas) {
        return canvas.at("/canvas/stored_data/flow_id").asText();
    }

    private static JsonNode component(JsonNode canvas, String id) {
        for (Iterator<JsonNode> it = canvas.at("/canvas/content/components").elements(); it.hasNext(); ) {
            JsonNode component = it.next();
            if (component.has("id") && id.equals(component.get("id").asText())) {
                return component;
            }
        }
        return null;
    }

    private static String componentValue(JsonNode canvas, String id) {
        JsonNode component = component(canvas, id);
        if (component == null) {
            return null;
        }
        return component.has("value") ? component.get("value").asText() : null;
    }

    private static String allText(JsonNode canvas) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<JsonNode> it = canvas.at("/canvas/content/components").elements(); it.hasNext(); ) {
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

    /**
     * #7: select-shaped fields render as a dropdown, because a single-select laid its
     * options out in one horizontal row that neither wrapped nor scaled — option-heavy
     * partner forms clipped every label to a few characters at the default panel width.
     *
     * <p>Asserts the option TEXT survives whole, not just the option count: the canvas
     * JSON always carried the full labels (the truncation was Intercom's renderer, never
     * data loss), so a test that only counted options would have passed throughout the
     * defect and would pass again if the component silently reverted.
     */
    @Test
    void selectShapedFieldsRenderAsDropdownWithEveryOptionLabelIntact() throws Exception {
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        JsonNode form = submit(flow, "select:1042|512", "{}");

        JsonNode severity = component(form, "f_89");
        assertNotNull(severity, "the partner form's SELECT field must render");
        assertEquals("dropdown", severity.path("type").asText(),
                "single-select truncates option labels at the default panel width (#7)");

        List<String> optionTexts = new ArrayList<>();
        List<String> optionIds = new ArrayList<>();
        for (Iterator<JsonNode> it = severity.path("options").elements(); it.hasNext(); ) {
            JsonNode option = it.next();
            optionTexts.add(option.path("text").asText());
            optionIds.add(option.path("id").asText());
        }
        assertEquals(List.of("High", "Medium", "Low"), optionTexts);
        // The id is what comes back in input_values, so the submit contract is unchanged.
        assertEquals(List.of("High", "Medium", "Low"), optionIds);
    }

    @Test
    void happyPathSubmitsAndConfirms() throws Exception {
        JsonNode home = initialize();
        String flow = flowId(home);
        assertNotNull(flow);

        JsonNode results = submit(flow, "search", "{\"q\":\"netapp\"}");
        assertEquals(flow, flowId(results), "flow pointer must survive search");
        assertTrue(allText(results).contains("NetApp | Storage Division"));

        JsonNode form = submit(flow, "select:1042|512", "{}");
        assertEquals(flow, flowId(form), "flow pointer must survive selection");
        String formText = allText(form);
        assertTrue(formText.contains("Serial Number"));
        assertTrue(formText.contains("Partner note:"));
        assertTrue(formText.contains("Escalation: Call the duty manager for P1s."),
                "partner-supplied escalation guidance must render, not be fetched and dropped");

        JsonNode invalid = submit(flow, "submit_case", "{\"summary\":\"\",\"description\":\"broken volume\"}");
        assertTrue(allText(invalid).contains("Missing required:"));
        assertTrue(allText(invalid).contains("Serial Number"));

        JsonNode confirmed = submit(flow, "submit_case",
                "{\"summary\":\"Latency spike\",\"description\":\"volumes degraded\",\"f_88\":\"SN-1\",\"f_89\":\"High\"}");
        String confirmedText = allText(confirmed);
        assertTrue(confirmedText.contains("Case submitted"));
        assertTrue(confirmedText.contains("Token: TSA-"));
        assertEquals(flow, flowId(confirmed), "flow pointer must survive submission");
    }

    @Test
    void submitTimeoutReconcilesWithoutDuplicate() throws Exception {
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        submit(flow, "select:1042|512", "{}");

        JsonNode pending = submit(flow, "submit_case",
                "{\"summary\":\"please timeout now\",\"description\":\"desc\",\"f_88\":\"SN-2\",\"f_89\":\"Low\"}");
        assertTrue(allText(pending).contains("Submission status unknown"));

        // A stale tab re-clicking submit while the outcome is unresolved must be forced
        // onto the reconcile path, never into a second gateway.submit.
        JsonNode guarded = submit(flow, "submit_case",
                "{\"summary\":\"please timeout now\",\"description\":\"desc\",\"f_88\":\"SN-2\",\"f_89\":\"Low\"}");
        assertTrue(allText(guarded).contains("Submission status unknown"));

        JsonNode reconciled = submit(flow, "check_status", "{}");
        String text = allText(reconciled);
        assertTrue(text.contains("Case submitted"), "lookup must find the case the timeout hid");
        assertTrue(text.contains("Token: TSA-"));
    }

    @Test
    void duplicateSubmitRendersTheSameTokenInsteadOfASecondCase() throws Exception {
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        submit(flow, "select:1042|512", "{}");
        String first = allText(submit(flow, "submit_case",
                "{\"summary\":\"Latency spike\",\"description\":\"desc\",\"f_88\":\"SN-3\",\"f_89\":\"High\"}"));
        assertTrue(first.contains("Case submitted"));
        String token1 = first.replaceAll("(?s).*Token: (TSA-[0-9a-f]+).*", "$1");

        // Same flow, second tab, submit clicked again: the fake mints a fresh token per
        // gateway.submit, so an identical token proves no second submission happened.
        String second = allText(submit(flow, "submit_case",
                "{\"summary\":\"Latency spike\",\"description\":\"desc\",\"f_88\":\"SN-3\",\"f_89\":\"High\"}"));
        assertTrue(second.contains("Case submitted"));
        String token2 = second.replaceAll("(?s).*Token: (TSA-[0-9a-f]+).*", "$1");
        assertEquals(token1, token2, "re-entry must render the landed outcome, not create a duplicate case");
    }

    @Test
    void unsupportedFormFailsLoudly() throws Exception {
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"unsupported\"}");
        JsonNode blocked = submit(flow, "select:2100|777", "{}");
        String text = allText(blocked);
        assertTrue(text.contains("doesn't support yet"));
        assertTrue(text.contains("FILES"));
    }

    @Test
    void searchFailureRendersRetryableHome() throws Exception {
        String flow = flowId(initialize());
        JsonNode failed = submit(flow, "search", "{\"q\":\"failsearch\"}");
        String text = allText(failed);
        assertTrue(text.contains("Partner search failed"));
        assertTrue(text.contains("Partner name or tag"), "home search input must be back for retry");
    }

    @Test
    void backHomeMintsAFreshFlowId() throws Exception {
        String flow = flowId(initialize());
        JsonNode fresh = submit(flow, "back_home", "{}");
        assertNotNull(flowId(fresh));
        assertTrue(!flow.equals(flowId(fresh)),
                "internalCaseNumber == flowId, so a new collaboration needs a new flow id");
    }

    @Test
    void retrySubmitWhileUnresolvedCannotReachTheGateway() throws Exception {
        // QA finding 1: retry_submit used to bypass the pending guard, trusting the
        // client-supplied component id to prove a reconcile happened.
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        submit(flow, "select:1042|512", "{}");
        submit(flow, "submit_case",
                "{\"summary\":\"please timeout now\",\"description\":\"desc\",\"f_88\":\"SN-4\",\"f_89\":\"Low\"}");
        String tokenBefore = gateway.findSubmission(flow).orElseThrow().token();

        JsonNode blocked = submit(flow, "retry_submit", "{}");
        assertTrue(allText(blocked).contains("Submission status unknown"),
                "retry while unresolved must be forced onto the reconcile path");
        assertEquals(tokenBefore, gateway.findSubmission(flow).orElseThrow().token(),
                "the gateway must not have been asked to submit a second time");
    }

    @Test
    void backHomeWhileUnresolvedKeepsTheFlow() throws Exception {
        // QA finding 3: "Start over" from the unresolved canvas minted a fresh flow id,
        // severing the only key that can ever reconcile the possibly-created case.
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        submit(flow, "select:1042|512", "{}");
        submit(flow, "submit_case",
                "{\"summary\":\"please timeout now\",\"description\":\"desc\",\"f_88\":\"SN-5\",\"f_89\":\"Low\"}");

        JsonNode refused = submit(flow, "back_home", "{}");
        assertEquals(flow, flowId(refused), "starting over while unresolved must keep the flow id");
        assertTrue(allText(refused).contains("Submission status unknown"));

        JsonNode reconciled = submit(flow, "check_status", "{}");
        assertTrue(allText(reconciled).contains("Case submitted"));
        JsonNode fresh = submit(flow, "back_home", "{}");
        assertTrue(!flow.equals(flowId(fresh)), "after the outcome is known, starting over is legal");
    }

    @Test
    void reconcileNotFoundReArmsSubmitServerSide() throws Exception {
        // The legal retry path: only a lookup that found nothing (recorded server-side
        // as the UNRESOLVED -> DRAFT transition) re-arms submission for this flow.
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        submit(flow, "select:1042|512", "{}");
        submit(flow, "submit_case",
                "{\"summary\":\"please timeout-lost now\",\"description\":\"desc\",\"f_88\":\"SN-6\",\"f_89\":\"Low\"}");
        assertTrue(gateway.findSubmission(flow).isEmpty(), "the lost request must not have recorded a case");

        JsonNode blocked = submit(flow, "retry_submit", "{}");
        assertTrue(allText(blocked).contains("Submission status unknown"),
                "before any reconcile, retry stays blocked");

        JsonNode notFound = submit(flow, "check_status", "{}");
        String notFoundText = allText(notFound);
        assertTrue(notFoundText.contains("No case found yet"));
        assertTrue(notFoundText.contains("Retry submission"));

        JsonNode confirmed = submit(flow, "submit_case",
                "{\"summary\":\"real summary this time\",\"description\":\"desc\",\"f_88\":\"SN-6\",\"f_89\":\"Low\"}");
        assertTrue(allText(confirmed).contains("Case submitted"),
                "after a reconcile that found nothing, submitting is legal again");
    }

    @Test
    void staleFieldValuesDoNotSurvivePartnerChange() throws Exception {
        // QA finding 5 (probe-confirmed): values typed for partner A's form pre-filled
        // partner B's form and would have ridden into B's submission. Fixture partners
        // share field ids (88/89/90), which is exactly the dangerous case.
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        submit(flow, "select:1042|512", "{}");
        submit(flow, "submit_case", "{\"summary\":\"\",\"description\":\"\",\"f_88\":\"STALE-SERIAL\"}");

        JsonNode secondForm = submit(flow, "select:1042|513", "{}");
        assertEquals(null, componentValue(secondForm, "f_88"),
                "a different partner's form must not pre-fill with the old form's values");

        // And the draft is scoped to the CURRENT form: an injected key that is not on
        // the form must never reach the gateway.
        JsonNode confirmed = submit(flow, "submit_case",
                "{\"summary\":\"s\",\"description\":\"d\",\"f_88\":\"SN-7\",\"f_89\":\"High\",\"f_999\":\"NOT-ON-FORM\"}");
        assertTrue(allText(confirmed).contains("Case submitted"));
        assertEquals(java.util.Set.of("88", "89"), gateway.lastDraft().values().keySet(),
                "only current-form fields may ride into the submission");
    }

    @Test
    void staleSelectionKeyReRendersCurrentResults() throws Exception {
        // Selection is by partner identity, not list index: a key absent from the
        // CURRENT results (stale canvas in another view, or a fabricated id) must
        // re-render the live list, never select a different partner or dead-end.
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        JsonNode rendered = submit(flow, "select:9999|1", "{}");
        assertEquals(flow, flowId(rendered));
        String text = allText(rendered);
        assertTrue(text.contains("no longer in the results"));
        assertTrue(text.contains("NetApp | Storage Division"), "current results must re-render");
    }

    @Test
    void oversizedResultListIsCappedWithARefineNotice() throws Exception {
        String flow = flowId(initialize());
        JsonNode results = submit(flow, "search", "{\"q\":\"capacity\"}");
        long buttons = 0;
        for (Iterator<JsonNode> it = results.at("/canvas/content/components").elements(); it.hasNext(); ) {
            JsonNode component = it.next();
            if (component.has("id") && component.get("id").asText().startsWith("select:")) {
                buttons++;
            }
        }
        assertEquals(12, buttons, "the canvas payload budget caps rendered results");
        assertTrue(allText(results).contains("refine your search"));
    }

    @Test
    void typedFormFieldsRenderByShapeAndHiddenStaysServerSide() throws Exception {
        // Real BETA forms carry EMAIL/PHONE/TEXT/HIDDEN (the Phase-1 exit E2E found a
        // required EMAIL on sdk2's live form that the old STRING/SELECT set rejected
        // into the unsupported-type dead end).
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"typed\"}");
        JsonNode form = submit(flow, "select:2200|555", "{}");
        String text = allText(form);
        assertTrue(text.contains("Customer Email (required)"));
        assertTrue(text.contains("Impact Details (required)"));
        assertTrue(!text.contains("Routing Code"), "HIDDEN fields never render");
        assertEquals("input", componentType(form, "f_61"), "EMAIL renders as a text input");
        assertEquals("textarea", componentType(form, "f_63"), "TEXT renders as a textarea");

        JsonNode missing = submit(flow, "submit_case", "{\"summary\":\"s\",\"description\":\"d\"}");
        String missingText = allText(missing);
        assertTrue(missingText.contains("Customer Email"));
        assertTrue(!missingText.contains("Routing Code"),
                "a required HIDDEN field is the form template's job, not the agent's");

        JsonNode confirmed = submit(flow, "submit_case",
                "{\"summary\":\"s\",\"description\":\"d\",\"f_61\":\"cust@example.com\",\"f_63\":\"impact\"}");
        assertTrue(allText(confirmed).contains("Case submitted"));
        assertEquals(java.util.Set.of("61", "63"), gateway.lastDraft().values().keySet(),
                "hidden fields are never typed so never ride the draft");
    }

    private static String componentType(JsonNode canvas, String id) {
        for (Iterator<JsonNode> it = canvas.at("/canvas/content/components").elements(); it.hasNext(); ) {
            JsonNode component = it.next();
            if (component.has("id") && id.equals(component.get("id").asText())) {
                return component.path("type").asText();
            }
        }
        return null;
    }

    @Test
    void unknownComponentPreservesFlowAndStep() throws Exception {
        // QA finding 6: the dispatch fallthrough used to reset to a fresh flow,
        // silently destroying the agent's typed work on an unexpected component id.
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        submit(flow, "select:1042|512", "{}");

        JsonNode rendered = submit(flow, "bogus_component_zzz", "{}");
        assertEquals(flow, flowId(rendered), "an unknown component must not reset the flow");
        assertTrue(allText(rendered).contains("NetApp | Storage Division"),
                "the current step (the form) must re-render instead of home");
    }

    @Test
    void formPrefillsSubmitterIdentityAndItSurvivesPartnerChange() throws Exception {
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        JsonNode form = submit(flow, "select:1042|512", "{}");
        assertEquals("Agent A", componentValue(form, CanvasController.FIELD_SUBMITTER_NAME),
                "submitter name must prefill from the acting teammate");
        assertEquals("agent@tsanet.org", componentValue(form, CanvasController.FIELD_SUBMITTER_EMAIL),
                "submitter email must prefill from the acting teammate");

        // A typed correction sticks: stored on the failed-validation submit, and a
        // partner change must NOT drop it — who is submitting is form-agnostic
        // (clearFieldValues drops only f_* keys).
        submit(flow, "submit_case", "{\"summary\":\"\",\"description\":\"\","
                + "\"submitter_email\":\"typed@sdk1.com\"}");
        JsonNode otherPartner = submit(flow, "select:1042|513", "{}");
        assertEquals("typed@sdk1.com", componentValue(otherPartner, CanvasController.FIELD_SUBMITTER_EMAIL),
                "a typed submitter email must survive a partner re-select");
    }

    @Test
    void offDomainRejectionKeepsTypedIdentityAndResubmitCarriesTheCorrection() throws Exception {
        // The live scenario this feature exists for: the teammate's login is off the
        // member's registered domain, the API rejects definitively, and the fix is one
        // email correction and a resubmit — on the same flow, no starting over.
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        submit(flow, "select:1042|512", "{}");

        JsonNode rejected = submit(flow, "submit_case",
                "{\"summary\":\"domain-check latency\",\"description\":\"d\",\"f_88\":\"SN-9\",\"f_89\":\"High\","
                        + "\"submitter_name\":\"Agent A\",\"submitter_email\":\"eng@wrong.example\"}");
        String rejectedText = allText(rejected);
        assertTrue(rejectedText.contains("Email must be from the company domain"),
                "the API's domain rule must reach the agent");
        assertTrue(rejectedText.contains("eng@wrong.example"), "the rejected address must be named");
        assertEquals("eng@wrong.example", componentValue(rejected, CanvasController.FIELD_SUBMITTER_EMAIL),
                "the typed value must survive the rejection re-render");
        assertEquals("eng@wrong.example", gateway.lastDraft().submitterEmail(),
                "the typed email must be what the gateway actually saw");

        JsonNode confirmed = submit(flow, "submit_case",
                "{\"summary\":\"domain-check latency\",\"description\":\"d\",\"f_88\":\"SN-9\",\"f_89\":\"High\","
                        + "\"submitter_name\":\"Agent A\",\"submitter_email\":\"eng@sdk1.com\"}");
        assertTrue(allText(confirmed).contains("Case submitted"),
                "a corrected email must be able to resubmit the same flow");
        assertEquals("eng@sdk1.com", gateway.lastDraft().submitterEmail());
        assertEquals(java.util.Set.of("88", "89"), gateway.lastDraft().values().keySet(),
                "submitter identity keys must never leak into partner custom fields");
    }

    @Test
    void untypedSubmitterFallsBackToTheActingTeammate() throws Exception {
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        submit(flow, "select:1042|512", "{}");
        JsonNode confirmed = submit(flow, "submit_case",
                "{\"summary\":\"fallback ok\",\"description\":\"d\",\"f_88\":\"SN-10\",\"f_89\":\"Low\"}");
        assertTrue(allText(confirmed).contains("Case submitted"));
        assertEquals("Agent A", gateway.lastDraft().submitterName());
        assertEquals("agent@tsanet.org", gateway.lastDraft().submitterEmail(),
                "with nothing typed, the teammate default must ride the draft unchanged");
    }

    @Test
    void missingIdentityJoinsTheRequiredListAndNeverReachesTheGateway() throws Exception {
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        submit(flow, "select:1042|512", "{}");

        // Wrong-but-well-formed: a valid submit whose payload carries no admin object
        // and no typed identity. The old code shipped it with an empty submitter.
        var before = gateway.lastDraft();
        JsonNode invalid = post("/canvas/submit", "{\"workspace_id\":\"w1\",\"component_id\":\"submit_case\","
                + "\"current_canvas\":{\"stored_data\":{\"flow_id\":\"" + flow + "\"}},"
                + "\"input_values\":{\"summary\":\"s\",\"description\":\"d\",\"f_88\":\"SN-11\",\"f_89\":\"Low\"}}");
        String text = allText(invalid);
        assertTrue(text.contains("Missing required:"));
        assertTrue(text.contains("Your name"));
        assertTrue(text.contains("Your email"));
        assertTrue(before == gateway.lastDraft(),
                "a submission with no resolvable identity must not reach the gateway");
    }

    @Test
    void typedIdentityRidesRetrySubmitFromStateNotInputs() throws Exception {
        // retry_submit's canvas has no input fields, so the identity must come from the
        // server-side values snapshot — the current request only supplies the teammate
        // default, which here is a DIFFERENT address than the one typed.
        String flow = flowId(initialize());
        submit(flow, "search", "{\"q\":\"netapp\"}");
        submit(flow, "select:1042|512", "{}");
        submit(flow, "submit_case",
                "{\"summary\":\"please timeout-lost now\",\"description\":\"d\",\"f_88\":\"SN-12\",\"f_89\":\"Low\","
                        + "\"submitter_email\":\"eng@sdk1.com\"}");
        assertTrue(gateway.findSubmission(flow).isEmpty(), "the lost request must not have recorded a case");

        submit(flow, "check_status", "{}");
        submit(flow, "retry_submit", "{}");
        assertEquals("eng@sdk1.com", gateway.lastDraft().submitterEmail(),
                "the typed email must ride the retry draft, not the retry payload's teammate default");
        assertEquals("Agent A", gateway.lastDraft().submitterName(),
                "an untyped name still falls back to the acting teammate on the same draft");
    }
}
