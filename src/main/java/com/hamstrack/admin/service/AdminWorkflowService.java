package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.AdminWorkflowResponse;
import com.hamstrack.admin.dto.UpsertWorkflowRequest;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.issue.dto.StatusResponse;
import com.hamstrack.issue.entity.*;
import com.hamstrack.issue.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Workflow CRUD for the admin console. Updates replace statuses/transitions
 * wholesale; removing a status that still holds issues in bound projects is
 * refused (the board renders only workflow statuses — those issues would
 * vanish). The system default workflow can be edited but never deleted.
 */
@Service
@RequiredArgsConstructor
public class AdminWorkflowService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStatusRepository workflowStatusRepository;
    private final WorkflowTransitionRepository workflowTransitionRepository;
    private final StatusRepository statusRepository;
    private final IssueRepository issueRepository;
    private final ProjectCountService projectCountService;

    @Transactional(readOnly = true)
    public List<AdminWorkflowResponse> list(ScopeContext scope) {
        // Delegated consoles also SEE inherited (global/workspace) workflows — read-only,
        // so a project admin can tell what they're using; only own-scope rows are editable.
        var workflows = scope.isGlobal()
                ? workflowRepository.findAllAtScope(null, null)
                : workflowRepository.findAllBindableForProject(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return workflows.stream().map(wf -> toResponse(scope, wf)).toList();
    }

    @Transactional
    public AdminWorkflowResponse create(ScopeContext scope, UpsertWorkflowRequest req) {
        if (workflowRepository.existsAtScopeAndName(scope.workspaceId(), scope.projectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow name already exists");
        }
        var wf = new Workflow();
        scope.stamp(wf);
        wf.setName(req.name());
        wf.setDescription(req.description());
        workflowRepository.save(wf);
        applyStatusesAndTransitions(scope, wf, req);
        return toResponse(scope, wf);
    }

    @Transactional
    public AdminWorkflowResponse update(ScopeContext scope, UUID id, UpsertWorkflowRequest req) {
        var wf = require(scope, id);
        if (!wf.getName().equals(req.name())
                && workflowRepository.existsAtScopeAndName(scope.workspaceId(), scope.projectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow name already exists");
        }
        // Integrity guard: statuses being removed must hold no issues in
        // projects using this workflow
        var keptIds = new HashSet<>(req.statusIds());
        for (var ws : workflowStatusRepository.findAllByWorkflowOrderByPosition(wf)) {
            if (!keptIds.contains(ws.getStatus().getId())) {
                long stranded = issueRepository.countByStatusInWorkflowProjects(
                        ws.getStatus(), wf, wf.isSystemDefault());
                if (stranded > 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            stranded + " issues are in status '" + ws.getStatus().getName()
                                    + "' — move them before removing it from this workflow");
                }
            }
        }
        wf.setName(req.name());
        wf.setDescription(req.description());
        workflowRepository.save(wf);
        workflowTransitionRepository.deleteAllByWorkflow(wf);
        workflowStatusRepository.deleteAllByWorkflow(wf);
        // Force the DELETEs to hit the DB before we re-insert. Hibernate otherwise
        // orders INSERTs ahead of DELETEs in a single flush, so re-adding a status
        // that was already present collides with UNIQUE(workflow_id, status_id).
        workflowStatusRepository.flush();
        applyStatusesAndTransitions(scope, wf, req);
        return toResponse(scope, wf);
    }

    @Transactional
    public void delete(ScopeContext scope, UUID id) {
        var wf = require(scope, id);
        if (wf.isSystemDefault()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The system default workflow cannot be deleted");
        }
        long projects = projectCountService.projectsUsingWorkflow(scope, wf);
        if (projects > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    projects + " projects use this workflow — reassign them first");
        }
        workflowRepository.delete(wf);
    }

    private void applyStatusesAndTransitions(ScopeContext scope, Workflow wf, UpsertWorkflowRequest req) {
        var seen = new HashSet<UUID>();
        short pos = 0;
        for (var statusId : req.statusIds()) {
            if (!seen.add(statusId)) continue;
            var status = requireStatus(scope, statusId);
            var ws = new WorkflowStatus();
            ws.setWorkflow(wf);
            ws.setStatus(status);
            ws.setPosition(pos++);
            workflowStatusRepository.save(ws);
        }
        if (req.transitions() != null) {
            for (var rule : req.transitions()) {
                if (rule.fromStatusId() != null && !seen.contains(rule.fromStatusId())) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Transition source status is not part of the workflow");
                }
                if (!seen.contains(rule.toStatusId())) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Transition target status is not part of the workflow");
                }
                var t = new WorkflowTransition();
                t.setWorkflow(wf);
                t.setFromStatus(rule.fromStatusId() != null ? requireStatus(scope, rule.fromStatusId()) : null);
                t.setToStatus(requireStatus(scope, rule.toStatusId()));
                workflowTransitionRepository.save(t);
            }
        }
    }

    private AdminWorkflowResponse toResponse(ScopeContext scope, Workflow wf) {
        var statuses = workflowStatusRepository.findAllByWorkflowOrderByPosition(wf).stream()
                .map(ws -> StatusResponse.of(ws.getStatus()))
                .toList();
        var transitions = workflowTransitionRepository.findAllByWorkflow(wf).stream()
                .map(t -> new AdminWorkflowResponse.TransitionRule(
                        t.getFromStatus() == null ? null : t.getFromStatus().getId(),
                        t.getToStatus().getId()))
                .toList();
        return new AdminWorkflowResponse(wf.getId(), wf.getName(), wf.getDescription(),
                wf.isSystemDefault(), statuses, transitions,
                projectCountService.projectsUsingWorkflow(scope, wf), wf.scopeLabel());
    }

    private Workflow require(ScopeContext scope, UUID id) {
        return workflowRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
    }

    /** A status the workflow may include: visible to this scope (global ∪ ancestor-ws ∪ own project). */
    private Status requireStatus(ScopeContext scope, UUID id) {
        return statusRepository.findByIdVisibleTo(id, scope.visibleWorkspaceId(), scope.visibleProjectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown status"));
    }
}
