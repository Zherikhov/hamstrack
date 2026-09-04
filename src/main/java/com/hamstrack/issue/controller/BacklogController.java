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
 * <p><strong>Throttle: BOTH controls, on every planning read, from one
 * registration</strong> (HD-174). {@code PlanningRateLimitConfig} binds
 * {@code /api/workspaces/*}{@code /projects/*}{@code /backlog/**} to a single
 * {@code PrincipalThrottleInterceptor} carrying (a) the planning budget —
 * {@code app.planning.requests-per-minute}, default 240, its own pot rather than the
 * reports one because a card dragged across sections costs two section refreshes and
 * 60/min would 429 an ordinary grooming session — and (b) the shared expensive-read
 * occupancy bound, {@code app.expensive-read.max-in-flight}, which is the half that
 * matters here: {@code BacklogService.view} holds ONE pool connection across
 * {@code 12 + N} statements, 32 at {@code AGILE_MAX_OPEN_SPRINTS=20}, and
 * {@code DB_STATEMENT_TIMEOUT_MS} bounds each of them and not their sum. Past either:
 * {@code 429} — with {@code Retry-After} and no {@code errorType} for the budget, or
 * {@code errorType} {@code TOO_MANY_IN_FLIGHT} / {@code EXPENSIVE_SURFACE_BUSY} with
 * {@code Retry-After: 1} for the permit. <strong>A refusal is never a narrowed section, a
 * smaller {@code sectionCap} or a truncated view</strong>: the honesty protocol below is a
 * statement about the data, and a throttle must not become the thing that changes it.
 *
 * <p>One pattern, so a fourth planning read is covered the moment its {@code @GetMapping}
 * exists — a budget is earned by the work a handler does, not by where it is mounted, and
 * this repo has already paid a review round for the opposite assumption. The occupancy
 * share is the EXISTING one rather than a second (ADR-0031), so this added no number that
 * has to sit below {@code DB_POOL_MAX_SIZE} and no new way to fail a boot.
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
 * <p>None of which changed the decision, only its reasoning — and the decision it produced
 * is why HD-174 budgeted the whole surface in one pattern rather than the aggregate alone.
 * Throttling the section endpoint alone would still be worse than throttling neither: a
 * client refused on a section falls back to the whole-view refetch, which runs the same
 * aggregation anyway and ships every section's rows with it. <strong>That asymmetry has an
 * error-path twin the client owes an answer to</strong>: a section refresh whose catch
 * invalidates the aggregate turns a cheap refusal into the expensive request, so a 429 of
 * any kind on a section must leave the section stale rather than provoke a
 * {@code GET …/backlog}. The invariant to keep is a property of the planning surface and
 * not a list of today's paths — <em>every planning read carries the same interceptor chain,
 * carrying the planning budget AND an occupancy bound</em> — and
 * {@code PlanningThrottleParityTest} holds it over a set derived from the registrations, so
 * a fourth planning read cannot quietly become an asymmetry.
 *
 * <p><strong>The budget was earned by the grouped stats query, not by response size</strong>
 * — HD-174, landed, with the pot and the pattern that
 * {@code docs/design/backlog-section-refresh-proposal.md} §8 recommended. What it
 * deliberately did NOT take in is {@code GET …/issues} (board + list) and
 * {@code GET …/sprints}: they are a <em>weaker</em> case — weaker because neither runs an
 * unconditional project-wide aggregation, <strong>not</strong> because their work is
 * bounded by what they return, which is not true of any filtered, ordered, capped query.
 * Extending the pattern to them is an argument to be made on their own worst case, so the
 * omission is reasoned rather than forgotten and is not to be "fixed" by widening the
 * pattern.
 *
 * <p><strong>DC / Cloud: uniform.</strong> No profile gate and no conditional bean. There
 * IS a new property — {@code app.planning.requests-per-minute}, env
 * {@code PLANNING_REQUESTS_PER_MINUTE} — but it is a <em>product</em> property rather than a
 * plan one: it ships with the same default in both modes, is never varied per tenant, and
 * joins {@code app.agile.*} as a family of DoS guards with identical values in both. The
 * occupancy bound it sits beside brings no dial of its own at all. Nothing here touches
 * storage, email, auth or billing, so the two modes differ in no observable way on this
 * surface — the conclusion is unchanged; only the reason it holds is narrower than "nothing
 * was configured".
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
