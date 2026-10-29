package org.tsanet.connector.tsanet.dto;

import java.util.Map;

/**
 * Everything needed for POST /v1/collaboration-requests. internalCaseNumber is the
 * reconcile key: the Connect API filters list reads by it, so a client-side timeout is
 * reconciled by lookup instead of blind resubmission. It is NOT an idempotency
 * guarantee — the create endpoint rejects no duplicates — so the flow-status machine
 * must gate every retry (docs/decisions.md D2 amendment).
 *
 * <p>{@code partnerCompanyId} is the {@link PartnerHit#companyId()} of the partner the
 * form was loaded for. The create request carries only the form's documentId, so this is
 * the one place a gateway learns which company the case goes to, which end-to-end
 * encryption needs to pick the partner's key (tsanetgit/Connect_Gateway#51, decision
 * GW-7). Null when the adapter doesn't pass it. A gateway with no encryption configured
 * ignores it; per GW-7, one with encryption configured will refuse the submit rather
 * than send it unencrypted.
 */
public record SubmissionDraft(String internalCaseNumber, String documentId, String summary, String description,
        Map<String, String> values, String submitterName, String submitterEmail, String partnerCompanyId) {

    /**
     * Without a {@code partnerCompanyId}, as before #51: existing adapters compile and
     * behave unchanged against a gateway with no encryption configured.
     */
    public SubmissionDraft(String internalCaseNumber, String documentId, String summary, String description,
            Map<String, String> values, String submitterName, String submitterEmail) {
        this(internalCaseNumber, documentId, summary, description, values, submitterName, submitterEmail, null);
    }
}
