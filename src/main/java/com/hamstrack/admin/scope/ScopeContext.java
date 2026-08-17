package com.hamstrack.admin.scope;

import com.hamstrack.issue.entity.Scoped;

import java.util.Objects;
import java.util.UUID;

/**
 * The scope a delegated-admin operation acts at: global (both null),
 * workspace-scoped, or project-private. Threaded through the {@code Admin*Service}s
 * so one code path serves all three consoles — the controller resolves and
 * authorizes the scope ({@code workspace.taxonomy.manage} /
 * {@code project.taxonomy.manage}, through {@code WorkspaceAccessService}) and passes it
 * here.
 *
 * <p><strong>This is not an authorization type</strong> and it survived HD-126's deletion
 * of {@code ScopeResolver} for that reason: it is the <em>catalog scope</em> a
 * delegated-admin write is stamped with and bounded by. Two different concepts that shared
 * a package.
 *
 * <p>Rows are created stamped with {@link #stamp} and only rows the context
 * {@link #owns} may be edited/deleted, so a workspace admin can never touch a
 * global row and a project admin can never touch a workspace row.
 */
public record ScopeContext(UUID workspaceId, UUID projectId, UUID ancestorWorkspaceId) {

    public static ScopeContext global() {
        return new ScopeContext(null, null, null);
    }

    public static ScopeContext workspace(UUID workspaceId) {
        return new ScopeContext(workspaceId, null, workspaceId);
    }

    /** Project scope carries the project's workspace as ancestor (for child visibility). */
    public static ScopeContext project(UUID workspaceId, UUID projectId) {
        return new ScopeContext(null, projectId, workspaceId);
    }

    public boolean isGlobal() {
        return workspaceId == null && projectId == null;
    }

    /** Stamp a freshly-created entity with this scope (never the ancestor). */
    public void stamp(Scoped entity) {
        entity.setScopeWorkspaceId(workspaceId);
        entity.setScopeProjectId(projectId);
    }

    /** True if the entity lives at exactly this scope. */
    public boolean owns(Scoped entity) {
        return Objects.equals(entity.getScopeWorkspaceId(), workspaceId)
                && Objects.equals(entity.getScopeProjectId(), projectId);
    }

    /**
     * True if this scope may <em>see</em> the entity (global ∪ ancestor-workspace ∪
     * own project) — the same visibility {@code findByIdVisibleTo}/{@code findAllVisibleTo}
     * enforce at the DB layer. Used to keep usage aggregation (counts, "used-by"
     * lists) from spanning containers of other tenants: a delegated console
     * computes usage only over containers it can see. Global scope sees everything.
     */
    public boolean canSee(Scoped entity) {
        if (isGlobal()) return true;
        boolean global = entity.getScopeWorkspaceId() == null && entity.getScopeProjectId() == null;
        boolean ownWorkspace = ancestorWorkspaceId != null
                && ancestorWorkspaceId.equals(entity.getScopeWorkspaceId());
        boolean ownProject = projectId != null
                && projectId.equals(entity.getScopeProjectId());
        return global || ownWorkspace || ownProject;
    }

    /**
     * Workspace whose rows are visible as children of a set built here (its own
     * workspace, or a project's ancestor workspace); null at global scope. Used
     * with {@link #visibleProjectId()} to check a status/priority/type a scoped
     * set references is one this scope may see (global ∪ ancestor-ws ∪ own project).
     */
    public UUID visibleWorkspaceId() {
        return ancestorWorkspaceId;
    }

    /** Project whose private rows are visible as children; null unless project scope. */
    public UUID visibleProjectId() {
        return projectId;
    }
}
