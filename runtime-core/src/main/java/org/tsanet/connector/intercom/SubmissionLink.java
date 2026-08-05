package org.tsanet.connector.intercom;

import java.time.Instant;

/**
 * One outbound case anchored to its Intercom home: the routing substrate for both
 * directions. Outbound, the annotation pipeline writes these facts onto the ticket;
 * inbound (Phase 2), TSANet webhooks arrive keyed by token and Intercom events by
 * conversation id, and this record is how either finds the other side.
 *
 * <p>No case content here on purpose (no summary/description): the link is routing
 * and audit state, not a copy of the collaboration.
 */
public record SubmissionLink(
        String token,
        String internalCaseNumber,
        String workspaceId,
        String conversationId,
        String ticketId,
        String partnerDisplay,
        String respondBy,
        boolean testCase,
        String submittedBy,
        Instant submittedAt) {

    public boolean ticketAnchored() {
        return ticketId != null && !ticketId.isBlank();
    }
}
