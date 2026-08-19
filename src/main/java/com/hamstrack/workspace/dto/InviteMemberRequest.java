package com.hamstrack.workspace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * <strong>Exactly one of {@code roleId} and {@code role} must be present</strong> — 422
 * ({@code RoleSelectionException}) for neither and for both.
 *
 * <p>Both fields exist <em>transitionally</em>. S5 has shipped and the invite screen is
 * live, so removing {@code role} in this slice would break the running SPA's invite flow
 * between S4 and S6 — a production regression for the sake of one DTO field, and a slice
 * must be independently deployable. {@code role} is marked deprecated in
 * {@code openapi.yaml} by S8; S6 moves the SPA to {@code roleId}; removal is its own ticket.
 *
 * @param roleId the assignable role's id — <strong>the only way to name a custom
 *               role</strong>, since {@code role} resolves built-ins only. Resolved through
 *               {@code RoleRepository.findAssignable(id, workspaceId, WORKSPACE)}, so a
 *               foreign, wrong-scope or unknown id is one indistinguishable
 *               <strong>422</strong>
 * @param role   the legacy workspace role key — {@code "OWNER"} / {@code "ADMIN"} /
 *               {@code "MEMBER"}. A plain string since HD-126 (S3), because the ordinal
 *               {@code WorkspaceRole} enum it used to be is deleted: an enum cannot name a
 *               custom role. <strong>The wire values are unchanged</strong> — Jackson
 *               serialised the enum by name, and these are those names. An unusable value is
 *               {@code RoleCatalog.requireAssignable}'s <strong>422 "Unknown role"</strong>,
 *               which is §12's verdict for every role reference that cannot be honoured (a
 *               foreign custom-role id must not be distinguishable from a nonsense one).
 *
 *               <p>{@code @Size(max = 40)} matches {@code roles.key VARCHAR(40)}, and it is
 *               here because the enum used to bound this field implicitly: an arbitrary
 *               string now reaches {@code UnknownRoleException}, which echoes it back in the
 *               problem+json {@code detail}. Only a caller who already holds
 *               {@code workspace.member.manage} can reach that echo, so this is a bound and
 *               not a fix — but an unbounded value that is reflected to a client should
 *               never have to rely on who can send it.
 */
public record InviteMemberRequest(
        @Email @NotBlank String email,
        UUID roleId,
        @Size(max = 40) String role
) {}
