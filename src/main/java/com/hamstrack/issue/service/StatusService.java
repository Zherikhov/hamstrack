package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.CreateStatusRequest;
import com.hamstrack.issue.dto.StatusResponse;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.StatusCategory;
import com.hamstrack.issue.repository.StatusRepository;
import com.hamstrack.workspace.entity.Workspace;
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
public class StatusService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final StatusRepository statusRepository;

    @Transactional
    public StatusResponse create(User actor, UUID workspaceId, CreateStatusRequest req) {
        var workspace = resolveWorkspace(actor, workspaceId);
        if (statusRepository.existsByWorkspaceAndName(workspace, req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Status name already exists");
        }
        var status = buildStatus(workspace, req.name(), req.category(), req.color());
        var maxPos = statusRepository.findAllByWorkspaceOrderByPosition(workspace).stream()
                .mapToInt(Status::getPosition).max().orElse(-1);
        status.setPosition((short) (maxPos + 1));
        statusRepository.save(status);
        return StatusResponse.of(status);
    }

    @Transactional(readOnly = true)
    public List<StatusResponse> list(User actor, UUID workspaceId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        return statusRepository.findAllByWorkspaceOrderByPosition(workspace).stream()
                .map(StatusResponse::of)
                .toList();
    }

    @Transactional
    public StatusResponse update(User actor, UUID workspaceId, UUID statusId, CreateStatusRequest req) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var status = statusRepository.findByIdAndWorkspace(statusId, workspace)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.name() != null && !req.name().equals(status.getName())) {
            if (statusRepository.existsByWorkspaceAndName(workspace, req.name())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Status name already exists");
            }
            status.setName(req.name());
        }
        if (req.category() != null) status.setCategory(req.category());
        if (req.color() != null) status.setColor(req.color());
        statusRepository.save(status);
        return StatusResponse.of(status);
    }

    @Transactional
    public void delete(User actor, UUID workspaceId, UUID statusId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var status = statusRepository.findByIdAndWorkspace(statusId, workspace)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        statusRepository.delete(status);
    }

    // Called internally when creating a workspace
    @Transactional
    public void seedDefaults(Workspace workspace) {
        short pos = 0;
        for (var entry : new Object[][]{
                {"To Do",       StatusCategory.TODO,        "#6B7280"},
                {"In Progress", StatusCategory.IN_PROGRESS, "#3B82F6"},
                {"Done",        StatusCategory.DONE,        "#10B981"}
        }) {
            var s = buildStatus(workspace, (String) entry[0], (StatusCategory) entry[1], (String) entry[2]);
            s.setPosition(pos++);
            statusRepository.save(s);
        }
    }

    private Status buildStatus(Workspace workspace, String name, StatusCategory category, String color) {
        var s = new Status();
        s.setWorkspace(workspace);
        s.setName(name);
        s.setCategory(category);
        if (color != null) s.setColor(color);
        return s;
    }

    private Workspace resolveWorkspace(User actor, UUID workspaceId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        return workspace;
    }
}
