package com.hamstrack.issue.entity;

import java.util.UUID;

/**
 * A catalog primitive (status, priority, issue type, field def) or reusable set
 * (workflow, priority/field/issue-type set) that lives at exactly one of three
 * scopes, mutually exclusive by DB CHECK (V11):
 * <ul>
 *   <li><b>global</b> — both ids {@code null}; managed by the system admin</li>
 *   <li><b>workspace-scoped</b> — {@code scopeWorkspaceId} set; managed by that
 *       workspace's OWNER/ADMIN</li>
 *   <li><b>project-private</b> — {@code scopeProjectId} set; managed by that
 *       project's MANAGER</li>
 * </ul>
 * All implementors get the accessors from Lombok {@code @Getter/@Setter}.
 */
public interface Scoped {
    UUID getScopeWorkspaceId();
    void setScopeWorkspaceId(UUID id);
    UUID getScopeProjectId();
    void setScopeProjectId(UUID id);

    /** "GLOBAL" | "WORKSPACE" | "PROJECT" — lets delegated consoles mark inherited (read-only) rows. */
    default String scopeLabel() {
        return getScopeProjectId() != null ? "PROJECT"
                : getScopeWorkspaceId() != null ? "WORKSPACE"
                : "GLOBAL";
    }
}
