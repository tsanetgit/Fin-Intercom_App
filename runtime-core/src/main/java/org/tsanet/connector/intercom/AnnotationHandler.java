package org.tsanet.connector.intercom;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tsanet.connector.events.EventQueue;
import org.tsanet.connector.events.InboundEvent;
import org.tsanet.connector.events.InboundEventHandler;

/**
 * Drains "annotate_submission" events: looks up the link, writes the annotation
 * through the Intercom gateway, records how far it got. Idempotent per token — a
 * link whose annotation state is already set is skipped, which is also what makes
 * a duplicated event or a replayed note harmless (docs/decisions.md D4/D10).
 *
 * <p>Failures re-offer the event with backoff (5s, then 20s) up to 3 attempts, via a
 * scheduler so the drain thread never sleeps; re-offers go straight to the queue and
 * therefore bypass webhook-intake dedupe by construction. After the last attempt the
 * failure is audited loudly — the link table plus the audit line are the manual
 * re-drive path for annotation. The reconciliation poller (Phase 2) recovers missing
 * inbound TICKETS, not missing annotations — a link that exists but was never
 * annotated is outside its prefilter, so this remains the gap it does not close.
 */
@Component
public class AnnotationHandler implements InboundEventHandler {

    public static final String KIND = "annotate_submission";

    private static final Logger log = LoggerFactory.getLogger(AnnotationHandler.class);
    private static final int MAX_ATTEMPTS = 3;

    private final EventQueue queue;
    private final SubmissionLinkTable links;
    private final IntercomGateway intercom;
    private final long[] retryDelaysMillis;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "annotation-retry");
        t.setDaemon(true);
        return t;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public AnnotationHandler(EventQueue queue, SubmissionLinkTable links, IntercomGateway intercom) {
        this(queue, links, intercom, new long[] {5_000, 20_000});
    }

    AnnotationHandler(EventQueue queue, SubmissionLinkTable links, IntercomGateway intercom,
            long[] retryDelaysMillis) {
        this.queue = queue;
        this.links = links;
        this.intercom = intercom;
        this.retryDelaysMillis = retryDelaysMillis.clone();
    }

    @Override
    public boolean handles(InboundEvent event) {
        return event.source() == InboundEvent.Source.CONNECTOR && KIND.equals(event.kind());
    }

    @Override
    public void handle(InboundEvent event) {
        String token = String.valueOf(event.payload().get("token"));
        int attempt = event.payload().get("attempt") instanceof Number n ? n.intValue() : 1;
        Optional<SubmissionLink> found = links.byToken(token);
        if (found.isEmpty()) {
            // A restart between landing and draining loses the in-memory link; say so
            // loudly, never skip silently (the token in the log is the re-drive key).
            log.error("tsanet_audit event=link_missing token={} attempt={}", token, attempt);
            return;
        }
        SubmissionLink link = found.get();
        if (links.annotationState(token) != AnnotationState.NONE) {
            return; // already annotated; replay or duplicate event
        }
        try {
            IntercomGateway.AnnotationOutcome outcome = intercom.annotate(link);
            AnnotationState state = outcome == IntercomGateway.AnnotationOutcome.FULL
                    ? AnnotationState.FULL
                    : AnnotationState.DEGRADED;
            links.annotationState(token, state);
            log.info("tsanet_audit event={} token={} workspace={} conversation={} ticket={} attempt={}",
                    state == AnnotationState.FULL ? "annotation_done" : "annotation_degraded",
                    token, link.workspaceId(), link.conversationId(), link.ticketId(), attempt);
        } catch (RuntimeException failure) {
            if (attempt < MAX_ATTEMPTS) {
                long delay = retryDelaysMillis[Math.min(attempt - 1, retryDelaysMillis.length - 1)];
                log.warn("tsanet_audit event=annotation_retry token={} attempt={} next_in_ms={} reason={}",
                        token, attempt, delay, failure.getMessage());
                int next = attempt + 1;
                scheduler.schedule(() -> {
                    boolean queued = queue.offer(new InboundEvent(
                            InboundEvent.Source.CONNECTOR, KIND, token, Instant.now(),
                            Map.of("token", token, "attempt", next)));
                    if (!queued) {
                        log.error("tsanet_audit event=annotation_enqueue_failed token={} attempt={}",
                                token, next);
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } else {
                log.error("tsanet_audit event=annotation_failed token={} workspace={} conversation={}"
                                + " ticket={} attempts={} reason={}",
                        token, link.workspaceId(), link.conversationId(), link.ticketId(),
                        attempt, failure.getMessage());
            }
        }
    }

    @PreDestroy
    void stop() {
        int dropped = scheduler.shutdownNow().size();
        if (dropped > 0) {
            // Token-level identity is in the preceding annotation_retry lines; this
            // makes the loss itself visible instead of reading as still in flight.
            log.warn("tsanet_audit event=annotation_retries_dropped count={} reason=shutdown", dropped);
        }
    }
}
