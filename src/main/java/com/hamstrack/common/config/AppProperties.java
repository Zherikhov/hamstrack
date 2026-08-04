package com.hamstrack.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String baseUrl,
        @DefaultValue("noreply@example.com") String mailFrom,
        Registration registration,
        Legal legal,
        Demo demo,
        Onboarding onboarding
) {
    public record Registration(boolean publicSignupEnabled) {}

    public record Demo(@DefaultValue("true") boolean seedOnFirstLogin) {}

    // First-login onboarding (choose to create or join a team). Cloud-only:
    // the cloud profile enables it, the base default is off (so DC skips it).
    public record Onboarding(@DefaultValue("false") boolean enabled) {}

    public record Legal(
            @DefaultValue("true") boolean publicLandingEnabled,
            @DefaultValue("true") boolean termsAcceptanceRequired
    ) {}
}
