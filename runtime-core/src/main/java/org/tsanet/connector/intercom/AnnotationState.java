package org.tsanet.connector.intercom;

/**
 * How far a link's Intercom annotation got. Tri-state on purpose: a boolean would
 * report DEGRADED (attributes landed without "Respond By" after a format rejection)
 * as done, hiding the exact signal that says the wire format needs attention.
 */
public enum AnnotationState {
    /** Not annotated yet (or every attempt failed; see the audit log). */
    NONE,
    /** Annotated, but a non-essential field was dropped to get the write through. */
    DEGRADED,
    /** Everything applicable to the anchor landed and read back (D3). */
    FULL
}
