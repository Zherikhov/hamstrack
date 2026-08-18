package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.observability.ProductMetrics.RoleScopeViolationSource;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.dto.ProjectRef;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.project.service.ProjectAdminGuard;
import com.hamstrack.workspace.dto.ProjectAccessImpactResponse;
import com.hamstrack.workspace.dto.ProjectAccessResponse;
import com.hamstrack.workspace.dto.SettableRolesView;
import com.hamstrack.workspace.dto.UpdateWorkspaceRequest;
import com.hamstrack.workspace.dto.WorkspaceResponse;
import com.hamstrack.workspace.entity.ProjectAccessMode;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.exception.ReactivatedProjectDefaultsException;
import com.hamstrack.workspace.exception.RoleSelectionException;
import com.hamstrack.workspace.exception.WorkspaceGrantCeilingException;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * <strong>The workspace's project-access policy</strong> (HD-130, S7 §7.1): the
 * {@code OPEN}/{@code STRICT} switch, the workspace-level default project role, and the impact
 * preview that has to be honest about what flipping either would do.
 *
 * <p>It is a separate service from {@code WorkspaceService} because it is the <em>one</em>
 * place those two columns are written, and {@code WorkspaceService} is already the
 * create/invite/accept surface. The project-level twin lives in {@code ProjectService}, where
 * the {@code ProjectContext} and the project ceiling already are.
 *
 * <h2>What the mode changes, and what it does not</h2>
 *
 * <p>Exactly one thing: whether a workspace member with no explicit {@code project_members}
 * row inherits the project's declared default (§5.2). There is <strong>no enforcement-off
 * mode</strong> and this slice does not create one — permissions always mean what they say, in
 * both modes. {@code STRICT} narrows <em>writes</em> only; every member can still see every
 * project, because narrowing reads would be private projects by the back door.
 *
 * <p>The enforcement branch itself is {@code WorkspaceAccessService.defaultProjectRole}'s and
 * is not touched here. Nothing in this class asks {@code if (mode == STRICT)}: a second
 * authorization path is what this epic exists to remove, and it is the same rule that forbids
 * forking DC from Cloud.
 *
 * <h2>Guards, and the order they run in</h2>
 *
 * <ol>
 *   <li>an empty body is a <strong>400</strong>, and naming the default role in both accepted
 *       ways is a <strong>422</strong> — refused rather than resolved by precedence;</li>
 *   <li>{@code requireMember} → <strong>404</strong> for an unknown workspace <em>and</em> for
 *       a non-member, then {@code workspace.edit} → <strong>403</strong>. A 403 is therefore
 *       only ever reachable by a proven member;</li>
 *   <li>{@code RoleCatalog.requireAssignable(PROJECT, wsId, roleId)} → <strong>422</strong> for
 *       unknown, foreign or WORKSPACE-scoped, <em>before</em> anything is read about the
 *       workspace's state. PROJECT scope is not negotiable: a WORKSPACE role accepted here
 *       would put {@code workspace.edit} into every member's {@code ProjectContext} in every
 *       project of the workspace — the wrong-scope escalation with the widest blast radius in
 *       the product;</li>
 *   <li>the grant ceiling, on <strong>both ends</strong> and with the built-in Owner exempt →
 *       <strong>403</strong> naming the missing permission ({@link SettableRoles}) — plus, on
 *       a flip <em>to</em> {@code OPEN}, on every declared default this write re-activates
 *       even though the body names none of them
 *       ({@link #requireReactivatedDefaultsAreSettable});</li>
 *   <li>stranding doors 7 and 8 → <strong>409 {@code STRANDED_BY_INHERITANCE}</strong>;</li>
 *   <li>no-op detection: a request whose every value already holds returns 200 without
 *       writing, so "setting OPEN on an OPEN workspace changes nothing" is true at the row
 *       level and not merely at the permission level;</li>
 *   <li>mutate and save last (the {@code @Version} ordering rule — {@code workspaces} has no
 *       version column and S7 does not add one, but the habit is what keeps the rule true when
 *       one is);</li>
 *   <li>one structured INFO line, ids only, Loki-safe. There is no audit table.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectAccessService {

    private final WorkspaceAccessService workspaceAccess;
    private final WorkspaceRepository workspaceRepository;
    private final RoleCatalog roleCatalog;
    private final SettableRoles settableRoles;
    private final ProjectAccessImpact impact;
    private final ProjectAdminGuard projectAdminGuard;
    /**
     * <strong>For {@link #requireReactivatedDefaultsAreSettable} only.</strong> This class
     * writes one row and reads none — the impact preview is
     * {@link ProjectAccessImpact}'s job and stays there — but a flip to {@code OPEN} makes
     * every per-project declared default live, and a ceiling has to see the values it is about
     * to activate. Do not grow a second use: counting anything here is how this service and the
     * preview start answering differently.
     */
    private final ProjectRepository projectRepository;

    // ------------------------------------------------------------------ W1: read

    /**
     * <strong>W1</strong> — {@code GET /api/workspaces/{ws}/project-access}. One request behind
     * the whole General page: mode, declared default, the ceiling rendered, and the impact of
     * the state as it stands.
     */
    @Transactional(readOnly = true)
    public ProjectAccessResponse get(User actor, UUID workspaceId) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        ctx.permissions().require(Permission.WORKSPACE_EDIT);
        var workspace = ctx.workspace();
        return new ProjectAccessResponse(
                workspace.getProjectAccessMode(),
                workspace.getDefaultProjectRoleId(),
                settable(ctx),
                impact.of(workspace, ProjectAccessProposal.current(workspace)));
    }

    // ------------------------------------------------------------------ W2: preview

    /**
     * <strong>W2</strong> — {@code POST /api/workspaces/{ws}/project-access/preview}. The same
     * body as the write, running the same guards 1–5 and returning the impact
     * <strong>instead of</strong> applying it. Persists nothing.
     *
     * <p>POST because it carries a body and changes nothing — precisely
     * {@code POST /roles/preview}'s idiom.
     *
     * <p><strong>A ceiling failure surfaces as the ordinary 403</strong>, never as a "would
     * fail" field in a 200 body: a preview that succeeds while describing a refusal teaches a
     * client to ignore it.
     */
    @Transactional(readOnly = true)
    public ProjectAccessImpactResponse preview(User actor, UUID workspaceId,
                                               UpdateWorkspaceRequest req) {
        requireWellFormed(req);
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        ctx.permissions().require(Permission.WORKSPACE_EDIT);
        var workspace = ctx.workspace();
        var proposal = proposalFrom(ctx, workspace, req);
        // Doors 7–8 are evaluated INSIDE ProjectAccessImpact.of, so the preview's
        // strandedProjects is the very list the write would 409 with (AC 27).
        return impact.of(workspace, proposal);
    }

    // ------------------------------------------------------------------ W3: write

    /**
     * <strong>W3</strong> — {@code PATCH /api/workspaces/{ws}}. Name, access mode and default
     * project role, each optional and each an independent decision.
     */
    @Transactional
    public WorkspaceResponse update(User actor, UUID workspaceId, UpdateWorkspaceRequest req) {
        requireWellFormed(req);
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        ctx.permissions().require(Permission.WORKSPACE_EDIT);
        var workspace = ctx.workspace();

        // ---- reads and refusals first; every mutation is at the bottom ----
        var proposal = proposalFrom(ctx, workspace, req);

        // Doors 7 and 8. Re-derived here whether or not the caller ever previewed — the
        // counts are advisory, this refusal is not (§4.3).
        var stranded = projectAdminGuard.projectsLosingLastAdministrator(workspace, proposal);
        if (!stranded.isEmpty()) {
            var strictening = proposal.mode() != workspace.getProjectAccessMode();
            projectAdminGuard.requireNoInheritedAdministratorsLost(stranded,
                    strictening
                            ? "Restricting project access"
                            : "Making “" + defaultRoleName(workspace, proposal) + "” the default",
                    strictening
                            ? "Add an explicit administrator to each first — the current default still lets you."
                            : "Choose a default that also manages members, or add an explicit "
                              + "administrator to each project first.");
        }

        var modeBefore = workspace.getProjectAccessMode();
        var defaultBefore = workspace.getDefaultProjectRoleId();
        boolean renames = req.name() != null && !req.name().equals(workspace.getName());
        boolean changesMode = req.projectAccessMode() != null && req.projectAccessMode() != modeBefore;
        boolean changesDefault = req.touchesDefaultProjectRole()
                && !Objects.equals(proposal.workspaceDefaultRoleId(), defaultBefore);
        if (!renames && !changesMode && !changesDefault) {
            // Nothing to write. Returning without a save keeps `updated_at` still, so
            // "setting OPEN on an OPEN workspace changes nothing" is true at the row level.
            return WorkspaceResponse.of(workspace, ctx.workspaceRole());
        }

        // ---- mutation, immediately before the save ----
        if (renames) {
            workspace.setName(req.name());
        }
        if (changesMode) {
            workspace.setProjectAccessMode(req.projectAccessMode());
        }
        if (changesDefault) {
            workspace.setDefaultProjectRoleId(proposal.workspaceDefaultRoleId());
        }
        workspaceRepository.save(workspace);

        // The only record this leaves: ids only, no names or emails, so the line is safe to
        // ship to Loki. There is no audit table (epic §19 OQ 7).
        log.info("workspace.project_access_changed workspace={} actor={} mode={}->{} "
                 + "defaultRole={}->{} strandedChecked={}",
                workspace.getId(), actor.getId(), modeBefore, workspace.getProjectAccessMode(),
                defaultBefore, workspace.getDefaultProjectRoleId(), stranded.size());
        return WorkspaceResponse.of(workspace, ctx.workspaceRole());
    }

    // ------------------------------------------------------------------ internals

    /**
     * Rule 1 (§7.1). A body that asks for nothing is a 400 rather than a silent 200, and a body
     * that names the default project role in <em>both</em> accepted ways is a 422 — the two
     * fields do not mean the same thing, so a silent winner would store a value the caller did
     * not ask for, in the one part of the product where that is a privilege change.
     *
     * <p><strong>INVARIANT: nothing in here may read workspace state.</strong> It runs
     * <em>before</em> {@code requireMember}, so every branch it takes is observable to a
     * caller who has not been proved a member — which is fine only while every branch is a
     * function of the request record alone, as it is today. The moment one of them consults
     * the workspace (its mode, its default, whether it exists), the pair 400/422-vs-404 becomes
     * an oracle for that state, and the product's one non-negotiable rule — non-existence and
     * non-membership are the same 404 — is broken from the cheapest possible position. If a
     * check ever needs the workspace, move it below {@code requireMember}; do not "just this
     * once" read it here (tenancy review, round 2).
     */
    private void requireWellFormed(UpdateWorkspaceRequest req) {
        if (req.namesDefaultProjectRoleAmbiguously()) {
            // Named with THIS body's fields: the no-argument factory belongs to the membership
            // doors and talks about `roleId` and `role`, neither of which exists here.
            throw RoleSelectionException.ambiguous("defaultProjectRoleId",
                    "clearDefaultProjectRole",
                    "one names the role every member inherits and the other removes it");
        }
        if (req.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Name at least one of name, projectAccessMode, defaultProjectRoleId or "
                    + "clearDefaultProjectRole");
        }
    }

    /**
     * Guards 3 and 4, then the post-state as a value.
     *
     * <p><strong>The ceiling is applied to both ends, and at workspace scope the "current" end
     * is not vacuous</strong> (§3.1), because the comparand deliberately does not include the
     * current default: an Admin may not <em>replace</em> a default that is itself outside the
     * baseline. So an Owner who sets the workspace default to Team lead cannot have it silently
     * narrowed back by an Admin — the same "whatever you may not grant, you may not strip" rule
     * {@code RoleService.requireWithinDefinitionCeiling} applies to role contents and
     * {@code WorkspaceMemberService.updateRole} applies to both ends of a membership change.
     *
     * <p>Skipped when the request does not touch the default <strong>and does not make one
     * live</strong>: a pure rename, or a flip that leaves inheritance where it was, must not
     * 403 because of a value it is not changing. A flip <em>to</em> {@code OPEN} is not that
     * request — see {@link #requireReactivatedDefaultsAreSettable}.
     */
    private ProjectAccessProposal proposalFrom(WorkspaceContext ctx, Workspace workspace,
                                               UpdateWorkspaceRequest req) {
        var mode = req.projectAccessMode() != null
                ? req.projectAccessMode()
                : workspace.getProjectAccessMode();
        // Does this write turn inheritance back on? Every declared default in the workspace
        // then stops being inert, which is a grant even though the body names no role.
        boolean goesLive = mode == ProjectAccessMode.OPEN
                           && workspace.getProjectAccessMode() != ProjectAccessMode.OPEN;
        boolean exempt = settableRoles.ownerExempt(ctx.workspaceRole());
        var comparand = exempt ? null : settableRoles.workspaceComparand(ctx.permissions());

        if (!req.touchesDefaultProjectRole()) {
            if (goesLive && !exempt) {
                // The workspace default included: the body does not name it, but this write is
                // what makes it apply to everybody again.
                requireWithinCeiling(comparand,
                        workspaceAccess.declaredDefaultProjectRole(workspace));
                requireReactivatedDefaultsAreSettable(workspace, comparand);
            }
            return ProjectAccessProposal.workspace(mode, workspace.getDefaultProjectRoleId());
        }
        // 422 for unknown / foreign / WORKSPACE-scoped, before anything is judged about it.
        var requested = req.defaultProjectRoleId() == null
                ? null
                : roleCatalog.requireAssignable(
                        RoleScope.PROJECT, workspace.getId(), req.defaultProjectRoleId());

        if (!exempt) {
            // …the role being handed to every member without an explicit row…
            if (requested != null) {
                requireWithinCeiling(comparand, requested);
            }
            // …and the one being replaced. `declaredDefaultProjectRole` and NOT
            // `defaultProjectRole`: the declared default is stored in both modes and goes live
            // again the moment somebody flips back, so a ceiling that only bit while OPEN would
            // be a ceiling you step over by flipping twice (§2).
            requireWithinCeiling(comparand, workspaceAccess.declaredDefaultProjectRole(workspace));
            if (goesLive) {
                // The per-project overrides go live too, and this body names none of them.
                requireReactivatedDefaultsAreSettable(workspace, comparand);
            }
        }
        return ProjectAccessProposal.workspace(mode, requested == null ? null : requested.id());
    }

    /**
     * <strong>{@code STRICT → OPEN} is a grant, and the body does not have to name a role for
     * it to be one</strong> (HD-130 S7, security review round 2 — the High).
     *
     * <p>{@link #proposalFrom} skipped the ceiling entirely for a request that did not touch
     * the default, on the reasonable-sounding ground that "a pure mode flip must not 403
     * because of a value it is not changing". But a flip to {@code OPEN} changes that value's
     * <em>effect</em>: an inert declared default becomes the effective role of every member
     * with no explicit {@code project_members} row, in every project that does not override it,
     * and every per-project override becomes live in its own project. So an actor holding only
     * {@code workspace.edit}, refused
     * {@code PATCH {"defaultProjectRoleId": <Project admin>}} by the ceiling, got the identical
     * outcome from {@code PATCH {"projectAccessMode":"OPEN"}} — one field, no role id, no
     * refusal.
     *
     * <p><strong>Doors 7 and 8 do not cover this and are not supposed to.</strong> They read
     * the <em>current</em> mode when deciding which projects have inherited administrators, so
     * a flip that turns inheritance on produces no candidates at all — correct for stranding
     * (turning inheritance on can never strand anybody) and no kind of grant guard.
     *
     * <p><strong>The refusal is satisfiable — but not always by its reader, and the message has
     * to say which.</strong> Two different stored values can block this flip and their remedies
     * are not the same:
     * <ul>
     *   <li>the <strong>workspace</strong> default (bounded in {@link #proposalFrom}) blocks it
     *       only when it is already outside the actor's baseline, and the ceiling applies to
     *       both ends — so the same actor cannot narrow it either. The way out is an Owner, who
     *       is exempt here as everywhere;</li>
     *   <li>a <strong>per-project</strong> override (bounded here) is worse: clearing one needs
     *       {@code project.member.manage} <em>in that project</em>, deliberately outside
     *       {@link Permission#projectCuration()}, so no workspace-scoped role grants it. It is
     *       therefore refused with {@link ReactivatedProjectDefaultsException}, which names the
     *       offending <em>projects</em> as an extension — a generic ceiling 403 names a role
     *       and leaves the reader hunting through every project's settings for it — and states
     *       all three actions that can actually clear it.</li>
     * </ul>
     * The rule both follow is the codebase's own, written down three times in
     * {@code StrandedProjectsException}: a refusal may only prescribe an action its reader can
     * perform.
     *
     * <p>Cost: one read of the workspace's live projects, on a write that happens when somebody
     * clicks a switch, and only in the {@code STRICT → OPEN} direction for a non-Owner. It is
     * the same list the impact preview loads; it is not shared with it because this refusal has
     * to run before anything is counted, and a 403 must not depend on how many projects there
     * are.
     *
     * <p>Archived projects are excluded, for doors 7–9's reason: they are frozen and there is
     * nothing to inherit in them. A stored default whose id fails the scope+ownership assertion
     * is skipped rather than refused — {@code resolveRoleOrDegrade} logs and counts it.
     *
     * <p><strong>Why that skip is safe, precisely.</strong> Not because a degraded default
     * grants nothing: it grants the <strong>built-in Contributor</strong>
     * ({@code WorkspaceAccessService.fallback} returns {@code roleCatalog.defaultProjectRole()}
     * on exactly that branch, which is what {@link #defaultRoleName} renders). It is safe
     * because the degrade target is <em>inside the comparand by construction</em>:
     * {@link SettableRoles#workspaceComparand} <em>is</em>
     * {@code effectiveProjectPermissions(actorWorkspacePermissions, builtInContributor)}, and
     * {@code effectiveProjectPermissions} only ever unions onto that role's own set. So there
     * is nothing left to bound — skipping is the same answer the ceiling would give.
     *
     * <p>That is an invariant across two classes and neither expresses it, so it is pinned by
     * {@code ComparandCoversDegradeTargetTest}: either half changing alone — a degrade fallback
     * that resolved to the workspace default instead, or a comparand that stopped starting from
     * Contributor — turns this skip into a silent bypass with no other failing test.
     */
    private void requireReactivatedDefaultsAreSettable(Workspace workspace, PermissionSet comparand) {
        // Grouped rather than deduplicated: the refusal names the projects, so the same role in
        // three projects has to produce three names, not one judgement and two discarded rows.
        // A null declared default means "inherit the workspace default", which the caller has
        // already bounded.
        var byRole = new LinkedHashMap<UUID, List<ProjectRef>>();
        for (var project : projectRepository.findAllByWorkspaceAndArchivedAtIsNull(workspace)) {
            var declared = project.getDefaultProjectRoleId();
            if (declared != null) {
                byRole.computeIfAbsent(declared, id -> new ArrayList<>())
                        .add(ProjectRef.of(project));
            }
        }
        for (var entry : byRole.entrySet()) {
            var role = workspaceAccess.resolveRoleOrDegrade(entry.getKey(), RoleScope.PROJECT,
                    workspace.getId(), RoleScopeViolationSource.DEFAULT_PROJECT_ROLE);
            if (role.isEmpty()) {
                continue;
            }
            // One role at a time, with exactly the projects that declare it: a body naming one
            // role while listing another role's projects would be the same "states what it did
            // not test" defect this refusal was split out to fix. A second offending role
            // surfaces on the retry, as its own coherent refusal.
            var missing = comparand.firstNotCovered(role.get().permissions());
            if (missing.isPresent()) {
                throw new ReactivatedProjectDefaultsException(
                        entry.getValue(), role.get().name(), missing.get());
            }
        }
    }

    private void requireWithinCeiling(PermissionSet comparand, RoleView role) {
        comparand.firstNotCovered(role.permissions()).ifPresent(missing -> {
            throw new WorkspaceGrantCeilingException(role.name(), missing);
        });
    }

    /**
     * The name of the role door 8 is about to make the default — for the 409's sentence only.
     *
     * <p><strong>Resolved, not read.</strong> The id here is <em>usually</em> one this request
     * just put through {@code requireAssignable}, which would make {@code roleCatalog.view(id)
     * .name()} correct — but not always: when the body does not touch the default, the
     * proposal carries the id already stored on the row, which nothing in this request has
     * checked. {@code ProjectDefaultRoleResponse}'s javadoc forbids that pattern in as many
     * words, and "correct today, by an argument three classes deep" is exactly the shape that
     * stops being correct without anybody noticing. So it goes through the degrade path like
     * every other rendered role name: a value that fails the scope+ownership assertion is
     * logged, counted and rendered as the built-in Contributor's name — the role that actually
     * applies once a corrupt id degrades — rather than published.
     */
    private String defaultRoleName(Workspace workspace, ProjectAccessProposal after) {
        var fallback = roleCatalog.defaultProjectRole().name();
        if (after.workspaceDefaultRoleId() == null) {
            return fallback;
        }
        return workspaceAccess.resolveRoleOrDegrade(after.workspaceDefaultRoleId(),
                        RoleScope.PROJECT, workspace.getId(),
                        RoleScopeViolationSource.DEFAULT_PROJECT_ROLE)
                .map(RoleView::name)
                .orElse(fallback);
    }

    /** The ceiling, rendered for the picker — same predicate, same comparand, same strings. */
    private SettableRolesView settable(WorkspaceContext ctx) {
        return settableRoles.projectRoles(ctx.workspace().getId(),
                settableRoles.workspaceComparand(ctx.permissions()),
                settableRoles.ownerExempt(ctx.workspaceRole()));
    }
}
