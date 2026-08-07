package org.tsanet.connector.intercom;

/**
 * Where an inbound collaboration case has got to, as far as THIS connector knows.
 *
 * <p>Deliberately the connector's own view rather than a mirror of the Connect
 * lifecycle: it exists to make canvas rendering and transition guarding a function of
 * recorded state instead of of what an agent last clicked. Full lifecycle sync in both
 * directions is on the estate's unconverged list and is not this.
 *
 * <p>Guarding transitions on this is the same discipline as claim-before-create: a
 * stale panel in a second browser tab must not be able to accept a case that was
 * already rejected, because that would hit the partner API twice.
 */
public enum CaseState {
    /** Ticket exists, no response sent to the partner yet. */
    PENDING,
    ACCEPTED,
    REJECTED,
    /** We asked the partner for more information and are waiting on them. */
    INFO_REQUESTED,
    /**
     * The PARTNER closed the case. Terminal, and unlike the others it is not something
     * an agent here did — it is a fact mirrored inbound, which is why nothing in the
     * panel can produce it.
     */
    CLOSED
}
