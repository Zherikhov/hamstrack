package com.hamstrack.issue.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.entity.IssueFieldValue;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A filled custom field on an issue. Value shape depends on the field type
 * (see the project config's field definitions, which carry type + options).
 */
public record FieldValueResponse(UUID fieldId, JsonNode value) {

    public static List<FieldValueResponse> of(Collection<IssueFieldValue> values) {
        if (values == null) return List.of();
        return values.stream()
                .map(v -> new FieldValueResponse(v.getField().getId(), v.getValue()))
                .toList();
    }
}
