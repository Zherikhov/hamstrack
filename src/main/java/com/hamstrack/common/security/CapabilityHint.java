package com.hamstrack.common.security;

/**
 * The delivery capability a {@link Permission} is <em>about</em> — a hint for the role
 * editor ("this only matters for projects that plan in sprints"), and
 * <strong>never</strong> a check.
 *
 * <p><strong>Rule P3 (roles-permissions-proposal §5.3, and Rule A of
 * {@code docs/design/delivery-paths-proposal.md}):</strong> a capability gates the UI,
 * a permission gates the API, and the two must never be conflated. {@code sprint.manage}
 * is checked on {@code POST /sprints} regardless of whether the project's {@code board}
 * capability is {@code SCRUM}; a Kanban project still returns 403-or-200 on the strength
 * of the permission alone. Nothing in this package may ever read a
 * {@code projects.board_mode} / {@code releases_enabled} / {@code estimation_enabled}
 * column to decide an authorization outcome.
 *
 * <p>Only the two capabilities that actually have a dedicated permission appear here.
 * There is deliberately no {@code ESTIMATION} constant: story points are covered by
 * {@code issue.edit} and a separate {@code estimate.set} permission was rejected (§6.3)
 * — "who estimates" is a team norm, not an access boundary.
 */
public enum CapabilityHint {

    /** {@code projects.board_mode} — sprint planning. */
    BOARD,

    /** {@code projects.releases_enabled} — versions and release cutting. */
    RELEASES
}
