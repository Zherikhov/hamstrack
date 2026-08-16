package com.hamstrack.issue.dto;

/**
 * Where the unfinished issues of a completing sprint go (HD-22,
 * agile-sprints-proposal §4.4). One dialog, one decision — deliberately not a
 * multi-step sprint-report wizard.
 */
public enum UnfinishedDisposition {

    /** {@code sprint_id := null}; the rank is preserved, so the carried-over items keep their order. */
    BACKLOG,

    /**
     * Move them to another sprint, named by {@code targetSprintId}. The target must be
     * a <strong>FUTURE</strong> sprint of the same project — not an ACTIVE one (there
     * is none: the sprint being completed is it) and not a COMPLETED one.
     */
    SPRINT
}
