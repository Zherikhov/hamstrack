package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.exception.IssueNotFoundException;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectRepository;
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
 * The single tenancy chokepoint (HD-82). Encapsulates the resolve-workspace →
 * verify-membership → <strong>404-not-403</strong> dance that was copy-pasted across
 * ~12 services, so a reviewer can audit the project's top bug class (cross-tenant
 * leaks) in one place.
 *
 * <p><strong>Invariant (byte-identical to every former inline copy):</strong> a
 * non-existent workspace AND a workspace the caller isn't a member of both throw
 * {@link WorkspaceNotFoundException} (HTTP 404) — never a 403, which would disclose
 * existence across tenants. The project/issue compositions add
 * {@link ProjectNotFoundException} / {@link IssueNotFoundException} on top,
 * matching the former inline resolvers exactly.
 *
 * <p>Every method is {@code @Transactional(readOnly = true)} and participates in the
 * caller's transaction via {@code REQUIRED} propagation (no new tx boundary), so the
 * returned entities stay managed for the caller and no extra flush is introduced that
 * could bump an {@code @Version} — matching the previous inline behavior.
 *
 * <p><strong>Not folded in here:</strong> the admin delegated-scope
 * {@code ScopeResolver}, which deliberately returns <em>403</em> for a member without
 * the required role (a different rule); it may delegate its resolve+membership step to
 * {@link #requireMember} but keeps its own 403 semantics.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceAccessService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final IssueRepository issueRepository;

    /**
     * Resolve the workspace and verify the actor's membership. Throws
     * {@link WorkspaceNotFoundException} (404) for both a non-existent workspace and a
     * non-member — identical to every former inline {@code resolveWorkspace}.
     */
    @Transactional(readOnly = true)
    public WorkspaceContext requireMember(User actor, UUID workspaceId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        var membership = workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        return new WorkspaceContext(workspace, membership);
    }

    /**
     * {@link #requireMember} then resolve the project within that workspace. Throws
     * {@link ProjectNotFoundException} (404) if the project isn't in the workspace.
     */
    @Transactional(readOnly = true)
    public ProjectContext requireProjectMember(User actor, UUID workspaceId, UUID projectId) {
        var ws = requireMember(actor, workspaceId);
        var project = projectRepository.findByIdAndWorkspace(projectId, ws.workspace())
                .orElseThrow(ProjectNotFoundException::new);
        return new ProjectContext(ws.workspace(), ws.membership(), project);
    }

    /**
     * {@link #requireProjectMember} then resolve the issue by number within the
     * project. Throws {@link IssueNotFoundException} (404) if no such issue exists.
     */
    @Transactional(readOnly = true)
    public IssueContext requireIssue(User actor, UUID workspaceId, UUID projectId, long issueNumber) {
        var pc = requireProjectMember(actor, workspaceId, projectId);
        var issue = issueRepository.findByProjectAndNumber(pc.project(), issueNumber)
                .orElseThrow(IssueNotFoundException::new);
        return new IssueContext(pc.workspace(), pc.membership(), pc.project(), issue);
    }

    /**
     * Membership + a workspace-role gate. Mirrors {@code ScopeResolver}'s admin-scope
     * semantics: a non-existent workspace or a non-member → 404
     * ({@link WorkspaceNotFoundException}); a member <em>without</em> the required role
     * → 403 ({@link InsufficientWorkspaceRoleException}). Use ONLY where a caller wants
     * workspace-role gating; the default tenancy path is {@link #requireMember}.
     */
    @Transactional(readOnly = true)
    public WorkspaceContext requireWorkspaceRole(User actor, UUID workspaceId, WorkspaceRole min) {
        var ctx = requireMember(actor, workspaceId);
        if (!ctx.role().isAtLeast(min)) {
            throw new InsufficientWorkspaceRoleException();
        }
        return ctx;
    }
}
