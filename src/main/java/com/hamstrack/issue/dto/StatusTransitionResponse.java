package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.StatusTransition;

import java.util.UUID;

public record StatusTransitionResponse(
        UUID id,
        UUID fromStatusId,
        String fromStatusName,
        UUID toStatusId,
        String toStatusName
) {
    public static StatusTransitionResponse of(StatusTransition t) {
        return new StatusTransitionResponse(
                t.getId(),
                t.getFromStatus().getId(),
                t.getFromStatus().getName(),
                t.getToStatus().getId(),
                t.getToStatus().getName()
        );
    }
}
