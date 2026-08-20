package org.tsanet.connector.tsanet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Age-based eviction for the SDK operational cache (connect-sdk.db), issue #66: the
 * cache holds case content (summaries, note text, response details, raw webhook
 * payloads) and had no eviction, so content outlived its operational usefulness in
 * both the live store and the backup window. Safe because the cache is a CACHE, not
 * the source of truth: the panel and the reconciliation poller re-fetch from the
 * Connect API by token (verified on #66).
 *
 * <p>Scope, from the upstream schema (connect-library {@code DatabaseInitializer} —
 * read, not imported: runtime-core carries no com.tsanet dependency by rule, D6, and
 * this class opens its own JDBC connection to the same file for the same reason):
 * TERMINAL cases (status CLOSED or REJECTED, matching the adapter's own terminal set —
 * the SDK stores the raw Connect status enum string) older than the window are deleted
 * with their case_note / case_response / attachment_config / attachment_forward_result
 * children in one transaction; orphaned children whose parent was evicted earlier age
 * out too; webhook_inbound_event rows (raw_payload carriers) age out by received_at.
 * user_context, partner_selection, collaboration_request_form (company-keyed) and
 * webhook_subscription are reference/config shapes and are left alone. OPEN cases
 * never age out, however old: an unresolved case is live state, not residue.
 *
 * <p>Timestamps in that schema are {@code Instant.now().toString()} (ISO-8601 UTC,
 * "Z" suffix; upstream receipt: CollaborationRequestRepository), so a lexicographic
 * compare against a cutoff in the same format is correct to within sub-second
 * fraction-length noise at the boundary — irrelevant at retention scale.
 * COALESCE(updated_at, fetched_at) orders the parent-age decision; fetched_at is NOT
 * NULL in every table, so the comparison never goes NULL-silent.
 *
 * <p>OFF by default (blank retention = the bean does not even register; the
 * {@link Enabled} condition requires non-blank text, so a template's empty env var
 * stays off). Member opt-in per the connector-features convention; Anthropic asked
 * for a window (#66) and names the number.
 */
@Component
@Conditional(SdkCacheRetentionSweep.Enabled.class)
public class SdkCacheRetentionSweep {

    /** Registers the bean only for non-blank retention — "" in a template stays OFF. */
    static class Enabled implements org.springframework.context.annotation.ConfigurationCondition {
        @Override
        public ConfigurationPhase getConfigurationPhase() {
            return ConfigurationPhase.REGISTER_BEAN;
        }

        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String v = context.getEnvironment().getProperty("connector.sdk-cache.retention", "");
            return v != null && !v.isBlank();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SdkCacheRetentionSweep.class);

    /** Chunk size for IN-list deletes: works on every sqlite build (no DELETE..LIMIT reliance). */
    private static final int CHUNK = 500;

    private final Duration retention;
    private final String sqlitePath;
    private boolean warnedMissingFile;

    public SdkCacheRetentionSweep(
            @Value("${connector.sdk-cache.retention:}") String retention,
            @Value("${gateway.tsanet.sdk.sqlite-path:}") String sqlitePath) {
        Duration parsed = null;
        try {
            // DurationStyle matches the repo's existing convention (reconcile-lookback: 24h):
            // simple style ("30d", "720h") and ISO-8601 both parse.
            parsed = DurationStyle.detectAndParse(retention.trim());
        } catch (RuntimeException e) {
            log.error("tsanet_audit event=sdk_cache_sweep_disabled reason=unparseable_retention value={}", retention);
        }
        if (parsed != null && (parsed.isZero() || parsed.isNegative())) {
            log.error("tsanet_audit event=sdk_cache_sweep_disabled reason=non_positive_retention value={}", retention);
            parsed = null;
        }
        this.retention = parsed;
        this.sqlitePath = sqlitePath == null ? "" : sqlitePath.trim();
        if (this.retention != null && this.sqlitePath.isEmpty()) {
            // Retention configured but no store path: without this line the sweep
            // would no-op silently every tick, which the audit-line rule forbids.
            log.error("tsanet_audit event=sdk_cache_sweep_disabled reason=no_sqlite_path");
        }
    }

    /** Per-table deleted counts for one sweep run; the audit line and the tests read this. */
    public record SweepResult(int cases, int notes, int responses, int attachConfigs,
            int attachForwards, int orphanNotes, int orphanResponses, int orphanAttachConfigs,
            int orphanAttachForwards, int events) {
        int total() {
            return cases + notes + responses + attachConfigs + attachForwards
                    + orphanNotes + orphanResponses + orphanAttachConfigs + orphanAttachForwards + events;
        }
    }

    @Scheduled(fixedDelayString = "${connector.sdk-cache.sweep-interval:PT6H}",
            initialDelayString = "${connector.sdk-cache.sweep-initial-delay:PT10M}")
    public void scheduledSweep() {
        sweep(Instant.now());
    }

    /** Entry point split from the schedule so tests drive it with a pinned clock. */
    public SweepResult sweep(Instant now) {
        if (retention == null || sqlitePath.isEmpty()) {
            return null;
        }
        // Files.exists BEFORE connect: sqlite-jdbc creates the file on connect, and a
        // fake-mode deployment must not grow an empty db plus -wal/-shm litter.
        if (!Files.exists(Path.of(sqlitePath))) {
            if (!warnedMissingFile) {
                log.debug("sdk cache sweep: no database at {} yet; skipping", sqlitePath);
                warnedMissingFile = true;
            }
            return null;
        }
        String cutoff = now.minus(retention).toString();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout = 5000");
                // journal_mode is persistent per-database and belongs to the engine's
                // store; the sweep sets only the timeout.
            }
            c.setAutoCommit(false);
            List<String> doomed = doomedTokens(c, cutoff);
            int notes = 0;
            int responses = 0;
            int attachCfg = 0;
            int attachFwd = 0;
            int cases = 0;
            for (int i = 0; i < doomed.size(); i += CHUNK) {
                List<String> chunk = doomed.subList(i, Math.min(i + CHUNK, doomed.size()));
                notes += deleteByTokens(c, "case_note", "case_token", chunk);
                responses += deleteByTokens(c, "case_response", "case_token", chunk);
                attachCfg += deleteByTokens(c, "attachment_config", "case_token", chunk);
                attachFwd += deleteByTokens(c, "attachment_forward_result", "case_token", chunk);
                cases += deleteByTokens(c, "collaboration_request", "token", chunk);
            }
            c.commit();
            // Orphans: children whose parent was evicted on an earlier run (or never
            // cached), themselves older than the window — the age check protects a
            // child cached mid-fetch before its parent row lands.
            int orphanNotes = orphanDelete(c, "case_note", "fetched_at", cutoff);
            int orphanResponses = orphanDelete(c, "case_response", "fetched_at", cutoff);
            int orphanAttachCfg = orphanDelete(c, "attachment_config", "fetched_at", cutoff);
            int orphanAttachFwd = orphanDelete(c, "attachment_forward_result", "forwarded_at", cutoff);
            c.commit();
            int events;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM webhook_inbound_event WHERE received_at < ?")) {
                ps.setString(1, cutoff);
                events = ps.executeUpdate();
            }
            c.commit();
            SweepResult result = new SweepResult(cases, notes, responses, attachCfg, attachFwd,
                    orphanNotes, orphanResponses, orphanAttachCfg, orphanAttachFwd, events);
            // Always emitted, zeros included: silence is indistinguishable from not-running.
            log.info("tsanet_audit event=sdk_cache_sweep cutoff={} window={} cases={} notes={} responses={}"
                            + " attach_cfg={} attach_fwd={} orphan_notes={} orphan_responses={}"
                            + " orphan_attach_cfg={} orphan_attach_fwd={} events={} total={}",
                    cutoff, retention, result.cases(), result.notes(), result.responses(),
                    result.attachConfigs(), result.attachForwards(), result.orphanNotes(),
                    result.orphanResponses(), result.orphanAttachConfigs(), result.orphanAttachForwards(),
                    result.events(), result.total());
            return result;
        } catch (SQLException e) {
            // SQLITE_BUSY past the timeout lands here too: log, abort this run, let the
            // next fixedDelay tick retry. Never crash the scheduler thread.
            log.warn("tsanet_audit event=sdk_cache_sweep_failed reason={}", e.getMessage());
            return null;
        }
    }

    /** Terminal set matches the adapter's own (CLOSED + REJECTED; PENDINGACTION is not terminal). */
    private static List<String> doomedTokens(Connection c, String cutoff) throws SQLException {
        List<String> tokens = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT token FROM collaboration_request WHERE status IN ('CLOSED','REJECTED')"
                        + " AND COALESCE(updated_at, fetched_at) < ?")) {
            ps.setString(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tokens.add(rs.getString(1));
                }
            }
        }
        return tokens;
    }

    private static int deleteByTokens(Connection c, String table, String column, List<String> tokens)
            throws SQLException {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + " IN (" + in + ")")) {
            for (int i = 0; i < tokens.size(); i++) {
                ps.setString(i + 1, tokens.get(i));
            }
            return ps.executeUpdate();
        }
    }

    private static int orphanDelete(Connection c, String table, String ageColumn, String cutoff)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM " + table + " WHERE " + ageColumn + " < ?"
                        + " AND case_token NOT IN (SELECT token FROM collaboration_request)")) {
            ps.setString(1, cutoff);
            return ps.executeUpdate();
        }
    }
}
