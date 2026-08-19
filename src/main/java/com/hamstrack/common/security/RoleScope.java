package com.hamstrack.common.security;

/**
 * The level at which a {@link Permission} — and therefore a role that grants it —
 * applies. One enum for both because they must never disagree: a role of scope
 * {@code PROJECT} may only hold permissions of scope {@code PROJECT}, and that
 * invariant is only checkable if there is a single vocabulary.
 *
 * <p>Stored as {@code VARCHAR(20)} on {@code roles.scope} (never a PostgreSQL ENUM
 * type — Hibernate 7 + PG ENUM produces JDBC cast errors on INSERT).
 */
public enum RoleScope {

    /** Granted once per workspace, through {@code workspace_members.role_id}. */
    WORKSPACE,

    /**
     * Granted per project, through {@code project_members.role_id} — or, for a member
     * with no explicit row, through the project-access default chain
     * (roles-permissions-proposal §5.2).
     */
    PROJECT
}
