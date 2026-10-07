package com.hamstrack.common.testsupport;

import java.util.Locale;

/**
 * <strong>One spelling of Spring's relaxed binding</strong> (HD-326): the environment variable an
 * operator sets for a configuration property, and the kebab-case property name a properties file
 * writes for a record component.
 *
 * <p>It exists because three copies of those two transforms were live at once — in
 * {@code StartupRefusalNamesTheVariableTest} (the property-to-variable mapping), in
 * {@link SeedAdminDefaults} (the {@code seed.admin} variables) and in a third one about to be
 * written for {@link Doors#configurationProperties()}. They agreed on the day they were written,
 * which is the only day a copy ever agrees: this repository's own history has five folds of one
 * email address and four walks of one cause chain, each a faithful copy of its neighbour.
 *
 * <p>The transform is not remembered, either: {@code StartupRefusalNamesTheVariableTest}'s
 * {@code theRelaxedVariableNameIsTheOneSpringActuallyResolves} holds <em>this</em> implementation
 * against a real {@code SystemEnvironmentPropertySource}, with both steps exercised (the dot and
 * the dash — Spring handles them separately), so a change here is answered by the library rather
 * than by a reviewer.
 */
public final class RelaxedNames {

    private RelaxedNames() {
    }

    /**
     * {@code app.storage.s3.access-key} &rarr; {@code APP_STORAGE_S3_ACCESS_KEY}. Upper case under
     * {@link Locale#ROOT} (a Turkish default locale folds {@code i} to a dotless {@code ı}, which
     * is R2 in {@code ArchitectureRulesTest}), dots and dashes to underscores.
     */
    public static String variableFor(String property) {
        return property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /** {@code displayName} &rarr; {@code display-name}, the spelling a properties file uses. */
    public static String kebabCase(String camel) {
        var out = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
