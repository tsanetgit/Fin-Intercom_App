package org.tsanet.connector.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rings behind /admin/logs/tail and /admin/audit/tail (docs/decisions.md D20). */
class RingBufferLogAppenderTest {

    private record Rig(Logger logger, RingBufferLogAppender ring) {
    }

    private static Rig rig(int lineCapacity, int auditCapacity) {
        LoggerContext context = new LoggerContext();
        RingBufferLogAppender ring = new RingBufferLogAppender(lineCapacity, auditCapacity);
        ring.setContext(context);
        ring.start();
        Logger logger = context.getLogger("ring.probe");
        logger.addAppender(ring);
        return new Rig(logger, ring);
    }

    @Test
    void theRingEvictsOldestFirstAndTailsInOrder() {
        Rig r = rig(3, 2);
        for (int i = 1; i <= 4; i++) {
            r.logger().info("line {}", i);
        }
        List<String> tail = r.ring().tailLines(10);
        assertEquals(3, tail.size(), "capacity 3 after 4 lines");
        assertTrue(tail.get(0).endsWith("line 2"), "oldest surviving line first: " + tail.get(0));
        assertTrue(tail.get(2).endsWith("line 4"), "newest last: " + tail.get(2));
        assertEquals(1, r.ring().tailLines(1).size());
        assertTrue(r.ring().tailLines(1).get(0).endsWith("line 4"), "tail(1) is the newest line");
    }

    @Test
    void onlyTheAuditConventionReachesTheAuditRing() {
        Rig r = rig(10, 10);
        r.logger().info("plain line");
        r.logger().error("tsanet_audit event=probe outcome=ok");
        r.logger().info("mentions tsanet_audit mid-line, which is not the convention");
        List<String> audit = r.ring().tailAudit(10);
        assertEquals(1, audit.size(), "prefix match, not substring: " + audit);
        assertTrue(audit.get(0).contains("tsanet_audit event=probe"));
        assertEquals(3, r.ring().tailLines(10).size(), "the general ring keeps everything");
    }

    @Test
    void formattedMessagesCarryTheirArguments() {
        Rig r = rig(5, 5);
        r.logger().info("token {} landed in {}ms", "abc123", 42);
        assertTrue(r.ring().tailLines(1).get(0).endsWith("token abc123 landed in 42ms"));
    }
}
