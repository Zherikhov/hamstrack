package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.sse.SseRegistry;
import com.hamstrack.common.storage.FileStorage;
import com.hamstrack.issue.dto.AttachmentResponse;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueAttachment;
import com.hamstrack.issue.exception.AttachmentNotFoundException;
import com.hamstrack.issue.exception.IssueNotFoundException;
import com.hamstrack.issue.repository.IssueAttachmentRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final IssueRepository issueRepository;
    private final IssueAttachmentRepository attachmentRepository;
    private final FileStorage fileStorage;
    private final SseRegistry sseRegistry;

    public record AttachmentDownload(String filename, String contentType, long sizeBytes, InputStream stream) {}

    @Transactional
    public AttachmentResponse upload(User actor, UUID workspaceId, UUID projectId, long issueNumber, MultipartFile file) {
        var issue = resolveIssue(actor, workspaceId, projectId, issueNumber);
        requireNotArchived(issue);
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }

        var attachment = new IssueAttachment();
        attachment.setIssue(issue);
        attachment.setFilename(sanitizeFilename(file.getOriginalFilename()));
        attachment.setSizeBytes(file.getSize());
        attachment.setContentType(truncate(
                file.getContentType() != null ? file.getContentType() : "application/octet-stream", 255));
        attachment.setUploadedBy(actor);
        // Key is server-generated (no user input) — the original filename lives only in the DB
        attachment.setStorageKey("ws/" + workspaceId + "/issues/" + issue.getId() + "/" + UUID.randomUUID());
        attachmentRepository.save(attachment);

        // Last side effect before commit: if the write to storage fails, the tx rolls
        // back and no row exists; a commit failure after this leaks at most one blob
        try (var in = file.getInputStream()) {
            fileStorage.store(attachment.getStorageKey(), in, file.getSize(), attachment.getContentType());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store file");
        }

        sseRegistry.broadcast(workspaceId, "ATTACHMENT_ADDED",
                Map.of("projectId", projectId.toString(), "issueNumber", issueNumber));
        return AttachmentResponse.of(attachment);
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(User actor, UUID workspaceId, UUID projectId, long issueNumber) {
        var issue = resolveIssue(actor, workspaceId, projectId, issueNumber);
        return attachmentRepository.findAllByIssueOrderByCreatedAtAsc(issue).stream()
                .map(AttachmentResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public AttachmentDownload download(User actor, UUID workspaceId, UUID projectId, long issueNumber, UUID attachmentId) {
        var issue = resolveIssue(actor, workspaceId, projectId, issueNumber);
        var attachment = findAttachmentOnIssue(attachmentId, issue);
        return new AttachmentDownload(
                attachment.getFilename(), attachment.getContentType(), attachment.getSizeBytes(),
                fileStorage.open(attachment.getStorageKey()));
    }

    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, long issueNumber, UUID attachmentId) {
        var issue = resolveIssue(actor, workspaceId, projectId, issueNumber);
        requireNotArchived(issue);
        var attachment = findAttachmentOnIssue(attachmentId, issue);
        if (!attachment.getUploadedBy().getId().equals(actor.getId())
                && !hasProjectRole(actor, issue, ProjectRole.MANAGER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        attachmentRepository.delete(attachment);
        deleteFromStorageAfterCommit(attachment.getStorageKey());

        sseRegistry.broadcast(workspaceId, "ATTACHMENT_DELETED",
                Map.of("projectId", projectId.toString(), "issueNumber", issueNumber));
    }

    /**
     * Called by IssueService.delete — DB rows go away via ON DELETE CASCADE, but the
     * blobs must be cleaned up explicitly (after commit, so a rollback keeps them).
     */
    @Transactional
    public void removeStoredFilesForIssue(Issue issue) {
        attachmentRepository.findAllByIssueOrderByCreatedAtAsc(issue)
                .forEach(a -> deleteFromStorageAfterCommit(a.getStorageKey()));
    }

    // Blob deletion must not precede the commit (a rollback can't restore the file),
    // and a storage failure must not fail the request — the row is gone, the orphan
    // blob is only a cleanup concern
    private void deleteFromStorageAfterCommit(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    fileStorage.delete(storageKey);
                } catch (RuntimeException e) {
                    log.warn("Failed to delete stored attachment {}", storageKey, e);
                }
            }
        });
    }

    private String sanitizeFilename(String original) {
        var name = original != null ? StringUtils.getFilename(original) : null;
        if (name == null || name.isBlank()) name = "file";
        return truncate(name, 255);
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void requireNotArchived(Issue issue) {
        if (issue.getProject().isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is archived");
        }
    }

    private boolean hasProjectRole(User actor, Issue issue, ProjectRole required) {
        return projectMemberRepository.findByProjectAndUser(issue.getProject(), actor)
                .map(pm -> pm.getRole().isAtLeast(required))
                .orElse(false);
    }

    // The attachment must belong to the issue resolved from the URL — a global findById
    // would let callers reach attachments in workspaces they aren't members of
    private IssueAttachment findAttachmentOnIssue(UUID attachmentId, Issue issue) {
        return attachmentRepository.findById(attachmentId)
                .filter(a -> a.getIssue().getId().equals(issue.getId()))
                .orElseThrow(AttachmentNotFoundException::new);
    }

    private Issue resolveIssue(User actor, UUID workspaceId, UUID projectId, long issueNumber) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        var project = projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
        return issueRepository.findByProjectAndNumber(project, issueNumber)
                .orElseThrow(IssueNotFoundException::new);
    }
}
