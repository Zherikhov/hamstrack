package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1's window rules, asserted on R3's endpoint — because they are now <em>one</em> implementation
 * ({@code ReportWindow}) and the thing worth guarding is that every report keeps calling it.
 *
 * <p>The rule itself: a window this server will not serve is <strong>refused with the cap
 * named</strong>, never quietly narrowed. A clamp has no symptom — the chart renders, the numbers
 * are internally consistent, and they are about a period the caller did not ask for.
 *
 * <p>{@code app.reports.max-window-days} is 30 here so the boundary is exercised with dates a
 * human can check, and — the case that shipped broken once in R1 — so the parameterless call the
 * reports page makes on load is still served: the server's own 90-day default must be capped to a
 * window it will accept, or a low configured maximum turns the endpoint's own default request
 * into a permanent 400.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.reports.max-window-days=30"
})
@AutoConfigureMockMvc
class CycleTimeWindowTest extends CycleTimeTestBase {

    @Test
    void aWindowWiderThanTheCapIsRefusedAndTheCapIsNamed() throws Exception {
        var ctx = newProject();

        getCycleTime(ctx, ctx.token(), "?from=2025-03-01&to=2025-04-15")
                .andExpect(status().isBadRequest())
                // The operator-facing property name, so the answer to "why?" is in the body.
                .andExpect(jsonPath("$.detail", containsString("app.reports.max-window-days")))
                // The configured cap, not a literal 365 — a clamp would have to invent one.
                .andExpect(jsonPath("$.detail", containsString("30")))
                // And what it actually measured, because the caller did the date arithmetic.
                .andExpect(jsonPath("$.detail", containsString("46 days")));
    }

    @Test
    void aReversedWindowIsRefused() throws Exception {
        var ctx = newProject();

        getCycleTime(ctx, ctx.token(), "?from=2025-03-31&to=2025-03-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("is after to")));
    }

    /**
     * The date band, which R1 added after two 500s: {@code +999999999-12-31} binds happily (
     * {@code ISO_DATE}'s year field is {@code EXCEEDS_PAD}) and then overflows on the half-open
     * window's own {@code plusDays(1)}, while a year past PostgreSQL's {@code timestamptz} ceiling
     * fails in the driver instead.
     *
     * <p>R3 shares that implementation, so what this pins is that it <em>calls</em> it — and calls
     * it <strong>before</strong> doing any arithmetic of its own, which is the only ordering in
     * which the band can protect anything. The message names the band rather than saying
     * "invalid", because the caller of a report API is usually a chart that did the date
     * arithmetic itself.
     */
    @Test
    void aDateOutsideTheSupportedBandIsRefusedRatherThanCrashing() throws Exception {
        var ctx = newProject();

        // The overflow case: the largest date ISO_DATE will parse.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(reportsBase(ctx) + "/cycle-time")
                        .param("to", "+999999999-12-31")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("2200")));

        // The driver case: past PostgreSQL's ceiling, which pgjdbc binds and the server refuses
        // with "timestamp out of range" — a 500 on an endpoint that promises 400.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(reportsBase(ctx) + "/cycle-time")
                        .param("from", "+500000-01-01").param("to", "+500000-01-10")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("2200")));

        // And the merely silly year, refused for the sake of the message: the report it would
        // otherwise return is empty, correct, and about nothing anybody asked.
        getCycleTime(ctx, ctx.token(), "?from=5000-01-01&to=5000-01-10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("5000-01-01")));

        // The other end — dates before the epoch are a typo, not history.
        getCycleTime(ctx, ctx.token(), "?from=1200-01-01&to=1200-01-10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("1970")));
    }

    /** The boundary itself is served — the cap is a maximum, not a strict inequality. */
    @Test
    void aWindowExactlyAtTheCapIsServed() throws Exception {
        var ctx = newProject();

        var report = cycleTime(ctx, "?from=2025-03-01&to=2025-03-30");

        assertThat(report.get("from").asText()).isEqualTo("2025-03-01");
        assertThat(report.get("to").asText()).isEqualTo("2025-03-30");
    }

    /**
     * The bug that shipped once: with a configured maximum below the 90-day default, the server's
     * own default window must shrink to fit rather than refuse itself.
     */
    @Test
    void theServersOwnDefaultWindowIsServableUnderALowCap() throws Exception {
        var ctx = newProject();

        var report = cycleTime(ctx, null);

        assertThat(java.time.LocalDate.parse(report.get("from").asText())
                .datesUntil(java.time.LocalDate.parse(report.get("to").asText()).plusDays(1))
                .count())
                .as("the default window is min(90, max-window-days) — never a self-inflicted 400")
                .isEqualTo(30);
    }

    /** {@code /aging} has no window at all, so the cap cannot reach it. */
    @Test
    void theAgingReportIsUnaffectedByTheWindowCap() throws Exception {
        var ctx = newProject();

        getAging(ctx, ctx.token()).andExpect(status().isOk());
    }
}
