package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.IssueType;

import java.util.UUID;

public record IssueTypeResponse(UUID id, String name, String color, String icon, short position) {
    public static IssueTypeResponse of(IssueType t) {
        return new IssueTypeResponse(t.getId(), t.getName(), t.getColor(), t.getIcon(), t.getPosition());
    }
}
