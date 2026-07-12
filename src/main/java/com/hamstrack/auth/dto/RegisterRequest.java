package com.hamstrack.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(min = 2, max = 100) String displayName,
        // Wrapper, not primitive: Boot enables FAIL_ON_NULL_FOR_PRIMITIVES, so an absent
        // field would be a 400 JSON parse error even when acceptance isn't required.
        // Not @AssertTrue — the requirement is conditional on app.legal.terms-acceptance-required
        Boolean termsAccepted
) {
    public boolean hasAcceptedTerms() {
        return Boolean.TRUE.equals(termsAccepted);
    }
}
