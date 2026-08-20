package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.dto.PageResponse;
import com.hamstrack.common.dto.Paging;
import com.hamstrack.issue.dto.AddIssuesToSprintRequest;
import com.hamstrack.issue.dto.CompleteSprintRequest;
import com.hamstrack.issue.dto.CreateSprintRequest;
import com.hamstrack.issue.dto.SprintCompletionPreview;
import com.hamstrack.issue.dto.SprintCompletionResult;
import com.hamstrack.issue.dto.SprintResponse;
import com.hamstrack.issue.dto.StartSprintRequest;
import com.hamstrack.issue.dto.UpdateSprintRequest;
import com.hamstrack.issue.entity.SprintState;
import com.hamstrack.issue.service.SprintService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Project sprints (HD-22) — a project-scoped time-box with a one-way lifecycle
 * (FUTURE → ACTIVE → COMPLETED). Sprints are <em>content</em>, not bound taxonomy:
 * they never appear in {@code GET …/projects/{p}/config} and have their own cache key
 * in the SPA (§3.6), so a sprint start does not invalidate the config every board
 * render depends on.
 *
 * <p>Every endpoint resolves through workspace→project membership: a missing
 * workspace, a missing project or a non-member all yield <strong>404</strong> (never
 * 403 — no existence leak, the project's #1 bug class). <strong>403</strong> is
 * reserved for a member who lacks the permission — {@code sprint.manage}, held by the
 * built-in project MANAGER and, in every project of their workspace, by a workspace
 * OWNER/ADMIN through {@code project.curate.all} (HD-123 §10.2).
 *
 * <ul>
 *   <li>{@code GET    …/sprints?state=&page=&size=} — any project member. ALWAYS paged:
 *       open sprints are capped but COMPLETED ones accumulate for years. {@code state}
 *       is repeatable; omitting it returns all states. Order: ACTIVE, then FUTURE by
 *       sequence, then COMPLETED newest-first;</li>
 *   <li>{@code POST   …/sprints} — curator; 409 on a duplicate name (case-insensitive
 *       within the project, COMPLETED rows included), 422 at the open-sprint cap;</li>
 *   <li>{@code GET    …/sprints/{id}} — any project member;</li>
 *   <li>{@code PATCH  …/sprints/{id}} — curator (name/goal/dates only; the lifecycle
 *       moves through start/complete and there is NO re-open). 422 when it would move
 *       {@code startAt} on a sprint that has left FUTURE: the commitment rows are dated
 *       the original start, so re-dating a running or completed sprint would silently
 *       re-draw its burn-up;</li>
 *   <li>{@code POST   …/sprints/{id}/start} — curator; 409 when not FUTURE or when
 *       another sprint is already active, 422 on a {@code startAt} in the future (five
 *       minutes of client clock skew tolerated): starting is the act that turns a plan
 *       into history, and the PATCH freeze above would make a forward date permanent;</li>
 *   <li>{@code GET    …/sprints/{id}/completion-preview} — any project member;</li>
 *   <li>{@code POST   …/sprints/{id}/complete} — curator; 409 when not ACTIVE;</li>
 *   <li>{@code POST   …/sprints/{id}/issues} — the ISSUE-EDIT tier (any member who may
 *       edit issues), because planning is teamwork; 422 when the TARGET sprint is
 *       COMPLETED and when any issue in the batch is currently in a COMPLETED one
 *       (this is a removal path too), validated up front so the batch fails atomically;</li>
 *   <li>{@code DELETE …/sprints/{id}/issues/{issueId}} — issue-edit tier; 204 either way
 *       when the issue is not in the sprint, 422 when the sprint is COMPLETED;</li>
 *   <li>{@code DELETE …/sprints/{id}?force=} — curator; 409 for an ACTIVE sprint or one
 *       that still holds issues without {@code force}; 404 when a concurrent delete won
 *       the race (the conditional delete affected no row).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/sprints")
@RequiredArgsConstructor
public class SprintController {

    private final SprintService sprintService;

    /**
     * The sprint list. {@code state} is a repeatable filter ({@code ?state=ACTIVE&state=FUTURE});
     * omitting it returns every state. Counters ride along — they cost ONE grouped query
     * for the whole page, so there is nothing to opt out of.
     *
     * <p>The {@link Sort} is deliberately {@link Sort#unsorted()}: the repository's
     * ORDER BY encodes the three-group ordering, and a Sort from the Pageable would be
     * appended after it and silently take precedence.
     */
    @GetMapping
    public PageResponse<SprintResponse> list(@AuthenticationPrincipal User actor,
                                             @PathVariable UUID workspaceId,
                                             @PathVariable UUID projectId,
                                             @RequestParam(required = false) List<SprintState> state,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return sprintService.list(actor, workspaceId, projectId, state,
                Paging.of(page, size, Sort.unsorted()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SprintResponse create(@AuthenticationPrincipal User actor,
                                 @PathVariable UUID workspaceId,
                                 @PathVariable UUID projectId,
                                 @Valid @RequestBody CreateSprintRequest req) {
        return sprintService.create(actor, workspaceId, projectId, req);
    }

    @GetMapping("/{sprintId}")
    public SprintResponse get(@AuthenticationPrincipal User actor,
                              @PathVariable UUID workspaceId,
                              @PathVariable UUID projectId,
                              @PathVariable UUID sprintId) {
        return sprintService.get(actor, workspaceId, projectId, sprintId);
    }

    @PatchMapping("/{sprintId}")
    public SprintResponse update(@AuthenticationPrincipal User actor,
                                 @PathVariable UUID workspaceId,
                                 @PathVariable UUID projectId,
                                 @PathVariable UUID sprintId,
                                 @Valid @RequestBody UpdateSprintRequest req) {
        return sprintService.update(actor, workspaceId, projectId, sprintId, req);
    }

    /**
     * Start. The body is optional — {@code POST} with no body means "start it now and
     * run it for {@code app.agile.default-sprint-length-days}". Idempotent-looking
     * success is deliberately NOT offered: a second call is a <strong>409</strong>, and
     * so is starting one while another sprint is already active.
     *
     * <p>A {@code startAt} in the FUTURE is a <strong>422</strong> (backdating stays
     * legal) — see {@code SprintService.requireStartHasArrived}. The caller's remedy is
     * to leave the sprint planned: a FUTURE sprint's dates stay editable, and this call
     * stamps the start itself.
     */
    @PostMapping("/{sprintId}/start")
    public SprintResponse start(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId,
                                @PathVariable UUID projectId,
                                @PathVariable UUID sprintId,
                                @Valid @RequestBody(required = false) StartSprintRequest req) {
        return sprintService.start(actor, workspaceId, projectId, sprintId, req);
    }

    /**
     * The completion dialog's data source: the same counters the subsequent completion
     * will report, plus the FUTURE sprints the unfinished work may be carried over to.
     * Read-only, and readable by any project member — looking at the numbers is not a
     * commitment.
     */
    @GetMapping("/{sprintId}/completion-preview")
    public SprintCompletionPreview completionPreview(@AuthenticationPrincipal User actor,
                                                     @PathVariable UUID workspaceId,
                                                     @PathVariable UUID projectId,
                                                     @PathVariable UUID sprintId) {
        return sprintService.completionPreview(actor, workspaceId, projectId, sprintId);
    }

    /**
     * Complete. Unlike {@code start} the body is REQUIRED: the disposition of the
     * unfinished work is a real decision, and defaulting it silently would move issues
     * nobody asked to move. A double-click is a 409 — the move is destructive, so an
     * idempotent-looking success would hide exactly the case that must not be hidden.
     */
    @PostMapping("/{sprintId}/complete")
    public SprintCompletionResult complete(@AuthenticationPrincipal User actor,
                                           @PathVariable UUID workspaceId,
                                           @PathVariable UUID projectId,
                                           @PathVariable UUID sprintId,
                                           @Valid @RequestBody CompleteSprintRequest req) {
        return sprintService.complete(actor, workspaceId, projectId, sprintId, req);
    }

    /**
     * Bulk-assign issues. The <strong>issue-edit</strong> tier, not curator: putting
     * items into a sprint is ordinary planning work the whole team does at a planning
     * meeting. An issue already in the sprint is a silent no-op (no history row, no
     * version bump); a COMPLETED sprint is a 422.
     *
     * <p>Assigning DETACHES from whatever sprint the issue is in today, so this is a
     * removal path as well and carries the same freeze on the source side: an issue
     * currently in a COMPLETED sprint is a 422 ("Sprint 'X' is completed") too. The
     * whole batch is checked before anything moves, so one frozen member fails the
     * request atomically instead of leaving it half-applied.
     */
    @PostMapping("/{sprintId}/issues")
    public SprintResponse addIssues(@AuthenticationPrincipal User actor,
                                    @PathVariable UUID workspaceId,
                                    @PathVariable UUID projectId,
                                    @PathVariable UUID sprintId,
                                    @Valid @RequestBody AddIssuesToSprintRequest req) {
        return sprintService.addIssues(actor, workspaceId, projectId, sprintId, req);
    }

    /**
     * Take one issue out of the sprint; the rank is preserved. Idempotent — an issue that
     * is NOT in this sprint is a 204, and that check runs FIRST, so it stays a 204 even
     * for a COMPLETED sprint. Removing an issue that IS in a COMPLETED sprint is a 422:
     * its membership is the delivery record the completion already reported.
     */
    @DeleteMapping("/{sprintId}/issues/{issueId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeIssue(@AuthenticationPrincipal User actor,
                            @PathVariable UUID workspaceId,
                            @PathVariable UUID projectId,
                            @PathVariable UUID sprintId,
                            @PathVariable UUID issueId) {
        sprintService.removeIssue(actor, workspaceId, projectId, sprintId, issueId);
    }

    /**
     * Delete. An ACTIVE sprint is a 409 ("complete it first"); a FUTURE/COMPLETED one
     * that still holds issues is a 409 unless {@code force=true}, which detaches them
     * first and preserves their rank.
     *
     * <p>The row goes away through a CONDITIONAL delete keyed by {@code (id, project)},
     * so two curators deleting the same sprint at once do not 500: the loser's statement
     * affects no row and it gets a <strong>404</strong> — the sprint is gone either way.
     */
    @DeleteMapping("/{sprintId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User actor,
                       @PathVariable UUID workspaceId,
                       @PathVariable UUID projectId,
                       @PathVariable UUID sprintId,
                       @RequestParam(defaultValue = "false") boolean force) {
        sprintService.delete(actor, workspaceId, projectId, sprintId, force);
    }
}
