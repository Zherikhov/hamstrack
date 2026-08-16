package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Sprint;
import com.hamstrack.issue.entity.SprintState;

import java.util.UUID;

/**
 * A sprint's display summary as embedded in an {@link IssueResponse} and in the
 * completion dialog's target list (HD-22, agile-sprints-proposal §7). Deliberately
 * minimal — id/name plus {@code state} so the SPA can badge an ACTIVE sprint
 * differently from a FUTURE one without a second fetch.
 */
public record SprintRef(UUID id, String name, SprintState state) {

    public static SprintRef of(Sprint sprint) {
        return sprint == null ? null
                : new SprintRef(sprint.getId(), sprint.getName(), sprint.getState());
    }
}
