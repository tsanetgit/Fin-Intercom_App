package org.tsanet.connector.tsanet.dto;

/**
 * Who is answering the partner, on every response action.
 *
 * <p>Prefilled from the acting Intercom teammate but EDITABLE: the API rejects an
 * engineer address off the member's registered domain, the connector cannot know
 * which domains those are (only the API's problem+json detail carries that rule), and
 * a teammate whose login address is off-domain would otherwise face an unfixable
 * failure. Editable default plus the rejection rendered beside the field makes it one
 * correction and a resubmit.
 */
public record EngineerIdentity(String name, String email, String phone) {
}
