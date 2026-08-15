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
 * <p><b>Ticketless links get the note alone</b> ({@code tsanetgit/Fin-Intercom_App#50}).
 * An outbound submit from a plain Inbox conversation is never anchored to a ticket, and
 * a conversation has no ticket state, so the state write is not deferred there — it does
 * not apply. Every other guard is shared, deliberately: such a link is re-fetched,
 * direction- and status-checked, and deduped exactly like an anchored one, so the two
 * paths cannot drift on the rules that decide WHETHER to surface. They differ only on
 * WHERE. State-id configuration is likewise demanded only of the anchored path, since
 * refusing a ticketless link for a missing state id would withhold the one write it can
 * make.
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
        // A submit from a plain Inbox conversation is never anchored to a ticket. Such a
        // link takes EVERY guard below unchanged — the fetch, the direction and status
        // refusals, the dedup record — and diverges only at the platform write, because a
        // conversation has no ticket state to move (#50). The note is therefore not the
        // second of two writes but the entire surface.
        boolean anchored = link.ticketAnchored();
        String anchor = anchored ? "ticket=" + link.ticketId() : "conversation=" + link.conversationId();
        if (!anchored && (link.conversationId() == null || link.conversationId().isBlank())) {
            // Neither anchor. Nothing to write on, and no id to name in the audit line.
            log.error("tsanet_audit event=response_no_anchor token={}", token);
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

        // Resolved and demanded ONLY for anchored links. A ticketless link has no state
        // to move, so refusing it for a missing state id would withhold the one write it
        // can actually make.
        String stateId = null;
        if (anchored) {
            stateId = switch (status) {
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
        }

        try {
            if (anchored) {
                intercom.postTicketNote(link.ticketId(), noteText(link, status));
            } else {
                intercom.postConversationNote(link.conversationId(), noteText(link, status));
            }
        } catch (RuntimeException e) {
            // Nothing recorded, so a redelivery retries. On an anchored link the state
            // moved first and its re-move targets the same id and re-verifies clean, so
            // the note is the write actually retried; on a ticketless link the note was
            // the only write, and the retry is the whole surface.
            log.error("tsanet_audit event=response_note_failed token={} {} reason={}"
                    + " consequence=redelivery_retries_the_surface",
                    token, anchor, e.getMessage());
            return;
        }

        try {
            links.surfacedStatus(token, status.name());
        } catch (RuntimeException e) {
            // The design's one admitted residual, stated where it happens: both
            // platform writes landed but the dedup record did not, so a redelivery
            // re-runs the surface and the note posts twice. Loud, because every other
            // failure branch here is - and this is the only one that duplicates a
            // partner-visible-adjacent write instead of losing one.
            log.error("tsanet_audit event=response_record_failed token={} {} reason={}"
                    + " consequence=duplicate_note_on_redelivery",
                    token, anchor, e.getMessage());
            return;
        }
        // state_id on the line so a misconfigured-but-real id is visible in the audit
        // stream, mirroring closure_applied (#27 auto-QA). ONE event name across both
        // surfaces, with state_id=none for the ticketless one: a verdict surfaced either
        // way, and counting surfaces should not need two queries.
        log.info("tsanet_audit event=response_surfaced token={} {} partner={} status={}"
                + " state_id={}", token, anchor, link.partnerDisplay(), status,
                stateId == null ? "none" : stateId);
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
