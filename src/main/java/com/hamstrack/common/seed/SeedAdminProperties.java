package com.hamstrack.common.seed;

import com.hamstrack.common.config.Credential;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The first administrator a self-hosted installation is <em>told</em> to create (HD-326).
 *
 * <p>These three values used to be three {@code @Value} fields on {@link DataSeeder} — the only
 * three in the whole production tree, which made {@code seed.admin} the one configuration root of
 * this application that no properties file mentioned. That is not a style blemish: the
 * property-to-variable mapping {@code StartupRefusalNamesTheVariableTest} derives is read out of
 * the {@code ${VAR}} placeholders in {@code application*.properties}, so this root was invisible to
 * it, and a whole second derivation had to be written to see it (HD-315). It is now an ordinary
 * bound {@code @ConfigurationProperties} root, and that derivation reads it off the <em>type</em>,
 * which is what lets it see a root with no line in any properties file — the shape every root is
 * entitled to have, and the shape this one has.
 *
 * <h2>There is deliberately NO {@code seed.admin.*} line in {@code application.properties}</h2>
 * <strong>MEASURED (2026-10-02)</strong>, and the measurement is the reason, not the style.
 * Ordinary placeholder lines were written there first and <em>reverted</em>: a line in the packaged
 * properties file outranks {@code SpringApplicationBuilder.properties(...)}, which lands in
 * {@code defaultProperties} — the lowest-precedence source there is — and that builder is the boot
 * vehicle of {@code SeedAdminSquatterRefusalTest}, {@code SeedPasswordLimitTest} and
 * {@code SeedGuardStartupOrderingTest}. Adding {@code seed.admin.email=${SEED_ADMIN_EMAIL:}}
 * therefore shadowed it and turned four of their cases vacuous: two refusals stopped firing, and two
 * "must still boot" cases started passing because the address was now always empty. An environment
 * variable outranks the file, so production was unaffected and nothing would have announced the
 * loss. <strong>Do not add the line.</strong> The comment standing at the {@code seed.admin}
 * position in {@code src/main/resources/application.properties} carries the same measurement, for
 * the reader who is editing that file rather than this one; these two paragraphs must keep saying
 * the same thing. The defaults live on this record's components instead, which is the next section.
 *
 * <h2>{@code @DefaultValue("")} is load-bearing, not decoration</h2>
 * <strong>MEASURED (2026-10-02)</strong> on Boot 4.1: a record component with no
 * {@code @DefaultValue} binds to <strong>{@code null}</strong> when the property is absent —
 * {@code email=null password=null displayName=null} — and {@link DataSeeder#run} asks
 * {@code email.isBlank()}, so an installation that configures no administrator at all (the default,
 * and every Cloud install) would take a {@code NullPointerException} while the context refreshes.
 * The {@code @Value("${seed.admin.email:}")} this replaced yielded {@code ""}, and {@code ""} is
 * the value every branch in that class reads as "not configured". The same run measured
 * {@code @DefaultValue} present: {@code email="" password="" displayName="Admin"}. Sealed
 * behaviourally by {@code SeedAdminPropertiesTest}, which boots with nothing set, and
 * structurally by its companion case, which refuses a component that declares no default —
 * reading the annotation off the <em>canonical constructor's parameter</em>, because that is the
 * only place it exists (see below).
 *
 * <p><strong>And no read site guards against the null</strong> — deliberately, decided in HD-326's
 * fix loop. The invariant is a property of this type, so it is held at the type (one structural
 * case, negative-controlled) rather than at each of the ten {@code seedAdmin.*()} reads in
 * {@link DataSeeder}; a guard on one of them would be a one-site answer to a ten-site category.
 * The failure mode it would replace is not silent: MEASURED on this JDK (21.0.12.1), the
 * unguarded read fails with {@code Cannot invoke "String.isBlank()" because the return value of
 * "…SeedAdminProperties.email()" is null} and a frame naming the exact line, during refresh and
 * therefore before the port binds — the same moment {@code SeedGuardStartupOrderingTest} seals for
 * the two real refusals. A hand-written refusal there could only name a remedy its reader cannot
 * perform (restore an annotation in this file) or the one that is actively wrong (an operator who
 * configured no administrator being told to configure one), and that is the rule this subsystem
 * exists to keep.
 *
 * <h2>Why the length bound is NOT a constraint on this record</h2>
 * The ticket that created this class asked for a "validated configuration property", and
 * {@code seed.admin.email}'s bound deliberately stayed in {@link DataSeeder#rejectOverLongEmail}.
 * Every ground below was measured against this exact shape before it shipped. The first three are
 * correctness objections, which a determined author can argue past; the fourth is the one with
 * security weight, and it is the only one a future reader cannot rediscover by reasoning:
 * <ul>
 *   <li><strong>{@code @Size(max = 255)} cannot see the fold, so it stops refusing the value the
 *       guard exists for.</strong> The address is stored lower-cased and {@code toLowerCase} is not
 *       length-preserving, so 65 × U+0130 plus a 189-character domain is 255 characters as
 *       configured and 320 as stored. Measured: with {@code @Size(max = 255)} that configuration
 *       <em>started the context</em>, and the boot would then have died inside the INSERT with
 *       {@code value too long for type character varying(255)} — the HD-306 defect, reintroduced.
 *       A bound on the raw property is a bound on the wrong string.</li>
 *   <li><strong>It also refuses too much, because the bound is conditional on state that does not
 *       exist at binding time.</strong> {@link DataSeeder#rejectOverLongEmail} may only refuse a
 *       boot whose {@link DataSeeder#run} would reach the INSERT — an over-long address beside a
 *       blank {@code seed.admin.password} writes nothing and must still boot, which is the gate
 *       HD-306 got wrong twice. Measured: the guard allows that configuration and
 *       {@code @Size(max = 255)} <em>refuses the boot</em>. Binding happens before anything can be
 *       asked about the password or about the existing row, so no binding-time mechanism —
 *       a constraint or a compact constructor — can express this gate.</li>
 *   <li><strong>A constraint's own refusal is weaker than the one it would replace, and in the
 *       wrong language.</strong> Measured: a bare {@code @Size} renders as
 *       {@code Reason: размер должен находиться в диапазоне от 0 до 255} — Hibernate Validator
 *       resolves the default message through the JVM's default locale, so a DC operator reads a
 *       refusal in whatever language the host happens to default to, with
 *       {@code Action: Update your application's configuration} under it: no variable, no folded
 *       length, no remedy. A hand-written {@code message =} fixes the language and names the
 *       variable (that is {@code AppProperties.Privacy}'s pattern and it is correct there), but it
 *       cannot fix either bullet above.</li>
 *   <li><strong>A failed binding-time constraint PRINTS THE VALUE.</strong> MEASURED (2026-10-03)
 *       on Hibernate Validator 9.1.0.Final through Boot 4.1's registered
 *       {@code BindValidationFailureAnalyzer}, with a {@code @Size} on a credential-shaped property
 *       — exactly the shape the ticket asked for — the startup console carries:
 *       <pre>
 * Binding to target …Properties failed:
 *
 *     Property: seed.admin.password
 *     Value: "CLEARTEXT-CANARY-CHARLIE-xxxxxxxxxxxxxxxx"
 *     Origin: "SEED_ADMIN_PASSWORD" from property source "systemEnvironment"
 *     Reason: &lt;the bullet above: whatever language the host defaults to&gt;
 *       </pre>
 *       That is {@code FailureAnalysis.description}, printed to the startup console verbatim, and
 *       nothing in this tree masks it (there is no {@code SanitizingFunction} bean here); the log
 *       collector reads every container's stdout ({@code docker-compose.observability.yml}) — so
 *       the refusal writes the seed password into the log store in clear. Reachability is not
 *       theoretical: {@code openssl rand -base64 96} is 128
 *       characters, which is precisely the mistake {@link DataSeeder#rejectOverLongPassword}'s
 *       message exists to catch, so the first value an operator trips the bound with is a value
 *       they then re-use. <strong>The shape that does not leak already exists in this tree</strong>
 *       — that imperative {@code @PostConstruct} check reports
 *       {@code PasswordLimits.byteLength(password)} and never the value
 *       ({@code DataSeeder.java:216-227}), which is the only reason the same refusal is safe to
 *       print. A constraint has no way to say that: the analyzer renders every failed binding the
 *       same way, whatever the property holds.</li>
 * </ul>
 * So this record carries no constraint, and therefore no {@code @Validated}: that annotation is
 * what makes a <em>declared</em> constraint fire on a properties class, and a class declaring none
 * has nothing for it to do ({@code AppProperties} states the rule, and
 * {@code ArchitectureRulesTest#validatedOnlyOnConfigurationProperties} enforces its one direction —
 * {@code @Validated} implies {@code @ConfigurationProperties}, never the reverse).
 *
 * <p><strong>And the last bullet is held over the CATEGORY, not over this record.</strong> The
 * property above is one of several whose value is a credential, every one of them a component of a
 * {@code @ConfigurationProperties} record with a default {@code toString}, and none of them carried
 * anything that refused a constraint before HD-326. So {@code password} is marked
 * {@link Credential} and {@code CredentialPropertiesCarryNoConstraintTest} refuses a
 * {@code jakarta.validation} constraint on every marked component and {@code @Validated} on every
 * type declaring one — with the measured console output above in its failure message, because a
 * rule whose reason is a leak has to show the leak. That test also measures the leak itself
 * against the real analyzer on every run, so this bullet is a live check and not a memory.
 *
 * <h2>{@code @DefaultValue} exists only on the constructor parameter</h2>
 * <strong>MEASURED (2026-10-02):</strong> {@code @DefaultValue} declares
 * {@code @Target({PARAMETER})} — {@code RECORD_COMPONENT} is not among its targets and neither is
 * {@code FIELD}. So for all three components below,
 * {@code getRecordComponents()[i].getAnnotation(DefaultValue.class)} returns {@code null}, and so
 * does the backing field and so does the accessor; only
 * {@code getDeclaredConstructors()[0].getParameters()[i]} carries it. A derived population built on
 * the component (or the field, or the accessor) would report every member as undefaulted — or,
 * inverted, excuse every member for ever — while looking like it works. The one reflective reader
 * in the test tree is {@code common.testsupport.SeedAdminDefaults}, and it is proved by a planted
 * violation rather than by this paragraph.
 *
 * @param email       the address to seed, {@code ""} meaning "seed nobody". Stored lower-cased, so
 *                    the length that matters is the folded one — bounded by
 *                    {@link DataSeeder#rejectOverLongEmail}
 * @param password    the password to seed, {@code ""} meaning "skip seeding". Bounded in UTF-8
 *                    BYTES by {@link DataSeeder#rejectOverLongPassword}, because that is the unit
 *                    BCrypt refuses in, and refused by name by
 *                    {@link DataSeeder#rejectPublishedPassword} when it is the value this project
 *                    published. Marked {@link Credential}: no {@code jakarta.validation} constraint
 *                    may be declared on it, for the reason the fourth bullet above measures
 * @param displayName the seeded account's display name. Defaulted rather than empty on purpose: a
 *                    display name is not an identity, so it is not one of the values a template may
 *                    not ship filled in ({@code EnvTemplateGuardTest})
 */
@ConfigurationProperties("seed.admin")
public record SeedAdminProperties(
        @DefaultValue("") String email,
        @DefaultValue("") @Credential String password,
        @DefaultValue("Admin") String displayName
) {
}
