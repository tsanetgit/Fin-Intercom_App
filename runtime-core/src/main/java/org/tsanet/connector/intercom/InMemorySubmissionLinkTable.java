package org.tsanet.connector.intercom;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Never-evicting in-memory link table (Phase 1); see the interface for why. */
@Component
public class InMemorySubmissionLinkTable implements SubmissionLinkTable {

    private static final Logger log = LoggerFactory.getLogger(InMemorySubmissionLinkTable.class);
    private static final int SIZE_WARN_THRESHOLD = 5_000;

    private record Entry(SubmissionLink link, AtomicReference<AnnotationState> annotation) {
    }

    private final Map<String, Entry> byToken = new ConcurrentHashMap<>();
    private final Map<String, String> tokenByInternal = new ConcurrentHashMap<>();
    private final Map<String, String> tokenByConversation = new ConcurrentHashMap<>();

    @Override
    public void record(SubmissionLink link) {
        Entry previous = byToken.putIfAbsent(link.token(),
                new Entry(link, new AtomicReference<>(AnnotationState.NONE)));
        if (previous != null) {
            return; // replay: first record wins, indexes already point here
        }
        tokenByInternal.putIfAbsent(link.internalCaseNumber(), link.token());
        if (link.conversationId() != null) {
            tokenByConversation.putIfAbsent(link.conversationId(), link.token());
        }
        int size = byToken.size();
        if (size > SIZE_WARN_THRESHOLD && size % 1_000 == 0) {
            log.warn("submission link table at {} entries (in-memory, never evicts;"
                    + " persistence is the Phase-2 precondition)", size);
        }
    }

    @Override
    public Optional<SubmissionLink> byToken(String token) {
        return Optional.ofNullable(byToken.get(token)).map(Entry::link);
    }

    @Override
    public Optional<SubmissionLink> byInternalCaseNumber(String internalCaseNumber) {
        return Optional.ofNullable(tokenByInternal.get(internalCaseNumber)).flatMap(this::byToken);
    }

    @Override
    public Optional<SubmissionLink> byConversationId(String conversationId) {
        return Optional.ofNullable(tokenByConversation.get(conversationId)).flatMap(this::byToken);
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
