package org.tsanet.connector.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tsanet.connector.events.InboundEvent;
import org.tsanet.connector.intercom.SubmissionLink;
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

    /**
     * Consecutive completed listings that observed at least one unanchored claim.
     * Distinct from {@link #consecutiveFailures} on purpose: a held cursor is not a
     * failure, but a claim that stays unanchored cycle after cycle is an orphan (a
     * crash between record() and anchor()) that will never self-heal — and
     * consecutiveFailures stays 0 the whole time, so an alert keyed on it never fires.
     * This counter is the alert surface (#26: surfacing was ruled over automatic
     * reclaim). Volatile for the same reason as consecutiveFailures.
     */
    private volatile int consecutiveHeld;

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
        int skippedTerminal = 0;
        int inFlight = 0;
        int failed = 0;
        Instant high = null;
        Instant oldestClaim = null;
        for (InboundCase c : cases) {
            if (c.token() == null || c.token().isBlank()) {
                continue; // the gateway already logged it; unroutable either way
            }
            if (c.terminal()) {
                // BEFORE the claim lookup on purpose (#28): a terminal case advances the
                // cursor even when an orphaned claim exists for it — a crash between
                // record() and anchor() followed by the partner closing. Nothing will
                // ever anchor that claim and no create should happen, so treating it as
                // in-flight would hold the cursor permanently. The handler carries the
                // same guard for the push path; without THIS one, every partner-closed
                // unlinked case would count as failed and wedge the cursor forever —
                // the outcome check below reads no-link-after-handle as a failed create.
                //
                // The one surface such an orphan gets: once the cursor passes, the case
                // leaves the window and the stale claim row goes invisible, so it is
                // named here while still listed (#26's held counter never sees it —
                // this branch deliberately skips before inFlight is counted).
                links.byToken(c.token())
                        .filter(l -> !l.ticketAnchored())
                        .ifPresent(l -> log.warn("tsanet_audit event=reconcile_orphaned_claim_terminal"
                                        + " token={} status={} claim_age_s={} action=release_by_hand",
                                c.token(), c.status(), claimAgeSeconds(l.submittedAt())));
                skippedTerminal++;
                high = newest(high, c);
                continue;
            }
            Optional<SubmissionLink> existing = links.byToken(c.token());
            if (existing.map(SubmissionLink::ticketAnchored).orElse(false)) {
                // The common case by a wide margin: push already handled it and the
                // ticket exists. Skipping here is what makes the overlap window free.
                alreadyLinked++;
                high = newest(high, c);
                continue;
            }
            if (existing.isPresent()) {
                // A row with NO anchor is a claim in flight, not a finished ticket —
                // another thread is between record() and anchor() right now. It is not
                // a failure, so no error noise, but the cursor MUST NOT advance past it:
                // if that create then fails and releases the claim, the case would fall
                // outside the window with no link and never be seen again.
                inFlight++;
                oldestClaim = older(oldestClaim, existing.get().submittedAt());
                log.info("tsanet_audit event=reconcile_in_flight token={}", c.token());
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
            // ANCHORED, not merely present. byToken() is true for a bare claim too, and
            // this handler returns normally via inbound_already_claimed when it LOSES the
            // record() race to the live drain. Counting that as success let the cursor
            // advance past a case whose create then failed and released the claim —
            // producing exactly the permanent loss D16 exists to prevent, by way of the
            // invariant it forbids (#22 QA).
            Optional<SubmissionLink> after = links.byToken(c.token());
            if (after.map(SubmissionLink::ticketAnchored).orElse(false)) {
                created++;
                log.info("tsanet_audit event=reconcile_recovered token={} partner={}",
                        c.token(), c.partnerCompany());
                high = newest(high, c);
            } else if (after.isPresent()) {
                // Claimed by someone else mid-cycle. In flight, so hold the cursor
                // without calling it a failure.
                inFlight++;
                oldestClaim = older(oldestClaim, after.get().submittedAt());
                log.info("tsanet_audit event=reconcile_in_flight token={}", c.token());
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

        // One place, keyed on what the completed listing OBSERVED (#26): the counter
        // measures claim staleness, not cycle health. A dirty cycle that observed no
        // claim still resets it; a failed LIST observed nothing and left it unchanged
        // by returning above. That includes the cursor-persist-failure exit below —
        // deliberate, see D16.
        consecutiveHeld = inFlight > 0 ? consecutiveHeld + 1 : 0;

        if (failed > 0) {
            consecutiveFailures++;
            log.error("tsanet_audit event=reconcile_cycle_dirty listed={} created={} skipped={}"
                            + " skipped_terminal={} in_flight={} failed={} cursor_held={} consecutive={}",
                    cases.size(), created, alreadyLinked, skippedTerminal, inFlight, failed, cursor,
                    consecutiveFailures);
            return;
        }
        if (inFlight > 0) {
            // A HOLD, not a failure, and it gets its own event at warn rather than
            // sharing the dirty-cycle line. The dirty line prints `consecutive=`, which
            // this branch never touches — reporting a failure counter for something that
            // is not a failure is the kind of mismatch that gets alerted on and then
            // ignored. D16 says "not a failure, no error noise"; this is what makes the
            // code say it too (#22 QA round 2).
            //
            // NOTE: an orphaned claim (a crash between record() and anchor()) is
            // permanent, so this branch then repeats every cycle and the cursor never
            // advances. Loud, bounded, and recoverable by hand — held_cycles and the
            // oldest claim's age are the alert surface for exactly that (#26; a live
            // create resolves within its gateway deadline, so an age far past it
            // means orphan). See D16.
            log.warn("tsanet_audit event=reconcile_cycle_held listed={} created={} skipped={}"
                            + " skipped_terminal={} in_flight={} cursor_held={} held_cycles={}"
                            + " oldest_claim_age_s={}",
                    cases.size(), created, alreadyLinked, skippedTerminal, inFlight, cursor,
                    consecutiveHeld, claimAgeSeconds(oldestClaim));
            return;
        }

        // PERSIST BEFORE DECLARING SUCCESS. SqlitePollerStateStore.cursor(Instant) throws
        // on a SQL failure by design — it refuses to hide a broken volume (D12). The
        // first version reset consecutiveFailures and then persisted OUTSIDE any try, so
        // on a broken volume every cycle reported a healthy counter, never reached the
        // clean audit line, and let the exception escape into Spring's scheduler handler
        // in non-audit format. The store promised loudness and the caller undid it, in
        // exactly the D12 failure mode this is supposed to cover (#22 QA).
        //
        // Advance only to what was actually observed. On an empty window there is
        // nothing to advance TO — moving the mark to "now" would step over any case
        // whose updatedAt lands between the query and this line.
        if (high != null) {
            try {
                state.cursor(high);
            } catch (RuntimeException e) {
                consecutiveFailures++;
                log.error("tsanet_audit event=reconcile_cycle_failed stage=cursor_persist"
                                + " cursor={} attempted={} consecutive={} reason={}",
                        cursor, high, consecutiveFailures, e.getMessage());
                return;
            }
        }
        // Reset only after the mark is durable: a counter cleared ahead of the write
        // reports health the cycle has not earned.
        consecutiveFailures = 0;
        log.info("tsanet_audit event=reconcile_cycle listed={} created={} skipped={}"
                        + " skipped_terminal={} in_flight=0 failed=0 cursor={}",
                cases.size(), created, alreadyLinked, skippedTerminal,
                high == null ? cursor : high);
    }

    /** How many cycles have failed in a row; 0 after any clean cycle. */
    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * Consecutive completed listings that observed an unanchored claim; 0 after one
     * that observed none. Climbing while {@link #consecutiveFailures} sits at 0 is the
     * orphaned-claim signature (#26).
     */
    public int consecutiveHeld() {
        return consecutiveHeld;
    }

    /** The oldest claim timestamp seen this cycle; a null submittedAt cannot age a claim. */
    private static Instant older(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    /** Age in seconds of the oldest observed claim, or -1 when none carried a timestamp. */
    private static long claimAgeSeconds(Instant oldestClaim) {
        return oldestClaim == null ? -1 : java.time.Duration.between(oldestClaim, Instant.now()).getSeconds();
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
