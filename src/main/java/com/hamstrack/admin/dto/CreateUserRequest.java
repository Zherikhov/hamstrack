package com.hamstrack.admin.dto;

import com.hamstrack.auth.entity.SystemRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin-initiated account creation (DC: the only way in besides the seed admin).
 * No password is set and no email is sent — the response carries a one-time
 * setup link the admin hands over out-of-band. {@code systemRole} defaults to
 * USER when omitted.
 */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 100) String displayName,
        SystemRole systemRole
) {
    public SystemRole roleOrDefault() {
        return systemRole != null ? systemRole : SystemRole.USER;
    }
}
