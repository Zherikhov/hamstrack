package com.hamstrack.project.dto;

import java.util.UUID;

/**
 * <strong>{@code PATCH /api/workspaces/{ws}/projects/{p}/default-role}</strong> (HD-130,
 * S7 §7.1 P2) — exactly one of two choices, which is the same shape the picker renders.
 *
 * <p>{@code inherit: true} writes {@code NULL}, meaning <em>"fall back to the workspace
 * default"</em>. It is a real choice and not an absence: "this project deliberately follows
 * the workspace" and "this project happens to name the same role the workspace does" are
 * different configurations that diverge the moment the workspace default moves, and S6's
 * {@code resolveDefaultRole(...).origin} already tells them apart client-side.
 *
 * <p>Neither or both → <strong>422</strong> ({@code RoleSelectionException}), never a
 * precedence rule, for the reason that class documents: two fields that mean different things
 * must not be resolved by picking a winner, in the one part of the product where that is a
 * privilege change.
 *
 * <p><strong>{@code Boolean}, never {@code boolean}.</strong> Jackson 3 enables
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so a primitive would 400 the {@code {"roleId": …}}
 * body — which omits it — before any of this is reached.
 */
public record UpdateProjectDefaultRoleRequest(UUID roleId, Boolean inherit) {

    public UpdateProjectDefaultRoleRequest {
        inherit = inherit != null && inherit;
    }

    public boolean inheritsWorkspaceDefault() {
        return inherit;
    }
}
