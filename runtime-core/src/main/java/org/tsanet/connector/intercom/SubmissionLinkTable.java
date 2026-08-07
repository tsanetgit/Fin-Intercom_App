package org.tsanet.connector.intercom;

import java.util.List;
import java.util.Optional;

/**
 * The token <-> ticket routing table. Links are precious: losing one orphans every
 * later update for that case, so implementations never evict. In-memory for Phase 1;
 * persistence behind this interface is a PRECONDITION for Phase 2 inbound, not a
 * nice-to-have — a restart loses the table and with it the routing for every
 * pre-restart case (docs/decisions.md D10).
 *
 * <p><b>Null keys are answered, never thrown on.</b> They arrive for real — a webhook
 * payload missing its token, a ticketless link with no conversation — and an exception
 * from this table kills the event drain, which is why every method below that takes a
 * key treats a null one exactly as it treats an absent one. {@link #record} is the
 * deliberate exception, and the opposite: a link missing a routing key is refused,
 * because a link that cannot be found later is a partner case with no route home.
 * {@code SubmissionLinkTableContractTest} pins this across the whole keyed surface for
 * every implementation, rather than per method, so that adding an entry point cannot
 * quietly leave one unpinned.
 */
public interface SubmissionLinkTable {

    /**
     * Records the link once; a token already present is left untouched (replay-safe).
     *
     * @throws IllegalStateException if the token or the internal case number is null.
     *     Rejection is atomic: nothing is left behind on any index.
     */
    /**
     * Records a link, first-write-wins on the token.
     *
     * @return true when THIS call inserted the row, false when the token was already
     *     present. The return value is the only atomic claim primitive the table
     *     offers: an inbound handler that checks byToken and then creates has a
     *     TOCTOU race where two concurrent deliveries both find nothing and both
     *     create a ticket. Claiming with this instead makes the token's unique
     *     constraint the arbiter, so exactly one caller proceeds to the platform
     *     write. Callers that do not race may ignore it.
     */
    boolean record(SubmissionLink link);

    Optional<SubmissionLink> byToken(String token);

    Optional<SubmissionLink> byInternalCaseNumber(String internalCaseNumber);

    /**
     * Inbound-from-Intercom routing (Phase 2): events arrive keyed by conversation,
     * and one conversation can legitimately anchor several cases over its life —
     * newest first, so the common single-case lookup is get(0).
     */
    List<SubmissionLink> byConversationId(String conversationId);

    /** Never null: {@link AnnotationState#NONE} for an unset, unknown, or null token. */
    AnnotationState annotationState(String token);

    /**
     * A no-op for an unknown or null token. The state itself is not a key and must not
     * be null — storing one would answer null from the read above, and the drain reads
     * it to decide whether an annotation has already run.
     *
     * @throws NullPointerException if {@code state} is null
     */
    void annotationState(String token, AnnotationState state);

    /**
     * Releases an UNANCHORED claim, returning whether a row was removed.
     *
     * <p>Exists for exactly one caller: a handler that won the claim on a token and
     * then failed to create the platform object. Without this the token stays marked
     * as handled forever and no redelivery can ever produce the ticket — a partner's
     * case lost in silence, which is worse than a duplicate because nothing surfaces
     * it.
     *
     * <p>Safe by construction rather than by discipline: a link that HAS an anchor
     * (ticket or conversation) is a real route home and is never removed, whatever the
     * caller asks for. So a misuse degrades to a no-op instead of orphaning a live
     * case.
     */
    boolean releaseClaim(String token);

    /**
     * Attaches the platform anchor to an existing link, after the object it points at
     * has been created. Separate from {@link #record} because record() is
     * first-write-wins: a claim is taken before the ticket exists, so the anchor
     * cannot be part of the insert and re-recording would be ignored.
     *
     * <p>Write-once, and a BLANK id is not an anchor. Both rules exist because
     * {@link #releaseClaim} decides on "is this link anchored": if a blank id could be
     * stored, the two stores would disagree about whether such a link may be released
     * (the map store asks {@code ticketAnchored()}, the SQL store asks
     * {@code ticket_id IS NULL}), and the SQL store — the one that runs in prod —
     * would refuse to release it, so redelivery would find the token claimed forever
     * and no ticket would ever be created. Guarding at this choke point makes the two
     * predicates agree by construction rather than by matching wording. Re-pointing an
     * existing anchor is likewise refused: it would silently move a live case.
     */
    void anchor(String token, String ticketId);

    int size();
}
