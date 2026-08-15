package org.tsanet.connector.intercom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Wire-level probes for the annotation writes, against the exact shapes the spike
 * verified: attributes keyed by NAME, datetime as epoch seconds, D3 read-back, the
 * degraded retry that drops only "Respond By", and the parse-failure path that must
 * report DEGRADED (the wire-format-changed signal), never FULL.
 */
class RestIntercomGatewayTest {

    private static final String BASE = "https://intercom.test";

    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final RestIntercomGateway gateway = new RestIntercomGateway(http, BASE, "admin-1");

    private static SubmissionLink link(String respondBy) {
        return new SubmissionLink("TSA-9", "flow-9", "w1", "conv-9", "ticket-9", "Partner & Co",
                respondBy, true, "agent@tsanet.org", Instant.parse("2026-08-05T20:00:00Z"));
    }

    private void expectReadBack(String tokenOnTicket) {
        server.expect(requestTo(BASE + "/tickets/ticket-9"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"ticket_attributes\":{\"TSANet Token\":\""
                        + tokenOnTicket + "\"}}", MediaType.APPLICATION_JSON));
    }

    private void expectNote() {
        server.expect(requestTo(BASE + "/tickets/ticket-9/reply"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"note-1\"}", MediaType.APPLICATION_JSON));
    }

    @Test
    void fullAnnotationWritesEpochSecondsAndReadsBack() {
        server.expect(requestTo(BASE + "/tickets/ticket-9"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"ticket_attributes\":{\"TSANet Token\":\"TSA-9\","
                        + "\"Partner\":\"Partner & Co\",\"Respond By\":1786033600}}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectReadBack("TSA-9");
        expectNote();
        assertEquals(IntercomGateway.AnnotationOutcome.FULL,
                gateway.annotate(link("2026-08-06T16:26:40Z")));
        server.verify();
    }

    @Test
    void absentRespondByIsFullButUnparseableIsDegraded() {
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectReadBack("TSA-9");
        expectNote();
        assertEquals(IntercomGateway.AnnotationOutcome.FULL, gateway.annotate(link(null)),
                "nothing applicable was dropped: FULL");

        server.reset();
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectReadBack("TSA-9");
        expectNote();
        assertEquals(IntercomGateway.AnnotationOutcome.DEGRADED,
                gateway.annotate(link("tomorrow-ish")),
                "a respond-by the case CARRIES but we cannot parse is the format signal");
    }

    @Test
    void rejectedFullWriteRetriesWithoutRespondByAsDegraded() {
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"ticket_attributes\":{\"TSANet Token\":\"TSA-9\","
                        + "\"Partner\":\"Partner & Co\"}}", true))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectReadBack("TSA-9");
        expectNote();
        assertEquals(IntercomGateway.AnnotationOutcome.DEGRADED,
                gateway.annotate(link("2026-08-06T16:26:40Z")));
        server.verify();
    }

    @Test
    void readBackMismatchThrowsWithTheRenameHint() {
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectReadBack("SOMETHING-ELSE");
        IntercomException e = assertThrows(IntercomException.class,
                () -> gateway.annotate(link(null)));
        assertTrue(e.getMessage().contains("did not read back"),
                "D3: a 200 write that does not read back is not done");
    }

    @Test
    void conversationAnchoredLinkGetsTheNoteOnly() {
        server.expect(requestTo(BASE + "/conversations/conv-9/reply"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"note-2\"}", MediaType.APPLICATION_JSON));
        SubmissionLink conversationOnly = new SubmissionLink("TSA-9", "flow-9", "w1", "conv-9",
                null, "Partner & Co", null, true, "agent@tsanet.org",
                Instant.parse("2026-08-05T20:00:00Z"));
        assertEquals(IntercomGateway.AnnotationOutcome.FULL, gateway.annotate(conversationOnly));
        server.verify();
    }

    @Test
    void noteHtmlEscapesPartnerAuthoredText() {
        String html = RestIntercomGateway.noteHtml(new SubmissionLink("TSA-9", "flow-9", "w1",
                "conv-9", "ticket-9", "<script>alert('x')</script> & Co", null, false,
                "agent@tsanet.org", Instant.parse("2026-08-05T20:00:00Z")));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&amp; Co"));
        assertTrue(!html.contains("<script>"));
    }

    // ---- moveTicketState / verifyStateLanded (#29): the read-back is the method's
    // reason to exist — before these probes, an implementation that skipped
    // verifyStateLanded entirely passed every test in the repo. ----

