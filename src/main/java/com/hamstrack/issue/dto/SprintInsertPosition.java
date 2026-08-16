package com.hamstrack.issue.dto;

/**
 * Where a bulk {@code POST …/sprints/{id}/issues} drops the moved issues inside the
 * target sprint's shared rank space (HD-22, agile-sprints-proposal §4.4).
 *
 * <p>Sections share ONE rank space ({@code issues.position}) — {@code sprint_id} is a
 * filter, not a separate order — so "top"/"bottom" means "before the section's
 * current first row" / "after its current last row", and the moved issues keep their
 * relative order among themselves.
 */
public enum SprintInsertPosition {
    TOP,
    /** The default: adding work to a sprint is not a priority statement. */
    BOTTOM
}
