package org.tsanet.connector.tsanet;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.tsanet.connector.tsanet.dto.EngineerIdentity;
import org.tsanet.connector.tsanet.dto.InboundCase;
import org.tsanet.connector.tsanet.dto.PartnerHit;
import org.tsanet.connector.tsanet.dto.ProcessForm;
import org.tsanet.connector.tsanet.dto.SubmissionDraft;
import org.tsanet.connector.tsanet.dto.SubmissionResult;

/**
 * The seam to TSANet Connect. Implemented in the connect-gateway module on top of
 * com.tsanet:connect-library, which is consumed VERBATIM from tsanetgit/Connect_SDK
 * main (the oauth branch merged in tsanetgit/Connect_SDK#44; v0.1.0 still certifies
 * against pin 0f57fac); changes go upstream, never into a local fork
 * (docs/decisions.md D6).
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

    /**
     * Every INBOUND case whose {@code updatedAt} is at or after {@code updatedAfter}.
     *
     * <p>The reconciliation poller's only read, and the thing that turns a lost push
     * event into a LATE case instead of a lost one: intake answers 200 on every branch
     * so TSANet never redelivers, which makes push a single attempt from our side.
     *
     * <p>Rows are FULL cases — everything {@link #fetchByToken} would return — so a
     * caller acts without a per-token read.
     *
     * <p><b>Cursoring on {@code updatedAt} is safe for create-recovery, which was
     * verified rather than assumed.</b> On BETA a freshly created case comes back with
     * {@code updatedAt} exactly equal to {@code createdAt}, and a filter set just before
     * it returns that case (probe, 2026-08-07). Had creation left {@code updatedAt}
     * unset until first modification, this cursor would have missed precisely the new
     * cases the poller exists to catch — for 24h, until the server's SLA sweep touched
     * them. If that behaviour ever changes, this contract is what breaks.
     *
     * <p>Callers pass a cursor with OVERLAP already subtracted. The field mixes
     * granularity (creation writes sub-second, the SLA sweep writes whole seconds) and
     * the filter's inclusive/exclusive boundary is unspecified. Re-listing a case is
     * free — the caller skips anything already linked — while missing one is not.
     *
     * <p>Idempotent. Bounded by its own deadline rather than the canvas read budget: no
     * agent is waiting on it, and the response is unpaginated.
     */
    List<InboundCase> listInboundUpdatedSince(Instant updatedAfter);

    /**
     * Accepts a partner's case. {@code caseNumber} is OUR reference, which the partner
     * quotes back, so it must be an identifier our agents can actually search.
     *
     * <p>All three response actions share a failure contract with the rest of this
     * interface: a definitive rejection is {@link GatewayException} and anything
     * ambiguous is {@link GatewayTimeoutException}. The email-domain rule is the
     * canonical definitive case — no status code conveys it, so the message carries
     * the API's validated problem+json detail and the panel renders it beside the
     * email field.
     */
    void approve(String token, EngineerIdentity engineer, String caseNumber, String nextSteps);

    /** Declines a partner's case, with a reason the partner reads. */
    void reject(String token, EngineerIdentity engineer, String reason);

    /** Asks the partner for more before deciding. */
    void requestInformation(String token, EngineerIdentity engineer, String requestedInformation);
}
