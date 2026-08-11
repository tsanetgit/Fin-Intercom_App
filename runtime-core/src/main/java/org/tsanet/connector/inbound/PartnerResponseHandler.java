package org.tsanet.connector.inbound;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tsanet.connector.events.InboundEvent;
import org.tsanet.connector.events.InboundEventHandler;
import org.tsanet.connector.intercom.IntercomGateway;
import org.tsanet.connector.intercom.SubmissionLink;
import org.tsanet.connector.intercom.SubmissionLinkTable;
import org.tsanet.connector.tsanet.TsanetGateway;
import org.tsanet.connector.tsanet.dto.InboundCase;
import org.tsanet.connector.webhooks.TsanetCloudEvent;

/**
 * A partner responded to our outbound case; surface the verdict on the ticket.
 *
 * <p>Without this, response events drain and drop — observed live 2026-08-11: Veritas
 * accepted an outbound case and the six-event webhook batch produced nothing on the
 * ticket, so a teammate learns of an accept only by opening the panel
 * ({@code tsanetgit/Fin-Intercom_App#45}). Surfacing is two writes: the ticket state
 * moves per the D17 mapping, and an admin note names the verdict.
 *
 * <p><b>The fetched status is the truth, not the event</b> (D3). The event body is
 * unread beyond the token: the case is re-fetched and the surface keys entirely on
 * what the fetch says, so a stale or reordered delivery cannot surface a verdict the
 * case no longer holds.
 *
 * <p><b>Once per transition, not once per delivery.</b> One verdict arrives as a burst
 * (five status-bearing events in one second, observed on the Veritas accept), and
 * at-least-once delivery can add more. The dedup record is
 * {@link SubmissionLinkTable#surfacedStatus(String)} — durable, so it survives a
 * restart, which matters because redelivery is the ONLY recovery surface (the intake
 * 200-acks every branch, D4, and the drain consumes on handler exception).
 *
 * <p><b>Ordering: platform first, then the record</b> — the same rationale as
 * {@link InboundClosureHandler}, verbatim: this mirrors a fact that already happened,
 * and recording first would make the redelivery that could repair a failed write a
 * no-op. Within the platform writes: state, then note, then record. Any failure
 * before the record leaves the whole surface retryable; the residual is a duplicate
 * note when the note lands and the record write is lost, which the failure log states.
 *
 * <p><b>What this handler refuses.</b> INBOUND-direction cases: every response event
 * there is an echo of our own panel action, and "Partner accepted" would be false —
 * the panel slice owns its own state moves. CLOSED: {@code REQUEST_CLOSED} is
 * {@link InboundClosureHandler}'s event, and this handler stepping in on a raced
 * fetch would do closure's job without closure's guards. OPEN: a response event whose
 * fetch shows no verdict yet is a raced read; skipping WITHOUT recording lets the
 * next delivery surface it. PENDINGACTION/UNKNOWN: deliberately unmapped (D17) —
 * refuse loudly, record nothing.
 *
 * <p><b>This handler never touches {@link org.tsanet.connector.intercom.CaseState}.</b>
 * That field is OUR response to an inbound case and guards the panel against
 * answering a partner twice; writing it here would trip closure's already-closed
 * guard and the panel's double-response guard on state this handler has no claim to.
 */
