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
              annotation_state     TEXT NOT NULL
            )""";
    private static final String INDEX_INTERNAL =
            "CREATE INDEX IF NOT EXISTS idx_submission_link_internal ON submission_link(internal_case_number)";
    private static final String INDEX_CONVERSATION =
            "CREATE INDEX IF NOT EXISTS idx_submission_link_conversation ON submission_link(conversation_id)";

    private static final String COLUMNS = "token, internal_case_number, workspace_id, conversation_id,"
            + " ticket_id, partner_display, respond_by, test_case, submitted_by, submitted_at";

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

    private void initialize() {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(DDL);
            s.executeUpdate(INDEX_INTERNAL);
            s.executeUpdate(INDEX_CONVERSATION);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot initialize the submission link table", e);
        }
    }

    @Override
    public boolean record(SubmissionLink link) {
        // One row, two indexes maintained by SQLite: unlike three hand-kept maps, a
        // partially-recorded link is not representable.
        String sql = "INSERT INTO submission_link (" + COLUMNS + ", annotation_state)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
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
            ps.setString(11, AnnotationState.NONE.name());
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
        if (token == null || ticketId == null) {
            return;
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE submission_link SET ticket_id = ? WHERE token = ?")) {
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
                submittedAt == null ? null : Instant.parse(submittedAt));
    }
}
