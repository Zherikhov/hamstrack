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
 * HD-127 (S4) — {@link RolesProperties} must <strong>fail fast, never clamp</strong>, the
 * posture {@link BoardProperties}, {@link ClassificationProperties}, {@link AgileProperties}
 * and {@link LockingProperties} already take, and this test mirrors their
 * {@link ApplicationContextRunner} shape (a context that must NOT start cannot be asserted
 * on by an annotation that starts it).
 *
 * <p><strong>{@code 0} here is not the PostgreSQL semantic trap {@code lock_timeout=0} is.</strong>
 * A zero lock timeout <em>means</em> something to the database — "wait for ever" — so it would
 * bind and silently restore the DoS. Nothing reads a zero role cap as a special value; it is an
 * operator reaching for "disable custom roles", which this knob deliberately does not offer
 * (custom roles are a product feature, never a licence check — §13). The near-disable is
 * <strong>1</strong>, which is accepted, and the near-unlimited is 500. So a zero must abort the
 * boot with the property named, rather than be quietly read as 1 or as unlimited — those are
 * opposite outcomes and the operator cannot tell which they got.
 *
 * <p>The <strong>blank</strong> case is the same one {@code LockingPropertiesTest} pins, and it
 * matters here because {@code .env.prod.example} now makes an explicit promise about it
 * ("leave the line COMMENTED OUT — not blank"). {@code ROLES_MAX_CUSTOM_PER_WORKSPACE=} is how an
 * operator disables a line in a {@code .env}; the {@code ${ROLES_MAX_CUSTOM_PER_WORKSPACE:50}}
 * placeholder does not rescue it, because a default only applies to an <em>unresolvable</em>
 * placeholder and a present-but-empty variable resolves, to the empty string. The only thing
 * between that and a silent 50 is the record component being a <strong>primitive {@code int}</strong>
 * — boxed, it would convert to {@code null}, the binder would read that as unbound, and
 * {@code @DefaultValue} would hand back 50 to an operator who believed they had changed it.
 * {@link #theComponentIsAPrimitiveBecauseThatIsWhatCatchesTheBlank()} pins the mechanism.
 *
 * <p>And the DC/Cloud assertion no sibling test makes:
 * {@link #noProfileFileOverridesTheCap()}. "Identical in DC and Cloud, never profile-gated" is
 * asserted in five places ({@link RolesProperties}'s javadoc, {@code application.properties},
 * {@code .env.prod.example}, {@code docs/self-hosting.md}, {@code docs/api-dc.md}) and a one-line
 * edit to a profile file falsifies all five at once, silently, with a green suite. It is asserted
 * as the <em>absence of the key</em> rather than as a bound value on purpose: a profile file that
 * set it to the same 50 would still be a licence-check-shaped seam, and would still pass a
 * value assertion.
 */
class RolesPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultIsTheDocumentedFifty() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Promised to operators by application.properties, .env.prod.example,
            // docs/self-hosting.md and docs/api-dc.md — a drifted default changes how many
            // custom roles every install in the field may define.
            assertThat(context.getBean(RolesProperties.class).maxCustomPerWorkspace())
                    .isEqualTo(50);
        });
    }

    /**
     * {@code 0} is the value an operator writes when they want custom roles switched off, and
     * there is no such setting — see the class javadoc. {@code -1} is the same reach, and 501
     * is one past the documented ceiling. All three abort startup, naming the property: at boot
     * that message is the operator's only clue.
     */
    @Test
    void zeroNegativeAndPastTheCeilingRefuseToStart() {
        assertRejected("app.roles.max-custom-per-workspace=0");
        assertRejected("app.roles.max-custom-per-workspace=-1");
        assertRejected("app.roles.max-custom-per-workspace=501");
    }

    /**
     * Both documented bounds are inclusive — an operator following "valid range 1–500" to the
     * letter must boot on either end. {@code 1} is the near-disable the class javadoc points at;
     * {@code 500} is what {@code .env.prod.example} tells operators to write when they want no
     * practical cap.
     */
    @Test
    void bothBoundsAreInclusive() {
        assertBinds("app.roles.max-custom-per-workspace=1", 1);
        assertBinds("app.roles.max-custom-per-workspace=500", 500);
        // The value RoleApiTest drives the real limit path with must stay bindable, or that
        // suite silently stops exercising the configured cap (same reason
        // LockingPropertiesTest pins 250).
        assertBinds("app.roles.max-custom-per-workspace=3", 3);
    }

    /**
     * The blank, arriving the way it actually arrives in production: through the real
     * {@code ${ROLES_MAX_CUSTOM_PER_WORKSPACE:50}} placeholder from
     * {@code application.properties}. Pinned as a placeholder rather than as a bare empty
     * property because the whole risk lives in the difference between "absent" and
     * "present and empty" — the first takes the default, the second must not.
     */
    @Test
    void aBlankEnvVarStopsTheBootInsteadOfQuietlyMeaningTheDefault() {
        var withPlaceholder = runner.withPropertyValues(
                "app.roles.max-custom-per-workspace=${ROLES_MAX_CUSTOM_PER_WORKSPACE:50}");

        withPlaceholder.run(context -> {
            assertThat(context)
                    .as("the variable is simply absent — the placeholder default applies")
                    .hasNotFailed();
            assertThat(context.getBean(RolesProperties.class).maxCustomPerWorkspace())
                    .isEqualTo(50);
        });

        withPlaceholder.withPropertyValues("ROLES_MAX_CUSTOM_PER_WORKSPACE=")
                .run(context -> {
                    assertThat(context)
                            .as("ROLES_MAX_CUSTOM_PER_WORKSPACE= is present-but-empty, so the "
                                + "placeholder RESOLVES (to \"\") and the :50 default never "
                                + "applies — if this context starts, an operator who blanked the "
                                + "line is silently running with a cap they think they removed, "
                                + "which .env.prod.example explicitly promises they are not")
                            .hasFailed();
                    assertThat(failureOf(context))
                            .contains("app.roles.max-custom-per-workspace");
                });

        withPlaceholder.withPropertyValues("ROLES_MAX_CUSTOM_PER_WORKSPACE=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RolesProperties.class).maxCustomPerWorkspace())
                            .isEqualTo(7);
                });
    }

    /**
     * The test above hardcodes the placeholder, so on its own it proves only that
     * <em>a</em> {@code ${VAR:50}} behaves that way. This pins that the real
     * {@code application.properties} is still wired exactly that way — same variable name,
     * same default — because everything the blank test asserts is worthless if production
     * reads a differently-named variable or a different fallback.
     */
    @Test
    void theBasePropertiesFileWiresTheDocumentedPlaceholder() throws Exception {
        var content = new String(new ClassPathResource("application.properties")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content)
                .as("the variable name and the :50 fallback are what "
                    + "aBlankEnvVarStopsTheBootInsteadOfQuietlyMeaningTheDefault() simulates, "
                    + "and what .env.prod.example, docs/self-hosting.md and docs/api-dc.md all "
                    + "promise operators")
                .contains("app.roles.max-custom-per-workspace="
                          + "${ROLES_MAX_CUSTOM_PER_WORKSPACE:50}");
    }

    /**
     * The mechanism behind the test above, asserted directly so a future "tidy it to
     * {@code Integer}" fails here loudly instead of turning
     * {@link #aBlankEnvVarStopsTheBootInsteadOfQuietlyMeaningTheDefault()} into a test of
     * nothing. {@code @Min(1)} does <em>not</em> catch the blank: a boxed component binds to
     * {@code null}, the binder treats that as unbound, {@code @DefaultValue} supplies 50, and
     * validation then passes on a value the operator never wrote.
     */
    @Test
    void theComponentIsAPrimitiveBecauseThatIsWhatCatchesTheBlank() throws Exception {
        assertThat(RolesProperties.class.getDeclaredField("maxCustomPerWorkspace").getType())
                .as("maxCustomPerWorkspace must stay a primitive int — boxed, a blank "
                    + "ROLES_MAX_CUSTOM_PER_WORKSPACE= converts to null, the binder reads it as "
                    + "unbound, and @DefaultValue silently applies 50")
                .isEqualTo(int.class);
    }

    /**
     * <strong>The DC/Cloud invariant, asserted where a properties edit cannot hide.</strong>
     * Custom roles are a product feature and not a plan feature, so the cap is the same number
     * in both deployment modes and neither profile file may mention it. Asserted as absence of
     * the key: any occurrence at all — even one setting the same 50 — is the licence-check seam
     * this knob's javadoc, {@code application.properties}, {@code .env.prod.example},
     * {@code docs/self-hosting.md} and {@code docs/api-dc.md} all promise does not exist.
     */
    @Test
    void noProfileFileOverridesTheCap() throws Exception {
        for (var profile : new String[]{"dc", "cloud"}) {
            var file = "application-" + profile + ".properties";
            var content = new String(new ClassPathResource(file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(content)
                    .as("%s must not mention app.roles.max-custom-per-workspace — the cap is "
                        + "identical in dc and cloud by design (a sprawl guard, never a licence "
                        + "check), and a profile override is exactly the tier-shaped seam five "
                        + "documents promise is absent", file)
                    .doesNotContain("app.roles.max-custom-per-workspace")
                    .doesNotContain("ROLES_MAX_CUSTOM_PER_WORKSPACE");
        }
    }

    private void assertBinds(String property, int expected) {
        runner.withPropertyValues(property)
                .run(context -> {
                    assertThat(context).as(property).hasNotFailed();
                    assertThat(context.getBean(RolesProperties.class).maxCustomPerWorkspace())
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
                        .hasMessageContaining("maxCustomPerWorkspace"));
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
    @EnableConfigurationProperties(RolesProperties.class)
    static class TestConfig {}
}
