package com.hamstrack.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Board query bounds (HD-79). {@code GET .../issues} with no {@code size} param
 * returns the board's cards; without a cap it returned EVERY issue in the project —
 * an OOM/latency risk on large projects. {@code maxIssues} caps that response
 * server-side (never client-overridable); the response reports truncation so the SPA
 * can nudge power users to filters / Backlog / Search (all already paginated).
 */
@ConfigurationProperties(prefix = "app.board")
public record BoardProperties(
        @DefaultValue("500") int maxIssues
) {}
