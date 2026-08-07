package org.tsanet.connector.inbound;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tsanet.connector.events.InboundEvent;
import org.tsanet.connector.events.InboundEventHandler;
import org.tsanet.connector.intercom.CaseState;
import org.tsanet.connector.intercom.IntercomGateway;
import org.tsanet.connector.intercom.SubmissionLink;
import org.tsanet.connector.intercom.SubmissionLinkTable;
import org.tsanet.connector.webhooks.TsanetCloudEvent;

/**
 * The partner closed the case; resolve the ticket that mirrors it.
 *
 * <p>Without this, a closed case leaves its Intercom ticket open forever with the panel
 * still offering Accept and Decline — so an agent can answer a partner who has gone.
 * That is the concrete harm, and it is why this is worth shipping ahead of the outbound
 * half.
 *
 * <p><b>Platform first, then the record.</b> The opposite order to
 * {@link InboundPanel}'s response flow, and deliberately so. There, the recorded state
 * moves BEFORE the API call because it is a guard against answering a partner twice.
 * Here nothing is being sent to anyone — this mirrors a fact that already happened — so
 * the risk runs the other way: recording CLOSED before the ticket actually resolves
 * would tell an agent the case is closed while the ticket sits open in their queue.
 *
 * <p><b>Idempotent, because it will be delivered more than once.</b> A ticket already
 * resolved is left alone: TSANet redelivers on any non-2xx, the drain can replay, and a
 * closure arriving twice must not produce two writes.
 *
 * <p><b>What this does NOT recover.</b> Nothing re-drives a MISSED closure. The
 * reconciliation poller (D16) claims {@code collaboration-request.created} only, so a
 * closure event lost in transit leaves the ticket open with no second chance — the same
 * gap inbound create had before the poller existed. Extending the poller to reconcile
 * status is the status-sync box on {@code tsanetgit/Member-Integrations#51}, not this
 * slice, and this javadoc must not be read as claiming otherwise.
 */
@Component
public class InboundClosureHandler implements InboundEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InboundClosureHandler.class);

    private final IntercomGateway intercom;
    private final SubmissionLinkTable links;
    private final String resolvedStateId;

    public InboundClosureHandler(IntercomGateway intercom, SubmissionLinkTable links,
            InboundProperties properties) {
        this.intercom = intercom;
        this.links = links;
        this.resolvedStateId = properties.resolvedStateId();
    }

    @Override
    public boolean handles(InboundEvent event) {
        return event.source() == InboundEvent.Source.TSANET
                && TsanetCloudEvent.Type.REQUEST_CLOSED.wireValue().equals(event.kind());
    }

    @Override
    public void handle(InboundEvent event) {
        String token = event.id();
        if (token == null || token.isBlank()) {
            log.error("tsanet_audit event=closure_unroutable reason=no_token");
            return;
        }
        if (resolvedStateId == null || resolvedStateId.isBlank()) {
            // No default is possible: ticket-state ids are PER-WORKSPACE (D17), and the
            // only way found to enumerate them is an API version labelled Unstable. A
            // guessed id would either 404 or — worse — move the ticket to some other
            // workspace-local state that happens to share the number.
            log.error("tsanet_audit event=closure_unconfigured token={} reason=no_resolved_state_id",
                    token);
            return;
        }

        Optional<SubmissionLink> found = links.byToken(token);
        if (found.isEmpty()) {
            // A case we never ticketed. Nothing to resolve, and creating a ticket now to
            // immediately close it would put a dead item in an agent's queue.
            log.info("tsanet_audit event=closure_no_link token={}", token);
            return;
        }
        SubmissionLink link = found.get();
        if (!link.ticketAnchored()) {
            // A claim mid-flight: the create has not finished, so there is no ticket to
            // resolve yet. Same shape as the poller's in-flight case.
            log.warn("tsanet_audit event=closure_unanchored token={} reason=claim_in_flight", token);
            return;
        }
        if (links.caseState(token) == CaseState.CLOSED) {
            log.info("tsanet_audit event=closure_already_applied token={} ticket={}",
                    token, link.ticketId());
            return;
        }

        try {
            intercom.moveTicketState(link.ticketId(), resolvedStateId);
        } catch (RuntimeException e) {
            // Loud and not recorded: leaving the state un-moved is what lets a
            // redelivery or a manual retry still fix it. Recording CLOSED here would
            // make the next delivery a no-op and strand the ticket open permanently.
            log.error("tsanet_audit event=closure_ticket_resolve_failed token={} ticket={} reason={}",
                    token, link.ticketId(), e.getMessage());
            return;
        }

        // Bounded, because the only competing writer is an agent answering in the panel
        // at this exact moment. If it keeps losing, the ticket is still resolved — which
        // is what the agent sees — and only our record of WHY is stale, so this logs
        // rather than looping.
        if (!moveStateToClosed(token)) {
            log.warn("tsanet_audit event=closure_state_not_recorded token={} ticket={}"
                            + " reason=lost_compare_and_set observed={}",
                    token, link.ticketId(), links.caseState(token));
            return;
        }
        log.info("tsanet_audit event=closure_applied token={} ticket={} partner={}",
                token, link.ticketId(), link.partnerDisplay());
    }

    /**
     * Compare-and-set from whatever state currently holds. The table offers no
     * unconditional setter on purpose — every other writer of this field is guarding
     * against a double response — so closure reads the current value and moves from it,
     * retrying once if an agent won the race in between.
     */
    private boolean moveStateToClosed(String token) {
        for (int attempt = 0; attempt < 2; attempt++) {
            CaseState current = links.caseState(token);
            if (current == CaseState.CLOSED) {
                return true;
            }
            if (links.caseState(token, current, CaseState.CLOSED)) {
                return true;
            }
        }
        return false;
    }
}
