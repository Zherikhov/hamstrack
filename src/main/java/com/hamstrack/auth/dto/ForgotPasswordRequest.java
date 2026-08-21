package com.hamstrack.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code @Size(max = 255)}: see {@code EmailLengthBoundTest}. */
public record ForgotPasswordRequest(@Email @NotBlank @Size(max = 255) String email) {}
