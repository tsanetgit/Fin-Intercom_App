package org.tsanet.connector.tsanet;

/**
 * The bounded-deadline expiry. For reads this means "render the retry canvas". For a
 * submission it means "possibly succeeded server-side": the flow must reconcile via
 * findSubmission(internalCaseNumber) before ever resubmitting (decisions.md D2).
 */
public class GatewayTimeoutException extends GatewayException {

    public GatewayTimeoutException(String message) {
        super(message);
    }
}