    @Test
    void stateMoveWritesTheIdAndReadsBackClosed() {
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"ticket_state_id\":\"9619799\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"open\":false,\"ticket_state\":\"Resolved\"}",
                        MediaType.APPLICATION_JSON));
        gateway.moveTicketState("ticket-9", "9619799");
        server.verify();
    }

    @Test
    void stateMoveSilent200ThrowsWithTheWorkspaceHint() {
        // The D3 case the method exists for: Intercom answers 200 while ignoring an
        // unknown or foreign ticket_state_id (the silent-200 trap, T1), so the ticket
        // reads back still open.
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"open\":true,\"ticket_state\":\"Submitted\"}",
                        MediaType.APPLICATION_JSON));
        IntercomException e = assertThrows(IntercomException.class,
                () -> gateway.moveTicketState("ticket-9", "9619799"));
        assertTrue(e.getMessage().contains("resolved-state-id"),
                "the hint must point at the per-workspace configuration, the one thing an"
                        + " operator can actually fix: " + e.getMessage());
    }

    // ---- moveOpenTicketState / verifyOpenStateLanded (#45): non-terminal targets,
    // where the open flag is true before AND after — the id in the ticket_state
    // OBJECT is the only thing that can prove the write landed. ----

    @Test
    void openStateMoveVerifiesByTheReadBackId() {
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"ticket_state_id\":\"9619797\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"open\":true,\"ticket_state\":{\"id\":\"9619797\","
                        + "\"category\":\"in_progress\"}}", MediaType.APPLICATION_JSON));
        gateway.moveOpenTicketState("ticket-9", "9619797");
        server.verify();
    }

    @Test
    void openStateMoveWithAWrongReadBackIdThrows() {
        // The silent-200 shape for a non-terminal write: 200 on the PUT, but the
        // ticket still carries some other state's id.
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"open\":true,\"ticket_state\":{\"id\":\"9619796\","
                        + "\"category\":\"submitted\"}}", MediaType.APPLICATION_JSON));
        IntercomException e = assertThrows(IntercomException.class,
                () -> gateway.moveOpenTicketState("ticket-9", "9619797"));
        assertTrue(e.getMessage().contains("did not land"),
                "an unmoved state must surface as failure: " + e.getMessage());
    }

    @Test
    void openStateMoveOnAStringShapeReadBackThrowsRatherThanPassingUnverified() {
        // The old-API-version representation, probed live 2026-08-11: ticket_state as
        // a bare string — AND on that version the write itself is silently ignored,
        // so passing here would report success for a write that never happened.
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"open\":true,\"ticket_state\":\"submitted\"}",
                        MediaType.APPLICATION_JSON));
        IntercomException e = assertThrows(IntercomException.class,
                () -> gateway.moveOpenTicketState("ticket-9", "9619797"));
        assertTrue(e.getMessage().contains("unverifiable"),
                "the error must say WHY: the version predates the object shape: " + e.getMessage());
    }

    @Test
    void aTicketNoteEscapesItsTextAndPostsAsAnAdminNote() {
        server.expect(requestTo(BASE + "/tickets/ticket-9/reply")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"message_type\":\"note\",\"type\":\"admin\","
                        + "\"admin_id\":\"admin-1\","
                        + "\"body\":\"<p>Partner &amp; Co accepted the TSANet case.</p>\"}"))
                .andRespond(withSuccess("{\"id\":\"part-1\",\"part_type\":\"note\"}",
                        MediaType.APPLICATION_JSON));
        gateway.postTicketNote("ticket-9", "Partner & Co accepted the TSANet case.");
        server.verify();
    }

    @Test
    void aConversationNotePostsTheSameShapeOnTheConversationPath() {
        // #50: the ticketless verdict surface. Same reply-part body as the ticket note —
        // only the path differs — so this pins that the path is the ONLY difference and a
        // future divergence in one is caught rather than assumed.
        server.expect(requestTo(BASE + "/conversations/conv-1/reply")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"message_type\":\"note\",\"type\":\"admin\","
                        + "\"admin_id\":\"admin-1\","
                        + "\"body\":\"<p>Partner &amp; Co accepted the TSANet case.</p>\"}"))
                .andRespond(withSuccess("{\"id\":\"part-2\",\"part_type\":\"note\"}",
                        MediaType.APPLICATION_JSON));
        gateway.postConversationNote("conv-1", "Partner & Co accepted the TSANet case.");
        server.verify();
    }

    @Test
    void aConversationNoteWithNoIdFailsBeforeReachingTheWire() {
        // No server expectation set: a call that escaped would fail the MockRestServiceServer.
        assertThrows(IntercomException.class,
                () -> gateway.postConversationNote("  ", "a verdict"));
        assertThrows(IntercomException.class,
                () -> gateway.postConversationNote("conv-1", "  "));
    }

    @Test
    void stateMoveReadBackFailureIsAFailureNotSuccess() {
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/tickets/ticket-9")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        IntercomException e = assertThrows(IntercomException.class,
                () -> gateway.moveTicketState("ticket-9", "9619799"));
        assertTrue(e.getMessage().contains("state read-back"),
                "a failed read-back proves nothing landed; it must not surface as success: "
                        + e.getMessage());
    }
}
