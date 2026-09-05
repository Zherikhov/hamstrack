package com.hamstrack.common.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * <strong>The one place the database timeout family is derived, and the boot refuses a
 * configuration in which the three bounds contradict each other</strong> (HD-151, HD-233).
 *
 * <h2>The family, one clause per constraint</h2>
 * Three numbers, each with its own subject, and the relation between them stated so that it
 * survives any of the three changing:
 *
 * <blockquote>
 * The <strong>lock</strong> bound ({@code app.locking.lock-timeout-ms}) is how long a transaction
 * may wait for a <strong>row</strong>. The <strong>statement</strong> bound
 * ({@code app.persistence.statement-timeout-ms}) is how long one statement may <strong>run</strong>,
 * and must be at least twice the lock bound because it counts that wait. The
 * <strong>acquisition</strong> bound ({@code spring.datasource.hikari.connection-timeout}) is how
 * long a request may wait for a <strong>connection</strong>, and must be at least the lock bound,
 * because a connection held by a lock-waiting transaction is legitimately unavailable for exactly
 * that long. None of the three is derived from the size of the pool, and the acquisition bound is
 * not derived from the statement bound in either direction.
 * </blockquote>
 *
 * <p>The shipped values are <strong>3000 / 10000 / 3000</strong>. Two of them did not move when
 * the third was chosen, and that is a decision rather than an omission: the acquisition bound is a
 * <em>queueing</em> budget — a thread waiting for a connection does no work, it holds a Tomcat
 * worker and, inside an {@code AfterCommit} effect, a connection as well — while the statement
 * bound is a bound on <em>work</em> and is sized against the workload. Its floor is the longest
 * hold this product actually bounds, which is a lock wait; its ceiling is the price of a parked
 * worker, which {@code app.expensive-read.acquire-wait-ms} already prices at ~2 thread-seconds per
 * refused request. Hikari's own floor is 250 ms and is never binding here. Full argument:
 * ADR-0034, {@code docs/design/database-timeout-family-proposal.md}.
 *
 * <p><strong>What is NOT a term in any of this: "roughly a third of Hikari's default".</strong>
 * The statement bound was once derived that way, from a number nobody had chosen. That clause
 * closed the family into a circle — the statement bound justified by the acquisition bound, the
 * acquisition bound then forbidden from moving because the statement bound depended on it — and it
 * is deleted rather than re-scaled. A property source that wants to state the relation its bound
 * has points here; it does not restate a version of it.
 *
 * <h2>Three hard rules and two soft ones, and the register of each is deliberate</h2>
 * <ul>
 *   <li><strong>H-1: statement ≥ 2 × lock.</strong> A boot failure, because below it a documented
 *       status contract silently inverts (see {@link #check()}).</li>
 *   <li><strong>H-2: the acquisition bound may not be 0, blank or below Hikari's floor —
 *       and the bound the POOL ends up holding must be the one that was checked.</strong>
 *       Hikari maps {@code 0} to {@code Integer.MAX_VALUE} — about 24.8 days, i.e. <em>no
 *       bound</em>. That is the identical plausible-looking-zero trap {@code @Min} refuses for the
 *       other two members of the family, and it is the only one Hikari will not refuse for us:
 *       below 250 ms it throws, at 0 it accepts in silence. Reachable only because
 *       {@link TheBoundsAreCheckedBeforeThePoolIsBuilt} makes the pool wait for this check —
 *       see there, it was measured rather than assumed. The second clause is
 *       {@link ThePoolHoldsTheBoundThatWasChecked}, and it exists because the first one can be
 *       defeated by a legal-looking <em>name</em> rather than by a value: a rule can only refuse
 *       what it reads, so what the pool holds is what decides. It checks
 *       {@code validation-timeout} against the same number for the same reason — the shipped file
 *       spells one variable into both Hikari lines, and that is the one of the two no other rule
 *       reads back.</li>
 *   <li><strong>H-3: the shutdown residue write must fit inside the stop grace, acquisition
 *       included.</strong> {@code MailAsyncProperties.Async.isShutdownWithinTheStopGrace()} is the
 *       same relation at binding time, minus the one term a nested record cannot see. Stated in
 *       both places because an operator must never get two unrelated-looking refusals for one
 *       misconfiguration.</li>
 * </ul>
 *
 * <p>The two soft rules WARN rather than refusing, because each is a sizing judgement about a
 * deployment this file cannot see the context for: <strong>acquisition ≥ lock</strong> (raising
 * {@code DB_LOCK_TIMEOUT_MS} without raising the acquisition bound with it converts ordinary row
 * contention into pool refusals) and <strong>statement ≤ stop grace</strong> (a statement that
 * outlasts the grace the platform gives the process cannot finish inside a shutdown anyway, so a
 * bound above it is a bound the environment does not honour). Both are silent at the shipped
 * defaults, and a default boot that logged a sizing complaint about itself is how a log stops
 * being read.
 *
 * <p>A {@code @Component} with {@code @PostConstruct} rather than an {@code @AssertTrue} because
 * the values live in different records ({@code AgileProperties.isPlanningViewBounded} is the
 * precedent for the joint check, and is the shape to copy when both operands are in one record).
 * The outcome is identical — the context fails to start — and the message is the documentation.
 *
 * <h2>A rule whose worth is a MOMENT owns its own eagerness</h2>
 * Every rule in this file is a rule about <em>when</em> it decides: before the pool is built
 * ({@link TheBoundsAreCheckedBeforeThePoolIsBuilt}) or before the connector opens
 * ({@link ThePoolHoldsTheBoundThatWasChecked}). A callback runs at the moment its bean is
 * <em>instantiated</em>, and that moment is not a property of the bean's code — it is a property of
 * its <em>definition</em>, which is editable from outside this source tree.
 * {@code spring.main.lazy-initialization=true} ({@code SPRING_MAIN_LAZY_INITIALIZATION}, which
 * flows through {@code .env} like every other {@code SPRING_*} name) defers a plain
 * {@code @Component}, and a deferred startup rule does not merely fire late — for a bean nothing
 * else injects, it never fires at all, with no refusal, no WARN and no log line, which is the same
 * <em>fails open in silence</em> shape the seal below exists to close, arriving by a property
 * rather than by a value.
 *
 * <p>The property, stated once for anything added here: <strong>a bean whose value is a moment is
 * eager for a reason a reader can name; a bean whose value is a return value needs no reason.</strong>
 * The reasons differ between the beans in this file, and that is because the beans genuinely
 * differ, not out of inconsistency:
 * <ul>
 *   <li><strong>{@code @Lazy(false)}, on this class.</strong> Needed, because a plain
 *       {@code @Component} is exactly what the property defers, and because the alternative is
 *       <em>borrowed</em> eagerness — without the pin this bean is instantiated under the property
 *       only as a constructor dependency of the seal, and (in a servlet application) as an argument
 *       to the {@code DatabaseBusyFilter} registration the web server forces into existence. An
 *       injection edge is an eagerness one refactor away, and H-1/H-2/H-3 would then stop refusing
 *       with nothing to notice by.
 *       {@code LazyInitializationBeanFactoryPostProcessor.postProcess} returns early on any
 *       definition whose lazy-init flag is already set, so the property can no longer reach it;
 *       {@link com.hamstrack.common.seed.DataSeeder} is the precedent.</li>
 *   <li><strong>Nothing at all, on {@link ThePoolHoldsTheBoundThatWasChecked}</strong>, and that is
 *       a checked fact rather than an oversight: {@code LazyInitializationBeanFactoryPostProcessor}
 *       adds {@code LazyInitializationExcludeFilter.forBeanTypes(SmartInitializingSingleton.class)}
 *       to its own filters, and says why in its class javadoc — "to ensure that their callback
 *       method is invoked". So the property cannot reach a {@code SmartInitializingSingleton};
 *       an explicit {@code @Lazy} on the class still can, because an explicitly set flag wins over
 *       the exclusion. A redundant pin there would tell the next reader that the exclusion is not
 *       there. Verified against spring-boot 4.1.0 and sealed behaviourally by
 *       {@code PoolBoundSealBootTest.theSealStillRefusesUnderGlobalLazyInitialization}, which is
 *       what keeps this a fact and not a belief about somebody else's code.</li>
 *   <li><strong>Nothing at all, on {@link TheBoundsAreCheckedBeforeThePoolIsBuilt}</strong>: a
 *       {@code BeanFactoryPostProcessor} is instantiated by {@code invokeBeanFactoryPostProcessors},
 *       before any lazy flag has been applied to anything.</li>
 * </ul>
 * The request-time beans that consume {@link #acquisitionBoundMs()} ({@code DatabaseBusyFilter},
 * {@code GlobalExceptionHandler}) need no reason, because their moment is a request.
 *
 * @see PoolShareConsistency the sibling that owns <em>occupancy</em> rather than duration
 */
@Component
@Lazy(false)
@RequiredArgsConstructor
@Slf4j
public class DatabaseTimeoutConsistency {

    /** The statement bound must be at least this many times the lock bound. */
    private static final int MARGIN = 2;

    /** The one spelling of the key, shared by the check and the refusal that quotes it. */
    static final String ACQUISITION_PROPERTY = "spring.datasource.hikari.connection-timeout";

    /**
     * The second Hikari line that reads {@link #ACQUISITION_ENV_VAR}. Not a fourth bound — the same
     * number spelled twice, as {@code application.properties} says next to it — and named here only
     * so the seal can report which of the two lines diverged.
     */
    static final String VALIDATION_PROPERTY = "spring.datasource.hikari.validation-timeout";

    /** What an operator actually types, which is what a refusal has to name. */
    static final String ACQUISITION_ENV_VAR = "DB_CONNECTION_TIMEOUT_MS";

    /**
     * Hikari's own floor, verified against {@code HikariConfig}: {@code setConnectionTimeout}
     * throws below {@code SOFT_TIMEOUT_FLOOR}. Never binding at any value this product ships, and
     * named here so the refusal an operator meets comes from the check that knows their variable's
     * name rather than from a pool that does not.
     */
    static final long HIKARI_ACQUISITION_FLOOR_MS = 250;

    /**
     * Hikari's default, which the shipped {@code application.properties} now <strong>overrides</strong>
     * — reaching this fallback means that line was removed. It is not a value this product chooses;
     * it is the value it inherits when its own choice has been deleted.
     */
    static final long HIKARI_DEFAULT_CONNECTION_TIMEOUT_MS = 30_000;

    private final StatementTimeoutProperties statementTimeout;
    private final LockingProperties locking;
    private final MailAsyncProperties mail;
    private final Environment environment;

    /**
     * The acquisition bound in force, resolved once at startup from the same key Hikari reads.
     * Published because the refusal a failed acquisition gets has to name how long the request was
     * allowed to wait, and there must be exactly one answer to that.
     */
    private long acquisitionBoundMs;

    @PostConstruct
    void check() {
        int statementMs = statementTimeout.statementTimeoutMs();
        int lockMs = locking.lockTimeoutMs();
        long acquisitionMs = resolveAcquisitionBound();
        this.acquisitionBoundMs = acquisitionMs;

        if (statementMs < MARGIN * lockMs) {
            throw new IllegalStateException(
                    "app.persistence.statement-timeout-ms (" + statementMs + ") must be at least "
                    + MARGIN + "x app.locking.lock-timeout-ms (" + lockMs + "), i.e. at least "
                    + (MARGIN * lockMs) + ". PostgreSQL applies both bounds to the same statement "
                    + "and statement_timeout counts the lock wait too, so the smaller one always "
                    + "fires first: a statement bound at or below the lock bound makes the lock "
                    + "bound dead configuration and silently turns every lock-wait timeout — today "
                    + "a retryable 409 with Retry-After — into a 422 that is not retryable. Raise "
                    + "DB_STATEMENT_TIMEOUT_MS or lower DB_LOCK_TIMEOUT_MS.");
        }
        refuseAShutdownThatOutrunsTheStopGrace(acquisitionMs);

        // Only after every hard rule passes: a boot that is about to abort should not also print
        // sizing advice about a configuration it is refusing.
        warnIfAWaiterGivesUpBeforeAHolderMust(lockMs, acquisitionMs);
        warnIfTheBoundOutlastsTheGrace(statementMs);
    }

    /**
     * How long a request may wait for a connection, in milliseconds — the number the
     * {@code 503 DATABASE_BUSY} refusal reports and the WARN beside it names.
     */
    public long acquisitionBoundMs() {
        return acquisitionBoundMs;
    }

    /**
     * <strong>H-2 — the acquisition bound is read as a string, through the same relaxed binding
     * Hikari's own value goes through.</strong>
     *
     * <p><em>As a string</em>, because every failure mode of this one value is a value that
     * <em>binds</em>: {@code 0} binds and means no bound at all, a blank binds and fails conversion
     * somewhere else with a message about a pool rather than about the line an operator edited, and
     * a below-floor value binds far enough for Hikari to throw an {@code IllegalArgumentException}
     * naming neither the property nor the variable. Reading the raw text is what lets one refusal
     * name all three.
     *
     * <p><em>Through {@link Binder}</em>, because {@code Environment.getProperty} and Boot's
     * configuration binding do not resolve the same set of names, and the gap is a name an operator
     * is told to use. {@code SystemEnvironmentPropertySource.checkPropertyName} tries dots→{@code _}
     * and hyphens→{@code _}; Boot's {@code SystemEnvironmentPropertyMapper} additionally tries the
     * dashes-<em>removed</em> form, which is what Boot's own documentation prints. So
     * {@code SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT=0} binds {@code 0} into
     * {@code HikariConfig.setConnectionTimeout} — {@code Integer.MAX_VALUE}, about 24.8 days —
     * while a check reading the {@code Environment} sees the {@code 3000} from
     * {@code application.properties}, passes every rule, and then reports a bound that is not in
     * force. That is the exact state this rule exists to refuse, reached by a name rather than by a
     * value, and it also feeds the wrong term into H-3's arithmetic. Binding the way the pool binds
     * is half the answer; {@link ThePoolHoldsTheBoundThatWasChecked} is the other half, and it is
     * the one that does not depend on this file having enumerated the spellings correctly.
     *
     * <p>Absent is the one legitimate case, and it is not "unset": the shipped
     * {@code application.properties} sets this key, so an absent value means that line was removed
     * from the file. The fallback is Hikari's own default, which is what would then be in force.
     */
    private long resolveAcquisitionBound() {
        String raw = Binder.get(environment)
                .bind(ACQUISITION_PROPERTY, String.class)
                .orElse(null);
        if (raw == null) {
            return HIKARI_DEFAULT_CONNECTION_TIMEOUT_MS;
        }
        if (raw.isBlank()) {
            throw new IllegalStateException(refusal(
                    "is BLANK. " + ACQUISITION_ENV_VAR + "= is an empty value, not an absent one, "
                    + "so the placeholder default in application.properties never applies and the "
                    + "pool is configured with nothing at all"));
        }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(refusal("is not a number (\"" + raw + "\")"));
        }
        if (value == 0) {
            throw new IllegalStateException(refusal(
                    "is 0, which Hikari maps to Integer.MAX_VALUE — about 24.8 days. That is NO "
                    + "BOUND, not no wait: every request that finds the pool full would park until "
                    + "a connection appeared, holding a Tomcat worker the whole time, which is the "
                    + "state this bound exists to remove. It is the same plausible-looking zero "
                    + "PostgreSQL reads as \"disabled\" for the other two bounds of this family, "
                    + "and the only one the pool accepts in silence"));
        }
        if (value < HIKARI_ACQUISITION_FLOOR_MS) {
            throw new IllegalStateException(refusal(
                    "is " + value + " ms, below Hikari's own floor of " + HIKARI_ACQUISITION_FLOOR_MS
                    + " ms. The pool refuses it too, with a message that names neither this "
                    + "property nor the variable you set"));
        }
        return value;
    }

    /** One shape for every way the acquisition bound can be wrong, so the remedy is always there. */
    private static String refusal(String problem) {
        return ACQUISITION_PROPERTY + " (" + ACQUISITION_ENV_VAR + ") " + problem
               + ". It is how long a request may wait for a database connection before being "
               + "refused with 503 DATABASE_BUSY: set it to at least " + HIKARI_ACQUISITION_FLOOR_MS
               + " ms and at least app.locking.lock-timeout-ms (DB_LOCK_TIMEOUT_MS), or remove "
               + ACQUISITION_ENV_VAR + " from the environment to get the shipped default — comment "
               + "the line out, never blank it.";
    }

    /**
     * <strong>H-3 — the shutdown residue write must fit inside the stop grace, and the connection
     * acquisition is one of its terms.</strong>
     *
     * <p>{@code MailAsyncProperties.Async.isShutdownWithinTheStopGrace()} asserts the same relation
     * at binding time and cannot see this term: the acquisition bound is a pool property and is
     * invisible inside a nested record's {@code @AssertTrue}. So the arithmetic is completed here,
     * and the message says it is the same relation plus one term — an operator must not receive two
     * unrelated-looking refusals for one misconfiguration.
     *
     * <p>Why it is a refusal and not a warning: shutdown waits the drain, <em>then</em> writes
     * whatever is still queued to {@code failed_email} as one batch. If the process is killed
     * before that write, every queued account-critical email is lost silently — which is the loss
     * that path exists to prevent, now with a mechanism in the code that makes a reader believe
     * otherwise. At the shipped defaults it holds with room: 15000 + 3000 + 1000 + 100 = 19 100
     * against a grace of 30 000.
     */
    private void refuseAShutdownThatOutrunsTheStopGrace(long acquisitionMs) {
        var async = mail.async();
        long cost = async.shutdownCostMs(acquisitionMs);
        long grace = async.stopGraceMs();
        if (cost > grace) {
            throw new IllegalStateException(
                    "The mail shutdown does not fit inside the stop grace once the connection "
                    + "acquisition is counted: drain " + (async.shutdownDrainSeconds() * 1000L)
                    + " ms + acquisition " + acquisitionMs + " ms + commit "
                    + MailAsyncProperties.Async.RESIDUE_WRITE_FIXED_MS + " ms + "
                    + async.queueCapacity() + " queued row(s) = " + cost + " ms, against "
                    + "app.mail.async.stop-grace-seconds (" + grace + " ms). This is the same "
                    + "relation app.mail.async.shutdown-drain-seconds and "
                    + "app.mail.async.queue-capacity are already checked against, plus the one "
                    + "term a nested record cannot see. Shutdown waits the drain, THEN writes "
                    + "whatever is still queued to failed_email as one batch; past the grace the "
                    + "process is killed part-way through and every queued account-critical email "
                    + "is lost silently. Fix it with ONE of: a shorter "
                    + "MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS, a smaller MAIL_ASYNC_QUEUE_CAPACITY, a "
                    + "shorter " + ACQUISITION_ENV_VAR + ", or a larger APP_STOP_GRACE_SECONDS — "
                    + "which raises the container's stop_grace_period and this bound together, "
                    + "since docker-compose.prod.yml reads the same variable.");
        }
    }

    /**
     * <strong>Soft rule A — a waiter must not give up before a holder is entitled to hold.</strong>
     *
     * <p>A connection held by a transaction waiting out its whole lock budget is legitimately
     * unavailable for that long. An acquisition bound below the lock bound therefore converts
     * ordinary row contention — which this product answers with a retryable {@code 409} — into
     * connection-acquisition refusals, i.e. it re-labels a condition the product handles as a pool
     * incident.
     *
     * <p>A WARN and not a refusal, in the register the deleted pool-turnover rule used: it fires on
     * the operator action that genuinely invalidates the acquisition bound (raising
     * {@code DB_LOCK_TIMEOUT_MS} without raising the acquisition bound with it), and an operator
     * who has weighed the two may legitimately want them apart. Silent at the shipped 3000/3000.
     */
    private void warnIfAWaiterGivesUpBeforeAHolderMust(int lockMs, long acquisitionMs) {
        if (acquisitionMs < lockMs) {
            log.warn("{} ({} ms) is below app.locking.lock-timeout-ms ({} ms), so a request can be "
                     + "refused a connection while the transaction holding it is still inside the "
                     + "lock wait this application grants it: ordinary row contention — a retryable "
                     + "409 — starts arriving as 503 DATABASE_BUSY instead. This is a sizing "
                     + "warning, not an error. Raise {} to at least {} ms, or lower "
                     + "DB_LOCK_TIMEOUT_MS with it.",
                     ACQUISITION_PROPERTY, acquisitionMs, lockMs, ACQUISITION_ENV_VAR, lockMs);
        }
    }

    /**
     * <strong>The pool is made to wait for this check, and the reason is that otherwise the check
     * an operator most needs is unreachable.</strong>
     *
     * <p>Measured rather than assumed, by booting the application with
     * {@code DB_CONNECTION_TIMEOUT_MS=0}: without this, the {@code DataSource} is built first (the
     * {@code EntityManagerFactory} needs it) and the boot fails while binding
     * <strong>{@code validation-timeout}</strong> — because {@code setValidationTimeout} has no
     * zero case and refuses anything below 250, while {@code setConnectionTimeout} maps 0 to
     * {@code Integer.MAX_VALUE} in silence. So the operator who typed 0 was told
     * "validationTimeout cannot be less than 250ms": a property they never set, and an explanation
     * of the wrong problem. H-2 exists to say that <em>0 means no bound</em>, and a refusal that
     * fires only in a configuration nobody runs is a belief rather than a guard.
     *
     * <p>The same mechanism Boot uses to order Flyway ahead of the beans that read a migrated
     * schema: a {@link BeanFactoryPostProcessor} runs before any singleton exists and adds a
     * {@code depends-on} edge. There can be no cycle — this check reads three
     * {@code @ConfigurationProperties} records and the {@code Environment}, none of which can reach
     * a {@code DataSource}. A context with no {@code DataSource} at all (every property unit test)
     * simply finds nothing to order.
     */
    @Component
    static class TheBoundsAreCheckedBeforeThePoolIsBuilt implements BeanFactoryPostProcessor {

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            String[] pools = beanFactory.getBeanNamesForType(DataSource.class, true, false);
            String[] checkNames = beanFactory.getBeanNamesForType(
                    DatabaseTimeoutConsistency.class, true, false);
            if (checkNames.length == 0) {
                return;
            }
            for (String pool : pools) {
                var definition = beanFactory.getBeanDefinition(pool);
                var existing = definition.getDependsOn();
                var combined = new java.util.LinkedHashSet<String>();
                if (existing != null) {
                    combined.addAll(java.util.Arrays.asList(existing));
                }
                combined.addAll(java.util.Arrays.asList(checkNames));
                definition.setDependsOn(combined.toArray(String[]::new));
            }
        }
    }

    /**
     * <strong>The seal: what the POOL actually holds, checked against what was validated, after the
     * pool has been built.</strong> The single source of truth for the acquisition bound is the
     * number {@code HikariConfig} is carrying — not the property the check happened to read — and
     * this is what makes {@link #acquisitionBoundMs()} a fact rather than an assertion.
     *
     * <p>It closes several unrelated holes at once, which is why it is worth a bean of its own — and
 * the list is written without a leading count, because a number goes stale one entry before the
 * list does and this one already grew.
     *
     * <ul>
     *   <li><strong>A name that binds one way for Hikari and another for a check.</strong>
     *       {@code Environment.getProperty} and Boot's relaxed binder do not resolve the same set of
     *       spellings; {@link #resolveAcquisitionBound()} now uses the binder, but that fix is only
     *       as good as this file's understanding of the mapping rules, and those are Boot's to
     *       change. Comparing against the built pool needs no understanding of them at all.</li>
     *   <li><strong>An ordering regression.</strong> {@link TheBoundsAreCheckedBeforeThePoolIsBuilt}
     *       finds the pool with {@code getBeanNamesForType(DataSource.class, …)}, which <em>fails
     *       open in silence</em> if a deployment ever supplies the pool behind a {@code FactoryBean}
     *       or under a less specific type: no edge, no log, and H-2 becomes unreachable exactly as
     *       it was before that edge existed. This runs once every singleton has been created, when
     *       the pool exists however it was made, so it cannot be routed around by the
     *       ordering.</li>
     *   <li><strong>A second Hikari line reading the same variable that nothing else reads
     *       back.</strong> {@code application.properties} spells {@link #ACQUISITION_ENV_VAR} into
     *       {@code validation-timeout} as well, because a borrowed connection idle past
     *       {@code aliveBypassWindowMs} is aliveness-checked with it. No rule, refusal, WARN or
     *       metric in this process ever reports that number, so a name that overrode it would
     *       change what an acquisition costs and leave nothing to notice by. Only the pool knows,
     *       so only the pool can be asked.</li>
     * </ul>
     *
     * <p>{@code Integer.MAX_VALUE} is named separately from the equality test although the equality
     * test would also catch it: it is the one value that means <em>no bound at all</em>, and an
     * operator who reaches it has typed a zero somewhere this process could not see. The message
     * has to say that rather than print two numbers that differ.
     *
     * <p><strong>Absence is not silence, and the two absences are not the same absence.</strong> No
     * {@code DataSource} at all is the ordinary case for a property unit test and is skipped without
     * a word. Everything else that stops this check from reading the pool is an instance running an
     * unverified bound and must not pass quietly — a {@code DataSource} that cannot be unwrapped to
     * a {@link HikariDataSource}, and several with no primary, which {@code getIfUnique()} reports
     * by the same {@code null} as none at all. Both WARN rather than refusing, because refusing
     * would make an otherwise legitimate alternative pool unbootable over a check about Hikari.
     */
    @Component
    @RequiredArgsConstructor
    @Slf4j
    static class ThePoolHoldsTheBoundThatWasChecked implements SmartInitializingSingleton {

        private final DatabaseTimeoutConsistency bounds;
        private final ObjectProvider<DataSource> dataSources;

        /**
         * <strong>End of {@code finishBeanFactoryInitialization()}, which is before the web server
         * exists.</strong> This was an {@code @EventListener(ContextRefreshedEvent.class)} and the
         * difference is not cosmetic: Boot starts Tomcat from {@code WebServerStartStopLifecycle},
         * driven out of {@code finishRefresh()} <em>immediately before</em> that event is
         * published. So on a mismatch the connector was already listening and could accept — and
         * be routed — requests in the window between opening and the context tearing itself down,
         * while every other rule in this class refuses before the pool is even built. It matters
         * most on Cloud, where a load balancer can send traffic to an instance that is about to
         * die and where a rolling deploy flaps rather than stops.
         *
         * <p>{@link SmartInitializingSingleton} runs at the end of singleton pre-instantiation, so
         * the premise this check needs still holds — the pool has been built and post-processed —
         * and nothing has been started. {@code PoolBoundSealBootTest} cannot see the difference by
         * construction: it boots {@code WebApplicationType.NONE}, so there is no connector to open
         * either way.
         *
         * <p><strong>The earlier moment costs no eagerness, and that was checked rather than
         * assumed.</strong> This callback exists only for a singleton that was pre-instantiated, so
         * it looks like it should be the shape an operator can delete with
         * {@code spring.main.lazy-initialization=true} while the {@code @EventListener} it replaced —
         * resolved by name at publish time, needing no prior instantiation — was immune. It is not:
         * {@code LazyInitializationBeanFactoryPostProcessor} excludes
         * {@code SmartInitializingSingleton} bean types from that property by construction, for
         * precisely this reason. What DOES still defer it is an explicit {@code @Lazy} on this
         * class, because an explicitly set lazy flag is checked before the exclusions. Both halves
         * are behavioural in {@code PoolBoundSealBootTest}: one case boots under the property, and
         * the enclosing class says why no pin belongs here.
         */
        @Override
        public void afterSingletonsInstantiated() {
            verify();
        }

        void verify() {
            // getIfUnique, not getIfAvailable: getIfAvailable raises NoUniqueBeanDefinitionException
            // when there are two candidates and no primary, which would fail the refresh from
            // inside this check with a message about bean resolution rather than about a bound.
            // But getIfUnique answers null for BOTH of the cases it cannot pick one for, and they
            // are not the same case, so they are told apart here rather than collapsed. ZERO
            // candidates is the ordinary property unit test and is skipped without a word; two with
            // no primary is a deployment whose pool this check never read — the unverified-bound
            // condition the WARN owns, reached through a bean definition instead of through a type.
            // Returning silently on both is the same fails-open-in-silence shape this seal exists
            // to close, and it is "absence is not silence", three paragraphs up, inverted.
            var dataSource = dataSources.getIfUnique();
            if (dataSource == null) {
                long candidates = dataSources.stream().count();
                if (candidates > 0) {
                    warnTheBoundIsUnverified("this context holds " + candidates + " DataSource "
                            + "beans and none of them is primary, so there is no one pool to ask");
                }
                return;
            }
            var pool = hikariIn(dataSource);
            if (pool == null) {
                warnTheBoundIsUnverified("the connection pool is a "
                        + dataSource.getClass().getName() + " rather than a HikariDataSource");
                return;
            }
            long inForce = pool.getConnectionTimeout();
            if (inForce == Integer.MAX_VALUE) {
                throw new IllegalStateException(mismatch(ACQUISITION_PROPERTY,
                        bounds.acquisitionBoundMs(),
                        "Integer.MAX_VALUE — about 24.8 days, which is NO BOUND at all. Hikari maps "
                        + "0 to that value in silence, so a 0 has reached the pool by a name this "
                        + "check did not read"));
            }
            if (inForce != bounds.acquisitionBoundMs()) {
                throw new IllegalStateException(mismatch(ACQUISITION_PROPERTY,
                        bounds.acquisitionBoundMs(), inForce + " ms"));
            }
            // The SAME number, one property over, and the identical hole: application.properties
            // spells DB_CONNECTION_TIMEOUT_MS into validation-timeout too, because a borrowed
            // connection idle past aliveBypassWindowMs is aliveness-checked with it. What equality
            // buys is a CEILING on the overshoot rather than the absence of one, and the difference
            // was read out of HikariPool.getConnection(hardTimeout) (HikariCP 7.0.2) rather than
            // assumed: isConnectionDead(...) runs AFTER connectionBag.borrow(timeout) has returned,
            // and the loop re-derives timeout = hardTimeout - elapsedMillis(startTime) only
            // afterwards — so one dead-connection probe overruns the hard timeout by up to
            // validationTimeout at WHATEVER value it is set to. Held equal, the worst-case park is
            // bounded at 2x the acquisition bound: a number this process knows and prices, which is
            // what AuthRateLimitFilter's refund cap is sized against. Left to diverge it is this
            // bound plus somebody else's, today Hikari's own 5000 default. Nothing else in this
            // process reads that value, so nothing else would ever notice a
            // SPRING_DATASOURCE_HIKARI_VALIDATIONTIMEOUT overriding it.
            long validationInForce = pool.getValidationTimeout();
            if (validationInForce != bounds.acquisitionBoundMs()) {
                throw new IllegalStateException(mismatch(VALIDATION_PROPERTY,
                        bounds.acquisitionBoundMs(), validationInForce + " ms")
                        + VALIDATION_IS_NOT_A_SEPARATE_DECISION);
            }
        }

        /**
         * <strong>The sentence {@link #mismatch} cannot carry, because this is the one divergence
         * that may not be an accident.</strong> Everything in {@code mismatch} diagnoses a value
         * that moved by a name nobody meant to use, and its remedy — "remove whatever else is
         * writing it" — is performable but is the wrong reading for the operator who typed
         * {@code SPRING_DATASOURCE_HIKARI_VALIDATIONTIMEOUT} on purpose: they are sent hunting a bug
         * that is them, and are never told the intent itself is unsupported. A refusal may only
         * prescribe an action its reader can perform, and that reader cannot act on an intent this
         * product does not support until it says so.
         */
        private static final String VALIDATION_IS_NOT_A_SEPARATE_DECISION =
                " And if you set it deliberately: a validation timeout different from the "
                + "acquisition bound is not a supported configuration of this product. The two are "
                + "ONE NUMBER — held equal, the worst case one getConnection() can park is twice "
                + "the acquisition bound, a figure this process knows and prices; apart, it is that "
                + "bound plus a second one nothing here reads back. " + ACQUISITION_ENV_VAR
                + " is the only knob, and it moves both lines.";

        /**
         * <strong>One sentence for "this instance is running a bound nothing checked", whichever way
         * the check was prevented from checking it.</strong> Two callers differing by one clause — a
         * pool that is not Hikari's, or no single pool to ask — because what an operator has to do
         * about either is the same and what they are risking is the same. A WARN rather than a
         * refusal in both: refusing would make an otherwise legitimate alternative pool, or a
         * legitimate second {@code DataSource}, unbootable over a check about Hikari.
         */
        private void warnTheBoundIsUnverified(String because) {
            log.warn("The acquisition bound this instance validated ({} ms, from {}) could not be "
                     + "checked against the bound the pool is actually holding, because {}. Every "
                     + "refusal and every WARN that quotes that number is quoting a property rather "
                     + "than a fact — INCLUDING one that has already been decided by the time you "
                     + "read this: the boot-time ceiling that keeps the mail shutdown inside "
                     + "APP_STOP_GRACE_SECONDS counts this bound as a term and can refuse the boot "
                     + "outright, and it runs from @PostConstruct, before this check. AND 503 "
                     + "DATABASE_BUSY MAY NOT BE ANSWERED AT ALL: both writers of that refusal "
                     + "discriminate on java.sql.SQLTransientConnectionException — the type Hikari "
                     + "raises from HikariPool.createTimeoutException, which another pool is free "
                     + "never to raise — so on a pool that does not, a failed acquisition stays a "
                     + "500, hamstrack_db_connection_acquisition_failed_total stays at zero, and "
                     + "the alert built on it can never fire. Nobody but the reader of this line "
                     + "will ever know that.",
                     bounds.acquisitionBoundMs(), ACQUISITION_PROPERTY, because);
        }

        /**
         * One shape for every way a validated number and a held one can disagree, because the
         * remedy is the same question each time: <em>who else is setting this?</em> Deliberately
         * NOT the outer {@code refusal(...)}, whose remedy ("set it to at least 250 ms, or comment
         * the line out") is advice about the value — and the value in
         * {@code application.properties} is not what is wrong here. It takes the property name
         * because the variable drives more than one of them and the reader has to be told which
         * line diverged.
         */
        private static String mismatch(String property, long validated, String inForce) {
            return property + " (" + ACQUISITION_ENV_VAR + ") was validated as "
                   + validated + " ms at startup, but the pool that was built is holding " + inForce
                   + ". Every rule checked at boot, and every number reported afterwards — the "
                   + "ceiling that keeps the shutdown inside APP_STOP_GRACE_SECONDS, and the bound "
                   + "the 503 DATABASE_BUSY WARN quotes to an operator — was computed against a "
                   + "bound that is not in force. Something is setting this value by a name or from "
                   + "a place this check does not read. The likeliest is a dashes-REMOVED "
                   + "environment spelling — SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT or "
                   + "SPRING_DATASOURCE_HIKARI_VALIDATIONTIMEOUT: Boot's relaxed binding accepts "
                   + "either and it wins over application.properties. " + ACQUISITION_ENV_VAR
                   + " is spelled into BOTH Hikari lines on purpose, because they are one number "
                   + "and not two decisions, so set it there and remove whatever else is writing "
                   + "it; if it is a DataSourceProperties customiser or a second property source, "
                   + "that is where to look.";
        }

        /** The pool itself, however it is wrapped, or {@code null} if it is not Hikari's at all. */
        private static HikariDataSource hikariIn(DataSource dataSource) {
            if (dataSource instanceof HikariDataSource pool) {
                return pool;
            }
            try {
                if (dataSource.isWrapperFor(HikariDataSource.class)) {
                    return dataSource.unwrap(HikariDataSource.class);
                }
            } catch (SQLException e) {
                // Asking a wrapper what it wraps must not be the reason a boot fails; the WARN the
                // caller then writes says the bound is unverified, which is the honest outcome.
                log.debug("Could not unwrap {} to a HikariDataSource", dataSource.getClass(), e);
            }
            return null;
        }
    }

    /**
     * <strong>Soft rule B — a bound above the grace is a bound the environment does not
     * honour.</strong>
     *
     * <p>This carries the concern the deleted {@code POOL_TURNOVER_SHARE} rule protected — a very
     * large {@code DB_STATEMENT_TIMEOUT_MS} letting one request hold one connection for minutes —
     * onto an anchor that is real rather than circular. That rule compared the statement bound
     * against half the acquisition bound on the premise that one statement ≈ one connection hold,
     * and this tree contains two files written earlier that disprove it: a transaction of a hundred
     * statements at half the budget holds a connection for fifty times it
     * ({@code BoundedJpaTransactionManager}), and one planning aggregate holds one for ~320 s
     * ({@code PlanningProperties}). It certified a turnover that could not happen, which is worse
     * than no check at all.
     *
     * <p>The stop grace is an anchor with a nameable remedy: a statement that outlasts the grace the
     * platform gives the process cannot finish inside a shutdown anyway. Silent at 10000 ≤ 30000;
     * fires at 60000 and 300000, which are exactly the values {@code docs/self-hosting.md} labels
     * diagnostic.
     */
    private void warnIfTheBoundOutlastsTheGrace(int statementMs) {
        long graceMs = mail.async().stopGraceMs();
        if (statementMs > graceMs) {
            log.warn("app.persistence.statement-timeout-ms ({} ms) is longer than the stop grace "
                     + "this deployment gives the process (app.mail.async.stop-grace-seconds, {} "
                     + "ms), so a statement running at that bound cannot finish inside a shutdown: "
                     + "a deploy kills it, and it holds one of DB_POOL_MAX_SIZE connections until "
                     + "it does. This is a sizing warning, not an error — a deliberately long "
                     + "bound is legitimate on a large install. If it is deliberate, raise "
                     + "DB_POOL_MAX_SIZE with it (and see EXPENSIVE_READ_MAX_IN_FLIGHT, which is "
                     + "what bounds how much of that pool the expensive-read surface may hold at "
                     + "once) and APP_STOP_GRACE_SECONDS if you want the statement to survive a "
                     + "deploy; if it is not, {} ms or less keeps the bound inside the window the "
                     + "platform actually gives it.", statementMs, graceMs, graceMs);
        }
    }
}
