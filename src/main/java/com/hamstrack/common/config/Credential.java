package com.hamstrack.common.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <strong>The value bound to this configuration component is a credential</strong> (HD-326).
 *
 * <p>It declares one thing and does one thing: it says that the <em>value</em> of the component is
 * a secret, and it makes that statement machine-readable, so a rule about credential-bearing
 * configuration has a population it can enumerate instead of a naming convention it has to guess
 * at. {@code CredentialPropertiesCarryNoConstraintTest} is the rule that reads it today, and it
 * refuses a {@code jakarta.validation} constraint at any site on the binding path to a marked
 * component &mdash; the component itself, the type declaring it, and every component and type above
 * it up to the root &mdash; plus {@code @Validated} on the type that declares one. The reason is
 * measured rather than remembered: Boot renders the <em>value</em> a failed binding-time constraint
 * rejected into the startup console, and for a constraint on a component that holds a record that
 * value is the whole record, whose {@code toString} includes the credential (both shapes are quoted
 * in that test, and the leaf one also in
 * {@link com.hamstrack.common.seed.SeedAdminProperties}' javadoc).
 *
 * <h2>What it is NOT</h2>
 * It is <strong>not</strong> a mask, a sanitiser or a redaction, and nothing in this application
 * consults it at runtime. In particular it does not change what an actuator endpoint renders:
 * masking there is decided by {@code management.endpoint.*.show-values} and by
 * {@code SanitizingFunction} beans, of which this codebase declares none (READ 2026-10-03: zero
 * occurrences of {@code SanitizingFunction} under {@code src/main/java}, and no
 * {@code show-values} line in any {@code application*.properties}). Adding either is a separate
 * decision with its own ticket; the day it is taken, this annotation is the population it should be
 * driven from rather than a second list.
 *
 * <h2>Why a marker and not a name</h2>
 * The obvious population rule is "a component whose name looks like a credential", and a rename
 * then takes the member out of the population: whatever was being checked about it stops being
 * checked, and the run reports the same green (<em>reasoned</em> from the construction &mdash; that
 * population is deliberately not the one the rule reads, so there is nothing to measure it on). So
 * the marker is the population &mdash; it travels with the component through any rename &mdash; and
 * the name shape
 * ({@code PublishedCredentials.CREDENTIAL_SHAPED}, this repository's one credential-name pattern)
 * is used only in the direction where being wrong is harmless: every credential-SHAPED component
 * must carry this annotation, which catches the credential that arrives unmarked. A rename can
 * only ever remove a redundant accusation there; it can never empty the population being checked.
 *
 * <h2>{@code @Target(RECORD_COMPONENT)} only, deliberately</h2>
 * Every {@code @ConfigurationProperties} holder in this tree is a record (MEASURED 2026-10-03: 23
 * of 23), and a single target means the annotation exists in exactly one place reflection can ask
 * about &mdash; {@code getRecordComponents()[i].getAnnotation(Credential.class)}. The opposite
 * mistake is already written up on {@code SeedAdminDefaults}: {@code @DefaultValue} targets
 * {@code PARAMETER} only, so reading it off the record component returns {@code null} for every
 * member and a guard built that way excuses the whole category for ever. If a properties holder
 * that is not a record ever arrives, widen this target and widen the reader in the same change
 * &mdash; the reader is proved by a planted violation, so a target it cannot see fails loudly.
 */
@Documented
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface Credential {
}
