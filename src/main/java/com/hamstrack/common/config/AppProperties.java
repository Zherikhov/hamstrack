package com.hamstrack.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String baseUrl,
        @DefaultValue("noreply@hamstrack.com") String mailFrom,
        Registration registration
) {
    public record Registration(boolean publicSignupEnabled) {}
}
