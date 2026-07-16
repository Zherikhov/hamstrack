package com.hamstrack.admin.dto;

import com.hamstrack.issue.dto.PriorityResponse;

import java.util.List;
import java.util.UUID;

public record AdminPrioritySetResponse(
        UUID id, String name, boolean systemDefault,
        List<Item> items,
        long projectsUsing
) {
    public record Item(PriorityResponse priority, boolean isDefault) {}
}
