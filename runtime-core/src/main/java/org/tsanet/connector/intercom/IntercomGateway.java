package org.tsanet.connector.intercom;

/**
 * The seam to the Intercom REST API for the connector's own writes. Slice 3 needs
 * exactly one operation: annotating the anchor with the submitted case's facts.
 *
 * <p>Rules that bind every implementation:
 * - Verify effects (docs/decisions.md D3): Intercom returns 200 while silently
 *   ignoring unknown fields, so a mutation is not done until it reads back.
 * - Runs ONLY on the event-drain path, never inside a canvas response (D2 budget).
 * - Idempotency lives in the caller (the annotation handler checks the link's state
 *   before invoking); implementations may assume one logical annotation per call.
 * - Failure messages must be log-safe: no tokens, no response bodies.
 */
public interface IntercomGateway {

    /**
     * Writes the case facts onto the link's anchor: ticket-anchored links get the
     * custom attributes (token, partner, respond-by) plus an admin note; ticketless
     * links get the note on the conversation. Returns how far it got; throws
     * {@link IntercomException} when nothing durable landed.
     */
    AnnotationOutcome annotate(SubmissionLink link);

    /**
     * Creates the ticket that anchors an inbound partner case, and returns its id.
     *
     * <p>Called only by the inbound handler, and only after that handler has WON the
     * link-table claim on the case token — so this method never has to be idempotent
     * with respect to the case. It does have to be idempotent with respect to the
     * synthetic partner contact: tickets require exactly one contact (D5), and a
     * fresh contact per delivery would litter the workspace, so implementations find
     * the contact by its stable external id before creating one.
     *
     * <p>Throws {@link IntercomException} when no ticket landed. The caller releases
     * its claim on that, so a redelivery can retry cleanly rather than the token
     * being permanently marked as handled.
     */
    CreatedTicket createCollaborationTicket(InboundTicketRequest request);

    /**
     * The two identifiers an Intercom ticket carries, which are NOT interchangeable.
     *
     * @param id the API handle (e.g. 215475361003303) — what every /tickets/ call and
     *     canvas payload uses, and what the link anchors on
     * @param number the agent-facing number (e.g. 121556608, shown as "#121556608") —
     *     the ONLY one searchable in the Help Desk, and therefore the only one that can
     *     be quoted to a partner as our case number
     */
    record CreatedTicket(String id, String number) {
    }

    /** Cheap credential/reachability probe (GET /me). */
    boolean healthy();

    /** How far the annotation got when it succeeded. */
    enum AnnotationOutcome { FULL, DEGRADED }
}
