package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long a transaction that takes row locks may wait for one (HD-136 review round 4).
 *
 * <p>Consumed by {@code common.persistence.LockTimeout}, which applies it with
 * {@code SET LOCAL} inside the one transaction that asked for it — never globally, and
 * never on the datasource, because Flyway shares that datasource and a migration taking a
 * lock on a large table would start failing on the timeout.
 *
 * <p><strong>A DoS guard, not a mode switch</strong> ({@link BoardProperties}' posture):
 * identical in DC and Cloud, no profile override. The failure it bounds is
 * tenant-independent — a membership removal holds its locks across an unbounded history
 * write, so two overlapping removals of the same busy member can pin connections for as
 * long as the loser is willing to wait, which without this is <em>for ever</em>.
 *
 * <p><strong>Fail fast, never clamp</strong>, and note what the lower bound is really
 * buying: PostgreSQL reads {@code lock_timeout = 0} as <em>disabled</em>, so the one
 * misconfiguration that silently restores the unbounded wait this exists to delete is a
 * plausible-looking zero. {@code @Min} refuses it at startup instead. The upper bound is
 * a minute, far past any human-scale membership edit and well inside a proxy's own read
 * timeout — a value above it would mean the request is dead long before the lock is.
 */
@Validated
@ConfigurationProperties(prefix = "app.locking")
public record LockingProperties(
        /*
         * 3 s: comfortably longer than the whole locked section takes when uncontended
         * (a handful of indexed reads, one bulk UPDATE and one batched insert), and short
         * enough that a pathological pile-up drains rather than exhausts the pool. The
         * loser is not failed permanently — GlobalExceptionHandler.handlePessimisticLock
         * answers 409 with Retry-After, i.e. "this will work in a moment", which is true.
         *
         * PRIMITIVE int ON PURPOSE — do not "tidy" it to Integer. It is the safety net for
         * the blank case: `DB_LOCK_TIMEOUT_MS=` is how an operator ordinarily disables a
         * line in a .env file, and that binds an EMPTY value, not an absent one, so
         * an empty string does not convert to an int and the boot fails loudly. Boxed, it
         * would convert to null, the binder would treat that as unbound and quietly hand
         * back the @DefaultValue — so the operator who thought they had disabled the bound
         * gets 3000 instead, and neither the log nor the config says so. A loud refusal is
         * the better answer to an ambiguous line. .env.prod.example therefore says "comment
         * it out, never blank".
         */
        @DefaultValue("3000") @Min(100) @Max(60000) int lockTimeoutMs
) {}
