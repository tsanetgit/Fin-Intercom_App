package org.tsanet.connector.intercom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Durable link table on a local SQLite file (docs/decisions.md D12). Same never-evicting
 * contract as the in-memory table, but a restart no longer orphans every case: this is
 * the Phase-2 inbound precondition D10 named.
 *
 * <p>Durability choices, each load-bearing:
 * <ul>
 * <li>{@code synchronous=FULL} under WAL — NORMAL survives an application crash but can
 *     lose the last commits on power loss, which is a {@code record()} that returned
 *     while the link vanished: the one outcome D10 forbids.</li>
 * <li>{@code ON CONFLICT(token) DO NOTHING}, never {@code INSERT OR IGNORE} — OR IGNORE
 *     swallows every constraint failure, so a malformed link (null token, null internal
 *     case number) would disappear silently instead of throwing.</li>
 * <li>Every {@link SQLException} surfaces as an unchecked failure rather than a logged
 *     warning. A caller that proceeds past a failed {@code record()} has submitted a
 *     partner case with no route home, discoverable only when annotation cannot find it.</li>
 * <li>Short-lived connections from a configured {@link SQLiteDataSource}: sqlite-jdbc
 *     connections are not safely shared across threads, and the drain thread
 *     ({@code annotationState}) and canvas threads ({@code record}) both write. It also
 *     keeps a Postgres swap at GA a DataSource change rather than a rewrite.</li>
 * </ul>
 *
 * <p>{@code annotation_state} assumes a single writer, the event drain, which only writes
 * it for a token it has already found here — so the row exists by the time the state is
 * written. A multi-threaded drain would need a compare-and-set
 * ({@code UPDATE ... WHERE token = ? AND annotation_state = ?}) instead.
 *
 * <p>Deployment precondition: the file must live on storage that outlives the container.
 * On a container overlay filesystem this class is exactly as volatile as the maps it
 * replaces, and nothing here can detect that — hence the absolute path in the boot log.
 */
public class SqliteSubmissionLinkTable implements SubmissionLinkTable {

