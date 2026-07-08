package com.hamstrack.project.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.project.dto.*;
import com.hamstrack.project.entity.*;
import com.hamstrack.project.exception.*;
import com.hamstrack.project.repository.*;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;

    @Transactional
    public ProjectResponse create(User actor, UUID workspaceId, CreateProjectRequest req) {
        var workspace = resolveWorkspace(actor, workspaceId);
        if (projectRepository.existsByWorkspaceAndKey(workspace, req.key())) {
            throw new ProjectKeyConflictException();
        }
        var project = new Project();
        project.setWorkspace(workspace);
        project.setName(req.name());
        project.setKey(req.key().toUpperCase());
        project.setDescription(req.description());
        project.setCreatedBy(actor);
        projectRepository.save(project);

        var member = new ProjectMember();
        member.setProject(project);
        member.setUser(actor);
        member.setRole(ProjectRole.MANAGER);
        projectMemberRepository.save(member);

        return ProjectResponse.of(project, ProjectRole.MANAGER);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(User actor, UUID workspaceId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        return projectRepository.findAllByWorkspace(workspace).stream()
                .map(p -> {
                    var role = projectMemberRepository.findByProjectAndUser(p, actor)
                            .map(ProjectMember::getRole)
                            .orElse(ProjectRole.VIEWER);
                    return ProjectResponse.of(p, role);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(User actor, UUID workspaceId, UUID projectId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = resolveProject(workspace, projectId);
        var role = getRole(actor, project);
        return ProjectResponse.of(project, role);
    }

    @Transactional
    public ProjectResponse update(User actor, UUID workspaceId, UUID projectId, UpdateProjectRequest req) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = resolveProject(workspace, projectId);
        requireRole(actor, project, ProjectRole.MANAGER);
        if (req.name() != null) project.setName(req.name());
        if (req.description() != null) project.setDescription(req.description());
        projectRepository.save(project);
        return ProjectResponse.of(project, ProjectRole.MANAGER);
    }

    @Transactional
    public void archive(User actor, UUID workspaceId, UUID projectId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = resolveProject(workspace, projectId);
        requireRole(actor, project, ProjectRole.MANAGER);
        project.setArchivedAt(Instant.now());
        projectRepository.save(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> listMembers(User actor, UUID workspaceId, UUID projectId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = resolveProject(workspace, projectId);
        requireRole(actor, project, ProjectRole.VIEWER);
        return projectMemberRepository.findAllByProject(project).stream()
                .map(ProjectMemberResponse::of)
                .toList();
    }

    @Transactional
    public ProjectMemberResponse addMember(User actor, UUID workspaceId, UUID projectId, AddProjectMemberRequest req) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = resolveProject(workspace, projectId);
        requireRole(actor, project, ProjectRole.MANAGER);
        var user = userRepository.findById(req.userId())
                .orElseThrow(() -> new ProjectNotFoundException());
        if (projectMemberRepository.existsByProjectAndUser(project, user)) {
            throw new ProjectKeyConflictException();
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
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        return workspace;
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
}
