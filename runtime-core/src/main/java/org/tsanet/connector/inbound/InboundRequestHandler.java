package org.tsanet.connector.inbound;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tsanet.connector.events.InboundEvent;
import org.tsanet.connector.events.InboundEventHandler;
import org.tsanet.connector.intercom.InboundTicketRequest;
import org.tsanet.connector.intercom.IntercomGateway;
import org.tsanet.connector.intercom.SubmissionLink;
import org.tsanet.connector.intercom.SubmissionLinkTable;
import org.tsanet.connector.tsanet.GatewayException;
import org.tsanet.connector.tsanet.TsanetGateway;
import org.tsanet.connector.tsanet.dto.InboundCase;
import org.tsanet.connector.webhooks.TsanetCloudEvent;

/**
 * Turns a partner-created collaboration case into exactly one Intercom ticket.
 *
 * <p><b>Why claim-before-create.</b> Checking the link table and then creating is a
 * TOCTOU race: two deliveries of the same event both find no link and both create a
 * ticket. That is not hypothetical — it is the duplicate-ticket defect already seen on
 * the Zendesk connector, where {@code note.created} overtook
 * {@code collaboration-request.created}. So the token's UNIQUE constraint arbitrates:
 * {@code record()} reports whether THIS call inserted, exactly one caller wins, and
 * only the winner creates. A loser does nothing at all.
 *
 * <p><b>Why the claim is released on failure.</b> A claim held by a caller whose
 * platform write then failed would mark the token handled forever, and no redelivery
 * could ever produce the ticket. The claim is therefore removed before the failure
 * propagates, which trades a duplicate-ticket risk under a pathological interleaving
 * for the certainty of never silently losing a partner's case. Given the queue
 * redelivers and a lost case is invisible while a duplicate is not, that is the right
 * way round.
 *
 * <p>Only {@code collaboration-request.created} is claimed here. The other four push
 * types belong to later slices and are left unclaimed, which the drain logs.
 */
@Component
public class InboundRequestHandler implements InboundEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InboundRequestHandler.class);

    /** Inbound cases carry no internalCaseNumber of ours; the table requires one as a
     * routing key, so it is derived from the token. Local, provisional convention
     * (docs/decisions.md D14) — not an estate-wide one. */
    static final String INBOUND_INTERNAL_PREFIX = "inbound:";

    private final TsanetGateway tsanet;
    private final IntercomGateway intercom;
    private final SubmissionLinkTable links;
    private final String ticketTypeId;

    public InboundRequestHandler(TsanetGateway tsanet, IntercomGateway intercom,
            SubmissionLinkTable links, InboundProperties properties) {
        this.tsanet = tsanet;
        this.intercom = intercom;
        this.links = links;
        this.ticketTypeId = properties.collaborationTicketTypeId();
    }

    @Override
    public boolean handles(InboundEvent event) {
        return event.source() == InboundEvent.Source.TSANET
                && TsanetCloudEvent.Type.REQUEST_CREATED.wireValue().equals(event.kind());
    }

    @Override
    public void handle(InboundEvent event) {
        String token = event.id();
        if (token == null || token.isBlank()) {
            log.error("tsanet_audit event=inbound_unroutable reason=no_token");
            return;
        }
        if (ticketTypeId == null || ticketTypeId.isBlank()) {
            // Refusing beats creating a ticket of the wrong type: the type carries the
            // attributes every later slice reads.
            log.error("tsanet_audit event=inbound_unconfigured token={} reason=no_ticket_type_id", token);
            return;
        }

        InboundCase inboundCase;
        try {
            inboundCase = tsanet.fetchByToken(token).orElse(null);
        } catch (GatewayException e) {
            // Includes the ambiguous case. Nothing is claimed yet, so redelivery or the
            // reconciliation poller retries cleanly.
            log.warn("tsanet_audit event=inbound_fetch_failed token={} reason={}", token, e.getMessage());
            return;
        }
        if (inboundCase == null) {
            log.warn("tsanet_audit event=inbound_case_absent token={}", token);
            return;
        }
        if (!inboundCase.inbound()) {
            // A case we submitted. It already has a link and an anchor; creating a
            // ticket for it would duplicate the agent's own work.
            log.info("tsanet_audit event=inbound_skipped_not_inbound token={} direction={}",
                    token, inboundCase.direction());
            return;
        }

        SubmissionLink claim = new SubmissionLink(token, INBOUND_INTERNAL_PREFIX + token,
                null, null, null, inboundCase.partnerCompany(), inboundCase.respondBy(),
                inboundCase.testCase(), null, Instant.now());
        if (!links.record(claim)) {
            // Someone else owns this token: either a concurrent delivery that is
            // creating the ticket right now, or an earlier one that already did.
            log.info("tsanet_audit event=inbound_already_claimed token={}", token);
            return;
        }

        String ticketId;
        try {
            ticketId = intercom.createCollaborationTicket(
                    InboundTicketRequest.from(inboundCase, ticketTypeId));
        } catch (RuntimeException e) {
            // IntercomException and anything else the write throws: a claim held by a
            // failed create must not outlive it.
            links.releaseClaim(token);
            log.error("tsanet_audit event=inbound_ticket_create_failed token={} reason={}"
                    + " claim=released", token, e.getMessage());
            return;
        }

        // record() is first-write-wins, so the anchor cannot be added by re-recording;
        // the table's own updater is what carries it.
        links.anchor(token, ticketId);
        log.info("tsanet_audit event=inbound_linked token={} ticket={} partner={}",
                token, ticketId, inboundCase.partnerCompany());
    }
}
