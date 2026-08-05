package org.tsanet.connector.tsanet;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.tsanet.connector.tsanet.dto.FormField;
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
 * lookup finds nothing. The partner "Unsupported Co" returns a form with a FILES field
 * to exercise the loud unsupported-type failure.
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

    private static final List<PartnerHit> FIXTURE_PARTNERS = List.of(
            new PartnerHit("NetApp | Storage Division", "NetApp", "Storage Division", "1042", "512"),
            new PartnerHit("NetApp | Cloud", "NetApp", "Cloud", "1042", "513"),
            new PartnerHit("SDK Two Co", "SDK Two Co", null, "1157", null),
            new PartnerHit("Broken Forms Inc", "Broken Forms Inc", "Support", "2000", "999"),
            new PartnerHit("Unsupported Co", "Unsupported Co", "Files Dept", "2100", "777"));
}
