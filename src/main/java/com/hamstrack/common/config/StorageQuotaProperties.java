package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * The per-workspace attachment storage ceiling (HD-191 §10.1).
 *
 * <p><strong>Same mechanism in both deployment models, different numbers, config-gated and
 * never forked.</strong> What differs is what the ceiling is protecting. On {@code dc} the
 * operator owns the disk and usually the whole install, signup is locked down by default and
 * there are no strangers — so the number is a safety net against runaway growth: generous
 * enough that no real team meets it by accident, finite enough that a bug or one abusive
 * account cannot fill the volume. On {@code cloud} signup is public, the backend is S3, every
 * byte stored and every request made is billed, and a workspace is the unit a stranger gets for
 * the price of one disposable mailbox — so the ceiling is about money, and the base default is
 * overridden in {@code application-cloud.properties}, mirroring how {@code app.storage.type} is
 * already profile-defaulted.
 *
 * <p><strong>Its own off switch, deliberately NOT {@code app.rate-limit.enabled}</strong>
 * (§10.2). Folding them would mean an operator who wants to remove a disk bound has to disable
 * auth brute-force protection, and an operator debugging a limiter has to remove the disk
 * bound. Two different kinds of control, two switches — and both the
 * {@code application.properties} master-switch block and {@code .env.prod.example} say so where
 * they enumerate the exceptions, which is where the next reader looks.
 *
 * <p><strong>Disabled does not mean unmeasured</strong> (§6.9). With {@link #enabled()} false,
 * usage is still counted by the trigger, still reported by {@code GET …/storage} and still
 * reconciled; nothing is refused. That is {@code RecipientMailThrottle}'s reasoning: a switch
 * that stops the bookkeeping means an instance turning it back on resumes with a blank window,
 * and the operator loses the very number that would tell them what to set.
 *
 * <p><strong>A quota bounds growth and deletes nothing.</strong> Lowering it below current usage
 * refuses new uploads and touches no existing file; reads and downloads are never quota-gated,
 * so a full workspace stays fully readable.
 */
@Validated
@ConfigurationProperties(prefix = "app.storage.quota")
public record StorageQuotaProperties(
        @DefaultValue("true") boolean enabled,
        /*
         * The ceiling, in bytes, per workspace.
         *
         * MUST be >= app.attachments.max-file-size: a quota that can never admit a single legal
         * file is a misconfiguration, not a policy, and it presents to a user as every upload
         * being refused with a message that names two numbers neither of which they can change.
         * Checked at startup by StorageQuotaConsistency.
         *
         * PROVISIONAL, and the fill gauge is what will say so (OQ-D1): there is no billing
         * model, no tier and no measured distribution behind either default. It is one env var.
         *
         * BEFORE RAISING THE QUOTA ON AN EXISTING INSTALL: nothing to do. Before LOWERING it, or
         * before enabling it for the first time on an instance with real content, read
         * OQ-D2 — a quota introduced silently at a value somebody is already past is
         * indistinguishable from an outage, and GET .../storage/projects is the page that says
         * who that is.
         */
        @DefaultValue("100GB") DataSize workspaceBytes,
        /*
         * The share of the quota at which the SPA starts saying so, and the level the fill alert
         * is sized against. It changes no server behaviour at all: nothing is refused, narrowed
         * or slowed at the threshold. It exists so that the first thing a workspace hears about
         * its ceiling is not a refusal.
         */
        @DefaultValue("80") @Min(1) @Max(99) int warnAtPercent,
        /*
         * A Spring cron expression for the reconcile pass (WorkspaceStorageReconciler), or an
         * EMPTY STRING to disable the schedule entirely.
         *
         * Disabling is a real operator choice and it is not silent: the drift gauge then ages
         * and StorageDriftGaugeStale fires, which is the intended signal rather than a
         * side effect. The seeded freshness stamp is what makes that true — a reconciler that
         * never ran must not look like one that ran and found nothing.
         *
         * Nightly to start (OQ-D4). Drift is expected to be zero — the trigger is the mechanism
         * and this is the witness — so if StorageUsageCounterDrift ever fires, the frequency
         * question has answered itself and it is a cron string.
         */
        @DefaultValue("0 20 3 * * *") String reconcileCron
) {}
