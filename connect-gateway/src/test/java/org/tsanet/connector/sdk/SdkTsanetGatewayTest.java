package org.tsanet.connector.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.auth.AuthMode;
import com.tsanet.api.facade.AttachmentsFacade;
import com.tsanet.api.facade.AuthFacade;
import com.tsanet.api.facade.CaseNotesFacade;
import com.tsanet.api.facade.CaseResponsesFacade;
import com.tsanet.api.facade.CollaborationRequestsFacade;
import com.tsanet.api.facade.PartnersFacade;
import com.tsanet.api.facade.UserFacade;
import com.tsanet.api.facade.WebhooksFacade;
import com.tsanet.api.generated.api.CollaborationRequestsApi;
import com.tsanet.api.generated.api.EntitySearchApi;
import com.tsanet.api.generated.api.FormRequestApi;
import com.tsanet.api.generated.model.CollaborationRequestDTO;
import com.tsanet.api.generated.model.CollaborationRequestStatus;
import com.tsanet.api.generated.model.CollaborationRequestStatusDTO;
import com.tsanet.api.generated.model.CollaborationRequestType;
import com.tsanet.api.generated.model.FieldMetadataDTO;
import com.tsanet.api.generated.model.PartnerSelectionDTO;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.tsanet.connector.tsanet.GatewayException;
import org.tsanet.connector.tsanet.GatewayTimeoutException;
import org.tsanet.connector.tsanet.dto.PartnerHit;
import org.tsanet.connector.tsanet.dto.ProcessForm;
import org.tsanet.connector.tsanet.dto.SubmissionDraft;
import org.tsanet.connector.tsanet.dto.SubmissionResult;

/**
 * Unit probes for the gateway's non-negotiables: wall-clock deadlines with counters,
 * the submit exception taxonomy (ambiguous -> GatewayTimeoutException), sanitization
 * (no response body or URL ever reaches an agent-visible message), one-shot 401
 * recovery, the findSubmission equals-guard and multiplicity surfacing, and the
 * ZAF-proven options parsing. Generated API classes are subclassed as stubs; no
 * network anywhere.
 */
class SdkTsanetGatewayTest {

    private static final String LEAKY_BODY = "response-body-with-bearer-token-XYZSECRET";

    // ---- stub plumbing ----

    static final class StubAuth implements AuthFacade {
        final AtomicInteger authenticates = new AtomicInteger();

        @Override
        public String authenticate() {
            authenticates.incrementAndGet();
            return "token-fresh";
        }

        @Override
        public String login(String username, String password) {
            return authenticate();
        }

        @Override
        public String loginWithConfiguredCredentials() {
            return authenticate();
        }

        @Override
        public boolean isAuthorized() {
            return true;
        }

        @Override
        public Optional<String> currentUsername() {
            return Optional.of("stub");
        }

        @Override
        public Optional<String> currentAccountId() {
            return Optional.of("default");
        }

        @Override
        public Optional<AuthMode> authMode() {
            return Optional.of(AuthMode.CONNECT1_PASSWORD);
        }

        @Override
        public Optional<Instant> tokenExpiresAt() {
            return Optional.empty();
        }

        @Override
        public Optional<String> currentBearerToken() {
            return Optional.of("token-cached");
        }

        @Override
        public void logout() {
        }
    }