    private static final Logger log = LoggerFactory.getLogger(SqliteSubmissionLinkTable.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS submission_link (
              seq                  INTEGER PRIMARY KEY AUTOINCREMENT,
              token                TEXT NOT NULL UNIQUE,
              internal_case_number TEXT NOT NULL,
              workspace_id         TEXT,
              conversation_id      TEXT,
              ticket_id            TEXT,
              partner_display      TEXT,
              respond_by           TEXT,
              test_case            INTEGER NOT NULL,
              submitted_by         TEXT,
              submitted_at         TEXT,
              annotation_state     TEXT NOT NULL,
              case_state           TEXT NOT NULL DEFAULT 'PENDING',
              case_number          TEXT
            )""";
    private static final String INDEX_INTERNAL =
            "CREATE INDEX IF NOT EXISTS idx_submission_link_internal ON submission_link(internal_case_number)";
    private static final String INDEX_CONVERSATION =
            "CREATE INDEX IF NOT EXISTS idx_submission_link_conversation ON submission_link(conversation_id)";
    // NOT unique, and that is load-bearing: one support ticket can legitimately anchor
    // SEVERAL cases, because an agent may escalate one customer problem to two
    // partners. A unique index here looked right ("one ticket per inbound case") and
    // would have broken multi-partner escalation — the existing contract test caught
    // it. Ambiguity is handled in byTicketId instead, where it can refuse rather than
    // guess.
    private static final String INDEX_TICKET =
            "CREATE INDEX IF NOT EXISTS idx_submission_link_ticket ON submission_link(ticket_id)";

    private static final String COLUMNS = "token, internal_case_number, workspace_id, conversation_id,"
            + " ticket_id, partner_display, respond_by, test_case, submitted_by, submitted_at,"
            + " case_number";

    private final DataSource dataSource;

    public SqliteSubmissionLinkTable(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("connector.links.sqlite-path is required when"
                    + " connector.links.store=sqlite; refusing to start without a link store");
        }
        Path file = Paths.get(path).toAbsolutePath();
        Path parent = file.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("cannot create link-table directory " + parent, e);
            }
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        // Both the event drain and canvas threads write; without this a concurrent
        // write fails SQLITE_BUSY instead of waiting.
        config.setBusyTimeout(5_000);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + file);
        this.dataSource = ds;
        initialize();
        // Absolute path, because "links.db" in a container says nothing about whether
        // the volume behind it survives a restart.
        log.info("submission link table: sqlite at {} ({} links)", file, size());
    }

    /**
     * Columns added after the first shipped schema, in the order they arrived. SQLite
     * allows NOT NULL on an added column only with a constant default, which is why
     * case_state carries one; existing outbound rows becoming PENDING is harmless
     * because the state only governs inbound response flows.
     */
    private static final Map<String, String> EVOLVED_COLUMNS = new LinkedHashMap<>(Map.of(
            "case_state", "TEXT NOT NULL DEFAULT 'PENDING'",
            "case_number", "TEXT"));

    /**
     * Creates the table on a fresh file, and EVOLVES one that already exists.
     *
     * <p>The evolution half is not optional here. {@code CREATE TABLE IF NOT EXISTS} is
     * a no-op against an existing table, and D11/D12 require this file to live on
     * storage that outlives the container — so upgrading an existing database is the
     * NORMAL restart, not an edge case. Without this, a new column turns every
     * operation into "no such column" while the connector still boots healthy and even
     * logs a correct link count, because size() touches only old columns. That is
     * worse than the volatile store the durable one replaced, and silent at the one
     * moment somebody is watching (#17 QA round).
     *
     * <p>Construction ends by selecting every column, so a schema that is still wrong
     * fails at deploy instead of poisoning writes later. That is D11's own standard —
     * fail where someone is looking — applied to schema rather than storage.
     */
    private void initialize() {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(DDL);
            Set<String> present = new java.util.HashSet<>();
            try (ResultSet rs = s.executeQuery("SELECT name FROM pragma_table_info('submission_link')")) {
                while (rs.next()) {
                    present.add(rs.getString(1));
                }
            }
            for (Map.Entry<String, String> column : EVOLVED_COLUMNS.entrySet()) {
                if (!present.contains(column.getKey())) {
                    log.info("submission link table: adding column {}", column.getKey());
                    s.executeUpdate("ALTER TABLE submission_link ADD COLUMN "
                            + column.getKey() + " " + column.getValue());
                }
            }
            s.executeUpdate(INDEX_INTERNAL);
            s.executeUpdate(INDEX_CONVERSATION);
            s.executeUpdate(INDEX_TICKET);
            // Fail loudly rather than at the first write: touch every column the code
            // will use, including the ones not in COLUMNS.
            s.executeQuery("SELECT " + COLUMNS + ", annotation_state, case_state"
                    + " FROM submission_link LIMIT 1").close();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot initialize the submission link table", e);
        }
    }

    @Override
    public boolean record(SubmissionLink link) {
        // One row, two indexes maintained by SQLite: unlike three hand-kept maps, a
        // partially-recorded link is not representable.
        String sql = "INSERT INTO submission_link (" + COLUMNS + ", annotation_state)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT(token) DO NOTHING";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, link.token());
            ps.setString(2, link.internalCaseNumber());
            ps.setString(3, link.workspaceId());
            ps.setString(4, link.conversationId());
            ps.setString(5, link.ticketId());
            ps.setString(6, link.partnerDisplay());
            ps.setString(7, link.respondBy());
            ps.setInt(8, link.testCase() ? 1 : 0);
            ps.setString(9, link.submittedBy());
            ps.setString(10, link.submittedAt() == null ? null : link.submittedAt().toString());
            ps.setString(11, link.caseNumber());
            ps.setString(12, AnnotationState.NONE.name());
            // 0 rows means ON CONFLICT(token) suppressed the insert: this caller lost
            // the claim. 1 means it won and owns the platform write that follows.
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot record a submission link", e);
        }
    }

    @Override
    public Optional<SubmissionLink> byToken(String token) {
        return queryOne("SELECT " + COLUMNS + " FROM submission_link WHERE token = ?", token);
    }

    @Override
    public Optional<SubmissionLink> byInternalCaseNumber(String internalCaseNumber) {
        // ASC LIMIT 1: the in-memory index is putIfAbsent, so the FIRST recorded token
        // wins. Two links can legitimately share an internal case number — that is D2's
        // residual duplicate window — and reconcile must keep answering with the same
        // one it answered with before.
        return queryOne("SELECT " + COLUMNS + " FROM submission_link WHERE internal_case_number = ?"
                + " ORDER BY seq ASC LIMIT 1", internalCaseNumber);
    }

    @Override
    public List<SubmissionLink> byConversationId(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        String sql = "SELECT " + COLUMNS + " FROM submission_link WHERE conversation_id = ?"
                + " ORDER BY seq DESC";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                List<SubmissionLink> found = new ArrayList<>();
                while (rs.next()) {
                    found.add(read(rs));
                }
                return List.copyOf(found);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read submission links by conversation", e);
        }
    }

    @Override
    public AnnotationState annotationState(String token) {
        String sql = "SELECT annotation_state FROM submission_link WHERE token = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return AnnotationState.NONE;
                }
                // valueOf, deliberately: an unreadable state must not read as NONE, which
                // would re-run a completed annotation and duplicate the note.
                return AnnotationState.valueOf(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read annotation state", e);
        }
    }

    @Override
    public void annotationState(String token, AnnotationState state) {
        String sql = "UPDATE submission_link SET annotation_state = ? WHERE token = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setString(2, token);
            // D3 says verify effects because a remote 200 can lie. A local ACID UPDATE
            // does not need a read-back: the rowcount already is the receipt. Absent
            // token stays a no-op (as in memory), but a silent one would hide an
            // annotation that believes it recorded its progress and did not.
            if (ps.executeUpdate() == 0) {
                log.warn("annotation state {} written for an unknown link token", state);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot write annotation state", e);
        }
    }

    @Override
    public boolean releaseClaim(String token) {
        if (token == null) {
            return false;
        }
        // The anchor guard lives in the WHERE clause, so it is enforced by the database
        // rather than by a read-then-decide that another writer could invalidate.
        String sql = "DELETE FROM submission_link WHERE token = ?"
                + " AND ticket_id IS NULL AND conversation_id IS NULL";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot release a submission link claim", e);
        }
    }

    @Override
    public void anchor(String token, String ticketId) {
        if (token == null || ticketId == null || ticketId.isBlank()) {
            return; // a blank id is not an anchor; see the interface javadoc
        }
        // Write-once in the WHERE clause, the same way releaseClaim's guard is: the
        // database refuses to re-point a live case rather than the caller remembering
        // not to ask.
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE submission_link SET ticket_id = ? WHERE token = ? AND ticket_id IS NULL")) {
            ps.setString(1, ticketId);
            ps.setString(2, token);
            if (ps.executeUpdate() == 0) {
                log.error("tsanet_audit event=anchor_orphaned token={} ticket={}", token, ticketId);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot anchor a submission link", e);
        }
    }

    @Override
    public Optional<SubmissionLink> byTicketId(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        List<SubmissionLink> matches = queryList(
                "SELECT " + COLUMNS + " FROM submission_link WHERE ticket_id = ?", ticketId);
        return single(ticketId, matches);
    }

    @Override
    public CaseState caseState(String token) {
        if (token == null) {
            return CaseState.PENDING;
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT case_state FROM submission_link WHERE token = ?")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                // valueOf deliberately, as with annotation_state: an unreadable state
                // must not read as PENDING, which would re-offer an action already taken.
                return rs.next() ? CaseState.valueOf(rs.getString(1)) : CaseState.PENDING;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the case state", e);
        }
    }

    @Override
    public boolean caseState(String token, CaseState expected, CaseState next) {
        if (token == null || expected == null || next == null) {
            return false;
        }
        // Compare-and-set in the WHERE clause: two panels racing on one ticket are
        // arbitrated by the database, not by a read-then-write in the caller.
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE submission_link SET case_state = ? WHERE token = ? AND case_state = ?")) {
            ps.setString(1, next.name());
            ps.setString(2, token);
            ps.setString(3, expected.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot move the case state", e);
        }
    }

    @Override
    public void caseNumber(String token, String caseNumber) {
        if (token == null || caseNumber == null || caseNumber.isBlank()) {
            return;
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE submission_link SET case_number = ? WHERE token = ?")) {
            ps.setString(1, caseNumber);
            ps.setString(2, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot record the case number", e);
        }
    }

    @Override
    public int size() {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM submission_link")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot count submission links", e);
        }
    }

    private Optional<SubmissionLink> queryOne(String sql, String key) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read a submission link", e);
        }
    }

    /**
     * Exactly-one-or-nothing. Several links CAN share a ticket (one support ticket
     * escalated to two partners), and a response flow acting on the wrong one would
     * answer the wrong partner — so ambiguity refuses rather than picking, the same
     * way findSubmission refuses a multiple match instead of confirming one of them.
     */
    private static Optional<SubmissionLink> single(String ticketId, List<SubmissionLink> matches) {
        if (matches.size() > 1) {
            log.warn("tsanet_audit event=link_ambiguous_for_ticket ticket={} matches={}",
                    ticketId, matches.size());
            return Optional.empty();
        }
        return matches.stream().findFirst();
    }

    private List<SubmissionLink> queryList(String sql, String key) {
        List<SubmissionLink> found = new java.util.ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read submission links", e);
        }
        return found;
    }

    private static SubmissionLink read(ResultSet rs) throws SQLException {
        String submittedAt = rs.getString(10);
        return new SubmissionLink(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getInt(8) != 0,
                rs.getString(9),
                submittedAt == null ? null : Instant.parse(submittedAt),
                rs.getString(11));
    }
}
