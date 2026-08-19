package com.hamstrack.common.config;

import com.hamstrack.workspace.entity.ProjectAccessMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-130 (S7 §10) — {@link WorkspaceProperties}.
 *
 * <p>One knob, and it is a <strong>security default</strong>, which is why it fails fast: there
 * is no third access mode, so an unrecognised value has nothing safe to fall back to. Falling
 * back to {@code OPEN} would silently create world-writable workspaces for an operator who
 * typed {@code Strict} with a capital S; falling back to {@code STRICT} would silently lock
 * every new workspace for an operator who typed {@code open } with a trailing space and never
 * looked. Not booting is the only answer that cannot be missed.
 *
 * <p>The mirror of {@code RolesPropertiesTest}'s DC/Cloud assertion applies here for the same
 * reason and is asserted the same way — as the <strong>absence of the key</strong> from both
 * profile files. Access modes are a product feature, not a plan feature (epic §13), and a
 * profile file that set the same {@code OPEN} would still be the tier-shaped seam three
 * documents promise does not exist.
 */
class WorkspacePropertiesTest {

    /**
     * The production wiring, verbatim — {@link #theBasePropertiesFileWiresTheDocumentedPlaceholder}
     * asserts that {@code application.properties} says exactly this, and every case below that
     * is about an <em>environment variable</em> rather than about a property has to travel
     * through it, because the placeholder is now the only place the default lives.
     */
    private static final String WIRING =
            "app.workspace.default-project-access-mode=${DEFAULT_PROJECT_ACCESS_MODE:OPEN}";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    /**
     * <strong>AC 1.</strong> The default is {@code OPEN}, which is byte-identically the
     * behaviour every workspace has today — the whole no-op guarantee of this slice rests on a
     * workspace that never opens the new screen being unchanged.
     *
     * <p>Bound through the <strong>real placeholder</strong> with the environment variable
     * absent, because that is now the only place the default lives: the record carries
     * {@code @NotNull} and no {@code @DefaultValue}, so "unset" has to travel through
     * {@code ${DEFAULT_PROJECT_ACCESS_MODE:OPEN}} exactly as it does in production. Asserting
     * it against a context with the key missing altogether would pin a wiring nothing ships.
     */
    @Test
    void theDefaultIsOpen() {
        runner.withPropertyValues(WIRING).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WorkspaceProperties.class).defaultProjectAccessMode())
                    .as("a drifted default would make every NEW workspace in every install "
                        + "restricted on upgrade, which is the one thing §11 A promises cannot "
                        + "happen without somebody choosing it")
                    .isEqualTo(ProjectAccessMode.OPEN);
        });
    }

    /**
     * <strong>The property is required, and an absent one aborts the boot rather than being
     * guessed</strong> (security review, round 2).
     *
     * <p>This is about an absent <strong>property key</strong> — not an absent environment
     * variable, which is {@link #theDefaultIsOpen}'s case and resolves to {@code OPEN} through
     * the placeholder (renamed in round 3: the old name said "value" and read as the operator
     * case, which is covered separately and answers differently).
     *
     * <p>It is the state {@code application.properties} makes unreachable in any shipped
     * install — {@link #theBasePropertiesFileWiresTheDocumentedPlaceholder} is what keeps that
     * true — and it is kept because it is the regression guard against
     * {@code @DefaultValue("OPEN")} coming back: with that annotation, <em>every</em> way of
     * arriving at "no value" silently became {@code OPEN}, including the ones where the
     * operator had said something.
     */
    @Test
    void anAbsentPropertyRefusesToStart() {
        runner.run(context -> assertThat(context)
                .as("binding with no value at all succeeded. The default must come from the "
                    + "placeholder in application.properties and from nowhere else, or a blank "
                    + "DEFAULT_PROJECT_ACCESS_MODE= is silently permissive again")
                .hasFailed());
    }

    @Test
    void bothModesBind() {
        assertBinds("OPEN", ProjectAccessMode.OPEN);
        assertBinds("STRICT", ProjectAccessMode.STRICT);
        // Relaxed binding is Boot's, not ours — pinned so a future "tighten it" is a decision.
        assertBinds("strict", ProjectAccessMode.STRICT);
    }

    /**
     * An unrecognised value aborts the boot rather than guessing. Both directions of the guess
     * are wrong and an operator cannot tell which one they got — see the class javadoc.
     */
    @Test
    void anUnknownModeRefusesToStart() {
        for (var bad : new String[]{"PRIVATE", "off", "true"}) {
            runner.withPropertyValues("app.workspace.default-project-access-mode=" + bad)
                    .run(context -> assertThat(context).as(bad).hasFailed());
        }
    }

    /**
     * <strong>A blank {@code DEFAULT_PROJECT_ACCESS_MODE=} aborts startup</strong> — the
     * security review's round-2 finding, and the behaviour three documents already promised.
     *
     * <p>{@code .env.prod.example}, {@code docs/self-hosting.md} and the comment above the
     * property in {@code application.properties} all say an unusable value aborts the boot. A
     * present-but-empty variable resolves the {@code ${…:OPEN}} placeholder to {@code ""}, so
     * the fallback never applies; the enum converter then yields {@code null}, the binder reads
     * that as <em>unbound</em>, and the old {@code @DefaultValue("OPEN")} supplied {@code OPEN}
     * — silently, and in the permissive direction, for an operator who had written something
     * and been misheard. The numeric caps are saved from this shape by their primitive
     * {@code int} components; an enum has no such net, so the refusal has to be written down.
     *
     * <p><strong>And the abort has to name the environment variable</strong> (round 3). The
     * default constraint message renders as {@code rejected value [null]} on
     * {@code defaultProjectAccessMode} — a property the operator never wrote — with nothing
     * about {@code DEFAULT_PROJECT_ACCESS_MODE} and no hint that the empty line is the cause,
     * unlike the unrecognised-value path, which at least echoes what was typed. So the message
     * on {@code @NotNull} is asserted here rather than only written down.
     */
    @Test
    void aBlankEnvVarRefusesToStart() {
        runner.withPropertyValues(WIRING, "DEFAULT_PROJECT_ACCESS_MODE=")
                .run(context -> {
                    assertThat(context)
                            .as("a blank DEFAULT_PROJECT_ACCESS_MODE= booted. It falls back "
                                + "PERMISSIVELY, while .env.prod.example, docs/self-hosting.md and "
                                + "application.properties all promise it aborts")
                            .hasFailed();
                    assertThat(context).getFailure()
                            .as("the abort must name DEFAULT_PROJECT_ACCESS_MODE and say to delete "
                                + "the line rather than blank it — an operator reading only "
                                + "\"rejected value [null]\" on a property they never wrote has "
                                + "nothing to act on")
                            .hasStackTraceContaining("DEFAULT_PROJECT_ACCESS_MODE")
                            .hasStackTraceContaining("delete the line");
                });
    }

    /** The real wiring, so the placeholder simulated above is the one production reads. */
    @Test
    void theBasePropertiesFileWiresTheDocumentedPlaceholder() throws Exception {
        var content = new String(new ClassPathResource("application.properties")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains(
                "app.workspace.default-project-access-mode=${DEFAULT_PROJECT_ACCESS_MODE:OPEN}");
    }

    /**
     * <strong>The DC/Cloud invariant.</strong> Access modes are a product feature and not a plan
     * feature, so neither profile file may mention this key at all — even to set the same value.
     */
    @Test
    void noProfileFileOverridesTheMode() throws Exception {
        for (var profile : new String[]{"dc", "cloud"}) {
            var file = "application-" + profile + ".properties";
            var content = new String(new ClassPathResource(file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(content)
                    .as("%s must not mention the project-access mode: any occurrence is a "
                        + "deployment-model seam, and this slice's whole DC/Cloud posture is "
                        + "that there is none", file)
                    .doesNotContain("app.workspace.default-project-access-mode")
                    .doesNotContain("DEFAULT_PROJECT_ACCESS_MODE");
        }
    }

    private void assertBinds(String value, ProjectAccessMode expected) {
        runner.withPropertyValues("app.workspace.default-project-access-mode=" + value)
                .run(context -> {
                    assertThat(context).as(value).hasNotFailed();
                    assertThat(context.getBean(WorkspaceProperties.class).defaultProjectAccessMode())
                            .isEqualTo(expected);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WorkspaceProperties.class)
    static class TestConfig {}
}
