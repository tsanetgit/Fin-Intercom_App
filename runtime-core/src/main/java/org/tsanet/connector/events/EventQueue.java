package org.tsanet.connector.events;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tsanet.connector.config.ConnectorProperties;

/**
 * Fast-ack handoff between webhook intake and (future) processing. The drain worker
 * only logs in the skeleton; real handlers arrive with Phase 2, and the reconciliation
 * poller remains the source of truth regardless (docs/decisions.md D4).
 */
@Component
public class EventQueue {

    private static final Logger log = LoggerFactory.getLogger(EventQueue.class);

    private final LinkedBlockingQueue<InboundEvent> queue;
    private Thread drain;

    public EventQueue(ConnectorProperties properties) {
        this.queue = new LinkedBlockingQueue<>(properties.queueCapacity());
    }

    public boolean offer(InboundEvent event) {
        boolean accepted = queue.offer(event);
        if (!accepted) {
            log.warn("event queue full; dropped {} {} {}", event.source(), event.kind(), event.id());
        }
        return accepted;
    }

    public int size() {
        return queue.size();
    }

    @PostConstruct
    void start() {
        drain = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    InboundEvent e = queue.take();
                    log.info("event {} {} id={} keys={}", e.source(), e.kind(), e.id(), e.payload().keySet());
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }, "event-drain");
        drain.setDaemon(true);
        drain.start();
    }

    @PreDestroy
    void stop() {
        if (drain != null) {
            drain.interrupt();
        }
    }
}
