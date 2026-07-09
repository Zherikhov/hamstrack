package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.CreateIssueTypeRequest;
import com.hamstrack.issue.dto.IssueTypeResponse;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.issue.repository.IssueTypeRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceRole;
import com.hamstrack.workspace.exception.InsufficientWorkspaceRoleException;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IssueTypeService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final IssueTypeRepository issueTypeRepository;
    private final IssueRepository issueRepository;

    @Transactional
    public IssueTypeResponse create(User actor, UUID workspaceId, CreateIssueTypeRequest req) {
        var workspace = resolveWorkspace(actor, workspaceId);
        requireAdmin(actor, workspace);
        if (issueTypeRepository.existsByWorkspaceAndName(workspace, req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue type name already exists");
        }
        var type = new IssueType();
        type.setWorkspace(workspace);
        type.setName(req.name());
        if (req.color() != null) type.setColor(req.color());
        if (req.icon() != null) type.setIcon(req.icon());
        var maxPos = issueTypeRepository.findAllByWorkspaceOrderByPosition(workspace).stream()
                .mapToInt(IssueType::getPosition).max().orElse(-1);
        type.setPosition((short) (maxPos + 1));
        issueTypeRepository.save(type);
        return IssueTypeResponse.of(type);
    }

    @Transactional(readOnly = true)
    public List<IssueTypeResponse> list(User actor, UUID workspaceId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        return issueTypeRepository.findAllByWorkspaceOrderByPosition(workspace).stream()
                .map(IssueTypeResponse::of)
                .toList();
    }

    @Transactional
    public IssueTypeResponse update(User actor, UUID workspaceId, UUID typeId, CreateIssueTypeRequest req) {
        var workspace = resolveWorkspace(actor, workspaceId);
        requireAdmin(actor, workspace);
        var type = issueTypeRepository.findByIdAndWorkspace(typeId, workspace)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.name() != null && !req.name().equals(type.getName())) {
            if (issueTypeRepository.existsByWorkspaceAndName(workspace, req.name())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue type name already exists");
            }
            type.setName(req.name());
        }
        if (req.color() != null) type.setColor(req.color());
        if (req.icon() != null) type.setIcon(req.icon());
        issueTypeRepository.save(type);
        return IssueTypeResponse.of(type);
    }

    @Transactional
    public void delete(User actor, UUID workspaceId, UUID typeId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        requireAdmin(actor, workspace);
        var type = issueTypeRepository.findByIdAndWorkspace(typeId, workspace)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // issues.type_id has no ON DELETE action — deleting an in-use type would be a 500
        if (issueRepository.existsByType(type)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue type is used by existing issues");
        }
        issueTypeRepository.delete(type);
    }

    // Called internally when creating a workspace — no actor membership check needed
    @Transactional
    public void seedDefaults(Workspace workspace) {
        short pos = 0;
        for (var entry : new String[][]{
                {"Bug", "#EF4444", "bug"},
                {"Task", "#3B82F6", "task"},
                {"Story", "#8B5CF6", "story"},
                {"Epic", "#F59E0B", "epic"}
        }) {
            var t = new IssueType();
            t.setWorkspace(workspace);
            t.setName(entry[0]);
            t.setColor(entry[1]);
            t.setIcon(entry[2]);
            t.setPosition(pos++);
            issueTypeRepository.save(t);
        }
    }

    private Workspace resolveWorkspace(User actor, UUID workspaceId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        return workspace;
    }

    private void requireAdmin(User actor, Workspace workspace) {
        var member = workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        if (!member.getRole().isAtLeast(WorkspaceRole.ADMIN)) {
            throw new InsufficientWorkspaceRoleException();
        }
    }
}
