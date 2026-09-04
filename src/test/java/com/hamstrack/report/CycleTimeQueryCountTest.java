package com.hamstrack.report;

import com.hamstrack.common.config.CacheConfig;
import com.hamstrack.report.dto.CycleTimeQuery;
import com.hamstrack.report.service.AgingReportService;
import com.hamstrack.report.service.CycleTimeReportService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cost claim, for R3 (reports-proposal §0, §12 "Performance"): a report is a <strong>fixed,
 * small number of statements</strong>, independent of how much the project contains. Same shape as
 * {@code FlowReportQueryCountTest} and {@code PermissionResolutionQueryCountTest} — the exact
 * number <em>is</em> the design claim, so a tripwire that fires when it moves is the point.
 *
 * <p><strong>6 = 4 + 2, twice.</strong> Four statements are the project-scoped tenancy resolution
 * every project endpoint pays. Two are each report:
 * <ul>
 *   <li>cycle time — the bounded row query and the combined aggregate query;</li>
 *   <li>aging — the bounded row query and the live open-counts query.</li>
 * </ul>
 *
 * <p><strong>Aging has a third statement, and its absence from the count above is the assertion
 * about the cache</strong> (round 2). The lifetime cycle-time percentiles are the only
 * O(project-history) read in the feature — no window, no cap, growing forever — so they sit
 * behind {@code LifetimeCycleTimeCache} for 60 s per project. That makes the warm path two and
 * the cold path three, and {@link #theLifetimePercentilesAreCachedAndNothingElseIs()} pins the
 * difference at exactly one by evicting that one cache and re-measuring. Pinning both numbers
 * matters more than pinning either: a warm path of three means the cache is being bypassed (the
 * classic {@code @Cacheable} self-invocation trap — it is a separate bean for that reason), and
 * a cold path of two means something else quietly started caching a live number.
 *
 * <p>Aging's <strong>columns cost zero</strong>: they come from {@code ProjectConfigService},
 * which is cached, and this suite warms it like any request after the first would. That is worth
 * pinning rather than assuming, because "read the workflow to build the columns" is a query
 * per request if the cache is ever bypassed, and it would be invisible in every functional test.
 *
 * <p>Two things would break this and are easy to do by accident: counting or grouping rows in Java
 * after fetching them <em>more</em> of them (the count starts tracking the data), and splitting a
 * combined query "for readability" — cheap once, and the precedent that makes the sixth report
 * cost twelve.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class CycleTimeQueryCountTest extends CycleTimeTestBase {

    private static final int RESOLUTION_STATEMENTS = 4;
    private static final int REPORT_STATEMENTS = 2;

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired CycleTimeReportService cycleTimeReportService;
    @Autowired AgingReportService agingReportService;
    @Autowired CacheManager cacheManager;

    @Test
    void oneCycleTimeReportIsTwoStatementsWhateverTheProjectHolds() throws Exception {
        var small = newProject();
        seed(small, 3);
        var large = newProject();
        seed(large, 25);

        runCycleTime(small);
        runCycleTime(large);

        long smallCount = count(() -> runCycleTime(small));
        long largeCount = count(() -> runCycleTime(large));

        assertThat(smallCount)
                .as(() -> "the cycle-time report took " + smallCount + " statements, not "
                  + (RESOLUTION_STATEMENTS + REPORT_STATEMENTS) + " (" + RESOLUTION_STATEMENTS
                  + " for the tenancy resolution + " + REPORT_STATEMENTS + " for the report: the "
                  + "bounded item query and the combined aggregate query). If this is higher, "
                  + "something is reading issues one at a time — most likely a per-item lookup of "
                  + "the project key or the issue type.")
                .isEqualTo(RESOLUTION_STATEMENTS + REPORT_STATEMENTS);

        assertThat(smallCount)
                .as("the cycle-time report is data-dependent: " + smallCount + " statements for a "
                  + "project with 3 completed issues vs " + largeCount + " for one with 25. The "
                  + "row count may grow; the statement count may not.")
                .isEqualTo(largeCount);
    }

    @Test
    void oneAgingReportIsTwoStatementsAndItsColumnsAreFree() throws Exception {
        var small = newProject();
        seed(small, 3);
        var large = newProject();
        seed(large, 25);

        // Warm both — the first resolution in a fresh process also loads the built-in roles, and
        // the first aging call populates the project-config cache the columns are read from.
        runAging(small);
        runAging(large);

        long smallCount = count(() -> runAging(small));
        long largeCount = count(() -> runAging(large));

        assertThat(smallCount)
                .as(() -> "the aging report took " + smallCount + " statements, not "
                  + (RESOLUTION_STATEMENTS + REPORT_STATEMENTS) + ". Either a combined query was "
                  + "split, or the workflow columns stopped coming from the cached "
                  + "ProjectConfigService and are being read per request.")
                .isEqualTo(RESOLUTION_STATEMENTS + REPORT_STATEMENTS);

        assertThat(smallCount)
                .as("the aging report is data-dependent: " + smallCount + " statements for 3 issues "
                  + "vs " + largeCount + " for 25.")
                .isEqualTo(largeCount);
    }

    /**
     * The cache is real, it fronts exactly one statement, and it fronts the right one.
     *
     * <p>Measured by evicting <strong>only</strong> the lifetime-percentile entry from a
     * fully-warm project, so nothing else can account for the difference — a cold
     * {@code newProject()} would also miss the project-config cache the columns come from and
     * conflate the two. One extra statement on the miss, back to the warm count on the next call.
     *
     * <p>This is the assertion that fails if somebody folds {@code LifetimeCycleTimeCache} back
     * into {@code AgingReportService}: {@code @Cacheable} is proxy-applied, so a self-invocation
     * bypasses it silently, every request pays the unbounded aggregate again, and the only
     * symptom would be a warm path that costs three.
     */
    @Test
    void theLifetimePercentilesAreCachedAndNothingElseIs() throws Exception {
        var ctx = newProject();
        seed(ctx, 8);

        runAging(ctx);                       // warms the config cache AND the percentile cache
        long warm = count(() -> runAging(ctx));

        evictLifetimePercentiles(ctx.projectId());
        long cold = count(() -> runAging(ctx));
        long warmAgain = count(() -> runAging(ctx));

        assertThat(warm)
                .as(() -> "the warm aging report took " + warm + " statements, not "
                  + (RESOLUTION_STATEMENTS + REPORT_STATEMENTS) + ". If it is one higher, the "
                  + "lifetime percentiles are not being served from cache at all — most likely "
                  + "LifetimeCycleTimeCache was folded into its caller, where @Cacheable cannot "
                  + "apply because the call is a self-invocation.")
                .isEqualTo(RESOLUTION_STATEMENTS + REPORT_STATEMENTS);

        assertThat(cold)
                .as(() -> "evicting the lifetime percentiles changed the aging report from " + warm
                  + " statements to " + cold + ", not " + (warm + 1) + ". Exactly one statement "
                  + "is supposed to be behind that cache.")
                .isEqualTo(warm + 1);

        assertThat(warmAgain)
                .as("the aging report did not re-warm: " + warmAgain + " statements after a miss "
                  + "that should have repopulated the cache.")
                .isEqualTo(warm);
    }

    // ------------------------------------------------------------------ plumbing

    /** A fixed, absolute window so the assertion never depends on the day it runs. */
    private void runCycleTime(Ctx ctx) {
        cycleTimeReportService.cycleTime(ctx.owner(), ctx.wsId(), ctx.projectId(),
                new CycleTimeQuery(LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-31"),
                        null, null, null));
    }

    private void runAging(Ctx ctx) {
        agingReportService.aging(ctx.owner(), ctx.wsId(), ctx.projectId());
    }

    /** A mix of completed, in-progress and never-started issues — every branch of both reports. */
    private void seed(Ctx ctx, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            var day = String.format("2025-03-%02d", (i % 27) + 1);
            switch (i % 3) {
                case 0 -> completed(ctx, "done " + i, day + "T09:00:00Z", day + "T10:00:00Z",
                        day + "T17:00:00Z");
                case 1 -> inProgressSince(ctx, "wip " + i, day + "T09:00:00Z", day + "T10:00:00Z");
                default -> neverStarted(ctx, "todo " + i, day + "T09:00:00Z");
            }
        }
    }

    /** Drop one project's cached percentile triple, leaving every other cache warm. */
    private void evictLifetimePercentiles(UUID projectId) {
        var cache = cacheManager.getCache(CacheConfig.REPORT_LIFETIME_CYCLE_TIME_CACHE);
        if (cache == null) {
            throw new AssertionError("no '" + CacheConfig.REPORT_LIFETIME_CYCLE_TIME_CACHE
                                     + "' cache — the aging report is not caching anything, so "
                                     + "every request is paying for the unbounded aggregate");
        }
        cache.evict(projectId);
    }

    private long count(Runnable body) {
        var stats = statistics();
        stats.clear();
        body.run();
        return stats.getPrepareStatementCount();
    }

    private Statistics statistics() {
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        return stats;
    }
}
