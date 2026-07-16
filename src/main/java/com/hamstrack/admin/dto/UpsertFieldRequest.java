package com.hamstrack.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.entity.FieldType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * key blank on create = derived from the name; immutable afterwards, like the
 * type. config: {"options":[{id,label,color}]} for selects, {"min","max"} for
 * numbers.
 */
public record UpsertFieldRequest(
        @NotBlank @Size(max = 100) String name,
        @Pattern(regexp = "[a-z0-9_]*") @Size(max = 50) String key,
        @NotNull FieldType type,
        JsonNode config,
        @Size(max = 500) String description
) {}
