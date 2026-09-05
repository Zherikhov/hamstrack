package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long any one statement of an application transaction may run (HD-151).
 *
 * <p>Consumed by {@code common.persistence.BoundedJpaTransactionManager}, which applies it with
 * {@code SET LOCAL} to <strong>every</strong> transaction the application opens — not to a list
 * of expensive call sites. A lock wait is structural and can be found by reading the code, which
 * is why {@link LockingProperties} can be applied by opt-in; a slow statement is
 * <em>volumetric</em>, its cost is a function of how much a tenant has accumulated, and no
 * static property of a query proves it fast. An opt-in list would therefore describe our
 * measurement history rather than the risk.
 *
 * <p>Not on the datasource, for the reason {@code LockTimeout} gives at length: Flyway shares
 * that datasource and a migration legitimately runs for minutes. The transaction manager is
 * already the line between application work and Flyway's, so "everything the application does is
 * bounded, migrations are not" is expressible with no list at all.
 *
 * <p><strong>A DoS guard, not a mode switch</strong> ({@link BoardProperties}' posture):
 * identical in {@code dc} and {@code cloud}, no profile override. A deployment that wants a
 * different bound sets {@code DB_STATEMENT_TIMEOUT_MS} — visibly, in the one place an operator
 * reads. A per-profile default would be a second, invisible knob, and a per-tenant one would be
 * a licence check wearing a resource guard's clothes.
 *
 * <p><strong>Fail fast, never clamp</strong>, and note what the lower bound is really buying:
 * PostgreSQL reads {@code statement_timeout = 0} as <em>disabled</em>, so the one
 * misconfiguration that silently restores the unbounded statement this exists to delete is a
 * plausible-looking zero. {@code @Min} refuses it at startup instead. There is a second
 * constraint this record cannot express, because it spans two records — the value must stay
 * comfortably above {@code app.locking.lock-timeout-ms} or the lock bound becomes dead
 * configuration; {@code DatabaseTimeoutConsistency} enforces it at startup and explains why.
 */
@Validated
@ConfigurationProperties(prefix = "app.persistence")
public record StatementTimeoutProperties(
        /*
         * 10 s, and each clause below is a constraint rather than a preference:
         *
         *  - ABOVE app.locking.lock-timeout-ms (3000). Mandatory. statement_timeout measures
         *    total statement time INCLUDING time spent waiting for a lock, so a statement bound
         *    at or below the lock bound always fires first — which would silently convert the
         *    retryable "409 + Retry-After" lock contract into a 422 that is not retryable.
         *    PostgreSQL's own docs say as much under lock_timeout. Enforced by
         *    DatabaseTimeoutConsistency, which requires a 2x margin so a transaction that waited
         *    nearly the whole lock budget still has time to do its work.
         *  - ~100x an ordinary request, so it fires on pathology and not on a cold cache.
         *
         * The pool's ACQUISITION bound is not a term in this number, in either direction: it is a
         * queueing budget (how long a request waits for a connection), while this is a bound on
         * work. A third clause here used to derive this value from it, and that clause is what
         * closed the family into a circle. DatabaseTimeoutConsistency owns the one relation this
         * bound has to the rest of the family, and states it in one place; see it, and ADR-0034,
         * rather than restating a version of it here.
         *
         * PRIMITIVE int ON PURPOSE — do not "tidy" it to Integer. `DB_STATEMENT_TIMEOUT_MS=` is
         * how an operator ordinarily disables a line in a .env file, and that binds an EMPTY
         * value, not an absent one, so an empty string does not convert to an int and the boot
         * fails loudly. Boxed, it would convert to null, the binder would treat that as unbound
         * and quietly hand back the @DefaultValue — so the operator who thought they had
         * disabled the bound gets 10000 instead, and neither the log nor the config says so.
         * .env.prod.example therefore says "comment it out, never blank".
         *
         * Max 10 minutes rather than unbounded: above that the request is long dead at every
         * intermediary, and what is left to bound is the CONNECTION hold, which this setting
         * does not bound at all (see BoundedJpaTransactionManager).
         */
        @DefaultValue("10000") @Min(1000) @Max(600000) int statementTimeoutMs
) {}
