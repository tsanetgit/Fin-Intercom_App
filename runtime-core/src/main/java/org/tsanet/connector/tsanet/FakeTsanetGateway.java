package org.tsanet.connector.tsanet;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.tsanet.connector.tsanet.dto.FormField;
import org.tsanet.connector.tsanet.dto.EngineerIdentity;
import org.tsanet.connector.tsanet.dto.InboundCase;
import org.tsanet.connector.tsanet.dto.PartnerHit;
import org.tsanet.connector.tsanet.dto.ProcessForm;
import org.tsanet.connector.tsanet.dto.SubmissionDraft;
import org.tsanet.connector.tsanet.dto.SubmissionResult;

/**
 * Failure-injectable fixture gateway for development and tests. Fixtures mirror the
 * real Connect API shapes (see the knowledge-base form contract). Injection hooks:
 * search "failsearch" throws; the partner whose departmentId is 999 fails form fetch;
 * a draft whose summary contains "timeout" records the submission server-side and THEN
 * throws GatewayTimeoutException, simulating the succeeded-but-client-timed-out case
 * that findSubmission must reconcile; a summary containing "timeout-lost" throws the
 * same WITHOUT recording, simulating a request that died in transit so the reconcile
 * lookup finds nothing; a summary containing "domain-check" rejects definitively
 * (nothing recorded) unless the draft's submitter email is on sdk1.com, mirroring the
 * live email-domain rule. The partner "Unsupported Co" returns a form with a FILES
 * field to exercise the loud unsupported-type failure.
 */
@Component
@ConditionalOnProperty(name = "connector.tsanet.mode", havingValue = "fake", matchIfMissing = true)
public class FakeTsanetGateway implements TsanetGateway {

    private final Environment environment;
    private final Map<String, SubmissionResult> submitted = new ConcurrentHashMap<>();
    private volatile SubmissionDraft lastDraft;

    public FakeTsanetGateway(Environment environment) {
        this.environment = environment;
    }

