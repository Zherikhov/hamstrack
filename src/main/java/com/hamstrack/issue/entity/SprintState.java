package com.hamstrack.issue.entity;

/**
 * The lifecycle of a {@link Sprint} (HD-22, agile-sprints-proposal §4.1):
 *
 * <pre>
 *   (none) ──POST /sprints──▶ FUTURE ──/start──▶ ACTIVE ──/complete──▶ COMPLETED
 * </pre>
 *
 * <p>One-way on purpose. <strong>Re-open is NOT allowed</strong>: a completion is a
 * reported event (done vs carried-over counts were handed to the user and the
 * unfinished issues were already moved elsewhere), so re-opening would invalidate
 * that report and re-open the one-active race. The recovery path is "create a new
 * sprint and move the issues back" — two clicks, no ambiguity.
 *
 * <p>Persisted as {@code VARCHAR(10)} and validated here, never as a PostgreSQL
 * ENUM type (Hibernate 7 + PG enums throw JDBC cast errors on INSERT).
 */
public enum SprintState {

    /** Planning bucket. Any number per project, bounded by {@code app.agile.max-open-sprints-per-project}. */
    FUTURE,

    /**
     * At most ONE per project — enforced in the DB by the partial unique index
     * {@code sprints_one_active_per_project_uk}, not only in Java. {@code startAt}
     * is non-null (see {@code sprints_active_ck}).
     */
    ACTIVE,

    /** Terminal. {@code completedAt} is non-null (see {@code sprints_completed_ck}). */
    COMPLETED
}
