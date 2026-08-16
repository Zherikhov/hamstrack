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
     * Rename / re-describe / switch the board mode.
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
     * own settings — now including {@code boardMode}, which changes how the board and
     * the backlog render — must not stay quietly writable. {@code unarchive} is the way
     * back, and it is deliberately still MANAGER-only.
     */
    @Transactional
    public ProjectResponse update(User actor, UUID workspaceId, UUID projectId, UpdateProjectRequest req) {
        var project = scopeResolver.requireProjectCurator(actor, workspaceId, projectId);
        requireNotArchived(project);
        if (req.name() != null) project.setName(req.name());
        if (req.description() != null) project.setDescription(req.description());
        if (req.boardMode() != null) project.setBoardMode(req.boardMode());
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
