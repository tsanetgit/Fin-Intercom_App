package org.tsanet.connector.intercom;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fixture Intercom gateway for development and tests, mirroring FakeTsanetGateway's
 * discipline: impossible to ship (refuses the prod profile), failure-injectable, and
 * observable. Injection hooks: a partnerDisplay containing "intercom-fail" always
 * throws; containing "intercom-flaky" throws on the FIRST attempt for its token and
 * succeeds after (exercises the retry path); containing "intercom-degrade" returns
 * DEGRADED.
 */
@Component
@ConditionalOnProperty(name = "connector.intercom.mode", havingValue = "fake", matchIfMissing = true)
public class FakeIntercomGateway implements IntercomGateway {

    private final Environment environment;
    private final List<SubmissionLink> annotated = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> attemptsByToken = new ConcurrentHashMap<>();

    public FakeIntercomGateway(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void refuseProd() {
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "FakeIntercomGateway is active under the prod profile; set connector.intercom.mode=rest");
        }
    }

    @Override
    public AnnotationOutcome annotate(SubmissionLink link) {
        String partner = link.partnerDisplay() == null ? "" : link.partnerDisplay().toLowerCase();
        int attempt = attemptsByToken.computeIfAbsent(link.token(), t -> new AtomicInteger())
                .incrementAndGet();
        if (partner.contains("intercom-fail")) {
            throw new IntercomException("injected annotation failure");
        }
        if (partner.contains("intercom-flaky") && attempt == 1) {
            throw new IntercomException("injected transient annotation failure (attempt 1)");
        }
        annotated.add(link);
        return partner.contains("intercom-degrade") ? AnnotationOutcome.DEGRADED : AnnotationOutcome.FULL;
    }

    @Override
    public boolean healthy() {
        return true;
    }

    /** Test taps. */
    public List<SubmissionLink> annotated() {
        return List.copyOf(annotated);
    }

    public int attempts(String token) {
        AtomicInteger n = attemptsByToken.get(token);
        return n == null ? 0 : n.get();
    }
}
