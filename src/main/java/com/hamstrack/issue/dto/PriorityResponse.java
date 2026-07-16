package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Priority;

import java.util.UUID;

public record PriorityResponse(UUID id, String name, String color, String icon, short position) {
    public static PriorityResponse of(Priority p) {
        return new PriorityResponse(p.getId(), p.getName(), p.getColor(), p.getIcon(), p.getPosition());
    }
}
