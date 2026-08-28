package org.tsanet.connector.sdk;

import com.tsanet.api.storage.CacheRetention;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteDataSource;

/**
 * Age-based eviction for the SDK operational cache (connect-sdk.db), issue #66,
 * delegating to {@link CacheRetention#sweep} shipped in {@code connect-library}
 * 0.2.0 (Connect_SDK#66) rather than the hand-duplicated SQL this class replaces.
 * Declared directly here rather than in runtime-core because D6 forbids
 * {@code com.tsanet.*} there; this module already carries connect-library
 * transitively via gateway-engine, and now also directly (pom.xml) so the version
 * is not left to transitive resolution.
 *
 * <p>Scope is upstream's, not restated here (see {@link CacheRetention}'s own
 * javadoc) — notably narrower than the sweep this replaces, by design, per Shawn's
 * explicit sign-off: {@code attachment_forward_result} is never evicted (no API
 * read-back path — eviction would be unrecoverable) and children with no cached
 * parent are never age-swept (three fetch paths cache them without a parent row, so
 * for this webhook-driven adapter a parentless child is normal steady state, not
 * residue — round-3 QA on Connect_SDK#66 found the wider eviction unsafe for
 * exactly that shape). Consequence accepted: {@code attachment_forward_result} rows
 * for a swept case become permanently unreachable by any mechanism in this
 * codebase, and parentless children persist until/unless their parent is
 * eventually cached.
 *
 * <p>OFF by default (blank retention = the bean does not even register; the
 * {@link Enabled} condition requires non-blank text, so a template's empty env var
 * stays off). Also only registers under {@code gateway.tsanet.mode=sdk}, matching
 * {@code SdkGatewayConfiguration}'s own gate — there is no {@code connect-sdk.db}
 * to sweep otherwise.
 */
@Component
@ConditionalOnProperty(name = "gateway.tsanet.mode", havingValue = "sdk")
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

    @Scheduled(fixedDelayString = "${connector.sdk-cache.sweep-interval:PT6H}",
            initialDelayString = "${connector.sdk-cache.sweep-initial-delay:PT10M}")
    public void scheduledSweep() {
        sweep(Instant.now());
    }

    /** Entry point split from the schedule so tests drive it with a pinned clock. */
    public CacheRetention.SweepResult sweep(Instant now) {
        if (retention == null || sqlitePath.isEmpty()) {
            return null;
        }
        // Files.exists BEFORE connecting: sqlite-jdbc creates the file (plus
        // -wal/-shm) on connect, and a not-yet-warmed sdk-mode deployment must not
        // grow db litter before the engine itself has written anything.
        if (!Files.exists(Path.of(sqlitePath))) {
            if (!warnedMissingFile) {
                log.debug("sdk cache sweep: no database at {} yet; skipping", sqlitePath);
                warnedMissingFile = true;
            }
            return null;
        }
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + sqlitePath);
        try {
            CacheRetention.SweepResult result = CacheRetention.sweep(new JdbcTemplate(dataSource), retention, now);
            // Always emitted, zeros included: silence is indistinguishable from not-running.
            log.info("tsanet_audit event=sdk_cache_sweep window={} cases={} notes={} responses={}"
                            + " attach_cfg={} events={} total={}",
                    retention, result.cases(), result.notes(), result.responses(),
                    result.attachConfigs(), result.events(), result.total());
            return result;
        } catch (RuntimeException e) {
            // Never crash the scheduler thread: log and let the next fixedDelay tick
            // retry. CacheRetention.sweep() rolls back the failing phase itself
            // before this is thrown, so the store is never left half-committed here.
            if (e instanceof CacheRetention.RetentionSweepException rse) {
                CacheRetention.SweepResult committed = rse.committed();
                log.warn("tsanet_audit event=sdk_cache_sweep_failed phase={} reason={} committed_cases={}"
                                + " committed_notes={} committed_responses={} committed_attach_cfg={}"
                                + " committed_events={}",
                        rse.phase(), e.getMessage(), committed.cases(), committed.notes(),
                        committed.responses(), committed.attachConfigs(), committed.events());
            } else {
                log.warn("tsanet_audit event=sdk_cache_sweep_failed reason={}", e.getMessage(), e);
            }
            return null;
        }
    }
}
