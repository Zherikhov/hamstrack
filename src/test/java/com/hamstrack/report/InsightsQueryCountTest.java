package com.hamstrack.report;

import com.hamstrack.report.dto.InsightsRequest;
import com.hamstrack.report.service.InsightsService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * The cost claim for R6 (§2.6 "one {@code GROUP BY} over the existing search Criteria query with
 * page/sort dropped"), pinned the way every other report in this epic pins its own.
 *
 * <p><strong>This one matters more than the others, because it is the only endpoint in the epic
 * whose SQL SHAPE varies with the request.</strong> Slice, segment and measure all change the
 * statement; what must NOT change is how many statements there are, and in particular the count
 * must not grow with the data. The specific failure this exists to catch is a per-bucket lookup —
 * resolving each bucket's display name or its HQL fragment with a query would be invisible in
 * every functional test and would turn a two-statement report into 2 + N.
 *
 * <p>The assertions are RELATIVE rather than absolute, on purpose. The bulk of any absolute
 * number here is the SHARED SEARCH CONTEXT — the same name-resolution maps {@code POST …/search}
 * builds on every request, which is where the "machinery that already exists" in §2.6 actually
 * lives — and pinning that total would make this file fail every time an unrelated part of
 * search grew a lookup, which is a tripwire nobody trusts after the second false alarm. What is
 * pinned is what this slice actually claims: the count does not move with the data, and adding a
 * segment adds a constant.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class InsightsQueryCountTest extends InsightsTestBase {

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired InsightsService insightsService;

    /**
     * Twenty issues across five statuses cost exactly what one issue costs. If this fails with a
     * number that grew, look for a lookup inside the bucket loop.
     */
    @Test
    void theStatementCountDoesNotDependOnHowMuchDataThereIs() throws Exception {
        var small = newProject();
        createIssue(small, "Task", "only");

        var large = newProject();
        for (int i = 0; i < 20; i++) {
            createIssue(large, "Task", "issue " + i,
                    i % 2 == 0 ? withStatus(large, "In Progress") : withStatus(large, "Done"));
        }

        // Warm: the first resolution in a fresh process also loads the built-in roles.
        run(small, "STATUS", null);
        run(large, "STATUS", null);

        long one = count(() -> run(small, "STATUS", null));
        long twenty = count(() -> run(large, "STATUS", null));

        assert one == twenty
                : "insights is data-dependent: " + one + " statements for one issue and " + twenty
                  + " for twenty. The likeliest cause is a per-bucket lookup — a display name or an "
                  + "HQL fragment resolved with a query instead of read off the tuple or the "
                  + "already-built ResolutionContext.";
    }

    /**
     * Segmenting costs a fixed <strong>two</strong> more statements, whatever the data. Pinned as a
     * range of exactly one number because both halves are interesting.
     *
     * <p>One of the two is the point of the feature: the cell aggregate, ONE {@code GROUP BY} that
     * serves every bar. The obvious way to build a pivot is to fetch each bar's breakdown
     * separately, which is N+1 wearing the shape of a feature, and this test is what fails if
     * somebody does.
     *
     * <p>The other is a <strong>pre-existing inefficiency this slice inherits rather than
     * introduces</strong>, and it is written down here so the number is not mistaken for the
     * design. {@code SearchScope.scopePredicate} looks up the caller's visible project ids from
     * the database <em>every time a predicate is built</em>, so each of the three Criteria queries
     * this endpoint assembles pays for one identical {@code SELECT … FROM projects WHERE
     * workspace_id = ? AND archived_at IS NULL}. {@code POST …/search} already pays it twice for
     * the same reason (its count query and its page query). The list is also ALREADY on the
     * {@code ResolutionContext} that every one of these calls is handed — it is put there by the
     * same {@code SearchScope} method — so removing the repeat is a small change to shared search
     * code rather than to this report, and it is deliberately not made here: touching the tenancy
     * boundary to save a cheap indexed lookup is not a trade to make inside a feature slice. If it
     * is ever made, this assertion becomes {@code flat + 1} and search gets faster too.
     */
    @Test
    void aSegmentCostsAFixedTwoStatementsNotOnePerBar() throws Exception {
        var ctx = newProject();
        for (int i = 0; i < 6; i++) {
            createIssue(ctx, "Task", "issue " + i,
                    i % 2 == 0 ? withStatus(ctx, "In Progress") : withStatus(ctx, "Done"),
                    i % 3 == 0 ? priority(ctx, "Urgent") : priority(ctx, "Low"));
        }

        run(ctx, "STATUS", null);
        run(ctx, "STATUS", "PRIORITY");

        long flat = count(() -> run(ctx, "STATUS", null));
        long segmented = count(() -> run(ctx, "STATUS", "PRIORITY"));

        assert segmented == flat + 2
                : "a segmented insights request took " + segmented + " statements against " + flat
                  + " unsegmented. It must cost a fixed two more — the cell aggregate and the "
                  + "visible-project lookup SearchScope repeats per predicate — however many bars "
                  + "there are. A number that grows with the bars is a per-bar breakdown query.";
    }

    /**
     * The many-valued dimension is a join, not a second query. {@code LABEL} is the only dimension
     * with no association on {@code Issue}, so it is the one somebody could reasonably implement by
     * loading labels separately.
     */
    @Test
    void theLabelDimensionIsAJoinNotASecondPass() throws Exception {
        var ctx = newProject();
        var a = createLabel(ctx, "alpha");
        var b = createLabel(ctx, "beta");
        createIssue(ctx, "Task", "both", labels(a, b));
        createIssue(ctx, "Task", "one", labels(a));

        run(ctx, "STATUS", null);
        run(ctx, "LABEL", null);

        long byStatus = count(() -> run(ctx, "STATUS", null));
        long byLabel = count(() -> run(ctx, "LABEL", null));

        assert byStatus == byLabel
                : "grouping by label took " + byLabel + " statements against " + byStatus
                  + " for a ToOne dimension — it must be the same single aggregate with one more "
                  + "join, not a second pass over issue_labels.";
    }

    // ------------------------------------------------------------------ plumbing

    private void run(Ctx ctx, String slice, String segment) {
        insightsService.insights(ctx.owner(), ctx.wsId(),
                new InsightsRequest("", null, slice, segment));
    }

    private long count(Runnable body) {
        var stats = statistics();
        stats.clear();
        body.run();
        return stats.getPrepareStatementCount();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
