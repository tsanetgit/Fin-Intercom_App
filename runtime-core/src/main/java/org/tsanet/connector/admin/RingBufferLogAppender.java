package org.tsanet.connector.admin;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded in-memory tails for /admin/logs/tail and /admin/audit/tail (docs/decisions.md
 * D20). Two rings off one appender: every formatted line lands in the general ring, and
 * lines whose raw message carries the {@code tsanet_audit} convention land in the audit
 * ring as well. The convention IS the audit surface — audit lines are emitted through
 * each class's own logger, so there is no single logger name to attach to; keying on
 * the message prefix follows the same convention the log-based alerting already reads.
 *
 * <p>The rings do not survive a restart and are not a substitute for platform log
 * retention; they exist so an operator can read the recent past without a cloud CLI.
 */
public final class RingBufferLogAppender extends AppenderBase<ILoggingEvent> {

    private final int lineCapacity;
    private final int auditCapacity;
    private final ArrayDeque<String> lines;
    private final ArrayDeque<String> audit;

    public RingBufferLogAppender(int lineCapacity, int auditCapacity) {
        if (lineCapacity < 1 || auditCapacity < 1) {
            throw new IllegalArgumentException("ring capacities must be positive");
        }
        this.lineCapacity = lineCapacity;
        this.auditCapacity = auditCapacity;
        this.lines = new ArrayDeque<>(lineCapacity);
        this.audit = new ArrayDeque<>(auditCapacity);
    }

    @Override
    protected synchronized void append(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        String line = Instant.ofEpochMilli(event.getTimeStamp()) + " " + event.getLevel() + " "
                + event.getLoggerName() + " - " + message;
        push(lines, line, lineCapacity);
        if (message != null && message.startsWith("tsanet_audit")) {
            push(audit, line, auditCapacity);
        }
    }

    private static void push(ArrayDeque<String> ring, String line, int capacity) {
        if (ring.size() == capacity) {
            ring.removeFirst();
        }
        ring.addLast(line);
    }

    /** The most recent {@code n} lines, oldest first. */
    public synchronized List<String> tailLines(int n) {
        return tail(lines, n);
    }

    /** The most recent {@code n} audit lines, oldest first. */
    public synchronized List<String> tailAudit(int n) {
        return tail(audit, n);
    }

    private static List<String> tail(ArrayDeque<String> ring, int n) {
        List<String> all = new ArrayList<>(ring);
        return List.copyOf(all.subList(Math.max(0, all.size() - n), all.size()));
    }

    public int lineCapacity() {
        return lineCapacity;
    }

    public int auditCapacity() {
        return auditCapacity;
    }
}
