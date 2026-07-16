package com.hamstrack.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldType;

import java.util.UUID;

public record AdminFieldResponse(
        UUID id, String key, String name, FieldType type,
        JsonNode config, String description,
        boolean archived, UsageInfo usage
) {
    public static AdminFieldResponse of(FieldDef f, UsageInfo usage) {
        return new AdminFieldResponse(f.getId(), f.getKey(), f.getName(), f.getType(),
                f.getConfig(), f.getDescription(), f.getArchivedAt() != null, usage);
    }
}