    static TsaNetApiSession session(AuthFacade auth) {
        return new TsaNetApiSession() {
            @Override
            public AuthFacade auth() {
                return auth;
            }

            @Override
            public CollaborationRequestsFacade collaborationRequests() {
                throw new UnsupportedOperationException("facade not used by the gateway");
            }

            @Override
            public CaseNotesFacade caseNotes() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CaseResponsesFacade caseResponses() {
                throw new UnsupportedOperationException();
            }

            @Override
            public UserFacade users() {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebhooksFacade webhooks() {
                throw new UnsupportedOperationException();
            }

            @Override
            public PartnersFacade partners() {
                throw new UnsupportedOperationException();
            }

            @Override
            public AttachmentsFacade attachments() {
                throw new UnsupportedOperationException();
            }
        };
    }

    static final class StubSearch extends EntitySearchApi {
        Supplier<List<PartnerSelectionDTO>> onSearch = List::of;

        @Override
        public List<PartnerSelectionDTO> searchPartners(String term) {
            return onSearch.get();
        }
    }

    static final class StubForms extends FormRequestApi {
        Supplier<CollaborationRequestDTO> onFetch = CollaborationRequestDTO::new;

        @Override
        public CollaborationRequestDTO getFormByDepartmentId(Long id) {
            return onFetch.get();
        }

        @Override
        public CollaborationRequestDTO getFormByCompanyId(Long id) {
            return onFetch.get();
        }

        @Override
        public CollaborationRequestDTO getFormByDocumentId(Long id) {
            return onFetch.get();
        }
    }

    static final class StubCollaborations extends CollaborationRequestsApi {
        Supplier<List<CollaborationRequestStatusDTO>> onList = List::of;
        java.util.function.Function<CollaborationRequestDTO, CollaborationRequestStatusDTO> onCreate =
                form -> new CollaborationRequestStatusDTO();
        final AtomicReference<CollaborationRequestDTO> lastCreate = new AtomicReference<>();

        @Override
        public List<CollaborationRequestStatusDTO> listCollaborationRequests(String internalCaseNumber,
                CollaborationRequestType type, CollaborationRequestStatus status,
                OffsetDateTime updatedAfter, OffsetDateTime createdAfter, Boolean includeRemovedNotes) {
            return onList.get();
        }

        @Override
        public CollaborationRequestStatusDTO createCollaborationRequest(CollaborationRequestDTO form) {
            lastCreate.set(form);
            return onCreate.apply(form);
        }
    }

    private final StubAuth auth = new StubAuth();
    private final StubSearch search = new StubSearch();
    private final StubForms forms = new StubForms();
    private final StubCollaborations collaborations = new StubCollaborations();

    private SdkTsanetGateway gateway(long readMs, long submitMs) {
        return new SdkTsanetGateway(session(auth), search, forms, collaborations,
                new SdkTsanetGateway.Config("https://stub.invalid", true, readMs, submitMs),
                new GatewayTimeouts());
    }

    private static CollaborationRequestDTO formWithField(long fieldId) {
        CollaborationRequestDTO form = new CollaborationRequestDTO();
        form.setDocumentId(512L);
        FieldMetadataDTO field = new FieldMetadataDTO();
        field.setFieldId(fieldId);
        field.setLabel("Serial");
        form.setCustomFields(new java.util.ArrayList<>(List.of(field)));
        return form;
    }

    private static CollaborationRequestStatusDTO status(String caseNumber, String token, boolean testCase) {
        CollaborationRequestStatusDTO dto = new CollaborationRequestStatusDTO();
        dto.setSubmitterCaseNumber(caseNumber);
        dto.setToken(token);
        dto.setTestCase(testCase);
        dto.setRespondBy(OffsetDateTime.of(2026, 8, 6, 12, 0, 0, 0, ZoneOffset.UTC));
        return dto;
    }

    private static SubmissionDraft draft(String internal, Map<String, String> values) {
        return new SubmissionDraft(internal, "512", "summary", "description", values,
                "Agent A", "agent@tsanet.org");
    }

    // ---- deadlines ----

    @Test
    void deadlineExpiryThrowsTimeoutAndCountsIt() {
        search.onSearch = () -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        };
        SdkTsanetGateway gateway = gateway(120, 4000);
        long before = System.nanoTime();
        assertThrows(GatewayTimeoutException.class, () -> gateway.searchPartners("netapp"));
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;
        assertTrue(elapsedMs < 2_000, "the deadline, not the slow call, must bound the wait");
        assertEquals(1, gateway.timeouts().total("search"));
    }

    // ---- submit taxonomy + sanitization ----

