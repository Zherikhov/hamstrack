package com.hamstrack.auth.dto;

import com.hamstrack.common.util.DisplayText;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * <p><strong>{@code email} is bounded at 255</strong> because that is the width of the
 * column it lands in. {@code @Email} alone permits roughly 320 characters, so without the
 * bound a long-but-well-formed address reaches the INSERT and comes back as a 500 — a
 * validation failure wearing a server-error costume. See {@code EmailLengthBoundTest}.
 *
 * <p><strong>{@code displayName} is a display string</strong>, so it carries
 * {@link DisplayText#SINGLE_LINE} for the same reason the role DTOs do — except that here
 * the reason is already concrete rather than eventual. A display name heads sentences the
 * server writes about a privilege: HD-129's adoption-consent outcome reads
 * "<em>{name} … is now Team lead in:</em>" followed by a project list, and a leading
 * {@code U+202E} reorders the rest of that line, so the name a member reads before
 * consenting is not the name that was stored. It also lands in notification subjects,
 * mention text, the audit trail and CSV exports, none of which this application renders.
 * The pattern rejects only invisible and reordering characters, so every real name — any
 * script, any diacritic — still passes.
 */
public record RegisterRequest(
        @Email @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(min = 2, max = 100) @Pattern(regexp = DisplayText.SINGLE_LINE,
                message = "Display name must not contain control characters") String displayName,
        // Wrapper, not primitive: Boot enables FAIL_ON_NULL_FOR_PRIMITIVES, so an absent
        // field would be a 400 JSON parse error even when acceptance isn't required.
        // Not @AssertTrue — the requirement is conditional on app.legal.terms-acceptance-required
        Boolean termsAccepted
) {
    public boolean hasAcceptedTerms() {
        return Boolean.TRUE.equals(termsAccepted);
    }
}
