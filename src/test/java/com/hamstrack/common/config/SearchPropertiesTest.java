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
 * {@link SearchProperties} held to the {@link ReportPropertiesTest} standard (HD-140 R6 round 3,
 * item 4) — because it arrived with a bound in an annotation, a promise in four documents, and
 * nothing joining them.
 *
 * <p>That is the R3 mechanism verbatim: {@code app.reports.*} once had the same gap, the validator
 * and the prose drifted, and two of four documents ended up describing a range that aborts the
 * boot. There are eight {@code *PropertiesTest} classes in this codebase for that reason; this is
 * the ninth, and it exists so the search budget cannot repeat it.
 *
 * <p>An {@link ApplicationContextRunner} rather than {@code @SpringBootTest}, because a context
 * that must NOT start cannot be asserted on by an annotation that starts it.
 */
class SearchPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    /**
     * 120 is promised to operators by {@code application.properties}, {@code .env.prod.example} and
     * the operator tables in {@code docs/api-dc.md} / {@code docs/self-hosting.md}. A drifted
     * default is a documented number that is not the one running.
     */
    @Test
    void theDefaultIsTheDocumentedOne() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SearchProperties.class).requestsPerMinute()).isEqualTo(120);
        });
    }

    /**
     * There is deliberately no "unlimited": 0 is the value an operator writes to mean "turn it
     * off", and the off switch is {@code app.rate-limit.enabled}, which turns off every limiter
     * that HAS an off switch rather than silently leaving this surface open. (One is deliberately
     * outside it: the backlog-rebalance cooldown is a fixed internal safety valve with no variable
     * and no switch - see application.properties and docs/self-hosting.md.)
     */
    @Test
    void zeroOrNegativeRefusesToStart() {
        assertRejected("app.search.requests-per-minute=0");
        assertRejected("app.search.requests-per-minute=-1");
    }

    /**
     * The bound is inclusive at both ends — an operator who follows the documented 1–10000 to the
     * letter must be able to boot — and one over is a refusal, never a clamp. Same range as the
     * reports budget on purpose: they are two pots of the same kind of thing, and a reader
     * comparing the two operator rows should not have to hold two ranges in their head.
     */
    @Test
    void theDocumentedRangeBootsAndOneOverRefusesToStart() {
        for (var value : new int[]{1, 10_000}) {
            runner.withPropertyValues("app.search.requests-per-minute=" + value)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(SearchProperties.class).requestsPerMinute())
                                .isEqualTo(value);
                    });
        }
        assertRejected("app.search.requests-per-minute=10001");
    }

    /** The override {@code SearchThrottleTest} drives the real endpoints with. */
    @Test
    void inRangeOverrideBindsNormally() {
        runner.withPropertyValues("app.search.requests-per-minute=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SearchProperties.class).requestsPerMinute())
                            .isEqualTo(3);
                });
    }

    /**
     * The blank, arriving the way it actually arrives in production: through the real
     * {@code ${SEARCH_REQUESTS_PER_MINUTE:120}} placeholder. The whole risk lives in the difference
     * between "absent" and "present and empty" — the first takes the default, the second must stop
     * the boot, which is exactly what {@code .env.prod.example} promises operators ("leave the line
     * COMMENTED OUT — not blank").
     */
    @Test
    void aBlankEnvVarStopsTheBootInsteadOfQuietlyMeaningTheDefault() {
        var withPlaceholder = runner.withPropertyValues(
                "app.search.requests-per-minute=${SEARCH_REQUESTS_PER_MINUTE:120}");

        withPlaceholder.run(context -> {
            assertThat(context)
                    .as("the variable is simply absent — the placeholder default applies")
                    .hasNotFailed();
            assertThat(context.getBean(SearchProperties.class).requestsPerMinute()).isEqualTo(120);
        });

        withPlaceholder.withPropertyValues("SEARCH_REQUESTS_PER_MINUTE=")
                .run(context -> {
                    assertThat(context)
                            .as("SEARCH_REQUESTS_PER_MINUTE= is present-but-empty, so the "
                                + "placeholder RESOLVES (to \"\") and the :120 default never "
                                + "applies — if this context starts, an operator who blanked the "
                                + "line is silently running with a budget they think they removed")
                            .hasFailed();
                    assertThat(failureOf(context)).contains("app.search.requests-per-minute");
                });

        withPlaceholder.withPropertyValues("SEARCH_REQUESTS_PER_MINUTE=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SearchProperties.class).requestsPerMinute())
                            .isEqualTo(7);
                });
    }

    /**
     * The test above hardcodes the placeholder, so on its own it proves only that <em>a</em>
     * {@code ${VAR:120}} behaves that way. This pins that the real {@code application.properties}
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
                .contains("app.search.requests-per-minute=${SEARCH_REQUESTS_PER_MINUTE:120}");
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
        assertThat(SearchProperties.class.getDeclaredField("requestsPerMinute").getType())
                .as("requestsPerMinute must stay a primitive int — boxed, a blank "
                    + "SEARCH_REQUESTS_PER_MINUTE converts to null, the binder reads it as "
                    + "unbound, and @DefaultValue silently applies the documented default")
                .isEqualTo(int.class);
    }

    /**
     * <strong>The DC/Cloud invariant, asserted where a properties edit cannot hide.</strong> How
     * much a member may search is a product property, not a plan property. Asserted as absence of
     * the key rather than as a bound value on purpose: a profile file setting the same 120 would
     * still be the tier-shaped seam the documentation promises does not exist, and would still pass
     * a value assertion.
     */
    @Test
    void noProfileFileOverridesTheSearchBudget() throws Exception {
        for (var profile : new String[]{"dc", "cloud"}) {
            var file = "application-" + profile + ".properties";
            var content = new String(new ClassPathResource(file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(content)
                    .as("%s must not mention app.search.* — the search budget is identical in dc "
                        + "and cloud by design, and if it were ever limited per deployment it "
                        + "would be by lowering a number, never by a second code path", file)
                    .doesNotContain("app.search.")
                    .doesNotContain("SEARCH_");
        }
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
    @EnableConfigurationProperties(SearchProperties.class)
    static class TestConfig {}
}
