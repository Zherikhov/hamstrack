package com.hamstrack.admin.dto;

import java.util.List;
import java.util.UUID;

public record AdminFieldSetResponse(
        UUID id, String name, boolean systemDefault,
        List<Item> items,
        long projectsUsing
) {
    public record Item(AdminFieldResponse field, boolean required, boolean showOnCreate) {}
}
