package org.tsanet.connector.intercom;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Never-evicting in-memory link table (Phase 1); see the interface for why. Volatile by
 * construction, so {@link LinkTableConfiguration} wires it only outside the prod profile;
 * {@link SqliteSubmissionLinkTable} is the durable store (docs/decisions.md D11).
 */
public class InMemorySubmissionLinkTable implements SubmissionLinkTable {

    private static final Logger log = LoggerFactory.getLogger(InMemorySubmissionLinkTable.class);
    private static final int SIZE_WARN_THRESHOLD = 5_000;

    private record Entry(SubmissionLink link, AtomicReference<AnnotationState> annotation) {
    }

    private final Map<String, Entry> byToken = new ConcurrentHashMap<>();
    private final Map<String, String> tokenByInternal = new ConcurrentHashMap<>();
    // One conversation can anchor several cases over its life (QA round-2): the
    // index is multi-valued, newest first.
    private final Map<String, CopyOnWriteArrayList<String>> tokensByConversation = new ConcurrentHashMap<>();

    @Override
    public void record(SubmissionLink link) {
        Entry previous = byToken.putIfAbsent(link.token(),
                new Entry(link, new AtomicReference<>(AnnotationState.NONE)));
        if (previous != null) {
            return; // replay: first record wins, indexes already point here
        }
        tokenByInternal.putIfAbsent(link.internalCaseNumber(), link.token());
        if (link.conversationId() != null) {
            tokensByConversation.computeIfAbsent(link.conversationId(), c -> new CopyOnWriteArrayList<>())
                    .add(0, link.token());
        }
        int size = byToken.size();
        if (size > SIZE_WARN_THRESHOLD && size % 1_000 == 0) {
            log.warn("submission link table at {} entries (in-memory, never evicts;"
                    + " persistence is the Phase-2 precondition)", size);
        }
    }

    // A null key answers empty on all three lookups rather than throwing: keys arrive
    // from webhook payloads and from links whose optional fields are null, so
    // ConcurrentHashMap.get(null) would NPE the event drain on a reachable path, and the
    // SQL store answers empty for the same input. The contract pins them to one answer.

    @Override
    public Optional<SubmissionLink> byToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byToken.get(token)).map(Entry::link);
    }

    @Override
    public Optional<SubmissionLink> byInternalCaseNumber(String internalCaseNumber) {
        if (internalCaseNumber == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokenByInternal.get(internalCaseNumber)).flatMap(this::byToken);
    }

    @Override
    public List<SubmissionLink> byConversationId(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        CopyOnWriteArrayList<String> tokens = tokensByConversation.get(conversationId);
        if (tokens == null) {
            return List.of();
        }
        return tokens.stream().map(this::byToken).flatMap(Optional::stream).toList();
    }

    @Override
    public AnnotationState annotationState(String token) {
        Entry entry = byToken.get(token);
        return entry == null ? AnnotationState.NONE : entry.annotation().get();
    }

    @Override
    public void annotationState(String token, AnnotationState state) {
        Entry entry = byToken.get(token);
        if (entry != null) {
            entry.annotation().set(state);
        }
    }

    @Override
    public int size() {
        return byToken.size();
    }
}