@Component
public class PartnerResponseHandler implements InboundEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PartnerResponseHandler.class);

    private final TsanetGateway tsanet;
    private final IntercomGateway intercom;
    private final SubmissionLinkTable links;
    private final InboundProperties properties;

    public PartnerResponseHandler(TsanetGateway tsanet, IntercomGateway intercom,
            SubmissionLinkTable links, InboundProperties properties) {
        this.tsanet = tsanet;
        this.intercom = intercom;
        this.links = links;
        this.properties = properties;
    }

    @Override
    public boolean handles(InboundEvent event) {
        if (event.source() != InboundEvent.Source.TSANET) {
            return false;
        }
        return TsanetCloudEvent.Type.RESPONSE_CREATED.wireValue().equals(event.kind())
                || TsanetCloudEvent.Type.RESPONSE_UPDATED.wireValue().equals(event.kind());
    }

    @Override
    public void handle(InboundEvent event) {
        String token = event.id();
        if (token == null || token.isBlank()) {
            log.error("tsanet_audit event=response_unroutable reason=no_token");
            return;
        }
        Optional<SubmissionLink> found = links.byToken(token);
        if (found.isEmpty()) {
            // A case we never linked. Responses on it are not ours to surface.
            log.info("tsanet_audit event=response_no_link token={}", token);
            return;
        }
        SubmissionLink link = found.get();
        if (!link.ticketAnchored()) {
            // KNOWN partial coverage, not an anomaly: an outbound submit from a plain
            // Inbox conversation produces a ticketless link (the same shape annotate()
            // serves via its conversation-reply branch), and a ticket state cannot
            // move on a conversation. Surfacing a conversation-note-only verdict is
            // the named follow-up on tsanetgit/Fin-Intercom_App#45 — until then this
            // line is the only trace a response on such a case gets.
            log.warn("tsanet_audit event=response_no_ticket token={} conversation={}",
                    token, link.conversationId());
            return;
        }

        Optional<InboundCase> fetched = tsanet.fetchByToken(token);
        if (fetched.isEmpty()) {
            // Loud, and nothing recorded: an empty fetch on a case we hold a link for
            // is ambiguity, and the next delivery deserves a clean retry.
            log.error("tsanet_audit event=response_fetch_empty token={}", token);
            return;
        }
        InboundCase current = fetched.get();
        if (current.direction() != InboundCase.Direction.OUTBOUND) {
            // INBOUND: the response event is an echo of our own panel action — there
            // is no partner verdict to surface, and the note this handler writes
            // would attribute our action to the partner. UNKNOWN: same discipline as
            // D17's unmapped statuses — do not act on a wire value we cannot read.
            log.info("tsanet_audit event=response_echo_skipped token={} direction={}",
                    token, current.direction());
            return;
        }

        InboundCase.Status status = current.status();
        switch (status) {
            case OPEN -> {
                // A response event whose fetch shows no verdict is a raced read.
                // Nothing recorded, so the next delivery re-tries against a fresher
                // fetch instead of being deduped into silence.
                log.info("tsanet_audit event=response_no_verdict token={} status=OPEN", token);
                return;
            }
            case CLOSED -> {
                // REQUEST_CLOSED owns closure (InboundClosureHandler); a response
                // event racing a close must not do closure's job without its guards.
                log.info("tsanet_audit event=response_closure_deferred token={}", token);
                return;
            }
            case PENDINGACTION, UNKNOWN -> {
                // Deliberately unmapped (D17): moving the ticket on a status nobody
                // has defined would be invisibly wrong. Not recorded, so a later
                // transition to a mapped status still surfaces.
                log.error("tsanet_audit event=response_unmapped token={} status={}", token, status);
                return;
            }
            default -> { }
        }

        if (status.name().equals(links.surfacedStatus(token))) {
            log.info("tsanet_audit event=response_already_surfaced token={} status={}",
                    token, status);
            return;
        }

        String stateId = switch (status) {
            case ACCEPTED -> properties.inProgressStateId();
            case INFORMATION -> properties.waitingStateId();
            case REJECTED -> properties.resolvedStateId();
            default -> null;   // unreachable: earlier switch returned on every other member
        };
        if (stateId == null || stateId.isBlank()) {
            // Per-status refusal: state ids are PER-WORKSPACE (D17) and a guessed id
            // may name some other state that happens to exist. ALERTABLE: each
            // response arriving while its id is unconfigured surfaces nothing, and
            // only a redelivery or a later transition retries it.
            log.error("tsanet_audit event=response_unconfigured token={} status={} reason=no_state_id",
                    token, status);
            return;
        }

        try {
            // REJECTED targets a terminal state and uses the open-flag-verified move;
            // ACCEPTED/INFORMATION target open states, whose verification compares the
            // read-back ticket_state.id (vacuous open-flag check otherwise).
            if (status == InboundCase.Status.REJECTED) {
                intercom.moveTicketState(link.ticketId(), stateId);
            } else {
                intercom.moveOpenTicketState(link.ticketId(), stateId);
            }
        } catch (RuntimeException e) {
            // Loud and not recorded: a redelivery re-runs the whole surface, and the
            // state re-move is idempotent (same target). ALERTABLE with no duplicate
            // in flight, same one-shot-loss property as closure (D18).
            log.error("tsanet_audit event=response_ticket_move_failed token={} ticket={} reason={}",
                    token, link.ticketId(), e.getMessage());
            return;
        }

        try {
            intercom.postTicketNote(link.ticketId(), noteText(link, status));
        } catch (RuntimeException e) {
            // The state moved but the verdict note did not land, and nothing is
            // recorded — so a redelivery retries both. The state re-move targets the
            // same id and re-verifies clean; the note is the write actually retried.
            log.error("tsanet_audit event=response_note_failed token={} ticket={} reason={}"
                    + " consequence=redelivery_retries_state_and_note",
                    token, link.ticketId(), e.getMessage());
            return;
        }

        links.surfacedStatus(token, status.name());
        // state_id on the line so a misconfigured-but-real id is visible in the audit
        // stream, mirroring closure_applied (#27 auto-QA).
        log.info("tsanet_audit event=response_surfaced token={} ticket={} partner={} status={}"
                + " state_id={}", token, link.ticketId(), link.partnerDisplay(), status, stateId);
    }

    private static String noteText(SubmissionLink link, InboundCase.Status status) {
        String partner = link.partnerDisplay() == null || link.partnerDisplay().isBlank()
                ? "The partner" : link.partnerDisplay();
        return switch (status) {
            case ACCEPTED -> partner + " accepted the TSANet case.";
            case INFORMATION -> partner + " requested more information on the TSANet case.";
            case REJECTED -> partner + " declined the TSANet case.";
            // Unreachable: handle() returned on every other member before mapping.
            default -> throw new IllegalStateException("no note text for status " + status);
        };
    }
}
