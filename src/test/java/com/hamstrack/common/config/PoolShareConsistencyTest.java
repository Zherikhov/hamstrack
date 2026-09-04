package com.hamstrack.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>The boot refuses a configuration in which the bulkhead reserves nothing</strong>
 * (HD-182, AC-11).
 *
 * <p>Both failures it catches are invisible at runtime, which is exactly why they belong at
 * startup. A per-principal ceiling above the surface ceiling never fires — the surface refuses
 * first, with the wrong {@code errorType} and the wrong remedy — and a surface ceiling at or above
 * the pool lets the expensive-read surface hold every connection on the replica while every
 * document says the interactive API keeps a share. Neither logs anything; both look like a working
 * instance until the day they matter.
 *
 * <p>A plain unit test rather than a context-fails-to-start one, following
 * {@link StorageQuotaConsistencyTest}: the behaviour is arithmetic over one record and a pool
 * size, {@code @PostConstruct} is what runs it, and spinning a Spring context per misconfiguration
 * would buy nothing but slower tests. The {@link HikariDataSource} here is never connected — only
 * its configured maximum is read, which is precisely the value the check reads in production.
 */
class PoolShareConsistencyTest {

    @Test
    void aPerPrincipalCeilingAboveTheSurfaceCeilingRefusesTheBoot() {
        assertThatThrownBy(() -> check(7, 6, 10))
                .as("""
                    A PER-PRINCIPAL CEILING ABOVE THE SURFACE CEILING MUST NOT BOOT.

                    It is dead configuration in the exact shape of the lock-versus-statement rule: \
                    the surface ceiling always refuses first, so the per-principal number never \
                    fires — and the caller gets EXPENSIVE_SURFACE_BUSY ("the instance is busy, \
                    retry") where the truth is TOO_MANY_IN_FLIGHT ("your own requests are, let one \
                    finish"). Two different remedies, and the wrong one is the one that cannot be \
                    acted on.""")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.expensive-read.max-in-flight-per-principal")
                .hasMessageContaining("EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL");
    }

    @Test
    void aSurfaceCeilingAtOrAboveThePoolRefusesTheBoot() {
        for (int total : new int[]{10, 12}) {
            assertThatThrownBy(() -> check(3, total, 10))
                    .as("""
                        A SURFACE CEILING AT OR ABOVE THE POOL MUST NOT BOOT.

                        The whole feature is "the rest of the API always retains \
                        DB_POOL_MAX_SIZE - max-in-flight connections". At equality that \
                        difference is zero: the expensive-read surface can hold every connection \
                        on the replica, the bulkhead reserves nothing, and every document — \
                        application.properties, .env.prod.example, self-hosting.md, ADR-0030 — \
                        still says otherwise. A guard believed to cover more than it does is this \
                        project's most expensive failure shape.""")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.expensive-read.max-in-flight")
                    .hasMessageContaining("DB_POOL_MAX_SIZE");
        }
    }

    /** The shipped defaults against the shipped pool, which is the configuration nobody edits. */
    @Test
    void theShippedDefaultsBootAndWarnAboutNothing() {
        assertThat(warningsFrom(() -> check(3, 6, 10)))
                .as("6 of a pool of 10 is exactly the documented 60%, and the WARN is for MORE "
                    + "than that — a default install must not log a sizing complaint about itself")
                .isEmpty();
    }

    /**
     * <strong>A WARN, deliberately, and this one is a sizing judgement where the two above are
     * ordering rules.</strong> A deployment with a large pool and an operator who knows their
     * traffic may legitimately want more of it on this surface; refusing to start would be this
     * check overruling them on a number it cannot see the context for. What it must not do is stay
     * silent — and the message says the residue in CONNECTIONS, because connections are what the
     * interactive API actually runs out of.
     */
    @Test
    void moreThanSixtyPercentOfThePoolWarnsAndNamesTheConnectionsLeft() {
        var warnings = warningsFrom(() -> check(3, 8, 10));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains("app.expensive-read.max-in-flight")
                .contains("2 connection(s)")
                .contains("DB_POOL_MAX_SIZE");
    }

