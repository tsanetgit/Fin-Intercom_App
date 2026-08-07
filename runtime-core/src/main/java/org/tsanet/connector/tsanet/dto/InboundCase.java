package org.tsanet.connector.tsanet.dto;

/**
 * A collaboration case read back by token — the adapter-facing view of
 * {@code CollaborationRequestStatusDTO}.
 *
 * <p>Distinct from {@link SubmissionResult}, which is what our own submit returns.
 * A case the connector did not create has no {@code internalCaseNumber} of ours, so
 * {@code findSubmission} cannot reach it; the token is the only handle.
 *
 * <p>{@link #direction()} is load-bearing rather than informational: push events fire
 * for cases in both directions, and only a partner-originated one should ever cause a
 * ticket to be created. An outbound case is one we submitted and already hold a link
 * for. Fields the estate has not converged on (severity mapping, note direction,
 * attachments) are deliberately absent — see references/semantics.md.
 */
public record InboundCase(
        String token,
        Direction direction,
        Status status,
        String partnerCompany,
        String summary,
        String description,
        String respondBy,
        String priority,
        boolean testCase) {

    public enum Direction { OUTBOUND, INBOUND, UNKNOWN }

    /**
     * The lifecycle values the API defines. {@code ALL} is a list filter rather than a
     * case state and never appears on one; it is absent here on purpose.
     */
    public enum Status { OPEN, INFORMATION, REJECTED, ACCEPTED, CLOSED, PENDINGACTION, UNKNOWN }

    /** True when this case came from a partner and is ours to surface to an agent. */
    public boolean inbound() {
        return direction == Direction.INBOUND;
    }
}
