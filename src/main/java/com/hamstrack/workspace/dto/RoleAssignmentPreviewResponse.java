package com.hamstrack.workspace.dto;

/**
 * The body of {@code POST /roles/preview}: the derived {@code assignment} block on its own,
 * wrapped rather than returned bare so the shape matches the same key on
 * {@link RoleResponse} and a client renders one component for both.
 */
public record RoleAssignmentPreviewResponse(RoleAssignmentView assignment) {
}
