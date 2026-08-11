package org.tsanet.connector.inbound;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Workspace-specific configuration for inbound cases.
 *
 * <p>{@code collaborationTicketTypeId} is the Back-office ticket type that carries the
 * TSANet attributes (token, partner, respond-by). It is per-workspace — the dev
 * workspace's id is not the pilot's — so it has no default: an unset value refuses to
 * create rather than creating tickets of the wrong type, because the type is what
 * every later slice reads attributes from.
 *
 * <p>The reconcile settings govern {@link ReconciliationPoller}. Each default is chosen
 * against a specific failure rather than picked for roundness:
 * <ul>
 * <li><b>lookback 24h</b> — the window a first run (or one with an unreadable cursor)
 *     asks for. The list endpoint has NO pagination, so this is the only load control
 *     that exists; epoch would ask for every case ever. 24h also matches the respondBy
 *     deadline, so a case that could still be answered is inside it.</li>
 * <li><b>overlap 120s</b> — subtracted from the stored cursor on every query. Absorbs
 *     the mixed granularity of {@code updatedAt} (creation writes sub-second, the
 *     server's SLA sweep writes whole seconds, both observed on BETA) and the
 *     unspecified inclusive/exclusive boundary of the filter. Overlap is free: a
 *     re-listed case is skipped by the link-table prefilter. Missing one is not.</li>
 * </ul>
 *
 * <p>{@code resolvedStateId} is the Intercom ticket-state id a closed partner case moves
 * to (docs/decisions.md D17). Like the ticket type it is PER-WORKSPACE and has no
 * default — and unlike most ids it cannot even be looked up conveniently: the only way
 * found to enumerate state ids is {@code Intercom-Version: Unstable}, so it is resolved
 * out of band and configured. Unset means closure REFUSES rather than guessing, because
 * a wrong id may not 404 — it may name a different state that happens to exist.
 *
 * <p>{@code inProgressStateId} and {@code waitingStateId} are the same species of id
 * for the two NON-terminal targets in D17's mapping — In progress (a partner accepted)
 * and Waiting on customer (a partner asked for more information). Response surfacing
 * checks each id AT THE POINT OF MAPPING, so an unset id disables only the statuses
 * that need it, and each refusal is its own error-level audit line.
 */
@ConfigurationProperties(prefix = "connector.inbound")
public record InboundProperties(
        String collaborationTicketTypeId,
        Boolean reconcileEnabled,
        Duration reconcileLookback,
        Duration reconcileOverlap,
        String resolvedStateId,
        String inProgressStateId,
        String waitingStateId) {

    public InboundProperties {
        // Defaulted HERE rather than in an accessor, so the record component can keep
        // the name an operator will actually type. The earlier shape called it
        // `reconcileEnabledValue` purely to free up the accessor name, which meant
        // `connector.inbound.reconcile-enabled=false` bound to NOTHING and was silently
        // ignored — the poller stayed on and the operator had no signal (#22 QA).
        // Unset means TRUE: someone who never heard of this setting should get the
        // recovery, not silently lose it. Turning it off has to be typed.
        if (reconcileEnabled == null) {
            reconcileEnabled = Boolean.TRUE;
        }
        if (reconcileLookback == null || reconcileLookback.isNegative() || reconcileLookback.isZero()) {
            reconcileLookback = Duration.ofHours(24);
        }
        if (reconcileOverlap == null || reconcileOverlap.isNegative()) {
            reconcileOverlap = Duration.ofSeconds(120);
        }
    }

    /**
     * Ticket type only, for callers that do not care about reconcile settings.
     *
     * <p>A STATIC FACTORY rather than a second constructor, and that is not style: a
     * record with two constructors cannot be bound by {@code @ConfigurationProperties} —
     * Spring stops treating the canonical one as the binding constructor and looks for a
     * no-arg one instead, so the whole application fails to start with "No default
     * constructor found". Caught by the existing context tests, not by this file.
     */
    public static InboundProperties ofTicketType(String collaborationTicketTypeId) {
        return new InboundProperties(collaborationTicketTypeId, null, null, null, null, null, null);
    }

    /** Ticket type plus the resolved-state id, for closure-flow callers. */
    public static InboundProperties ofTicketTypeAndResolvedState(String collaborationTicketTypeId,
            String resolvedStateId) {
        return new InboundProperties(collaborationTicketTypeId, null, null, null, resolvedStateId,
                null, null);
    }

    /** All three state ids, for response-surfacing callers. */
    public static InboundProperties ofStateIds(String collaborationTicketTypeId,
            String inProgressStateId, String waitingStateId, String resolvedStateId) {
        return new InboundProperties(collaborationTicketTypeId, null, null, null, resolvedStateId,
                inProgressStateId, waitingStateId);
    }

}
