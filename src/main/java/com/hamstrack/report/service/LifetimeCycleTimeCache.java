package com.hamstrack.report.service;

import com.hamstrack.common.config.CacheConfig;
import com.hamstrack.report.repository.AgingReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * project id &rarr; the {@code (p50, p85, sampleSize)} of everything that project has ever
 * finished, for 60 seconds.
 *
 * <h2>Why this exists</h2>
 * {@code AgingReportRepository.completedCycleTimeStats} is the only statement in the reports
 * feature that is <strong>O(project history) with nothing the caller can narrow</strong>: no
 * window, no {@code LIMIT}, no filter, and it grows for the life of the project. Every other
 * report statement is bounded by a validated window or by the row cap. Meanwhile
 * {@code ReportRateLimiter} charges one unit per request regardless of what that request costs,
 * so the cheapest way to spend this server's CPU was to hold the aging page open on the oldest
 * project in the install.
 *
 * <p>The fix keeps the report's claim intact. The line on the aging board says "older than 85% of
 * <em>everything the team has ever completed</em>", and that sentence is why the aggregate has no
 * window in the first place — narrowing it would have made a much smaller claim while looking
 * identical. So the pass still covers the whole history; it just runs <strong>at most once per
 * project per minute per node</strong> instead of once per request.
 *
 * <h2>Why 60 seconds, and why that number is not arbitrary</h2>
 * It is the TTL the response already advertises: {@code ReportController} answers every report
 * with {@code Cache-Control: private, max-age=60}, so a client is already entitled to show a
 * reader a minute-old copy of these exact numbers. Serving them from a minute-old server-side
 * snapshot therefore widens no staleness window that the contract did not already permit — it
 * moves an existing allowance from the browser to the server, where it also helps the second
 * reader. This cache uses {@link CacheConfig}'s shared 60 s spec deliberately (see
 * {@link CacheConfig#REPORT_LIFETIME_CYCLE_TIME_CACHE}); unlike {@code roleView} it is a
 * <em>config-freshness</em> cache, not a security one, so the shared spec is the right home.
 *
 * <h2>What is deliberately NOT cached</h2>
 * The open half of the report — the item list and {@code openCounts} — runs per request, always.
 * That half is bounded by work in flight rather than by history, and it is what the reader is
 * looking at right now: a minute-old open count printed above a live item list could disagree
 * with the rows underneath it, which is precisely the "these numbers don't match" complaint the
 * whole proposal is organised around (§1.5). The percentiles are a reference line drawn from
 * years of history, and a line like that does not visibly move in a minute.
 *
 * <h2>A separate bean, and that is not a style choice</h2>
 * {@code @Cacheable} is applied by a Spring proxy, so a self-invocation from inside
 * {@code AgingReportService} would silently bypass the cache and quietly restore the exact cost
 * this class was added to remove — with no failing test and no error. This is the same reason
 * {@code RolePermissionCache} is separate from {@code WorkspaceAccessService} and
 * {@code ProjectConfigCache} from {@code ProjectConfigService}. <strong>Do not "simplify" it
 * back into its caller.</strong> {@code CycleTimeQueryCountTest} is the tripwire: it pins the
 * warm path at two statements and the cold one at three, so a bypass shows up as a warm path
 * that costs three.
 *
 * <h2>No eviction, on purpose</h2>
 * The bounded TTL is the whole invalidation strategy, exactly as for the taxonomy caches. There
 * is nothing to evict <em>on</em>: the input is every completed issue of the project, so a
 * correct eviction would have to fire on every issue close, every reopen and every
 * {@code closed_at}/{@code started_at} correction — a lot of wiring, on the hot write path, to
 * make a reference line 60 seconds fresher. A missed eviction in such a scheme would be a
 * permanently stale number; a TTL cannot have that bug.
 */
@Service
@RequiredArgsConstructor
public class LifetimeCycleTimeCache {

    private final AgingReportRepository agingReportRepository;

    /**
     * The lifetime cycle-time percentiles of one project, with the sample they came from.
     *
     * <p>{@code sampleSize} is carried rather than recomputed because it is what
     * {@link CycleTimeReportService#MIN_PERCENTILE_SAMPLES} is compared against — the count and
     * the percentiles must describe the same set of rows or the suppression threshold guards a
     * different population than the numbers it is guarding. Keeping the triple together in one
     * immutable record makes that impossible to get wrong, and makes the cached value safe to
     * hand to concurrent readers.
     *
     * @param sampleSize completed issues that also have a {@code started_at} — may be 0
     * @param p50 median cycle time in days, {@code null} when the sample is empty
     * @param p85 85th-percentile cycle time in days, {@code null} when the sample is empty
     */
    public record LifetimeCycleTime(long sampleSize, Double p50, Double p85) {

        /** A project with no completed-and-started work at all. */
        public static final LifetimeCycleTime NONE = new LifetimeCycleTime(0, null, null);
    }

    /**
     * <strong>{@code sync = true} matters here for a different reason than on
     * {@code RolePermissionCache.byId}.</strong> There the concern was the sheer frequency of
     * misses on a hot key; here it is the <em>cost</em> of one: a non-sync {@code @Cacheable}
     * does get-miss &rarr; invoke &rarr; put, so N concurrent readers of one project's aging
     * page at the moment the entry expires each launch their own unbounded aggregate scan —
     * which is the stampede this class exists to prevent, reintroduced at the TTL boundary.
     * {@code sync = true} routes through {@code CaffeineCache.get(key, loader)}, atomic per key,
     * so an expiry costs exactly one scan however many readers arrive together.
     *
     * <p>The caller must pass the <strong>resolved</strong> project id — the one off
     * {@code ProjectContext} after {@code resolveProject}, never a path variable. A cache key
     * taken from an unresolved request parameter would be a cross-tenant hazard of the classic
     * shape: an id nobody proved the caller may see, used to address a shared in-memory map.
     * (It would not actually leak today, because the value is derived from a statement that
     * scopes itself and every caller resolves first — but the key is the kind of thing a later
     * refactor gets wrong, so the requirement is stated rather than left to be inferred.)
     */
    @Cacheable(cacheNames = CacheConfig.REPORT_LIFETIME_CYCLE_TIME_CACHE,
            key = "#resolvedProjectId", sync = true)
    @Transactional(readOnly = true)
    public LifetimeCycleTime forProject(UUID resolvedProjectId) {
        var rows = agingReportRepository.completedCycleTimeStats(resolvedProjectId);
        if (rows.isEmpty()) {
            return LifetimeCycleTime.NONE;
        }
        var row = rows.get(0);
        return new LifetimeCycleTime(
                ((Number) row[0]).longValue(),
                row[1] == null ? null : ((Number) row[1]).doubleValue(),
                row[2] == null ? null : ((Number) row[2]).doubleValue());
    }
}
