package org.tsanet.connector.inbound;

import java.util.List;
import java.util.Optional;
import org.tsanet.connector.intercom.AnnotationState;
import org.tsanet.connector.intercom.CaseState;
import org.tsanet.connector.intercom.SubmissionLink;
import org.tsanet.connector.intercom.SubmissionLinkTable;

/**
 * Pass-through {@link SubmissionLinkTable} so a test can override ONE method and inherit
 * real behaviour for the rest.
 *
 * <p>Exists because the interleavings worth testing here are about a single method
 * answering differently at two moments in one cycle — the live drain winning a
 * {@code record()} race while the poller is mid-case. A hand-written stub would have to
 * reimplement the whole surface, and every method it got subtly wrong would weaken the
 * test rather than fail it.
 */
abstract class DelegatingLinkTable implements SubmissionLinkTable {

    private final SubmissionLinkTable delegate;

    DelegatingLinkTable(SubmissionLinkTable delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean record(SubmissionLink link) {
        return delegate.record(link);
    }

    @Override
    public Optional<SubmissionLink> byToken(String token) {
        return delegate.byToken(token);
    }

    @Override
    public Optional<SubmissionLink> byInternalCaseNumber(String internalCaseNumber) {
        return delegate.byInternalCaseNumber(internalCaseNumber);
    }

    @Override
    public List<SubmissionLink> byConversationId(String conversationId) {
        return delegate.byConversationId(conversationId);
    }

    @Override
    public AnnotationState annotationState(String token) {
        return delegate.annotationState(token);
    }

    @Override
    public void annotationState(String token, AnnotationState state) {
        delegate.annotationState(token, state);
    }

    @Override
    public boolean releaseClaim(String token) {
        return delegate.releaseClaim(token);
    }

    @Override
    public void anchor(String token, String ticketId) {
        delegate.anchor(token, ticketId);
    }

    @Override
    public Optional<SubmissionLink> byTicketId(String ticketId) {
        return delegate.byTicketId(ticketId);
    }

    @Override
    public CaseState caseState(String token) {
        return delegate.caseState(token);
    }

    @Override
    public boolean caseState(String token, CaseState expected, CaseState next) {
        return delegate.caseState(token, expected, next);
    }

    @Override
    public void caseNumber(String token, String caseNumber) {
        delegate.caseNumber(token, caseNumber);
    }

    @Override
    public int size() {
        return delegate.size();
    }
}
