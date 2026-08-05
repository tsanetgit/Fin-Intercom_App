package org.tsanet.connector.tsanet;

/** A Connect-side failure the canvas layer turns into an error canvas with a retry. */
public class GatewayException extends RuntimeException {

    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
