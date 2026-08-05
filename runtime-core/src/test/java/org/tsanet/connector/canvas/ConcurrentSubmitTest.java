package org.tsanet.connector.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.tsanet.connector.tsanet.TsanetGateway;
import org.tsanet.connector.tsanet.dto.PartnerHit;
import org.tsanet.connector.tsanet.dto.ProcessForm;
import org.tsanet.connector.tsanet.dto.SubmissionDraft;
import org.tsanet.connector.tsanet.dto.SubmissionResult;

/**
 * QA finding 2: every submit guard was check-then-act on unsynchronized state, so two
 * views POSTing submit_case concurrently could both call gateway.submit. The fake is
 * instant, which hid the window; this test holds the gateway open with a latch so the
 * window is real, then proves exactly one submit gets through.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
})
class ConcurrentSubmitTest {

    @TestConfiguration
    static class BlockingGatewayConfig {
        @Bean
        @Primary
        BlockingGateway blockingGateway() {
            return new BlockingGateway();
        }
    }

    /** Gateway whose submit parks until released, making the race window explicit. */
    static final class BlockingGateway implements TsanetGateway {

        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger submits = new AtomicInteger();
        private final Map<String, SubmissionResult> store = new ConcurrentHashMap<>();

        /** Fresh latches per test; the Spring context (and this bean) is shared. */
        void reset() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            submits.set(0);
            store.clear();
        }

        @Override
        public boolean healthy() {
            return true;
        }

        @Override
        public List<PartnerHit> searchPartners(String query) {
            return List.of(new PartnerHit("Latch Co", "Latch Co", null, "42", "512"));
        }

        @Override
        public ProcessForm form(PartnerHit partner) {
            return new ProcessForm("512", null, null, List.of());
        }

        @Override
        public SubmissionResult submit(SubmissionDraft draft) {
            submits.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test never released the gateway");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            SubmissionResult result = new SubmissionResult(
                    "TSA-latch", Instant.now().plus(2, ChronoUnit.HOURS).toString(), true);
            store.put(draft.internalCaseNumber(), result);
            return result;
        }

        @Override
        public Optional<SubmissionResult> findSubmission(String internalCaseNumber) {
            return Optional.ofNullable(store.get(internalCaseNumber));
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BlockingGateway gateway;

    @org.junit.jupiter.api.BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-intercom-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(body)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String post(String path, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sign(bytes))
                        .content(bytes))
                .andReturn().getResponse().getContentAsString();
    }

    private String submitBody(String flowId, String componentId, String inputValuesJson) {
        return "{\"workspace_id\":\"w1\",\"admin\":{\"name\":\"Agent A\",\"email\":\"agent@tsanet.org\"},"
                + "\"component_id\":\"" + componentId + "\","
                + "\"current_canvas\":{\"stored_data\":{\"flow_id\":\"" + flowId + "\"}},"
                + "\"input_values\":" + inputValuesJson + "}";
    }

    @Test
    void concurrentSubmitsOnOneFlowReachTheGatewayExactlyOnce() throws Exception {
        String home = post("/canvas/initialize", "{\"workspace_id\":\"w1\"}");
        String flow = home.replaceAll("(?s).*\"flow_id\":\"([^\"]+)\".*", "$1");
        post("/canvas/submit", submitBody(flow, "search", "{\"q\":\"latch\"}"));
        post("/canvas/submit", submitBody(flow, "select:42|512", "{}"));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = pool.submit(() -> post("/canvas/submit",
                    submitBody(flow, "submit_case", "{\"summary\":\"s\",\"description\":\"d\"}")));
            assertTrue(gateway.entered.await(10, TimeUnit.SECONDS), "first submit must reach the gateway");

            String second = post("/canvas/submit",
                    submitBody(flow, "submit_case", "{\"summary\":\"s\",\"description\":\"d\"}"));
            assertTrue(second.contains("Submission in progress"),
                    "the losing view must be told a submit is in flight, not submit again");
            assertEquals(1, gateway.submits.get(), "only the claim winner may call gateway.submit");

            gateway.release.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS).contains("Case submitted"));
            assertEquals(1, gateway.submits.get());
        } finally {
            gateway.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void checkStatusWhileSubmittingDoesNotReArmRetry() throws Exception {
        // reconcileNotFound must no-op while the original call is in flight: an empty
        // lookup mid-submit proves nothing and must not unlock a second submit.
        String home = post("/canvas/initialize", "{\"workspace_id\":\"w1\"}");
        String flow = home.replaceAll("(?s).*\"flow_id\":\"([^\"]+)\".*", "$1");
        post("/canvas/submit", submitBody(flow, "search", "{\"q\":\"latch\"}"));
        post("/canvas/submit", submitBody(flow, "select:42|512", "{}"));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = pool.submit(() -> post("/canvas/submit",
                    submitBody(flow, "submit_case", "{\"summary\":\"s\",\"description\":\"d\"}")));
            assertTrue(gateway.entered.await(10, TimeUnit.SECONDS), "first submit must reach the gateway");

            String lookup = post("/canvas/submit", submitBody(flow, "check_status", "{}"));
            assertTrue(lookup.contains("Submission in progress"),
                    "an empty lookup mid-flight must report in-progress, not re-arm");

            String retry = post("/canvas/submit", submitBody(flow, "retry_submit", "{}"));
            assertTrue(retry.contains("Submission in progress"),
                    "retry after the mid-flight lookup must still be blocked");
            assertEquals(1, gateway.submits.get());

            gateway.release.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS).contains("Case submitted"));
            assertEquals(1, gateway.submits.get());
        } finally {
            gateway.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void beginSubmitAdmitsExactlyOneClaimant() throws Exception {
        OutboundFlowState state = new OutboundFlowState("race-flow");
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    if (state.beginSubmit()) {
                        wins.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, wins.get(), "DRAFT -> SUBMITTING must be claimable exactly once");
    }
}
