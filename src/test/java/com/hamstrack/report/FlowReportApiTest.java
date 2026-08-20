package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The arithmetic of {@code GET …/reports/flow} (reports-proposal §2.1) — the numbers, the
 * bucketing and the {@code meta} block that every later report inherits.
 *
 * <p>What is actually at risk here, and therefore what each test pins:
 * <ul>
 *   <li>the <strong>opening balance</strong>. The "open at bucket end" line is the only
 *       part of the report that depends on history <em>outside</em> the window; getting
 *       it wrong produces a chart that is internally consistent and globally false, and
 *       starting it at zero would be a smaller-looking bug than it is;</li>
 *   <li><strong>zero-filled</strong> buckets. The database returns only buckets that have
 *       rows; a series with holes draws a chart that lies about the shape of the work;</li>
 *   <li><strong>partial edge buckets</strong>. A weekly window that starts mid-week opens
 *       with a bucket dated the previous Monday, and that bucket must count only what
 *       happened inside the requested window — the window is never snapped outwards;</li>
 *   <li>{@code meta.basedOnIssues} counting <strong>distinct issues</strong>, not events:
 *       an issue created and closed in the same window appears on both lines and is one
 *       issue of evidence, not two.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class FlowReportApiTest extends FlowReportTestBase {

    /**
     * The whole report in one fixture: history before the window, work inside it, work
     * after it, and one issue that leaves the window through the resolved line only.
     */
    @Test
    void countsCreatedAndResolvedPerDayAndCarriesTheOpenBalanceForward() throws Exception {
        var ctx = newProject();

        // Before the window: one still open (raises the opening balance), one already
        // closed (must NOT raise it — created and closed both precede the window).
        var openBefore = issueCreatedAt(ctx, "open since January", "2025-01-10T09:00:00Z");
        var closedBefore = issueCreatedAt(ctx, "closed in January", "2025-01-11T09:00:00Z");
        closedAt(ctx, closedBefore.get("number").asLong(),
                UUID.fromString(closedBefore.get("id").asText()), "2025-01-20T09:00:00Z");

        // Inside the window.
        issueCreatedAt(ctx, "still open", "2025-03-04T10:00:00Z");
        var closedInside = issueCreatedAt(ctx, "opened and closed inside", "2025-03-04T11:00:00Z");
        closedAt(ctx, closedInside.get("number").asLong(),
                UUID.fromString(closedInside.get("id").asText()), "2025-03-06T15:00:00Z");

        // Created inside, closed AFTER the window: on the created line, not the resolved one.
        var closedAfter = issueCreatedAt(ctx, "closed next month", "2025-03-10T08:00:00Z");
        closedAt(ctx, closedAfter.get("number").asLong(),
                UUID.fromString(closedAfter.get("id").asText()), "2025-03-25T08:00:00Z");

        // Entirely after the window — must not appear anywhere.
        issueCreatedAt(ctx, "april", "2025-04-01T08:00:00Z");

        var report = flow(ctx, "?from=2025-03-03&to=2025-03-16&interval=DAY");

        assertThat(report.get("from").asText()).isEqualTo("2025-03-03");
        assertThat(report.get("to").asText()).isEqualTo("2025-03-16");
        assertThat(report.get("interval").asText()).isEqualTo("DAY");
        assertThat(report.get("buckets").size()).as("14 inclusive days -> 14 daily buckets").isEqualTo(14);

        // The opening balance: 2 created before the window, 1 of them closed before it.
        assertThat(bucket(report, "2025-03-03").get("created").asLong()).isZero();
        assertThat(bucket(report, "2025-03-03").get("openAtEnd").asLong())
                .as("open at the window's start = created-before minus closed-before")
                .isEqualTo(1);

        assertThat(bucket(report, "2025-03-04").get("created").asLong()).isEqualTo(2);
        assertThat(bucket(report, "2025-03-04").get("resolved").asLong()).isZero();
        assertThat(bucket(report, "2025-03-04").get("openAtEnd").asLong()).isEqualTo(3);

        // A day nothing happened on is present with zeros, not missing.
        assertThat(bucket(report, "2025-03-05").get("created").asLong()).isZero();
        assertThat(bucket(report, "2025-03-05").get("resolved").asLong()).isZero();
        assertThat(bucket(report, "2025-03-05").get("openAtEnd").asLong()).isEqualTo(3);

        assertThat(bucket(report, "2025-03-06").get("resolved").asLong()).isEqualTo(1);
        assertThat(bucket(report, "2025-03-06").get("openAtEnd").asLong()).isEqualTo(2);

        assertThat(bucket(report, "2025-03-10").get("created").asLong()).isEqualTo(1);
        assertThat(bucket(report, "2025-03-16").get("openAtEnd").asLong()).isEqualTo(3);

        var totals = report.get("totals");
        assertThat(totals.get("created").asLong()).isEqualTo(3);
        assertThat(totals.get("resolved").asLong()).isEqualTo(1);
        assertThat(totals.get("net").asLong()).isEqualTo(2);

        var meta = report.get("meta");
        assertThat(meta.get("basedOnIssues").asLong())
                .as("distinct issues touching the window — the one created AND closed inside it "
                    + "is evidence once, not twice")
                .isEqualTo(3);
        assertThat(meta.get("truncated").asBoolean()).isFalse();
        assertThat(meta.get("cap").asInt()).isEqualTo(20000);
        assertThat(meta.get("computedAt").asText()).isNotBlank();

        // The still-open January issue was never touched by any of this.
        assertThat(getIssue(ctx, openBefore.get("number").asLong()).get("closedAt").isNull()).isTrue();
    }

    /**
     * Weekly buckets are Monday-aligned in UTC, and a window that does not start on a
     * Monday opens with a <strong>partial</strong> bucket: the bucket is dated the Monday
     * before {@code from}, but nothing that happened before {@code from} may be counted in
     * it. The alternative — quietly widening the window to the bucket boundary — would be
     * a silent clamp in the other direction.
     */
    @Test
    void weeklyBucketsAreMondayAlignedAndTheFirstOneIsPartial() throws Exception {
        var ctx = newProject();
        issueCreatedAt(ctx, "the Tuesday before the window", "2025-03-04T10:00:00Z");
        issueCreatedAt(ctx, "inside", "2025-03-06T10:00:00Z");

        // from is a Wednesday; to a Tuesday.
        var report = flow(ctx, "?from=2025-03-05&to=2025-03-18&interval=WEEK");

        assertThat(report.get("buckets").size()).isEqualTo(3);
        assertThat(report.get("buckets").get(0).get("date").asText())
                .as("date_trunc('week') is ISO — Monday").isEqualTo("2025-03-03");
        assertThat(report.get("buckets").get(1).get("date").asText()).isEqualTo("2025-03-10");
        assertThat(report.get("buckets").get(2).get("date").asText()).isEqualTo("2025-03-17");

        assertThat(bucket(report, "2025-03-03").get("created").asLong())
                .as("the March 4 issue predates `from` and must not be counted, even though "
                    + "its bucket is in the series")
                .isEqualTo(1);
        assertThat(report.get("totals").get("created").asLong()).isEqualTo(1);
    }

    /** A project with no issues renders the window, not a blank panel (§6). */
    @Test
    void anEmptyProjectStillGetsAFullZeroFilledSeries() throws Exception {
        var ctx = newProject();

        var report = flow(ctx, "?from=2025-03-01&to=2025-03-05&interval=DAY");

        assertThat(report.get("buckets").size()).isEqualTo(5);
        for (var b : report.get("buckets")) {
            assertThat(b.get("created").asLong()).isZero();
            assertThat(b.get("resolved").asLong()).isZero();
            assertThat(b.get("openAtEnd").asLong()).isZero();
        }
        assertThat(report.get("totals").get("net").asLong()).isZero();
        assertThat(report.get("meta").get("basedOnIssues").asLong()).isZero();
        assertThat(report.get("meta").get("truncated").asBoolean()).isFalse();
    }

    /**
     * No parameters at all is the documented default report: the last 90 days, weekly.
     * The default is asserted rather than assumed because the SPA relies on it to render
     * the page before the user touches the window picker.
     */
    @Test
    void defaultsToTheLastNinetyDaysInWeeklyBuckets() throws Exception {
        var ctx = newProject();

        var report = flow(ctx, null);

        var today = LocalDate.now(ZoneOffset.UTC);
        assertThat(report.get("interval").asText()).isEqualTo("WEEK");
        assertThat(report.get("to").asText()).isEqualTo(today.toString());
        assertThat(report.get("from").asText()).isEqualTo(today.minusDays(89).toString());
        assertThat(report.get("buckets").get(0).get("date").asText())
                .isEqualTo(today.minusDays(89)
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString());
        assertThat(report.get("meta").get("cap").asInt()).isEqualTo(20000);
    }

    /**
     * The three filters of §2.1 narrow every part of the report together — both series and
     * the opening balance. A filter that narrowed the lines but not the balance would draw
     * an "open" line that starts at the wrong height and never recovers.
     */
    @Test
    void filtersNarrowBothSeriesAndTheOpeningBalance() throws Exception {
        var ctx = newProject();
        var bugType = typeId(ctx, "Bug");

        // A bug open since before the window, plus one bug and one task inside it.
        var oldBug = createIssue(ctx, "old bug", "\"typeId\":\"" + bugType + "\"");
        createdAt(UUID.fromString(oldBug.get("id").asText()), "2025-01-05T09:00:00Z");
        var newBug = createIssue(ctx, "new bug", "\"typeId\":\"" + bugType + "\"");
        createdAt(UUID.fromString(newBug.get("id").asText()), "2025-03-04T09:00:00Z");
        issueCreatedAt(ctx, "a task", "2025-03-04T09:00:00Z");

        var unfiltered = flow(ctx, "?from=2025-03-03&to=2025-03-09&interval=DAY");
        assertThat(unfiltered.get("totals").get("created").asLong()).isEqualTo(2);
        assertThat(bucket(unfiltered, "2025-03-03").get("openAtEnd").asLong()).isEqualTo(1);

        var bugsOnly = flow(ctx, "?from=2025-03-03&to=2025-03-09&interval=DAY&typeId=" + bugType);
        assertThat(bugsOnly.get("totals").get("created").asLong()).isEqualTo(1);
        assertThat(bugsOnly.get("meta").get("basedOnIssues").asLong()).isEqualTo(1);
        assertThat(bucket(bugsOnly, "2025-03-03").get("openAtEnd").asLong())
                .as("the opening balance is filtered too — the old bug still counts here")
                .isEqualTo(1);

        // An id that exists nowhere narrows to nothing. It is NOT a 404, and the reason is
        // NOT "resolving it would leak another tenant's taxonomy" — a lookup scoped to THIS
        // project answers identically for a foreign id and a nonexistent one, so it would
        // leak nothing. The real reasons are stronger: a filter is a predicate, not an
        // addressed resource, so the 404-on-unknown-id convention is a category error here;
        // and resolving three optional ids would cost three statements the report does not
        // spend. Note the split rule in ReportController's javadoc — a report's SUBJECT id
        // (R4's sprintId) resolves and 404s, a narrowing FILTER id never does.
        var nothing = flow(ctx, "?from=2025-03-03&to=2025-03-09&interval=DAY&typeId=" + UUID.randomUUID());
        assertThat(nothing.get("totals").get("created").asLong()).isZero();
        assertThat(nothing.get("meta").get("basedOnIssues").asLong()).isZero();
        assertThat(nothing.get("buckets").size()).isEqualTo(7);
    }

    /**
     * {@code Cache-Control: private, max-age=60} (§4.3). "private" is the load-bearing
     * half: a report is one tenant's data and must never be held by a shared cache.
     *
     * <p><strong>And {@code Vary: Authorization}</strong> (round 2, item 4), which is the
     * half that makes {@code private} mean what it looks like it means. A browser's HTTP
     * cache keys on (method, URL) and <em>ignores request headers unless {@code Vary} names
     * them</em> — so on a shared profile, within the 60 seconds, a cached 200 could be
     * replayed to a different bearer token, one the server would have answered 404. The
     * response is per-credential, so the credential has to be in the cache key.
     */
    @Test
    void theResponseIsPrivatelyCacheableForAMinuteAndVariesByCredential() throws Exception {
        var ctx = newProject();

        getFlow(ctx, ctx.token(), "?from=2025-03-01&to=2025-03-05")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("private"),
                                org.hamcrest.Matchers.containsString("max-age=60"))))
                .andExpect(header().string("Vary",
                        org.hamcrest.Matchers.containsString("Authorization")));
    }

    /**
     * <strong>What the numbers are based on</strong> (round 2, item 7): three disclosures
     * that each remove a way for this report to be quietly misread.
     *
     * <ul>
     *   <li>{@code meta.firstIssueAt} — "we only have N days of history" without a second
     *       request, and dated by the first issue rather than by the project's own
     *       {@code createdAt}, which is not the same date and is often years off;</li>
     *   <li>{@code bucket.partial} — the first and last bucket of an unaligned window cover
     *       less calendar than the others, and their bars are legitimately shorter. Flagged
     *       server-side because the alternative is the client re-deriving Monday truncation
     *       in TypeScript: one calendar rule in two languages, drifting apart;</li>
     *   <li>{@code meta.unmatchedFilters} — a typo'd or stale filter id renders a complete,
     *       plausible, all-zero chart. "No bugs were created in Q1" and "your filter matched
     *       nothing" are the same picture, and only the server can tell them apart.</li>
     * </ul>
     */
    @Test
    void theReportDisclosesItsHistoryItsPartialBucketsAndAFilterThatMatchedNothing()
            throws Exception {
        var ctx = newProject();
        issueCreatedAt(ctx, "the first thing that ever happened here", "2025-02-11T09:00:00Z");
        issueCreatedAt(ctx, "inside", "2025-03-06T10:00:00Z");

        // from is a Wednesday, to a Tuesday: both edge buckets are partial, the middle is not.
        var report = flow(ctx, "?from=2025-03-05&to=2025-03-18&interval=WEEK");

        assertThat(report.get("meta").get("firstIssueAt").asText())
                .as("the project's earliest issue, so the UI can say how much history there is")
                .startsWith("2025-02-11");
        assertThat(report.get("buckets").get(0).get("partial").asBoolean())
                .as("the bucket dated the Monday before `from` covers only part of a week")
                .isTrue();
        assertThat(report.get("buckets").get(1).get("partial").asBoolean())
                .as("a whole week inside the window is not partial").isFalse();
        assertThat(report.get("buckets").get(2).get("partial").asBoolean())
                .as("the window ends mid-week").isTrue();
        assertThat(report.get("meta").get("unmatchedFilters").isEmpty())
                .as("nothing was filtered, so nothing can have failed to match").isTrue();

        // A daily window aligned to its own buckets has no partial bucket at all.
        var daily = flow(ctx, "?from=2025-03-05&to=2025-03-07&interval=DAY");
        for (var b : daily.get("buckets")) {
            assertThat(b.get("partial").asBoolean()).isFalse();
        }

        // A filter id that nothing in THIS project carries. Named, not guessed at: saying
        // "nothing here carries this id" discloses nothing about any other tenant, and the
        // alternative is a chart of zeros that looks exactly like a quiet quarter.
        var stale = flow(ctx, "?from=2025-03-05&to=2025-03-18&typeId=" + UUID.randomUUID());
        assertThat(stale.get("meta").get("unmatchedFilters").toString()).contains("typeId");
        assertThat(stale.get("totals").get("created").asLong()).isZero();

        // A filter that DOES match is not reported as unmatched, even when the window is empty.
        var bugType = typeId(ctx, "Bug");
        var bug = createIssue(ctx, "a bug", "\"typeId\":\"" + bugType + "\"");
        createdAt(UUID.fromString(bug.get("id").asText()), "2025-01-02T09:00:00Z");
        var quiet = flow(ctx, "?from=2025-03-05&to=2025-03-18&typeId=" + bugType);
        assertThat(quiet.get("meta").get("unmatchedFilters").isEmpty())
                .as("the type exists in this project — the window is simply quiet, which is a "
                    + "different statement and must not be reported as a broken filter")
                .isTrue();
        assertThat(quiet.get("totals").get("created").asLong()).isZero();
    }
}
