package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;

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
class ReportRowCapTest extends CycleTimeTestBase {

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
}
