package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code app.reports.max-rows}, which R1 declared and R3 is the first slice to actually hit
 * (§1.7, §6). The flow report aggregates in PostgreSQL and returns one row per bucket; cycle time
 * and aging return one row per <em>issue</em>, so this is where the cap becomes load-bearing.
 *
 * <p><strong>The rule is that truncation is never silent.</strong> A capped report that says
 * nothing is precisely the mechanism behind the recurring complaint the whole proposal is
 * organised around — <em>"these numbers don't match what I expected"</em> — because the response
 * is well-formed, the chart renders, and it is about a subset nobody chose. So three things must
 * hold together, and all three are asserted below:
 * <ul>
 *   <li>{@code meta.truncated} is true;</li>
 *   <li>{@code meta.cap} names the number that bit, so a client never has to guess which budget
 *       it was measured against;</li>
 *   <li>the counts ({@code sampleSize}, {@code meta.basedOnIssues}) still describe the
 *       <strong>whole</strong> set, so the reader can see how much is missing. A report that
 *       truncated its own denominator too would be internally consistent and useless.</li>
 * </ul>
 *
 * <p>The cap is set to 2 so the boundary is exercised with a fixture a human can check.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.reports.max-rows=2"
})
@AutoConfigureMockMvc
class ReportRowCapTest extends ReportCsvTestBase {

