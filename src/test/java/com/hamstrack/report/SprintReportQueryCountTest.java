package com.hamstrack.report;

import com.hamstrack.report.dto.ReportMeasure;
import com.hamstrack.report.service.SprintBurnupService;
import com.hamstrack.report.service.SprintReviewService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cost claim for R4 (§2.3 "the cheapest report in the set", §12 "Performance"): both sprint
 * reports are a <strong>fixed, small number of statements</strong>, independent of how large the
 * sprint is and independent of how the sprint was chosen. Same shape as
 * {@code CycleTimeQueryCountTest} and {@code PermissionResolutionQueryCountTest} — the exact number
 * IS the design claim, so a tripwire that fires when it moves is the point.
 *
 * <p><strong>7 = 4 + 3.</strong> Four statements are the project-scoped tenancy resolution every
 * project endpoint pays. Three are the report, and they are the same three for both:
 * <ul>
 *   <li>the sprint — {@code findByIdAndProject}, or {@code findActiveByProject} when the caller
 *       named none;</li>
 *   <li>the ledger, once, joined to {@code issues};</li>
 *   <li>the {@code meta} scalars — the project's earliest issue and the ledger's distinct-issue
 *       count, in <strong>one</strong> statement. The count was added in R4 round 2 (it must be
 *       taken above the row cap, not from the rows that survived it) and deliberately rode on the
 *       existing scalar rather than becoming an eighth statement: it is a subquery over the same
 *       {@code idx_sprint_scope_events_sprint} range the ledger read already scans, so it costs no
 *       round trip and this number did not move.</li>
 * </ul>
 *
 * <p><strong>Both reports cost the same because they run the same query</strong>, and that is worth
 * pinning rather than assuming: the burn-up's lines, its scope-change log and all five review lists
 * are derived in memory from one ledger read. The failure this guards against is not an N+1 over
 * issues — that would be caught by the data-independence assertion — but the far likelier
 * "the review needs the issues too, let's fetch them", which would be invisible in every functional
 * test and would double the cost of the cheapest report in the epic.
 *
 * <p>The default-sprint path is measured separately for the same reason: resolving "the ACTIVE
 * sprint" is one statement, not a list-and-filter, and a picker that cost a page of sprints would
 * make the parameterless request — the one the SPA sends first — the expensive one.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class SprintReportQueryCountTest extends SprintReportTestBase {

    private static final int RESOLUTION_STATEMENTS = 4;
    private static final int REPORT_STATEMENTS = 3;

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired SprintBurnupService sprintBurnupService;
    @Autowired SprintReviewService sprintReviewService;

    @Test
    void bothSprintReportsAreThreeStatementsWhateverTheSprintHolds() throws Exception {
        var small = newProject();
        var smallSprint = sprintOf(small, 2);
        var large = newProject();
        var largeSprint = sprintOf(large, 25);

        // Warm: the first resolution in a fresh process also loads the built-in roles.
        runBurnup(small, smallSprint);
        runBurnup(large, largeSprint);
        runReview(small, smallSprint);
        runReview(large, largeSprint);

        long smallBurnup = count(() -> runBurnup(small, smallSprint));
        long largeBurnup = count(() -> runBurnup(large, largeSprint));
        long smallReview = count(() -> runReview(small, smallSprint));
        long largeReview = count(() -> runReview(large, largeSprint));

        assertThat(smallBurnup)
                .as(() -> "the burn-up took " + smallBurnup + " statements, not "
                  + (RESOLUTION_STATEMENTS + REPORT_STATEMENTS) + " (" + RESOLUTION_STATEMENTS
                  + " for the tenancy resolution + the sprint, the ledger and meta.firstIssueAt). "
                  + "If it is higher, something is reading issues one at a time — most likely a "
                  + "per-event lookup of the issue behind a ledger row.")
                .isEqualTo(RESOLUTION_STATEMENTS + REPORT_STATEMENTS);

        assertThat(smallReview)
                .as("the sprint review took " + smallReview + " statements and the burn-up "
                  + smallBurnup + ". They share one ledger query by design; a difference means one "
                  + "of them started fetching the issues separately.")
                .isEqualTo(smallBurnup);

        assertThat(smallBurnup)
                .as("the sprint reports are data-dependent: " + smallBurnup + "/" + smallReview
                  + " statements for a 2-issue sprint vs " + largeBurnup + "/" + largeReview
                  + " for a 25-issue one. The row count may grow; the statement count may not.")
                .isEqualTo(largeBurnup);
        assertThat(smallReview)
                .as("the sprint reports are data-dependent: " + smallBurnup + "/" + smallReview
                  + " statements for a 2-issue sprint vs " + largeBurnup + "/" + largeReview
                  + " for a 25-issue one. The row count may grow; the statement count may not.")
                .isEqualTo(largeReview);
    }

    @Test
    void defaultingToTheActiveSprintCostsExactlyAsMuchAsNamingIt() throws Exception {
        var ctx = newProject();
        var sprint = sprintOf(ctx, 3);

        runBurnup(ctx, sprint);
        long named = count(() -> runBurnup(ctx, sprint));
        long defaulted = count(() -> runBurnup(ctx, null));

        assertThat(named)
                .as("naming the sprint cost " + named + " statements and defaulting to the ACTIVE one "
                  + defaulted + ". The picker is one statement either way; if the default is more, "
                  + "it is listing sprints and filtering in Java.")
                .isEqualTo(defaulted);
    }

    // ------------------------------------------------------------------ plumbing

    private void runBurnup(Ctx ctx, UUID sprintId) {
        sprintBurnupService.burnup(ctx.owner(), ctx.wsId(), ctx.projectId(), sprintId,
                ReportMeasure.POINTS);
    }

    private void runReview(Ctx ctx, UUID sprintId) {
        sprintReviewService.review(ctx.owner(), ctx.wsId(), ctx.projectId(), sprintId);
    }

    /** A started sprint holding {@code issues} issues, half of them estimated, one of them done. */
    private UUID sprintOf(Ctx ctx, int issues) throws Exception {
        var ids = new UUID[issues];
        for (int i = 0; i < issues; i++) {
            var issue = i % 2 == 0
                    ? createIssue(ctx, "work " + i, "\"storyPoints\":" + (i + 1))
                    : createIssue(ctx, "work " + i);
            ids[i] = idOf(issue);
            if (i == 0) {
                markDone(ctx, numberOf(issue));
            }
        }
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, ids).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        return sprint;
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
