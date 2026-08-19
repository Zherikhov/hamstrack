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
         *
         * The number an operator reads here is ROWS; what actually constrains it is
         * BYTES, and nothing on the configuration page relates the two — so the
         * arithmetic is written down, the way issue_key VARCHAR(40) carries its sum.
         * Per shipped row, at the peak of one request, roughly:
         *
         *   ~980 B  the JDBC Object[] and the values hanging off it — worst-case
         *           title (issues.title is VARCHAR(500), one byte per char under
         *           compact strings) dominates, plus the key, two UUIDs, two
         *           OffsetDateTimes and the numbers;
         *   ~140 B  the DTO built beside it (AgingItem / CycleTimeItem) — strings and
         *           UUIDs are shared with the array, its own boxed doubles are not;
         *   ~750 B  the serialized JSON, because a @ResponseBody is buffered whole
         *           rather than streamed.
         *   ------
         *   ~1.9 KB per row worst case (~0.6 KB with realistic 60-character titles),
         *           and all three copies ARE alive together: the full result list is
         *           materialised, then mapped, then serialized.
         *
         * So maxRows is really "megabytes of transient heap ÷ ~1.9". At the old
         * ceiling of 200 000 that is ~380 MB for ONE authenticated GET, so an operator
         * who stayed inside the documented range could OOM the instance with a single
         * request. At 50 000 it is ~95 MB.
         *
         * The reference heap that ceiling is sized against is 512 MB, and that is an
         * ASSUMPTION, not a property of this deployment. Nothing in the repository
         * bounds the heap: the Dockerfile ends in a bare `java -jar` with no -Xmx and
         * no -XX:MaxRAMPercentage, and docker-compose.prod.yml sets no memory limit —
         * so the JVM takes ~25% of whatever HOST RAM it detects and the real budget
         * floats with the machine. At the ~1 GB host docs/self-hosting.md tells
         * operators to budget, that is a ~256 MB heap and 50 000 rows is a large
         * fraction of it; on an 8 GB host the same ceiling is conservative by roughly
         * 4x. HD-152 configures a heap bound and is what turns this assumption into a
         * fact — treat it as a prerequisite of this rationale rather than as
         * independent hardening, and until it lands, size maxRows against the heap you
         * actually get rather than against the number written here.
         *
         * No claim is made about how many of these fit at once, because nothing bounds
         * concurrency: the throttle bounds request RATE per principal and imposes no
         * concurrency bound at all — N principals asking together is N of these live
         * together, on whatever heap the host happens to give.
         *
         * The default of 20 000 (~38 MB worst case, ~12 MB typical) is unchanged and
         * is not the footgun; the ceiling was.
         */
        @DefaultValue("20000") @Min(1) @Max(50_000) int maxRows,
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
