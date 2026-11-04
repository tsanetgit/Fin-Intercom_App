package org.tsanet.connector.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tsanet.connector.intercom.CaseState;
import org.tsanet.connector.intercom.InMemorySubmissionLinkTable;
import org.tsanet.connector.intercom.SubmissionLink;
import org.tsanet.connector.intercom.SubmissionLinkTable;
import org.tsanet.connector.tsanet.FakeTsanetGateway;

/** What an agent can actually do with a partner's case, and what they cannot. */
class InboundPanelTest {

    private final FakeTsanetGateway tsanet = new FakeTsanetGateway(new MockEnvironment());
    private final SubmissionLinkTable links = new InMemorySubmissionLinkTable();
    private final InboundPanel panel = new InboundPanel(links, tsanet);

    private static final String TOKEN = "inbound-77";
    /** The API handle: what the link anchors on and what canvas payloads carry. */
    private static final String TICKET = "100000000000001";
    /** The agent-facing number: the ONLY thing that may be quoted to a partner.
     * Deliberately unlike TICKET — an earlier version of this test used one value for
     * both and so asserted the defect it was meant to catch (#17 QA round). */
    private static final String TICKET_NUMBER = "100000001";

    @BeforeEach
    void anchorAnInboundCase() {
        links.record(new SubmissionLink(TOKEN, InboundRequestHandler.INBOUND_INTERNAL_PREFIX + TOKEN,
                "w1", null, null, "NetApp", "2026-08-08T12:00:00Z", true, null, Instant.now()));
        links.anchor(TOKEN, TICKET);
        links.caseNumber(TOKEN, TICKET_NUMBER);
    }

    private static Map<String, Object> onTicket(String ticketId) {
        return Map.of("workspace_id", "w1",
                "ticket", Map.of("id", ticketId),
                "admin", Map.of("name", "Dana Agent", "email", "dana@member.example"));
    }

