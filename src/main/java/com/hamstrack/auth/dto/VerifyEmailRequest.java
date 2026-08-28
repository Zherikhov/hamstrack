package com.hamstrack.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * <strong>The bound comes from the generator, not from the column</strong> (HD-171 §4.4):
 * {@code TokenUtils.generateRawToken} is 32 random bytes rendered as 43 Base64url characters, so
 * 64 is generous and finite. The column behind this field cannot be the thing that refuses —
 * {@code email_verifications.token_hash} is 64 hex characters whatever was submitted, so an
 * unbounded token overflows nothing and merely feeds SHA-256 an input the caller sizes, on an
 * unauthenticated door.
 */
public record VerifyEmailRequest(
        @NotBlank @Size(max = 64) String token
) {}
