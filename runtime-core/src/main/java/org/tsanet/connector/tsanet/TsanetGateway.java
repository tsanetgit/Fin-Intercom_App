package org.tsanet.connector.tsanet;

import java.util.List;
import java.util.Optional;
import org.tsanet.connector.tsanet.dto.InboundCase;
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

    /**
     * Create the collaboration case. NOT idempotent; see class javadoc.
     *
     * <p>Exception contract: throw plain {@link GatewayException} ONLY when the API
     * definitively rejected the request before a case could exist (a parsed 400/401
     * validation or auth failure) — the flow treats that as safe to edit and resubmit.
     * Every ambiguous outcome (deadline expiry, connection reset mid-response, 5xx
     * after the request was sent) MUST surface as {@link GatewayTimeoutException} so
     * the flow parks UNRESOLVED and reconciles by lookup; mapping ambiguity to plain
     * GatewayException re-arms submit and reopens the duplicate-case window.
     */
    SubmissionResult submit(SubmissionDraft draft);

    /** Reconcile a possibly-successful submission by its internalCaseNumber. */
    Optional<SubmissionResult> findSubmission(String internalCaseNumber);

    /**
     * Reads a collaboration case by its token, or empty when the API has no such case.
     *
     * <p>The inbound counterpart to {@link #findSubmission}: a case a partner created
     * carries no internalCaseNumber of ours, so the token is the only handle that
     * reaches it. Push payloads are thin — a type and a token — so every inbound
     * handler starts here.
     *
     * <p>Empty means the API answered that no case exists (a parsed 404), which is a
     * definitive answer a handler may act on. Every other failure throws, and an
     * ambiguous one throws {@link GatewayTimeoutException} exactly as elsewhere in
     * this contract: a read that may not have happened must not be reported as "no
     * such case", because a handler would then treat a live partner case as absent.
     */
    Optional<InboundCase> fetchByToken(String token);
}
