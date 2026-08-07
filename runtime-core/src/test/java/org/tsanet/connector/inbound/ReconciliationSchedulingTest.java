package org.tsanet.connector.inbound;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;

/**
 * That the poller is actually SCHEDULED, which no other test can tell you.
 *
 * <p>This repo had no scheduling at all before the poller, so {@code @EnableScheduling}
 * had to be added to {@code ConnectorApplication}. Forgetting it is the perfect silent
 * failure: {@code @Scheduled} becomes inert, the bean is still created and injected, the
 * context starts clean, every unit test of {@code cycle()} still passes — and the
 * connector simply never reconciles anything, forever. Nothing else in the suite would
 * notice, because everything else calls {@code cycle()} directly.
 *
 * <p>Asserted through {@link ScheduledTaskHolder} rather than by waiting for a tick: a
 * timing-based test would either be slow or flaky, and registration is the property that
 * actually matters. If the annotation is not honoured, no task exists to find.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "connector.tsanet.mode=fake",
        "connector.intercom.mode=fake",
        "connector.links.store=memory",
        // Long enough that no cycle actually runs during the test; this asserts
        // REGISTRATION, and a firing cycle would just add noise and a partner call.
        "connector.inbound.reconcile-interval-ms=3600000",
        "connector.inbound.reconcile-initial-delay-ms=3600000",
})
class ReconciliationSchedulingTest {

    // required=false on purpose: with @EnableScheduling absent there is no
    // ScheduledTaskHolder bean AT ALL, and a required injection would fail the test as
    // an autowiring error whose message says nothing about reconciliation. Optional
    // injection lets the assertion below deliver the actual diagnosis.
    @Autowired(required = false)
    private Set<ScheduledTaskHolder> holders;

    @Test
    void thePollerIsRegisteredAsAScheduledTask() {
        boolean registered = holders != null && holders.stream()
                .flatMap(h -> h.getScheduledTasks().stream())
                .map(ScheduledTask::getTask)
                .map(t -> String.valueOf(t.getRunnable()))
                .anyMatch(s -> s.contains(ReconciliationPoller.class.getName())
                        && s.contains("cycle"));
        assertTrue(registered,
                "ReconciliationPoller.cycle() is not registered as a scheduled task — either"
                        + " @EnableScheduling is missing from ConnectorApplication or the"
                        + " @Scheduled annotation was removed. The connector would start"
                        + " healthy and never reconcile anything.");
    }
}
