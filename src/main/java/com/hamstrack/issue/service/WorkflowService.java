package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.CreateStatusTransitionRequest;
import com.hamstrack.issue.dto.StatusTransitionResponse;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.StatusTransition;
import com.hamstrack.issue.repository.StatusRepository;
import com.hamstrack.issue.repository.StatusTransitionRepository;
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
public class WorkflowService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final StatusRepository statusRepository;
    private final StatusTransitionRepository transitionRepository;

    @Transactional(readOnly = true)
    public List<StatusTransitionResponse> list(User actor, UUID workspaceId) {
        var workspace = resolve(actor, workspaceId);
        return transitionRepository.findAllByWorkspaceOrderByCreatedAtAsc(workspace)
                .stream().map(StatusTransitionResponse::of).toList();
    }

    @Transactional
    public StatusTransitionResponse create(User actor, UUID workspaceId, CreateStatusTransitionRequest req) {
        var workspace = resolve(actor, workspaceId);
        requireOwner(actor, workspace);

        var from = statusRepository.findByIdAndWorkspace(req.fromStatusId(), workspace)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown from status"));
        var to = statusRepository.findByIdAndWorkspace(req.toStatusId(), workspace)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown to status"));

        if (from.getId().equals(to.getId()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "from and to must differ");

        if (transitionRepository.findAllByWorkspaceAndFromStatus(workspace, from)
                .stream().anyMatch(t -> t.getToStatus().getId().equals(to.getId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transition already exists");
        }

        var t = new StatusTransition();
        t.setWorkspace(workspace);
        t.setFromStatus(from);
        t.setToStatus(to);
        return StatusTransitionResponse.of(transitionRepository.save(t));
    }

    @Transactional
    public void delete(User actor, UUID workspaceId, UUID transitionId) {
        var workspace = resolve(actor, workspaceId);
        requireOwner(actor, workspace);
        var t = transitionRepository.findById(transitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!t.getWorkspace().getId().equals(workspace.getId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        transitionRepository.delete(t);
    }

    /**
     * Returns true if the status change is allowed.
     * Rule: if no transitions are defined for fromStatus in this workspace → open.
     *       If any transitions are defined from fromStatus → only those targets are allowed.
     */
    public boolean isTransitionAllowed(Workspace workspace, Status fromStatus, Status toStatus) {
        if (fromStatus.getId().equals(toStatus.getId())) return true;
        var allowed = transitionRepository.findAllByWorkspaceAndFromStatus(workspace, fromStatus);
        if (allowed.isEmpty()) return true; // unrestricted from this status
        return allowed.stream().anyMatch(t -> t.getToStatus().getId().equals(toStatus.getId()));
    }

    private Workspace resolve(User actor, UUID workspaceId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        return workspace;
    }

    private void requireOwner(User actor, Workspace workspace) {
        var member = workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        if (member.getRole() != WorkspaceRole.OWNER)
            throw new InsufficientWorkspaceRoleException();
    }
}
