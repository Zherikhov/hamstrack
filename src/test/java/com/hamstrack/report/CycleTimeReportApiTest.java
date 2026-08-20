package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code GET …/reports/cycle-time} — the finished-work half (reports-proposal §2.2), and above
 * all <strong>the honesty rule</strong>.
 *
 * <p>The rule is one sentence: cycle time is defined only for issues that have a
 * {@code started_at}, and {@code created_at} is never substituted for a missing one. The reason it
 * gets a whole suite is that violating it produces no error, no warning and no visibly wrong
 * value — every dot simply moves up, the p85 the aging half draws across its columns moves with
 * it, and the report keeps calling itself "cycle time". It is the single most likely way this
 * feature ships wrong, so the tests below assert the <em>absence</em> of a number as carefully as
 * they assert its presence.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class CycleTimeReportApiTest extends CycleTimeTestBase {

    private static final String WINDOW = "?from=2025-03-01&to=2025-03-31";

    // ============================================================ the honesty rule

    /**
     * An issue with no start contributes to lead time and to {@code missingStartCount}, and to
     * nothing else. The assertion that matters is the {@code isNull()} one: a fallback to
     * {@code created_at} would put a plausible number there and nothing would fail.
     */
    @Test
    void anIssueWithNoStartHasNoCycleTimeAndIsCounted() throws Exception {
        var ctx = newProject();
        var started = completed(ctx, "started properly",
                "2025-03-01T00:00:00Z", "2025-03-03T00:00:00Z", "2025-03-05T00:00:00Z");
        var gap = completed(ctx, "closed before we recorded starts",
                "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z", "2025-03-11T00:00:00Z");
        clearStartedAt(gap);

        var report = cycleTime(ctx, WINDOW);
        var items = report.get("items");

        assertThat(report.get("sampleSize").asLong()).isEqualTo(2);
        assertThat(report.get("missingStartCount").asLong())
                .as("the gap is disclosed as a count, never filled in")
                .isEqualTo(1);

        var withStart = item(items, started.get("number").asLong());
        assertThat(withStart.get("cycleDays").asDouble()).isEqualTo(2.0);
        assertThat(withStart.get("leadDays").asDouble()).isEqualTo(4.0);

        var withoutStart = item(items, gap.get("number").asLong());
        assertThat(withoutStart.get("startedAt").isNull())
                .as("the provenance of the missing number is returned, not hidden")
                .isTrue();
        assertThat(withoutStart.get("cycleDays").isNull())
                .as("""
                    cycleDays MUST be null for an issue with no started_at. If this fails with \
                    10.0, somebody substituted created_at: the report still renders, every number \
                    is plausible, and it is now a lead-time report wearing the label 'cycle time'.\
                    """)
                .isTrue();
        assertThat(withoutStart.get("leadDays").asDouble())
                .as("lead time is defined for everything from day one, which is why the page is "
                    + "never empty on a freshly upgraded install")
                .isEqualTo(10.0);
    }

    /** The same rule at the aggregate level: the gap must not drag the cycle percentiles. */
    @Test
    void theCyclePercentilesIgnoreIssuesWithNoStart() throws Exception {
        var ctx = newProject();
        // Five issues started exactly one day before they closed…
        for (int day = 1; day <= 5; day++) {
            completed(ctx, "one-day issue " + day,
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + (day + 1) + "T00:00:00Z");
        }
        // …and one 30-day issue whose start we do not know. Substituting created_at for it would
        // pull the cycle p85 far off 1.0 while leaving the lead numbers untouched.
        var gap = completed(ctx, "long, and start unknown",
                "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z", "2025-03-31T00:00:00Z");
        clearStartedAt(gap);

        var report = cycleTime(ctx, WINDOW);
        var cycle = report.get("percentiles").get("cycle");
        var lead = report.get("percentiles").get("lead");

        assertThat(cycle.get("p50").asDouble()).isEqualTo(1.0);
        assertThat(cycle.get("p85").asDouble())
                .as("every issue the cycle percentiles saw took exactly one day")
                .isEqualTo(1.0);
        assertThat(lead.get("p85").asDouble())
                .as("the 30-day issue is real work and belongs to the lead numbers")
                .isGreaterThan(1.0);
    }

    // ============================================================ the percentiles

    /**
     * p50/p85 with values chosen so the answer distinguishes {@code percentile_cont} from the two
     * things it could be mistaken for.
     *
     * <p>Cycle times 1, 2, 3, 4, 10 days. The continuous 85th percentile is
     * {@code 4 + 0.4 × (10 − 4) = 6.4} — a value that <strong>appears in no row</strong>. A
     * nearest-rank percentile would answer 10, and a mean would answer 4. So 6.4 is evidence that
     * the number came from PostgreSQL's {@code percentile_cont … WITHIN GROUP} and not from a
     * hand-rolled approximation in Java, which is exactly what §2.2 asks for.
     */
    @Test
    void percentilesAreContinuousAndComputedInPostgres() throws Exception {
        var ctx = newProject();
        int[] cycleDays = {1, 2, 3, 4, 10};
        for (int i = 0; i < cycleDays.length; i++) {
            completed(ctx, "issue " + i,
                    "2025-03-01T00:00:00Z",
                    "2025-03-05T00:00:00Z",
                    "2025-03-" + String.format("%02d", 5 + cycleDays[i]) + "T00:00:00Z");
        }

        var report = cycleTime(ctx, WINDOW);
        var cycle = report.get("percentiles").get("cycle");

        assertThat(report.get("sampleSize").asLong()).isEqualTo(5);
        assertThat(cycle.get("p50").asDouble()).isEqualTo(3.0);
        assertThat(cycle.get("p85").asDouble())
                .as("6.4 is the interpolated 85th percentile of [1,2,3,4,10]; nearest-rank would "
                    + "say 10 and a mean would say 4")
                .isCloseTo(6.4, within(0.001));
    }

    /**
     * Below five samples the lines are suppressed rather than drawn — printing noise is worse than
     * printing nothing (§2.2, §6). The sample size is still returned, because the client's message
     * is <em>"need 5, have 3"</em> and it cannot write that from nulls alone.
     */
    @Test
    void percentilesAreSuppressedBelowFiveSamples() throws Exception {
        var ctx = newProject();
        for (int day = 1; day <= 4; day++) {
            completed(ctx, "issue " + day,
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + (day + 1) + "T00:00:00Z");
        }

        var report = cycleTime(ctx, WINDOW);

        assertThat(report.get("sampleSize").asLong()).isEqualTo(4);
        assertThat(report.get("percentiles").get("cycle").get("p50").isNull()).isTrue();
        assertThat(report.get("percentiles").get("cycle").get("p85").isNull()).isTrue();
        assertThat(report.get("percentiles").get("lead").get("p50").isNull()).isTrue();
        assertThat(report.get("items")).as("the dots are still there — only the lines are").hasSize(4);
    }

    /**
     * The two pairs are gated independently, because they are computed over different samples. On
     * an install whose history predates R2 this is the normal case, not an edge one: plenty of
     * completed issues, few of them with a recoverable start.
     */
    @Test
    void aThinCycleSampleSuppressesOnlyTheCyclePair() throws Exception {
        var ctx = newProject();
        for (int day = 1; day <= 6; day++) {
            var issue = completed(ctx, "issue " + day,
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + (day + 1) + "T00:00:00Z");
            if (day > 2) {
                clearStartedAt(issue);          // 6 completed, only 2 with a start
            }
        }

        var report = cycleTime(ctx, WINDOW);

        assertThat(report.get("sampleSize").asLong()).isEqualTo(6);
        assertThat(report.get("missingStartCount").asLong()).isEqualTo(4);
        assertThat(report.get("percentiles").get("cycle").get("p50").isNull())
                .as("two cycle samples is not a percentile")
                .isTrue();
        assertThat(report.get("percentiles").get("lead").get("p50").asDouble())
                .as("six lead samples is, and hiding it would throw away a number we have")
                .isEqualTo(1.0);
    }

    // ============================================================ the window

    /** The window is a range on {@code closed_at}: when work FINISHED, not when it was filed. */
    @Test
    void theWindowSelectsByCompletionDateNotByCreation() throws Exception {
        var ctx = newProject();
        var old = completed(ctx, "filed long ago, finished in the window",
                "2024-01-01T00:00:00Z", "2025-03-01T00:00:00Z", "2025-03-10T00:00:00Z");
        completed(ctx, "filed in the window, finished after it",
                "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z", "2025-04-10T00:00:00Z");

        var report = cycleTime(ctx, WINDOW);

        assertThat(report.get("items")).hasSize(1);
        assertThat(report.get("items").get(0).get("key").asText())
                .endsWith("-" + old.get("number").asLong());
        assertThat(report.get("items").get(0).get("leadDays").asDouble())
                .as("lead time spans the whole life of the issue, not the window")
                .isGreaterThan(400);
    }

    /** An open issue has no completion date and is therefore not finished work. */
    @Test
    void openIssuesAreNotInTheFinishedHalf() throws Exception {
        var ctx = newProject();
        inProgressSince(ctx, "still going", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");
        neverStarted(ctx, "never picked up", "2025-03-01T00:00:00Z");

        var report = cycleTime(ctx, WINDOW);

        assertThat(report.get("items")).isEmpty();
        assertThat(report.get("sampleSize").asLong()).isZero();
        assertThat(report.get("percentiles").get("lead").get("p50").isNull()).isTrue();
        assertThat(report.get("meta").get("basedOnIssues").asLong()).isZero();
    }

    // ============================================================ filters

    /**
     * A filter is a predicate, not an addressed resource: an id that names nothing narrows the
     * report to nothing and says so in {@code meta.unmatchedFilters} — it does not 404, which
     * would make the endpoint an existence oracle for ids in other tenants.
     */
    @Test
    void anUnknownFilterNarrowsToNothingAndIsNamed() throws Exception {
        var ctx = newProject();
        completed(ctx, "one", "2025-03-01T00:00:00Z", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");

        var report = cycleTime(ctx, WINDOW + "&typeId=" + UUID.randomUUID());

        assertThat(report.get("items")).isEmpty();
        assertThat(report.get("sampleSize").asLong()).isZero();
        assertThat(report.get("meta").get("unmatchedFilters").toString())
                .as("an empty chart must not be ambiguous between 'nothing finished' and "
                    + "'your filter is broken'")
                .contains("typeId");
    }

    /** A filter that matches is applied, and is not reported as unmatched. */
    @Test
    void aMatchingFilterNarrowsTheSampleWithoutBeingFlagged() throws Exception {
        var ctx = newProject();
        completed(ctx, "a task", "2025-03-01T00:00:00Z", "2025-03-01T00:00:00Z",
                "2025-03-02T00:00:00Z");

        var report = cycleTime(ctx, WINDOW + "&typeId=" + typeId(ctx, "Task"));

        assertThat(report.get("items")).hasSize(1);
        assertThat(report.get("meta").get("unmatchedFilters")).isEmpty();
    }

    // ============================================================ meta

    /** The shared provenance block, on this report too. */
    @Test
    void everyResponseCarriesItsProvenance() throws Exception {
        var ctx = newProject();
        completed(ctx, "one", "2025-02-01T00:00:00Z", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");

        var meta = cycleTime(ctx, WINDOW).get("meta");

        assertThat(meta.get("computedAt").asText()).isNotBlank();
        assertThat(meta.get("basedOnIssues").asLong())
                .as("basedOnIssues counts the distinct issues the numbers came from")
                .isEqualTo(1);
        assertThat(meta.get("truncated").asBoolean()).isFalse();
        assertThat(meta.get("cap").asInt()).isPositive();
        assertThat(meta.get("firstIssueAt").asText()).startsWith("2025-02-01");
    }

    /**
     * <strong>{@code meta.firstIssueAt} reaches behind the window and outside the sample.</strong>
     *
     * <p>It says how far back this PROJECT's history reaches, which is why the whole page can
     * warn that a chart is standing on very little data — so it must not be computed over the
     * window. Until round 3 it was {@code min(created_at)} of the windowed, filtered, completed
     * sample, which made it circular: the earliest issue inside a 31-day window is at most 31
     * days old, so every project on earth reported about a month of history.
     * {@link #everyResponseCarriesItsProvenance()} could not catch that — its one issue is both
     * the project's earliest AND in the sample, so it reads the same either way. This one is
     * built so the two answers differ.
     *
     * <p>Filters, unlike the window, ARE applied — the same rule flow follows, so that on a
     * filtered chart the field means "the first issue this chart could ever have shown".
     */
    @Test
    void firstIssueAtIsTheProjectsHistoryNotTheWindowsOrTheSamples() throws Exception {
        var ctx = newProject();
        // Old, still open, never completed: outside the window AND outside the sample, so under
        // the previous implementation it was invisible to this field.
        neverStarted(ctx, "filed long ago, nobody picked it up", "2023-01-15T00:00:00Z");
        completed(ctx, "the only completed one", "2025-03-01T00:00:00Z", "2025-03-01T09:00:00Z",
                "2025-03-02T09:00:00Z");

        var report = cycleTime(ctx, WINDOW);

        assertThat(report.get("meta").get("firstIssueAt").asText())
                .as("the project's earliest issue, even though it is neither completed nor in "
                    + "the window — otherwise the field can only ever return the window")
                .startsWith("2023-01-15");
        assertThat(report.get("sampleSize").asLong())
                .as("and the sample itself is untouched by it")
                .isEqualTo(1);

        // Filters still narrow it: a filter matching nothing leaves no issue to be earliest.
        var filtered = cycleTime(ctx, WINDOW + "&typeId=" + UUID.randomUUID());
        assertThat(filtered.get("meta").get("firstIssueAt").isNull())
                .as("filters apply to this field exactly as they do on /flow")
                .isTrue();
    }

    /** The endpoint's own parameterless call must work — the server picks a window it can serve. */
    @Test
    void noParametersAtAllIsAValidRequest() throws Exception {
        var ctx = newProject();

        var report = cycleTime(ctx, null);

        assertThat(report.get("from").asText()).isNotBlank();
        assertThat(report.get("to").asText()).isNotBlank();
        assertThat(report.get("items")).isEmpty();
    }
}
