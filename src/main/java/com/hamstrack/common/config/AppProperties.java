package com.hamstrack.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String baseUrl,
        Registration registration
) {
    public record Registration(boolean publicSignupEnabled) {}
}
