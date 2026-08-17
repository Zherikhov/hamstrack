package com.hamstrack.workspace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param role the workspace role key — {@code "OWNER"} / {@code "ADMIN"} / {@code "MEMBER"}.
 *             A plain string since HD-126 (S3), because the ordinal {@code WorkspaceRole}
 *             enum it used to be is deleted: an enum cannot name a custom role, and S4
 *             makes custom roles assignable here. <strong>The wire values are unchanged</strong>
 *             — Jackson serialised the enum by name, and these are those names — so no
 *             client sees a difference. The one shift is the answer to an unusable value:
 *             Jackson used to reject it with 400, and {@code RoleCatalog.requireAssignable}
 *             now answers <strong>422 "Unknown role"</strong>, which is §12's verdict for
 *             every role reference that cannot be honoured (a foreign custom-role id must
 *             not be distinguishable from a nonsense one).
 *
 *             <p>{@code @Size(max = 40)} matches {@code roles.key VARCHAR(40)}, and it is
 *             here because the enum used to bound this field implicitly: an arbitrary
 *             string now reaches {@code UnknownRoleException}, which echoes it back in the
 *             problem+json {@code detail}. Only a caller who already holds
 *             {@code workspace.member.manage} can reach that echo, so this is a bound and
 *             not a fix — but an unbounded value that is reflected to a client should
 *             never have to rely on who can send it.
 */
public record InviteMemberRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(max = 40) String role
) {}
