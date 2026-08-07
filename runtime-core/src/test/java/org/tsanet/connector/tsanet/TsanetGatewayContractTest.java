package org.tsanet.connector.tsanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.tsanet.connector.tsanet.dto.InboundCase;
import org.tsanet.connector.tsanet.dto.PartnerHit;
import org.tsanet.connector.tsanet.dto.ProcessForm;
import org.tsanet.connector.tsanet.dto.SubmissionDraft;
import org.tsanet.connector.tsanet.dto.SubmissionResult;

/**
 * The gateway contract both implementations must pass: FakeTsanetGateway now, the
 * SDK-backed gateway in slice 2 (against BETA with the sdk test members). Subclasses
 * provide the instance and the environment-appropriate inputs; the semantics asserted
 * here are the ones the canvas flow depends on.
 */
public abstract class TsanetGatewayContractTest {

    protected abstract TsanetGateway gateway();

    /** A query guaranteed to match at least one partner in the environment under test. */
    protected String knownPartnerQuery() {
        return "netapp";
    }

    /** Whether this implementation supports the injected submit-timeout scenario. */
    protected boolean supportsInjectedSubmitTimeout() {
        return true;
    }

    protected SubmissionDraft draft(String internalCaseNumber, String documentId, String summary) {
        return new SubmissionDraft(internalCaseNumber, documentId, summary,
                "Contract test description", Map.of(), "Contract Tester", "tester@tsanet.org");
    }

    @Test
    void searchReturnsPopulatedHits() {
        List<PartnerHit> hits = gateway().searchPartners(knownPartnerQuery());
        assertFalse(hits.isEmpty(), "known query must match");
        PartnerHit first = hits.get(0);
        assertNotNull(first.companyId());
        assertNotNull(first.companyName());
        assertFalse(first.display().isBlank());
    }

    @Test
    void formCarriesDocumentIdAndFieldMetadata() {
        PartnerHit partner = gateway().searchPartners(knownPartnerQuery()).get(0);
        ProcessForm form = gateway().form(partner);
        assertNotNull(form.documentId(), "documentId is required for submission");
        assertNotNull(form.fields());
        form.fields().forEach(f -> {
            assertNotNull(f.fieldId());
            assertNotNull(f.label());
            assertNotNull(f.type());
        });
    }

    @Test
    void submitReturnsTokenAndIsFindableByInternalCaseNumber() {
        PartnerHit partner = gateway().searchPartners(knownPartnerQuery()).get(0);
        ProcessForm form = gateway().form(partner);
        String internal = "contract-" + UUID.randomUUID();
        SubmissionResult result = gateway().submit(draft(internal, form.documentId(), "Contract test case"));
        assertNotNull(result.token());
        Optional<SubmissionResult> found = gateway().findSubmission(internal);
        assertTrue(found.isPresent(), "submission must be findable by internalCaseNumber");
        assertEquals(result.token(), found.get().token());
    }

    @Test
    void unknownInternalCaseNumberFindsNothing() {
        assertTrue(gateway().findSubmission("contract-never-" + UUID.randomUUID()).isEmpty());
    }

    /**
     * The token a subclass can read back as an INBOUND case, or null when the
     * environment has none. The SDK-backed subclass needs a real partner-originated
     * case on BETA to answer this, which not every environment has.
     */
    protected String knownInboundToken() {
        return null;
    }

    @Test
    void anUnknownTokenIsAbsentRatherThanAnError() {
        // A definitive "no such case" is something a handler acts on, so it must be an
        // empty Optional and never an exception — an inbound handler that cannot tell
        // "absent" from "the read failed" would treat a live partner case as absent.
        assertTrue(gateway().fetchByToken("contract-never-" + UUID.randomUUID()).isEmpty());
        assertTrue(gateway().fetchByToken(null).isEmpty(), "a null token is absent, not a crash");
        assertTrue(gateway().fetchByToken("   ").isEmpty());
    }

    @Test
    void aKnownTokenReadsBackWithItsDirection() {
        String token = knownInboundToken();
        if (token == null) {
            return;
        }
        InboundCase found = gateway().fetchByToken(token)
                .orElseThrow(() -> new AssertionError("known token did not read back: " + token));
        assertEquals(token, found.token());
        // direction is what decides whether a ticket gets created at all, so an
        // implementation that cannot determine it must say UNKNOWN rather than guess.
        assertNotNull(found.direction());
        assertTrue(found.inbound(), "the fixture token must be a partner-originated case");
    }

    @Test
    void inboundListReturnsOnlyRoutableInboundCases() {
        // This is the one read whose rows become tickets. An implementation that lets
        // an OUTBOUND case through — because it dropped the type filter, or because the
        // server ignored it — creates an inbound ticket for work WE submitted, and
        // invites an agent to answer their own case. Asserted on the contract rather
        // than in one implementation's test, because the two reach the guarantee by
        // different means: the SDK gateway filters server-side, the fake in memory.
        List<InboundCase> cases = gateway().listInboundUpdatedSince(null);
        assertNotNull(cases, "the list is never null; empty is the no-work answer");
        cases.forEach(c -> {
            assertTrue(c.inbound(), "listInboundUpdatedSince returned a non-INBOUND case: "
                    + c.token() + " direction=" + c.direction());
            assertNotNull(c.token(), "a case with no token cannot be routed and must not be returned");
            assertFalse(c.token().isBlank(), "a blank token is as unroutable as a null one");
        });
    }

    @Test
    void inboundListHonoursTheCursor() {
        // The property the poller's whole design rests on: if the filter were ignored,
        // every cycle would re-offer every case that ever existed and the cursor would
        // be decorative.
        //
        // THE ASSUMPTION IS LOAD-BEARING, not defensive. Asserting "a 10-year-future
        // cursor returns nothing" is trivially satisfied by an implementation with no
        // inbound cases at all — which is the fake's default state, so the first
        // version of this test passed while the fake ignored its cursor entirely
        // (probed). An environment with nothing to filter cannot measure filtering, and
        // a test that cannot measure must SKIP visibly rather than report a pass.
        //
        // The non-vacuous version lives in FakeTsanetGatewayContractTest, which seeds
        // two cases either side of a cursor.
        List<InboundCase> all = gateway().listInboundUpdatedSince(null);
        Assumptions.assumeFalse(all.isEmpty(),
                "no inbound cases in this environment — cursor filtering is unmeasurable here");
        List<InboundCase> future =
                gateway().listInboundUpdatedSince(Instant.now().plus(Duration.ofDays(3650)));
        assertTrue(future.isEmpty(), "a cursor 10 years ahead must match nothing, but "
                + future.size() + " of " + all.size() + " came back — the updatedAfter"
                + " filter is being ignored");
    }

    @Test
    void timedOutSubmitIsStillReconcilableByLookup() {
        if (!supportsInjectedSubmitTimeout()) {
            return;
        }
        PartnerHit partner = gateway().searchPartners(knownPartnerQuery()).get(0);
        ProcessForm form = gateway().form(partner);
        String internal = "contract-timeout-" + UUID.randomUUID();
        assertThrows(GatewayTimeoutException.class,
                () -> gateway().submit(draft(internal, form.documentId(), "please timeout")));
        assertTrue(gateway().findSubmission(internal).isPresent(),
                "a client-side timeout may still have created the case; lookup must see it");
    }
}
