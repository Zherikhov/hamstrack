package com.hamstrack.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code @Size(max = 255)} on the address is not decoration: see
 * {@link com.hamstrack.admin.dto.CreateUserRequest} and {@code EmailLengthBoundTest} for why
 * every door that carries an address needs it, including the ones that only read.
 */
public record LoginRequest(
        @Email @NotBlank @Size(max = 255) String email,
        @NotBlank String password
) {}
