package org.tsanet.connector.tsanet;

import java.util.List;
import java.util.Optional;
import org.tsanet.connector.tsanet.dto.PartnerHit;
import org.tsanet.connector.tsanet.dto.ProcessForm;
import org.tsanet.connector.tsanet.dto.SubmissionDraft;
import org.tsanet.connector.tsanet.dto.SubmissionResult;

/**
 * The seam to TSANet Connect. Implemented in the connect-gateway module on top of
 * com.tsanet:connect-library, which is consumed VERBATIM from tsanetgit/Connect_SDK
 * branch `oauth` (commit 0f57fac) until that branch merges; changes go upstream, never
 * into a local fork (docs/decisions.md D6).
 *
 * Rules that bind every implementation:
 * - Canvas handlers make AT MOST ONE gateway call per response, under a hard total
 *   deadline (1500ms for reads, 4000ms for submit), with no in-handler retries; on
 *   expiry throw {@link GatewayTimeoutException} (docs/decisions.md D2, amended).
 * - Reads (search/form/find) are idempotent and safe to retry from a fresh canvas.
 *   {@link #submit} is NOT: a client-side timeout may still have created the case, so
 *   flows reconcile through {@link #findSubmission}, keyed on internalCaseNumber (the
 *   Connect API filters by it), before any resubmission.
 * - Verify effects on every mutation (docs/decisions.md D3): write testSubmission, read
 *   back testCase; never trust a bare success status.
 */
public interface TsanetGateway {

    /** Cheap credential/reachability check for boot and health surfaces. */
    boolean healthy();

    /** Partner directory search (GET /v1/partners/{term}). Idempotent. */
    List<PartnerHit> searchPartners(String query);

    /**
     * The partner's current process form, fetched fresh per submission flow
     * (GET /v1/forms/department/{id} or /company/{id}). Never cached long-term.
     */
    ProcessForm form(PartnerHit partner);

    /** Create the collaboration case. NOT idempotent; see class javadoc. */
    SubmissionResult submit(SubmissionDraft draft);

    /** Reconcile a possibly-successful submission by its internalCaseNumber. */
    Optional<SubmissionResult> findSubmission(String internalCaseNumber);
}
