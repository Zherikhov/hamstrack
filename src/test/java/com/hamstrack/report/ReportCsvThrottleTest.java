package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The exports spend the reports budget, and they spend the SAME budget as the JSON
 * endpoints</strong> (HD-141 R7).
 *
 * <p>{@code ThrottleCoverageTest} already asks the real handler mapping whether every handler in
 * {@code com.hamstrack.report.controller} sits behind a per-principal throttle, and the six
 * handlers added here are covered by it by being in that package. This file asserts the two
 * things that structural test deliberately does not: that the budget actually refuses, and — the
 * one that matters — that a download is charged to the <em>same pot</em> as the report it
 * downloads.
 *
 * <p>Why that second one is worth a test. R6's lesson was that "inherited by construction" holds
 * only for endpoints mounted where the pattern points, and an export is the most natural candidate
 * for a second mount point (a {@code /downloads} path, a separate controller, a servlet). If
 * {@code …/reports/velocity.csv} ever ends up outside {@code REPORTS_PATH}, a caller gets a second
 * full allowance for the identical query merely by asking for it as a file, and every report on
 * the surface becomes twice as expensive to abuse. Alternating between the two shapes below is
 * what that failure looks like from outside.
 *
 * <p>This file used to also pin {@code REPORTS.hasSize(6)} — "so a seventh export cannot be added
 * without updating the list the tenancy loops iterate". Round 2 found that guard cannot fire for
 * the case it names: a seventh handler that nobody adds to {@code REPORTS} leaves the list at six,
 * the size assertion passes, and the new export is covered by none of the tenancy loops. A count
 * is not a set. It is now {@code ReportCsvSurfaceTest}, which derives the expected set from the
 * real handler mapping.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=1000",
        "app.reports.requests-per-minute=4",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ReportCsvThrottleTest extends ReportCsvTestBase {

    @Test
    void anExportPastTheBudgetIsA429WithRetryAfter() throws Exception {
        var ctx = newProject();

        for (int i = 0; i < 4; i++) {
            getCsv(ctx, ctx.token(), "aging.csv", "").andExpect(status().isOk());
        }

        getCsv(ctx, ctx.token(), "aging.csv", "")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail", containsString("report")));
    }

    @Test
    void anExportAndItsJsonEndpointShareOnePot() throws Exception {
        var ctx = newProject();

        getAging(ctx, ctx.token()).andExpect(status().isOk());
        getCsv(ctx, ctx.token(), "aging.csv", "").andExpect(status().isOk());
        getAging(ctx, ctx.token()).andExpect(status().isOk());
        getCsv(ctx, ctx.token(), "aging.csv", "").andExpect(status().isOk());

        getCsv(ctx, ctx.token(), "aging.csv", "")
                .andExpect(status().isTooManyRequests());
        getAging(ctx, ctx.token())
                .andExpect(status().isTooManyRequests());
    }

}
