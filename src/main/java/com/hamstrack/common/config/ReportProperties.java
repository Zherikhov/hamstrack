package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds on every report (reports-proposal §5.3) — how much history one report may
 * span, and how many issue rows one report may materialise.
 *
 * <p><strong>Identical in {@code dc} and {@code cloud}, with no profile override.</strong>
 * Reporting depth is a product property, not a plan property: if it were ever limited
 * per deployment it would be by lowering a number, never by a second code path (§8).
 *
 * <p><strong>Fail fast, never clamp</strong> ({@code @Validated}, the posture
 * {@link BoardProperties} and {@link AgileProperties} take). And the same rule applies
 * one level up, to the caller: a report window wider than {@link #maxWindowDays()} is a
 * <strong>400 naming the cap</strong>, never a silently narrowed window. An undisclosed
 * clamp is precisely how a reporting feature earns the complaint the whole proposal is
 * organised around — <em>"these numbers don't match what I expected"</em> (§1.5) — so the
 * cap is stated in the error and echoed in every response's {@code meta}.
 */
@Validated
@ConfigurationProperties(prefix = "app.reports")
public record ReportProperties(
        /*
         * The widest window any report accepts, in days, INCLUSIVE of both endpoints
         * (2026-01-01..2026-01-01 is one day, not zero). A wider request is refused
         * with the cap named.
         *
         * The @Max is not in §5.3, which only asks for @Min. It is here because
         * maxWindowDays is the ONLY thing bounding the flow report's response size:
         * at interval=DAY the bucket count IS the window length, so an operator who
         * typed 1000000 would get a million-element JSON array per request and
         * discover it under load rather than at startup. 3650 (ten years) is far past
         * any real reporting window and still a 3651-element series at worst.
         */
        @DefaultValue("365") @Min(1) @Max(3650) int maxWindowDays,
        /*
         * The most issue ROWS one report may materialise before it reports itself
         * truncated (`meta.truncated`, `meta.cap`). It does not bite on the flow
         * report, which aggregates in PostgreSQL and returns one row per bucket — see
         * FlowReportService#meta, which explains why reporting `truncated: false`
         * there is a fact and not a stub. It exists now because `meta` is a SHARED
         * contract established by this slice and consumed unchanged by the row-level
         * reports (cycle time, aging WIP) in R3, where it is load-bearing.
         *
         * @Max for the same reason as above: this number is an allocation budget.
         */
        @DefaultValue("20000") @Min(1) @Max(200_000) int maxRows,
        /*
         * How many report requests ONE PRINCIPAL may make per minute across the whole
         * reports surface (HD-28 R1 round 2, item 1). Not in §5.3 either, and it is the
         * only thing bounding the WORK this feature does — max-window-days bounds the
         * response array, not the cost of producing it. The opening balance is
         * O(project history) by design, `Cache-Control: private` means no shared cache
         * ever absorbs a repeat, and the auth rate limiter covers six POST-only auth
         * URLs. Without this an authenticated member in a loop is a connection-pool
         * exhaustion with no rate limit in front of it.
         *
         * 60 is roughly one request per second sustained, which no human interaction
         * with a chart approaches (the SPA also de-duplicates through its query cache)
         * and which still leaves a full dashboard of six reports plus filter changes
         * comfortable. Refusing past it is a 429 + Retry-After, never a wrong number.
         *
         * There is no "unlimited": 0 is out of range and fails startup. The off switch
         * is app.rate-limit.enabled, which is the master switch for every in-memory
         * limiter in the app and is documented as such.
         */
        @DefaultValue("60") @Min(1) @Max(10_000) int requestsPerMinute
) {}
