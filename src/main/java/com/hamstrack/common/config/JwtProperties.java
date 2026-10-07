package com.hamstrack.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        @Credential String secret,
        Duration accessTokenExpiration,
        Duration refreshTokenExpiration
) {}
