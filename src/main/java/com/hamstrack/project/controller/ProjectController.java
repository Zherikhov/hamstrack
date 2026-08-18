package com.hamstrack.project.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.project.dto.*;
import com.hamstrack.project.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Project management within a workspace: CRUD, archive/unarchive and project
 * membership. Visible to all workspace members; the creator gets MANAGER
 * automatically. Archived projects are hidden from listings by default and
 * reject issue mutations.
 *
 * <p><strong>Two mutation tiers</strong> since HD-22 (agile-sprints-proposal
 * §3.2): {@code PATCH /{projectId}} (name / description / {@code delivery})
 * takes {@code project.edit} — the built-in project MANAGER, <strong>or</strong> a
 * workspace OWNER/ADMIN through {@code project.curate.all} (HD-123 §10.2) — which
 * aligns it with every other project-content write (components, versions,
 * sprints) and with the SPA's settings gate. {@code archive}/{@code unarchive}
 * ({@code project.archive}) and member management
 * ({@code project.member.manage}) are <strong>not</strong> in that curator set,
 * so they stay MANAGER-only. Listing members is open to any workspace member
 * (§10.3.1): its old {@code requireRole(VIEWER)} gate passed for everybody.
 *
 * <p>{@code DELETE /{projectId}/members/{userId}} additionally answers
 * <strong>409</strong> when the target is the project's last administrator. Since HD-136
 * that means the last member holding {@code project.member.manage} <em>whatever role
 * carries it</em> — the guard asks the permission, not the built-in MANAGER role id, so a
 * sole administrator on a custom role is protected too ({@code ProjectAdminGuard}). A lost
 * row-lock race is <em>also</em> a 409 here — this is the third of the three transactions
 * bounded by {@code LockTimeout} — and the two are told apart by {@code Retry-After}: the
 * contention one carries it and means retry the identical request, the last-administrator
 * one does not and means change something first (appoint another administrator).
 *
 * <p>An <strong>archived</strong> project is frozen: {@code PATCH /{projectId}}
 * returns <strong>409 "Project is archived"</strong>, the same answer every issue
 * edit, sprint mutation and rank move already gives. That includes
 * {@code delivery}, which changes how the board, the backlog, the rail and the
 * issue detail render; {@code unarchive} is the way back. Reads keep working.
 *
 * <p><strong>Delivery capabilities</strong> (HD-102, delivery-paths-proposal §11.3)
 * ride the create/patch bodies and every {@code ProjectResponse}:
 * {@code delivery: { board, releases, estimation, preset }}. Status codes here are
 * <strong>200/201</strong> normal · <strong>400</strong> for an unknown enum value,
 * a {@code delivery.preset} attempt (it is derived, never settable) or a
 * {@code boardMode} that disagrees with {@code delivery.board} · <strong>403</strong>
 * member without the curation role · <strong>404</strong> unknown workspace/project
 * or non-member · <strong>409</strong> archived.
 *
 * <p>The two 400s reach the client in <strong>different shapes</strong>, and the docs
 * say so. {@code delivery.preset} is a {@code @Null} constraint on a
 * {@code @Valid}-cascaded nested record, so it fails at the validation boundary and
 * {@code GlobalExceptionHandler.handleValidation} answers a field-anchored body whose
 * {@code errors} map is keyed {@code delivery.preset}. The {@code boardMode} /
 * {@code delivery.board} disagreement is a cross-field rule enforced in
 * {@code ProjectService.applyDelivery}, so it answers a plain {@code ProblemDetail}
 * with no {@code errors} map. Both rejections are total — neither applies anything
 * else from the same body.
 * <strong>No status code anywhere in the API depends on a capability</strong>
 * (Rule A, §5.1): {@code POST /sprints} still succeeds on a Kanban project and
 * {@code fixVersionIds} still applies with releases off. A capability is a
 * presentation preference; it is never an authorization boundary, and reviewers must
 * not read a hidden control as a protected one.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId,
                                  @Valid @RequestBody CreateProjectRequest req) {
        return projectService.create(actor, workspaceId, req);
    }

    @GetMapping
    public List<ProjectResponse> list(@AuthenticationPrincipal User actor,
                                      @PathVariable UUID workspaceId,
                                      @RequestParam(defaultValue = "false") boolean includeArchived) {
        return projectService.list(actor, workspaceId, includeArchived);
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId,
                               @PathVariable UUID projectId) {
        return projectService.get(actor, workspaceId, projectId);
    }

    @PatchMapping("/{projectId}")
    public ProjectResponse update(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId,
                                  @PathVariable UUID projectId,
                                  @Valid @RequestBody UpdateProjectRequest req) {
        return projectService.update(actor, workspaceId, projectId, req);
    }

    @PostMapping("/{projectId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@AuthenticationPrincipal User actor,
                        @PathVariable UUID workspaceId,
                        @PathVariable UUID projectId) {
        projectService.archive(actor, workspaceId, projectId);
    }

    @PostMapping("/{projectId}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchive(@AuthenticationPrincipal User actor,
                          @PathVariable UUID workspaceId,
                          @PathVariable UUID projectId) {
        projectService.unarchive(actor, workspaceId, projectId);
    }

    @GetMapping("/{projectId}/members")
    public List<ProjectMemberResponse> listMembers(@AuthenticationPrincipal User actor,
                                                   @PathVariable UUID workspaceId,
                                                   @PathVariable UUID projectId) {
        return projectService.listMembers(actor, workspaceId, projectId);
    }

    @PostMapping("/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectMemberResponse addMember(@AuthenticationPrincipal User actor,
                                           @PathVariable UUID workspaceId,
                                           @PathVariable UUID projectId,
                                           @Valid @RequestBody AddProjectMemberRequest req) {
        return projectService.addMember(actor, workspaceId, projectId, req);
    }


    /**
     * Change an existing project member's role (HD-127, M4) — the project twin of
     * {@code PATCH /api/workspaces/{ws}/members/{userId}}.
     *
     * <p>Gate: {@code project.member.manage}, plus the grant ceiling on <em>both</em> the
     * target's current role and the requested one, plus the §4 escape (any holder of
     * {@code project.member.manage} may always grant the built-in Project admin — to somebody
     * else, never to themselves), plus the last-administrator invariant, which a demotion can
     * break with no row removed at all.
     *
     * <p>200 · <strong>403</strong> missing the permission, or a ceiling refusal naming the
     * permission · <strong>404</strong> unknown workspace, caller not a member, project not
     * in this workspace, or the target holds no project membership here ·
     * <strong>409</strong> it would take the project's last administrator, or a lost row-lock
     * race (with {@code Retry-After}) · <strong>422</strong> a {@code roleId} that is
     * unknown, foreign or WORKSPACE-scoped.
     */
    @PatchMapping("/{projectId}/members/{userId}")
    public ProjectMemberResponse updateMember(@AuthenticationPrincipal User actor,
                                              @PathVariable UUID workspaceId,
                                              @PathVariable UUID projectId,
                                              @PathVariable UUID userId,
                                              @Valid @RequestBody UpdateProjectMemberRequest req) {
        return projectService.updateMember(actor, workspaceId, projectId, userId, req);
    }
    @DeleteMapping("/{projectId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal User actor,
                             @PathVariable UUID workspaceId,
                             @PathVariable UUID projectId,
                             @PathVariable UUID userId) {
        projectService.removeMember(actor, workspaceId, projectId, userId);
    }

    /**
     * <strong>This project's default access</strong> (HD-130, S7 §7.2 P1) — both links of the
     * §5.2 chain, the workspace's access mode, and which PROJECT roles this actor may make the
     * default (with the first missing permission for each one they may not).
     *
     * <p>Gate: <strong>{@code project.member.manage}</strong>, not {@code project.edit}. The
     * default role is membership authority rather than project settings and the two are
     * deliberately different grants — which is also why this is a separate endpoint instead of
     * a field on {@code PATCH /{projectId}}: folding a second-permission field into a
     * single-permission PATCH is how a gate gets forgotten.
     *
     * <p>200 · <strong>403</strong> · <strong>404</strong> unknown workspace, non-member, or a
     * project not in this workspace.
     */
    @GetMapping("/{projectId}/default-role")
    public ProjectDefaultRoleResponse defaultRole(@AuthenticationPrincipal User actor,
                                                  @PathVariable UUID workspaceId,
                                                  @PathVariable UUID projectId) {
        return projectService.getDefaultRole(actor, workspaceId, projectId);
    }

    /**
     * Set this project's default access (S7 §7.2 P2): exactly one of {@code {"roleId": …}} or
     * {@code {"inherit": true}}. {@code inherit} writes NULL — "follow the workspace default" —
     * which is a real choice and not an absence.
     *
     * <p>Gate: {@code project.member.manage}, plus the grant ceiling on <strong>both
     * ends</strong> against the actor's real effective set in this project, with
     * <strong>nobody exempt and no §4 escape</strong>: the escape rests on
     * {@code target != actor}, and a default's target is everyone including the actor, so the
     * actor who may promote a colleague to Project admin still gets a 403 naming
     * {@code issue.delete} when they aim that role at the default.
     *
     * <p>Answers the full {@code ProjectResponse}, so the People card re-renders from the write.
     *
     * <p>200 (including for the value already stored, which is written as a no-op) ·
     * <strong>403</strong> missing the permission, or a ceiling refusal naming it ·
     * <strong>404</strong> unknown workspace, non-member, or project not in this workspace ·
     * <strong>409 {@code STRANDED_BY_INHERITANCE}</strong> when the new default would leave this
     * project with nobody able to manage its membership — it had administrators only through
     * the default and no explicit administering row (door 9; no adoption retry, because
     * adopting would <em>narrow</em> whoever currently inherits the wider default) ·
     * <strong>422</strong> a {@code roleId} that is unknown, foreign or WORKSPACE-scoped, or a
     * body sending neither or both fields.
     */
    @PatchMapping("/{projectId}/default-role")
    public ProjectResponse setDefaultRole(@AuthenticationPrincipal User actor,
                                          @PathVariable UUID workspaceId,
                                          @PathVariable UUID projectId,
                                          @Valid @RequestBody UpdateProjectDefaultRoleRequest req) {
        return projectService.setDefaultRole(actor, workspaceId, projectId, req);
    }
}
