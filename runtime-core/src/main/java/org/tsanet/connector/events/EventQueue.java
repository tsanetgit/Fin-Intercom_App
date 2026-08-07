package org.tsanet.connector.events;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.tsanet.connector.config.ConnectorProperties;

/**
 * Fast-ack handoff between intake (webhooks, deferred connector work) and processing.
 * The drain offers each event to the first {@link InboundEventHandler} bean that
 * claims it; unclaimed events keep the skeleton's log line. One drain thread on
 * purpose — handlers make bounded blocking calls and pilot volume is small, so
 * webhook events queue behind an in-flight handler (docs/decisions.md D10). The
 * reconciliation poller is the backstop regardless (D4, D16) — and it deliberately
 * does NOT enqueue here: offer() drops on full, so a large recovery batch could
 * evict the very live events this queue exists to carry.
 */
@Component
public class EventQueue implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(EventQueue.class);

    private final LinkedBlockingQueue<InboundEvent> queue;
    // ObjectProvider, resolved lazily on first dispatch: handlers (the annotation
    // pipeline) legitimately depend on this queue to re-offer retries, so an eager
    // handler-list injection here would be a constructor cycle.
    private final ObjectProvider<InboundEventHandler> handlerProvider;
    private volatile List<InboundEventHandler> handlers;
    private Thread drain;

    public EventQueue(ConnectorProperties properties, ObjectProvider<InboundEventHandler> handlerProvider) {
        this.queue = new LinkedBlockingQueue<>(properties.queueCapacity());
        this.handlerProvider = handlerProvider;
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
                    dispatch(e);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }, "event-drain");
        drain.setDaemon(true);
        drain.start();
    }

    /** All singletons exist here, so resolving cannot re-enter bean creation. */
    @Override
    public void afterSingletonsInstantiated() {
        handlers = handlerProvider.orderedStream().toList();
    }

    private void dispatch(InboundEvent e) {
        List<InboundEventHandler> resolved = handlers;
        if (resolved == null) {
            // Non-Spring construction (tests): resolve lazily, but never cache an
            // empty answer — a transiently-empty provider must not silence handlers
            // forever (QA round-2).
            resolved = handlerProvider.orderedStream().toList();
            if (!resolved.isEmpty()) {
                handlers = resolved;
            }
        }
        for (InboundEventHandler handler : resolved) {
            if (handler.handles(e)) {
                try {
                    handler.handle(e);
                } catch (RuntimeException unexpected) {
                    // Contract violation by the handler; the drain must survive it.
                    log.error("handler {} threw for {} {} id={}", handler.getClass().getSimpleName(),
                            e.source(), e.kind(), e.id(), unexpected);
                }
                return;
            }
        }
        log.info("event {} {} id={} keys={}", e.source(), e.kind(), e.id(), e.payload().keySet());
    }

    @PreDestroy
    void stop() {
        if (drain != null) {
            drain.interrupt();
        }
    }
}
