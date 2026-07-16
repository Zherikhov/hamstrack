package com.hamstrack.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Full replacement: items in display order; exactly one should be the default. */
public record UpsertPrioritySetRequest(
        @NotBlank @Size(max = 100) String name,
        @NotEmpty @Valid List<Item> items
) {
    public record Item(@NotNull UUID priorityId, boolean isDefault) {}
}
