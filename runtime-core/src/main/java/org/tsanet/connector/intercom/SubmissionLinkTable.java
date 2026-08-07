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

    int size();
}
