package org.tsanet.connector.sdk;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.PartnerSearchValidation;
import com.tsanet.api.facade.AuthFacade;
import com.tsanet.api.generated.api.CollaborationRequestsApi;
import com.tsanet.api.generated.api.EntitySearchApi;
import com.tsanet.api.generated.api.FormRequestApi;
import com.tsanet.api.generated.invoker.ApiClient;
import com.tsanet.api.generated.model.CasePriority;
import com.tsanet.api.generated.model.CollaborationRequestDTO;
import com.tsanet.api.generated.model.CollaborationRequestStatusDTO;
import com.tsanet.api.generated.model.ContactDetailsDTO;
import com.tsanet.api.generated.model.FieldMetadataDTO;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.tsanet.connector.tsanet.GatewayException;
import org.tsanet.connector.tsanet.GatewayTimeoutException;
import org.tsanet.connector.tsanet.TsanetGateway;
import org.tsanet.connector.tsanet.dto.FormField;
import org.tsanet.connector.tsanet.dto.PartnerHit;
import org.tsanet.connector.tsanet.dto.ProcessForm;
import org.tsanet.connector.tsanet.dto.SubmissionDraft;
import org.tsanet.connector.tsanet.dto.SubmissionResult;

/**
 * Connect-SDK-backed gateway. The SDK session supplies auth (login, token cache,
 * client-credentials refresh); the operations go through the library's own PUBLIC
 * generated client ({@code com.tsanet.api.generated.*}) on a dedicated, per-gateway
 * {@link ApiClient} — never shared across members (a shared client would be a
 * cross-tenant credential bleed; docs/decisions.md D9).
 *
 * <p>Generated-direct instead of the facades because the facade layer cannot honor the
 * {@link TsanetGateway} contract: its DTOs drop respondBy/testCase (connect-sdk-demo#7),
 * createRequest hardcodes testSubmission=true (#6) and cannot set submitterContactDetails,
 * its form template drops adminNote/escalationInstructions, and no facade exposes the
 * internalCaseNumber list filter that findSubmission stands on. The SDK stays verbatim
 * (D6): both gaps remain upstream issues, worked around through public surface only.
 *
 * <p>Every operation runs under a hard total deadline (D2): expiry throws
 * {@link GatewayTimeoutException}, increments a per-op counter, and logs the structured
 * key {@code tsanet_gateway_timeout}. Failures are sanitized at this boundary — the
 * canvas layer renders exception messages to agents, so messages carry the operation
 * and an HTTP status at most, never URLs or response bodies. On {@code submit}, every
 * ambiguous failure (expiry, transport fault, 5xx after send) surfaces as
 * {@link GatewayTimeoutException} so the flow parks UNRESOLVED and reconciles; plain
 * {@link GatewayException} is reserved for definitive pre-create rejection, per the
 * contract on {@link TsanetGateway#submit}.
 */