    @Test
    void aCappedCycleTimeReportShipsTheMostRecentAndSaysSo() throws Exception {
        var ctx = newProject();
        for (int day = 1; day <= 5; day++) {
            completed(ctx, "issue " + day,
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-1" + day + "T00:00:00Z");        // closes on the 11th…15th
        }

        var report = cycleTime(ctx, "?from=2025-03-01&to=2025-03-31");

        assertThat(report.get("items")).hasSize(2);
        assertThat(report.get("meta").get("truncated").asBoolean()).isTrue();
        assertThat(report.get("meta").get("cap").asInt()).isEqualTo(2);
        assertThat(report.get("sampleSize").asLong())
                .as("the denominator survives the cap: the reader must be able to see that 2 of 5 "
                    + "are shown")
                .isEqualTo(5);
        assertThat(report.get("meta").get("basedOnIssues").asLong()).isEqualTo(5);
        assertThat(report.get("items").get(0).get("closedAt").asText())
                .as("what survives the cap is the MOST RECENT work, which is the sentence the UI "
                    + "is required to print")
                .startsWith("2025-03-15");
    }

    /**
     * The percentiles describe the whole window even when the dots do not — an aggregate costs the
     * same either way, and a p85 computed from "the most recent 2" would be a different statistic
     * under the same label.
     */
    @Test
    void aCappedReportStillDrawsItsLinesFromTheWholeSample() throws Exception {
        var ctx = newProject();
        int[] cycleDays = {1, 2, 3, 4, 10};
        for (int i = 0; i < cycleDays.length; i++) {
            completed(ctx, "issue " + i, "2025-03-01T00:00:00Z", "2025-03-05T00:00:00Z",
                    "2025-03-" + String.format("%02d", 5 + cycleDays[i]) + "T00:00:00Z");
        }

        var report = cycleTime(ctx, "?from=2025-03-01&to=2025-03-31");

        assertThat(report.get("items")).hasSize(2);
        assertThat(report.get("percentiles").get("cycle").get("p50").asDouble())
                .as("the median of [1,2,3,4,10] — not of the two rows that survived the cap")
                .isEqualTo(3.0);
    }

    /** Aging truncates from the other end: what survives is the OLDEST, which is the point. */
    @Test
    void aCappedAgingReportKeepsTheOldestItems() throws Exception {
        var ctx = newProject();
        var oldest = inProgressSince(ctx, "rotting", "2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z");
        var second = inProgressSince(ctx, "stale", "2024-06-01T00:00:00Z", "2024-06-02T00:00:00Z");
        inProgressSince(ctx, "fresh a", "2025-06-01T00:00:00Z", "2025-06-02T00:00:00Z");
        inProgressSince(ctx, "fresh b", "2025-07-01T00:00:00Z", "2025-07-02T00:00:00Z");

        var report = aging(ctx);

        assertThat(itemKeys(column(report, "In Progress")))
                .as("an aging report that dropped its oldest items would be answering the "
                    + "opposite of the question it was asked")
                .containsExactly(oldest.get("key").asText(), second.get("key").asText());
        assertThat(report.get("meta").get("truncated").asBoolean()).isTrue();
        assertThat(report.get("meta").get("basedOnIssues").asLong())
                .as("the true open count survives the cap, so 'showing 2 of 4' is expressible")
                .isEqualTo(4);
    }

    /** Exactly at the cap is not truncation — the cap is a maximum, not a strict inequality. */
    @Test
    void exactlyTheCapIsNotReportedAsTruncated() throws Exception {
        var ctx = newProject();
        inProgressSince(ctx, "one", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");
        inProgressSince(ctx, "two", "2025-03-03T00:00:00Z", "2025-03-04T00:00:00Z");

        var report = aging(ctx);

        assertThat(report.get("meta").get("truncated").asBoolean()).isFalse();
        assertThat(itemKeys(column(report, "In Progress"))).hasSize(2);
    }

    /**
     * The R4 sprint reports cap <strong>ledger rows</strong>, not issues — but the rule above is
     * the same one, and it was broken here (HD-29 R4 round 2): {@code basedOnIssues} was sized
     * from the rows that <em>survived</em> the cap, so the one field whose job is to disclose
     * truncation would have shrunk along with the data it was disclosing. A reader would have been
     * told the report covered two issues, which is exactly the number they could already count on
     * screen, at the moment they most needed the real one.
     *
     * <p>Unreachable with real data — a sprint's ledger is hundreds of rows against a cap of
     * 20 000 — so nothing but a test with the cap turned down to 2 can hold this contract, and
     * "unreachable" is a property of today's data rather than of the field's meaning.
     */
    @Test
    void aCappedSprintLedgerStillCountsEveryIssueItWasAbout() throws Exception {
        var ctx = newProject();
        var a = createIssue(ctx, "one");
        var b = createIssue(ctx, "two");
        var c = createIssue(ctx, "three");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(a), idOf(b), idOf(c))
                .andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());

        var report = burnup(ctx, "?sprintId=" + sprint);

        assertThat(report.get("meta").get("truncated").asBoolean())
                .as("three commitment rows against a cap of two")
                .isTrue();
        assertThat(report.get("meta").get("cap").asInt()).isEqualTo(2);
        assertThat(report.get("meta").get("basedOnIssues").asLong())
                .as("counted in the database, above the cap — the true total is what makes "
                    + "'showing 2 of 3' expressible, and it is what the reader can no longer see")
                .isEqualTo(3);
        assertThat(report.get("committedAtStart").asInt())
                .as("the numbers that ARE derived from the shipped rows shrink with them, which is "
                    + "why the disclosure has to be honest")
                .isEqualTo(2);

        // The sprint review reads the same ledger through the same record, so it says the same.
        var review = review(ctx, "?sprintId=" + sprint);
        assertThat(review.get("meta").get("basedOnIssues").asLong()).isEqualTo(3);
        assertThat(review.get("meta").get("truncated").asBoolean()).isTrue();
    }

