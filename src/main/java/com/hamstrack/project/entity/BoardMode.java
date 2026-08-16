package com.hamstrack.project.entity;

/**
 * How a project's board renders (HD-22, agile-sprints-proposal §3.5). A
 * <strong>presentation switch, not a permission</strong>: the sprint API works
 * identically in both modes (a Kanban team may still plan one iteration), and only
 * two things change on screen —
 *
 * <ul>
 *   <li><strong>Board:</strong> {@link #SCRUM} scopes it to the ACTIVE sprint and
 *       shows the sprint header; {@link #KANBAN} is the pre-0.13.0 behaviour,
 *       unchanged.</li>
 *   <li><strong>Backlog:</strong> sprint sections render when the mode is
 *       {@code SCRUM} <em>or</em> the project already has a non-completed sprint, so
 *       a pure-Kanban project that never created one sees no Scrum vocabulary.</li>
 * </ul>
 *
 * <p>Persisted as {@code VARCHAR(10)} and validated here, never as a PostgreSQL ENUM
 * type (Hibernate 7 + PG enums throw JDBC cast errors on INSERT).
 */
public enum BoardMode {
    KANBAN,
    SCRUM
}
