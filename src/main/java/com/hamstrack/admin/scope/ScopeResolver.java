package com.hamstrack.admin.scope;

import com.hamstrack.auth.entity.User;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.project.exception.InsufficientProjectRoleException;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.entity.WorkspaceRole;
import com.hamstrack.workspace.exception.InsufficientWorkspaceRoleException;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves and authorizes a <em>delegated</em> admin scope for the current
 * actor. The global scope is guarded by {@code hasRole("ADMIN")} in
 * SecurityConfig; the workspace and project scopes are membership-based and
 * enforced here (the {@code /api/workspaces/**} endpoints are only
 * {@code authenticated()} at the filter level).
 *
 * <p>Tenancy rule: non-membership and non-existence both return 404 (never
 * reveal a resource exists across tenants); being a member <em>without</em> the
 * required role — where the caller already knows the resource exists — returns
 * 403.
 */
@Service
@RequiredArgsConstructor
public class ScopeResolver {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    /** Workspace whose config the actor may administer (OWNER or ADMIN). */
    @Transactional(readOnly = true)
    public Workspace requireWorkspaceAdmin(User actor, UUID workspaceId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        var member = workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        if (!member.getRole().isAtLeast(WorkspaceRole.ADMIN)) {
            throw new InsufficientWorkspaceRoleException();
        }
        return workspace;
    }

    /**
     * Project whose config the actor may administer (MANAGER). Requires the
     * actor to be a member of the enclosing workspace as well, so a project id
     * from another tenant is never resolvable.
     */
    @Transactional(readOnly = true)
    public Project requireProjectAdmin(User actor, UUID workspaceId, UUID projectId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        if (!workspaceMemberRepository.existsByWorkspaceAndUser(workspace, actor)) {
            throw new WorkspaceNotFoundException();
        }
        var project = projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
        var member = projectMemberRepository.findByProjectAndUser(project, actor)
                .orElseThrow(ProjectNotFoundException::new);
        if (!member.getRole().isAtLeast(ProjectRole.MANAGER)) {
            throw new InsufficientProjectRoleException();
        }
        return project;
    }
}
