package com.hamstrack.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlanningProperties} held to the {@link SearchPropertiesTest} standard (HD-174) — because
 * it arrives, as every budget in this product has, with a bound in an annotation and a promise in
 * five documents, and nothing joining them.
 *
 * <p>An {@link ApplicationContextRunner} rather than {@code @SpringBootTest}, because a context
 * that must NOT start cannot be asserted on by an annotation that starts it.
 */
class PlanningPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    /**
     * 240 is promised to operators by {@code application.properties}, {@code .env.prod.example} and
     * the operator tables in {@code docs/api-dc.md} / {@code docs/api-cloud.md} /
     * {@code docs/self-hosting.md}. A drifted default is a documented number that is not the one
     * running — and this one is a <em>derived</em> number (~3.4x the busiest single tab), so a
     * drift also silently invalidates the derivation the documents carry.
     */
    @Test
    void theDefaultIsTheDocumentedOne() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PlanningProperties.class).requestsPerMinute()).isEqualTo(240);
        });
    }

    /**
     * There is deliberately no "unlimited": 0 is the value an operator writes to mean "turn it
     * off", and the off switch is {@code app.rate-limit.enabled}, which turns off every limiter
     * that HAS an off switch. Note the direction that switch does NOT reach: it leaves the
     * expensive-read occupancy bound in force, so turning the planning rate budget off does not
     * re-open the connection-pool hole HD-174's other half closes.
     */
    @Test
    void zeroOrNegativeRefusesToStart() {
        assertRejected("app.planning.requests-per-minute=0");
        assertRejected("app.planning.requests-per-minute=-1");
    }

    /**
     * The bound is inclusive at both ends — an operator who follows the documented 1–10000 to the
     * letter must be able to boot — and one over is a refusal, never a clamp. The same range as
     * every other per-principal request budget on purpose: they are pots of the same kind of
     * thing, and a reader comparing their operator rows should not have to hold a range per pot
     * in their head. Deliberately not counted: the sibling sentence in {@code SearchPropertiesTest}
     * carried a count and went stale the release this pot was added.
     */
    @Test
    void theDocumentedRangeBootsAndOneOverRefusesToStart() {
        for (var value : new int[]{1, 10_000}) {
            runner.withPropertyValues("app.planning.requests-per-minute=" + value)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(PlanningProperties.class).requestsPerMinute())
                                .isEqualTo(value);
                    });
        }
        assertRejected("app.planning.requests-per-minute=10001");
    }

    /** The override {@code PlanningThrottleTest} drives the real endpoints with. */
    @Test
    void inRangeOverrideBindsNormally() {
        runner.withPropertyValues("app.planning.requests-per-minute=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PlanningProperties.class).requestsPerMinute())
                            .isEqualTo(3);
                });
    }

    /**
     * The blank, arriving the way it actually arrives in production: through the real
     * {@code ${PLANNING_REQUESTS_PER_MINUTE:240}} placeholder. The whole risk lives in the
     * difference between "absent" and "present and empty" — the first takes the default, the second
     * must stop the boot, which is exactly what {@code .env.prod.example} promises operators
     * ("leave the line COMMENTED OUT — not blank").
     */
    @Test
    void aBlankEnvVarStopsTheBootInsteadOfQuietlyMeaningTheDefault() {
        var withPlaceholder = runner.withPropertyValues(
                "app.planning.requests-per-minute=${PLANNING_REQUESTS_PER_MINUTE:240}");

        withPlaceholder.run(context -> {
            assertThat(context)
                    .as("the variable is simply absent — the placeholder default applies")
                    .hasNotFailed();
            assertThat(context.getBean(PlanningProperties.class).requestsPerMinute()).isEqualTo(240);
        });

        withPlaceholder.withPropertyValues("PLANNING_REQUESTS_PER_MINUTE=")
                .run(context -> {
                    assertThat(context)
                            .as("PLANNING_REQUESTS_PER_MINUTE= is present-but-empty, so the "
                                + "placeholder RESOLVES (to \"\") and the :240 default never "
                                + "applies — if this context starts, an operator who blanked the "
                                + "line is silently running with a budget they think they removed")
                            .hasFailed();
                    assertThat(failureOf(context)).contains("app.planning.requests-per-minute");
                });

        withPlaceholder.withPropertyValues("PLANNING_REQUESTS_PER_MINUTE=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PlanningProperties.class).requestsPerMinute())
                            .isEqualTo(7);
                });
    }

    /**
     * The test above hardcodes the placeholder, so on its own it proves only that <em>a</em>
     * {@code ${VAR:240}} behaves that way. This pins that the real {@code application.properties}
     * is still wired exactly that way — same variable name, same fallback — because everything the
     * blank test asserts is worthless if production reads a differently-named variable.
     */
    @Test
    void theBasePropertiesFileWiresTheDocumentedPlaceholder() throws Exception {
        var content = new String(new ClassPathResource("application.properties")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content)
                .as("the variable name and fallback are what the blank test simulates, and what "
                    + ".env.prod.example and the operator tables promise")
                .contains("app.planning.requests-per-minute=${PLANNING_REQUESTS_PER_MINUTE:240}");
    }

    /**
     * The mechanism behind the blank test, asserted directly so a future "tidy it to
     * {@code Integer}" fails here loudly instead of turning that test into a test of nothing.
     * {@code @Min(1)} does <em>not</em> catch the blank: a boxed component binds to {@code null},
     * the binder treats that as unbound, {@code @DefaultValue} supplies the default, and validation
     * then passes on a value the operator never wrote.
     */
    @Test
    void theComponentIsAPrimitiveBecauseThatIsWhatCatchesTheBlank() throws Exception {
        assertThat(PlanningProperties.class.getDeclaredField("requestsPerMinute").getType())
                .as("requestsPerMinute must stay a primitive int — boxed, a blank "
                    + "PLANNING_REQUESTS_PER_MINUTE converts to null, the binder reads it as "
                    + "unbound, and @DefaultValue silently applies the documented default")
                .isEqualTo(int.class);
    }

    /**
     * <strong>The DC/Cloud invariant, asserted where a properties edit cannot hide.</strong> How
     * often a member may drag a card is a product property, not a plan property. Asserted as
     * absence of the key rather than as a bound value on purpose: a profile file setting the same
     * 240 would still be the tier-shaped seam the documentation promises does not exist, and would
     * still pass a value assertion.
     */
    @Test
    void noProfileFileOverridesThePlanningBudget() throws Exception {
        for (var profile : new String[]{"dc", "cloud"}) {
            var file = "application-" + profile + ".properties";
            var content = new String(new ClassPathResource(file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(content)
                    .as("%s must not mention app.planning.* — the planning budget is identical in "
                        + "dc and cloud by design, and if it were ever limited per deployment it "
                        + "would be by lowering a number, never by a second code path", file)
                    .doesNotContain("app.planning.")
                    .doesNotContain("PLANNING_");
        }
    }

    /**
     * <strong>HD-174 added no number that has to sit below {@code DB_POOL_MAX_SIZE}</strong>, and
     * that is the whole of ADR-0031's operational payoff rather than an incidental fact: a literal
     * that must stay under the pool is what crash-looped every small self-host on the HD-182
     * upgrade, and a pair of them would be that hazard squared.
     *
     * <p>Asserted as the absence of an occupancy property on this prefix, because the failure mode
     * is somebody adding one "for symmetry" — which would turn {@code ExpensiveReadShare}'s
     * derive-from-the-pool default into a partition that is degenerate on the pools this project
     * recommends (a pool of 4 gives 1 permit per surface).
     */
    @Test
    void thePlanningBudgetHasNoOccupancyDialOfItsOwn() {
        assertThat(PlanningProperties.class.getRecordComponents())
                .as("app.planning.* must stay a RATE budget only. An occupancy ceiling here would "
                    + "have to sit below DB_POOL_MAX_SIZE jointly with the expensive-read share, "
                    + "which is a partition problem and is degenerate on small pools — ADR-0031. "
                    + "The planning surface takes permits from the EXISTING share")
                .hasSize(1)
                .allMatch(component -> component.getName().equals("requestsPerMinute"));
    }

    private void assertRejected(String property) {
        runner.withPropertyValues(property)
                .run(context -> assertThat(context)
                        .as(property)
                        .hasFailed()
                        .getFailure()
                        // the field name lives on the deepest cause (BindValidationException)
                        .rootCause()
                        .hasMessageContaining("requestsPerMinute"));
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
    @EnableConfigurationProperties(PlanningProperties.class)
    static class TestConfig {}
}
