package com.hamstrack.auth.dto;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        long expiresIn,   // seconds
        UUID userId,
        String email,
        String displayName
) {}
