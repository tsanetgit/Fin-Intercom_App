package org.tsanet.connector.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tsanet.connector.events.InboundEvent;
import org.tsanet.connector.intercom.SubmissionLinkTable;
import org.tsanet.connector.tsanet.TsanetGateway;
import org.tsanet.connector.tsanet.dto.InboundCase;
import org.tsanet.connector.webhooks.TsanetCloudEvent;

/**
 * Re-drives partner cases the push path never delivered.
 *
 * <p><b>Why this exists at all.</b> Push is a single attempt from our side. The intake
 * answers 200 on every branch — it must, because TSANet treats 4xx as permanent and
 * never retries it — and the event drain catches internally so it never re-offers. Every
 * failure between those two is therefore permanent, and
 * {@link InboundRequestHandler}'s guarantee was "recoverable, not recovered". This is
 * what makes it literal.
 *
 * <p><b>Why it calls the handler directly instead of going through the EventQueue.</b>
 * The queue is bounded and {@code offer()} DROPS ON FULL, so a cycle that found fifty
 * missed cases could evict live webhook events — the poller would cause exactly the loss
 * it exists to repair. It is also a single drain thread shared with live intake, so a
 * batch would sit in front of real-time traffic. Calling
 * {@link InboundRequestHandler#handle} directly is safe by construction: {@code record()}
 * arbitrates between this thread and the drain exactly as it arbitrates between two
 * deliveries, and the loser no-ops.
 *
 * <p><b>Why one guard-create path and not a second.</b> A create sequence here would be
 * a second place to get claim-release-on-failure wrong. The prefilter below means the
 * handler's own {@code fetchByToken} only fires for genuinely missed cases — rare by
 * definition — so declining to use the full case objects the list already returned costs
 * nothing in steady state and keeps one implementation of the rule.
 *
 * <p><b>Why the cursor only advances on a clean cycle.</b> Advancing past a case that
 * failed to become a ticket would lose it permanently, which is the single most
 * consequential bug available here. So any failure keeps the cursor and the whole window
 * is retried next cycle. A wedged cursor degrades safely: new cases still arrive every
 * cycle because the window extends to now, and the cost is a widening query plus loud
 * logs rather than lost work. There is deliberately no partial advance to "the newest
 * case handled" — that is only sound if results are ordered by {@code updatedAt}, which
 * is not specified.
 *
 * <p>Single-instance only. Two containers would race the cursor; they could not create
 * duplicate tickets (the token's unique constraint still arbitrates) but they would
 * duplicate the work and fight over the mark.
 */