public final class SdkTsanetGateway implements TsanetGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SdkTsanetGateway.class);

    private static final Duration TOKEN_EXPIRY_SKEW = Duration.ofSeconds(30);
    /** Definitive pre-create rejections; everything else on submit is ambiguous. */
    private static final Set<Integer> REJECTED_BEFORE_CREATE = Set.of(400, 401, 403, 404, 422);

    /** Wall-clock budgets and the test-submission flag; see docs/decisions.md D2/D9. */
    public record Config(String apiBaseUrl, boolean testSubmission, long readDeadlineMillis,
            long submitDeadlineMillis) {

        public Config {
            if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
                throw new IllegalArgumentException("apiBaseUrl is required");
            }
            if (readDeadlineMillis <= 0) {
                readDeadlineMillis = 1500;
            }
            if (submitDeadlineMillis <= 0) {
                submitDeadlineMillis = 4000;
            }
        }
    }

    private final TsaNetApiSession session;
    private final EntitySearchApi entitySearch;
    private final FormRequestApi forms;
    private final CollaborationRequestsApi collaborations;
    private final Config config;
    private final GatewayTimeouts timeouts;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    SdkTsanetGateway(TsaNetApiSession session, EntitySearchApi entitySearch, FormRequestApi forms,
            CollaborationRequestsApi collaborations, Config config, GatewayTimeouts timeouts) {
        this.session = session;
        this.entitySearch = entitySearch;
        this.forms = forms;
        this.collaborations = collaborations;
        this.config = config;
        this.timeouts = timeouts;
    }

    /** Builds the real client stack: own RestTemplate with socket timeouts, bearer per request. */
    public static SdkTsanetGateway create(TsaNetApiSession session, Config config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout((int) config.submitDeadlineMillis());
        RestTemplate restTemplate = new RestTemplate(new BufferingClientHttpRequestFactory(factory));
        restTemplate.getInterceptors().add(bearerPerRequest(session));
        ApiClient apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath(config.apiBaseUrl());
        return new SdkTsanetGateway(session,
                new EntitySearchApi(apiClient),
                new FormRequestApi(apiClient),
                new CollaborationRequestsApi(apiClient),
                config,
                new GatewayTimeouts());
    }

    /**
     * Bearer injection happens per request, at request time — mutating a shared
     * ApiClient token before each call would race under concurrent canvas requests.
     * OAuth tokens are refreshed ahead of a known expiry (30s skew); password-mode
     * tokens have no known expiry, so they rely on the 401 recovery in callOnce.
     */
    private static ClientHttpRequestInterceptor bearerPerRequest(TsaNetApiSession session) {
        return (request, body, execution) -> {
            AuthFacade auth = session.auth();
            boolean stale = auth.tokenExpiresAt()
                    .map(expiry -> !Instant.now().plus(TOKEN_EXPIRY_SKEW).isBefore(expiry))
                    .orElse(false);
            String token = (stale || auth.currentBearerToken().isEmpty())
                    ? auth.authenticate()
                    : auth.currentBearerToken().get();
            request.getHeaders().setBearerAuth(token);
            return execution.execute(request, body);
        };
    }

    public GatewayTimeouts timeouts() {
        return timeouts;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    // ---- TsanetGateway ----

    @Override
    public boolean healthy() {
        try {
            // A filtered list read is role-gated business surface (unlike /v1/me, which
            // is not role-gated and proves nothing about case rights); the random
            // reference keeps the response empty and cheap.
            bounded("health", config.readDeadlineMillis(),
                    () -> collaborations.listCollaborationRequests(
                            "healthz-" + UUID.randomUUID(), null, null, null, null, Boolean.FALSE));
            return true;
        } catch (RuntimeException e) {
            log.warn("tsanet_gateway_unhealthy reason={}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<PartnerHit> searchPartners(String query) {
        PartnerSearchValidation.ValidationResult validation =
                PartnerSearchValidation.validateKeywordSearch(query);
        if (!validation.valid()) {
            // The SDK's own validation strings are agent-actionable; pass them through.
            throw new GatewayException(validation.message());
        }
        String term = query.strip();
        List<com.tsanet.api.generated.model.PartnerSelectionDTO> body =
                bounded("search", config.readDeadlineMillis(), () -> entitySearch.searchPartners(term));
        if (body == null) {
            return List.of();
        }
        return body.stream()
                .map(dto -> new PartnerHit(
                        dto.getLabel(),
                        dto.getCompanyName(),
                        dto.getDepartmentName(),
                        dto.getCompanyId() == null ? null : String.valueOf(dto.getCompanyId()),
                        dto.getDepartmentId() == null ? null : String.valueOf(dto.getDepartmentId())))
                .toList();
    }

    @Override
    public ProcessForm form(PartnerHit partner) {
        CollaborationRequestDTO form = bounded("form", config.readDeadlineMillis(),
                () -> fetchForm(partner));
        return toProcessForm(form);
    }

    private CollaborationRequestDTO fetchForm(PartnerHit partner) {
        if (partner.departmentId() != null && !partner.departmentId().isBlank()) {
            return forms.getFormByDepartmentId(Long.valueOf(partner.departmentId()));
        }
        return forms.getFormByCompanyId(Long.valueOf(partner.companyId()));
    }

    @Override
    public SubmissionResult submit(SubmissionDraft draft) {
        CollaborationRequestStatusDTO created = bounded("submit", config.submitDeadlineMillis(), () -> {
            // Re-fetched fresh by documentId, exactly like the SDK facade does: the
            // form IS the create request body, and staleness belongs to the server's
            // validation, not to a cache here. Both round trips share the 4000ms budget.
            CollaborationRequestDTO form = forms.getFormByDocumentId(Long.valueOf(draft.documentId()));
            applyDraft(form, draft);
            return collaborations.createCollaborationRequest(form);
        });
        if (created == null || created.getToken() == null) {
            // A 200 without a token is a wrong-but-well-formed response; treat as
            // ambiguous — the case may exist server-side (D3: never trust a bare 200).
            throw new GatewayTimeoutException("Connect API returned no token (submit); outcome unknown");
        }
        return toResult(created);
    }

    private void applyDraft(CollaborationRequestDTO form, SubmissionDraft draft) {
        List<FieldMetadataDTO> fields = form.getCustomFields() == null
                ? Collections.emptyList()
                : form.getCustomFields();
        Set<String> formFieldIds = new HashSet<>();
        for (FieldMetadataDTO field : fields) {
            if (field.getFieldId() == null) {
                continue;
            }
            String id = String.valueOf(field.getFieldId());
            formFieldIds.add(id);
            String provided = draft.values().get(id);
            if (provided != null) {
                field.setValue(provided.strip());
            }
        }
        // Fail loudly if the draft carries a field the re-fetched form no longer has:
        // silently dropping it would submit less than the agent typed (advisor note).
        List<String> unknown = draft.values().keySet().stream()
                .filter(id -> !formFieldIds.contains(id))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new GatewayException("The partner's form changed since it was loaded (unknown field id "
                    + String.join(", ", unknown) + "); reselect the partner and retry.");
        }
        form.setInternalCaseNumber(draft.internalCaseNumber());
        form.setProblemSummary(draft.summary());
        form.setProblemDescription(draft.description());
        form.setTestSubmission(config.testSubmission());
        ContactDetailsDTO submitter = new ContactDetailsDTO();
        submitter.setName(draft.submitterName());
        submitter.setEmail(draft.submitterEmail());
        form.setSubmitterContactDetails(submitter);
        if (form.getPriority() == null) {
            form.setPriority(CasePriority.MEDIUM);
        }
        if (form.getCustomFields() == null) {
            form.setCustomFields(Collections.emptyList());
        }
        if (form.getInternalNotes() == null) {
            form.setInternalNotes(Collections.emptyList());
        }
    }

    @Override
    public Optional<SubmissionResult> findSubmission(String internalCaseNumber) {
        List<CollaborationRequestStatusDTO> body = bounded("find", config.readDeadlineMillis(),
                () -> collaborations.listCollaborationRequests(
                        internalCaseNumber, null, null, null, null, Boolean.FALSE));
        if (body == null) {
            return Optional.empty();
        }
        // Trust nothing about the filter: require the field to actually match. If the
        // server ever ignores the parameter, every row fails this check and the flow
        // safely re-arms instead of confirming a stranger's case.
        List<CollaborationRequestStatusDTO> matches = body.stream()
                .filter(dto -> internalCaseNumber.equals(dto.getSubmitterCaseNumber()))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            // The residual duplicate window (D2) really happened: surface it, never
            // silently confirm one of them.
            log.warn("tsanet_gateway_multiplicity internalCaseNumber={} matches={}",
                    internalCaseNumber, matches.size());
            throw new GatewayException("Multiple cases carry this flow's reference; open TSANet Connect"
                    + " to reconcile them before continuing");
        }
        return Optional.of(toResult(matches.get(0)));
    }

    // ---- plumbing ----

    private static SubmissionResult toResult(CollaborationRequestStatusDTO dto) {
        return new SubmissionResult(
                dto.getToken(),
                dto.getRespondBy() == null ? null : dto.getRespondBy().toString(),
                Boolean.TRUE.equals(dto.getTestCase()));
    }

    private static ProcessForm toProcessForm(CollaborationRequestDTO form) {
        if (form == null || form.getDocumentId() == null) {
            throw new GatewayException("The partner's process form is unavailable (no documentId)");
        }
        List<FormField> fields = new ArrayList<>();
        if (form.getCustomFields() != null) {
            for (FieldMetadataDTO field : form.getCustomFields()) {
                fields.add(new FormField(
                        field.getFieldId() == null ? null : String.valueOf(field.getFieldId()),
                        field.getLabel(),
                        field.getType() == null ? null : field.getType().getValue(),
                        Boolean.TRUE.equals(field.getRequired()),
                        field.getSection() == null ? null : field.getSection().getValue(),
                        parseOptions(field.getOptions())));
            }
        }
        return new ProcessForm(
                String.valueOf(form.getDocumentId()),
                form.getAdminNote(),
                form.getEscalationInstructions(),
                fields);
    }

    /**
     * TSANet delimits SELECT options with newlines (CRLF), with legacy comma-delimited
     * forms in the wild — the exact parsing rule the production ZAF app uses.
     */
    static List<String> parseOptions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> byNewline = split(raw, "\\r\\n|\\r|\\n");
        if (byNewline.size() > 1) {
            return byNewline;
        }
        List<String> byComma = split(raw, ",");
        return byComma.size() > 1 ? byComma : byNewline;
    }

    private static List<String> split(String raw, String regex) {
        List<String> values = new ArrayList<>();
        for (String part : raw.split(regex)) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    /** Runs {@code call} (with one-shot 401 recovery) under a hard wall-clock deadline. */
    private <T> T bounded(String op, long deadlineMillis, Callable<T> call) {
        Future<T> future = executor.submit(() -> callOnce(call));
        try {
            return future.get(deadlineMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            long total = timeouts.record(op);
            log.warn("tsanet_gateway_timeout op={} deadline_ms={} total={}", op, deadlineMillis, total);
            throw new GatewayTimeoutException(
                    "Connect API timed out (" + op + ", >" + deadlineMillis + "ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new GatewayException("Connect API call interrupted (" + op + ")");
        } catch (ExecutionException e) {
            throw sanitize(op, e.getCause());
        }
    }

    /** One auth-recovery retry on 401; anything else propagates to the sanitizer. */
    private <T> T callOnce(Callable<T> call) throws Exception {
        try {
            return call.call();
        } catch (HttpClientErrorException.Unauthorized e) {
            session.auth().authenticate();
            return call.call();
        }
    }

    /**
     * Maps any failure to a message safe for agent-visible cards: operation name and
     * HTTP status at most — never URLs, never response bodies (they can carry internal
     * detail). Full cause goes to the server log here. GatewayExceptions pass through
     * untouched: theirs are constructed-safe (SDK validation strings included).
     */
    private GatewayException sanitize(String op, Throwable cause) {
        boolean submit = "submit".equals(op);
        if (cause instanceof GatewayException gateway) {
            return gateway;
        }
        if (cause instanceof RestClientResponseException http) {
            int status = http.getStatusCode().value();
            log.warn("tsanet_gateway_http_error op={} status={}", op, status);
            if (submit && !REJECTED_BEFORE_CREATE.contains(status)) {
                return new GatewayTimeoutException(
                        "Connect API returned HTTP " + status + " (submit); outcome unknown");
            }
            return new GatewayException("Connect API rejected the request (HTTP " + status + ", " + op + ")");
        }
        if (cause instanceof ResourceAccessException) {
            log.warn("tsanet_gateway_unreachable op={} cause={}",
                    op, cause.getCause() == null ? "io" : cause.getCause().getClass().getSimpleName());
            if (submit) {
                // The connection died after the request may have been sent: ambiguous.
                return new GatewayTimeoutException("Connect API unreachable (submit); outcome unknown");
            }
            return new GatewayException("Connect API unreachable (" + op + ")");
        }
        if (cause instanceof IllegalArgumentException validation && validation.getMessage() != null) {
            // SDK-side validation (search term length, malformed ids): agent-actionable.
            return new GatewayException(validation.getMessage());
        }
        log.error("tsanet_gateway_error op={} type={}",
                op, cause == null ? "unknown" : cause.getClass().getName(), cause);
        if (submit) {
            return new GatewayTimeoutException("Connect API call failed (submit); outcome unknown");
        }
        return new GatewayException("Connect API call failed (" + op + ")");
    }
}
