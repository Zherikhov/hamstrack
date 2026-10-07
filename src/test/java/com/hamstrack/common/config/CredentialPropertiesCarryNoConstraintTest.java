package com.hamstrack.common.config;

import com.hamstrack.common.testsupport.Doors;
import com.hamstrack.common.testsupport.Doors.ConfigProperty;
import com.hamstrack.common.testsupport.RelaxedNames;
import com.hamstrack.ops.PublishedCredentials;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * <strong>HD-326 &mdash; a configuration property whose value is a credential declares no
 * bean-validation constraint, because a failed binding PRINTS THE VALUE.</strong>
 *
 * <h2>The measurement this rule exists for</h2>
 * {@link #aBindingConstraintPrintsTheValueOfThePropertyItRefused()} is not a description of the
 * leak, it is the leak, run against the real libraries on every run: a {@code @Size} on a
 * credential-shaped property, an over-long value arriving through a
 * {@code SystemEnvironmentPropertySource}, and Boot's own registered
 * {@code BindValidationFailureAnalyzer} rendering (MEASURED 2026-10-03, Hibernate Validator
 * 9.1.0.Final)
 * <pre>
 * Binding to target …$ProbeCredentialProperties failed:
 *
 *     Property: probe.credential.password
 *     Value: "CLEARTEXT-CANARY-CHARLIE-xxxxxxxxxxxxxxxx"
 *     Origin: "PROBE_CREDENTIAL_PASSWORD" from property source "systemEnvironment"
 *     Reason: &lt;the constraint's default message, resolved through the JVM's default locale&gt;
 * </pre>
 * That text is {@code FailureAnalysis.description}; Boot prints it to the startup console verbatim,
 * and the log collector this deployment runs reads every container's stdout
 * ({@code docker-compose.observability.yml}, READ). <strong>Nothing masked it in the measurement
 * above</strong>, and there is nothing in the tree that could: key-name masking is a property of
 * {@code SanitizingFunction} beans, and this codebase declares none (READ: zero occurrences under
 * {@code src/main/java}). Whether a {@code SanitizingFunction} would even be consulted on this path
 * is <em>inferred</em> and untested &mdash; there is none to test with, which is the only claim this
 * rule needs. So the refusal an author adds for tidiness writes the secret into the log store, in
 * clear, on the one boot where it matters.
 *
 * <h2>Why the category and not {@code seed.admin.password}</h2>
 * The finding arrived about one member. It is a property of every component whose value is a
 * credential, all of them components of a {@code @ConfigurationProperties} record with a default
 * {@code toString}, and before this test nothing in the tree held it for any of them:
 * {@code ArchitectureRulesTest#validatedOnlyOnConfigurationProperties} enforces the opposite
 * direction only ({@code @Validated} implies {@code @ConfigurationProperties}, never the reverse).
 * The members are printed on every run, green or red.
 *
 * <h2>The population is a MARKER, and the name shape is only a tripwire</h2>
 * {@link Credential} is the population: it travels with the component through any rename, so no
 * rename can take a member out of the set being checked. Under the obvious alternative &mdash; "a
 * component whose NAME looks credential-shaped" &mdash; a rename removes the member instead, and
 * the rule then passes over it while reporting the same green (<em>reasoned</em> from the
 * construction, not measured: that population is deliberately not the one the rule reads). The name
 * shape is still used, in the one direction where being wrong is harmless: every credential-SHAPED
 * component must carry the marker ({@link #everyCredentialShapedPropertyCarriesTheMarker()}), which
 * catches the credential that arrives unmarked. A rename there can only ever drop a redundant
 * accusation &mdash; and even that is announced rather than absorbed, by the floor on the shaped
 * set.
 * The shape itself is {@link PublishedCredentials#CREDENTIAL_SHAPED}, this repository's one
 * credential-name pattern &mdash; a second copy of it would be the defect.
 *
 * <p><strong>There is no exclusion</strong>, and there is nowhere to hide one: every marked member
 * is checked, the predicate admits nothing, and both populations are printed with their names. The
 * floors are set at today's exact count rather than at a fraction of it, because the population is
 * small enough that any loss is a defect: an emptied set must red, not pass for free.
 *
 * <h2>What the fix loop changed, and what it admits (HD-326 round 3)</h2>
 * Two arms, and the second one moved:
 * <ul>
 *   <li><strong>The constraint arm widened.</strong> It read the credential's own four landing
 *       sites; it now walks the binding path up to the root, because a constraint on the component
 *       that HOLDS a credential-bearing record prints that record &mdash; credential included
 *       ({@link #aConstraintOnTheContainerOfACredentialPrintsTheCredential}, measured). Three
 *       production sites were reachable and unguarded:
 *       {@code StorageProperties#s3}, {@code StorageProperties.S3} and {@code StorageProperties}.</li>
 *   <li><strong>The {@code @Validated} arm narrowed</strong> from "the declaring type or any type
 *       enclosing it" to the declaring type alone. What that ADMITS is printed by name on every run
 *       rather than described here: today {@code StorageProperties}, which does not carry the
 *       annotation, so the narrowing changes no verdict in this tree &mdash; it changes what a
 *       future change may do. The point of admitting it is that
 *       {@code app.storage.s3.timeouts.connect-ms/read-ms/api-call-ms/api-call-attempt-ms} are four
 *       bare {@code long}s with no bound at all, and the only declarative way to bound them runs
 *       through {@code @Validated} on that root (plus {@code @Valid} on the path &mdash; measured by
 *       {@link #aNestedConstraintFiresOnlyWithValidatedOnTheRootAndValidOnThePath}, because neither
 *       annotation reaches a nested value alone). The widened constraint arm is what keeps that door
 *       safe to open: the enabler is admitted, the leak is still refused.</li>
 * </ul>
 * <strong>The residual cost, named rather than discovered as a red build.</strong> A credential
 * declared directly on a root makes that root the declaring type, so the arm still refuses
 * {@code @Validated} on {@code JwtProperties} and {@code SeedAdminProperties} &mdash; which leaves
 * {@code jwt.access-token-expiration} and {@code jwt.refresh-token-expiration} (a {@code PT0S}
 * mints already-expired tokens) and {@code seed.admin.email} / {@code seed.admin.display-name}
 * without a declarative bound. All four are unbounded today anyway; whoever bounds them bounds them
 * imperatively, the way {@code DataSeeder} does, or moves the credential into a nested record of
 * its own and bounds the siblings on the root.
 */
class CredentialPropertiesCarryNoConstraintTest {

    /**
     * Every bindable configuration property &mdash; the set the two populations below are cut from.
     * 95 on 2026-10-03 across 23 roots; floored in {@code DoorsHarnessTest} as well, so a collapsed
     * scan is refused whether or not this class runs.
     */
    private static final int ALL_PROPERTIES_FLOOR = 80;

    /**
     * {@code JwtProperties#secret}, {@code StorageProperties.S3#accessKey},
     * {@code StorageProperties.S3#secretKey}, {@code SeedAdminProperties#password} &mdash; 4 on
     * 2026-10-03, which is also the floor. A credential that legitimately leaves is a deliberate
     * edit here, in the same commit, naming the member.
     */
    private static final int CREDENTIAL_FLOOR = 4;

    /** Boot's analyzer for a failed binding-time constraint, asserted to be the REGISTERED one. */
    private static final String ANALYZER =
            "org.springframework.boot.diagnostics.analyzer.BindValidationFailureAnalyzer";

    // ------------------------------------------------------------------ the rule

    @Test
    void noCredentialBearingPropertyDeclaresAConstraintOrAValidatedType() {
        var all = Doors.configurationProperties().floor(ALL_PROPERTIES_FLOOR);
        var credentials = all.filter("@Credential-marked", ConfigProperty::credential).floor(CREDENTIAL_FLOOR);

        var ids = new TreeSet<String>();
        var perOwner = new TreeMap<String, Integer>();
        var offences = new ArrayList<String>();
        // The types this arm stopped refusing when it was narrowed to the declaring type.
        var admitted = new TreeMap<String, String>();
        // Where the ancestor walk of constraintsOn() ended, per member.
        var walkEndedAt = new TreeMap<String, String>();
        for (var property : credentials) {
            ids.add(property.id());
            perOwner.merge(property.owner().getName(), 1, Integer::sum);
            for (var where : constraintsOn(property)) {
                offences.add("  " + property.describe() + " declares " + where);
            }
            if (AnnotatedElementUtils.hasAnnotation(property.owner(), Validated.class)) {
                offences.add("  " + property.describe() + " is declared on "
                             + property.owner().getSimpleName() + ", which carries @Validated — the "
                             + "annotation that makes a declared constraint on that type fire");
            }
            for (var type = bindingParentOf(property.owner()); type != null; type = bindingParentOf(type)) {
                admitted.put(type.getName(), "binds " + property.id()
                                             + (AnnotatedElementUtils.hasAnnotation(type, Validated.class)
                                                     ? " — AND CARRIES @Validated TODAY" : ""));
            }
            // The ancestor walk is this reader's model of the binding path, so where it stops is
            // part of the measurement rather than an implementation detail: a credential whose walk
            // ends below its root has levels nobody looked at.
            var reached = property.owner();
            while (bindingParentOf(reached) != null) {
                reached = bindingParentOf(reached);
            }
            walkEndedAt.put(property.id(), reached.getSimpleName()
                                           + (AnnotatedElementUtils.hasAnnotation(
                                                   reached, ConfigurationProperties.class) ? " (a root)" : ""));
            if (!AnnotatedElementUtils.hasAnnotation(reached, ConfigurationProperties.class)) {
                offences.add("  " + property.describe() + ": the ancestor walk ended at "
                             + reached.getName() + ", which is not a @ConfigurationProperties root — "
                             + "so the levels above it were not examined for a constraint whose rejected "
                             + "value would render this credential. Teach bindingParentOf the shape this "
                             + "nesting has; do not let the walk stop quietly.");
            }
        }

        System.out.println("[HD-326] " + credentials.describe() + ": " + ids);
        System.out.println("[HD-326] " + all.size() + " bindable propert(ies) across "
                           + all.stream().map(ConfigProperty::root).distinct().count() + " root(s)");
        // The DELTA of the narrowing, by name and on every run. This arm used to walk outward to
        // every enclosing type; it now asks only the declaring one, so these types became allowed
        // to carry @Validated. A predicate that grants an exemption can only ever be wrong
        // silently, so the set it admits is printed rather than described - and the day one of
        // them actually carries the annotation, this line says so instead of a reader inferring it.
        System.out.println("[HD-326] @Validated refused on the declaring types "
                           + credentials.stream().map(p -> p.owner().getSimpleName()).distinct().sorted().toList()
                           + "; admitted by the narrowing to the declaring type: " + admitted);
        System.out.println("[HD-326] constraint walk ended at: " + walkEndedAt);

        assertThat(offences)
                .as("""
                        A SITE WHOSE REJECTED VALUE WOULD RENDER A CREDENTIAL CARRIES A BEAN-VALIDATION \
                        CONSTRAINT.

                        %s

                        A failed binding-time constraint PRINTS THE VALUE IT REJECTED. Both shapes are \
                        measured on this run against Boot's own registered BindValidationFailureAnalyzer:

                          Property: probe.credential.password            <- on the credential itself
                          Value: "CLEARTEXT-CANARY-CHARLIE-xxxxxxxxxxxxxxxx"
                          Origin: "PROBE_CREDENTIAL_PASSWORD" from property source "systemEnvironment"

                          Property: probe.container.holder               <- on the component HOLDING it
                          Value: "Holder[password=CLEARTEXT-CANARY-DELTA-yyyyyyyyyyyyyyyy]"

                        That is FailureAnalysis.description, printed to the startup console verbatim, with \
                        no sanitiser in the tree to mask anything (there is no SanitizingFunction here) and \
                        a log collector reading every container's stdout \
                        (docker-compose.observability.yml). The second shape is why this reader walks the \
                        binding path: a record's toString renders every component, so a constraint one \
                        level out prints a credential nobody annotated. The first value an operator trips \
                        a length bound with is one they then re-use.

                        Delete the constraint and bound the value imperatively instead, reporting the \
                        MEASURE and never the value — DataSeeder.rejectOverLongPassword reports \
                        PasswordLimits.byteLength(password) and is the shape to copy. A sibling that is \
                        not on the path to a credential may still be bounded declaratively: \
                        app.storage.s3.timeouts.* is reachable from @Validated on StorageProperties plus \
                        @Valid on the path (measured by \
                        aNestedConstraintFiresOnlyWithValidatedOnTheRootAndValidOnThePath). If the \
                        component is not a credential, take @Credential off it and say in the commit why.""",
                        String.join("\n", offences))
                .isEmpty();

        // Granularity control: the unit of this rule is Owner#component, never the class. Two of
        // today's four members sit on ONE type, so a population keyed on the class would let one
        // member's clean bill of health answer for its neighbour's — and would still print four
        // plausible-looking lines above.
        assertThat(ids)
                .as("two marked components on one type must stay two members: %s", ids)
                .hasSize(credentials.size());
        // The narrowing's own live check. Its whole purpose is to leave the ENCLOSING type free to
        // carry @Validated for its non-credential siblings (app.storage.s3.timeouts.* is the case
        // that paid for it), so if no credential has an enclosing type any more, the narrowing
        // excuses nothing and is prose - the shape this project refuses in an exclusion.
        assertThat(admitted)
                .as("""
                        THE NARROWING OF THE @Validated ARM NOW ADMITS NOTHING, so it is a weakening \
                        that buys nobody anything: every credential is declared directly on a \
                        @ConfigurationProperties root, so "the declaring type" and "every enclosing type" \
                        are the same question. Either widen the arm back to the enclosing chain (it costs \
                        nothing while this set is empty), or find out which nested credential-bearing \
                        record was flattened into its root.""")
                .isNotEmpty();

        assertThat(perOwner.values())
                .as("""
                        NO TYPE DECLARES TWO MARKED COMPONENTS ANY MORE (%s), so this test can no longer \
                        tell whether its unit is the component or the class — the two granularities agree \
                        on every one-member type, and that agreement is what hid an HD-306 defect inside \
                        a green category test. If a type legitimately lost a credential, re-point this \
                        control at whichever type still carries two; if none does, assert the ids \
                        directly.""", perOwner)
                .anyMatch(count -> count >= 2);
    }

    /**
     * The tripwire on the marker: a component whose NAME says it holds a credential must carry
     * {@link Credential}. This is the direction a name may safely decide &mdash; it can accuse, never
     * excuse &mdash; and it is what catches the credential that arrives in a new root with nobody
     * thinking about this rule.
     */
    @Test
    void everyCredentialShapedPropertyCarriesTheMarker() {
        var all = Doors.configurationProperties().floor(ALL_PROPERTIES_FLOOR);
        var shaped = all.filter("credential-shaped name (PublishedCredentials.CREDENTIAL_SHAPED)",
                CredentialPropertiesCarryNoConstraintTest::isCredentialShaped);

        var unmarked = shaped.stream().filter(property -> !property.credential()).toList();

        System.out.println("[HD-326] credential-shaped: "
                           + shaped.stream().map(ConfigProperty::describe).sorted().toList());

        assertThat(unmarked)
                .as("""
                        A CONFIGURATION PROPERTY WHOSE NAME SAYS IT HOLDS A CREDENTIAL IS NOT MARKED \
                        @Credential, so every rule over credential-bearing configuration passes over it:

                        %s

                        Add @Credential to the record component (and read that annotation's javadoc — it is \
                        not a mask, it is a population). If the value is genuinely not a secret, rename it: \
                        PublishedCredentials.CREDENTIAL_SHAPED is this repository's one credential-name \
                        pattern and several other guards read a name through it, so a non-secret wearing a \
                        secret's name misleads more than this test.""",
                        unmarked.stream().map(ConfigProperty::describe).toList())
                .isEmpty();

        assertThat(shaped.size())
                .as("""
                        NO CONFIGURATION PROPERTY LOOKS CREDENTIAL-SHAPED ANY MORE, so this tripwire is \
                        checking nothing: it would admit an unmarked credential in silence. Either \
                        CREDENTIAL_SHAPED was narrowed (repair the pattern, never the floor — several \
                        guards read it), or a credential was renamed to something the pattern does not \
                        match. A rename is legitimate and costs nothing: the marker is the population and \
                        is unaffected. Lower this floor in the same commit and name the member.""")
                .isGreaterThanOrEqualTo(CREDENTIAL_FLOOR);
    }

    // ------------------------------------------------------------------ the measurement

    /** The shape the rule above exists for, planted here rather than in a production record. */
    @ConfigurationProperties("probe.credential")
    record ProbeCredentialProperties(@Size(max = 8) String password) {
    }

    /** A root that carries {@code @Validated}, a constraint inside its nested record, no cascade. */
    @Validated
    @ConfigurationProperties("probe.rootvalidated")
    record ProbeRootValidatedProperties(Nested nested) {
        record Nested(@Min(1) long value) {
        }
    }

    /** The mirror image: the {@code @Validated} is on the NESTED record, and the root has none. */
    @ConfigurationProperties("probe.nestedvalidated")
    record ProbeNestedValidatedProperties(ValidatedNested nested) {
        @Validated
        record ValidatedNested(@Min(1) long value) {
        }
    }

    /** The same root, with the one annotation that makes the cascade happen. */
    @Validated
    @ConfigurationProperties("probe.cascaded")
    record ProbeCascadedProperties(@Valid Nested nested) {
        record Nested(@Min(1) long value) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ProbeRootValidatedProperties.class, ProbeNestedValidatedProperties.class,
            ProbeCascadedProperties.class})
    static class ProbeValidationConfig {
    }

    /**
     * <strong>What actually makes a constraint inside a nested properties record fire</strong> —
     * asked of the real library, because the narrowed {@code @Validated} arm above rests on it and
     * two plausible answers turned out to be wrong.
     *
     * <h2>MEASURED on Boot 4.1 / Hibernate Validator 9.1.0.Final (2026-10-03)</h2>
     * <pre>
     * &#64;Validated on the ROOT, &#64;Min(1) violated in a nested record, no &#64;Valid   context STARTED
     * &#64;Validated on the NESTED record only, same violated &#64;Min(1)              context STARTED
     * &#64;Validated on the ROOT + &#64;Valid on the nested component                  context FAILED
     * </pre>
     * So <strong>neither</strong> annotation alone reaches a nested value: Boot decides whether to
     * validate from the annotation on the <em>root</em> ({@code ConfigurationPropertiesBinder} adds
     * its JSR-303 validator only when the {@code Bindable} target carries {@code @Validated}, which
     * is why the nested-only case is inert), and Hibernate Validator does not descend into a nested
     * object unless the component holding it is {@code @Valid}, which is why the root-only case is
     * inert too. The review premise this probe was asked to measure — "root-level {@code @Validated}
     * is what makes nested constraints fire" — is half true at best, and the class offered as its
     * live proof ({@code ExpensiveReadProperties}) declares no nested record at all: its
     * {@code @Min}/{@code @Max} sit on the root record's own components (READ).
     *
     * <p><strong>What the rule above takes from this.</strong> The {@code @Validated} arm asks only
     * about the type that DECLARES the credential, because that is the type whose own components'
     * constraints the annotation enables. On an enclosing type it enables nothing without a
     * {@code @Valid} chain, so refusing it there would be a rule about a no-op — and it would cost
     * the enclosing root its ability to bound its non-credential siblings, which is exactly what
     * {@code StorageProperties} needs for {@code app.storage.s3.timeouts.*}.
     */
    @Test
    void aNestedConstraintFiresOnlyWithValidatedOnTheRootAndValidOnThePath() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ProbeValidationConfig.class);

        runner.withPropertyValues("probe.rootvalidated.nested.value=0").run(context -> {
            System.out.println("[HD-326] @Validated on the ROOT, @Min(1) violated in a nested record, "
                               + "no @Valid: " + outcomeOf(context.getStartupFailure()));
            assertThat(context)
                    .as("""
                            @Validated ON THE ROOT ALONE NOW REACHES A NESTED RECORD'S CONSTRAINT.

                            It did not when the @Validated arm of \
                            noCredentialBearingPropertyDeclaresAConstraintOrAValidatedType was narrowed to \
                            the declaring type - the cascade needed @Valid on the component as well. If \
                            the library changed, widen that arm back to the enclosing chain and re-read \
                            the cost it names.""")
                    .hasNotFailed();
            assertThat(context.getBean(ProbeRootValidatedProperties.class).nested().value())
                    .as("an inert constraint must leave the out-of-range value in place, which is what "
                        + "makes it inert rather than merely unreported")
                    .isZero();
        });

        runner.withPropertyValues("probe.nestedvalidated.nested.value=0").run(context -> {
            System.out.println("[HD-326] @Validated on the NESTED record only, same violated @Min(1): "
                               + outcomeOf(context.getStartupFailure()));
            assertThat(context)
                    .as("""
                            @Validated ON A NESTED PROPERTIES RECORD NOW FIRES ITS OWN CONSTRAINTS, which \
                            is the opposite of what this library did when the arm above was narrowed. \
                            Widen it back to the enclosing types: a constraint inside a nested record can \
                            now be enabled from the record itself.""")
                    .hasNotFailed();
        });

        runner.withPropertyValues("probe.cascaded.nested.value=0").run(context -> {
            System.out.println("[HD-326] @Validated on the ROOT + @Valid on the nested component: "
                               + outcomeOf(context.getStartupFailure()));
            assertThat(context)
                    .as("""
                            NO COMBINATION IN THIS PROBE NOW REFUSES A VIOLATED NESTED CONSTRAINT, so the \
                            other two cases no longer measure a DIFFERENCE - they would pass over a \
                            library that validates nothing at all, which is the vacuous shape this \
                            repository keeps finding. Repair this case before trusting either of them.""")
                    .hasFailed();
        });
    }

    private static String outcomeOf(Throwable startupFailure) {
        return startupFailure == null ? "context STARTED"
                : "context FAILED — " + rootCauseLine(startupFailure);
    }

    /**
     * A constraint that refuses any non-null value, whatever its type — the cheapest way to ask the
     * analyzer what it prints for a site that is not the credential itself.
     */
    @Documented
    @Constraint(validatedBy = RefusesAnything.Validator.class)
    @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @interface RefusesAnything {

        String message() default "refused by the probe";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        class Validator implements ConstraintValidator<RefusesAnything, Object> {
            @Override
            public boolean isValid(Object value, ConstraintValidatorContext context) {
                return value == null;
            }
        }
    }

    /** The constraint sits on the CONTAINER of the credential, never on the credential. */
    @ConfigurationProperties("probe.container")
    record ProbeContainerProperties(@RefusesAnything Holder holder) {
        record Holder(String password) {
        }
    }

    /**
     * <strong>A constraint on the CONTAINER of a credential prints the credential too</strong> —
     * found by this probe rather than reasoned about, and it is why {@link #constraintsOn} walks
     * the ancestor chain instead of reading the credential component alone.
     *
     * <h2>MEASURED (2026-10-03), Boot 4.1 / Hibernate Validator 9.1.0.Final</h2>
     * <pre>
     * Binding to target …$ProbeContainerProperties failed:
     *
     *     Property: probe.container.holder
     *     Value: "Holder[password=CLEARTEXT-CANARY-DELTA-yyyyyyyyyyyyyyyy]"
     *     Reason: refused by the probe
     * </pre>
     * The rejected value of a failed constraint on the <em>holding component</em> is the whole
     * record, and a record's default {@code toString} renders every component — so the credential
     * is printed by a constraint that was never written anywhere near it. The equivalent site one
     * level further out ({@code StorageProperties#s3}) is reachable in production today, and
     * nothing refused it before this round: the reader looked at the credential's own four landing
     * sites only.
     *
     * <p>Note what this probe does <em>not</em> need: the credential has no annotation at all here,
     * and there is no {@code Origin:} line because the value was assembled by the binder rather
     * than read from one source. The leak does not depend on either.
     */
    @Test
    void aConstraintOnTheContainerOfACredentialPrintsTheCredential() {
        var canary = "CLEARTEXT-CANARY-DELTA-" + "y".repeat(16);
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("PROBE_CONTAINER_HOLDER_PASSWORD", canary)));

        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        var failure = catchThrowable(() -> Binder.get(environment).bind("probe.container",
                Bindable.of(ProbeContainerProperties.class), new ValidationBindHandler(validator)));
        assertThat(failure)
                .as("the container constraint did not fail, so this probe measured nothing — repair it "
                    + "rather than deleting it: it is what says whether the holding component is a leak")
                .isNotNull();

        var analysis = analyzeWithTheRegisteredAnalyzer(failure);
        assertThat(analysis)
                .as("%s produced no analysis for %s", ANALYZER, failure.getClass().getName())
                .isNotNull();
        System.out.println("[HD-326] a constraint on the CONTAINER of a credential renders:\n"
                           + analysis.getDescription());
        System.out.println("[HD-326] container probe: the credential value is "
                           + (analysis.getDescription().contains(canary) ? "PRINTED" : "NOT printed"));

        assertThat(analysis.getDescription())
                .as("""
                        A CONSTRAINT ON THE COMPONENT THAT HOLDS A CREDENTIAL NO LONGER PRINTS THE \
                        OBJECT IT REJECTED.

                        That is the whole reason constraintsOn() walks the ancestor chain - the holding \
                        component and the type-level annotation at every level - rather than reading the \
                        credential's own four landing sites. If the library stopped rendering the \
                        rejected object, that walk can be reconsidered on evidence; do not delete this \
                        probe, which is the only thing standing between the walk and a memory. \
                        Rendered description was:

                        %s""", analysis.getDescription())
                .contains(canary)
                .contains("probe.container.holder");
    }

    private static String rootCauseLine(Throwable failure) {
        var cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": "
               + String.valueOf(cause.getMessage()).lines().findFirst().orElse("");
    }

    /**
     * <strong>The leak, measured rather than remembered.</strong> A credential-shaped property with
     * a {@code @Size}, an over-long value from the environment, and the registered analyzer's own
     * description &mdash; asserted to contain the value verbatim. If a future Boot stops printing
     * the value, this test fails and the rule above can be reconsidered on evidence; until then the
     * rule's reason is re-established on every run.
     *
     * <p>The value is a canary, not a credential: it is generated here, is never written to a file,
     * and says what it is in its own text.
     */
    @Test
    void aBindingConstraintPrintsTheValueOfThePropertyItRefused() {
        var canary = "CLEARTEXT-CANARY-CHARLIE-" + "x".repeat(16);
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("PROBE_CREDENTIAL_PASSWORD", canary)));

        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        var failure = catchThrowable(() -> Binder.get(environment).bind("probe.credential",
                Bindable.of(ProbeCredentialProperties.class), new ValidationBindHandler(validator)));

        assertThat(failure)
                .as("binding a %d-character value against @Size(max = 8) did not fail, so this probe "
                    + "measured nothing — repair the probe rather than deleting it", canary.length())
                .isNotNull();

        var analysis = analyzeWithTheRegisteredAnalyzer(failure);
        assertThat(analysis)
                .as("%s produced no analysis for %s, so the startup console would print a bare stack "
                    + "trace instead — repair this probe before trusting the rule it backs",
                        ANALYZER, failure.getClass().getName())
                .isNotNull();

        System.out.println("[HD-326] " + ANALYZER + " description:\n" + analysis.getDescription());
        var impl = validator.getValidator().getClass();
        System.out.println("[HD-326] validator impl " + impl.getName() + " "
                           + impl.getPackage().getImplementationVersion());

        assertThat(analysis.getDescription())
                .as("""
                        BOOT NO LONGER PRINTS THE VALUE of a property whose binding-time constraint failed.

                        That is the whole reason a credential-bearing property carries no constraint in this \
                        codebase (noCredentialBearingPropertyDeclaresAConstraintOrAValidatedType, and the \
                        fourth bullet on SeedAdminProperties). If the library changed, re-read both and \
                        weaken them deliberately — do not delete this probe, which is the only thing \
                        standing between those two reasons and a memory. Description was:

                        %s""", analysis.getDescription())
                .contains(canary)
                .contains("probe.credential.password");
    }

    // ------------------------------------------------------------------ readers

    /**
     * <strong>Every site whose rejected value would render this credential</strong>, not only the
     * credential's own four.
     *
     * <h2>The four landing sites of one component</h2>
     * {@code jakarta.validation} declares no {@code ElementType.RECORD_COMPONENT} &mdash; MEASURED
     * by the negative control for this test, which plants {@code @Size(max = 72)} on
     * {@code JwtProperties.secret} and finds it on the accessor, the backing field and the
     * canonical-constructor parameter and <em>not</em> on the record component. A reader that asked
     * the component alone would answer "no constraint" for every record in this codebase, which is
     * the reflective-guard trap CLAUDE.md names: absent and not-applicable come back as the same
     * {@code null}. A custom constraint counts too, by its {@code @Constraint} meta-annotation, so a
     * project-local annotation cannot route around the package test.
     *
     * <h2>And the ancestor chain, because the leak is not only the leaf (HD-326 fix loop)</h2>
     * The value Boot prints for a failed constraint is the value that constraint REJECTED, and for a
     * constraint on a component that holds a record, that value is the whole record &mdash; whose
     * default {@code toString} renders every component. MEASURED by
     * {@link #aConstraintOnTheContainerOfACredentialPrintsTheCredential}:
     * {@code Value: "Holder[password=CLEARTEXT-CANARY-DELTA-…]"} from a constraint written on
     * {@code holder}, with no annotation anywhere near the credential. So a constraint on
     * {@code StorageProperties#s3}, or a type-level one on {@code StorageProperties.S3} or on
     * {@code StorageProperties}, prints {@code access-key} and {@code secret-key} just as surely as
     * one on the components themselves &mdash; and until this round nothing refused any of them.
     *
     * <p>The walk is therefore over the BINDING PATH from the credential up to its root: at each
     * level, the type itself (a type-level constraint rejects the instance) and the component of the
     * enclosing record that holds it (four landing sites again, through the same reader). The
     * enclosure chain is this reader's model of that path, and it is checked rather than assumed
     * &mdash; {@link #bindingParentOf} returns null when an enclosing type is not a record holding
     * the child, and {@link #noCredentialBearingPropertyDeclaresAConstraintOrAValidatedType} fails
     * on a credential whose walk stops before a root rather than silently measuring less.
     */
    private static List<String> constraintsOn(ConfigProperty property) {
        var found = new ArrayList<String>();
        collectComponentSites(property.owner(), property.component(), property.id(), found);
        for (var type = property.owner(); type != null; type = bindingParentOf(type)) {
            collect(type.getAnnotations(), "as a TYPE-LEVEL constraint on " + type.getSimpleName()
                                           + ", whose rejected value is that whole record", found);
            var parent = bindingParentOf(type);
            if (parent != null) {
                var holder = holdingComponentIn(parent, type);
                collectComponentSites(parent, holder,
                        parent.getSimpleName() + "#" + holder.getName() + " (the component that HOLDS "
                        + type.getSimpleName() + ", so its rejected value renders this credential)", found);
            }
        }
        return found;
    }

    /** The four places an annotation on {@code component} can land, read as a group. */
    private static void collectComponentSites(
            Class<?> owner, RecordComponent component, String describe, List<String> found) {
        collect(component.getAnnotations(), "on the record component " + describe, found);
        collect(component.getAccessor().getAnnotations(),
                "on the accessor " + component.getAccessor().getName() + "() of " + describe, found);
        try {
            collect(owner.getDeclaredField(component.getName()).getAnnotations(),
                    "on the backing field of " + describe, found);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("record component " + describe + " has no backing field, so this reader "
                                     + "cannot see an annotation that lands there — teach it the new shape", e);
        }
        var canonical = canonicalConstructorOf(owner);
        var components = owner.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            if (components[i].getName().equals(component.getName())) {
                collect(canonical.getParameters()[i].getAnnotations(),
                        "on the canonical-constructor parameter of " + describe, found);
            }
        }
    }

    /**
     * The enclosing record that BINDS {@code type} as one of its components, or {@code null} when
     * the enclosure chain is not a binding path (an unrelated nesting, or the root itself).
     * Deliberately not {@code getEnclosingClass()}: a properties record nested in a configuration
     * class for tidiness is enclosed by it and not held by it, and walking into that would make this
     * reader report annotations on a type no bound value ever renders.
     */
    private static Class<?> bindingParentOf(Class<?> type) {
        var parent = type.getEnclosingClass();
        return parent != null && parent.isRecord() && holdingComponentIn(parent, type) != null ? parent : null;
    }

    /** The component of {@code parent} whose type is {@code child}, or {@code null}. */
    private static RecordComponent holdingComponentIn(Class<?> parent, Class<?> child) {
        if (!parent.isRecord()) {
            return null;
        }
        for (var component : parent.getRecordComponents()) {
            if (component.getType().equals(child)) {
                return component;
            }
        }
        return null;
    }

    private static void collect(Annotation[] annotations, String where, List<String> into) {
        for (var annotation : annotations) {
            var type = annotation.annotationType();
            if (type.getName().startsWith("jakarta.validation.constraints.")
                || type.isAnnotationPresent(Constraint.class)) {
                into.add("@" + type.getSimpleName() + " " + where);
            }
        }
    }

    private static Constructor<?> canonicalConstructorOf(Class<?> type) {
        var arity = type.getRecordComponents().length;
        for (var candidate : type.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == arity) {
                return candidate;
            }
        }
        throw new AssertionError(type.getName() + " has no constructor of " + arity + " parameters, so a constraint "
                                 + "that lands only on the canonical constructor's parameter is invisible here");
    }

    /** The name shape, through the one pattern this repository keeps for it. */
    private static boolean isCredentialShaped(ConfigProperty property) {
        return PublishedCredentials.CREDENTIAL_SHAPED_NAME.matcher(property.variable()).matches()
               || PublishedCredentials.CREDENTIAL_SHAPED_NAME
                       .matcher(RelaxedNames.variableFor(property.component().getName()))
                       .matches();
    }

    /**
     * Boot's analyzer, instantiated reflectively because it is package-private &mdash; and asserted
     * to be the one {@code META-INF/spring.factories} registers, so this probe cannot measure a
     * class the runtime never reaches.
     */
    private static FailureAnalysis analyzeWithTheRegisteredAnalyzer(Throwable failure) {
        assertThat(springFactories())
                .as("%s is not registered as a FailureAnalyzer any more, so whatever this probe measures "
                    + "is not what a boot would print", ANALYZER)
                .contains(ANALYZER);
        try {
            var constructor = Class.forName(ANALYZER).getDeclaredConstructor();
            constructor.setAccessible(true);
            return ((FailureAnalyzer) constructor.newInstance()).analyze(failure);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not instantiate " + ANALYZER, e);
        }
    }

    private static String springFactories() {
        var text = new StringBuilder();
        try {
            var resources = CredentialPropertiesCarryNoConstraintTest.class.getClassLoader()
                    .getResources("META-INF/spring.factories");
            while (resources.hasMoreElements()) {
                try (var stream = resources.nextElement().openStream()) {
                    text.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return text.toString();
    }
}
