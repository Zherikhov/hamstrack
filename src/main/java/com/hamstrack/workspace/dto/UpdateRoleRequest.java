package com.hamstrack.workspace.dto;

import jakarta.validation.Valid;
import com.hamstrack.common.util.DisplayText;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code PATCH /api/workspaces/{ws}/roles/{roleId}} — rename, re-describe or re-compose a
 * custom role.
 *
 * <p><strong>{@code scope} is not on this DTO, and its absence is the guard.</strong> Were
 * scope editable, one PATCH would turn every existing assignment of the role into a
 * wrong-scope row — a WORKSPACE role sitting in {@code project_members.role_id}, putting
 * {@code workspace.member.manage} into every holder's {@code ProjectContext} — which is the
 * exact corruption {@code RoleRepository.findAssignable} exists to prevent, delivered
 * wholesale and retroactively. Neither are {@code key}, {@code builtIn} or
 * {@code workspaceId}: all three are identity, not configuration.
 *
 * <p>Every field is a reference type, so the Jackson 3 {@code FAIL_ON_NULL_FOR_PRIMITIVES}
 * trap cannot apply to a partial PATCH — keep it that way if the record grows.
 *
 * <p><strong>No control characters in {@code name} or {@code description}</strong> — see
 * {@link DuplicateRoleRequest}, which states the reasoning once: no log-injection path
 * exists today, and a display string that will eventually reach an email or an export is
 * not where to find out that one appeared. The two fields take different patterns for the
 * reason spelled out there — {@code name} is a label ({@link DisplayText#SINGLE_LINE}),
 * {@code description} is prose typed into a textarea ({@link DisplayText#MULTI_LINE}, which
 * re-admits TAB/LF/CR and nothing else).
 *
 * @param permissions <strong>a full replacement</strong> when present, {@code null} to
 *                    leave the grants alone. It cannot be a delta: {@code RolePermission}
 *                    equality is on the permission alone, so {@code add()}ing a key the
 *                    role already holds is a silent no-op and an {@code ownOnly} toggle
 *                    would simply not persist. An empty list is a real value — a role
 *                    granting nothing is legal
 * @param version     optimistic concurrency, following the shipped {@code IssueService}
 *                    convention: present and stale → 409 "Role was modified by someone
 *                    else"; absent → last writer wins, with {@code @Version} still catching
 *                    a true concurrent flush through
 *                    {@code GlobalExceptionHandler.handleOptimisticLock}
 */
public record UpdateRoleRequest(
        @Size(max = 80) @Pattern(regexp = DisplayText.SINGLE_LINE,
                message = "Name must not contain control characters") String name,
        @Size(max = 500) @Pattern(regexp = DisplayText.MULTI_LINE,
                message = "Description must not contain control characters") String description,
        @Valid @Size(max = 64) List<RolePermissionEntry> permissions,
        Long version
) {}
