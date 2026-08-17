package com.hamstrack.project.dto;

import com.hamstrack.project.entity.BoardMode;

/**
 * The <strong>derived</strong> display label for a project's delivery capability set
 * (HD-102, delivery-paths-proposal §2.3). Never stored, never accepted in a request
 * (open question 5): the three capabilities on {@code projects} are the single source
 * of truth and this is a convenience the server computes from them, so drift is
 * impossible by construction.
 *
 * <p>{@link #of} is deliberately the <strong>only</strong> place the table below
 * exists (risk 5, §17): a future capability added without touching this method would
 * silently turn most projects into {@link #CUSTOM} overnight.
 */
public enum DeliveryPreset {
    KANBAN,
    SCRUM,
    RELEASES,
    CUSTOM;

    /**
     * The §2.3 preset table, verbatim — three exact combinations are named, the other
     * five read as {@code CUSTOM} (which is a legal, first-class answer: "Scrum +
     * Releases" is an ordinary way to work and must never be forbidden).
     *
     * <pre>
     * preset    | board  | releases | estimation
     * ----------+--------+----------+-----------
     * KANBAN    | KANBAN | off      | off
     * SCRUM     | SCRUM  | off      | on
     * RELEASES  | KANBAN | on       | off
     * CUSTOM    | any other combination
     * </pre>
     */
    public static DeliveryPreset of(BoardMode board, boolean releases, boolean estimation) {
        if (board == BoardMode.KANBAN && !releases && !estimation) return KANBAN;
        if (board == BoardMode.SCRUM && !releases && estimation) return SCRUM;
        if (board == BoardMode.KANBAN && releases && !estimation) return RELEASES;
        return CUSTOM;
    }
}
