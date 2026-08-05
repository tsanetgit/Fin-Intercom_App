package org.tsanet.connector.intercom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemorySubmissionLinkTableTest {

    private static SubmissionLink link(String token, String internal, String conversation) {
        return new SubmissionLink(token, internal, "w1", conversation, "t-9", "Partner Co",
                "2026-08-06T12:00:00Z", true, "agent@tsanet.org", Instant.parse("2026-08-05T20:00:00Z"));
    }

    @Test
    void indexesAnswerAllThreeRoutingDirections() {
        InMemorySubmissionLinkTable table = new InMemorySubmissionLinkTable();
        table.record(link("TSA-1", "flow-1", "conv-1"));
        assertEquals("TSA-1", table.byToken("TSA-1").orElseThrow().token());
        assertEquals("TSA-1", table.byInternalCaseNumber("flow-1").orElseThrow().token());
        assertEquals("TSA-1", table.byConversationId("conv-1").get(0).token(),
                "Phase-2 inbound-from-Intercom routes by conversation id");
    }

    @Test
    void firstRecordWinsOnReplay() {
        InMemorySubmissionLinkTable table = new InMemorySubmissionLinkTable();
        table.record(link("TSA-2", "flow-2", "conv-2"));
        table.annotationState("TSA-2", AnnotationState.FULL);
        table.record(new SubmissionLink("TSA-2", "flow-2", "OTHER", "conv-2", null, "Other",
                null, false, null, Instant.parse("2026-08-05T21:00:00Z")));
        assertEquals("w1", table.byToken("TSA-2").orElseThrow().workspaceId(),
                "a replayed record must not overwrite the original link");
        assertEquals(AnnotationState.FULL, table.annotationState("TSA-2"),
                "a replayed record must not reset annotation progress");
    }

    @Test
    void oneConversationCanAnchorSeveralCasesNewestFirst() {
        // QA round-2: putIfAbsent modeled this one-to-one, so the first case owned
        // the conversation forever and later cases were unroutable from Intercom.
        InMemorySubmissionLinkTable table = new InMemorySubmissionLinkTable();
        table.record(link("TSA-a", "flow-a", "conv-shared"));
        table.record(link("TSA-b", "flow-b", "conv-shared"));
        var found = table.byConversationId("conv-shared");
        assertEquals(2, found.size());
        assertEquals("TSA-b", found.get(0).token(), "newest first");
        assertEquals("TSA-a", found.get(1).token());
    }

    @Test
    void annotationStateDefaultsToNoneAndSticks() {
        InMemorySubmissionLinkTable table = new InMemorySubmissionLinkTable();
        assertEquals(AnnotationState.NONE, table.annotationState("absent"));
        table.record(link("TSA-3", "flow-3", "conv-3"));
        assertEquals(AnnotationState.NONE, table.annotationState("TSA-3"));
        table.annotationState("TSA-3", AnnotationState.DEGRADED);
        assertEquals(AnnotationState.DEGRADED, table.annotationState("TSA-3"));
        assertTrue(table.size() == 1);
    }
}
