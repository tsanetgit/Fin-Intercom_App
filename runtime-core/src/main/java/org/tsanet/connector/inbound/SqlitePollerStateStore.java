package org.tsanet.connector.inbound;

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
import java.time.format.DateTimeParseException;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Durable cursor, on the same SQLite file as the link table.
 *
 * <p>Its own table and its own {@link DataSource} rather than a shared connection:
 * sqlite-jdbc connections are not safely shared across threads, and this is written from
 * the scheduler thread while the link table is written from the drain and canvas
 * threads. Short-lived connections from a configured DataSource is the pattern
 * {@code SqliteSubmissionLinkTable} already uses, for the same reason.
 *
 * <p>Same file on purpose: one volume to mount, one thing to back up, and a cursor that
 * cannot outlive the links it is a cursor over. A cursor surviving a wiped link table
 * would be the worst combination — it would skip the very window whose links were lost.
 *
 * <p>Stores the instant as an ISO-8601 string, not an epoch number. The value is read by
 * humans during incidents far more often than by this class, and the API it is compared
 * against speaks ISO-8601 too.
 */
public class SqlitePollerStateStore implements PollerStateStore {

    private static final Logger log = LoggerFactory.getLogger(SqlitePollerStateStore.class);

    private static final String KEY = "inbound_cursor";
    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS poller_state (
              name       TEXT PRIMARY KEY,
              value      TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )""";

    private final DataSource dataSource;

    public SqlitePollerStateStore(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("a sqlite path is required for the durable poller state store");
        }
        Path file = Paths.get(path).toAbsolutePath();
        Path parent = file.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("cannot create poller-state directory " + parent, e);
            }
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        // FULL, matching the link table: NORMAL can lose the last commits on power loss,
        // and a cursor that advanced in memory but not on disk re-scans — which is the
        // safe direction, but only because guard-create absorbs it. Keeping the two
        // stores on the same durability setting means one thing to reason about.
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setBusyTimeout(5_000);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + file);
        this.dataSource = ds;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(DDL);
            // Fail at construction rather than at the first cycle, the same standard the
            // link table applies to its own schema.
            s.executeQuery("SELECT name, value, updated_at FROM poller_state LIMIT 1").close();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot initialize the poller state table", e);
        }
        log.info("poller state: sqlite at {}", file);
    }

    @Override
    public Optional<Instant> cursor() {
        String sql = "SELECT value FROM poller_state WHERE name = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(Instant.parse(rs.getString(1)));
            }
        } catch (DateTimeParseException corrupt) {
            // An unparseable cursor is treated as ABSENT, not fatal, and loudly. Absent
            // means the poller falls back to the bounded lookback and keeps working;
            // throwing here would wedge every future cycle on one bad row, which is a
            // worse outcome than re-scanning a window guard-create already dedupes.
            log.error("tsanet_audit event=poller_cursor_unparseable reason={} action=falling_back_to_lookback",
                    corrupt.getMessage());
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the reconciliation cursor", e);
        }
    }

    @Override
    public void cursor(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("refusing to store a null cursor");
        }
        String sql = "INSERT INTO poller_state (name, value, updated_at) VALUES (?, ?, ?)"
                + " ON CONFLICT(name) DO UPDATE SET value = excluded.value,"
                + " updated_at = excluded.updated_at";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, KEY);
            ps.setString(2, value.toString());
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            // Surfaced, never swallowed: a cursor that silently failed to persist means
            // the next restart re-scans from an older mark. That is safe but it hides a
            // broken volume, which is the failure D12 is about.
            throw new IllegalStateException("cannot persist the reconciliation cursor", e);
        }
    }
}
