package com.hamstrack.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-136 review round 4/7 — {@link LockingProperties} must <strong>fail fast, never
 * clamp</strong>, the posture {@link BoardProperties}, {@link ClassificationProperties} and
 * {@link AgileProperties} already take, and this test mirrors their
 * {@link ApplicationContextRunner} shape (a context that must NOT start cannot be asserted
 * on by an annotation that starts it).
 *
 * <p>What makes this one different from its siblings, and why it is not optional: the
 * misconfiguration it has to catch is not a typo but a <em>plausible</em> value.
 * PostgreSQL reads {@code lock_timeout = 0} as "wait for ever", i.e. exactly the unbounded
 * wait this setting exists to delete — so a zero would not fail, it would silently restore
 * the DoS the feature was built against, and nothing in the logs would say so.
 *
 * <p>And the case no sibling covers at all: the <strong>blank</strong> value.
 * {@code DB_LOCK_TIMEOUT_MS=} is the ordinary way an operator disables a line in a
 * {@code .env} file, and the placeholder in {@code application.properties}
 * ({@code ${DB_LOCK_TIMEOUT_MS:3000}}) does not rescue it — a default only applies to an
 * <em>unresolvable</em> placeholder, and a variable that is present-but-empty resolves, to
 * the empty string. The only thing standing between that and a silent 3000 is the record
 * component being a <strong>primitive {@code int}</strong>: an empty string does not convert
 * to one, so the boot aborts. Boxed, it would convert to {@code null}, the binder would read
 * that as unbound, and {@code @DefaultValue} would quietly hand back 3000 — an operator who
 * believed they had turned the bound off would get it anyway, with nothing anywhere saying
 * so. {@link #aBlankValueStopsTheBootInsteadOfQuietlyMeaningTheDefault()} and
 * {@link #theBlankEnvVarReachesTheBinderThroughTheRealPlaceholder()} pin the behaviour;
 * {@link #theComponentIsAPrimitiveBecauseThatIsWhatCatchesTheBlank()} pins the mechanism, so
 * a future "tidy it to Integer" is a red test rather than a silent regression.
 */
class LockingPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultIsTheDocumentedThreeSeconds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Promised to operators by application.properties, .env.prod.example and
            // docs/self-hosting.md; a drifted default changes how long every membership
            // mutation in the install is willing to queue.
            assertThat(context.getBean(LockingProperties.class).lockTimeoutMs()).isEqualTo(3000);
        });
    }

    /**
     * The value that must never bind. {@code 0} is not a typo an operator would notice: it
     * looks like "no timeout" and PostgreSQL agrees — it means <em>wait for ever</em>, the
     * exact behaviour this property exists to remove. A negative one is the same class of
     * mistake. Both abort startup, and the failure names the property: at boot that message
     * is the operator's only clue.
     */
    @Test
    void zeroOrNegativeRefusesToStart() {
        assertRejected("app.locking.lock-timeout-ms=0");
        assertRejected("app.locking.lock-timeout-ms=-1");
    }

    /**
     * Both bounds are refusal points, not recommendations: under 100 ms a bound this tight
     * would fail ordinary uncontended work, and past a minute the HTTP request is dead long
     * before the lock wait is, so the bound bounds nothing anybody is still waiting for.
     */
    @Test
    void justOutsideEitherBoundRefusesToStart() {
        assertRejected("app.locking.lock-timeout-ms=99");
        assertRejected("app.locking.lock-timeout-ms=60001");
    }

    /**
     * And both are inclusive — an operator who follows the documented "valid range
     * 100–60000" to the letter must be able to boot on either end of it.
     */
    @Test
    void bothBoundsAreInclusive() {
        assertBinds("app.locking.lock-timeout-ms=100", 100);
        assertBinds("app.locking.lock-timeout-ms=60000", 60_000);
        // The value MembershipLockTimeoutTest drives the real locking paths with must stay
        // bindable too, or that test silently stops testing the configured bound.
        assertBinds("app.locking.lock-timeout-ms=250", 250);
    }

    /**
     * <strong>The case no sibling property covers.</strong> An empty value is what
     * {@code DB_LOCK_TIMEOUT_MS=} produces, and the answer has to be a stopped boot: the
     * alternative is an operator who thinks the bound is off running with 3000 and no way to
     * tell. See the class javadoc for why the primitive is what makes this happen.
     */
    @Test
    void aBlankValueStopsTheBootInsteadOfQuietlyMeaningTheDefault() {
        runner.withPropertyValues("app.locking.lock-timeout-ms=")
                .run(context -> assertThat(context)
                        .as("a blank value must abort the boot, not fall back to @DefaultValue")
                        .hasFailed());
        // ...and it must abort NAMING the line the operator wrote, since that message is the
        // only thing they get.
        runner.withPropertyValues("app.locking.lock-timeout-ms=")
                .run(context -> assertThat(failureOf(context))
                        .contains("app.locking.lock-timeout-ms"));
    }

    /**
     * The same blank, arriving the way it actually arrives in production: through the
     * {@code ${DB_LOCK_TIMEOUT_MS:3000}} placeholder in {@code application.properties}.
     * Pinned separately because the whole risk lives in the difference between "absent" and
     * "present and empty" — the first takes the default, the second must not.
     */
    @Test
    void theBlankEnvVarReachesTheBinderThroughTheRealPlaceholder() {
        var withPlaceholder = runner.withPropertyValues(
                "app.locking.lock-timeout-ms=${DB_LOCK_TIMEOUT_MS:3000}");

        withPlaceholder.run(context -> {
            assertThat(context)
                    .as("the variable is simply absent — the placeholder default applies")
                    .hasNotFailed();
            assertThat(context.getBean(LockingProperties.class).lockTimeoutMs()).isEqualTo(3000);
        });

        withPlaceholder.withPropertyValues("DB_LOCK_TIMEOUT_MS=")
                .run(context -> assertThat(context)
                        .as("DB_LOCK_TIMEOUT_MS= is present-but-empty, so the placeholder "
                            + "RESOLVES (to \"\") and the :3000 default never applies — if this "
                            + "context starts, the operator who blanked the line is running "
                            + "with a bound they believe they removed")
                        .hasFailed());

        withPlaceholder.withPropertyValues("DB_LOCK_TIMEOUT_MS=750")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LockingProperties.class).lockTimeoutMs())
                            .isEqualTo(750);
                });
    }

    /**
     * The mechanism behind the test above, asserted directly so that a future refactor that
     * "tidies" the component to {@code Integer} fails here loudly instead of turning
     * {@link #aBlankValueStopsTheBootInsteadOfQuietlyMeaningTheDefault()} into a test of
     * nothing. {@code @Min(100)} does <em>not</em> catch the blank: a boxed component would
     * bind to {@code null}, which the binder treats as unbound, so {@code @DefaultValue}
     * supplies 3000 and validation then passes on a value the operator never wrote.
     */
    @Test
    void theComponentIsAPrimitiveBecauseThatIsWhatCatchesTheBlank() throws Exception {
        assertThat(LockingProperties.class.getDeclaredField("lockTimeoutMs").getType())
                .as("lockTimeoutMs must stay a primitive int — boxed, a blank "
                    + "DB_LOCK_TIMEOUT_MS= converts to null, the binder reads it as unbound, "
                    + "and @DefaultValue silently applies 3000")
                .isEqualTo(int.class);
    }

    private void assertBinds(String property, int expected) {
        runner.withPropertyValues(property)
                .run(context -> {
                    assertThat(context).as(property).hasNotFailed();
                    assertThat(context.getBean(LockingProperties.class).lockTimeoutMs())
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
                        .hasMessageContaining("lockTimeoutMs"));
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
    @EnableConfigurationProperties(LockingProperties.class)
    static class TestConfig {}
}