@Component
public class ReconciliationPoller {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationPoller.class);

    private final TsanetGateway tsanet;
    private final InboundRequestHandler handler;
    private final SubmissionLinkTable links;
    private final PollerStateStore state;
    private final InboundProperties properties;

    /**
     * Volatile because the accessor below is public: the counter is only ever WRITTEN
     * from the scheduler thread (fixedDelay guarantees no overlapping cycle), but a
     * health surface reading it from another thread has no visibility guarantee without
     * this. One keyword removes the hazard; a comment saying "only read from the
     * scheduler thread" would be a rule nothing enforces.
     */
    private volatile int consecutiveFailures;

    public ReconciliationPoller(TsanetGateway tsanet, InboundRequestHandler handler,
            SubmissionLinkTable links, PollerStateStore state, InboundProperties properties) {
        this.tsanet = tsanet;
        this.handler = handler;
        this.links = links;
        this.state = state;
        this.properties = properties;
    }

    /**
     * {@code fixedDelay}, never {@code fixedRate}: the delay is measured from the END of
     * the previous run, so two cycles can never overlap. That is what makes locking the
     * cursor unnecessary — under fixedRate a slow cycle would be re-entered while still
     * running, and two threads would advance the same mark.
     */
    @Scheduled(fixedDelayString = "${connector.inbound.reconcile-interval-ms:300000}",
            initialDelayString = "${connector.inbound.reconcile-initial-delay-ms:60000}")
    public void cycle() {
        if (!properties.reconcileEnabled()) {
            return;
        }
        if (properties.collaborationTicketTypeId() == null
                || properties.collaborationTicketTypeId().isBlank()) {
            // Gated here as well as in the handler: without this the poller would emit
            // one error line PER CASE, every cycle, forever. One line per cycle naming
            // the actual cause is the difference between a signal and a flood.
            log.error("tsanet_audit event=reconcile_unconfigured reason=no_ticket_type_id");
            return;
        }

        Instant lookbackFloor = Instant.now().minus(properties.reconcileLookback());
        Instant cursor = state.cursor()
                .map(c -> c.minus(properties.reconcileOverlap()))
                // No stored cursor: a bounded window, never epoch. The endpoint has no
                // pagination, so the window size is the only load control there is.
                .orElse(lookbackFloor);

        List<InboundCase> cases;
        try {
            cases = tsanet.listInboundUpdatedSince(cursor);
        } catch (RuntimeException e) {
            // Definitive and ambiguous collapse to the same action here, and that is
            // correct rather than lazy: the list is idempotent, so both mean "retry the
            // same window next cycle". Building a skip-list would add a way to lose a
            // case in exchange for nothing.
            consecutiveFailures++;
            log.error("tsanet_audit event=reconcile_cycle_failed cursor={} consecutive={} reason={}",
                    cursor, consecutiveFailures, e.getMessage());
            return;
        }

        int created = 0;
        int alreadyLinked = 0;
        int failed = 0;
        Instant high = null;
        for (InboundCase c : cases) {
            if (c.token() == null || c.token().isBlank()) {
                continue; // the gateway already logged it; unroutable either way
            }
            if (links.byToken(c.token()).isPresent()) {
                // The common case by a wide margin: push already handled it. Skipping
                // here is what makes the overlap window free.
                alreadyLinked++;
                high = newest(high, c);
                continue;
            }
            try {
                handler.handle(synthetic(c));
            } catch (RuntimeException e) {
                failed++;
                log.error("tsanet_audit event=reconcile_case_failed token={} reason={}",
                        c.token(), e.getMessage());
                continue;
            }
            // OUTCOME, not absence-of-exception. InboundRequestHandler catches its own
            // create failures — it has to, because the event drain shares it and an
            // escaping exception would kill that thread — so a failed create returns
            // NORMALLY. Judging success by "handle() did not throw" therefore reports a
            // clean cycle for a case that never became a ticket, and the cursor steps
            // over it permanently. The link table is the only honest witness.
            //
            // Found by the test below rather than by reading the handler: the first
            // version of this loop counted a swallowed create failure as success.
            if (links.byToken(c.token()).isPresent()) {
                created++;
                log.info("tsanet_audit event=reconcile_recovered token={} partner={}",
                        c.token(), c.partnerCompany());
                high = newest(high, c);
            } else {
                // Every case reaching here was INBOUND (filtered server-side and
                // re-checked), so "still unlinked" is not a legitimate decline — it is a
                // create that did not happen. Holding the cursor risks wedging on a case
                // that has genuinely vanished server-side; that is the accepted trade,
                // because a wedge is loud and recoverable while a skip is silent and
                // permanent.
                failed++;
                log.error("tsanet_audit event=reconcile_case_unlinked token={}"
                        + " reason=handler_declined_or_failed", c.token());
            }
        }

        if (failed > 0) {
            consecutiveFailures++;
            log.error("tsanet_audit event=reconcile_cycle_dirty listed={} created={} skipped={}"
                            + " failed={} cursor_held={} consecutive={}",
                    cases.size(), created, alreadyLinked, failed, cursor, consecutiveFailures);
            return;
        }

        consecutiveFailures = 0;
        // Advance only to what was actually observed. On an empty window there is
        // nothing to advance TO — moving the mark to "now" would step over any case
        // whose updatedAt lands between the query and this line.
        if (high != null) {
            state.cursor(high);
        }
        log.info("tsanet_audit event=reconcile_cycle listed={} created={} skipped={} failed=0 cursor={}",
                cases.size(), created, alreadyLinked, high == null ? cursor : high);
    }

    /** How many cycles have failed in a row; 0 after any clean cycle. */
    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * The event the push path would have delivered. Built here rather than reusing a
     * webhook payload because there is no payload — the whole point is that none
     * arrived.
     */
    private static InboundEvent synthetic(InboundCase c) {
        return new InboundEvent(InboundEvent.Source.TSANET,
                TsanetCloudEvent.Type.REQUEST_CREATED.wireValue(),
                c.token(), Instant.now(), Map.of("source", "reconcile"));
    }

    /**
     * The newest {@code updatedAt} seen. Absent on a case means it cannot raise the
     * mark — a cursor must never advance past something whose position is unknown.
     */
    private static Instant newest(Instant current, InboundCase c) {
        Instant at = c.updatedAt();
        if (at == null) {
            return current;
        }
        return current == null || at.isAfter(current) ? at : current;
    }
}
