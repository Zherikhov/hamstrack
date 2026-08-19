package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The single most important behavioural rule in the reports spec</strong>
 * (§4.3, §6): a window this server will not serve is <strong>refused, with the cap named
 * in the message</strong> — never quietly narrowed to one it likes better.
 *
 * <p>The reason is in the research the proposal is built on: the recurring complaint about
 * competitors' reports is <em>"these numbers don't match what I expected"</em>, and the
 * mechanism is nearly always a report that silently answered a different question. A
 * clamped window is exactly that, with no symptom at all — the chart renders, the numbers
 * are internally consistent, and they are about six months the caller did not ask for.
 * Every later report inherits this rule from here.
 *
 * <p>{@code app.reports.max-window-days} is overridden to 30 so the boundary is exercised
 * with dates a human can check, and so the failure message can be asserted to contain the
 * configured number rather than a hardcoded 365.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.reports.max-window-days=30"
})
@AutoConfigureMockMvc
class FlowReportWindowTest extends FlowReportTestBase {

    @Test
    void aWindowWiderThanTheCapIsRefusedAndTheCapIsNamed() throws Exception {
        var ctx = newProject();

        getFlow(ctx, ctx.token(), "?from=2025-03-01&to=2025-04-15")
                .andExpect(status().isBadRequest())
                // The operator-facing property name, so the answer to "why?" is in the body.
                .andExpect(jsonPath("$.detail", containsString("app.reports.max-window-days")))
                // The configured cap, not a literal 365 — a clamp would have to invent one.
                .andExpect(jsonPath("$.detail", containsString("30")))
                // And what it actually measured, because the caller did the date arithmetic.
                .andExpect(jsonPath("$.detail", containsString("46 days")));
    }

    /** The boundary itself is served — the cap is a maximum, not a strict inequality. */
    @Test
    void aWindowExactlyAtTheCapIsServed() throws Exception {
        var ctx = newProject();

        var report = flow(ctx, "?from=2025-03-01&to=2025-03-30&interval=DAY");

        // 30 inclusive days: the window is [from, to] and both endpoints count.
        org.assertj.core.api.Assertions.assertThat(report.get("buckets").size()).isEqualTo(30);
    }

