package org.tsanet.connector.intercom;

/** An Intercom-side failure with a log-safe message (status + operation, never bodies). */
public class IntercomException extends RuntimeException {

    public IntercomException(String message) {
        super(message);
    }

    public IntercomException(String message, Throwable cause) {
        super(message, cause);
    }
}
