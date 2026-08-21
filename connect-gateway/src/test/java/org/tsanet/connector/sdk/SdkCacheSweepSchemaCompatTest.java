package org.tsanet.connector.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.tsanet.api.storage.DatabaseInitializer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.tsanet.connector.tsanet.SdkCacheRetentionSweep;

/**
 * The check behind the sweep's replicated-DDL comment (QA on #69): runtime-core's
 * {@link SdkCacheRetentionSweep} may not import com.tsanet (D6), so its SQL and its
 * test DDL replicate the upstream schema by hand. THIS module may import upstream, so
 * this test runs the sweep against the REAL schema — created by connect-library's own
 * {@link DatabaseInitializer} — and fails if the upstream shape drifts away from the
 * sweep's SQL (a missing table or renamed column surfaces as a failed run instead of
 * a silently diverging comment).
 */
class SdkCacheSweepSchemaCompatTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
    private static final String OLD = NOW.minus(Duration.ofDays(45)).toString();

    @TempDir
    Path dir;

    @Test
    void sweepRunsCleanAgainstTheRealUpstreamSchema() throws Exception {
        Path db = dir.resolve("connect-sdk.db");
        String url = "jdbc:sqlite:" + db;
        try (SingleConnectionDataSource ds = new SingleConnectionDataSource(url, true)) {
            DatabaseInitializer.createSchema(new JdbcTemplate(ds));
        }
        // Seed through plain JDBC against the REAL columns: a rename upstream fails
        // these inserts; a table drop fails the sweep. Either way the drift is loud.
        try (Connection c = DriverManager.getConnection(url)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO collaboration_request (id, status, summary, token, updated_at, fetched_at)"
                            + " VALUES (1, 'CLOSED', 'old case', 'tok-old', ?, ?)")) {
                ps.setString(1, OLD);
                ps.setString(2, OLD);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO case_note (id, case_token, summary, description, token, fetched_at)"
                            + " VALUES (1, 'tok-old', 'note', 'text', 'note-1', ?)")) {
                ps.setString(1, OLD);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO webhook_inbound_event (event_type, request_token, received_at,"
                            + " signature_valid, cache_synced, raw_payload) VALUES ('created', 'tok-old', ?, 1, 1, '{}')")) {
                ps.setString(1, OLD);
                ps.executeUpdate();
            }
        }

        SdkCacheRetentionSweep.SweepResult r =
                new SdkCacheRetentionSweep("30d", db.toString()).sweep(NOW);

        assertNotNull(r, "a failed run here means the sweep's SQL no longer matches the upstream schema");
        assertEquals(1, r.cases());
        assertEquals(1, r.notes());
        assertEquals(1, r.events());
    }
}
