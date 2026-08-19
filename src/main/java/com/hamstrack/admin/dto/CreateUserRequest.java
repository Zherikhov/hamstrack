package com.hamstrack.admin.dto;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.common.util.DisplayText;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Admin-initiated account creation (DC: the only way in besides the seed admin).
 * No password is set and no email is sent — the response carries a one-time
 * setup link the admin hands over out-of-band. {@code systemRole} defaults to
 * USER when omitted.
 *
 * <p><strong>{@code displayName} carries {@link DisplayText#SINGLE_LINE} because it writes
 * the same {@code users.display_name} column as
 * {@link com.hamstrack.auth.dto.RegisterRequest#displayName()}</strong>, which has had the
 * pattern since round 3. A rule on one of two doors into a column is not a rule: the stored
 * value is indistinguishable afterwards, and it is what heads the sentences the server
 * writes about a privilege ("{name} … is now Team lead in:"), notification subjects, the
 * audit trail and CSV exports — none of which this application renders. Only invisible and
 * reordering characters are rejected, so every real name still passes.
 */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 100) @Pattern(regexp = DisplayText.SINGLE_LINE,
                message = "Display name must not contain control characters") String displayName,
        SystemRole systemRole
) {
    public SystemRole roleOrDefault() {
        return systemRole != null ? systemRole : SystemRole.USER;
    }
}
