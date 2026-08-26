package com.hamstrack.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-151 — {@link StatementTimeoutProperties} must <strong>fail fast, never clamp</strong>, in
 * the shape {@link LockingProperties} established, <em>plus</em> the one thing no single-record
 * property can express: a value that is individually valid and jointly wrong.
 *
 * <p>The individual traps are its sibling's, for the same reason: PostgreSQL reads
 * {@code statement_timeout = 0} as <em>disabled</em>, so a zero does not fail, it silently
 * restores the unbounded statement this feature exists to delete; and
 * {@code DB_STATEMENT_TIMEOUT_MS=} is the ordinary way an operator disables a line in a
 * {@code .env}, which binds an <em>empty</em> value rather than an absent one — caught only by
 * the component being a primitive {@code int}. {@link LockingPropertiesTest} explains both at
 * length; they are pinned again here because they are pinned per property, not per project.
 *
 * <p><strong>The joint constraint is what makes this file more than a copy.</strong>
 * {@code statement_timeout} counts time spent waiting for a lock, so the smaller of the two
 * bounds always fires first. A statement bound at or below {@code app.locking.lock-timeout-ms}
 * therefore makes the lock bound dead configuration and silently swaps a retryable
 * {@code 409 + Retry-After} for a {@code 422} that is not retryable — a contract change with no
 * log line, no failing test and no diff. {@link DatabaseTimeoutConsistency} turns it into a
 * stopped boot, and these tests are what keep that check from being deleted as noise.
 */
class StatementTimeoutPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultIsTheDocumentedTenSeconds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Promised to operators by application.properties, .env.prod.example and
            // docs/self-hosting.md, and quoted back to callers inside the 422 itself.
            assertThat(context.getBean(StatementTimeoutProperties.class).statementTimeoutMs())
                    .isEqualTo(10_000);
        });
    }

    /**
     * The value that must never bind. {@code 0} looks like "no limit" and PostgreSQL agrees —
     * it means <em>run for ever</em>, which is the state this property exists to remove.
     */
    @Test
    void zeroOrNegativeRefusesToStart() {
        assertRejected("app.persistence.statement-timeout-ms=0");
        assertRejected("app.persistence.statement-timeout-ms=-1");
    }

    /**
     * Both bounds are refusal points rather than recommendations: under a second no legitimate
     * request would survive a cold cache, and past ten minutes the request is long dead at every
     * intermediary while the thing still unbounded — how long the <em>connection</em> is held —
     * is not what this setting bounds anyway.
     */
    @Test
    void justOutsideEitherBoundRefusesToStart() {
        assertRejected("app.persistence.statement-timeout-ms=999");
        assertRejected("app.persistence.statement-timeout-ms=600001");
    }

    @Test
    void bothBoundsAreInclusive() {
        assertBinds("app.persistence.statement-timeout-ms=1000", 1000,
                "app.locking.lock-timeout-ms=100");
        assertBinds("app.persistence.statement-timeout-ms=600000", 600_000);
        // The value StatementBoundTest drives the real bound with must stay bindable, or that
        // test silently stops testing the configured value.
        assertBinds("app.persistence.statement-timeout-ms=2000", 2000,
                "app.locking.lock-timeout-ms=250");
    }

    /**
     * <strong>Individually valid, jointly wrong — and the failure has to be at boot.</strong>
     * 3000 is inside every {@code @Min}/{@code @Max} on both records, and at the shipped lock
     * bound of 3000 it means the statement bound fires first on every lock wait in the instance.
     * Nothing else would notice: the lock timeout simply stops happening.
     */
    @Test
    void aStatementBoundThatWouldShadowTheLockBoundRefusesToStart() {
        // equal to the lock bound
        assertJointlyRejected(3000, 3000);
        // above it, but inside the 2x margin: the transaction would have almost no time left to
        // do its work after waiting nearly the whole lock budget
        assertJointlyRejected(5999, 3000);
    }

    @Test
    void theShippedDefaultsSatisfyTheMargin() {
        runner.withPropertyValues("app.persistence.statement-timeout-ms=10000",
                        "app.locking.lock-timeout-ms=3000")
                .run(context -> assertThat(context)
                        .as("the values this release ships must boot — if this fails, one of the "
                            + "two defaults moved and the other one has to move with it")
                        .hasNotFailed());
    }

    /**
     * The refusal is only useful if it says what to do about it, at the one moment somebody is
     * reading it: a stopped boot with a stack trace and no numbers is indistinguishable from a
     * typo somewhere else in the file.
     */
    @Test
    void theRefusalNamesBothKnobsAndTheReason() {
        runner.withPropertyValues("app.persistence.statement-timeout-ms=3000",
                        "app.locking.lock-timeout-ms=3000")
                .run(context -> assertThat(failureOf(context))
                        .contains("app.persistence.statement-timeout-ms")
                        .contains("app.locking.lock-timeout-ms")
                        .contains("DB_STATEMENT_TIMEOUT_MS")
                        .contains("409")
                        .contains("422"));
    }

    @Test
    void aBlankValueStopsTheBootInsteadOfQuietlyMeaningTheDefault() {
        runner.withPropertyValues("app.persistence.statement-timeout-ms=")
                .run(context -> assertThat(context)
                        .as("a blank value must abort the boot, not fall back to @DefaultValue")
                        .hasFailed());
    }

    /**
     * The blank arriving the way it actually arrives in production: through the
     * {@code ${DB_STATEMENT_TIMEOUT_MS:10000}} placeholder in {@code application.properties}.
     * The whole risk lives in the difference between "absent" and "present and empty".
     */
    @Test
    void theBlankEnvVarReachesTheBinderThroughTheRealPlaceholder() {
        var withPlaceholder = runner.withPropertyValues(
                "app.persistence.statement-timeout-ms=${DB_STATEMENT_TIMEOUT_MS:10000}");

        withPlaceholder.run(context -> {
            assertThat(context)
                    .as("the variable is simply absent — the placeholder default applies")
                    .hasNotFailed();
            assertThat(context.getBean(StatementTimeoutProperties.class).statementTimeoutMs())
                    .isEqualTo(10_000);
        });

        withPlaceholder.withPropertyValues("DB_STATEMENT_TIMEOUT_MS=")
                .run(context -> assertThat(context)
                        .as("DB_STATEMENT_TIMEOUT_MS= is present-but-empty, so the placeholder "
                            + "RESOLVES (to \"\") and the :10000 default never applies — if this "
                            + "context starts, the operator who blanked the line is running with "
                            + "a bound they believe they removed")
                        .hasFailed());
    }

    /**
     * The mechanism behind the test above, asserted directly so a future "tidy it to Integer"
     * fails here loudly instead of turning the blank test into a test of nothing.
     */
    @Test
    void theComponentIsAPrimitiveBecauseThatIsWhatCatchesTheBlank() throws Exception {
        assertThat(StatementTimeoutProperties.class.getDeclaredField("statementTimeoutMs").getType())
                .as("statementTimeoutMs must stay a primitive int — boxed, a blank "
                    + "DB_STATEMENT_TIMEOUT_MS= converts to null, the binder reads it as unbound, "
                    + "and @DefaultValue silently applies 10000")
                .isEqualTo(int.class);
    }

    private void assertBinds(String property, int expected, String... alsoSet) {
        runner.withPropertyValues(property).withPropertyValues(alsoSet)
                .run(context -> {
                    assertThat(context).as(property).hasNotFailed();
                    assertThat(context.getBean(StatementTimeoutProperties.class).statementTimeoutMs())
                            .isEqualTo(expected);
                });
    }

    private void assertRejected(String property) {
        runner.withPropertyValues(property)
                .run(context -> assertThat(context)
                        .as(property)
                        .hasFailed()
                        .getFailure()
                        // the field name lives on the deepest cause (BindValidationException)
                        .rootCause()
                        .hasMessageContaining("statementTimeoutMs"));
    }

    private void assertJointlyRejected(int statementMs, int lockMs) {
        runner.withPropertyValues("app.persistence.statement-timeout-ms=" + statementMs,
                        "app.locking.lock-timeout-ms=" + lockMs)
                .run(context -> assertThat(context)
                        .as("statement %d ms with lock %d ms: the statement bound counts the lock "
                            + "wait, so it fires first and the 409 contract quietly dies",
                                statementMs, lockMs)
                        .hasFailed());
    }

    /** Every message in the failure chain, so an assertion can look at all of them. */
    private String failureOf(AssertableApplicationContext context) {
        var out = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            out.append(t.getMessage()).append('\n');
        }
        return out.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({StatementTimeoutProperties.class, LockingProperties.class})
    @Import(DatabaseTimeoutConsistency.class)
    static class TestConfig {}
}
