package com.hamstrack.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounded mail executor + critical-mail durability knobs (HD-78). Bound under
 * {@code app.mail.*} with a distinct prefix so it never clashes with Boot's own
 * {@code spring.mail.*} {@code MailProperties} auto-config.
 *
 * <p>{@code async.*} drives the dedicated {@code mailExecutor} pool (so a stalled
 * SMTP host can't spawn a thread-per-task or starve other {@code @Async} work).
 * {@code critical.*} drives the retry-then-dead-letter path for account-critical
 * mail (verification + password reset); invite mail stays best-effort.
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailAsyncProperties(
        Async async,
        Critical critical
) {
    public record Async(
            @DefaultValue("2") int corePoolSize,
            @DefaultValue("5") int maxPoolSize,
            @DefaultValue("100") int queueCapacity
    ) {}

    public record Critical(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("2000") long retryBackoffMs
    ) {}
}
