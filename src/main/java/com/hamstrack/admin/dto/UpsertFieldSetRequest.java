package com.hamstrack.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Full replacement: items in display order. Empty items = a "no fields" set. */
public record UpsertFieldSetRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Valid List<Item> items
) {
    public record Item(@NotNull UUID fieldId, boolean required, boolean showOnCreate) {}
}
