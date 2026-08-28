package com.hamstrack.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * <strong>{@code token} is bounded from its generator</strong> (HD-171 §4.4), exactly as
 * {@link VerifyEmailRequest}'s is: {@code TokenUtils.generateRawToken} yields 43 Base64url
 * characters, and {@code password_resets.token_hash} is 64 hex characters no matter what was
 * submitted — so the column can never refuse an over-long token and the door has to.
 *
 * <p>{@code newPassword} keeps its {@code min}: this door <em>writes</em> a password, so a
 * strength floor belongs on it. {@code LoginRequest.password} deliberately has none, for the
 * opposite reason, spelled out on that record.
 *
 * <p><strong>Its {@code max} is 72 for the same reason {@code RegisterRequest}'s is</strong> — the
 * ceiling {@code BCryptPasswordEncoder.encode} enforces, in UTF-8 <em>bytes</em>
 * ({@link com.hamstrack.common.security.PasswordLimits}). At 100 this door answered
 * <strong>500</strong> to a 73-character ASCII password, and to a 37-character Cyrillic one,
 * because {@code encode} throws above 72 bytes (HD-171 §4.4). {@code @Size} counts UTF-16 units and
 * cannot see the byte cost, so it is paired with
 * {@code AuthService.rejectUnencodablePassword} — which runs <em>before</em> the reset token is
 * marked used, so a refused caller can retry on the same link.
 */
public record ResetPasswordRequest(
        @NotBlank @Size(max = 64) String token,
        @NotBlank @Size(min = 8, max = 72) String newPassword
) {}
