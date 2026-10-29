package org.tsanet.connector.tsanet;

/**
 * The gateway reached Connect and got a definite answer, but refuses the case's content
 * under the member's end-to-end encryption policy (tsanetgit/Connect_Gateway#51). For
 * example, the case failed verification, came unencrypted from a partner the member
 * encrypts with, or came encrypted from one it doesn't. Unlike a transport failure,
 * retrying returns the same refusal: the content itself is the reason.
 *
 * <p>A {@link GatewayException}, so an adapter that handles only that type still shows
 * an error rather than a case. The message names the case's token and the refusal's
 * step at most, never a field value or a key.
 */
public class GatewayContentRefusedException extends GatewayException {

    public GatewayContentRefusedException(String message) {
        super(message);
    }
}