    @Test
    void serverErrorOnSubmitIsAmbiguousAndLeaksNothing() {
        forms.onFetch = () -> formWithField(88);
        collaborations.onCreate = form -> {
            throw HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom",
                    HttpHeaders.EMPTY, LEAKY_BODY.getBytes(StandardCharsets.UTF_8), null);
        };
        SdkTsanetGateway gateway = gateway(1500, 4000);
        GatewayTimeoutException e = assertThrows(GatewayTimeoutException.class,
                () -> gateway.submit(draft("case-1", Map.of("88", "SN"))));
        assertTrue(e.getMessage().contains("outcome unknown"));
        assertFalse(e.getMessage().contains("XYZSECRET"), "response bodies must never reach the agent");
        assertFalse(e.getMessage().contains("stub.invalid"), "URLs must never reach the agent");
    }

    @Test
    void badRequestOnSubmitIsDefinitiveRejection() {
        forms.onFetch = () -> formWithField(88);
        collaborations.onCreate = form -> {
            throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad",
                    HttpHeaders.EMPTY, LEAKY_BODY.getBytes(StandardCharsets.UTF_8), null);
        };
        SdkTsanetGateway gateway = gateway(1500, 4000);
        GatewayException e = assertThrows(GatewayException.class,
                () -> gateway.submit(draft("case-2", Map.of("88", "SN"))));
        assertFalse(e instanceof GatewayTimeoutException, "a parsed 400 is not ambiguous");
        assertTrue(e.getMessage().contains("400"));
        assertFalse(e.getMessage().contains("XYZSECRET"));
    }

    @Test
    void transportFaultOnSubmitIsAmbiguousButOnReadsIsPlain() {
        forms.onFetch = () -> formWithField(88);
        collaborations.onCreate = form -> {
            throw new ResourceAccessException("connection reset by peer at https://stub.invalid/v1/x");
        };
        SdkTsanetGateway gateway = gateway(1500, 4000);
        assertThrows(GatewayTimeoutException.class,
                () -> gateway.submit(draft("case-3", Map.of("88", "SN"))));

        search.onSearch = () -> {
            throw new ResourceAccessException("connect timed out");
        };
        GatewayException read = assertThrows(GatewayException.class, () -> gateway.searchPartners("netapp"));
        assertFalse(read instanceof GatewayTimeoutException,
                "an unreachable READ renders a plain retry canvas, not the reconcile path");
        assertFalse(read.getMessage().contains("stub.invalid"));
    }

    @Test
    void tokenlessCreateResponseIsAmbiguousPerD3() {
        forms.onFetch = () -> formWithField(88);
        collaborations.onCreate = form -> new CollaborationRequestStatusDTO(); // 200, no token
        SdkTsanetGateway gateway = gateway(1500, 4000);
        assertThrows(GatewayTimeoutException.class,
                () -> gateway.submit(draft("case-4", Map.of("88", "SN"))));
    }

    @Test
    void draftFieldMissingFromRefetchedFormFailsLoudly() {
        forms.onFetch = () -> formWithField(88);
        SdkTsanetGateway gateway = gateway(1500, 4000);
        GatewayException e = assertThrows(GatewayException.class,
                () -> gateway.submit(draft("case-5", Map.of("88", "SN", "99", "stale"))));
        assertTrue(e.getMessage().contains("99"), "the unknown field id must be named");
        assertTrue(collaborations.lastCreate.get() == null, "nothing may be submitted");
    }

    @Test
    void submitCarriesFlagSubmitterAndValues() {
        forms.onFetch = () -> formWithField(88);
        collaborations.onCreate = form -> status("case-6", "TSA-1", true);
        SdkTsanetGateway gateway = gateway(1500, 4000);
        SubmissionResult result = gateway.submit(draft("case-6", Map.of("88", "  SN-9  ")));
        assertEquals("TSA-1", result.token());
        assertTrue(result.testCase());
        assertNotNull(result.respondBy());
        CollaborationRequestDTO sent = collaborations.lastCreate.get();
        assertEquals("case-6", sent.getInternalCaseNumber());
        assertEquals(Boolean.TRUE, sent.getTestSubmission());
        assertEquals("agent@tsanet.org", sent.getSubmitterContactDetails().getEmail());
        assertEquals("SN-9", sent.getCustomFields().get(0).getValue(), "values are stripped");
        assertNotNull(sent.getPriority(), "priority defaults when the form has none");
    }

    @Test
    void problemJsonDetailSurfacesOnSubmitRejection() {
        forms.onFetch = () -> formWithField(88);
        collaborations.onCreate = form -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/problem+json");
            String body = "{\"title\":\"Bad Request\",\"detail\":\"Submitter email must be on the"
                    + " member's registered domain\",\"instance\":\"https://internal.invalid/x\"}";
            throw HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "rejected",
                    headers, body.getBytes(StandardCharsets.UTF_8), null);
        };
        SdkTsanetGateway gateway = gateway(1500, 4000);
        GatewayException e = assertThrows(GatewayException.class,
                () -> gateway.submit(draft("case-pj", Map.of("88", "SN"))));
        assertTrue(e.getMessage().contains("registered domain"),
                "the RFC 7807 detail is the agent-actionable rule and must surface");
        assertFalse(e.getMessage().contains("internal.invalid"),
                "only the detail field surfaces, never other problem members");
    }

    @Test
    void problemDetailGuardsRejectNonProblemAndMalformedBodies() {
        forms.onFetch = () -> formWithField(88);
        // Same detail-bearing body but WITHOUT the problem+json content type: the
        // guard must refuse it and fall back to the status-only message.
        collaborations.onCreate = form -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad", headers,
                    ("{\"detail\":\"" + LEAKY_BODY + "\"}").getBytes(StandardCharsets.UTF_8), null);
        };
        SdkTsanetGateway gateway = gateway(1500, 4000);
        GatewayException e = assertThrows(GatewayException.class,
                () -> gateway.submit(draft("case-pj2", Map.of("88", "SN"))));
        assertFalse(e.getMessage().contains("XYZSECRET"));

        // problem+json content type but detail is not a string: guard yields null.
        collaborations.onCreate = form -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/problem+json");
            throw HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "bad", headers,
                    "{\"detail\":{\"nested\":\"object\"}}".getBytes(StandardCharsets.UTF_8), null);
        };
        GatewayException e2 = assertThrows(GatewayException.class,
                () -> gateway.submit(draft("case-pj3", Map.of("88", "SN"))));
        assertFalse(e2.getMessage().contains("nested"));
    }

    @Test
    void formFetchFailureDuringSubmitIsAReadFailureNotAmbiguous() {
        // The re-fetch is a READ inside the submit budget: its 5xx parks nothing,
        // because no create was attempted (QA round-3 note - manufacturing ambiguity
        // pushes traffic down the reconcile path for a plain read failure).
        forms.onFetch = () -> {
            throw HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom",
                    HttpHeaders.EMPTY, new byte[0], null);
        };
        SdkTsanetGateway gateway = gateway(1500, 4000);
        GatewayException e = assertThrows(GatewayException.class,
                () -> gateway.submit(draft("case-ff", Map.of())));
        assertFalse(e instanceof GatewayTimeoutException,
                "a form-fetch failure is definitive for the submit that never happened");
        assertTrue(collaborations.lastCreate.get() == null);
    }

    @Test
    void malformedDocumentIdIsDefinitiveNotAmbiguous() {
        // No network call is ever attempted for a bad id, so it must NOT park the
        // flow UNRESOLVED (gate finding: a phantom-duplicate risk for a create that
        // never happened).
        SdkTsanetGateway gateway = gateway(1500, 4000);
        GatewayException e = assertThrows(GatewayException.class,
                () -> gateway.submit(new SubmissionDraft("case-bad", "not-a-number", "s", "d",
                        Map.of(), "A", "a@tsanet.org")));
        assertFalse(e instanceof GatewayTimeoutException, "a parse failure is definitive");
        assertTrue(collaborations.lastCreate.get() == null);
    }

    @Test
    void interruptedSubmitIsAmbiguousNotDefinitive() {
        // An interrupted wait proves nothing about the create request already on the
        // wire; mapping it to plain GatewayException would re-arm DRAFT and invite a
        // duplicate submit (advisor catch on the pre-PR review).
        //
        // The worker is HELD until the assertion has run, because a pending interrupt
        // is only observable while the task is still in flight: Future.get() on an
        // ALREADY COMPLETE task returns the value and never checks the flag (probed
        // directly - an interrupted caller with a completed task returns normally,
        // flag still set). Without the latch this raced the virtual thread and passed
        // or failed on scheduling: green on macOS, red under CI load, which is how it
        // reached main on the day the sdk job first actually ran.
        CountDownLatch releaseCreate = new CountDownLatch(1);
        forms.onFetch = () -> formWithField(88);
        collaborations.onCreate = form -> {
            try {
                releaseCreate.await();
            } catch (InterruptedException e) {
                // bounded()'s future.cancel(true) lands here; keep the flag honest.
                Thread.currentThread().interrupt();
            }
            return status("case-int", "TSA-INT", true);
        };
        SdkTsanetGateway gateway = gateway(1500, 4000);
        Thread.currentThread().interrupt();
        try {
            GatewayTimeoutException thrown = assertThrows(GatewayTimeoutException.class,
                    () -> gateway.submit(draft("case-int", Map.of("88", "SN"))));
            // Both the interrupt path and the 4s deadline throw this same type, so
            // assert the REASON, not just the class: a held worker would otherwise let
            // this pass by timing out, proving nothing about interrupt handling.
            assertTrue(thrown.getMessage().contains("interrupted"),
                    "must be the interrupt path, not the deadline path: " + thrown.getMessage());
        } finally {
            releaseCreate.countDown();
            assertTrue(Thread.interrupted(), "the interrupt flag must be restored, then cleared for the suite");
        }
    }

    // ---- 401 recovery ----

    @Test
    void unauthorizedRecoversOnceViaReauthentication() {
        AtomicInteger calls = new AtomicInteger();
        search.onSearch = () -> {
            if (calls.incrementAndGet() == 1) {
                throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "unauthorized",
                        HttpHeaders.EMPTY, new byte[0], null);
            }
            PartnerSelectionDTO dto = new PartnerSelectionDTO();
            dto.setLabel("L");
            dto.setCompanyName("Sdk Two");
            dto.setCompanyId(1157L);
            return List.of(dto);
        };
        SdkTsanetGateway gateway = gateway(1500, 4000);
        List<PartnerHit> hits = gateway.searchPartners("sdk two");
        assertEquals(1, hits.size());
        assertEquals(2, calls.get(), "exactly one retry after re-auth");
        assertEquals(1, auth.authenticates.get(), "the retry must re-authenticate first");
    }

    // ---- findSubmission guards ----

    @Test
    void findSubmissionRequiresTheFieldToMatchTheFilter() {
        collaborations.onList = () -> List.of(status("SOMEONE-ELSE", "TSA-X", false));
        SdkTsanetGateway gateway = gateway(1500, 4000);
        assertTrue(gateway.findSubmission("case-7").isEmpty(),
                "an ignored server filter must read as not-found, never a false match");
    }

    @Test
    void findSubmissionSurfacesMultiplicityInsteadOfMasking() {
        collaborations.onList = () -> List.of(status("case-8", "TSA-A", true), status("case-8", "TSA-B", true));
        SdkTsanetGateway gateway = gateway(1500, 4000);
        GatewayException e = assertThrows(GatewayException.class, () -> gateway.findSubmission("case-8"));
        assertTrue(e.getMessage().contains("Multiple cases"));
    }

    @Test
    void findSubmissionRejectsATokenlessRow() {
        // QA round-2: submit treats a token-less 200 as ambiguous per D3, but the
        // reconcile read had no such guard — a matching row with a null token NPE'd
        // the handler and poisoned the flow LANDED-with-null. A row that cannot
        // supply the token is not a landing: explicit failure, never not-found (a
        // case may exist) and never a null-token result.
        collaborations.onList = () -> List.of(status("case-nt", null, true));
        SdkTsanetGateway gateway = gateway(1500, 4000);
        GatewayException e = assertThrows(GatewayException.class, () -> gateway.findSubmission("case-nt"));
        assertFalse(e instanceof GatewayTimeoutException);
        assertTrue(e.getMessage().contains("without a token"));
    }

    @Test
    void problemDetailStripsUnicodeControlsAndMarkdown() {
        forms.onFetch = () -> formWithField(88);
        collaborations.onCreate = form -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/problem+json");
            String body = "{\"detail\":\"Rejected\\u202e\\u200b\\u2028 [click me](https://evil.invalid)"
                    + " `code` field\"}";
            throw HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "bad",
                    headers, body.getBytes(StandardCharsets.UTF_8), null);
        };
        SdkTsanetGateway gateway = gateway(1500, 4000);
        GatewayException e = assertThrows(GatewayException.class,
                () -> gateway.submit(draft("case-uni", Map.of("88", "SN"))));
        String message = e.getMessage();
        assertFalse(message.contains("\u202e"), "RTL override must be stripped");
        assertFalse(message.contains("\u200b"), "zero-width space must be stripped");
        assertFalse(message.contains("\u2028"), "line separator must be stripped");
        assertFalse(message.contains("[") || message.contains("`"),
                "markdown-active characters are neutralized pending the live render check");
        assertTrue(message.contains("(click me)(https://evil.invalid)"));
    }

    @Test
    void findSubmissionMapsTheSingleMatch() {
        collaborations.onList = () -> List.of(status("case-9", "TSA-C", true));
        SdkTsanetGateway gateway = gateway(1500, 4000);
        SubmissionResult result = gateway.findSubmission("case-9").orElseThrow();
        assertEquals("TSA-C", result.token());
        assertTrue(result.testCase());
    }

    // ---- misc contract pieces ----

    @Test
    void searchValidationMessagePassesThroughUnchanged() {
        SdkTsanetGateway gateway = gateway(1500, 4000);
        GatewayException e = assertThrows(GatewayException.class, () -> gateway.searchPartners("   "));
        assertTrue(e.getMessage().toLowerCase().contains("search term"),
                "SDK validation strings are agent-actionable and pass through");
    }

    @Test
    void formMapsMetadataAndParsesOptions() {
        CollaborationRequestDTO form = formWithField(88);
        form.setAdminNote("note");
        form.setEscalationInstructions("escalate hard");
        FieldMetadataDTO field = form.getCustomFields().get(0);
        field.setOptions("High\r\nMedium\nLow");
        field.setRequired(true);
        forms.onFetch = () -> form;
        SdkTsanetGateway gateway = gateway(1500, 4000);
        ProcessForm mapped = gateway.form(new PartnerHit("l", "c", "d", "1157", "512"));
        assertEquals("512", mapped.documentId());
        assertEquals("note", mapped.adminNote());
        assertEquals("escalate hard", mapped.escalationInstructions());
        assertEquals(List.of("High", "Medium", "Low"), mapped.fields().get(0).options());
        assertTrue(mapped.fields().get(0).required());
    }

    @Test
    void optionsParsingPrefersNewlinesThenFallsBackToCommas() {
        assertEquals(List.of("A", "B"), SdkTsanetGateway.parseOptions("A\r\nB"));
        assertEquals(List.of("A", "B"), SdkTsanetGateway.parseOptions("A,B"));
        assertEquals(List.of("A,B x", "C"), SdkTsanetGateway.parseOptions("A,B x\nC"),
                "newline-delimited options may legally contain commas");
        assertEquals(List.of("Only"), SdkTsanetGateway.parseOptions("  Only  "));
        assertEquals(List.of(), SdkTsanetGateway.parseOptions(null));
    }

    @Test
    void healthyIsFalseOnAnyFailureAndTrueOnSuccess() {
        collaborations.onList = List::of;
        SdkTsanetGateway gateway = gateway(1500, 4000);
        assertTrue(gateway.healthy());
        collaborations.onList = () -> {
            throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down",
                    HttpHeaders.EMPTY, new byte[0], null);
        };
        assertFalse(gateway.healthy());
    }
}