    /**
     * <strong>A capped velocity report drops the sprints it could not read, and stops
     * forecasting.</strong> The R5 half of the contract above, and the one where getting it wrong
     * is invisible.
     *
     * <p>The sweep is {@code ORDER BY e.sprint_id … LIMIT cap + 1} over up to twelve sprints, and
     * those ids are <strong>UUID v7 — time-ordered</strong>. So a cap that bites does not shave
     * the tail off one sprint's ledger: it removes the most recently created sampled sprints
     * <em>entirely</em>. An absent group is indistinguishable from a sprint nobody put anything
     * in, whose honest reading is four zeros — so as first written, this fixture produced FOUR
     * bars, three of them empty, and those three zeros went into {@code percentile_cont} as real
     * samples and pulled p50 to 0. A forecast is exactly the number a reader cannot check, which
     * is why it is the worst possible place to put a silent artefact of a row cap.
     *
     * <p>So: every sampled sprint with no shipped rows is dropped once the cap has bitten, and the
     * band is suppressed — the surviving bars are complete and are still facts, but the sample is
     * no longer the one the caller asked for. {@code meta.truncated} says the cap bit and
     * {@code meta.basedOnIssues} still counts every issue the sampled ledgers hold, above the cap,
     * so "showing 1 of 4 sprints" is expressible rather than merely true.
     *
     * <p>The fixture is built so that exactly one sprint fits: the oldest has two ledger rows
     * (both its issues finished, so the completion moves nothing out) against a cap of two, and
     * the three after it have three each.
     */
    @Test
    void aCappedVelocityReportDropsTheSprintsItCouldNotReadAndSuppressesTheBand() throws Exception {
        var ctx = newProject();
        completedSprint(ctx, "S0", "2025-01-01T00:00:00Z", "2025-01-10T10:00:00Z", 2, 2, null);
        for (int i = 1; i <= 3; i++) {
            completedSprint(ctx, "S" + i, "2025-0" + (i + 1) + "-01T00:00:00Z",
                    "2025-0" + (i + 1) + "-10T10:00:00Z", 2, 1, null);
        }

        var report = velocity(ctx, null);

        assertThat(report.get("meta").get("truncated").asBoolean())
                .as("eleven ledger rows against a cap of two")
                .isTrue();
        assertThat(report.get("meta").get("cap").asInt()).isEqualTo(2);
        assertThat(report.get("meta").get("basedOnIssues").asLong())
                .as("counted in the database, above the cap — eight distinct issues across the "
                    + "four sampled sprints, which is what makes 'showing one of four' sayable")
                .isEqualTo(8);

        assertThat(report.get("sprints"))
                .as("three of the four sprints were never read, and a sprint that was not read is "
                    + "not a sprint that did nothing")
                .hasSize(1);
        for (var bar : report.get("sprints")) {
            assertThat(bar.get("committed").asInt())
                    .as("a bar of four zeros in a truncated report is the cap being reported as "
                        + "an empty sprint")
                    .isPositive();
        }

        var forecast = report.get("forecast");
        assertThat(forecast.get("p50").isNull() && forecast.get("p85").isNull())
                .as("a band computed over whatever fitted under the row cap is a number the "
                    + "report cannot stand behind — it is suppressed, exactly as it is below three "
                    + "completed sprints")
                .isTrue();
        assertThat(forecast.get("sampleSize").asInt())
                .as("suppressed, but still stating what there was")
                .isEqualTo(1);
    }

    /**
     * <strong>Truncation is disclosed in the exported file too</strong> (HD-141 R7).
     *
     * <p>The rule this class exists for — a capped report never stays quiet about it — is the one
     * an export is most likely to lose, because a CSV has no {@code meta} block to carry
     * {@code truncated} and {@code cap} and nobody misses a field that was never rendered. A file
     * that silently holds the first 2 of 5 rows is the "these numbers don't match" failure with a
     * filename attached, and unlike a chart it outlives the tab it was downloaded from.
     */
    @Test
    void aCappedExportSaysSoInItsHeaderRatherThanShippingFewerRowsQuietly() throws Exception {
        var ctx = newProject();
        for (int day = 1; day <= 5; day++) {
            completed(ctx, "issue " + day,
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-1" + day + "T00:00:00Z");
        }

        var csv = csv(ctx, "cycle-time.csv", "?from=2025-03-01&to=2025-03-31");

        assertThat(dataRows(csv)).hasSize(2);
        assertThat(comment(csv, "Truncated"))
                .as("the file has to say it is a subset; nobody can tell from the rows")
                .startsWith("YES - the row cap of 2");
        assertThat(comment(csv, "Based on issues"))
                .as("the denominator survives the cap in the file exactly as it does in the JSON, "
                    + "so 'showing 2 of 5' is expressible")
                .isEqualTo("5");
        assertThat(comment(csv, "Sample size")).isEqualTo("5");
    }
}
