package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Board query bounds (HD-79). {@code GET .../issues} with no {@code size} param
 * returns the board's cards; without a cap it returned EVERY issue in the project —
 * an OOM/latency risk on large projects. {@code maxIssues} caps that response
 * server-side (never client-overridable); the response reports truncation so the SPA
 * can nudge power users to filters / Backlog / Search (all already paginated).
 *
 * <p>This is a <strong>DoS guard, not a mode switch</strong>: identical values in DC
 * and Cloud, no profile override, no feature flag. A board response is
 * workspace/project-scoped and membership-guarded either way, so there is nothing to
 * fork.
 *
 * <p><strong>Fail fast, never clamp</strong> ({@code @Validated}, the posture
 * {@link ClassificationProperties} and {@link AgileProperties} take): {@code
 * BOARD_MAX_ISSUES=0} used to bind silently and empty EVERY board in the install —
 * each request returned {@code {issues: [], truncated: true}} with nothing in the
 * logs pointing at the typo (HD-92). An out-of-range value now aborts startup, the
 * same way {@code JwtService} refuses a too-short secret. Silently rewriting an
 * operator's value would be worse than refusing to boot.
 */
@Validated
@ConfigurationProperties(prefix = "app.board")
public record BoardProperties(
        /*
         * Upper bound = AgileProperties.MAX_PLANNING_VIEW_ROWS, the assembled
         * IssueResponses that record already declares as the most ONE unpaged response
         * may build (its planning-view product bound); referencing the constant keeps
         * the two from drifting. A board is exactly that animal — a single unpaged list, and
         * a cheaper one than the backlog (one capped query + one conditional count,
         * versus a query plus whole-section stats per section) — so pinning it to a
         * smaller number would invent a second, contradictory budget for the same
         * work. It is a refusal point, not a recommendation: 500 is the default, and
         * a board wide enough to need thousands wants filters / Backlog / Search, all
         * of which are paginated.
         */
        @DefaultValue("500") @Min(1) @Max(AgileProperties.MAX_PLANNING_VIEW_ROWS) int maxIssues
) {}
