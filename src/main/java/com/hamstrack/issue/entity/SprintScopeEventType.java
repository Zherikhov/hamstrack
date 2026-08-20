package com.hamstrack.issue.entity;

/**
 * The two things that can happen to a sprint's SCOPE (HD-137, reports-proposal §5.2):
 * an issue joined it, or an issue left it.
 *
 * <p>Deliberately closed at two values. "Scope at time T" is the arithmetic
 * {@code count(ADDED <= T) - count(REMOVED <= T)}, so a third member would not be a new
 * feature — it would be a wrong number in every burn-up drawn since. That is why this
 * vocabulary carries a DB {@code CHECK} (V18) where {@code sprints.state} does not.
 *
 * <p>Persisted as {@code VARCHAR(10)} and validated here, never as a PostgreSQL ENUM
 * type (Hibernate 7 + PG enums throw JDBC cast errors on INSERT).
 *
 * <p><strong>What is NOT an event:</strong> re-estimating an issue. A scope change is a
 * MEMBERSHIP change only — a re-estimate changes the height of the scope line from that
 * point on, and is never drawn as a step (§2.3). Conflating the two is the documented
 * reason burndowns get distrusted.
 */
public enum SprintScopeEventType {

    /** The issue joined the sprint — including the commitment batch written by a start. */
    ADDED,

    /**
     * The issue left the sprint — a manual removal, a move to another sprint or the
     * backlog, a completion's carry-over, or a force-delete's detach.
     */
    REMOVED
}
