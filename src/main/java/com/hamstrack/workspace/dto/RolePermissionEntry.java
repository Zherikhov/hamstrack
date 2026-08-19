package com.hamstrack.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One grant on a role, in the <strong>object form</strong>: {@code {"key":"comment.edit",
 * "ownOnly":true}}.
 *
 * <p><strong>Deliberately not {@code myPermissions}' flat {@code "comment.edit:own"} wire
 * form, and the two must not be converged.</strong> The role editor needs the own toggle
 * as a field it can render as a checkbox and send back; the suffix encoding exists so a
 * client gate can do one string comparison against a flat list. Both are produced from the
 * same stored grants, so they cannot disagree — but a single shape would make one of the
 * two consumers parse.
 *
 * <p><strong>{@code ownOnly} is boxed on purpose.</strong> Jackson 3 (Boot 4) enables
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so a primitive {@code boolean} here would make
 * {@code {"key":"issue.edit"}} — the overwhelmingly common shape, since most grants are
 * unrestricted — fail deserialization with {@code 400 "Failed to read request"}. The
 * canonical constructor coalesces null to {@code false}, which is also the semantic
 * default: an unqualified grant is the unrestricted one.
 *
 * @param key     a {@link com.hamstrack.common.security.Permission} key. Unknown here is
 *                <strong>422</strong> — the write-path half of the rule whose read-path
 *                half is {@code PermissionConverter}'s log-and-drop. Bounded to
 *                {@code role_permissions.permission VARCHAR(64)} because the value is
 *                echoed back in the problem+json detail.
 */
public record RolePermissionEntry(
        @NotBlank @Size(max = 64) String key,
        Boolean ownOnly
) {
    public RolePermissionEntry {
        ownOnly = ownOnly != null && ownOnly;
    }

    /** Never null after construction — see the canonical constructor. */
    public boolean isOwnOnly() {
        return Boolean.TRUE.equals(ownOnly);
    }
}