    /**
     * A context with no {@code DataSource} still gets the ordering rule. The pool comparison is the
     * only half that needs a pool, and skipping the other half with it would mean a
     * misconfiguration boots in one kind of context and not another.
     */
    @Test
    void withNoDataSourceTheOrderingRuleStillApplies() {
        assertThatThrownBy(() -> new PoolShareConsistency(share(7, 6, null)).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-in-flight-per-principal");
        assertThatCode(() -> new PoolShareConsistency(share(3, 6, null)).check())
                .doesNotThrowAnyException();
    }

    /**
     * <strong>An install that configured NOTHING must upgrade cleanly onto a pool smaller than the
     * share this product ships</strong> (HD-182 review) — the failure this whole derivation exists
     * to delete.
     *
     * <p>{@code DB_POOL_MAX_SIZE=6} is a configuration {@code .env.prod.example} recommends ("a
     * small pool goes a long way", plus the {@code work_mem} arithmetic that pushes small boxes
     * down). Against a literal default of 6 that install crash-looped on upgrade — correctly by the
     * rule, and having set no {@code EXPENSIVE_READ_*} variable at all. Every pair below both boots
     * and reserves something.
     */
    @Test
    void anUnconfiguredShareFitsWhateverPoolItFinds() {
        for (int pool : new int[]{10, 8, 6, 5, 4, 3, 2}) {
            var derived = share(ExpensiveReadProperties.DERIVE_FROM_POOL,
                                ExpensiveReadProperties.DERIVE_FROM_POOL, pool);
            assertThatCode(() -> new PoolShareConsistency(derived).check())
                    .as("a pool of %d with nothing configured must boot", pool)
                    .doesNotThrowAnyException();
            assertThat(derived.maxInFlight())
                    .as("a pool of %d must keep at least one connection for the rest of the API",
                        pool)
                    .isLessThan(pool)
                    .isPositive();
            assertThat(derived.maxInFlightPerPrincipal())
                    .as("a pool of %d must not derive a per-principal ceiling that can never fire",
                        pool)
                    .isLessThanOrEqualTo(derived.maxInFlight())
                    .isPositive();
        }
    }

    /**
     * The shipped pool gets the shipped numbers, which is what keeps every document true: 3 and 6
     * are still what an unconfigured install runs, and the derivation is invisible there.
     */
    @Test
    void theShippedPoolDerivesTheShippedNumbers() {
        var derived = share(ExpensiveReadProperties.DERIVE_FROM_POOL,
                            ExpensiveReadProperties.DERIVE_FROM_POOL, 10);

        assertThat(derived.maxInFlight()).isEqualTo(ExpensiveReadProperties.DEFAULT_MAX_IN_FLIGHT);
        assertThat(derived.maxInFlightPerPrincipal())
                .isEqualTo(ExpensiveReadProperties.DEFAULT_MAX_IN_FLIGHT_PER_PRINCIPAL);
        assertThat(warningsFrom(() -> new PoolShareConsistency(derived).check()))
                .as("a derived share is taken at the same 60% the sizing WARN fires above, so it "
                    + "must never complain about itself — a boot warning an operator cannot act "
                    + "on is how a log stops being read")
                .isEmpty();
        assertThat(warningsFrom(() -> new PoolShareConsistency(
                share(ExpensiveReadProperties.DERIVE_FROM_POOL,
                      ExpensiveReadProperties.DERIVE_FROM_POOL, 1)).check()))
                .as("and not even on a pool of ONE, the single case the derivation cannot leave "
                    + "anything behind: the remedy there is DB_POOL_MAX_SIZE, and the WARN that "
                    + "names it belongs to ExpensiveReadShare rather than to a message telling the "
                    + "reader to lower a number they never set")
                .isEmpty();
    }

    /**
     * <strong>Deriving never WIDENS the share.</strong> A large pool keeps the documented 6 rather
     * than acquiring 30: the only installs whose behaviour the derivation changes are the ones that
     * would otherwise have refused to start.
     */
    @Test
    void aLargePoolStillGetsTheDocumentedShare() {
        assertThat(share(ExpensiveReadProperties.DERIVE_FROM_POOL,
                         ExpensiveReadProperties.DERIVE_FROM_POOL, 50).maxInFlight())
                .isEqualTo(ExpensiveReadProperties.DEFAULT_MAX_IN_FLIGHT);
    }

    /**
     * <strong>The boot failure survives for a number somebody TYPED</strong>, which is the half of
     * this that must not be lost: an operator who sets a share at or above their pool is told, at
     * startup, that the bulkhead they configured reserves nothing.
     */
    @Test
    void anExplicitShareIsStillRefusedAgainstTheSamePoolThatDerivesFine() {
        assertThatCode(() -> new PoolShareConsistency(
                share(ExpensiveReadProperties.DERIVE_FROM_POOL,
                      ExpensiveReadProperties.DERIVE_FROM_POOL, 6)).check())
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> new PoolShareConsistency(share(3, 6, 6)).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_POOL_MAX_SIZE");
    }

    /**
     * <strong>The mixed configuration — a pinned per-principal ceiling against a DERIVED surface
     * ceiling — is narrowed, not refused</strong> (HD-182 review), and it is the last place the
     * crash loop was reachable.
     *
     * <p>{@code DB_POOL_MAX_SIZE=4} derives a surface ceiling of 2. An operator on that pool who
     * uncomments exactly the line {@code .env.prod.example} shows them —
     * {@code EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL=3} — used to meet hard rule 1 refusing
     * {@code 3 > 2}: a bound introduced so that one surface cannot take an instance down, taking
     * the instance down, over a pair only half of which anybody chose. Documenting the trap was the
     * alternative, and a trap an operator reaches by following the file is not documentation.
     *
     * <p>Narrowing is the same answer the surface ceiling already gets, for the same reason, and it
     * is visible: it costs one WARN naming both numbers and the pool, where the operator can see it
     * on a booted instance rather than in a crash loop.
     */
    @Test
    void aPinnedPerPrincipalCeilingAboveADerivedSurfaceCeilingIsNarrowedRatherThanRefused() {
        var warnings = new java.util.ArrayList<String>();
        ExpensiveReadShare mixed = warningsFrom(ExpensiveReadShare.class, warnings,
                () -> share(3, ExpensiveReadProperties.DERIVE_FROM_POOL, 4));

        assertThatCode(() -> new PoolShareConsistency(mixed).check())
                .as("""
                    A PINNED PER-PRINCIPAL CEILING OVER A DERIVED SURFACE CEILING MUST BOOT.

                    Only half of this pair was chosen by anybody: the operator typed 3, and this                     application derived 2 from their pool of 4. Refusing to start is the right                     answer to two numbers somebody stated a relation between, and a self-inflicted                     outage on a number one side of which we picked ourselves — which is the very                     mistake a literal default of 6 made before the derivation existed.""")
                .doesNotThrowAnyException();

        assertThat(mixed.maxInFlightPerPrincipal())
                .as("narrowed to the derived surface ceiling, so one caller may reach the whole "
                    + "surface share and no further")
                .isEqualTo(mixed.maxInFlight())
                .isEqualTo(2);
        assertThat(mixed.perPrincipalClamped()).isTrue();
        assertThat(warnings)
                .as("and narrowing SILENTLY would be the other failure: an operator whose typed "
                    + "number is not in force must be told, at the boot where it happened, with "
                    + "both numbers and the pool in the line")
                .hasSize(1);
        assertThat(warnings.getFirst())
                .contains("EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL")
                .contains("DB_POOL_MAX_SIZE");

        assertThatThrownBy(() -> check(3, 2, 4))
                .as("and the refusal survives where refusing is right: BOTH numbers typed is an "
                    + "operator stating a relation, and that statement can be wrong")
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A pinned per-principal ceiling that FITS the derived surface ceiling is untouched — the
     * clamp narrows a pair that could not work and never a number that could.
     */
    @Test
    void aPinnedPerPrincipalCeilingThatFitsIsLeftAlone() {
        var fits = share(2, ExpensiveReadProperties.DERIVE_FROM_POOL, 10);

        assertThat(fits.maxInFlightPerPrincipal()).isEqualTo(2);
        assertThat(fits.perPrincipalClamped()).isFalse();
        assertThatCode(() -> new PoolShareConsistency(fits).check()).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ plumbing

    private void check(int perPrincipal, int total, int poolSize) {
        new PoolShareConsistency(share(perPrincipal, total, poolSize)).check();
    }

    /** A resolved {@link ExpensiveReadShare}, which is what {@code @PostConstruct} produces. */
    private ExpensiveReadShare share(int perPrincipal, int total, Integer poolSize) {
        DataSource dataSource = null;
        if (poolSize != null) {
            var pool = new HikariDataSource();
            pool.setMaximumPoolSize(poolSize);
            dataSource = pool;
        }
        var share = new ExpensiveReadShare(properties(perPrincipal, total), provider(dataSource));
        share.resolve();
        return share;
    }

    private ExpensiveReadProperties properties(int perPrincipal, int total) {
        return new ExpensiveReadProperties(true, perPrincipal, total, 1000);
    }

    /**
     * Run {@code body}, collecting the WARN lines {@code source} emitted into {@code sink}. The
     * sibling below is the same mechanism for a {@link Runnable}; this one exists because the
     * clamp WARN is emitted during {@link ExpensiveReadShare#resolve()}, i.e. while the value
     * under test is being produced rather than while it is being checked.
     */
    private <T> T warningsFrom(Class<?> source, List<String> sink,
                               java.util.function.Supplier<T> body) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(source);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            return body.get();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .forEach(sink::add);
        }
    }

    /** The WARN lines {@link PoolShareConsistency} emitted while {@code body} ran. */
    private List<String> warningsFrom(Runnable body) {
        var logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(PoolShareConsistency.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static ObjectProvider<DataSource> provider(DataSource dataSource) {
        return new ObjectProvider<>() {
            @Override
            public DataSource getObject() {
                return dataSource;
            }

            @Override
            public DataSource getObject(Object... args) {
                return dataSource;
            }

            @Override
            public DataSource getIfAvailable() {
                return dataSource;
            }

            @Override
            public DataSource getIfUnique() {
                return dataSource;
            }
        };
    }
}
