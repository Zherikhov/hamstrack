package com.hamstrack.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String baseUrl,
        @DefaultValue("noreply@hamstrack.com") String mailFrom,
        Registration registration,
        Legal legal
) {
    public record Registration(boolean publicSignupEnabled) {}

    public record Legal(
            @DefaultValue("true") boolean publicLandingEnabled,
            @DefaultValue("true") boolean termsAcceptanceRequired
    ) {}
}
