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
        Instant submittedAt,
        /** The case number quoted to the partner on accept; null until then. Stored
         * rather than re-derived so the value we actually sent survives a change of
         * convention about which platform identifier fills it. */
        String caseNumber) {

    /** Pre-caseNumber convenience for the outbound path, which never quotes one. */
    public SubmissionLink(String token, String internalCaseNumber, String workspaceId,
            String conversationId, String ticketId, String partnerDisplay, String respondBy,
            boolean testCase, String submittedBy, Instant submittedAt) {
        this(token, internalCaseNumber, workspaceId, conversationId, ticketId, partnerDisplay,
                respondBy, testCase, submittedBy, submittedAt, null);
    }

    public boolean ticketAnchored() {
        return ticketId != null && !ticketId.isBlank();
    }
}
