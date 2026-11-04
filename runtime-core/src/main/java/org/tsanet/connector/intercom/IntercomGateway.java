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
     * @param id the API handle (e.g. 100000000000001) — what every /tickets/ call and
     *     canvas payload uses, and what the link anchors on
     * @param number the agent-facing number (e.g. 100000001, shown as "#100000001") —
     *     the ONLY one searchable in the Help Desk, and therefore the only one that can
     *     be quoted to a partner as our case number
     */
    record CreatedTicket(String id, String number) {
    }

    /**
     * Moves a ticket to a state, by state id, and VERIFIES it landed.
     *
     * <p>Read and write are asymmetric here (docs/decisions.md D17): a ticket reads its
     * state back as a plain string, but setting one requires the numeric
     * {@code ticket_state_id}. There is no set-by-name.
     *
     * <p>The verification is not optional. This is the exact surface D3 was written for —
     * {@code PUT /tickets/&#123;id&#125;} answers 200 while silently ignoring a field it does not
     * recognise, so a bare success here would let a state that never moved report as
     * moved, and the ticket would sit open with the partner's case closed.
     *
     * <p>The verification asserts the ticket is CLOSED (its {@code open} flag is false),
     * not that it carries one particular state name. <b>So a wrong-but-terminal state id
     * passes it</b> — a misconfiguration naming some other closing state in the same
     * workspace verifies clean. That residual is accepted because there is no id-to-name
     * mapping available (D17: state ids are only enumerable under
     * {@code Intercom-Version: Unstable}, which must not be in a runtime path), and it is
     * mitigated by the caller logging the id it used. Every state this connector writes
     * is terminal, and asserting on the flag keeps the check working when a workspace
     * renames its states — which D17 records that it may, since state ids and labels are
     * per-workspace. An implementation that needs to write a non-terminal state must
     * strengthen this, because the current check would pass one.
     *
     * @throws IntercomException if the write fails, or if the read-back shows the ticket
     *     is still open
     */
    void moveTicketState(String ticketId, String stateId);

    /**
     * Moves a ticket to a NON-terminal state (In progress, Waiting on customer), by
     * state id, and VERIFIES it landed — by a different mechanism than
     * {@link #moveTicketState}, because the open-flag check is vacuous for a ticket
     * that stays open.
     *
     * <p>The verification compares the read-back {@code ticket_state.id} against the id
     * written. That field exists only in the OBJECT representation of
     * {@code ticket_state}, which the workspace serves when the app's default API
     * version is recent enough (probed live 2026-08-11 on the dev workspace: an
     * unversioned read returns {@code {id, category, internal_label, ...}}, while a
     * {@code Intercom-Version: 2.11} read returns a bare string AND a 2.11 write is
     * silently ignored — the app-default version is what this connector actually runs
     * under, since it pins no version header). If the read-back comes back as the old
     * string shape, implementations must THROW rather than pass unverified: a
     * non-terminal write that cannot be verified is exactly the D3 silent-200 surface.
     *
     * @throws IntercomException if the write fails, or the read-back does not carry the
     *     written state id
     */
    void moveOpenTicketState(String ticketId, String stateId);

    /**
     * Posts an admin note on a ticket. {@code text} is PLAIN TEXT: implementations
     * escape it and wrap it in markup themselves, so no caller ever assembles HTML.
     *
     * <p>Throws {@link IntercomException} when the API reports failure. A silent 200
     * that drops the note is NOT detected — no read-back exists for reply parts yet —
     * so a dropped note is permanent once the caller records the surface. Callers decide
     * what a lost note costs — for response surfacing it blocks the dedup record, so a
     * redelivery retries the whole surface.
     */
    void postTicketNote(String ticketId, String text);

    /**
     * The same admin note on a CONVERSATION, for links that were never anchored to a
     * ticket — an outbound submit from a plain Inbox conversation. Identical contract
     * to {@link #postTicketNote}: plain text in, implementations own the markup, and
     * {@link IntercomException} on reported failure with no silent-200 detection.
     *
     * <p>Exists because a conversation has no ticket state to move, so the note is the
     * ENTIRE surface a partner verdict gets on such a link rather than the second of
     * two writes (#50). {@link #annotate} already writes to conversations this way; this
     * method is that capability made reachable with caller-supplied text.
     */
    void postConversationNote(String conversationId, String text);

    /** Cheap credential/reachability probe (GET /me). */
    boolean healthy();

    /** How far the annotation got when it succeeded. */
    enum AnnotationOutcome { FULL, DEGRADED }
}
