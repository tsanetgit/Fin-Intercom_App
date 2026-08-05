package org.tsanet.connector.tsanet.dto;

import java.util.Map;

/**
 * Everything needed for POST /v1/collaboration-requests. internalCaseNumber is the
 * reconcile key: the Connect API filters list reads by it, so a client-side timeout is
 * reconciled by lookup instead of blind resubmission. It is NOT an idempotency
 * guarantee — the create endpoint rejects no duplicates — so the flow-status machine
 * must gate every retry (docs/decisions.md D2 amendment).
 */
public record SubmissionDraft(String internalCaseNumber, String documentId, String summary, String description,
        Map<String, String> values, String submitterName, String submitterEmail) {
}
