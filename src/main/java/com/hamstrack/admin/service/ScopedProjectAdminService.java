package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.BindingOptionsResponse;
import com.hamstrack.admin.dto.BindingOptionsResponse.SetOption;
import com.hamstrack.admin.dto.ProjectBindingResponse;
import com.hamstrack.admin.dto.UpdateProjectBindingsRequest;
import com.hamstrack.auth.entity.User;
import com.hamstrack.common.security.Permission;
import com.hamstrack.issue.entity.Scoped;
import com.hamstrack.issue.repository.FieldSetRepository;
import com.hamstrack.issue.repository.IssueTypeSetRepository;
import com.hamstrack.issue.repository.PrioritySetRepository;
import com.hamstrack.issue.repository.WorkflowRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Delegated binding management for workspace admins and project admins. A
 * project may be bound only to sets <em>visible</em> to it — global, its own
 * workspace's, or its own project-private — enforced by {@link #requireBindable}.
 * This structurally prevents binding a set that belongs to another tenant or
 * another project. Authorization is {@code workspace.taxonomy.manage} on the workspace
 * paths and {@code project.taxonomy.manage} on the project ones, resolved through the
 * single tenancy chokepoint (HD-126 S3, §10.4 — {@code ScopeResolver} is gone); the
 * system-admin matrix stays in {@link AdminProjectService}.
 */
@Service
@RequiredArgsConstructor
public class ScopedProjectAdminService {

    private final WorkspaceAccessService workspaceAccess;
    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final PrioritySetRepository prioritySetRepository;
    private final FieldSetRepository fieldSetRepository;
    private final IssueTypeSetRepository issueTypeSetRepository;
    private final WorkflowRebindGuard workflowRebindGuard;

    // ---------- workspace admin ----------

    @Transactional(readOnly = true)
    public List<ProjectBindingResponse> workspaceMatrix(User actor, UUID workspaceId) {
        var workspace = requireWorkspaceTaxonomyAdmin(actor, workspaceId);
        return projectRepository.findAllByWorkspace(workspace).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BindingOptionsResponse workspaceBindingOptions(User actor, UUID workspaceId) {
        requireWorkspaceTaxonomyAdmin(actor, workspaceId);
        // proj = null → global ∪ workspace only (a null project id matches no rows)
        return options(workspaceId, null);
    }

    @Transactional
    public ProjectBindingResponse updateWorkspaceProjectBindings(
            User actor, UUID workspaceId, UUID projectId, UpdateProjectBindingsRequest req) {
        var workspace = requireWorkspaceTaxonomyAdmin(actor, workspaceId);
        var project = projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
        return applyBindings(project, req);
    }

    // ---------- project admin ----------

    @Transactional(readOnly = true)
    public ProjectBindingResponse projectBindings(User actor, UUID workspaceId, UUID projectId) {
        return toResponse(requireProjectTaxonomyAdmin(actor, workspaceId, projectId));
    }

    @Transactional(readOnly = true)
    public BindingOptionsResponse projectBindingOptions(User actor, UUID workspaceId, UUID projectId) {
        requireProjectTaxonomyAdmin(actor, workspaceId, projectId);
        return options(workspaceId, projectId);
    }

    @Transactional
    public ProjectBindingResponse updateProjectBindings(
            User actor, UUID workspaceId, UUID projectId, UpdateProjectBindingsRequest req) {
        return applyBindings(requireProjectTaxonomyAdmin(actor, workspaceId, projectId), req);
    }

    // ---------- authorization ----------

    /**
     * 404 for a missing workspace or a non-member; 403 for a member without
     * {@code workspace.taxonomy.manage}. Δ-free against the deleted
     * {@code ScopeResolver.requireWorkspaceAdmin}: the built-in Owner and Admin hold that
     * permission and Member does not.
     */
    private Workspace requireWorkspaceTaxonomyAdmin(User actor, UUID workspaceId) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        ctx.permissions().require(Permission.WORKSPACE_TAXONOMY_MANAGE);
        return ctx.workspace();
    }

