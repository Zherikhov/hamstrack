package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.BacklogSectionResponse;
import com.hamstrack.issue.dto.BacklogViewResponse;
import com.hamstrack.issue.dto.LabelMatch;
import com.hamstrack.issue.service.BacklogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The planning READS of one project (HD-22, HD-96) — the whole view, {@code GET
 * …/projects/{projectId}/backlog}, and any one section of it under
 * {@code …/backlog/sections/…}. The view returns the project's OPEN sprint sections
 * (ACTIVE first, then FUTURE by sequence) above the rank-ordered backlog, so the
 * BacklogPage renders in a single round trip instead of one fetch per section; a section
 * mapping serves a client refreshing a section it already has, after a rank move, a
 * sprint assignment or the manual refresh control.
 *
 * <p><strong>A section fetch takes no {@code page} and no {@code size}, and that absence
 * is the fix</strong> (HD-96). The SPA used to refresh a section through {@code GET
 * …/issues} asking for {@code size = sectionCap}, which {@code Paging} silently narrowed
 * to 100 while answering 200 — so a section of 101 issues or more rendered whole and came
 * back truncated, with no signal that anything had been withheld. The client was right to
 * read the cap off the server's own response instead of hardcoding it; it asked a
 * legitimate question and got a narrowed answer. With no size on the wire there is no
 * number for a client to hold, none to echo back and nothing to clamp, so the behaviour
 * holds when an operator retunes {@code app.agile.section-max-issues} in either direction.
 *
 * <p>Any project member may read it; a missing workspace, a missing project or a
 * non-member all yield <strong>404</strong>, never 403.
 *
 * <p>The filter parameters are exactly the ones {@code GET …/issues} already accepts
 * and are all server-side, so filtering searches the WHOLE project rather than the
 * capped page already on screen. Each section is independently capped at
 * {@code app.agile.section-max-issues} and reports {@code truncated} /
 * {@code totalAvailable} (the HD-79 pattern) — while its stats are computed over the
 * whole section, so a truncated section still shows honest totals.
 *
 * <p>The response also carries {@code bulkMoveCap}
 * ({@code app.agile.max-issues-per-bulk-move}) beside {@code sectionCap}: the two are
 * independent operator knobs with deliberately different defaults, so a client that
 * derived one from the other would 400 on every "move all to…" of a section larger than
 * the bulk cap. With both on the wire the SPA chunks at what the install actually allows.
 * Both caps ride on a single-section response too: a view whose sections have each been
 * refreshed must still carry them, and must adopt a newer value if the operator retuned
 * in between.
 *
 * <p><strong>Throttle: none, deliberately, and as parity with the aggregate rather than
 * as an omission.</strong> A budget is earned by the work a handler does, not by where it
 * is mounted — this repo has already paid a review round for the opposite assumption — so
 * this is a decision recorded rather than a default inherited. Today no planning read has
 * a per-principal budget: {@code ThrottleCoverageTest} seals the throttled path set and
 * none of its patterns matches {@code …/projects/*}{@code /backlog}.
 *
 * <p><strong>What a section fetch divides, and what it repeats.</strong> It divides the row
 * assembly and the response: the aggregate runs one row query per open section and
 * assembles them all, a section fetch does one section's worth. It <strong>repeats</strong>
 * the grouped stats query, which is the <em>unconditional, cap-blind</em> term of a planning
 * read — it reads and groups a whole section whatever the filters say and whatever the cap
 * is — so refreshing every section of a view one at a time ships the same bytes as one
 * {@code GET …/backlog} while running that aggregation once per section rather than once.
 * The ceiling is unchanged (the aggregate is still the most expensive single request), and
 * the sprint mapping is narrowed to its own group so it aggregates that sprint's rows rather
 * than the project's; the backlog mapping genuinely has to scan its group and still pays it.
 *
 * <p><strong>Two phrasings this paragraph has already been wrong in, so neither is to be
 * reintroduced:</strong> <em>"a section fetch does less work than the aggregate"</em> (false
 * — it repeats the aggregation), and <em>"the stats query is the only term the response size
 * does not bound"</em> (also false — a {@code LIMIT} bounds output, never selection, and the
 * section's {@code ORDER BY} orders the whole filtered set first, so any filter matching
 * little still visits the section to return almost nothing). Each rewrite reached for a
 * tidier category-shaped sentence and each landed slightly wider than the evidence; that is
 * the standing hazard of the durable-claim rule, not an accident. The defensible line is
 * about a mechanism rather than about which term is "the only" anything: the stats query is
 * unconditional and cap-blind, a row query is unbounded in work only as its filters happen
 * to fall.
 *
 * <p>None of which changes the decision, only its reasoning. Throttling the section
 * endpoint alone would still be worse than throttling neither: a client refused on a
 * section falls back to the whole-view refetch, which runs the same aggregation anyway and
 * ships every section's rows with it. The invariant to keep is therefore a property of the
 * planning surface and not a list of today's paths — <em>every planning read carries the
 * same interceptor chain</em> — and {@code PlanningThrottleParityTest} holds it, so
 * "no budget" cannot quietly become an asymmetry.
 *
 * <p><strong>The follow-up (HD-174) is earned by the grouped stats query, not by response
 * size.</strong> A budget for {@code /api/workspaces/*}{@code /projects/*}{@code /backlog/**}
 * with a pot of its own is the shape in
 * {@code docs/design/backlog-section-refresh-proposal.md} §8, and it is a sealed-set edit
 * with a propagation checklist attached, so it does not belong in a defect fix. Whoever
 * takes it should know that {@code GET …/issues} and the board list are a <em>weaker</em>
 * case than the planning reads — weaker because neither runs an unconditional project-wide
 * aggregation, <strong>not</strong> because their work is bounded by what they return, which
 * is not true of any filtered, ordered, capped query. Whether they belong on the same pot is
 * that ticket's question and this javadoc deliberately does not pre-answer it.
 *
 * <p><strong>DC / Cloud: uniform.</strong> No new property, no env var, no profile gate,
 * no conditional bean — {@code app.agile.*} is a family of DoS guards with identical values
 * in both modes, and nothing here touches storage, email, auth or billing.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/backlog")
@RequiredArgsConstructor
public class BacklogController {

    private final BacklogService backlogService;

    /**
     * @param includeDone default <strong>false</strong>: a done, unranked issue is
     *                    planning noise. It affects the BACKLOG section only — sprint
     *                    sections always include their DONE issues, because that is the
     *                    sprint's record of what it delivered.
     */
    @GetMapping
    public BacklogViewResponse view(@AuthenticationPrincipal User actor,
                                    @PathVariable UUID workspaceId,
                                    @PathVariable UUID projectId,
                                    @RequestParam(required = false) UUID statusId,
                                    @RequestParam(required = false) UUID assigneeId,
                                    @RequestParam(required = false) UUID priorityId,
                                    @RequestParam(required = false) UUID componentId,
                                    @RequestParam(required = false) List<UUID> labelId,
                                    @RequestParam(defaultValue = "any") String labelMatch,
                                    @RequestParam(required = false) UUID fixVersionId,
                                    @RequestParam(defaultValue = "false") boolean includeDone) {
        // Parsed here (not bound as an enum @RequestParam): Spring's String→enum
        // conversion is case-sensitive, and the wire contract is lowercase any/all.
        var match = LabelMatch.parse(labelMatch);
        return backlogService.view(actor, workspaceId, projectId, statusId, assigneeId,
                priorityId, componentId, labelId, match, fixVersionId, includeDone);
    }

    /**
     * The BACKLOG section on its own (HD-96). A literal path segment, so it can never
     * collide with the sprint mapping's UUID and wins that mapping by
     * {@code PathPattern} specificity regardless.
     *
     * <p>Its filter parameters are exactly {@link #view}'s, {@code includeDone} included:
     * a refresh that accepted a different set would answer a different question than the
     * render did, which is this defect's own shape one layer up.
     * {@code BacklogSectionParityTest} asserts the three signatures agree.
     */
    @GetMapping("/sections/backlog")
    public BacklogSectionResponse backlogSection(@AuthenticationPrincipal User actor,
                                                 @PathVariable UUID workspaceId,
                                                 @PathVariable UUID projectId,
                                                 @RequestParam(required = false) UUID statusId,
                                                 @RequestParam(required = false) UUID assigneeId,
                                                 @RequestParam(required = false) UUID priorityId,
                                                 @RequestParam(required = false) UUID componentId,
                                                 @RequestParam(required = false) List<UUID> labelId,
                                                 @RequestParam(defaultValue = "any") String labelMatch,
                                                 @RequestParam(required = false) UUID fixVersionId,
                                                 @RequestParam(defaultValue = "false") boolean includeDone) {
        var match = LabelMatch.parse(labelMatch);
        return backlogService.section(actor, workspaceId, projectId, null, statusId, assigneeId,
                priorityId, componentId, labelId, match, fixVersionId, includeDone);
    }

    /**
     * One SPRINT section on its own (HD-96). The sprint is resolved through the project,
     * so a sprint id from a sibling project in the same workspace is a <strong>404</strong>
     * rather than an answer; a malformed one is a 400 at binding, before any handler runs.
     *
     * <p>No {@code includeDone}: it applies to the backlog section only, here as in the
     * aggregate, because a sprint's DONE issues are its record of what it delivered.
     *
     * <p>A COMPLETED sprint answers 200. It drops out of the aggregate — which lists open
     * sprints — so the client's next full refetch removes the section, but a refresh
     * already in flight must not turn into a 404 or a 409: that would be state changing a
     * status code.
     */
    @GetMapping("/sections/{sprintId}")
    public BacklogSectionResponse sprintSection(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId,
                                                @PathVariable UUID projectId,
                                                @PathVariable UUID sprintId,
                                                @RequestParam(required = false) UUID statusId,
                                                @RequestParam(required = false) UUID assigneeId,
                                                @RequestParam(required = false) UUID priorityId,
                                                @RequestParam(required = false) UUID componentId,
                                                @RequestParam(required = false) List<UUID> labelId,
                                                @RequestParam(defaultValue = "any") String labelMatch,
                                                @RequestParam(required = false) UUID fixVersionId) {
        var match = LabelMatch.parse(labelMatch);
        return backlogService.section(actor, workspaceId, projectId, sprintId, statusId, assigneeId,
                priorityId, componentId, labelId, match, fixVersionId, false);
    }
}
