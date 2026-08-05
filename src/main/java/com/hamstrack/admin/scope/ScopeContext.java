package com.hamstrack.admin.scope;

import com.hamstrack.issue.entity.Scoped;

import java.util.Objects;
import java.util.UUID;

/**
 * The scope a delegated-admin operation acts at: global (both null),
 * workspace-scoped, or project-private. Threaded through the {@code Admin*Service}s
 * so one code path serves all three consoles — the controller resolves and
 * authorizes the scope (via {@link ScopeResolver}) and passes it here.
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
