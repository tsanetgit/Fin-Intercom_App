package org.tsanet.connector.intercom;

import org.tsanet.connector.tsanet.dto.InboundCase;

/**
 * What the platform needs in order to anchor an inbound partner case as a ticket.
 *
 * <p>Deliberately a translation of {@link InboundCase} rather than the case itself:
 * the platform gateway is adapter code and must not reach into TSANet DTOs, and the
 * ticket type is workspace configuration that the case knows nothing about.
 */
public record InboundTicketRequest(
        String token,
        String ticketTypeId,
        String partnerCompany,
        String summary,
        String description,
        String respondBy,
        boolean testCase) {

    public static InboundTicketRequest from(InboundCase inboundCase, String ticketTypeId) {
        return new InboundTicketRequest(
                inboundCase.token(),
                ticketTypeId,
                inboundCase.partnerCompany(),
                inboundCase.summary(),
                inboundCase.description(),
                inboundCase.respondBy(),
                inboundCase.testCase());
    }
}
