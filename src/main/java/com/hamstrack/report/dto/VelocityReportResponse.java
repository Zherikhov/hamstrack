package com.hamstrack.report.dto;

import java.util.List;

/**
 * {@code GET …/reports/velocity} (reports-proposal §2.5) — the last N completed sprints as bars,
 * and beside them the band to plan the next one with.
 *
 * <p>The whole response is one sentence with a picture: <em>"Recent sprints delivered between 14 and
 * 23 issues; plan for ~18 (p50) and treat 23 (p85) as a stretch. Based on 6 sprints."</em> Read
 * {@link VelocityForecast} for why that sentence is the shape it is.
 *
 * <h2>What this response cannot say, by construction</h2>
 * §1.4 refuses velocity-as-a-scoreboard on evidence — cross-team comparison <em>"harms their
 * estimating process, creates inflated estimates, and demoralizes the team"</em> — and the refusals
 * are implemented rather than annotated:
 * <ul>
 *   <li><strong>Nothing is keyed by a person.</strong> No field here or in {@link VelocitySprint} is
 *       an assignee, an actor or an owner, and the statement behind it does not select a person
 *       column at all. Not as a filter, not as a tooltip field, not in a future CSV.</li>
 *   <li><strong>Nothing aggregates above the project.</strong> This shape exists on exactly one
 *       endpoint, and that endpoint is under {@code /projects/{projectId}/}. Comparing two teams
 *       means opening two pages and doing the arithmetic by hand, and
 *       <strong>that friction is the design</strong> — it is the cost of the comparison, made
 *       visible to whoever is about to make it. Anyone tempted to add a workspace-level rollup
 *       "for convenience" is removing the only thing standing between this report and its
 *       documented harm.</li>
 *   <li><strong>No single headline number.</strong> There is no {@code averageVelocity} field and
 *       there is not meant to be one; the band is a range with a sample size, precisely so there is
 *       nothing to quote in a status report.</li>
 * </ul>
 * {@code VelocityRefusalTest} pins all three from the outside, because a refusal that lives only in
 * a comment is one helpful pull request away from being undone.
 *
 * @param measure  what the numbers count — {@code COUNT} by default, the measure every project has.
 *                 Echoed so a cached or shared response says what it is measuring
 * @param sprints  the sampled sprints in <strong>chronological order, oldest first</strong>, so the
 *                 client renders them left to right in time without sorting. Empty for a project
 *                 that has never completed a sprint — a 200 with an empty list and a suppressed
 *                 band, never a 404 and never an error: "you have not finished a sprint yet" is an
 *                 answer, and §6 requires an empty state rather than a blank panel
 * @param forecast the band, always present, its percentiles null below the suppression threshold
 * @param meta     the shared provenance block. {@code basedOnIssues} counts the distinct issues the
 *                 sampled ledgers hold, above the row cap; {@code firstIssueAt} is the project's
 *                 earliest issue and never the sample's
 */
public record VelocityReportResponse(
        ReportMeasure measure,
        List<VelocitySprint> sprints,
        VelocityForecast forecast,
        ReportMeta meta
) {}
