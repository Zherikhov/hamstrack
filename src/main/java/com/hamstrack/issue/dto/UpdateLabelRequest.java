package com.hamstrack.issue.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a label (HD-30, §4.3) — {@code null} means "leave unchanged" for
 * every field. Rename/recolor/describe is allowed to a workspace OWNER/ADMIN or the
 * label's own {@code created_by}; everything else is 403.
 *
 * <p>No primitive fields here on purpose: Boot 4 / Jackson 3 enable
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so a primitive in a partial PATCH body 400s
 * whenever the client omits it.
 */
public record UpdateLabelRequest(
        @Size(max = 200) String name,
        @Pattern(regexp = "^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$",
                message = "must be #RRGGBB or #RRGGBBAA") String color,
        @Size(max = 200) String description
) {}
