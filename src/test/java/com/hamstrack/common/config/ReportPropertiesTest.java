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
 * {@link ReportProperties} must <strong>fail fast, never clamp</strong> — the posture
 * {@link BoardProperties}, {@link AgileProperties} and {@link ClassificationProperties}
 * already take, and the one this class's own runtime behaviour depends on: the flow
 * endpoint refuses an over-wide window by NAMING {@code app.reports.max-window-days}, so a
 * value that bound quietly to nonsense would be quoted back to callers in an error message.
 * (An {@link ApplicationContextRunner} rather than {@code @SpringBootTest}, because a
 * context that must NOT start cannot be asserted on by an annotation that starts it.)
 *
 * <p>Also pinned: the <strong>documented defaults</strong>. 365, 20000 and 60 are promised to
 * operators by {@code application.properties}, {@code .env.prod.example} and
 * {@code docs/self-hosting.md}, and {@code max-rows} is echoed to every API client as
 * {@code meta.cap} — a drifted default silently changes a documented response field.
 *
 * <p><strong>Round 2 (item 8) brought this class up to the {@code RolesPropertiesTest}
 * standard</strong>, which had three assertions this one was missing and which are the ones
 * that actually catch production mistakes:
 * <ul>
 *   <li>the <strong>blank</strong> case ({@code REPORTS_MAX_WINDOW_DAYS=}), which is how an
 *       operator disables a line in a {@code .env} and which the {@code :365} placeholder
 *       does <em>not</em> rescue — a present-but-empty variable resolves, to the empty
 *       string;</li>
 *   <li>the <strong>primitive</strong> reflection test, because the primitive component is
 *       the entire mechanism that turns that blank into a refused boot rather than a silent
 *       365;</li>
 *   <li>the <strong>real placeholder</strong> in {@code application.properties}, because a
 *       blank test that simulates {@code ${SOMETHING_ELSE:365}} proves nothing about what
 *       production reads.</li>
 * </ul>
 * Plus the DC/Cloud assertion: {@code app.reports.*} appears in neither profile file, even
 * set to the same value — reporting depth is a product property, and a profile override is a
 * tier-shaped seam whatever number it holds.
 */
class ReportPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultsAreTheDocumentedOnes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var props = context.getBean(ReportProperties.class);
            assertThat(props.maxWindowDays()).isEqualTo(365);
            assertThat(props.maxRows()).isEqualTo(20_000);
            assertThat(props.requestsPerMinute()).isEqualTo(60);
        });
    }

    @Test
    void zeroOrNegativeRefusesToStart() {
        assertRejected("app.reports.max-window-days=0", "maxWindowDays");
        assertRejected("app.reports.max-window-days=-1", "maxWindowDays");
        assertRejected("app.reports.max-rows=0", "maxRows");
        assertRejected("app.reports.max-rows=-1", "maxRows");
        // 0 is the value an operator writes to mean "no reports for anyone", which this knob
        // does not offer: reports are a product feature, and the throttle is a budget, not a
        // switch. The switch is app.rate-limit.enabled.
        assertRejected("app.reports.requests-per-minute=0", "requestsPerMinute");
        assertRejected("app.reports.requests-per-minute=-1", "requestsPerMinute");
    }

    /**
     * The upper bounds are inclusive — an operator who follows the documented range to the
     * letter must be able to boot — and one over is a refusal. The window bound matters
     * most: at daily buckets the window length IS the number of points in the response, so
     * an unbounded value is an unbounded response.
     *
     * <p><strong>{@code max-rows} was lowered from 200 000 to 50 000 in round 2 of R3</strong>, and
     * the point of a ceiling is that it be survivable. A ceiling is a promise that every value
     * under it boots AND runs; at 200 000 one authenticated GET materialised roughly 380 MB of
     * transient heap worst case (~1.9 KB per shipped row, three copies alive at once — the
     * arithmetic is in {@link ReportProperties}), so an operator who stayed inside the documented
     * range could OOM the instance with a single request, and the throttle bounds request rate
     * rather than concurrency. 50 000 is ~95 MB, against an ASSUMED 512 MB reference heap — no
     * heap bound is configured anywhere (HD-152), so what an instance actually has follows host
     * RAM. The default of 20 000 is unchanged and was never the problem.
     */
    @Test
    void upperBoundsAreInclusiveAndOneOverRefusesToStart() {
        runner.withPropertyValues("app.reports.max-window-days=3650", "app.reports.max-rows=50000",
                        "app.reports.requests-per-minute=10000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var props = context.getBean(ReportProperties.class);
                    assertThat(props.maxWindowDays()).isEqualTo(3650);
                    assertThat(props.maxRows()).isEqualTo(50_000);
                    assertThat(props.requestsPerMinute()).isEqualTo(10_000);
                });
        assertRejected("app.reports.max-window-days=3651", "maxWindowDays");
        assertRejected("app.reports.max-rows=50001", "maxRows");
        assertRejected("app.reports.requests-per-minute=10001", "requestsPerMinute");
    }

    /** The overrides {@code FlowReportWindowTest} and {@code FlowReportThrottleTest} drive the real endpoints with. */
    @Test
    void inRangeOverrideBindsNormally() {
        runner.withPropertyValues("app.reports.max-window-days=30", "app.reports.requests-per-minute=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var props = context.getBean(ReportProperties.class);
                    assertThat(props.maxWindowDays()).isEqualTo(30);
                    assertThat(props.requestsPerMinute()).isEqualTo(3);
                });
    }

    /**
     * The blank, arriving the way it actually arrives in production: through the real
     * {@code ${REPORTS_MAX_WINDOW_DAYS:365}} placeholder from {@code application.properties}.
     * Pinned as a placeholder rather than as a bare empty property because the whole risk
     * lives in the difference between "absent" and "present and empty" — the first takes the
     * default, the second must not.
     *
     * <p>Which is exactly what {@code .env.prod.example} and {@code docs/self-hosting.md}
     * now promise operators ("leave the line COMMENTED OUT — not blank"), and a promise no
     * test made until round 2.
     */
    @Test
    void aBlankEnvVarStopsTheBootInsteadOfQuietlyMeaningTheDefault() {
        var withPlaceholder = runner.withPropertyValues(
                "app.reports.max-window-days=${REPORTS_MAX_WINDOW_DAYS:365}");

        withPlaceholder.run(context -> {
            assertThat(context)
                    .as("the variable is simply absent — the placeholder default applies")
                    .hasNotFailed();
            assertThat(context.getBean(ReportProperties.class).maxWindowDays()).isEqualTo(365);
        });

        withPlaceholder.withPropertyValues("REPORTS_MAX_WINDOW_DAYS=")
                .run(context -> {
                    assertThat(context)
                            .as("REPORTS_MAX_WINDOW_DAYS= is present-but-empty, so the "
                                + "placeholder RESOLVES (to \"\") and the :365 default never "
                                + "applies — if this context starts, an operator who blanked "
                                + "the line is silently running with a cap they think they "
                                + "removed, and that cap is quoted back to API callers in a "
                                + "400 as though they had chosen it")
                            .hasFailed();
                    assertThat(failureOf(context)).contains("app.reports.max-window-days");
                });

        withPlaceholder.withPropertyValues("REPORTS_MAX_WINDOW_DAYS=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ReportProperties.class).maxWindowDays()).isEqualTo(7);
                });
    }

    /**
     * The test above hardcodes the placeholder, so on its own it proves only that <em>a</em>
     * {@code ${VAR:365}} behaves that way. This pins that the real
     * {@code application.properties} is still wired exactly that way — same variable names,
     * same defaults — because everything the blank test asserts is worthless if production
     * reads a differently-named variable or a different fallback.
     */
    @Test
    void theBasePropertiesFileWiresTheDocumentedPlaceholders() throws Exception {
        var content = new String(new ClassPathResource("application.properties")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content)
                .as("the variable names and fallbacks are what the blank test simulates, and "
                    + "what .env.prod.example and docs/self-hosting.md promise operators")
                .contains("app.reports.max-window-days=${REPORTS_MAX_WINDOW_DAYS:365}")
                .contains("app.reports.max-rows=${REPORTS_MAX_ROWS:20000}")
                .contains("app.reports.requests-per-minute=${REPORTS_REQUESTS_PER_MINUTE:60}");
    }

    /**
     * The mechanism behind the blank test, asserted directly so a future "tidy it to
     * {@code Integer}" fails here loudly instead of turning that test into a test of nothing.
     * {@code @Min(1)} does <em>not</em> catch the blank: a boxed component binds to
     * {@code null}, the binder treats that as unbound, {@code @DefaultValue} supplies the
     * default, and validation then passes on a value the operator never wrote.
     */
    @Test
    void everyComponentIsAPrimitiveBecauseThatIsWhatCatchesTheBlank() throws Exception {
        for (var name : new String[]{"maxWindowDays", "maxRows", "requestsPerMinute"}) {
            assertThat(ReportProperties.class.getDeclaredField(name).getType())
                    .as("%s must stay a primitive int — boxed, a blank REPORTS_* variable "
                        + "converts to null, the binder reads it as unbound, and @DefaultValue "
                        + "silently applies the documented default", name)
                    .isEqualTo(int.class);
        }
    }

    /**
     * <strong>The DC/Cloud invariant, asserted where a properties edit cannot hide.</strong>
     * Reporting depth is a product property, not a plan property, so these numbers are the
     * same in both deployment modes and neither profile file may mention them. Asserted as
     * absence of the key rather than as a bound value on purpose: a profile file setting the
     * same 365 would still be the tier-shaped seam four documents promise does not exist, and
     * would still pass a value assertion.
     */
    @Test
    void noProfileFileOverridesAnyReportBound() throws Exception {
        for (var profile : new String[]{"dc", "cloud"}) {
            var file = "application-" + profile + ".properties";
            var content = new String(new ClassPathResource(file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(content)
                    .as("%s must not mention app.reports.* — reporting depth is identical in "
                        + "dc and cloud by design, and if it were ever limited per deployment "
                        + "it would be by lowering a number, never by a second code path", file)
                    .doesNotContain("app.reports.")
                    .doesNotContain("REPORTS_");
        }
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

    /** Every message in the failure chain, so an assertion can look at all of them. */
    private String failureOf(AssertableApplicationContext context) {
        var out = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            out.append(t.getMessage()).append('\n');
        }
        return out.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReportProperties.class)
    static class TestConfig {}
}
