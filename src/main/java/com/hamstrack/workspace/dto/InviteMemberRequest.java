package com.hamstrack.workspace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
 * <p>{@code email} is bounded at 255 for the same reason {@code RegisterRequest} bounds
 * its own: {@code workspace_invites.email} is {@code VARCHAR(255)} while {@code @Email}
 * accepts roughly 320 characters, so the overflow would surface as a 500 rather than the
 * 400 it is. See {@code EmailLengthBoundTest}.
 *
 * <p><strong>{@code email}'s local part must be ASCII, and that is a delivery rule rather
 * than a style rule.</strong> The invitation is mailed to the address <em>as folded</em> —
 * the same value the throttle counted and {@code workspace_invites.email} stores — so that
 * the address we bind is the address we write to (HD-190). But that fold is
 * {@code toLowerCase(Locale.ROOT)}, which collapses U+212A KELVIN SIGN onto plain {@code k}
 * and U+212B ANGSTROM SIGN onto {@code å}. Pasting such a local part would therefore send a
 * workspace name and a <em>live join token</em> to a different real person — one whose own
 * account email is folded the same way, matches exactly, and who can accept. A 400 at the
 * boundary costs nobody a deliverable address, because this application has no SMTPUTF8
 * path and such an address was never deliverable as typed; a silent redirect to a stranger
 * has no upside at all. Do <strong>not</strong> "fix" the same hazard by mailing
 * {@code req.email()} instead — counting one address and writing to another is the worse
 * bug, and it is the one this constraint exists to avoid reintroducing.
 *
 * <p>The <em>domain</em> is deliberately left unrestricted: an internationalised domain is
 * carried to its wire form by punycode ({@code MailAddresses.throttleKey}), which is a
 * normalisation rather than a fold onto somebody else's mailbox. So the pattern says only
 * "everything before the last {@code @} is ASCII", and a quoted local part that contains an
 * {@code @} of its own ({@code "a@b"@example.com}, which {@code @Email} accepts) still passes.
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
        @Email @NotBlank @Size(max = 255)
        @Pattern(regexp = "\\p{ASCII}*@[^@]*",
                message = "Email must not contain non-ASCII characters before the @")
        String email,
        UUID roleId,
        @Size(max = 40) String role
) {}