    private static String text(Map<String, Object> canvas) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components =
                (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) canvas
                        .get("canvas")).get("content")).get("components");
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> c : components) {
            sb.append(c.getOrDefault("text", "")).append('|')
                    .append(c.getOrDefault("label", "")).append('|')
                    .append(c.getOrDefault("id", "")).append('|')
                    .append(c.getOrDefault("value", "")).append('\n');
        }
        return sb.toString();
    }

    @Test
    void anInboundTicketShowsTheCaseAndTheThreeActions() {
        String rendered = text(panel.render(onTicket(TICKET)).orElseThrow());
        assertTrue(rendered.contains("NetApp"));
        assertTrue(rendered.contains("awaiting your response"));
        assertTrue(rendered.contains(InboundPanel.ACCEPT));
        assertTrue(rendered.contains(InboundPanel.REJECT));
        assertTrue(rendered.contains(InboundPanel.INFO));
    }

    @Test
    void anUnrelatedTicketIsNotOurs() {
        // An agent's own support ticket must keep the outbound partner search.
        assertTrue(panel.render(onTicket("some-other-ticket")).isEmpty());
        assertTrue(panel.render(Map.of("workspace_id", "w1")).isEmpty(), "no ticket, no panel");
    }

    @Test
    void theEngineerDefaultsToTheActingTeammateButStaysEditable() {
        String form = text(panel.submit(onTicket(TICKET), InboundPanel.ACCEPT, Map.of()).orElseThrow());
        assertTrue(form.contains("Dana Agent"), "prefilled from the acting teammate");
        assertTrue(form.contains("dana@member.example"));

        String edited = text(panel.submit(onTicket(TICKET), InboundPanel.ACCEPT,
                Map.of(InboundPanel.FIELD_EMAIL, "dana@registered.example")).orElseThrow());
        assertTrue(edited.contains("dana@registered.example"), "a typed value must win over the default");
    }

    @Test
    void acceptingSendsTheCaseNumberAnAgentCanActuallySearch() {
        panel.submit(onTicket(TICKET), InboundPanel.SUBMIT_ACCEPT,
                Map.of(InboundPanel.FIELD_TEXT, "Assigning a storage engineer today"));

        FakeTsanetGateway.Responded sent = tsanet.responses().get(0);
        assertEquals("approve", sent.action());
        assertEquals(TICKET_NUMBER, sent.caseNumber(),
                "the partner quotes this back, so it must be the SEARCHABLE number, never the"
                        + " API handle the link anchors on");
        assertEquals("dana@member.example", sent.engineer().email());
        assertEquals(CaseState.ACCEPTED, links.caseState(TOKEN));
        assertEquals(TICKET_NUMBER, links.byToken(TOKEN).orElseThrow().caseNumber(),
                "what we quoted is recorded, not re-derived later");
    }

    @Test
    void acceptIsRefusedRatherThanQuotingTheApiHandle() {
        // If the searchable number was never captured, falling back to the anchor would
        // quote the partner a reference no agent can find — which fails silently, on a
        // phone call, days later. Refusing is recoverable; quoting the handle is not.
        links.record(new SubmissionLink("inbound-nonum",
                InboundRequestHandler.INBOUND_INTERNAL_PREFIX + "inbound-nonum", "w1", null, null,
                "NetApp", null, true, null, Instant.now()));
        links.anchor("inbound-nonum", "api-handle-only");

        String rendered = text(panel.submit(onTicket("api-handle-only"), InboundPanel.SUBMIT_ACCEPT,
                Map.of(InboundPanel.FIELD_TEXT, "on it")).orElseThrow());

        assertTrue(rendered.contains("searchable case number"));
        assertTrue(tsanet.responses().isEmpty(), "nothing may reach the partner");
        assertEquals(CaseState.PENDING, links.caseState("inbound-nonum"));
    }

    @Test
    void answeredCasesOfferNoSecondResponse() {
        panel.submit(onTicket(TICKET), InboundPanel.SUBMIT_REJECT,
                Map.of(InboundPanel.FIELD_TEXT, "Not our product"));
        assertEquals(CaseState.REJECTED, links.caseState(TOKEN));

        String rendered = text(panel.render(onTicket(TICKET)).orElseThrow());
        assertTrue(rendered.contains("declined"));
        assertFalse(rendered.contains(InboundPanel.ACCEPT), "an answered case must not offer accept");
    }

    @Test
    void aStalePanelCannotAnswerACaseThatWasAlreadyAnswered() {
        // Two panels open on one ticket: the second must not reach the partner again.
        panel.submit(onTicket(TICKET), InboundPanel.SUBMIT_ACCEPT,
                Map.of(InboundPanel.FIELD_TEXT, "on it"));
        String second = text(panel.submit(onTicket(TICKET), InboundPanel.SUBMIT_REJECT,
                Map.of(InboundPanel.FIELD_TEXT, "actually no")).orElseThrow());

        assertEquals(1, tsanet.responses().size(), "the partner must be answered exactly once");
        assertTrue(second.contains("already answered"));
        assertEquals(CaseState.ACCEPTED, links.caseState(TOKEN));
    }

    @Test
    void missingRequiredFieldsReRenderTheFormWithWhatWasTyped() {
        String again = text(panel.submit(onTicket(TICKET), InboundPanel.SUBMIT_ACCEPT,
                Map.of(InboundPanel.FIELD_TEXT, "")).orElseThrow());
        assertTrue(again.contains("Missing required"));
        assertTrue(tsanet.responses().isEmpty(), "nothing reaches the partner");
        assertEquals(CaseState.PENDING, links.caseState(TOKEN), "and the state is untouched");
    }

    @Test
    void anOffDomainEngineerEmailIsRenderedAndTheCaseStaysAnswerable() {
        // The email-domain rule: definitive, unreadable from a status code, and fixable
        // only if the agent can see it and edit the field.
        String form = text(panel.submit(onTicket(TICKET), InboundPanel.SUBMIT_ACCEPT,
                Map.of(InboundPanel.FIELD_EMAIL, "contractor@offdomain.test",
                        InboundPanel.FIELD_TEXT, "Assigning an engineer")).orElseThrow());

        assertTrue(form.contains("registered domain"), "the API's reason must reach the agent");
        assertTrue(form.contains("contractor@offdomain.test"), "and the form keeps what was typed");
        assertTrue(form.contains("Assigning an engineer"));
        assertEquals(CaseState.PENDING, links.caseState(TOKEN),
                "a definitive rejection gives the state back so the agent can retry");
    }

    @Test
    void anAmbiguousResponseKeepsTheStateMovedAndSaysSo() {
        // The partner may already have it; re-offering the action is what would answer
        // twice, so the state stays moved and the panel says the outcome is unknown.
        links.record(new SubmissionLink("timeout-9",
                InboundRequestHandler.INBOUND_INTERNAL_PREFIX + "timeout-9", "w1", null, null,
                "NetApp", null, true, null, Instant.now()));
        links.anchor("timeout-9", "ticket-timeout");
        links.caseNumber("timeout-9", "9900777");

        String rendered = text(panel.submit(onTicket("ticket-timeout"), InboundPanel.SUBMIT_INFO,
                Map.of(InboundPanel.FIELD_TEXT, "which array?")).orElseThrow());

        assertTrue(rendered.contains("may or may not"));
        assertEquals(CaseState.INFO_REQUESTED, links.caseState("timeout-9"));
    }

    @Test
    void aPartnerClosedCaseSaysSoAndOffersNothing() {
        // #27 shipped the branch, #29 the coverage: closed-by-partner is rendered
        // distinct from "answered" because the agent did not do this and would
        // otherwise wonder why their options vanished.
        assertTrue(links.caseState(TOKEN, CaseState.PENDING, CaseState.CLOSED));
        String rendered = text(panel.render(onTicket(TICKET)).orElseThrow());
        assertTrue(rendered.contains("The partner closed this case."));
        assertTrue(rendered.contains("closed by the partner"));
        assertFalse(rendered.contains(InboundPanel.ACCEPT), "no action buttons on a closed case");
        assertFalse(rendered.contains(InboundPanel.REJECT));
        assertFalse(rendered.contains(InboundPanel.INFO));
        assertFalse(rendered.contains("has been answered"),
                "closed must not render as the answered branch — the agent did not do this");
    }
}