    /** The fake must be impossible to ship: refuse to start under the prod profile. */
    @PostConstruct
    void refuseProd() {
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "FakeTsanetGateway is active under the prod profile; set connector.tsanet.mode=sdk");
        }
    }

    @Override
    public boolean healthy() {
        return true;
    }

    @Override
    public List<PartnerHit> searchPartners(String query) {
        if (query.toLowerCase().contains("failsearch")) {
            throw new GatewayException("injected search failure");
        }
        String q = query.toLowerCase();
        return FIXTURE_PARTNERS.stream()
                .filter(p -> p.companyName().toLowerCase().contains(q)
                        || (p.departmentName() != null && p.departmentName().toLowerCase().contains(q)))
                .toList();
    }

    @Override
    public ProcessForm form(PartnerHit partner) {
        if ("999".equals(partner.departmentId())) {
            throw new GatewayException("injected form-fetch failure");
        }
        if ("777".equals(partner.departmentId())) {
            return new ProcessForm("777", "Attach diagnostics as a file.", null, List.of(
                    new FormField("f1", "Diagnostics bundle", "FILES", true, "PROBLEM_SECTION", List.of())));
        }
        if ("555".equals(partner.departmentId())) {
            // Mirrors the field-type spread real BETA forms carry (the Phase-1 exit
            // E2E found required EMAIL/PHONE on sdk2's real form).
            return new ProcessForm("555", null, null, List.of(
                    new FormField("61", "Customer Email", "EMAIL", true, "CONTACT_SECTION", List.of()),
                    new FormField("62", "Customer Phone", "PHONE", false, "CONTACT_SECTION", List.of()),
                    new FormField("63", "Impact Details", "TEXT", true, "PROBLEM_SECTION", List.of()),
                    new FormField("64", "Routing Code", "HIDDEN", true, "PROBLEM_SECTION", List.of())));
        }
        return new ProcessForm(partner.departmentId(), "Please include the serial number and firmware version.",
                "Call the duty manager for P1s.", List.of(
                        new FormField("88", "Serial Number", "STRING", true, "CONTACT_SECTION", List.of()),
                        new FormField("89", "Severity", "SELECT", true, "PROBLEM_SECTION",
                                List.of("High", "Medium", "Low")),
                        new FormField("90", "Firmware Version", "STRING", false, "PROBLEM_SECTION", List.of())));
    }

    /** The draft most recently handed to {@link #submit}, for test assertions. */
    public SubmissionDraft lastDraft() {
        return lastDraft;
    }

    @Override
    public SubmissionResult submit(SubmissionDraft draft) {
        this.lastDraft = draft;
        String summary = draft.summary() == null ? "" : draft.summary().toLowerCase();
        if (summary.contains("domain-check") && !draft.submitterEmail().endsWith("@sdk1.com")) {
            // Definitive pre-create rejection, like the live rule: no case exists after this.
            throw new GatewayException("Connect API rejected the request (HTTP 400, submit): Invalid"
                    + " email address: " + draft.submitterEmail()
                    + ". Reason: Email must be from the company domain: sdk1.com");
        }
        if (summary.contains("timeout-lost")) {
            throw new GatewayTimeoutException("injected submit timeout (request lost in transit)");
        }
        SubmissionResult result = new SubmissionResult(
                "TSA-" + UUID.randomUUID().toString().substring(0, 8),
                Instant.now().plus(2, ChronoUnit.HOURS).toString(),
                true);
        submitted.put(draft.internalCaseNumber(), result);
        if (summary.contains("timeout")) {
            throw new GatewayTimeoutException("injected submit timeout (case recorded server-side)");
        }
        return result;
    }

    @Override
    public Optional<SubmissionResult> findSubmission(String internalCaseNumber) {
        return Optional.ofNullable(submitted.get(internalCaseNumber));
    }

    /**
     * Fixture inbound cases, keyed by token. A token starting {@code failfetch} throws
     * (so handlers can be built against a failing read), {@code inbound-} yields a
     * partner-originated case, and {@code outbound-} yields one of our own — the case
     * a handler must NOT create a ticket for. Anything else is unknown.
     */
    @Override
    public Optional<InboundCase> fetchByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (token.startsWith("failfetch")) {
            throw new GatewayException("Connect API rejected the request (HTTP 500, fetchByToken)");
        }
        if (token.startsWith("timeoutfetch")) {
            throw new GatewayTimeoutException("Connect API timed out (fetchByToken, >1500ms)");
        }
        if (token.startsWith("inbound-")) {
            return Optional.of(new InboundCase(token, InboundCase.Direction.INBOUND,
                    InboundCase.Status.OPEN, "NetApp", "Replica lag on shared array",
                    "Customer reports 40s lag since 03:00 UTC", "2026-08-08T12:00:00Z",
                    "HIGH", true));
        }
        if (token.startsWith("outbound-")) {
            return Optional.of(new InboundCase(token, InboundCase.Direction.OUTBOUND,
                    InboundCase.Status.OPEN, "NetApp", "Our own request", "we sent this",
                    "2026-08-08T12:00:00Z", "MEDIUM", true));
        }
        return Optional.empty();
    }

    /**
     * Inbound cases the poller should discover. Empty by default, so the poller is a
     * no-op in ordinary development rather than inventing partner traffic.
     */
    private final List<InboundCase> inboundFeed = new CopyOnWriteArrayList<>();
    private volatile RuntimeException listFailure;

    /**
     * Adds a case the poller will see. The case carries its own updatedAt, exactly as a
     * real list row does — a parallel map of timestamps would be a second source of the
     * same fact, free to disagree with the record the poller actually reads.
     */
    public void seedInbound(InboundCase inboundCase) {
        if (inboundCase.updatedAt() == null) {
            // Refused rather than documented. A null-updatedAt seed passes EVERY cursor
            // window, so a test using one would silently stop exercising the filter it
            // meant to exercise — the same vacuity that let the first cursor test pass
            // against a fake with no filtering at all. Real list rows always carry it.
            throw new IllegalArgumentException(
                    "seeded inbound case needs an updatedAt; a null one matches every"
                            + " cursor window and makes filter assertions vacuous");
        }
        inboundFeed.add(inboundCase);
    }

    /** Makes list calls fail; null clears it. */
    public void failListWith(RuntimeException failure) {
        this.listFailure = failure;
    }

    @Override
    public List<InboundCase> listInboundUpdatedSince(Instant updatedAfter) {
        RuntimeException injected = listFailure;
        if (injected != null) {
            throw injected;
        }
        return inboundFeed.stream()
                // Direction is filtered server-side by the real gateway; mirroring that
                // here keeps a seeded OUTBOUND case out of the feed exactly as the API
                // would, so a caller's own direction check is not the only thing
                // standing between an outbound case and a duplicate ticket.
                .filter(InboundCase::inbound)
                // A null cursor means "everything" — that is what a first run with no
                // stored cursor asks for, and the real API treats an absent filter the
                // same way. Refusing it here would hide that path from every test.
                // updatedAt is non-null by construction (seedInbound refuses otherwise).
                .filter(c -> updatedAfter == null || !c.updatedAt().isBefore(updatedAfter))
                .toList();
    }

    /** What the fake was last asked to send, so flows can assert the payload. */
    public record Responded(String token, String action, EngineerIdentity engineer,
            String caseNumber, String text) {
    }

    private final List<Responded> responses = new CopyOnWriteArrayList<>();

    public List<Responded> responses() {
        return List.copyOf(responses);
    }

    @Override
    public void approve(String token, EngineerIdentity engineer, String caseNumber, String nextSteps) {
        record("approve", token, engineer, caseNumber, nextSteps);
    }

    @Override
    public void reject(String token, EngineerIdentity engineer, String reason) {
        record("reject", token, engineer, null, reason);
    }

    @Override
    public void requestInformation(String token, EngineerIdentity engineer, String requestedInformation) {
        record("information", token, engineer, null, requestedInformation);
    }

    /**
     * Injection points that mirror the real failures a response flow must render: an
     * off-domain engineer address is the email-domain rule (definitive, carries a
     * problem+json detail), and a token starting {@code timeout} is ambiguous.
     */
    private void record(String action, String token, EngineerIdentity engineer,
            String caseNumber, String text) {
        if (engineer != null && engineer.email() != null && engineer.email().endsWith("@offdomain.test")) {
            throw new GatewayException("Connect API rejected the request (HTTP 422, " + action
                    + "): engineer email must be on the member's registered domain");
        }
        if (token != null && token.startsWith("timeout")) {
            throw new GatewayTimeoutException("Connect API timed out (" + action + ", >4000ms)");
        }
        responses.add(new Responded(token, action, engineer, caseNumber, text));
    }

    private static final List<PartnerHit> FIXTURE_PARTNERS = buildFixtures();

    private static List<PartnerHit> buildFixtures() {
        List<PartnerHit> partners = new java.util.ArrayList<>(List.of(
                new PartnerHit("NetApp | Storage Division", "NetApp", "Storage Division", "1042", "512"),
                new PartnerHit("NetApp | Cloud", "NetApp", "Cloud", "1042", "513"),
                new PartnerHit("SDK Two Co", "SDK Two Co", null, "1157", null),
                new PartnerHit("Broken Forms Inc", "Broken Forms Inc", "Support", "2000", "999"),
                new PartnerHit("Unsupported Co", "Unsupported Co", "Files Dept", "2100", "777"),
                new PartnerHit("Typed Forms Co", "Typed Forms Co", "Field Types", "2200", "555")));
        // "capacity" matches 15 partners: exercises the results cap (12) in tests.
        for (int i = 1; i <= 15; i++) {
            partners.add(new PartnerHit("Capacity Test " + i, "Capacity Test " + i, null,
                    String.valueOf(3000 + i), null));
        }
        return List.copyOf(partners);
    }
}
