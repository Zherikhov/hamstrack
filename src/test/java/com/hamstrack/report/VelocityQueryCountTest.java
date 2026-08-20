package com.hamstrack.report;

import com.hamstrack.report.dto.ReportMeasure;
import com.hamstrack.report.service.VelocityService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * The cost claim for R5 (§2.5 "one grouped query over the ledger, bounded by N"; §12
 * "Performance"): velocity is a <strong>fixed</strong> number of statements, and in particular it is
 * not proportional to how many sprints it samples. Same shape as
 * {@code SprintReportQueryCountTest} — the exact number IS the design claim, so a tripwire that
 * fires when it moves is the point.
 *
 * <p><strong>7 = 4 + 3.</strong> Four are the project-scoped tenancy resolution every project
 * endpoint pays. Three are the report:
 * <ul>
 *   <li>the sprint sample — {@code findRecentCompletedByProject} with a {@code LIMIT N}, which is
 *       also the only place velocity's sprint ids can come from;</li>
 *   <li>the {@code meta} scalars — the project's earliest issue and the sampled ledgers'
 *       distinct-issue count, in one statement, the count taken ABOVE the row cap;</li>
 *   <li><strong>one</strong> sweep across all N ledgers.</li>
 * </ul>
 *
 * <p><strong>The failure this exists to catch is 3N, not N+1.</strong> R4 already built a reader
 * that answers this exact question for one sprint in three statements, and the obvious way to write
 * velocity is to call it in a loop — twelve sprints would then be thirty-six statements plus twelve
 * redundant tenancy resolutions, and every functional test in {@code VelocityApiTest} would still
 * pass. So the assertion that carries this file is the one comparing a 1-sprint project with a
 * 12-sprint one: the row count may grow, the statement count may not.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class VelocityQueryCountTest extends SprintReportTestBase {

    private static final int RESOLUTION_STATEMENTS = 4;
    private static final int REPORT_STATEMENTS = 3;

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired VelocityService velocityService;

    @Test
    void velocityIsThreeStatementsWhateverItSamples() throws Exception {
        var one = newProject();
        completedSprints(one, 1);
        var twelve = newProject();
        completedSprints(twelve, VelocityService.MAX_SPRINTS);

        // Warm: the first resolution in a fresh process also loads the built-in roles.
        run(one, null);
        run(twelve, null);

        long small = count(() -> run(one, null));
        long large = count(() -> run(twelve, VelocityService.MAX_SPRINTS));

        assert small == RESOLUTION_STATEMENTS + REPORT_STATEMENTS
                : "velocity took " + small + " statements, not "
                  + (RESOLUTION_STATEMENTS + REPORT_STATEMENTS) + " (" + RESOLUTION_STATEMENTS
                  + " for the tenancy resolution + the sprint sample, the ledger sweep and meta).";

        assert small == large
                : "velocity is data-dependent: " + small + " statements for 1 sprint and " + large
                  + " for " + VelocityService.MAX_SPRINTS + ". The likeliest cause is that it now "
                  + "reads one sprint's ledger at a time — which is what SprintLedgerReader does, "
                  + "and is 3N here. One sweep serves them all.";
    }

    /**
     * A project that has never completed a sprint costs one statement LESS, not one more: there is
     * nothing to sweep, so the sweep is not run. Pinned because the natural "fix" for the empty case
     * is a guard that runs the query anyway and discards the result.
     */
    @Test
    void aProjectWithNoCompletedSprintsDoesNotSweepAnything() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "work");

        run(ctx, null);
        long empty = count(() -> run(ctx, null));

        assert empty == RESOLUTION_STATEMENTS + REPORT_STATEMENTS - 1
                : "an empty velocity report took " + empty + " statements, not "
                  + (RESOLUTION_STATEMENTS + REPORT_STATEMENTS - 1)
                  + " — there are no sprints to sweep, so there is no sweep to run.";
    }

    // ------------------------------------------------------------------ plumbing

    private void run(Ctx ctx, Integer sprints) {
        velocityService.velocity(ctx.owner(), ctx.wsId(), ctx.projectId(), sprints,
                ReportMeasure.POINTS);
    }

    /** {@code n} finished sprints, each holding two estimated issues, one of them done. */
    private void completedSprints(Ctx ctx, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            var start = "2025-01-" + String.format("%02d", 1 + i * 2) + "T00:00:00Z";
            var end = "2025-01-" + String.format("%02d", 2 + i * 2) + "T10:00:00Z";
            completedSprint(ctx, "S" + i, start, end, 2, 1, i + 1);
        }
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