    @Test
    void aBackwardsWindowIsRefused() throws Exception {
        var ctx = newProject();

        getFlow(ctx, ctx.token(), "?from=2025-03-10&to=2025-03-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("2025-03-10")))
                .andExpect(jsonPath("$.detail", containsString("2025-03-01")));
    }

    /** A one-day window is legal — {@code from == to} is one whole UTC day, not zero. */
    @Test
    void aSingleDayWindowIsOneBucket() throws Exception {
        var ctx = newProject();

        var report = flow(ctx, "?from=2025-03-10&to=2025-03-10&interval=DAY");

        org.assertj.core.api.Assertions.assertThat(report.get("buckets").size()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(report.get("buckets").get(0).get("date").asText())
                .isEqualTo("2025-03-10");
    }

    @Test
    void anUnparseableDateOrIntervalIsARequestError() throws Exception {
        var ctx = newProject();

        getFlow(ctx, ctx.token(), "?from=last-tuesday&to=2025-03-10")
                .andExpect(status().isBadRequest());
        getFlow(ctx, ctx.token(), "?from=2025-03-01&to=2025-03-10&interval=FORTNIGHT")
                .andExpect(status().isBadRequest());
    }

    /**
     * HD-28 R1 round 2, item 2 — <strong>a cap below 90 must not break the endpoint's own
     * default request</strong>.
     *
     * <p>This class runs with {@code max-window-days=30}, which is inside the documented
     * range 1–3650 and is exactly what an operator wanting cheap reports would type. A
     * parameterless call — the request the reports page makes before the user has touched
     * anything — used to default to 90 days and then be refused by the very cap this class
     * exists to prove. The page was a permanent 400 on a perfectly legal configuration, and
     * this suite was already running that configuration without ever asserting the
     * consequence.
     *
     * <p><strong>This is defaulting, not clamping.</strong> The two are not the same rule and
     * the distinction is the whole reason the fix is legitimate: a clamp narrows a window the
     * caller <em>asked for</em>, which this feature refuses to do and
     * {@link #aWindowWiderThanTheCapIsRefusedAndTheCapIsNamed()} pins. Here the caller asked
     * for nothing, so there is no window to narrow — the server is choosing its own default
     * and must choose one it will actually serve.
     */
    @Test
    void theDefaultWindowIsDerivedFromTheCapSoTheDefaultRequestAlwaysWorks() throws Exception {
        var ctx = newProject();

        var report = flow(ctx, null);

        var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        org.assertj.core.api.Assertions.assertThat(report.get("to").asText())
                .isEqualTo(today.toString());
        org.assertj.core.api.Assertions.assertThat(report.get("from").asText())
                .as("the default window is min(90, max-window-days) days, so the endpoint's "
                    + "own default request can never be refused by its own cap")
                .isEqualTo(today.minusDays(29).toString());
    }

    /**
     * HD-28 R1 round 2, item 3 — <strong>an extreme but parseable date is a 400, not a
     * 500</strong>.
     *
     * <p>{@code ISO_DATE}'s year field is {@code EXCEEDS_PAD}, so {@code +999999999-12-31}
     * binds happily, passes the window checks as an ordinary 90-day range, and then blows up
     * on {@code to.plusDays(1)} — the half-open window's own arithmetic — as an unhandled
     * {@link java.time.DateTimeException}. The sibling case needs no overflow at all: a year
     * inside Java's range but outside PostgreSQL's {@code timestamptz} fails in the driver
     * instead — and that one needs a bigger number than it looks, because PostgreSQL reaches
     * to 294276 AD: {@code +500000-01-01} is the input that actually answers
     * {@code ERROR: timestamp out of range}, while a merely silly year like 5000 binds fine
     * and would return a cheerful, empty, unasked-for 200. Both extremes were 500s on an
     * endpoint whose javadoc promises 400 for a bad date; the 5000 case is refused for the
     * sake of the message rather than to avert a crash, and all three are asserted below.
     *
     * <p>Refused by naming the band, in the same refuse-and-say-why wording as the cap: the
     * caller of a report API is usually a chart that did the date arithmetic itself, and
     * "invalid" without a number tells it nothing.
     */
    @Test
    void aDateOutsideTheSupportedBandIsRefusedRatherThanCrashing() throws Exception {
        var ctx = newProject();

        // The overflow case: the largest date ISO_DATE will parse.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(reportsBase(ctx) + "/flow")
                        .param("to", "+999999999-12-31")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("2200")));

        // The DRIVER case, and it needs a bigger number than it looks. PostgreSQL's
        // timestamptz runs to 294276 AD, so a merely silly year like 5000 does NOT fail in
        // the driver — without the band it is a cheerful 200 over an empty series, which is
        // a different bug and a much quieter one. The year that genuinely crashes is one
        // past PostgreSQL's ceiling: pgjdbc binds it happily and the server answers
        // "ERROR: timestamp out of range", i.e. a DataIntegrityViolationException and a 500
        // on an endpoint whose contract promises 400. Verified by removing the band check
        // and watching exactly that happen.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(reportsBase(ctx) + "/flow")
                        .param("from", "+500000-01-01").param("to", "+500000-01-10")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("2200")));

        // And the plainly-silly year, which the band refuses for the sake of the message
        // rather than to avert a crash — the report it would otherwise return is empty,
        // correct and about nothing anybody asked.
        getFlow(ctx, ctx.token(), "?from=5000-01-01&to=5000-01-10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("5000-01-01")));

        // And the other end — dates before the epoch are equally a typo, not history.
        getFlow(ctx, ctx.token(), "?from=1200-01-01&to=1200-01-10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("1970")));
    }
}
