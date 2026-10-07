package com.hamstrack.common.seed;

import com.hamstrack.common.testsupport.SeedAdminDefaults;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>{@link SeedAdminProperties} — the three things that stop being free once the binding is a
 * record</strong> (HD-326).
 *
 * <p>{@code @Value("${seed.admin.email:}")} carried its default inside the placeholder, so an
 * unconfigured install read {@code ""} and {@link DataSeeder#run} could ask {@code isBlank()} with
 * no null check. A record component defaults to {@code null} instead — MEASURED, see
 * {@link #anUnconfiguredInstallBindsEmptyStringsRatherThanNulls} — and {@code null.isBlank()}
 * during context refresh is a boot that fails for a reason no operator can act on, on every
 * install that seeds nobody, which is the default and the whole Cloud fleet. So the defaults are
 * asserted twice: once behaviourally, by binding nothing, and once structurally, so a fourth
 * component added later cannot omit one.
 *
 * <p>The structural case reads {@code @DefaultValue} off the <strong>canonical constructor's
 * parameter</strong>, which {@code SeedAdminDefaults} explains and this class proves: the
 * annotation declares {@code @Target({PARAMETER})}, so the obvious reflective reader — the record
 * component — answers {@code null} for every member of every record in this codebase.
 * {@link #theReaderWouldNameAComponentThatDeclaredNoDefault} plants exactly that and requires the
 * reader to tell the two apart.
 */
class SeedAdminPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(SeedAdminProperties.class)
    static class Binding {
    }

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(Binding.class);

    /**
     * The behavioural half. No {@code seed.admin.*} property is set — the default DC install before
     * an operator edits anything, and every Cloud install for ever — and the bound values must be
     * the ones every branch of {@link DataSeeder} reads as "not configured".
     */
    @Test
    void anUnconfiguredInstallBindsEmptyStringsRatherThanNulls() {
        RUNNER.run(context -> {
            var bound = context.getBean(SeedAdminProperties.class);
            assertThat(bound.email())
                    .withFailMessage("""

                            seed.admin.email bound to null on an install that sets nothing, so
                            DataSeeder.run()'s `seedAdmin.email().isBlank()` throws a
                            NullPointerException while the context is refreshing - on every
                            installation that seeds no administrator, which is the default.

                            A record component with no @DefaultValue binds to null; the
                            @Value("${seed.admin.email:}") this replaced carried its default in the
                            placeholder and yielded "". Put @DefaultValue("") back on the component.""")
                    .isEqualTo("");
            assertThat(bound.password()).isEqualTo("");
            // Defaulted rather than empty, and that difference is what keeps it out of the
            // template rule in EnvTemplateGuardTest: a display name is not an identity.
            assertThat(bound.displayName()).isEqualTo("Admin");

            // And the whole point of the conversion: the guards read a value that is never null,
            // so none of the seven read sites needs a null check of its own.
            assertThat(bound.email().isBlank()).isTrue();
        });
    }

    /**
     * The structural half, so a fourth component cannot arrive without a default and be found by a
     * boot instead of by a build. The population is the record's own components, and the failure
     * names the component rather than a count.
     */
    @Test
    void everyComponentDeclaresADefaultSoNothingBindsToNull() {
        Map<String, String> defaults = SeedAdminDefaults.declaredDefaults();

        assertThat(defaults)
                .withFailMessage("""

                        NO seed.admin COMPONENT WAS READ, so this case is checking nothing. The
                        population is SeedAdminProperties' record components; if that type moved or
                        stopped being a record, point SeedAdminDefaults at the new shape.""")
                .hasSizeGreaterThanOrEqualTo(3);

        var undefaulted = defaults.entrySet().stream()
                .filter(entry -> entry.getValue() == null)
                .map(entry -> "  %s (%s / %s) declares no @DefaultValue".formatted(
                        entry.getKey(),
                        SeedAdminDefaults.propertyFor(entry.getKey()),
                        SeedAdminDefaults.variableFor(entry.getKey())))
                .toList();

        // Printed green or red: a population that collapsed to one member still prints one member.
        System.out.println("[HD-326] seed.admin components and their declared defaults: " + defaults);

        assertThat(undefaulted)
                .withFailMessage("""

                        A seed.admin COMPONENT DECLARES NO DEFAULT, so it binds to null on every
                        install that does not set it and the first read of it during context refresh
                        is a NullPointerException.

                        %s

                        Give it @DefaultValue("..."). Boot's binder has no other instruction for an
                        absent property on a record, and DataSeeder's read sites deliberately carry
                        no null checks because this case exists.""".formatted(String.join("\n", undefaulted)))
                .isEmpty();
    }

    // ------------------------------------------------------------------ the reader's own proof

    /** Planted: one component with a default, one without. A correct reader separates them. */
    @ConfigurationProperties("hd326.planted")
    record Planted(@DefaultValue("") String defaulted, String undefaulted) {
    }

    /**
     * <strong>The negative control for the reflective reader</strong>, which is the only part of
     * this file that could pass while measuring nothing.
     *
     * <p>{@code @DefaultValue} is {@code @Target({PARAMETER})}, so
     * {@code getRecordComponents()[i].getAnnotation(DefaultValue.class)} is {@code null} for BOTH
     * components of {@link Planted} — the one that declares a default and the one that does not.
     * This case asserts that difference is visible, and it asserts the broken reading is in fact
     * blind, so the reason {@code SeedAdminDefaults} reads the constructor parameter is a measured
     * fact in the build rather than a sentence in a javadoc.
     */
    @Test
    void theReaderWouldNameAComponentThatDeclaredNoDefault() {
        var viaComponent = new ArrayList<String>();
        for (var component : Planted.class.getRecordComponents()) {
            if (component.getAnnotation(DefaultValue.class) != null) {
                viaComponent.add(component.getName());
            }
        }
        assertThat(viaComponent)
                .withFailMessage("""

                        @DefaultValue is now visible on a RECORD COMPONENT, which it was not when
                        SeedAdminDefaults was written (@Target({PARAMETER}) only, measured
                        2026-10-02). That is good news, but this file's reasoning for reading the
                        canonical constructor instead has changed, so re-read it rather than
                        deleting this case: a reader keyed on the component used to be blind to
                        every member, which is why the constructor is read at all.""")
                .isEmpty();

        assertThat(SeedAdminDefaults.declaredDefaultsOf(Planted.class))
                .withFailMessage("""

                        THE @DefaultValue READER CANNOT TELL A DEFAULTED COMPONENT FROM AN
                        UNDEFAULTED ONE, so everyComponentDeclaresADefaultSoNothingBindsToNull above
                        is green whatever SeedAdminProperties declares - it either excuses every
                        component or accuses every component.

                        Read the annotation from the canonical constructor's PARAMETER. It is not on
                        the component, not on the backing field and not on the accessor.""")
                .containsEntry("defaulted", "")
                .containsEntry("undefaulted", null);
    }

    /**
     * The prefix, read from whichever of {@code value()} / {@code prefix()} the annotation was
     * written with — both spellings are used in this codebase and a reader that consulted one would
     * return {@code ""} for half of them, silently keying every derived variable name off nothing.
     */
    @Test
    void thePrefixIsReadWhicheverWayTheAnnotationIsWritten() {
        assertThat(SeedAdminDefaults.prefix()).isEqualTo("seed.admin");
        assertThat(SeedAdminDefaults.prefixOf(Planted.class)).isEqualTo("hd326.planted");
        assertThat(SeedAdminDefaults.prefixOf(PrefixAttribute.class)).isEqualTo("hd326.attribute");

        // And the relaxed spellings the operator actually types, including the camel hump that
        // becomes a dash in the property and an underscore in the variable.
        assertThat(SeedAdminDefaults.propertyFor("displayName")).isEqualTo("seed.admin.display-name");
        assertThat(SeedAdminDefaults.variableFor("displayName")).isEqualTo("SEED_ADMIN_DISPLAY_NAME");
        assertThat(SeedAdminDefaults.variableFor("email")).isEqualTo("SEED_ADMIN_EMAIL");
    }

    /** The other spelling of the same annotation. */
    @ConfigurationProperties(prefix = "hd326.attribute")
    record PrefixAttribute(@DefaultValue("") String value) {
    }
}
