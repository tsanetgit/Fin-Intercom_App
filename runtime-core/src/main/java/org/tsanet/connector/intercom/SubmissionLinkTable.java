package org.tsanet.connector.intercom;

import java.util.Optional;

/**
 * The token <-> ticket routing table. Links are precious: losing one orphans every
 * later update for that case, so implementations never evict. In-memory for Phase 1;
 * persistence behind this interface is a PRECONDITION for Phase 2 inbound, not a
 * nice-to-have — a restart loses the table and with it the routing for every
 * pre-restart case (docs/decisions.md D10).
 */
public interface SubmissionLinkTable {

    /** Records the link once; a token already present is left untouched (replay-safe). */
    void record(SubmissionLink link);

    Optional<SubmissionLink> byToken(String token);

    Optional<SubmissionLink> byInternalCaseNumber(String internalCaseNumber);

    /** Inbound-from-Intercom routing (Phase 2): events arrive keyed by conversation. */
    Optional<SubmissionLink> byConversationId(String conversationId);

    AnnotationState annotationState(String token);

    void annotationState(String token, AnnotationState state);

    int size();
}
