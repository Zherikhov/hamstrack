package com.hamstrack.project.service;

import com.hamstrack.admin.scope.ScopeResolver;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.exception.UserNotFoundException;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.project.dto.*;
import com.hamstrack.project.entity.*;
import com.hamstrack.project.exception.*;
import com.hamstrack.project.repository.*;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final WorkspaceAccessService workspaceAccess;
    private final ScopeResolver scopeResolver;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final ProductMetrics metrics;

    @Transactional
    public ProjectResponse create(User actor, UUID workspaceId, CreateProjectRequest req) {
        var workspace = resolveWorkspace(actor, workspaceId);
        // Normalize once and use the same value for the uniqueness check and the
        // insert — otherwise a future relaxed key pattern could 500 on the unique
        // constraint instead of returning a clean 409.
        var key = req.key().toUpperCase();
        if (projectRepository.existsByWorkspaceAndKey(workspace, key)) {
            throw new ProjectKeyConflictException();
        }
        var project = new Project();
        project.setWorkspace(workspace);
        project.setName(req.name());
        project.setKey(key);
        project.setDescription(req.description());
        project.setCreatedBy(actor);
        // HD-102: the creation picker's answer. Omitted → the entity's own lean
        // defaults (KANBAN, releases off, estimation off — §7 / open question 2).
        applyDelivery(project, null, req.delivery());
        projectRepository.save(project);

        var member = new ProjectMember();
        member.setProject(project);
        member.setUser(actor);
        member.setRole(ProjectRole.MANAGER);
        projectMemberRepository.save(member);
        metrics.projectCreated();

        return ProjectResponse.of(project, ProjectRole.MANAGER);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(User actor, UUID workspaceId, boolean includeArchived) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var projects = includeArchived
                ? projectRepository.findAllByWorkspace(workspace)
                : projectRepository.findAllByWorkspaceAndArchivedAtIsNull(workspace);
        // One membership query for all projects instead of one per project
        var roleByProjectId = projectMemberRepository.findAllByUserAndProjectIn(actor, projects).stream()
                .collect(Collectors.toMap(m -> m.getProject().getId(), ProjectMember::getRole));
        return projects.stream()
                .map(p -> ProjectResponse.of(p, roleByProjectId.getOrDefault(p.getId(), ProjectRole.VIEWER)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(User actor, UUID workspaceId, UUID projectId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = resolveProject(workspace, projectId);
        var role = getRole(actor, project);
        return ProjectResponse.of(project, role);
    }

    /**
     * Rename / re-describe / change the delivery capabilities.
     *
     * <p><strong>Deliberate, flagged permission change (HD-22 §3.2):</strong> this used
     * to be {@code requireRole(MANAGER)}. It is now
     * {@link ScopeResolver#requireProjectCurator} — project MANAGER <em>or</em>
     * workspace OWNER/ADMIN — because {@code boardMode} joined this PATCH and the SPA's
     * {@code ProjectSettingsArea} has always admitted exactly the curator predicate, so
     * a workspace admin could reach the form and then 403 on save. It also aligns this
     * endpoint with every other project-content write since HD-6 (components, versions,
     * and now sprints), all of which are curator-gated.
     *
     * <p>The widening is scoped on purpose: {@code archive}/{@code unarchive} and member
     * management stay MANAGER-only. What it grants a workspace OWNER/ADMIN who is not a
     * project member is the ability to rename a project in their own workspace — which
     * they can already do indirectly through the admin console's project bindings.
     * Tenancy is unchanged: {@code requireProjectCurator} resolves through workspace
     * membership first, so a missing workspace, a missing project and a non-member all
     * still yield 404, never 403.
     *
     * <p><strong>Archived projects are frozen</strong> (security review L5): every issue
     * edit, sprint mutation and rank move already 409s on an archived project, so its
     * own settings — now including the delivery capabilities, which change how the
     * board, the backlog, the rail and the issue detail render — must not stay quietly
     * writable. {@code unarchive} is the way back, and it is deliberately still
     * MANAGER-only.
     *
     * <p><strong>HD-102:</strong> the capabilities arrive in {@code delivery}, with the
     * deprecated top-level {@code boardMode} still accepted (and reconciled — see
     * {@link #applyDelivery}). Nothing else in the codebase reads them, so this method
     * is the <em>only</em> place a capability is ever written and there is no
     * capability-conditional behaviour anywhere downstream (Rule A, §5.1).
     */
    @Transactional
    @SuppressWarnings("deprecation") // reads the legacy boardMode mirror on purpose
    public ProjectResponse update(User actor, UUID workspaceId, UUID projectId, UpdateProjectRequest req) {
        var project = scopeResolver.requireProjectCurator(actor, workspaceId, projectId);
        requireNotArchived(project);
        if (req.name() != null) project.setName(req.name());
        if (req.description() != null) project.setDescription(req.description());
        applyDelivery(project, req.boardMode(), req.delivery());
        projectRepository.save(project);
        // The caller's REAL project role, not a hardcoded MANAGER: a workspace
        // OWNER/ADMIN who is not a project member now reaches this method, and echoing
        // MANAGER back would make the SPA render project-manager-only actions for them.
        return ProjectResponse.of(project, getRole(actor, project));
    }

    @Transactional
    public void archive(User actor, UUID workspaceId, UUID projectId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = resolveProject(workspace, projectId);
        requireRole(actor, project, ProjectRole.MANAGER);
        project.setArchivedAt(Instant.now());
        projectRepository.save(project);
    }

    @Transactional
    public void unarchive(User actor, UUID workspaceId, UUID projectId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = resolveProject(workspace, projectId);
        requireRole(actor, project, ProjectRole.MANAGER);
        project.setArchivedAt(null);
        projectRepository.save(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> listMembers(User actor, UUID workspaceId, UUID projectId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = resolveProject(workspace, projectId);
        requireRole(actor, project, ProjectRole.VIEWER);
        return projectMemberRepository.findAllByProjectWithUser(project).stream()
                .map(ProjectMemberResponse::of)
                .toList();
    }

    @Transactional
    public ProjectMemberResponse addMember(User actor, UUID workspaceId, UUID projectId, AddProjectMemberRequest req) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = resolveProject(workspace, projectId);
        requireRole(actor, project, ProjectRole.MANAGER);
        // Only workspace members can join a project — a bare findById would expose
        // any user's email/name across tenants via the response
        var user = userRepository.findById(req.userId())
                .filter(u -> workspaceMemberRepository.existsByWorkspaceAndUser(workspace, u))
                .orElseThrow(UserNotFoundException::new);
        if (projectMemberRepository.existsByProjectAndUser(project, user)) {
            throw new AlreadyProjectMemberException();
        }
        var member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(req.role());
        projectMemberRepository.save(member);
        return ProjectMemberResponse.of(member);
    }

    @Transactional
    public void removeMember(User actor, UUID workspaceId, UUID projectId, UUID userId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = resolveProject(workspace, projectId);
        requireRole(actor, project, ProjectRole.MANAGER);
        var user = userRepository.findById(userId)
                .orElseThrow(ProjectNotFoundException::new);
        var member = projectMemberRepository.findByProjectAndUser(project, user)
                .orElseThrow(ProjectNotFoundException::new);
        projectMemberRepository.delete(member);
    }

    // ---- helpers ----

    private Workspace resolveWorkspace(User actor, UUID workspaceId) {
        return workspaceAccess.requireMember(actor, workspaceId).workspace();
    }

    private Project resolveProject(Workspace workspace, UUID projectId) {
        return projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
    }

    private ProjectRole getRole(User actor, Project project) {
        return projectMemberRepository.findByProjectAndUser(project, actor)
                .map(ProjectMember::getRole)
                .orElse(ProjectRole.VIEWER);
    }

    private void requireRole(User actor, Project project, ProjectRole required) {
        var role = getRole(actor, project);
        if (!role.isAtLeast(required)) {
            throw new InsufficientProjectRoleException();
        }
    }

    /**
     * The <strong>only</strong> place a delivery capability is written (HD-102 §11.3),
     * shared by create (where {@code legacyBoardMode} is always null) and update.
     *
     * <p>Three rules, in order:
     * <ol>
     *   <li><strong>{@code preset} is derived, never settable</strong> (open question
     *       5) — a request carrying it is a <strong>400</strong> naming the field, not
     *       a silent ignore. The label is computed by {@code DeliveryPreset.of} from
     *       the capabilities, so accepting it would create a second source of truth
     *       that could disagree with the first.</li>
     *   <li><strong>The deprecated top-level {@code boardMode} and
     *       {@code delivery.board} must agree.</strong> Both present and equal → fine
     *       (an SPA mid-migration may well send both). Both present and different →
     *       <strong>400</strong>: picking a winner would silently discard half of what
     *       an out-of-date client asked for.</li>
     *   <li>Every member is <strong>partial</strong>: null leaves the capability alone,
     *       so a PATCH that only flips {@code releases} cannot disturb the board mode.
     *       On create, "alone" means the entity's lean field defaults.</li>
     * </ol>
     *
     * <p><strong>Rule A (§5.1) lives here, by omission:</strong> this method only ever
     * writes three columns on {@code projects}. No repository query, no other service
     * and no controller reads {@code releasesEnabled}/{@code estimationEnabled}/
     * {@code boardMode} to decide whether to accept a request, so no status code
     * anywhere can depend on a capability. Turning one off is a pure presentation
     * change: version, sprint and story-point data is untouched and every endpoint
     * that writes it keeps working identically (§13's non-destructive invariant).
     */
    private void applyDelivery(Project project, BoardMode legacyBoardMode, DeliveryRequest delivery) {
        if (delivery != null && delivery.preset() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "delivery.preset is derived from board/releases/estimation and cannot be set");
        }
        var board = delivery != null ? delivery.board() : null;
        if (board != null && legacyBoardMode != null && board != legacyBoardMode) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "boardMode and delivery.board disagree — send only one");
        }
        if (board == null) board = legacyBoardMode;
        if (board != null) project.setBoardMode(board);
        if (delivery != null) {
            if (delivery.releases() != null) project.setReleasesEnabled(delivery.releases());
            if (delivery.estimation() != null) project.setEstimationEnabled(delivery.estimation());
        }
    }

    /**
     * An archived project's content is frozen (issues, sprints, ranks) and so are its
     * settings — same 409 and same wording as {@code IssueService}/{@code SprintService},
     * so the SPA renders one message for the whole class. Reads still work.
     */
    private void requireNotArchived(Project project) {
        if (project.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is archived");
        }
    }
}
