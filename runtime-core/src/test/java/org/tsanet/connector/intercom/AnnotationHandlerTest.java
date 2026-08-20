package org.tsanet.connector.intercom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.tsanet.connector.config.ConnectorProperties;
import org.tsanet.connector.events.EventQueue;
import org.tsanet.connector.events.InboundEvent;
import org.tsanet.connector.events.InboundEventHandler;

/**
 * Handler semantics without Spring: idempotency by annotation state, scheduled
 * re-offer with backoff up to 3 attempts, loud link-missing, and terminal
 * annotation_failed after exhaustion. The queue here has no drain (no @PostConstruct
 * outside Spring), so re-offered events accumulate observably.
 */
class AnnotationHandlerTest {

    private static EventQueue bareQueue() {
        ObjectProvider<InboundEventHandler> none = new ObjectProvider<>() {
            @Override
            public InboundEventHandler getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InboundEventHandler getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public InboundEventHandler getIfAvailable() {
                return null;
            }

            @Override
            public InboundEventHandler getIfUnique() {
                return null;
            }

            @Override
            public Stream<InboundEventHandler> stream() {
                return Stream.empty();
            }

            @Override
            public Stream<InboundEventHandler> orderedStream() {
                return Stream.empty();
            }
        };
        return new EventQueue(new ConnectorProperties(null, null, 0, 0, 0, null, null, false), none);
    }

    private static InboundEvent event(String token, int attempt) {
        return new InboundEvent(InboundEvent.Source.CONNECTOR, AnnotationHandler.KIND, token,
                Instant.now(), Map.of("token", token, "attempt", attempt));
    }

    private static SubmissionLink link(String token, String partner) {
        return new SubmissionLink(token, "flow-" + token, "w1", "conv-1", "t-1", partner,
                "2026-08-06T12:00:00Z", true, "agent@tsanet.org", Instant.now());
    }

    private final FakeIntercomGateway intercom = new FakeIntercomGateway(new MockEnvironment());
    private final InMemorySubmissionLinkTable table = new InMemorySubmissionLinkTable();
    private final EventQueue queue = bareQueue();

    @Test
    void successMarksFullAndNeverRetries() {
        AnnotationHandler handler = new AnnotationHandler(queue, table, intercom, new long[] {10, 10});
        table.record(link("TSA-ok", "Nice Partner"));
        handler.handle(event("TSA-ok", 1));
        assertEquals(AnnotationState.FULL, table.annotationState("TSA-ok"));
        assertEquals(0, queue.size());
        handler.handle(event("TSA-ok", 1));
        assertEquals(1, intercom.attempts("TSA-ok"), "an already-annotated link must be skipped");
    }

    @Test
    void degradedOutcomeIsRecordedAsDegraded() {
        AnnotationHandler handler = new AnnotationHandler(queue, table, intercom, new long[] {10, 10});
        table.record(link("TSA-deg", "intercom-degrade Co"));
        handler.handle(event("TSA-deg", 1));
        assertEquals(AnnotationState.DEGRADED, table.annotationState("TSA-deg"),
                "a boolean would report the degraded write as done and hide the format signal");
    }

    @Test
    void transientFailureReOffersWithNextAttempt() throws Exception {
        AnnotationHandler handler = new AnnotationHandler(queue, table, intercom, new long[] {30, 30});
        table.record(link("TSA-flaky", "intercom-flaky Co"));
        handler.handle(event("TSA-flaky", 1));
        assertEquals(AnnotationState.NONE, table.annotationState("TSA-flaky"));
        long deadline = System.currentTimeMillis() + 3_000;
        while (queue.size() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, queue.size(), "the failed attempt must schedule a re-offer");
        handler.handle(event("TSA-flaky", 2));
        assertEquals(AnnotationState.FULL, table.annotationState("TSA-flaky"));
        assertEquals(2, intercom.attempts("TSA-flaky"));
    }

    @Test
    void exhaustionStopsRetryingAndLeavesStateNone() throws Exception {
        AnnotationHandler handler = new AnnotationHandler(queue, table, intercom, new long[] {10, 10});
        table.record(link("TSA-dead", "intercom-fail Co"));
        handler.handle(event("TSA-dead", 3));
        Thread.sleep(200);
        assertEquals(0, queue.size(), "attempt 3 is terminal: audit annotation_failed, no re-offer");
        assertEquals(AnnotationState.NONE, table.annotationState("TSA-dead"),
                "NONE + the audit line is the manual re-drive signal");
    }

    @Test
    void missingLinkIsLoudButHarmless() {
        AnnotationHandler handler = new AnnotationHandler(queue, table, intercom, new long[] {10, 10});
        handler.handle(event("TSA-ghost", 1));
        assertEquals(0, queue.size());
        assertTrue(intercom.annotated().isEmpty());
    }
}