    /**
     * 404 for a missing workspace, a missing project or a non-member of the
     * <em>workspace</em>; <strong>403</strong> for a workspace member without
     * {@code project.taxonomy.manage} in this project.
     *
     * <p>That 403 is the one deliberate 404→403 change of HD-126 (§10.3.2) — see
     * {@code ProjectAdminController} for why it is safe here and must not be copied
     * elsewhere. The permission itself is Δ-free: only the built-in Project admin holds
     * it, and a workspace Owner/Admin with no {@code project_members} row is still refused
     * (it is not in the {@code project.curate.all} bypass), which is exactly what
     * {@code requireProjectAdmin} did.
     */
    private Project requireProjectTaxonomyAdmin(User actor, UUID workspaceId, UUID projectId) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.PROJECT_TAXONOMY_MANAGE);
        return ctx.project();
    }

    // ---------- shared ----------

    /**
     * Full-replacement binding update (NULL = system default). A workflow change
     * is refused (409) while any issue sits in a status the new workflow lacks —
     * it would vanish from the board. Priority/field/type set changes are safe:
     * existing issues keep their values/type, only new work is constrained.
     */
    private ProjectBindingResponse applyBindings(Project project, UpdateProjectBindingsRequest req) {
        var newWorkflow = req.workflowId() == null ? null
                : requireBindable(workflowRepository.findById(req.workflowId()).orElse(null), project, "Workflow");
        var newPrioritySet = req.prioritySetId() == null ? null
                : requireBindable(prioritySetRepository.findById(req.prioritySetId()).orElse(null), project, "Priority set");
        var newFieldSet = req.fieldSetId() == null ? null
                : requireBindable(fieldSetRepository.findById(req.fieldSetId()).orElse(null), project, "Field set");
        var newTypeSet = req.issueTypeSetId() == null ? null
                : requireBindable(issueTypeSetRepository.findById(req.issueTypeSetId()).orElse(null), project, "Issue type set");

        workflowRebindGuard.check(project, newWorkflow);

        project.setWorkflow(newWorkflow);
        project.setPrioritySet(newPrioritySet);
        project.setFieldSet(newFieldSet);
        project.setIssueTypeSet(newTypeSet);
        projectRepository.save(project);
        return toResponse(project);
    }

    /** 422 unless the set exists and is visible to the project (global / its ws / itself). */
    private <T extends Scoped> T requireBindable(T set, Project project, String label) {
        if (set == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown " + label.toLowerCase(Locale.ROOT));
        }
        boolean visible = (set.getScopeWorkspaceId() == null && set.getScopeProjectId() == null)
                || project.getWorkspace().getId().equals(set.getScopeWorkspaceId())
                || project.getId().equals(set.getScopeProjectId());
        if (!visible) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    label + " is not available to this project");
        }
        return set;
    }

    private BindingOptionsResponse options(UUID workspaceId, UUID projectId) {
        return new BindingOptionsResponse(
                workflowRepository.findAllBindableForProject(workspaceId, projectId).stream()
                        .map(w -> SetOption.of(w.getId(), w.getName(), w)).toList(),
                prioritySetRepository.findAllBindableForProject(workspaceId, projectId).stream()
                        .map(s -> SetOption.of(s.getId(), s.getName(), s)).toList(),
                fieldSetRepository.findAllBindableForProject(workspaceId, projectId).stream()
                        .map(s -> SetOption.of(s.getId(), s.getName(), s)).toList(),
                issueTypeSetRepository.findAllBindableForProject(workspaceId, projectId).stream()
                        .map(s -> SetOption.of(s.getId(), s.getName(), s)).toList());
    }

    private ProjectBindingResponse toResponse(Project p) {
        return new ProjectBindingResponse(
                p.getId(), p.getKey(), p.getName(), p.isArchived(),
                p.getWorkspace().getId(), p.getWorkspace().getName(),
                p.getWorkflow() != null ? p.getWorkflow().getId() : null,
                p.getPrioritySet() != null ? p.getPrioritySet().getId() : null,
                p.getFieldSet() != null ? p.getFieldSet().getId() : null,
                p.getIssueTypeSet() != null ? p.getIssueTypeSet().getId() : null);
    }
}
