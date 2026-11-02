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

    /**
     * <strong>{@code "project <uuid>"} | {@code "workspace <uuid>"} | {@code "global"}</strong> —
     * where the row lives, in the words the operator's own SQL uses, <em>with the id</em>.
     *
     * <p>For a log record a machine may key on: a reader routing a finding to a tenant needs the
     * id, and a bare label costs it a second lookup. A global row is one shadowing that hits every
     * workspace on the instance at once, so saying which of the three it is is the difference
     * between one tenant's problem and everyone's.
     *
     * <p>Deliberately beside {@link #scopeLabel()} rather than copied into each writer: the two
     * published records that carry a {@code field.scope} member must agree on its grammar, and
     * they did not — one emitted this form and the other the bare label, under one member name
     * (HD-341). {@code scopeLabel()} stays what the API responses return; this is what a record
     * carries. Both are minted, so neither can be forged.
     */
    default String scopeRef() {
        if (getScopeProjectId() != null) return "project " + getScopeProjectId();
        if (getScopeWorkspaceId() != null) return "workspace " + getScopeWorkspaceId();
        return "global";
    }
}
