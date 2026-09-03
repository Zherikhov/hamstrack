package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * The two per-principal budgets over the mutating content surface (HD-191 §6.1, §6.2).
 *
 * <p>Until this existed, <strong>the write surface had no budget of any kind</strong>. Three
 * limiters covered reads and authentication — a per-IP auth window, a per-principal reports
 * budget, a per-principal search budget — and the rule they are all instances of is the one
 * CLAUDE.md states: <em>a throttle is earned by the work a handler does, not by where it is
 * mounted.</em> An issue write fans out SSE and notifications, writes history rows and bumps a
 * version; an upload hands 25 MB to S3, where the bill is per byte stored <em>and</em> per
 * request made. A search is throttled and does less.
 *
 * <p><strong>Two denominations, because one does not imply the other.</strong> A request budget
 * does not bound bytes: at any legal request rate an upload of the maximum file size is a
 * different quantity of work from a one-line comment. And a byte budget does not bound
 * requests, because most mutations carry no bytes at all. They are spent in different places
 * for the same reason — the request budget is a path binding in an interceptor, the byte budget
 * needs {@code MultipartFile.getSize()} and is therefore spent inside
 * {@code AttachmentService.upload}.
 *
 * <p><strong>Neither is the storage quota, and neither substitutes for it</strong> (§5.6). These
 * are keyed on the ACTOR, live in memory per node, are denominated per MINUTE and reset every
 * minute; the quota is keyed on the TENANT, lives in PostgreSQL, is denominated in cumulative
 * bytes and never resets. One member can exhaust a shared workspace quota entirely within their
 * own budget — that is the correct outcome, and it is why {@code app.storage.quota.enabled} is
 * a separate switch from {@code app.rate-limit.enabled}.
 *
 * <p>Both are under {@code app.rate-limit.enabled}, the master switch every per-principal
 * budget honours. There is no "unlimited" on either: {@code 0} is out of range and fails
 * startup, which is the posture {@link ReportProperties} and {@link SearchProperties} already
 * take.
 *
 * <p>Identical in {@code dc} and {@code cloud} with no profile override. These bound the
 * INSTANCE — the connection pool, the fan-out, the S3 request count — and an instance is an
 * instance in both models. What differs per model is the storage quota, which is about a disk
 * on one and a bill on the other; see {@link StorageQuotaProperties}.
 */
@Validated
@ConfigurationProperties(prefix = "app.write")
public record WriteProperties(
        /*
         * How many MUTATING requests one principal may make per minute across
         * /api/workspaces/*(/projects/*)/issues/** — issue create/update/delete, comment
         * create/update/delete, attachment upload/delete and rank.
         *
         * ALL mutating methods, not only POST. A budget that covered creation alone would leave
         * the surface half-budgeted, which PlanningThrottleParityTest already names as worse
         * than either whole answer: a client refused on the create retries with the patch. An
         * issue update writes history rows, bumps @Version and fans out SSE and notifications;
         * it is not the cheap half.
         *
         * 180 is 3/s sustained, sized against what the SPA actually does — inline-edit saves on
         * an issue page and a run of board drags, each of which is one PATCH. A human dragging
         * cards does not approach it; a script does immediately.
         */
        @DefaultValue("180") @Min(1) @Max(10_000) int requestsPerMinute,
        /*
         * How many uploaded BYTES one principal may push per minute, summed over the parsed
         * sizes of their uploads — never a client-declared Content-Length.
         *
         * It exists because the request budget does not bound bytes and the quota does not see
         * churn: upload -> delete -> upload leaves the workspace total exactly where it started
         * and bills every PUT and every stored byte in between. That loop is invisible to both
         * other controls and is exactly what this one costs.
         *
         * 250MB/minute is ~12 files at the default 20MB per-file limit — far above any person
         * attaching screenshots to a ticket, and a hard ceiling on the rate at which one account
         * can move volume into the store.
         *
         * MUST be >= app.attachments.max-file-size, or no legal file could ever be uploaded.
         * Checked at startup by StorageQuotaConsistency rather than left to be discovered by
         * the first upload after a deploy.
         */
        @DefaultValue("250MB") DataSize uploadBytesPerMinute
) {}
