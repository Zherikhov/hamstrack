package com.hamstrack.common.testsupport;

import com.hamstrack.common.seed.SeedAdminProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <strong>The {@code seed.admin.*} properties and their declared defaults, read off the real
 * class</strong> (HD-326). One reader, because two populations ask about the same three components:
 * {@code EnvTemplateGuardTest} asks which of them may never be shipped filled in by a template
 * (the ones defaulting to EMPTY — the ones the seeder reads as "not configured"), and
 * {@code SeedAdminPropertiesTest} asks whether every component declares a default at all. Those
 * were one {@code @Value}-shaped regex in {@code EnvTemplateGuardTest} before this class existed;
 * a copy of it would be the second.
 *
 * <h2>Why the canonical constructor and not the record component</h2>
 * <strong>MEASURED (2026-10-02)</strong>, and it is the whole reason this is a class rather than
 * three lines at each call site: {@code @DefaultValue} declares
 * {@code @Target({ElementType.PARAMETER})}. Not {@code RECORD_COMPONENT}, and not {@code FIELD}.
 * So against {@link SeedAdminProperties} as it ships:
 * <pre>
 * email        component=null     field=null     accessor=null     parameter=[""]
 * password     component=null     field=null     accessor=null     parameter=[""]
 * displayName  component=null     field=null     accessor=null     parameter=["Admin"]
 * </pre>
 * A derivation reading {@code getRecordComponents()[i].getAnnotation(DefaultValue.class)} — the
 * obvious thing to write — therefore sees {@code null} for every component, which is
 * indistinguishable from "this component declares no default". Depending on which way the caller
 * phrases its question that either accuses every member or excuses every member, for ever, while
 * looking like it works. {@code SeedAdminPropertiesTest} proves this reader against a planted
 * record whose components are deliberately mixed, so the claim above is a live check and not this
 * paragraph.
 *
 * <h2>Why the prefix is read from both places</h2>
 * {@code @ConfigurationProperties("seed.admin")} populates {@code value()} and leaves
 * {@code prefix()} empty; {@code @ConfigurationProperties(prefix = "seed.admin")} does the
 * reverse. Measured on the shipping class: {@code prefix=""} and {@code value="seed.admin"}. A
 * reader that consulted one would return the empty string for half the ways the same annotation is
 * written, and this codebase writes it both ways.
 */
public final class SeedAdminDefaults {

    private SeedAdminDefaults() {
    }

    /** The configuration root, e.g. {@code seed.admin}, however the annotation spells it. */
    public static String prefix() {
        return prefixOf(SeedAdminProperties.class);
    }

    /** Visible for the plant in {@code SeedAdminPropertiesTest} that proves this reader. */
    public static String prefixOf(Class<?> type) {
        ConfigurationProperties annotation = type.getAnnotation(ConfigurationProperties.class);
        if (annotation == null) {
            throw new AssertionError(type.getName() + " carries no @ConfigurationProperties, so the "
                    + "seed.admin properties are no longer a bound configuration root and every "
                    + "population derived from it is empty. If the binding moved, point this class at "
                    + "the type that carries it - do not delete the callers' floors.");
        }
        return annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
    }

    /**
     * Every component of {@link SeedAdminProperties}, in declaration order, mapped to the default
     * it declares — or to {@code null} when it declares none, which is a defect its own test
     * refuses rather than a state to interpret.
     */
    public static Map<String, String> declaredDefaults() {
        return declaredDefaultsOf(SeedAdminProperties.class);
    }

    /** Visible for the plant in {@code SeedAdminPropertiesTest} that proves this reader. */
    public static Map<String, String> declaredDefaultsOf(Class<?> type) {
        var components = type.getRecordComponents();
        if (components == null) {
            throw new AssertionError(type.getName() + " is not a record, so the canonical-constructor "
                    + "parameter this reader takes @DefaultValue from does not exist. Teach this class "
                    + "the new shape rather than letting its callers derive an empty population.");
        }
        var canonical = canonicalConstructorOf(type, components.length);
        var defaults = new LinkedHashMap<String, String>();
        for (int i = 0; i < components.length; i++) {
            DefaultValue declared = canonical.getParameters()[i].getAnnotation(DefaultValue.class);
            // Boot joins a multi-valued @DefaultValue with commas when it binds a scalar; these are
            // all single-valued, and a reader that silently took value()[0] would hide the day one
            // is not.
            defaults.put(components[i].getName(),
                    declared == null ? null : String.join(",", declared.value()));
        }
        return defaults;
    }

    private static java.lang.reflect.Constructor<?> canonicalConstructorOf(Class<?> type, int arity) {
        for (var candidate : type.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == arity) {
                return candidate;
            }
        }
        throw new AssertionError(type.getName() + " has no constructor of " + arity + " parameters, so "
                + "its canonical constructor could not be identified - and that is the only place "
                + "@DefaultValue exists (it declares @Target({PARAMETER}) only).");
    }

    /**
     * The environment variable an operator sets for a component, by Spring's relaxed binding:
     * {@code email} under {@code seed.admin} is {@code SEED_ADMIN_EMAIL}, and {@code displayName}
     * is {@code SEED_ADMIN_DISPLAY_NAME} — the camel hump is a dash in the property name and an
     * underscore in the variable. The transform itself is {@link RelaxedNames} — one spelling,
     * shared with {@link Doors#configurationProperties()} and with the property-to-variable
     * mapping, and held against the real {@code SystemEnvironmentPropertySource} by
     * {@code StartupRefusalNamesTheVariableTest}.
     */
    public static String variableFor(String component) {
        return RelaxedNames.variableFor(propertyFor(component));
    }

    /** The property name an operator finds in {@code application.properties}. */
    public static String propertyFor(String component) {
        return prefix() + "." + RelaxedNames.kebabCase(component);
    }
}
