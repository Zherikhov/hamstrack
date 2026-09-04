package com.hamstrack.common.config;

import com.hamstrack.common.ratelimit.ExpensiveReadConcurrencyLimit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExpensiveReadProperties} held to the {@link SearchPropertiesTest} standard (HD-182,
 * AC-12) — four numbers that arrive with bounds in annotations, a promise in five documents, and
 * nothing joining them unless a test does.
 *
 * <p>An {@link ApplicationContextRunner} rather than {@code @SpringBootTest}, because a context
 * that must NOT start cannot be asserted on by an annotation that starts it.
 */
class ExpensiveReadPropertiesTest {

    /**
     * <strong>The surefire system properties are cleared for every run in this class</strong>, and
     * that is not tidiness. {@code pom.xml} pins {@code app.expensive-read.max-in-flight} to 2 for
     * the whole suite — not because the suite would otherwise fail (the shipped value is
     * {@code -1}, so the capped pool of 4 derives 2 and boots) but so that every context runs the
     * same small, leak-detecting pair whatever a class does to its pool. System properties sit
     * inside a {@code StandardEnvironment}, so without clearing them the "documented default"
     * assertions below would be reading the TEST pin and would pass no matter what the record says.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withSystemProperties("app.expensive-read.max-in-flight=",
                                  "app.expensive-read.max-in-flight-per-principal=")
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    /**
     * <strong>Both ceilings ship as {@code -1}, and that is the documented default now</strong>
     * (HD-182 review): {@code -1} means "derive from the connection pool", and the 3 and 6 every
     * document quotes are what that derivation produces against the shipped pool of 10.
     *
     * <p>The change is not cosmetic. A literal 6 refused the boot — correctly, by
     * {@link PoolShareConsistency}'s rule that a share must leave a share behind — on every install
     * running {@code DB_POOL_MAX_SIZE=6} or lower, which is a configuration this project's own
     * {@code .env.prod.example} recommends. Refusing to start is the right answer to a number an
     * operator typed and a self-inflicted outage for one who typed nothing.
     *
     * <p>1000 remains a literal because a wait relates to Tomcat workers and not to the pool, so
     * there is nothing to derive it from.
     */
    @Test
    void theDefaultsAreTheDocumentedOnes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(ExpensiveReadProperties.class);
            assertThat(properties.limitEnabled()).isTrue();
            assertThat(properties.maxInFlightPerPrincipal())
                    .isEqualTo(ExpensiveReadProperties.DERIVE_FROM_POOL);
            assertThat(properties.maxInFlight())
                    .isEqualTo(ExpensiveReadProperties.DERIVE_FROM_POOL);
            assertThat(properties.acquireWaitMs()).isEqualTo(1000);
        });
        assertThat(ExpensiveReadProperties.DEFAULT_MAX_IN_FLIGHT_PER_PRINCIPAL).isEqualTo(3);
        assertThat(ExpensiveReadProperties.DEFAULT_MAX_IN_FLIGHT).isEqualTo(6);
    }

    /**
     * There is deliberately no "unlimited" on either ceiling: {@code 0} is the value an operator
     * writes to mean "turn it off", and the off switch is {@code EXPENSIVE_READ_LIMIT_ENABLED} —
     * which is visible, greppable and separate, rather than a zero that reads like a number.
     *
     * <p><strong>{@code 0} is now refused by the compact constructor rather than by {@code @Min},
     * and that is why this test matters more than it did</strong>: the annotation had to be
     * loosened to {@code @Min(-1)} to admit the derive sentinel, so the promise "0 fails startup"
     * — made in {@code application.properties}, {@code .env.prod.example} and
     * {@code docs/self-hosting.md} — now rests on code somebody could delete as redundant.
     */
    @Test
    void zeroRefusesToStartAndOnlyTheDeriveSentinelIsNegative() {
        assertRejected("app.expensive-read.max-in-flight=0", "maxInFlight");
        assertRejected("app.expensive-read.max-in-flight=-2", "maxInFlight");
        assertRejected("app.expensive-read.max-in-flight-per-principal=0",
                       "maxInFlightPerPrincipal");
        assertRejected("app.expensive-read.max-in-flight-per-principal=-2",
                       "maxInFlightPerPrincipal");

        runner.withPropertyValues("app.expensive-read.max-in-flight=-1",
                                  "app.expensive-read.max-in-flight-per-principal=-1")
                .run(context -> assertThat(context)
                        .as("-1 is the derive sentinel and must bind, or an install that "
                            + "configures nothing cannot start")
                        .hasNotFailed());
    }

    /**
     * <strong>The wait is the one number here that legitimately accepts zero</strong>, meaning
     * "refuse immediately", and asserting it is not pedantry: the two ceilings and the wait read
     * like three numbers of the same kind, and a future tidy-up that gave all three the same
     * {@code @Min(1)} would silently delete an operator's ability to turn the wait off.
     */
    @Test
    void theWaitAcceptsZeroBecauseZeroMeansRefuseImmediately() {
        runner.withPropertyValues("app.expensive-read.acquire-wait-ms=0").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ExpensiveReadProperties.class).acquireWaitMs()).isZero();
        });
        assertRejected("app.expensive-read.acquire-wait-ms=-1", "acquireWaitMs");
        assertRejected("app.expensive-read.acquire-wait-ms=2001", "acquireWaitMs");
    }

    /**
     * <strong>The wait has a ceiling because it is denominated in Tomcat workers</strong> (HD-182
     * review) — the one dial on this surface that relates to neither the pool nor the two ceilings.
     *
     * <p>A waiting request holds no connection and no heap, but it does hold a worker, so raising
     * the wait multiplies the thread cost of every refusal — and refusals are the cheap,
     * high-volume outcome by design. Under {@code app.rate-limit.enabled=false}, which deliberately
     * leaves this bound ON, arrivals are unbounded, so a 5 s wait let one principal park all 200
     * workers at zero database cost. 2000 is the bound; nothing in the record relates it to
     * {@code server.tomcat.threads.max}, which is why it is written down in both places.
     */
    @Test
    void theWaitIsCappedWellBelowATomcatWorkerBudget() {
        runner.withPropertyValues("app.expensive-read.acquire-wait-ms=2000")
                .run(context -> assertThat(context).hasNotFailed());
        assertRejected("app.expensive-read.acquire-wait-ms=2500", "acquireWaitMs");
        assertRejected("app.expensive-read.acquire-wait-ms=5000", "acquireWaitMs");
    }

    /** Inclusive at both ends — an operator who follows the documented range must be able to boot. */
    @Test
    void theDocumentedRangesBootAndOneOverRefusesToStart() {
        runner.withPropertyValues("app.expensive-read.max-in-flight=1000",
                                  "app.expensive-read.max-in-flight-per-principal=100")
                .run(context -> assertThat(context).hasNotFailed());
        assertRejected("app.expensive-read.max-in-flight=1001", "maxInFlight");
        assertRejected("app.expensive-read.max-in-flight-per-principal=101",
                       "maxInFlightPerPrincipal");
    }

    /**
     * The blank, arriving the way it actually arrives in production: through the real
     * {@code ${EXPENSIVE_READ_MAX_IN_FLIGHT:-1}} placeholder. The whole risk lives in the
     * difference between "absent" and "present and empty" — the first takes the default, the
     * second must stop the boot, which is what {@code .env.prod.example} promises ("comment it
     * out, never blank").
     *
     * <p><strong>This is why the derive sentinel is a NUMBER and not an empty value.</strong> The
     * obvious way to spell "not configured" is to ship {@code ${EXPENSIVE_READ_MAX_IN_FLIGHT:}} and
     * read a blank as "derive" — and it would have deleted this property, because a blank is
     * exactly the shape of an operator disabling a line and expecting to be told.
     */
    @Test
    void aBlankEnvVarStopsTheBootInsteadOfQuietlyMeaningTheDefault() {
        var withPlaceholder = runner.withPropertyValues(
                "app.expensive-read.max-in-flight=${EXPENSIVE_READ_MAX_IN_FLIGHT:-1}");

        withPlaceholder.run(context -> {
            assertThat(context).as("absent — the placeholder default applies").hasNotFailed();
            assertThat(context.getBean(ExpensiveReadProperties.class).maxInFlight())
                    .isEqualTo(ExpensiveReadProperties.DERIVE_FROM_POOL);
        });

        withPlaceholder.withPropertyValues("EXPENSIVE_READ_MAX_IN_FLIGHT=")
                .run(context -> {
                    assertThat(context)
                            .as("EXPENSIVE_READ_MAX_IN_FLIGHT= is present-but-empty, so the "
                                + "placeholder RESOLVES (to \"\") and the :6 default never applies "
                                + "— if this context starts, an operator who blanked the line is "
                                + "running with a bulkhead they think they removed")
                            .hasFailed();
                    assertThat(failureOf(context)).contains("app.expensive-read.max-in-flight");
                });
    }

    /**
     * The mechanism behind the blank test, asserted directly so a future "tidy it to boxed types"
     * fails here loudly instead of turning that test into a test of nothing: a boxed component
     * binds a blank to {@code null}, the binder reads that as unbound, and {@code @DefaultValue}
     * supplies a value the operator never wrote.
     *
     * <p><strong>Record COMPONENTS, not declared fields</strong> (HD-182 review). The subject is
     * what the binder binds, and {@code getDeclaredFields()} also returns this record's
     * {@code static final} constants — it passed only because those happen to be {@code int}s
     * today, and a {@code static final String} added beside them would have failed a test that has
     * nothing to say about constants. The floor below is the usual tripwire: an assertion of the
     * form "nothing offends" is perfectly true over an empty set.
     */
    @Test
    void everyComponentIsAPrimitiveBecauseThatIsWhatCatchesTheBlank() {
        var components = ExpensiveReadProperties.class.getRecordComponents();

        assertThat(components)
                .as("the record's components could not be read, so this test is guarding nothing")
                .hasSizeGreaterThanOrEqualTo(4);

        for (var component : components) {
            assertThat(component.getType().isPrimitive())
                    .as("%s must stay a primitive — boxed, a blank environment variable converts "
                        + "to null, the binder reads it as unbound, and @DefaultValue silently "
                        + "applies the documented default", component.getName())
                    .isTrue();
        }
    }

    /** The variable names and fallbacks the blank test simulates, and the documents promise. */
    @Test
    void theBasePropertiesFileWiresTheDocumentedPlaceholders() throws Exception {
        var content = properties("application.properties");
        assertThat(content)
                .contains("app.expensive-read.limit-enabled=${EXPENSIVE_READ_LIMIT_ENABLED:true}")
                .contains("app.expensive-read.max-in-flight-per-principal="
                          + "${EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL:-1}")
                .contains("app.expensive-read.max-in-flight=${EXPENSIVE_READ_MAX_IN_FLIGHT:-1}")
                .contains("app.expensive-read.acquire-wait-ms=${EXPENSIVE_READ_ACQUIRE_WAIT_MS:1000}");
    }

    /**
     * <strong>The DC/Cloud invariant, asserted where a properties edit cannot hide.</strong> How
     * much of a replica's pool one surface may hold is a resource property, not a plan property: a
     * per-tenant or per-mode occupancy share would be a licence check wearing a resource guard's
     * clothes. Asserted as ABSENCE of the key rather than as a bound value, because a profile file
     * setting the same 6 would still be the seam the documentation promises does not exist.
     */
    @Test
    void noProfileFileOverridesTheOccupancyBound() throws Exception {
        for (var profile : new String[]{"dc", "cloud"}) {
            var file = "application-" + profile + ".properties";
            assertThat(properties(file))
                    .as("%s must not mention app.expensive-read.* — the share of the pool is "
                        + "identical in dc and cloud by design, and a deployment that wants a "
                        + "different one sets the environment variable, visibly", file)
                    .doesNotContain("app.expensive-read.")
                    .doesNotContain("EXPENSIVE_READ_");
        }
    }

    /**
     * <strong>The off switch is its OWN, and this is the half of AC-13 that a behavioural test
     * cannot express cheaply</strong>: that {@code RATE_LIMIT_ENABLED=false} does not reach the
     * bulkhead is a fact about what {@link ExpensiveReadConcurrencyLimit} can even see.
     *
     * <p>Removing a bound on your connection pool must not require disabling brute-force
     * protection on your login page, and debugging a limiter must not require removing the
     * bulkhead. The behavioural other half is {@code ExpensiveReadBulkheadSaturationTest}, whose
     * context sets {@code app.rate-limit.enabled=false} and is still refused.
     */
    @Test
    void theOccupancyBoundCannotSeeTheRateLimitMasterSwitch() {
        var dependencies = Arrays.stream(
                        ExpensiveReadConcurrencyLimit.class.getDeclaredConstructors()[0]
                                .getParameterTypes())
                .map(Class::getSimpleName)
                .toList();
        assertThat(dependencies)
                .as("ExpensiveReadConcurrencyLimit must not take RateLimitProperties: its switch "
                    + "is app.expensive-read.limit-enabled, deliberately outside "
                    + "app.rate-limit.enabled, and the moment it can read the master switch "
                    + "somebody will make it obey it — at which point RATE_LIMIT_ENABLED=false "
                    + "silently re-opens the connection-pool hole HD-182 closed")
                .doesNotContain("RateLimitProperties");
    }

    private void assertRejected(String property, String field) {
        runner.withPropertyValues(property)
                .run(context -> assertThat(context)
                        .as(property)
                        .hasFailed()
                        .getFailure()
                        // the field name lives on the deepest cause (BindValidationException)
                        .rootCause()
                        .hasMessageContaining(field));
    }

    private String properties(String file) throws Exception {
        return new String(new ClassPathResource(file).getInputStream().readAllBytes(),
                          StandardCharsets.UTF_8);
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
    @EnableConfigurationProperties(ExpensiveReadProperties.class)
    static class TestConfig {}
}
