package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.sse.SseRegistry;
import com.hamstrack.issue.dto.CreateIssueRequest;
import com.hamstrack.issue.dto.IssueHistoryResponse;
import com.hamstrack.issue.dto.IssueResponse;
import com.hamstrack.issue.dto.UpdateIssueRequest;
import com.hamstrack.issue.entity.*;
import com.hamstrack.issue.exception.IssueNotFoundException;
import com.hamstrack.issue.repository.*;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IssueService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final IssueRepository issueRepository;
    private final IssueTypeRepository issueTypeRepository;
    private final StatusRepository statusRepository;
    private final PriorityRepository priorityRepository;
    private final UserRepository userRepository;
    private final IssueHistoryRepository historyRepository;
    private final ProjectConfigService projectConfigService;
    private final FieldValueService fieldValueService;
    private final AttachmentService attachmentService;
    private final SseRegistry sseRegistry;

    @Transactional
    public IssueResponse create(User actor, UUID workspaceId, UUID projectId, CreateIssueRequest req) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
        requireNotArchived(project);

        var type = projectConfigService.requireTypeInSet(project, resolveType(req.typeId()));
        var status = projectConfigService.requireStatusInWorkflow(project, resolveStatus(req.statusId()));
        var priority = req.priorityId() != null
                ? projectConfigService.requirePriorityInSet(project, resolvePriority(req.priorityId()))
                : projectConfigService.defaultPriority(project);

        // Atomic seq increment
        long seq = projectRepository.incrementAndGetIssueSeq(project.getId());

        var issue = new Issue();
        issue.setWorkspace(workspace);
        issue.setProject(project);
        issue.setNumber(seq);
        issue.setTitle(req.title());
        issue.setDescription(req.description());
        issue.setType(type);
        issue.setStatus(status);
        issue.setPriority(priority);
        issue.setReporter(actor);
        issue.setPosition(seq);

        if (req.assigneeId() != null) {
            issue.setAssignee(resolveAssignee(workspace, req.assigneeId()));
        }
        if (req.parentId() != null) {
            issue.setParent(issueRepository.findByIdAndProject(req.parentId(), project)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown parent issue")));
        }
        issue.setDueDate(req.dueDate());
        issueRepository.save(issue);

        // Custom fields: validates against the project's field set (incl.
        // required-on-create); no history entries for initial values
        fieldValueService.applyValues(issue, req.fields(), true, (f, o, n) -> {});

        sseRegistry.broadcast(workspaceId, "ISSUE_CREATED",
                Map.of("projectId", projectId.toString(), "issueNumber", issue.getNumber()));
        return IssueResponse.of(issue, fieldValueService.values(issue));
    }

    @Transactional(readOnly = true)
    public List<IssueResponse> list(User actor, UUID workspaceId, UUID projectId,
                                    UUID statusId, UUID assigneeId, UUID priorityId) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
        var issues = issueRepository.findByProjectFiltered(project, statusId, assigneeId, priorityId);
        var valuesByIssue = fieldValueService.valuesByIssue(issues);
        return issues.stream()
                .map(i -> IssueResponse.of(i, valuesByIssue.get(i.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public IssueResponse get(User actor, UUID workspaceId, UUID projectId, long number) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
        var issue = issueRepository.findByProjectAndNumber(project, number)
                .orElseThrow(IssueNotFoundException::new);
        return IssueResponse.of(issue, fieldValueService.values(issue));
    }

    @Transactional(readOnly = true)
    public List<IssueHistoryResponse> getHistory(User actor, UUID workspaceId, UUID projectId, long number) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
        var issue = issueRepository.findByProjectAndNumber(project, number)
                .orElseThrow(IssueNotFoundException::new);
        return historyRepository.findAllByIssueOrderByCreatedAtAsc(issue)
                .stream().map(IssueHistoryResponse::of).toList();
    }

    @Transactional
    public IssueResponse update(User actor, UUID workspaceId, UUID projectId, long number, UpdateIssueRequest req) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
        requireNotArchived(project);

        // All reads first (avoid Hibernate auto-flush double-write — see CLAUDE.md gotchas)
        var newType = req.typeId() != null
                ? projectConfigService.requireTypeInSet(project, resolveType(req.typeId()))
                : null;
        var newStatus = req.statusId() != null
                ? projectConfigService.requireStatusInWorkflow(project, resolveStatus(req.statusId()))
                : null;
        var newPriority = req.priorityId() != null
                ? projectConfigService.requirePriorityInSet(project, resolvePriority(req.priorityId()))
                : null;
        var newAssignee = req.assigneeId() != null ? resolveAssignee(workspace, req.assigneeId()) : null;

        var issue = issueRepository.findByProjectAndNumber(project, number)
                .orElseThrow(IssueNotFoundException::new);

        if (req.version() != null && req.version() != issue.getVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Issue was modified by someone else — refresh and retry");
        }

        var historyEntries = new ArrayList<IssueHistory>();

        if (req.title() != null && !req.title().equals(issue.getTitle())) {
            historyEntries.add(makeHistory(issue, actor, "title", issue.getTitle(), req.title()));
            issue.setTitle(req.title());
        }
        if (req.description() != null && !req.description().equals(issue.getDescription())) {
            historyEntries.add(makeHistory(issue, actor, "description",
                    issue.getDescription() != null ? "..." : null, "..."));
            issue.setDescription(req.description());
        }
        if (newType != null && !newType.getId().equals(issue.getType().getId())) {
            historyEntries.add(makeHistory(issue, actor, "type", issue.getType().getName(), newType.getName()));
            issue.setType(newType);
        }
        if (newStatus != null && !newStatus.getId().equals(issue.getStatus().getId())) {
            projectConfigService.validateTransition(project, issue.getStatus(), newStatus);
            historyEntries.add(makeHistory(issue, actor, "status", issue.getStatus().getName(), newStatus.getName()));
            issue.setStatus(newStatus);
        }
        if (newPriority != null && !newPriority.getId().equals(issue.getPriority().getId())) {
            historyEntries.add(makeHistory(issue, actor, "priority",
                    issue.getPriority().getName(), newPriority.getName()));
            issue.setPriority(newPriority);
        }
        if (newAssignee != null) {
            String oldName = issue.getAssignee() != null ? issue.getAssignee().getDisplayName() : null;
            if (!newAssignee.getId().equals(issue.getAssignee() != null ? issue.getAssignee().getId() : null)) {
                historyEntries.add(makeHistory(issue, actor, "assignee", oldName, newAssignee.getDisplayName()));
                issue.setAssignee(newAssignee);
            }
        }
        if (req.dueDate() != null && !req.dueDate().equals(issue.getDueDate())) {
            historyEntries.add(makeHistory(issue, actor, "dueDate",
                    issue.getDueDate() != null ? issue.getDueDate().toString() : null,
                    req.dueDate().toString()));
            issue.setDueDate(req.dueDate());
        }

        // Custom fields: partial map, JSON null clears; changes land in history
        fieldValueService.applyValues(issue, req.fields(), false,
                (fieldName, oldVal, newVal) ->
                        historyEntries.add(makeHistory(issue, actor, fieldName, oldVal, newVal)));

        issueRepository.save(issue);
        historyRepository.saveAll(historyEntries);

        sseRegistry.broadcast(workspaceId, "ISSUE_UPDATED",
                Map.of("projectId", projectId.toString(), "issueNumber", number));
        return IssueResponse.of(issue, fieldValueService.values(issue));
    }

    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, long number) {
        var workspace = resolveWorkspace(actor, workspaceId);
        var project = projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
        requireNotArchived(project);
        requireProjectRole(actor, project, ProjectRole.MANAGER);
        var issue = issueRepository.findByProjectAndNumber(project, number)
                .orElseThrow(IssueNotFoundException::new);
        attachmentService.removeStoredFilesForIssue(issue);
        issueRepository.delete(issue);

        sseRegistry.broadcast(workspaceId, "ISSUE_DELETED",
                Map.of("projectId", projectId.toString(), "issueNumber", number));
    }

    // ---- catalog resolution (global rows; workspace scoping reserved) ----

    private IssueType resolveType(UUID id) {
        return issueTypeRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .filter(t -> t.getArchivedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown issue type"));
    }

    private Status resolveStatus(UUID id) {
        return statusRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .filter(s -> s.getArchivedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown status"));
    }

    private Priority resolvePriority(UUID id) {
        return priorityRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .filter(p -> p.getArchivedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown priority"));
    }

    private IssueHistory makeHistory(Issue issue, User actor, String field, String oldVal, String newVal) {
        var h = new IssueHistory();
        h.setIssue(issue);
        h.setChangedBy(actor);
        h.setField(field);
        h.setOldValue(oldVal);
        h.setNewValue(newVal);
        return h;
    }

    private void requireNotArchived(Project project) {
        if (project.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is archived");
        }
    }

    // Assignee must be a member of the workspace — a bare findById would let callers
    // reference (and enumerate) users from other tenants
    private User resolveAssignee(Workspace workspace, UUID assigneeId) {
        return userRepository.findById(assigneeId)
                .filter(u -> workspaceMemberRepository.existsByWorkspaceAndUser(workspace, u))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown assignee"));
    }

    private Workspace resolveWorkspace(User actor, UUID workspaceId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        return workspace;
    }

    private void requireProjectRole(User actor, Project project, ProjectRole required) {
        var role = projectMemberRepository.findByProjectAndUser(project, actor)
                .map(pm -> pm.getRole())
                .orElse(ProjectRole.VIEWER);
        if (!role.isAtLeast(required)) {
            throw new com.hamstrack.project.exception.InsufficientProjectRoleException();
        }
    }
}
