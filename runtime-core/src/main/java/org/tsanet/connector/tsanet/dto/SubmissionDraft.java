package org.tsanet.connector.tsanet.dto;

import java.util.Map;

/**
 * Everything needed for POST /v1/collaboration-requests. internalCaseNumber doubles as
 * the idempotency key: the Connect API filters by it, so a client-side timeout is
 * reconciled by lookup instead of blind resubmission (docs/decisions.md D2 amendment).
 */
public record SubmissionDraft(String internalCaseNumber, String documentId, String summary, String description,
        Map<String, String> values, String submitterName, String submitterEmail) {
}
