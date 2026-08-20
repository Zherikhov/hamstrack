package com.hamstrack.report;

import com.hamstrack.report.dto.FlowQuery;
import com.hamstrack.report.dto.ReportInterval;
import com.hamstrack.report.service.FlowReportService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.time.LocalDate;

/**
 * The cost claim behind the whole reports epic (reports-proposal §0, §12 "Performance"):
 * a report is a <strong>fixed, small number of statements</strong>, independent of how
 * much the project contains. Modelled on {@code PermissionResolutionQueryCountTest},
 * which asserts absolute numbers for the same reason this one does — here the exact count
 * <em>is</em> the design claim, and a tripwire that fires when it moves is the point.
 *
 * <p><strong>7 = 4 + 3.</strong> Four statements are the project-scoped tenancy resolution
 * every project endpoint pays (workspace, membership, project, explicit project
 * membership — pinned independently by {@code PermissionResolutionQueryCountTest}). Three
 * are the report itself: created-per-bucket, resolved-per-bucket, and the combined
 * opening-balance + {@code basedOnIssues} counts. All three aggregate inside PostgreSQL;
 * not one issue row is materialised, which is why the number cannot move with the data.
 *
 * <p>The two things that would break this and are easy to do by accident: counting rows in
 * Java after fetching them (the count would start tracking the issue count), and splitting
 * the combined counts query into two "for readability" (the number would quietly become
 * 8 — cheap once, and the precedent that makes the sixth report cost twelve).
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class FlowReportQueryCountTest extends FlowReportTestBase {

    private static final int RESOLUTION_STATEMENTS = 4;
    private static final int REPORT_STATEMENTS = 3;

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired FlowReportService flowReportService;

    @Test
    void oneFlowReportIsThreeStatementsWhateverTheProjectHolds() throws Exception {
        var small = newProject();
        seed(small, 4);
        var large = newProject();
        seed(large, 60);

        // Warm both: the first resolution in a fresh process also loads the built-in roles.
        run(small);
        run(large);

        long smallCount = count(() -> run(small));
        long largeCount = count(() -> run(large));

        assert smallCount == RESOLUTION_STATEMENTS + REPORT_STATEMENTS
                : "the flow report took " + smallCount + " statements, not "
                  + (RESOLUTION_STATEMENTS + REPORT_STATEMENTS) + " (" + RESOLUTION_STATEMENTS
                  + " for the tenancy resolution + " + REPORT_STATEMENTS + " for the report: "
                  + "created-per-bucket, resolved-per-bucket, and the combined counts). If this "
                  + "is higher, something is reading issues instead of aggregating them, or the "
                  + "combined opening-balance/basedOnIssues query has been split up.";

        assert smallCount == largeCount
                : "the flow report is data-dependent: " + smallCount + " statements for a project "
                  + "with 4 issues vs " + largeCount + " for one with 60. Every number in this "
                  + "report is computed by PostgreSQL; nothing may loop over issues.";
    }

    // ------------------------------------------------------------------ plumbing

    /** A fixed, absolute window so the assertion never depends on the day it runs. */
    private void run(Ctx ctx) {
        flowReportService.flow(ctx.owner(), ctx.wsId(), ctx.projectId(),
                new FlowQuery(LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-31"),
                        ReportInterval.DAY, null, null, null));
    }

    /** Issues spread across the window and before it, half of them closed. */
    private void seed(Ctx ctx, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            var day = String.format("2025-03-%02d", (i % 28) + 1);
            var issue = issueCreatedAt(ctx, "issue " + i, day + "T09:00:00Z");
            if (i % 2 == 0) {
                closedAt(ctx, issue.get("number").asLong(),
                        java.util.UUID.fromString(issue.get("id").asText()), day + "T17:00:00Z");
            }
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
