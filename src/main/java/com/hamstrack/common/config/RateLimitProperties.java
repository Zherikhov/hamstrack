package com.hamstrack.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Abuse protection for the authentication endpoints. Two mechanisms:
 * a per-IP fixed window across all sensitive auth endpoints, and a per-account
 * exponential backoff on consecutive failed logins. Counters are in-memory —
 * sufficient for single-node deployments (both DC and the current Cloud);
 * revisit with a shared store (Redis) when Cloud scales horizontally.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        // Per-IP budget per minute shared by login/register/verify/resend/forgot/reset
        @DefaultValue("15") int authIpRequestsPerMinute,
        // Consecutive login failures for one account before backoff kicks in
        @DefaultValue("5") int loginFailureThreshold,
        // First backoff delay; doubles with each further failure
        @DefaultValue("30") long loginBackoffBaseSeconds,
        @DefaultValue("900") long loginBackoffMaxSeconds
) {}
