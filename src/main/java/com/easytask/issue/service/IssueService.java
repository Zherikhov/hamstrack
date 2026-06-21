package com.easytask.issue.service;

import com.easytask.issue.dto.ChangeIssueStatusRequest;
import com.easytask.issue.dto.CreateIssueRequest;
import com.easytask.issue.dto.IssueHistoryResponse;
import com.easytask.issue.dto.IssueResponse;
import com.easytask.issue.dto.MoveIssueRequest;
import com.easytask.issue.dto.UpdateIssueRequest;
import com.easytask.issue.entity.Issue;
import com.easytask.issue.entity.IssueHistory;
import com.easytask.issue.entity.IssuePriority;
import com.easytask.project.entity.Project;
import com.easytask.project.entity.ProjectIssueType;
import com.easytask.project.entity.ProjectIssueTypeStatus;
import com.easytask.project.entity.ProjectRole;
import com.easytask.project.entity.Status;
import com.easytask.auth.entity.User;
import com.easytask.workspace.entity.WorkspaceMember;
import com.easytask.workspace.entity.WorkspaceRole;
import com.easytask.issue.exception.InvalidIssueStatusException;
import com.easytask.project.exception.InvalidIssueTypeException;
import com.easytask.issue.exception.IssueNotFoundException;
import com.easytask.project.exception.ProjectAccessDeniedException;
import com.easytask.project.exception.ProjectNotFoundException;
import com.easytask.project.exception.UserNotProjectMemberException;
import com.easytask.workspace.exception.WorkspaceNotFoundException;
import com.easytask.issue.repository.IssueHistoryRepository;
import com.easytask.issue.repository.IssueRepository;
import com.easytask.project.repository.ProjectIssueTypeRepository;
import com.easytask.project.repository.ProjectIssueTypeStatusRepository;
import com.easytask.project.repository.ProjectMemberRepository;
import com.easytask.project.repository.ProjectRepository;
import com.easytask.auth.repository.UserRepository;
import com.easytask.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class IssueService {

    private static final BigDecimal POSITION_GAP = BigDecimal.valueOf(1024);

    private final IssueRepository issueRepository;
    private final IssueHistoryRepository issueHistoryRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectIssueTypeRepository projectIssueTypeRepository;
    private final ProjectIssueTypeStatusRepository projectIssueTypeStatusRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;

    public IssueService(IssueRepository issueRepository,
                         IssueHistoryRepository issueHistoryRepository,
                         ProjectRepository projectRepository,
                         ProjectMemberRepository projectMemberRepository,
                         ProjectIssueTypeRepository projectIssueTypeRepository,
                         ProjectIssueTypeStatusRepository projectIssueTypeStatusRepository,
                         WorkspaceMemberRepository workspaceMemberRepository,
                         UserRepository userRepository) {
        this.issueRepository = issueRepository;
        this.issueHistoryRepository = issueHistoryRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectIssueTypeRepository = projectIssueTypeRepository;
        this.projectIssueTypeStatusRepository = projectIssueTypeStatusRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public IssueResponse createIssue(User currentUser, UUID workspaceId, UUID projectId, CreateIssueRequest request) {
        WorkspaceMember actingMembership = requireWorkspaceMembership(currentUser, workspaceId);
        Project project = requireProject(workspaceId, projectId);
        requireProjectWriteAccess(actingMembership, projectId, currentUser);

        ProjectIssueType projectIssueType = projectIssueTypeRepository
                .findByIdAndProject_Id(request.projectIssueTypeId(), projectId)
                .orElseThrow(InvalidIssueTypeException::new);

        ProjectIssueTypeStatus initialCombination = projectIssueTypeStatusRepository
                .findFirstByProjectIssueType_IdOrderByPosition(projectIssueType.getId())
                .orElseThrow(InvalidIssueTypeException::new);
        Status initialStatus = initialCombination.getStatus();

        Issue issue = new Issue();
        issue.setProject(project);
        issue.setNumber(projectRepository.incrementAndGetIssueSeq(projectId));
        issue.setTitle(request.title());
        issue.setDescription(request.description());
        issue.setProjectIssueType(projectIssueType);
        issue.setStatus(initialStatus);
        issue.setPriority(request.priority() != null ? request.priority() : IssuePriority.MEDIUM);
        issue.setReporter(currentUser);
        issue.setAssignee(resolveAssignee(projectId, request.assigneeId()));
        issue.setPosition(nextPosition(projectId, initialStatus.getId()));
        issue.setDueDate(request.dueDate());
        issueRepository.saveAndFlush(issue);

        return toResponse(issue);
    }

    @Transactional(readOnly = true)
    public List<IssueResponse> listIssues(User currentUser, UUID workspaceId, UUID projectId) {
        requireWorkspaceMembership(currentUser, workspaceId);
        requireProject(workspaceId, projectId);
        return issueRepository.findByProject_IdAndDeletedAtIsNullOrderByNumber(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public IssueResponse getIssue(User currentUser, UUID workspaceId, UUID projectId, UUID issueId) {
        requireWorkspaceMembership(currentUser, workspaceId);
        requireProject(workspaceId, projectId);
        return toResponse(requireIssue(projectId, issueId));
    }

    @Transactional
    public IssueResponse updateIssue(User currentUser, UUID workspaceId, UUID projectId, UUID issueId,
                                      UpdateIssueRequest request) {
        WorkspaceMember actingMembership = requireWorkspaceMembership(currentUser, workspaceId);
        requireProject(workspaceId, projectId);
        requireProjectWriteAccess(actingMembership, projectId, currentUser);

        Issue issue = requireIssue(projectId, issueId);
        // resolved before any mutation: an assignee query issued against a dirty issue would
        // trigger Hibernate's auto-flush mid-method, double-incrementing the optimistic-lock version
        User assignee = resolveAssignee(projectId, request.assigneeId());
        String oldTitle = issue.getTitle();
        String oldDescription = issue.getDescription();
        IssuePriority oldPriority = issue.getPriority();
        String oldAssigneeName = displayName(issue.getAssignee());
        String oldDueDate = dateString(issue.getDueDate());

        issue.setTitle(request.title());
        issue.setDescription(request.description());
        issue.setPriority(request.priority());
        issue.setAssignee(assignee);
        issue.setDueDate(request.dueDate());
        issue.setUpdatedBy(currentUser);
        issueRepository.saveAndFlush(issue);

        recordHistory(issue, currentUser, "title", oldTitle, issue.getTitle());
        recordHistory(issue, currentUser, "description", oldDescription, issue.getDescription());
        recordHistory(issue, currentUser, "priority", oldPriority.name(), issue.getPriority().name());
        recordHistory(issue, currentUser, "assignee", oldAssigneeName, displayName(issue.getAssignee()));
        recordHistory(issue, currentUser, "dueDate", oldDueDate, dateString(issue.getDueDate()));

        return toResponse(issue);
    }

    @Transactional
    public IssueResponse changeStatus(User currentUser, UUID workspaceId, UUID projectId, UUID issueId,
                                       ChangeIssueStatusRequest request) {
        WorkspaceMember actingMembership = requireWorkspaceMembership(currentUser, workspaceId);
        requireProject(workspaceId, projectId);
        requireProjectWriteAccess(actingMembership, projectId, currentUser);

        Issue issue = requireIssue(projectId, issueId);
        ProjectIssueTypeStatus combination = projectIssueTypeStatusRepository
                .findByProjectIssueType_IdAndStatus_Id(issue.getProjectIssueType().getId(), request.statusId())
                .orElseThrow(InvalidIssueStatusException::new);
        // resolved before any mutation, see comment in updateIssue
        BigDecimal newPosition = nextPosition(projectId, request.statusId());
        String oldStatusName = issue.getStatus().getName();

        issue.setStatus(combination.getStatus());
        issue.setPosition(newPosition);
        issue.setUpdatedBy(currentUser);
        issueRepository.saveAndFlush(issue);

        recordHistory(issue, currentUser, "status", oldStatusName, issue.getStatus().getName());

        return toResponse(issue);
    }

    @Transactional
    public IssueResponse moveIssue(User currentUser, UUID workspaceId, UUID projectId, UUID issueId,
                                    MoveIssueRequest request) {
        WorkspaceMember actingMembership = requireWorkspaceMembership(currentUser, workspaceId);
        requireProject(workspaceId, projectId);
        requireProjectWriteAccess(actingMembership, projectId, currentUser);

        Issue issue = requireIssue(projectId, issueId);
        ProjectIssueTypeStatus combination = projectIssueTypeStatusRepository
                .findByProjectIssueType_IdAndStatus_Id(issue.getProjectIssueType().getId(), request.statusId())
                .orElseThrow(InvalidIssueStatusException::new);
        // resolved before any mutation, see comment in updateIssue
        BigDecimal prevPos = resolveNeighborPosition(projectId, request.statusId(), request.prevIssueId());
        BigDecimal nextPos = resolveNeighborPosition(projectId, request.statusId(), request.nextIssueId());
        BigDecimal newPosition = betweenPosition(projectId, request.statusId(), prevPos, nextPos);
        String oldStatusName = issue.getStatus().getName();

        issue.setStatus(combination.getStatus());
        issue.setPosition(newPosition);
        issue.setUpdatedBy(currentUser);
        issueRepository.saveAndFlush(issue);

        recordHistory(issue, currentUser, "status", oldStatusName, issue.getStatus().getName());

        return toResponse(issue);
    }

    @Transactional(readOnly = true)
    public List<IssueHistoryResponse> listHistory(User currentUser, UUID workspaceId, UUID projectId, UUID issueId) {
        requireWorkspaceMembership(currentUser, workspaceId);
        requireProject(workspaceId, projectId);
        requireIssue(projectId, issueId);
        return issueHistoryRepository.findByIssue_IdOrderByCreatedAt(issueId).stream()
                .map(this::toHistoryResponse)
                .toList();
    }

    private void recordHistory(Issue issue, User actor, String field, String oldValue, String newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        IssueHistory history = new IssueHistory();
        history.setIssue(issue);
        history.setActor(actor);
        history.setField(field);
        history.setOldValue(oldValue);
        history.setNewValue(newValue);
        issueHistoryRepository.save(history);
    }

    private String displayName(User user) {
        return user != null ? user.getDisplayName() : null;
    }

    private String dateString(LocalDate date) {
        return date != null ? date.toString() : null;
    }

    private BigDecimal resolveNeighborPosition(UUID projectId, UUID statusId, UUID neighborIssueId) {
        if (neighborIssueId == null) {
            return null;
        }
        return issueRepository.findByIdAndProject_IdAndStatus_IdAndDeletedAtIsNull(neighborIssueId, projectId, statusId)
                .orElseThrow(IssueNotFoundException::new)
                .getPosition();
    }

    private BigDecimal betweenPosition(UUID projectId, UUID statusId, BigDecimal prevPos, BigDecimal nextPos) {
        if (prevPos != null && nextPos != null) {
            return prevPos.add(nextPos).divide(BigDecimal.valueOf(2));
        }
        if (prevPos != null) {
            return prevPos.add(POSITION_GAP);
        }
        if (nextPos != null) {
            return nextPos.divide(BigDecimal.valueOf(2));
        }
        return nextPosition(projectId, statusId);
    }

    private User resolveAssignee(UUID projectId, UUID assigneeId) {
        if (assigneeId == null) {
            return null;
        }
        if (!projectMemberRepository.existsByProject_IdAndUser_Id(projectId, assigneeId)) {
            throw new UserNotProjectMemberException();
        }
        return userRepository.getReferenceById(assigneeId);
    }

    private BigDecimal nextPosition(UUID projectId, UUID statusId) {
        BigDecimal maxPosition = issueRepository.findMaxPosition(projectId, statusId);
        return (maxPosition != null ? maxPosition : BigDecimal.ZERO).add(POSITION_GAP);
    }

    private void requireProjectWriteAccess(WorkspaceMember actingMembership, UUID projectId, User currentUser) {
        boolean canWrite = actingMembership.getRole() != WorkspaceRole.MEMBER
                || projectMemberRepository.findByProject_IdAndUser_Id(projectId, currentUser.getId())
                        .map(m -> m.getRole() != ProjectRole.VIEWER)
                        .orElse(false);
        if (!canWrite) {
            throw new ProjectAccessDeniedException();
        }
    }

    private WorkspaceMember requireWorkspaceMembership(User currentUser, UUID workspaceId) {
        return workspaceMemberRepository.findByWorkspace_IdAndUser_Id(workspaceId, currentUser.getId())
                .orElseThrow(WorkspaceNotFoundException::new);
    }

    private Project requireProject(UUID workspaceId, UUID projectId) {
        return projectRepository.findByIdAndWorkspace_IdAndDeletedAtIsNull(projectId, workspaceId)
                .orElseThrow(ProjectNotFoundException::new);
    }

    private Issue requireIssue(UUID projectId, UUID issueId) {
        return issueRepository.findByIdAndProject_IdAndDeletedAtIsNull(issueId, projectId)
                .orElseThrow(IssueNotFoundException::new);
    }

    private IssueResponse toResponse(Issue issue) {
        User assignee = issue.getAssignee();
        return new IssueResponse(
                issue.getId(),
                issue.getProject().getId(),
                issue.getProject().getKey() + "-" + issue.getNumber(),
                issue.getNumber(),
                issue.getTitle(),
                issue.getDescription(),
                issue.getProjectIssueType().getId(),
                issue.getProjectIssueType().getIssueType().getName(),
                issue.getStatus().getId(),
                issue.getStatus().getName(),
                issue.getPriority(),
                issue.getReporter().getId(),
                assignee != null ? assignee.getId() : null,
                issue.getPosition(),
                issue.getDueDate(),
                issue.getCreatedAt(),
                issue.getUpdatedAt(),
                issue.getVersion());
    }

    private IssueHistoryResponse toHistoryResponse(IssueHistory history) {
        return new IssueHistoryResponse(
                history.getId(),
                history.getIssue().getId(),
                history.getActor().getId(),
                history.getActor().getDisplayName(),
                history.getField(),
                history.getOldValue(),
                history.getNewValue(),
                history.getCreatedAt());
    }
}
