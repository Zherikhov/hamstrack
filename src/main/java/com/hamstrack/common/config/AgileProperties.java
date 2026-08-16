package com.hamstrack.common.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds on the agile primitives of a project — how many issues one planning
 * section renders, how many open sprints a project may hold, how long a sprint
 * runs by default, and how many issues one bulk move may carry
 * (agile-sprints-proposal §3.7).
 *
 * <p>These are <strong>DoS guards and defaults, not mode switches</strong>:
 * identical values in DC and Cloud, no profile override, no feature flag (a kill
 * switch would create a second, untested code path — the same stance HD-6 took).
 * Everything sprints touch is workspace/project-scoped and membership-guarded, so
 * there is nothing to fork.
 *
 * <p><strong>Fail fast, never clamp</strong> ({@code @Validated}, mirroring
 * {@link ClassificationProperties}): an out-of-range value aborts startup rather
 * than silently bricking the planning view or being clamped behind the operator's
 * back.
 */
@Validated
@ConfigurationProperties(prefix = "app.agile")
public record AgileProperties(
        /*
         * Per-section cap in GET …/backlog. A planning view is
         * (max-open-sprints + 1) sections wide, so the worst-case payload is
         * bounded by the product of these two numbers (6300 rows at the defaults;
         * typical is a few hundred). A truncated section still reports honest
         * whole-section stats plus `truncated` + `totalAvailable` — the HD-79
         * pattern — so the SPA can link to Search instead of paging.
         */
        @DefaultValue("300") @Min(1) @Max(2000) int sectionMaxIssues,
        /*
         * FUTURE + ACTIVE sprints per project (COMPLETED ones are history and
         * cannot be started, so they do not count). 422 on POST /sprints when the
         * cap is reached. Bounds the planning view's width and the "which sprint?"
         * pickers; it is not a licensing knob.
         */
        @DefaultValue("20") @Min(1) @Max(100) int maxOpenSprintsPerProject,
        /*
         * The `endAt` a start defaults to when the caller sends none:
         * startAt + this many days. Two weeks is the common iteration length; a
         * team that runs one-week or four-week sprints sets it once per install.
         */
        @DefaultValue("14") @Min(1) @Max(90) int defaultSprintLengthDays,
        /*
         * Ids accepted by POST …/sprints/{id}/issues in one request. Beyond it the
         * request is malformed (400), not a business-rule rejection: the client is
         * expected to chunk. Bounds one transaction's write set.
         */
        @DefaultValue("100") @Min(1) @Max(500) int maxIssuesPerBulkMove
) {

    /**
     * The widest planning view the operator may configure — {@code (open sprints + 1) ×
     * section cap}, i.e. the number of fully-assembled {@code IssueResponse}s ONE
     * unpaged {@code GET …/backlog} can build.
     *
     * <p>It is also the ceiling on {@link BoardProperties# maxIssues} ({@code @Max}
     * references this constant, so the two cannot drift): a board is the same animal —
     * one unpaged list of assembled {@code IssueResponse}s — and a cheaper one, so a
     * separate number would be a second, contradictory budget for the same work.
     *
     * <p><strong>Changing this value changes the board cap too</strong>, and the
     * "1–20000" range is quoted to operators in three documents that are NOT generated
     * from here: {@code .env.prod.example}, {@code docs/self-hosting.md} and
     * {@code docs/api-dc.md}. Update all three in the same change.
     */
    static final int MAX_PLANNING_VIEW_ROWS = 20_000;

    /**
     * The per-field maxima are not composable, so the PRODUCT is validated too
     * (security review L7).
     *
     * <p>Each bound is individually defensible, but an operator who sets both to their
     * validated maxima gets a single unpaged planning view of 101 × 2000 ≈ 202 000
     * assembled issues — a self-inflicted OOM that "fail fast, never clamp" would
     * otherwise accept without a word. At the defaults the product is 6300, so this only
     * ever fires on a genuinely dangerous combination, and it fires at STARTUP with a
     * message naming both knobs rather than at 3am under load.
     */
    @AssertTrue(message = "app.agile.section-max-issues x (app.agile.max-open-sprints-per-project + 1) "
            + "must not exceed " + MAX_PLANNING_VIEW_ROWS + " — one GET /backlog assembles that many "
            + "issues in a single unpaged response; lower one of the two")
    public boolean isPlanningViewBounded() {
        return (long) sectionMaxIssues * (maxOpenSprintsPerProject + 1) <= MAX_PLANNING_VIEW_ROWS;
    }
}
