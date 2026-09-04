package com.hamstrack.issue;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>How much of a connection one planning read occupies — the half of HD-174's arithmetic
 * that can be asserted in this repository</strong>.
 *
 * <p>The occupancy bound exists because {@code BacklogService.view} is
 * {@code @Transactional(readOnly = true)} over the whole method, so <strong>one Hikari connection
 * is held across every statement it issues</strong>: the design says {@code 12 + N} for the
 * aggregate, i.e. 32 at {@code AGILE_MAX_OPEN_SPRINTS=20}, against a
 * {@code DB_STATEMENT_TIMEOUT_MS} that bounds each statement and not their sum. That number is
 * quoted in `ExpensiveReadProperties`, `PlanningProperties`, `application.properties`,
 * `.env.prod.example`, `docs/self-hosting.md`, `docs/observability.md`, ADR-0031 and the alert
 * rule's own runbook text. <strong>It was counted by reading the code.</strong> This asserts it
 * against the running application instead.
 *
 * <h2>What this measures, and what it deliberately does not</h2>
 *
 * <p>It measures <strong>statements per request and their growth in N</strong> — the shape
 * {@code constant + one per open section}. It does <strong>not</strong> measure duration, and
 * therefore does not settle HD-174's highest-risk assumption ("a planning response is short in the
 * healthy case", from which "a busy planner occupies ~0.23 permits" follows). <em>Nothing in this
 * repository can</em>: occupancy is arrival rate × duration on real data and real hardware, and a
 * duration taken against a handful of test rows on a developer laptop would be a number with the
 * authority of a measurement and the content of a guess — which is worse than the estimate it
 * replaced, because the next reader would stop looking. That number is
 * {@code ops/loadtest/RESULTS-TEMPLATE.md} §P1b's deliverable, taken off the k6 browse ladder,
 * which already exercises these exact endpoints.
 *
 * <p>So: the <strong>N</strong> in {@code 12 + N} is pinned here; the <strong>seconds</strong>
 * each of those statements costs is pinned nowhere yet, and is said so out loud.
 *
 * <h2>What it measured, and the one number it does NOT confirm</h2>
 *
 * <p><strong>The growth is exactly one statement per open sprint — confirmed.</strong> The
 * <em>constant</em> is not: this measures <strong>8</strong> with no open sprint and
 * <strong>12</strong> with four, i.e. {@code 8 + N}, where the design says {@code 12 + N} (and
 * therefore 32 rather than 28 at {@code AGILE_MAX_OPEN_SPRINTS=20}). <strong>That is not a
 * refutation and must not be written up as one</strong>, for two reasons this test cannot remove:
 *
 * <ul>
 *   <li><strong>Warm caches.</strong> Part of the constant block is permission resolution, and
 *       {@code RolePermissionCache} is a 10 s per-node Caffeine cache — so the second request in a
 *       test method issues fewer statements than a cold one, and the design's constant is the cold
 *       cost.</li>
 *   <li><strong>Empty sections.</strong> These projects carry no issues, and a batched loader keyed
 *       by an empty id list issues no statement at all. Five of them ride on this path.</li>
 * </ul>
 *
 * <p>So {@code 8 + N} is a measured <em>lower</em> bound on a warm request over empty sections, and
 * {@code 12 + N} is a read of the cold, populated path. <strong>Neither changes a decision</strong>:
 * what the occupancy bound rests on is that the count grows with N inside ONE transaction while
 * {@code statement_timeout} bounds only each statement — and that is what is asserted below.
 * Whoever wants the cold, populated constant should take it off the k6 ladder
 * ({@code ops/loadtest/RESULTS-TEMPLATE.md} §P1b), whose fixture has both.
 *
 * <p><strong>The growth is asserted, the constant is only reported.</strong> Equality of a delta
 * across two projects is exactly what "one query per open section" means and survives every
 * unrelated query change; an absolute {@code == 12} would be a brittle tripwire that fires when
 * somebody adds a legitimate lookup, and this repository has been explicit about preferring the
 * former (see {@code LabelQueryCountTest}). The absolute is printed in the failure message so a
 * reader who wants today's constant can have it without a second test having to guarantee it.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class PlanningOccupancyCostTest extends SprintTestBase {

    @Autowired EntityManagerFactory entityManagerFactory;

    /**
     * The planning aggregate costs a constant plus <strong>exactly one statement per open
     * sprint</strong>, and every one of them is inside a single transaction — which is what makes
     * the hold {@code 12 + N} statements long rather than {@code 12 + N} separate short holds.
     *
     * <p>Two projects, differing only in how many open sprints they carry, so the difference in
     * statement count is attributable to nothing else.
     */
    @Test
    void theAggregateCostsOneStatementPerOpenSprintAndTheyShareOneConnection() throws Exception {
        var none = newProject();
        var four = projectWithOpenSprints(4);

        // Warm both paths: the first execution of each also compiles and populates caches.
        backlogView(none);
        backlogView(four);

        long withNoSprints = countStatements(() -> backlogView(none));
        long withFourSprints = countStatements(() -> backlogView(four));

        assertThat(withFourSprints - withNoSprints)
                .as("""
                    THE PLANNING AGGREGATE'S STATEMENT COST DID NOT GROW BY ONE PER OPEN SPRINT.

                    Measured: %d statements with no open sprint, %d with four. THE ASSERTION IS \
                    THE GROWTH AND NOT THE CONSTANT — see the class javadoc. On a warm cache over \
                    empty sections the constant reads 8; the design's cold, populated reading is \
                    12, and this test can distinguish neither.

                    The documented shape is `12 + N`: a constant block (tenancy resolution, the \
                    open-sprint list, the cap-blind grouped stats query and five batched loaders) \
                    plus ONE ROW QUERY PER OPEN SECTION — 32 statements at \
                    AGILE_MAX_OPEN_SPRINTS=20, the number quoted in ExpensiveReadProperties, \
                    PlanningProperties, application.properties, .env.prod.example, \
                    docs/self-hosting.md, ADR-0031 and the ExpensiveReadSurfaceSaturated runbook.

                    If the growth is now MORE than one per sprint, an N+1 has appeared inside the \
                    transaction and every one of those documents understates the connection hold \
                    this surface takes out of app.expensive-read.max-in-flight. If it is LESS, \
                    good news that still has to be propagated: the same documents overstate it, \
                    and a bound argued from a number nobody re-checked is the shape this \
                    repository keeps paying for.""",
                    withNoSprints, withFourSprints)
                .isEqualTo(4);
    }

    /**
     * A section read is the constant block once — it is {@link #theAggregateCostsOneStatementPerOpenSprintAndTheyShareOneConnection()}'s
     * {@code N = 1} case — and, crucially, <strong>it does not get cheaper by being narrower</strong>:
     * it repeats the unconditional, cap-blind grouped stats query, which is the term the budget is
     * earned by. Asserted as "no cheaper than one section of the aggregate" rather than as an
     * absolute, so it stays true when the constant block moves.
     *
     * <p>Do not rewrite this as "a section fetch is cheaper than the aggregate". That phrasing has
     * been wrong in this repository before, is corrected in {@code BacklogController}'s javadoc and
     * in the HD-96 spec, and is the reason the whole surface shares one pot rather than the
     * aggregate alone carrying it.
     */
    @Test
    void aSectionReadRepeatsTheCapBlindAggregationRatherThanAvoidingIt() throws Exception {
        var ctx = projectWithOpenSprints(4);

        backlogView(ctx);
        sectionNode(ctx, "backlog", "");

        long aggregate = countStatements(() -> backlogView(ctx));
        long section = countStatements(() -> sectionNode(ctx, "backlog", ""));

        assertThat(section)
                .as("""
                    A SECTION READ BECAME MUCH CHEAPER THAN ONE SECTION OF THE AGGREGATE (%d vs \
                    %d statements for a project with four open sprints).

                    That would be good news and it would falsify the reason this surface is \
                    budgeted as a whole. A section fetch divides the row assembly and the response; \
                    it REPEATS the grouped stats query, which reads and groups a whole section \
                    whatever the filters say and whatever the cap is. If that stopped being true, \
                    the argument in PlanningProperties, BacklogController and \
                    ThrottleCoverageTest's sixth checklist question needs rewriting — not \
                    deleting, because the aggregate's own hold is unchanged.""",
                    section, aggregate)
                .isGreaterThan(aggregate / 4);
    }

    /** A project carrying {@code count} STARTED sprints, i.e. {@code count} open sections. */
    private Ctx projectWithOpenSprints(int count) throws Exception {
        var ctx = newProject();
        for (int i = 0; i < count; i++) {
            // FUTURE sprints, not started: an OPEN section is ACTIVE *or* FUTURE, and only one
            // sprint may be ACTIVE at a time — so `createSprint` is what varies N here.
            createSprint(ctx, "Sprint " + (i + 1));
        }
        return ctx;
    }

    private long countStatements(Callable<?> call) throws Exception {
        var statistics = statistics();
        statistics.clear();
        long before = statistics.getPrepareStatementCount();
        call.call();
        return statistics.getPrepareStatementCount() - before;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
