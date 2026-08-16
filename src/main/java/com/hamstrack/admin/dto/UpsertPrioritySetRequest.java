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
    // Boxed Boolean (not primitive) on purpose — Boot 4 / Jackson 3 enable
    // FAIL_ON_NULL_FOR_PRIMITIVES, so a primitive boolean here 400s ("Failed to
    // read request") whenever the flag is omitted. The canonical constructor
    // coalesces null → false so callers keep the primitive-accessor contract.
    public record Item(@NotNull UUID priorityId, Boolean isDefault) {
        public Item {
            isDefault = isDefault != null && isDefault;
        }
    }
}
