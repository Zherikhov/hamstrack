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
 * <p><strong>{@code password} is bounded at 72</strong> — the number of UTF-8 <em>bytes</em>
 * {@code BCryptPasswordEncoder.encode} will hash ({@link com.hamstrack.common.security.PasswordLimits}).
 * It was 100, and 73–100 ASCII characters therefore reached {@code encode}, which threw
 * {@code IllegalArgumentException("password cannot be more than 72 bytes")} that nothing translates:
 * <strong>a 500 on an unauthenticated endpoint, for a password a person could plausibly choose</strong>
 * (HD-171 §4.4). Narrowing locks nobody out — no account can hold a longer password, because the same
 * {@code encode} would have refused to create it.
 *
 * <p><strong>This annotation is half the guard and must not be read as the whole one</strong>, because
 * the two count different things: {@code @Size} counts UTF-16 code units and BCrypt counts bytes, so
 * 72 characters of Cyrillic is 144 bytes and passes here. {@code AuthService.rejectUnencodablePassword}
 * measures the bytes on both writing doors; the literal here stays a bare numeral so that a
 * <em>future</em> column-width scanner can read it — nothing reads this one today.
 * {@code EmailLengthBoundTest} does read {@code max\s*=\s*(\d+)}, but only on declarations it
 * reaches from an {@code @Email} (so it reads the address bound on this record and not the password
 * one). Keeping the form it reads costs nothing and is what makes the field scannable later; a
 * symbolic reference would read as no bound at all. What is meant to police the <em>value</em> is a
 * behavioural test (HD-171 §5.3), not a source scan.
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
        @NotBlank @Size(min = 8, max = 72) String password,
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
