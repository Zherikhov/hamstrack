package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.StatusCategory;

import java.util.UUID;

public record StatusResponse(UUID id, String name, String color, StatusCategory category, short position) {
    public static StatusResponse of(Status s) {
        return new StatusResponse(s.getId(), s.getName(), s.getColor(), s.getCategory(), s.getPosition());
    }
}
