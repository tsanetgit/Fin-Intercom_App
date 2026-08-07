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

    private final Map<String, String> ticketsByToken = new ConcurrentHashMap<>();
    private final Map<String, String> numbersByToken = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger createCalls =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Set to make the next create throw, so the handler's claim-release path is testable. */
    public volatile boolean failNextCreate;

    @Override
    public CreatedTicket createCollaborationTicket(InboundTicketRequest request) {
        createCalls.incrementAndGet();
        if (failNextCreate) {
            failNextCreate = false;
            throw new IntercomException("injected ticket-create failure");
        }
        // Deliberately NOT idempotent on the token: the contract puts idempotency in
        // the caller's claim, and a fake that quietly de-duplicated would hide a
        // handler that creates twice.
        // Deliberately unlike each other: a fake whose two ids were interchangeable
        // would let a test assert the wrong one and pass (#17 QA round).
        String ticketId = "api-" + ticketsByToken.size() + "-" + request.token();
        String number = "9900" + ticketsByToken.size();
        ticketsByToken.put(request.token(), ticketId);
        numbersByToken.put(request.token(), number);
        return new CreatedTicket(ticketId, number);
    }

    /** How many times a ticket create was attempted — the duplicate-ticket probe. */
    public int createCalls() {
        return createCalls.get();
    }

    public Map<String, String> numbersByToken() {
        return Map.copyOf(numbersByToken);
    }

    public Map<String, String> ticketsByToken() {
        return Map.copyOf(ticketsByToken);
    }

    private final Map<String, String> statesByTicket = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger stateMoveCalls =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Set to make the next state move throw, for the handler's failure path. */
    public volatile boolean failNextStateMove;

    @Override
    public void moveTicketState(String ticketId, String stateId) {
        // Counted BEFORE validation, so a caller that should not have called at all is
        // distinguishable from one that called with a bad argument. Asserting only on
        // the resulting state cannot tell those apart — the fake rejects both.
        stateMoveCalls.incrementAndGet();
        if (ticketId == null || ticketId.isBlank() || stateId == null || stateId.isBlank()) {
            throw new IntercomException("moveTicketState needs both a ticket id and a state id");
        }
        if (failNextStateMove) {
            failNextStateMove = false;
            throw new IntercomException("injected state-move failure");
        }
        statesByTicket.put(ticketId, stateId);
    }

    /** How many state moves were ATTEMPTED, valid or not — the never-called probe. */
    public int stateMoveCalls() {
        return stateMoveCalls.get();
    }

    /** The state a ticket was last moved to, or null. */
    public String stateOf(String ticketId) {
        return statesByTicket.get(ticketId);
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
