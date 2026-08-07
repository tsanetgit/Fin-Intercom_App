package org.tsanet.connector.intercom;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Never-evicting in-memory link table (Phase 1); see the interface for why. Volatile by
 * construction, so {@link LinkTableConfiguration} wires it only outside the prod profile;
 * {@link SqliteSubmissionLinkTable} is the durable store (docs/decisions.md D12).
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
    public boolean record(SubmissionLink link) {
        // Both routing keys validated BEFORE the first map mutation, and as one check:
        // caught at tokenByInternal instead, a null internal case number would leave the
        // link in byToken and out of the internal-case index — the partially-recorded
        // link that the single-row SQL store cannot represent. IllegalStateException
        // rather than the NPE the maps raise on their own, so a deliberate rejection is
        // distinguishable from an accidental one (SqliteSubmissionLinkTable throws the
        // same; the contract pins the type).
        if (link.token() == null || link.internalCaseNumber() == null) {
            throw new IllegalStateException("cannot record a submission link without both a"
                    + " token and an internal case number; the case would have no route home");
        }
        Entry previous = byToken.putIfAbsent(link.token(),
                new Entry(link, new AtomicReference<>(AnnotationState.NONE)));
        if (previous != null) {
            return false; // replay: first record wins, indexes already point here
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
        return true;
    }

    // Why every keyed entry point below tolerates a null key: the keys arrive from
    // webhook payloads and from links whose optional fields are null, and
    // ConcurrentHashMap throws on a null key where the SQL store's `WHERE col = NULL`
    // simply matches nothing — so an unguarded map here NPEs the event drain on a path
    // the SQL store handles. The normative rule lives on SubmissionLinkTable and is
    // pinned across the whole keyed surface by SubmissionLinkTableContractTest.

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
        Entry entry = entryFor(token);
        return entry == null ? AnnotationState.NONE : entry.annotation().get();
    }

    @Override
    public void annotationState(String token, AnnotationState state) {
        // State first, mirroring the SQL store, which binds it as parameter 1 and so
        // throws on a null state whatever the token is. A null STATE is not a missing
        // key: accepting it would leave this store answering null from a method whose
        // contract is NONE-when-unset, and the drain reads that to decide whether an
        // annotation already ran.
        Objects.requireNonNull(state, "annotation state");
        Entry entry = entryFor(token);
        if (entry == null) {
            // Warns rather than passing silently, as the SQL store's zero-rowcount does:
            // a caller that believes it recorded annotation progress and did not is the
            // failure this line exists to make visible. A null token lands here too,
            // exactly as `UPDATE ... WHERE token = NULL` matches no row.
            log.warn("annotation state {} written for an unknown link token", state);
            return;
        }
        entry.annotation().set(state);
    }

    private Entry entryFor(String token) {
        return token == null ? null : byToken.get(token);
    }

    @Override
    public int size() {
        return byToken.size();
    }
}
