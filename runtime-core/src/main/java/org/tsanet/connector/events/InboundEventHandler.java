package org.tsanet.connector.events;

/**
 * A drain-side consumer for queued events. Handlers are Spring beans; the queue's
 * drain thread offers each event to the first handler that claims it and falls back
 * to the skeleton's log line for the rest. This is the seam Phase 2's inbound
 * processing plugs into (docs/decisions.md D10); slice 3 uses it for the outbound
 * annotation pipeline.
 *
 * <p>Contract: {@link #handle} runs on the single drain thread — it may block briefly
 * (bounded HTTP), must never throw for expected failures (log and re-offer instead),
 * and must be idempotent per event id (D4: replay is always possible).
 */
public interface InboundEventHandler {

    boolean handles(InboundEvent event);

    void handle(InboundEvent event);
}
