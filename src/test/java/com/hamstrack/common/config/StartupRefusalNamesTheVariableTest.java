package com.hamstrack.common.config;

import com.hamstrack.common.testsupport.Doors;
import com.hamstrack.common.testsupport.Doors.ConfigProperty;
import com.hamstrack.common.testsupport.RelaxedNames;
import com.hamstrack.common.testsupport.SourceCallSites;
import com.hamstrack.ops.PublishedCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-315 &mdash; a refusal that names only the Spring property names something the operator
 * does not have.</strong>
 *
 * <h2>The claim being sealed</h2>
 * An operator configures this application with <em>environment variables</em>: {@code .env} holds
 * {@code STORAGE_S3_BUCKET}, and {@code docker-compose.yml} passes it in. Spring, on the other
 * hand, reports <em>properties</em> &mdash; {@code app.storage.s3.bucket} &mdash; because that is
 * the name binding failed on. When a startup refusal quotes only the property, the operator reads a
 * name that appears nowhere in any file they have edited, and a search for it across their own
 * configuration returns nothing. The filed instance was
 * {@code "app.storage.type=s3 requires app.storage.s3.bucket"}: two property names, no variable, no
 * remedy.
 *
 * <p>The project already had the right shape in several places &mdash; the fault named as a
 * property, the remedy named as a variable, as in {@code S3FileStorage} or
 * {@code StorageQuotaConsistency} &mdash; and it had the wrong shape in others. That is the ordinary
 * way a convention decays: it is carried by whoever remembers it. This test is the mechanism, so
 * the next refusal message is held by something other than review.
 *
 * <h2>Why the mapping is derived rather than listed</h2>
 * The property-to-variable mapping is not written down here; it is read out of the sources, from the
 * two places a property can acquire a variable in this application:
 * <ul>
 *   <li><strong>a placeholder</strong> in {@code src/main/resources/application*.properties} &mdash;
 *       {@code some.property=${SOME_VARIABLE:default}}. The placeholder <em>is</em> the mapping, and
 *       it is the same text Spring itself resolves. This is where a variable whose name differs from
 *       its property is declared ({@code spring.datasource.hikari.validation-timeout} is set from
 *       {@code DB_CONNECTION_TIMEOUT_MS}), so it wins wherever both derivations speak.</li>
 *   <li><strong>a {@code @Value("${some.property:default}")} binding</strong> in
 *       {@code src/main/java}, whose variable is the one Spring's <em>relaxed binding</em> looks for
 *       ({@link #relaxedVariableName}). Such a property can have no line in any properties file at
 *       all, so a mapping built from placeholders alone cannot see it. That is not hypothetical
 *       history: it could not see the three {@code seed.admin.*} properties, which is how the boot
 *       refusal at {@code DataSeeder.run} named {@code seed.admin.email} twice and
 *       {@code SEED_ADMIN_EMAIL} never while this test stayed green (HD-315 review). That the
 *       relaxed name is the one Spring resolves is not assumed here:
 *       {@link #theRelaxedVariableNameIsTheOneSpringActuallyResolves} asks the real
 *       {@code SystemEnvironmentPropertySource}.</li>
 * </ul>
 * A hand-kept list would have to be maintained alongside both and would go stale in exactly the
 * direction nothing notices: a property that gains a variable, or a variable that is renamed.
 *
 * <h2>Where the {@code @Value} arm's floor went &mdash; a deliberate move, not an erosion</h2>
 * HD-326 converted {@code seed.admin.*} to a bound {@code @ConfigurationProperties} record, and
 * those three were the <strong>only</strong> {@code @Value("${...}")} bindings in
 * {@code src/main/java} (measured: 3 of 3). So the second derivation above now maps nothing, and the
 * floor that used to sit on it (&ge;&nbsp;3) could only be satisfied by re-introducing the shape it
 * was floored to make visible. Lowering it to zero would have left a live-looking arm over an empty
 * set, which is the exact defect this class is a fix loop for.
 *
 * <p>That record deliberately has <strong>no line in {@code application.properties}</strong> — the
 * comment standing at the {@code seed.admin} position in that file carries the measurement that says
 * why, and {@code SeedAdminProperties}' own javadoc repeats it. Which is precisely why the floor had
 * to move onto a derivation that can see a root with no line at all, rather than onto the
 * placeholder mapping: a root is entitled to have no line, so a mapping keyed on lines is blind to
 * exactly the properties this test was written because of.
 *
 * <p>So the floor moved onto a <strong>third derivation</strong> that covers the same blindness for
 * the shape those properties now have: {@link #configurationPropertyMapping} reads every
 * {@code @ConfigurationProperties} root and every component under it from
 * {@link Doors#configurationProperties()}, and maps each to its relaxed variable name. Its roots are
 * held equal to a second derivation from {@code src/main/java}, so a truncated compile disagrees
 * with the sources instead of shrinking this mapping in silence. That is strictly wider than the arm it replaces
 * &mdash; every root and every component under it, against three {@code @Value} bindings &mdash; and
 * a root that arrives tomorrow is mapped without an edit here. No size is written down in this
 * sentence on purpose: the derivation prints both populations on every run, green or red, and a
 * figure in prose goes stale one entry before the list it describes. The {@code @Value} derivation itself
 * stays wired into the union, unfloored, because its population is legitimately empty today: a
 * future {@code @Value} is mapped on the day it is written, and its own assertion (a binding must
 * be readable on its own line) still fires. All three populations are printed on every run.
 *
 * <h2>The one exclusion, and how it is checked</h2>
 * A property named in a refusal for which neither derivation produced a variable is not a member
 * &mdash; but "not settable" and "this test cannot see it" have the same shape, which is how the
 * {@code seed.admin.*} hole survived. So the exclusion proves its own claim instead of asserting it:
 * every excluded property's relaxed variable name is searched for in the files an operator actually
 * edits ({@code .env*} and {@code docker-compose*.yml} &mdash; enumerated from {@code git ls-files},
 * never listed and never globbed off the working tree, so only files this repository <em>ships</em>
 * can decide a verdict), and an excluded property whose variable turns up there fails this test
 * naming the file it was found in. The set and the files themselves are printed on every run, green
 * or red, so a mapping or a population that collapses is visible rather than inferred from a number.
 *
 * <p>There is <strong>no developer-facing exclusion</strong>, and its removal is part of HD-315's
 * fix loop. It used to excuse an entire message that named any {@code Foo.BAR_BAZ} constant, on the
 * reasoning that such a refusal addresses whoever holds the source. Measured consequence: the
 * {@code InviteProperties} retention message named three operator properties, prescribed an operator
 * remedy ("RAISE THE RETENTION"), and was excused in full because one clause mentioned
 * {@code MailThrottlePolicy.MAX_CEILING_WINDOW} &mdash; so one of the six messages the fix claimed to
 * seal was not sealed. No derivable test can tell which of a message's readers it is addressed to,
 * and an exclusion that cannot be checked is a lie; the cost of dropping it is one parenthesis in
 * each of the two genuinely developer-facing invariants ({@code MailThrottlePolicy},
 * {@code MailSendEventRetention}), which now name the variable too and lose nothing by it.
 *
 * <h2>Two populations, one rule (HD-326 fix loop)</h2>
 * A refusal is not the only text an operator greps their {@code .env} against. The same claim holds
 * over <strong>log lines</strong>, and it was being held over none of them: the two
 * {@code DataSeeder.run} lines that report an administrator was NOT created named
 * {@code seed.admin.email} / {@code seed.admin.password} and neither variable, in the very method
 * HD-315 was filed about, while this class stayed green &mdash; because its population is
 * {@code throw}n text and its own harness fixture asserts (still, deliberately) that a
 * {@code log.info} line is not a refusal. So there are now two rules,
 * {@link #everyStartupRefusalThatNamesAPropertyAlsoNamesTheVariableAnOperatorSets} and
 * {@link #everyLogLineThatNamesAPropertyAlsoNamesTheVariableAnOperatorSets}, over one scanner, one
 * mapping, one exclusion and one verdict routine ({@link #audit}). They differ in exactly two
 * places: the context that makes a literal run a member, and the boundary each kind can cross
 * invisibly (a format template for a refusal, an argument-passed variable name for a log line).
 *
 * <h2>What counts as an operator-facing message</h2>
 * A run of string literals (text blocks included) joined by {@code +} &mdash; constants and
 * expressions between them are permitted, so {@code "a " + FLOOR + " b"} is one message &mdash;
 * whose first literal is the argument of a thrown {@code IllegalStateException} /
 * {@code IllegalArgumentException}, the value of a bean-validation {@code message = }, or the first
 * argument of a {@code log.trace/debug/info/warn/error} call. Two things
 * are resolved into that text rather than left invisible, because the refusal this test was asked to
 * hold up as an example used both:
 * <ul>
 *   <li><strong>{@code static final String} constants</strong> declared in the same file, for the
 *       identifiers appearing inside the message's own expression &mdash;
 *       {@code ACQUISITION_PROPERTY + " (" + ACQUISITION_ENV_VAR + ") "} is a property and a
 *       variable, and neither is a literal at that site;</li>
 *   <li><strong>a message-assembling helper</strong>: {@code throw new IllegalStateException(
 *       refusal("is BLANK. " + ...))} carries the whole of {@code refusal}'s body, so moving a
 *       message into a private method no longer moves it out of this test's sight.</li>
 * </ul>
 * Each of those shapes is planted in a synthetic source and asserted by
 * {@link #theScannerReadsEveryShapeItsJavadocClaims}, so this paragraph is a live check rather than a
 * description. "Text blocks included" is the one that most needed it: no production refusal is
 * written as one today, so nothing else would notice the capability going away.
 *
 * <h2>Boundaries &mdash; what this scanner cannot see, and which of those are held</h2>
 * <ul>
 *   <li><strong>{@code String.format} / {@code .formatted()} templates &mdash; HELD, not merely
 *       stated.</strong> The template is a literal and is read; its arguments are expressions and
 *       are not, so a variable passed as an argument would be invisible. Rather than describe that,
 *       the main test refuses a refusal written that way outright. It is a boundary nobody can walk
 *       across without being told, which is the difference between this line and the two below.</li>
 *   <li><strong>A helper called through another helper</strong>, or one declared in a different
 *       file. One hop from the {@code throw} is resolved; two are not. Open.</li>
 *   <li><strong>A constant declared in another class</strong> ({@code Other.PROPERTY}). Only
 *       same-file declarations are resolved. Open.</li>
 * </ul>
 * The two open ones are left open because closing them needs cross-file symbol resolution, which is
 * a compiler and not a scanner; both are stated here so a reader of a green run knows what the green
 * does not cover.
 * Javadoc cannot be a member by construction: this reads string literals, and {@code {@code app.foo}}
 * in a comment is not one. A message that names no property is not a member either &mdash; the rule
 * is about messages that have already chosen to name configuration.
 *
 * <h2>The floors</h2>
 * One per independent way this test can go vacuous: the placeholder mapping could stop being
 * derived, the {@code @ConfigurationProperties} mapping could stop being derived, either walk could
 * stop finding sources (the refusal population and the log population are floored separately,
 * because one collapsing while the other grows is exactly the shape that hid the seed skip lines),
 * the filter could stop matching any property, and the operator-file search
 * backing the exclusion could stop finding files. Each is asserted with a number below what the tree
 * holds today, so ordinary growth never trips them and a collapse always does. The roots of the
 * third derivation are the one thing held by an <em>equality</em> rather than a floor, because the
 * failure mode there is a partial loss that a floor's slack absorbs: a stale compile dropping a few
 * roots keeps any floor green while the mapping loses every property under them.
 *
 * <p>One of them used to be the {@code @Value} mapping's; the section above says why it moved and
 * what it moved onto. A floor was relocated, not retired &mdash; and the arm it left behind is
 * still called, still asserts its own readability invariant, and still contributes to the union.
 */
class StartupRefusalNamesTheVariableTest {

    private static final Path JAVA_ROOT = Path.of("src", "main", "java", "com", "hamstrack");
    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Path REPO_ROOT = Path.of(".");

    /** {@code some.property=${SOME_VARIABLE:default}} &mdash; the placeholder is the mapping. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("^\\s*([A-Za-z0-9._-]+)\\s*=\\s*\\$\\{([A-Z0-9_]+)[:}]");

    /**
     * {@code ${some.property:default}} inside a {@code @Value}, matched one LINE at a time for the
     * same reason everything else here scans characters: the input a pattern sees stays short.
     */
    private static final Pattern VALUE_PLACEHOLDER = Pattern.compile("\\$\\{([a-z0-9][A-Za-z0-9._-]*)[:}]");

    /**
     * A dotted, lower-case configuration property under one of this application's roots.
     *
     * <p><strong>Two segments, not three</strong> (HD-315 fix loop). The first version of this
     * pattern required {@code root.a.b}, so {@code jwt.secret} &mdash; the variable every install
     * must set, and the subject of two boot refusals &mdash; could not be a member of its own
     * category: stripping {@code (JWT_SECRET)} from both left this test green. The roots are the
     * prefixes this application's own configuration uses; {@code logging} and {@code management} are
     * here because {@code application.properties} binds variables under both.
     */
    private static final Pattern PROPERTY = Pattern.compile(
            "\\b((?:app|spring|seed|jwt|logging|management)\\.[a-z0-9]+(?:[.-][a-z0-9]+)*)\\b");

    /** A {@code SCREAMING_SNAKE} identifier, which is what a same-file string constant is called. */
    private static final Pattern CONSTANT_REFERENCE = Pattern.compile("\\b([A-Z][A-Z0-9_]{2,})\\b");

    /** {@code static final String NAME =} immediately before a literal run. */
    private static final Pattern CONSTANT_DECLARATION =
            Pattern.compile("static\\s+final\\s+String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*$");

    /** {@code throw new IllegalStateException(refusal(} &mdash; a message assembled one hop away. */
    private static final Pattern REFUSAL_HELPER_CALL = Pattern.compile(
            "throw\\s+new\\s+Illegal(?:State|Argument)Exception\\s*\\(\\s*([a-z][A-Za-z0-9_]*)\\s*\\(");

    /**
     * What may sit between the opening of a message-carrying call and the message's first literal:
     * one message-assembling helper call, and up to three constants the message starts with
     * ({@code throw new IllegalStateException(PROPERTY + " (" + VAR + ") …")}).
     *
     * <p>The constant prefix repeats at most three times <em>on purpose</em>. An unbounded
     * {@code (…)*} of a group that itself contains quantifiers is the nested shape that overflowed
     * this test's stack twice before; the input here is the 90 characters before a literal, so three
     * is past anything this codebase writes and the repetition can never run away.
     */
    private static final String MESSAGE_TAIL =
            "\\s*(?:[a-z][A-Za-z0-9_]*\\s*\\(\\s*)?(?:[A-Z][A-Z0-9_]{2,}\\s*\\+\\s*){0,3}$";

    /**
     * The contexts that make a literal run a refusal rather than prose: the argument of a thrown
     * {@code Illegal…Exception} or a bean-validation {@code message = }.
     */
    private static final Pattern REFUSAL_CONTEXT = Pattern.compile(
            "(?:throw\\s+new\\s+Illegal(?:State|Argument)Exception\\s*\\(|message\\s*=\\s*)" + MESSAGE_TAIL);

    /**
     * The other kind of operator-facing message: a log line, at every level. {@code debug} and
     * {@code trace} are included although no {@code debug} line names a property today &mdash; the
     * rule is about what an operator reads, and nothing about the reason depends on the level, so a
     * level-shaped exclusion would be an exclusion with no property behind it.
     */
    private static final Pattern LOG_CONTEXT = Pattern.compile(
            "log\\s*\\.\\s*(?:trace|debug|info|warn|error)\\s*\\(" + MESSAGE_TAIL);

    /** Which kind of message a literal run turned out to be. Both kinds are audited the same way. */
    private enum Kind { REFUSAL, LOG }

    /**
     * String literals are found by a character scan, not by a pattern. The natural expression
     * ({@code "((?:[^"\\]|\\.)*)"}) recurses once per matched character in Java's engine, and this
     * codebase's messages are long enough to exhaust the stack &mdash; measured as a second
     * {@code StackOverflowError} from this test, after the first one was fixed elsewhere. Two
     * different patterns failing the same way is the reason there is no third.
     */
    private record Literal(int start, int end, String text) { }

    /**
     * @param argumentTail the rest of the call after the message's last literal, up to the next
     *                     {@code ;} &mdash; where a variable name passed as an ARGUMENT would be,
     *                     which is the one shape this scanner can see but not read (see
     *                     {@link #everyLogLineThatNamesAPropertyAlsoNamesTheVariableAnOperatorSets})
     */
    private record Message(String file, int line, String text, Kind kind, String argumentTail) {

        String site() {
            return file + ":" + line;
        }
    }

    /**
     * Only {@code +}, identifiers and constants may sit between two literals of one message. This is
     * a character scan rather than a regex on purpose: the expression that reads naturally here
     * ({@code \s*\+\s*(?:ident\s*\+\s*)*}) nests two quantifiers, and a long gap between literals
     * makes it backtrack catastrophically &mdash; measured as a {@code StackOverflowError} from this
     * very test on its first run, not reasoned about.
     */
    private static boolean isConcatenation(String gap) {
        if (gap.length() > 200 || gap.indexOf('+') < 0) {
            return false;
        }
        // Named by what BREAKS a concatenation rather than by what is allowed inside one. The
        // allow-list version produced two false positives in a row, both on messages that already
        // did the right thing: a method call between literals (`"app.base-url (" +
        // appProperties.baseUrl() + ") has no host"`) and arithmetic (`+ (MARGIN * lockMs) +`).
        // Each time the run was cut short and the surviving head named a property and no variable.
        // The breaking characters are few and stable: an argument separator, a statement end, and a
        // block boundary. Everything else is an expression, and an expression between two literals
        // joined by `+` is part of the same message.
        for (int i = 0; i < gap.length(); i++) {
            char c = gap.charAt(i);
            if (c == ',' || c == ';' || c == '{' || c == '}') {
                return false;
            }
        }
        return true;
    }

    /**
     * Spring's relaxed binding for an environment variable: upper case, dots and dashes to
     * underscores. Held against the real {@code SystemEnvironmentPropertySource} by
     * {@link #theRelaxedVariableNameIsTheOneSpringActuallyResolves} rather than remembered — and the
     * transform itself is {@link RelaxedNames}, the one spelling of it, so that probe holds the
     * implementation {@code Doors.configurationProperties()} and {@code SeedAdminDefaults} use and
     * not a third copy that agreed with them on the day it was written.
     */
    private static String relaxedVariableName(String property) {
        return RelaxedNames.variableFor(property);
    }

    /**
     * The three derivations, merged once. Placeholders win wherever two of them speak: that line is
     * the text Spring itself resolves, and it is the only one that can carry a variable whose name
     * differs from its property.
     */
    private static Map<String, String> propertyToVariable() {
        Map<String, String> fromPlaceholders = placeholderMapping();
        assertThat(fromPlaceholders)
                .withFailMessage("""
                        NO PROPERTY-TO-VARIABLE MAPPING WAS DERIVED FROM THE PROPERTIES FILES, so this
                        test is checking almost nothing.

                        The mapping is read from `%s/application*.properties`, where an
                        operator-settable property is bound as `some.property=${SOME_VARIABLE:default}`.
                        If those files moved, changed shape, or stopped using placeholders, teach this
                        test where the mapping lives now - do not delete the assertion.""", RESOURCES)
                .hasSizeGreaterThanOrEqualTo(90);

        Map<String, String> fromConfigurationProperties = configurationPropertyMapping();
        assertThat(fromConfigurationProperties)
                .withFailMessage("""
                        NO @ConfigurationProperties PROPERTY WAS DERIVED, so this test is blind to
                        every property that reaches the application through the environment WITHOUT a
                        line in any properties file. That blindness is not hypothetical: it is why
                        `seed.admin.email` was exempt from its own category while a boot refusal named
                        it twice and SEED_ADMIN_EMAIL never.

                        This floor used to sit on the `@Value` derivation below, which was the only
                        thing that could see `seed.admin.*` until HD-326 made it an ordinary bound
                        record. If the roots moved, teach configurationPropertyMapping() where they
                        live now - do not delete the assertion.""")
                .hasSizeGreaterThanOrEqualTo(80);

        // Unfloored on purpose, and the class javadoc says where its floor went: the three bindings
        // it used to hold up moved to a @ConfigurationProperties record (HD-326) and they were the
        // whole population. The arm stays wired in so a future @Value is mapped the day it is
        // written, and its own "readable on its own line" assertion still fires inside.
        Map<String, String> fromValueBindings = valueBindingMapping();

        // Placeholders win wherever two derivations speak: that line is the text Spring itself
        // resolves, and it is the only one that can carry a variable whose name differs from its
        // property. The reflective derivation can only ever produce the relaxed spelling.
        var merged = new LinkedHashMap<>(fromPlaceholders);
        fromValueBindings.forEach(merged::putIfAbsent);
        fromConfigurationProperties.forEach(merged::putIfAbsent);

        // Reported on every run, green or red, by whichever rule asked for the mapping. The javadoc
        // used to promise this and two counters delivered it to nobody: one was incremented and
        // never read, the other was interpolated only into an assertion that could not fire.
        System.out.println("[HD-315] " + merged.size() + " propert(ies) mapped to a variable ("
                           + fromPlaceholders.size() + " from placeholders, "
                           + fromValueBindings.size() + " from @Value, "
                           + fromConfigurationProperties.size() + " from @ConfigurationProperties)");
        return merged;
    }

    /** The tracked operator-facing files, with the floor that keeps the one exclusion checkable. */
    private static Map<String, String> operatorFilesWithAFloor() {
        Map<String, String> operatorFiles = operatorFiles();
        assertThat(operatorFiles)
                .withFailMessage("""
                        NO OPERATOR-FACING FILE WAS FOUND (the TRACKED `.env*` and
                        `docker-compose*.yml` files of %s), so the one exclusion in this test - "this
                        property has no variable, therefore no operator can set it" - can no longer be
                        checked and would excuse anything. Point this search at the deployment
                        templates; do not widen it back to a walk of the working tree, which would let
                        an untracked file decide this test's verdict.""",
                        REPO_ROOT.toAbsolutePath().normalize())
                .hasSizeGreaterThanOrEqualTo(6);
        System.out.println("[HD-315] operator files: " + operatorFiles.keySet());
        return operatorFiles;
    }

    /** The exclusion set and the evidence for each entry, printed whatever the outcome. */
    private static void report(String what, Audit audit, int populationSize) {
        System.out.println("[HD-315] " + populationSize + " " + what + "; "
                           + audit.namingNoProperty() + " name no property; "
                           + audit.members().size() + " (site, property) member(s); "
                           + audit.checked().size() + " operator-settable propert(ies) checked");
        System.out.println("[HD-315] " + what + " excluded as not operator-settable ("
                           + audit.excluded().size() + "): " + (audit.excluded().isEmpty() ? "none" : ""));
        audit.excluded().forEach((property, why) -> System.out.println("[HD-315]   " + property + " - " + why));
        System.out.println("[HD-315] " + what + " checked: " + audit.checked());
        audit.members().forEach(System.out::println);
    }

    @Test
    void everyStartupRefusalThatNamesAPropertyAlsoNamesTheVariableAnOperatorSets() {
        Map<String, String> propertyToVariable = propertyToVariable();

        List<Message> messages = messagesOfKind(Kind.REFUSAL);
        assertThat(messages)
                .withFailMessage("""
                        NO REFUSAL MESSAGE WAS FOUND under %s, so this test is checking nothing.

                        A refusal is a run of string literals thrown as IllegalStateException /
                        IllegalArgumentException, or given as a bean-validation `message = `. If the
                        fail-fast sites moved or changed shape, point this test at them.""", JAVA_ROOT)
                .hasSizeGreaterThanOrEqualTo(55);

        Audit audit = audit(messages, propertyToVariable, operatorFilesWithAFloor());
        report("refusal message(s)", audit, messages.size());

        assertThat(audit.checked())
                .withFailMessage("""
                        NO OPERATOR-SETTABLE PROPERTY WAS CHECKED, so this test passed without
                        comparing anything. %d message(s) were read, %d of them named no property at
                        all, and %d property name(s) were excluded as unsettable: %s

                        Either the PROPERTY pattern stopped matching this codebase's naming, or the
                        mapping keys stopped agreeing with the names the messages use.""",
                        messages.size(), audit.namingNoProperty(), audit.excluded().size(),
                        audit.excluded().keySet())
                .hasSizeGreaterThanOrEqualTo(18);

        // The one boundary below that can be held rather than merely stated. A format template is
        // read by this scanner; its ARGUMENTS are not, so `"app.foo (%s) is wrong".formatted(VAR)`
        // would satisfy every check here while naming no variable in any text this test can see. It
        // is unpopulated today, and this is what keeps it that way.
        List<String> formatAssembled = formatAssembledRefusals();
        assertThat(formatAssembled)
                .withFailMessage("""
                        A STARTUP REFUSAL IS ASSEMBLED FROM A FORMAT TEMPLATE, which this test reads
                        only half of: the template is a literal and is scanned, the arguments are
                        expressions and are not. A variable name passed as an argument is invisible
                        here, so such a refusal would pass while naming the operator nothing.

                        %s

                        Write the property and its variable into the literal text - `app.storage.type
                        (STORAGE_TYPE)` - and keep the template for the values that genuinely vary.""",
                        String.join("\n", formatAssembled))
                .isEmpty();

        assertThat(audit.offences())
                .withFailMessage("""
                        A STARTUP REFUSAL NAMES A PROPERTY THE OPERATOR DOES NOT HAVE.

                        Spring reports the property, because that is the name binding failed on. The
                        operator set an environment variable, and a search for the property across
                        their own `.env` and compose file finds nothing. Name both: the property for
                        the fault, the variable for the remedy.

                        %s

                        The shape to copy is `S3FileStorage` or `StorageQuotaConsistency`, which say
                        it as `app.storage.s3.bucket (STORAGE_S3_BUCKET)` in plain concatenated
                        literals - the shape this scanner reads most reliably. The variable name is
                        not invented here: it is read from the `${...}` placeholder in
                        src/main/resources/application*.properties, or from the `@Value("${...}")`
                        binding, whose variable is the relaxed-binding spelling of the property.""",
                        String.join("\n", audit.offences()))
                .isEmpty();
    }

    /**
     * <strong>A LOG LINE that names a configuration property names the variable too</strong>
     * (HD-326 fix loop) &mdash; the same rule as the refusal above, over the other population of
     * operator-facing text, through the same scanner, the same mapping and the same exclusion.
     *
     * <h2>Why this exists, and why it is not a second test of the same thing</h2>
     * The refusal population is {@code throw}n text, and this class's own harness fixture asserts
     * that a {@code log.info} line is NOT a member of it &mdash; correctly, for that rule. So the
     * two lines in {@code DataSeeder.run} that say an administrator was NOT created were held by
     * nothing at all, and they said {@code seed.admin.email} / {@code seed.admin.password}: two
     * property names that appear in no {@code .env}, no compose file, no template and no README, so
     * an operator who greps their own configuration for the string in the log finds nothing. That is
     * the HD-315 defect, in the same method it was first found in, arriving through a door the
     * HD-315 rule does not watch. MEASURED on a {@code dc} boot with {@code SEED_ADMIN_PASSWORD_0}
     * set and {@code SEED_ADMIN_PASSWORD} unset: the WARN landed ~111 ms AFTER
     * {@code Started HamstrackApplication}, {@code docker compose up --wait} reported success, and
     * the documented sign-in answered 401.
     *
     * <h2>The population, and the two granularity controls</h2>
     * Every {@code log.*} literal run under {@code src/main/java}. The unit is
     * <strong>(site, property)</strong>, and both halves of that are controlled rather than assumed,
     * because a population counted at one granularity and checked at another cannot fail:
     * <ul>
     *   <li>one FILE must contribute two or more member sites, or the unit could be the file and
     *       nobody could tell the difference from a green run;</li>
     *   <li>one SITE must name two or more properties, or the unit could be the site &mdash; one
     *       named property's variable would then answer for its neighbour's.</li>
     * </ul>
     * Both controls name what satisfied them when they were written and say to re-point rather than
     * delete; no count in this javadoc is load-bearing, because every population and every member is
     * printed on each run, green or red.
     *
     * <h2>What it cannot see</h2>
     * The arguments of the call, which is where a log line's values go. A variable NAME handed over
     * that way would be invisible, so that shape is refused outright rather than described
     * ({@link #VARIABLE_AS_ARGUMENT} over {@link #argumentTailOf}) &mdash; unpopulated today, which
     * is the only reason the boundary needs holding at all.
     */
    @Test
    void everyLogLineThatNamesAPropertyAlsoNamesTheVariableAnOperatorSets() {
        Map<String, String> propertyToVariable = propertyToVariable();

        List<Message> messages = messagesOfKind(Kind.LOG);
        assertThat(messages)
                .withFailMessage("""
                        FEWER THAN 90 LOG MESSAGES WERE FOUND under %s, so this rule is looking at a
                        fraction of this application's operator-facing text and passing for that reason.

                        A log message is a run of string literals passed to log.trace/debug/info/warn/
                        error. If the logging shape changed - a facade of our own, a structured-event
                        builder - point LOG_CONTEXT at it rather than lowering this floor.""", JAVA_ROOT)
                .hasSizeGreaterThanOrEqualTo(90);

        Audit audit = audit(messages, propertyToVariable, operatorFilesWithAFloor());
        report("log message(s)", audit, messages.size());
        System.out.println("[HD-315] log members per file: " + audit.perFile());

        assertThat(audit.members())
                .withFailMessage("""
                        FEWER THAN 8 LOG MESSAGES NAME A CONFIGURATION PROPERTY, so this rule has
                        almost nothing to check. %d message(s) were read and %d named no property at
                        all. Either the PROPERTY pattern stopped matching this codebase's naming, or
                        the literal runs stopped being readable - repair whichever it is instead of
                        lowering this.""", messages.size(), audit.namingNoProperty())
                .hasSizeGreaterThanOrEqualTo(8);

        // Granularity control 1: the unit is not the FILE.
        assertThat(audit.perFile().values())
                .withFailMessage("""
                        NO FILE CARRIES TWO MESSAGES THAT NAME A PROPERTY ANY MORE (%s), so this rule
                        can no longer tell whether its unit is the message SITE or the file - the two
                        granularities agree on every one-member file, and that agreement is what hid an
                        HD-306 defect inside a green category test. DataSeeder carried five when this
                        was written; if its messages moved, re-point this control at whichever file now
                        carries two.""", audit.perFile())
                .anyMatch(count -> count >= 2);

        // Granularity control 2: nor is it the SITE.
        assertThat(audit.perSite().values())
                .withFailMessage("""
                        NO SINGLE MESSAGE NAMES TWO PROPERTIES ANY MORE (%s), so this rule can no
                        longer tell whether its unit is (site, property) or just the site: one named
                        property's variable would answer for its neighbour's. The password skip line in
                        DataSeeder.run named two when this was written, and the version of it this rule
                        was built for named one of them and no variable for either.""", audit.perSite())
                .anyMatch(count -> count >= 2);

        assertThat(audit.offences())
                .withFailMessage("""
                        A LOG LINE NAMES A CONFIGURATION PROPERTY THE OPERATOR DOES NOT HAVE.

                        An operator reads the log and greps their own `.env` for the name in it. A
                        property name appears in no file they have ever edited, so the search returns
                        nothing and the message has told them about a setting they cannot find. Name
                        both: the property for the fault, the variable for the remedy - and for a line
                        reporting something NOT done, say what the reader loses by it and what to do.

                        %s

                        The shape to copy is the pair in DataSeeder.run: `seed.admin.password
                        (SEED_ADMIN_PASSWORD) is empty, so no administrator account was created`,
                        followed by one remedy its reader can perform. Plain concatenated literals -
                        this scanner reads those most reliably - and the `{}` arguments stay for the
                        values that vary.""", String.join("\n", audit.offences()))
                .isEmpty();
    }

    /**
     * Every bindable property of every {@code @ConfigurationProperties} root, mapped to the
     * variable Spring's relaxed binding resolves it from — the third derivation, and the one that
     * took over the {@code @Value} arm's floor (HD-326). The enumeration is
     * {@link Doors#configurationProperties()}, which is where "what are all the X" lives in this
     * repository; the arithmetic below is this test's.
     *
     * <p><strong>The roots are derived TWICE and the two sets must be equal</strong> (HD-326 fix
     * loop). Doors reads compiled classes, and this repository's own rule for a bound is that it
     * comes from sources, never from compiled output — "a truncated compile truncates the bound in
     * the same stroke and agrees with the bug". The first version of this method conceded that in
     * prose ("a truncated or stale {@code target/classes} shrinks this set"), where the convention
     * asks for a mechanism: a stale compile dropping fewer roots than the floor's slack keeps the
     * floor green while the mapping silently loses those roots' properties, and a root whose
     * properties no shipped {@code .env} or compose file mentions then disappears into
     * {@code excluded} with "no file an operator edits mentions X" — the "not settable" versus
     * "this test cannot see it" confusion this class exists because of. So
     * {@link #configurationRootsInSources()} reads the annotation text out of
     * {@code src/main/java} and the two populations are asserted EQUAL. A truncated compile now
     * disagrees with the sources and reds, naming the roots that went missing.
     *
     * <p><strong>Why this and not three lines in {@code application.properties}.</strong> That was
     * tried first and MEASURED to be a worse answer: a line in the packaged properties file
     * outranks {@code SpringApplicationBuilder.properties(...)}, which lands in
     * {@code defaultProperties}, the lowest-precedence source there is. Adding
     * {@code seed.admin.email=${SEED_ADMIN_EMAIL:}} therefore shadowed the vehicle three seed tests
     * boot with and turned four of their cases vacuous — two refusals stopped firing, and two
     * "must still boot" cases started passing because the address was now always empty. An
     * environment variable outranks the file, so production was unaffected and nothing would have
     * announced the loss. A root may legitimately have no line, so the mapping has to be able to
     * see a root that has none, and the type is where that information actually lives.
     *
     * <p>This derivation is strictly wider than the one it replaces: it is keyed on the ROOTS and
     * their components rather than on the three {@code @Value} bindings that happened to be the
     * symptom in HD-315, so a root that arrives tomorrow is mapped without an edit here, whether or
     * not anybody gives it a placeholder line. Both figures are printed by this method and by the
     * main test on every run, which is the only place a count can be stated without going stale.
     *
     * <p>The compiled side reads the roots from the real annotation on the loaded class rather than
     * from a regex, because {@code {@code @ConfigurationProperties(prefix = "app")}} inside a javadoc
     * is text and a source scan cannot tell it from a declaration — which is exactly why the source
     * side below is comment-aware and refuses an occurrence it cannot read a prefix from, instead of
     * skipping it. Both spellings are read ({@code value} and {@code prefix}) — this codebase uses
     * both, and each leaves the other accessor empty. Nested records are walked, so
     * {@code app.privacy.contact-email} is mapped from {@code AppProperties.Privacy} and not only the
     * roots themselves. Test-planted properties records are outside the population by construction
     * now: {@code Doors} walks the code source of {@code HamstrackApplication}, so the explicit
     * {@code target/test-classes} filter this method used to carry is gone rather than kept as
     * decoration.
     */
    private static Map<String, String> configurationPropertyMapping() {
        var properties = Doors.configurationProperties();
        var compiledRoots = properties.stream().map(ConfigProperty::root)
                .collect(Collectors.toCollection(TreeSet::new));
        var sourceRoots = configurationRootsInSources();

        System.out.println("[HD-326] " + compiledRoots.size() + " compiled configuration root(s): "
                           + compiledRoots);
        System.out.println("[HD-326] " + sourceRoots.size() + " source-declared root(s); "
                           + properties.size() + " bindable propert(ies)");

        var onlyInSources = new TreeSet<>(sourceRoots);
        onlyInSources.removeAll(compiledRoots);
        var onlyCompiled = new TreeSet<>(compiledRoots);
        onlyCompiled.removeAll(sourceRoots);
        assertThat(compiledRoots)
                .withFailMessage("""
                        THE COMPILED @ConfigurationProperties ROOTS AND THE ONES DECLARED IN
                        src/main/java DISAGREE, so the property-to-variable mapping is derived from a
                        tree that is not the one this repository holds.

                        declared in sources but not compiled: %s
                          -> a truncated or stale target/classes. Every property under those roots is
                             missing from the mapping, and every refusal naming one is excused in
                             silence as "no file an operator edits mentions it". Run a full compile
                             (`mvnw clean compile`); never relax this to a floor, which is what a stale
                             compile agrees with.

                        compiled but not declared in sources: %s
                          -> a class file whose source is gone (`mvnw clean`), or a root this source
                             scan cannot read: it reads one line at a time, skips a commented-out
                             occurrence, and refuses one whose prefix is not a literal on that line.
                             Teach configurationRootsInSources() the new shape.""",
                        onlyInSources, onlyCompiled)
                .isEqualTo(sourceRoots);

        var mapping = new LinkedHashMap<String, String>();
        properties.forEach(property -> mapping.putIfAbsent(property.property(), property.variable()));
        return mapping;
    }

    /** {@code @ConfigurationProperties("app.foo")} / {@code (prefix = "app.foo")} — a declaration. */
    private static final Pattern CONFIGURATION_ROOT = Pattern.compile(
            "@ConfigurationProperties(?![A-Za-z])\\s*\\(\\s*(?:prefix\\s*=\\s*)?\"([A-Za-z0-9._-]+)\"");

    /**
     * The SECOND derivation of the root prefixes, from the annotation text under
     * {@code src/main/java} — the population the compiled one is held equal to, so that a truncated
     * compile disagrees with the sources rather than shrinking a bound in the same stroke as the bug.
     *
     * <p>Comment-aware through {@link SourceCallSites#isCommentedAt}, the one positional
     * comment judgement in the test tree (the {@code @Value} walk below uses it for the same
     * reason): {@code @ConfigurationPropertiesScan} is excluded by the pattern, a javadoc quoting the
     * annotation is excluded by the comment check, and an occurrence this scan cannot read a literal
     * prefix from on its own line FAILS rather than being skipped — a silently skipped declaration
     * would take its root out of this set and make the parity assertion accuse the compiler.
     */
    private static Set<String> configurationRootsInSources() {
        var roots = new TreeSet<String>();
        var unreadable = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(JAVA_ROOT)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int at = liveOccurrenceOf("@ConfigurationProperties", line);
                    if (at < 0 || Character.isLetter(charAfter(line, at + "@ConfigurationProperties".length()))) {
                        continue;
                    }
                    Matcher declaration = CONFIGURATION_ROOT.matcher(line);
                    declaration.region(at, line.length());
                    if (declaration.find()) {
                        roots.add(declaration.group(1));
                    } else {
                        unreadable.add(file + ":" + (i + 1) + "  " + line.strip());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(unreadable)
                .withFailMessage("""
                        A `@ConfigurationProperties` DECLARATION WAS NOT READABLE ON ITS OWN LINE, so its
                        root is missing from the source-derived population that the compiled one is held
                        equal to - and the parity assertion in configurationPropertyMapping() would then
                        blame a stale compile for this scan's blind spot.

                        %s

                        Put the prefix literal back on the annotation's line, or teach this scan the
                        shape it now has. Do not make it skip an occurrence it cannot read.""",
                        String.join("\n", unreadable))
                .isEmpty();
        return roots;
    }

    /**
     * The index of the first occurrence of {@code needle} on {@code line} that is not inside a
     * comment, or {@code -1}. One judgement, shared by both source walks in this class — the
     * {@code @Value} bindings and the {@code @ConfigurationProperties} roots — and positional on
     * purpose: a line that STARTS with code can still carry a commented-out occurrence.
     */
    private static int liveOccurrenceOf(String needle, String line) {
        for (int at = line.indexOf(needle); at >= 0; at = line.indexOf(needle, at + needle.length())) {
            if (!SourceCallSites.isCommentedAt(line, at)) {
                return at;
            }
        }
        return -1;
    }

    private static char charAfter(String line, int index) {
        return index < line.length() ? line.charAt(index) : ' ';
    }

    /**
     * The premise the {@code @Value} half of the mapping rests on, asked of the real library rather
     * than remembered: a property with no line in any properties file is set by the
     * <em>relaxed</em> spelling of its name, so {@code SEED_ADMIN_DISPLAY_NAME} is what an operator
     * puts in {@code .env} for {@code @Value("${seed.admin.display-name:Admin}")}. Both transforms
     * this test performs are exercised - the dot and the dash - because
     * {@code SystemEnvironmentPropertySource} handles them in separate steps.
     */
    @Test
    void theRelaxedVariableNameIsTheOneSpringActuallyResolves() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "hd-315-probe",
                Map.of(relaxedVariableName("seed.admin.email"), "root@example.com",
                        relaxedVariableName("seed.admin.display-name"), "Root")));

        assertThat(environment.getProperty("seed.admin.email")).isEqualTo("root@example.com");
        assertThat(environment.getProperty("seed.admin.display-name")).isEqualTo("Root");
    }

    /**
     * The scanner's own claims, planted in a synthetic source rather than asserted in prose. Every
     * shape this class's javadoc says it reads is read here, and the one it says it never reads - a
     * property named in a comment - is refused. Without this, "text blocks included" is a sentence:
     * no production refusal uses one today, so the capability would be exercised by nothing and
     * could rot into a lie between one release and the next, which is the shape HD-315 is a fix loop
     * for.
     *
     * <p><strong>And the two KINDS are held apart here</strong> (HD-326 fix loop). This fixture used
     * to assert that a {@code log.info} line is read by nothing, which was true and was the hole:
     * the log population is now its own rule, so the sample's log line must be read as a LOG message
     * and must NOT appear among the refusals. A scanner that merged the two would let a log line
     * satisfy the refusal floor, and a refusal satisfy the log rule's granularity controls.
     */
    @Test
    void theScannerReadsEveryShapeItsJavadocClaims() {
        String source = """
                class Sample {
                    static final String PROPERTY = "app.sample.constant";
                    static final String VAR = "SAMPLE_CONSTANT";

                    void plain() {
                        throw new IllegalStateException("app.sample.plain (SAMPLE_PLAIN) is wrong");
                    }

                    void startingWithAConstant() {
                        throw new IllegalStateException(PROPERTY + " (" + VAR + ") is wrong");
                    }

                    void throughAHelper() {
                        throw new IllegalStateException(refusal("is blank"));
                    }

                    private static String refusal(String problem) {
                        return "app.sample.helper (SAMPLE_HELPER) " + problem;
                    }

                    void asATextBlock() {
                        throw new IllegalStateException(\"""
                                app.sample.block (SAMPLE_BLOCK) is wrong\""");
                    }

                    // app.sample.comment is prose and must never be read as a refusal
                    void notARefusal() {
                        log.info("app.sample.log is a log line, not a refusal");
                    }
                }
                """;

        List<Message> scanned = messagesIn("Sample.java", source);
        String refusals = scanned.stream().filter(m -> m.kind() == Kind.REFUSAL)
                .map(Message::text).collect(Collectors.joining("\n"));
        String logLines = scanned.stream().filter(m -> m.kind() == Kind.LOG)
                .map(Message::text).collect(Collectors.joining("\n"));

        assertThat(refusals)
                .withFailMessage("""
                        THE REFUSAL SCANNER NO LONGER READS A SHAPE ITS JAVADOC CLAIMS IT READS, so
                        every production refusal written that way is exempt from this test while
                        looking covered. What it read from the sample source was:

                        %s""", refusals)
                .contains("app.sample.plain (SAMPLE_PLAIN)")
                .contains("app.sample.constant")
                .contains("SAMPLE_CONSTANT")
                .contains("app.sample.helper (SAMPLE_HELPER)")
                .contains("app.sample.block (SAMPLE_BLOCK)");

        assertThat(logLines)
                .withFailMessage("""
                        THE SCANNER NO LONGER READS A LOG LINE, so
                        everyLogLineThatNamesAPropertyAlsoNamesTheVariableAnOperatorSets is checking an
                        empty population while reporting the same green - and the two DataSeeder skip
                        lines that rule exists for would be held by nothing again. What it read as a log
                        message was:

                        %s""", logLines)
                .contains("app.sample.log");

        assertThat(refusals)
                .withFailMessage("""
                        THE REFUSAL SCANNER READ SOMETHING THAT IS NOT A REFUSAL (a comment, or a log
                        line), which turns that rule into a source of false accusations against messages
                        that are not its business - and lets a log line satisfy its population floor.
                        What it read as a refusal was:

                        %s""", refusals)
                .doesNotContain("app.sample.comment")
                .doesNotContain("app.sample.log");

        assertThat(logLines)
                .withFailMessage("""
                        THE LOG SCANNER READ SOMETHING THAT IS NOT A LOG LINE (a comment, or a thrown
                        refusal), so the two populations have merged: a refusal would satisfy the log
                        rule's granularity controls and vice versa. What it read as a log message was:

                        %s""", logLines)
                .doesNotContain("app.sample.comment")
                .doesNotContain("app.sample.plain");
    }

    /** {@code throw new IllegalStateException(String.format(} &mdash; a template, not a message. */
    private static final Pattern FORMAT_IN_REFUSAL = Pattern.compile(
            "throw\\s+new\\s+Illegal(?:State|Argument)Exception\\s*\\(\\s*"
            + "(?:[A-Za-z][A-Za-z0-9_.]*\\s*\\(\\s*)?(?:String\\.format|MessageFormat\\.format)\\s*\\(");

    /**
     * Refusals whose text is a format template. Two shapes: {@code String.format} applied inside the
     * {@code throw}, and {@code .formatted(} applied to the literal run that the {@code throw}
     * carries. The second is found from the literal side, because the closing literal of a run is
     * where {@code .formatted(} attaches.
     */
    private static List<String> formatAssembledRefusals() {
        var found = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(JAVA_ROOT)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList()) {
                String file = path.toString().replace('\\', '/');
                String source = Files.readString(path, StandardCharsets.UTF_8);
                Matcher format = FORMAT_IN_REFUSAL.matcher(source);
                while (format.find()) {
                    found.add("  %s:%d assembles its refusal with String.format"
                            .formatted(file, lineOf(source, format.start())));
                }
                for (Literal literal : literalsIn(source)) {
                    if (!source.startsWith(".formatted(", literal.end())) {
                        continue;
                    }
                    // Walk back to the nearest `Illegal…Exception(`; a `;` on the way means the
                    // template belongs to some other statement (a log line, an HTTP body).
                    int from = Math.max(0, literal.start() - 900);
                    String preceding = source.substring(from, literal.start());
                    int thrown = preceding.lastIndexOf("Exception(");
                    if (thrown >= 0 && preceding.indexOf(';', thrown) < 0
                        && preceding.substring(Math.max(0, thrown - 30), thrown).contains("Illegal")) {
                        found.add("  %s:%d applies .formatted() to its refusal text"
                                .formatted(file, lineOf(source, literal.start())));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).lines().count();
    }

    private static Map<String, String> placeholderMapping() {
        var mapping = new LinkedHashMap<String, String>();
        try (Stream<Path> files = Files.list(RESOURCES)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("application.*\\.properties"))
                    .toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    Matcher m = PLACEHOLDER.matcher(line);
                    if (m.find()) {
                        mapping.putIfAbsent(m.group(1), m.group(2));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return mapping;
    }

    /**
     * Every {@code @Value("${property:default}")} in the production tree, mapped to the variable
     * Spring's relaxed binding resolves it from. A {@code @Value} line that yields no placeholder
     * fails the walk rather than being skipped: that is the granularity control on this derivation,
     * because a binding written across two lines would otherwise shrink the mapping in silence -
     * which is the exact failure this half of the mapping exists to repair.
     */
    private static Map<String, String> valueBindingMapping() {
        var mapping = new LinkedHashMap<String, String>();
        var unreadable = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(JAVA_ROOT)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int at = liveOccurrenceOf("@Value(", line);
                    if (at < 0) {
                        continue;
                    }
                    // From the live occurrence to the end of the line: a placeholder written BEFORE
                    // it belongs to whatever that text is, and a second live @Value after it is
                    // covered by the same region.
                    Matcher m = VALUE_PLACEHOLDER.matcher(line);
                    m.region(at, line.length());
                    boolean bound = false;
                    while (m.find()) {
                        bound = true;
                        mapping.putIfAbsent(m.group(1), relaxedVariableName(m.group(1)));
                    }
                    if (!bound) {
                        unreadable.add(file + ":" + (i + 1) + "  " + line.strip());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(unreadable)
                .withFailMessage("""
                        A `@Value(` BINDING WAS NOT READABLE ON ITS OWN LINE, so the property it binds
                        is missing from this test's mapping and every refusal naming it is excused
                        without being checked. Put the placeholder back on the annotation's line, or
                        teach this walk the shape it now has.

                        %s""", String.join("\n", unreadable))
                .isEmpty();
        return mapping;
    }

    // A line of prose is not a binding (HD-326): a javadoc that quotes the shape this walk looks
    // for - `@Value("${seed.admin.email:}")`, which SeedAdminProperties writes to explain what it
    // replaced - was read as a live binding and put into the mapping. Measured on the first run
    // after that javadoc was written: `1 from @Value` in a tree whose last real @Value had just
    // been deleted. That phantom was harmless ONLY BY LUCK (it named the right variable); one
    // quoting a DIFFERENT property would have entered the union through putIfAbsent and excused a
    // refusal that names no variable at all. The judgement is POSITIONAL -
    // SourceCallSites.isCommentedAt, shared with the root scan above - because the first version
    // read the line's leading characters, and `foo(); /* @Value("${x}") */` starts with code.

    /**
     * The files an operator edits: anything named {@code .env*} or {@code docker-compose*.yml} that
     * this repository <strong>ships</strong>. The population is still derived, never listed - but it
     * is derived from {@code git ls-files}, not from a directory walk, and that difference is the
     * whole point of this method.
     *
     * <p><strong>Why tracked files and not a glob</strong> (HD-315 tenancy gate). A walk of the
     * working directory matches whatever happens to be lying in it, and a developer's own
     * {@code .env} is exactly the kind of thing that lies in it - measured: a synthetic tree picked
     * up {@code .env}, {@code .env.local} and {@code deploy/dc/.env}, and an untracked
     * {@code .env.hd315probe} planted at the root took this search from 9 files to 10. Those files
     * feed the one exclusion in this test, so a property could be an offence on a developer's
     * machine and not in CI, or the reverse: the build's verdict would depend on the machine that
     * ran it. A verdict may only be decided by inputs the repository itself carries. Note that a
     * real {@code .env} is also {@code .gitignore}d, so the divergence would not even show up in
     * {@code git status} - it is invisible from both ends.
     *
     * <p>Build output and {@code node_modules} need no exclusion any more: git does not track them,
     * so the filter that used to skip them is gone rather than kept as decoration.
     *
     * <p><strong>Both ways this can lose its population fail loudly.</strong> No git, a working
     * directory that is not a checkout, a git that errors, and an index that names nothing are each
     * refused by {@link PublishedCredentials#trackedFiles()} with the working directory quoted -
     * never an empty list quietly searched; and a tracked operator file that is missing from disk
     * fails here rather than being skipped. A seal that silently loses the population it checks
     * against is the failure this whole ticket is about, so neither end of this method may commit it.
     *
     * <p><strong>Through {@code PublishedCredentials} and not a {@code ProcessBuilder} of its own.</strong>
     * That class states, and HD-200/HD-304 depend on, its being this repository's <em>only</em> git
     * invocation, so a checkout that cannot answer produces one diagnosis instead of two that drift;
     * its {@code runGit} also drains stderr on a second thread, which a merged or unread stream gets
     * wrong by deadlocking the child. A private copy here would have been a second of both.
     */
    private static Map<String, String> operatorFiles() {
        var found = new TreeMap<String, String>();
        var missing = new ArrayList<String>();
        for (Path tracked : PublishedCredentials.trackedFiles()) {
            String path = PublishedCredentials.repositoryPath(tracked);
            String name = tracked.getFileName().toString();
            if (!name.startsWith(".env") && !(name.startsWith("docker-compose") && name.endsWith(".yml"))) {
                continue;
            }
            if (!Files.isRegularFile(tracked)) {
                missing.add(path);
                continue;
            }
            try {
                found.put(path, Files.readString(tracked, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(missing)
                .withFailMessage("""
                        AN OPERATOR-FACING FILE IS TRACKED BUT ABSENT FROM THE WORKING TREE, so the
                        exclusion in this test - "this property has no variable, therefore no operator
                        can set it" - is being decided against a smaller set of files than the
                        repository ships, and a property whose variable lives in one of these would be
                        excused rather than checked.

                        %s

                        Restore the file, or - if it was deliberately deleted - commit the deletion so
                        `git ls-files` stops naming it. Do not make this method skip a missing file.""",
                        String.join("\n", missing))
                .isEmpty();
        return found;
    }

    /** {@code indexOf} with word boundaries, so {@code MAIL_HOST} does not match {@code MAIL_HOSTS}. */
    private static boolean containsToken(String haystack, String token) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(token, from);
            if (at < 0) {
                return false;
            }
            boolean leftClear = at == 0 || !isTokenChar(haystack.charAt(at - 1));
            int after = at + token.length();
            boolean rightClear = after >= haystack.length() || !isTokenChar(haystack.charAt(after));
            if (leftClear && rightClear) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** One walk of the production tree, filtered to the kind the caller's rule is about. */
    private static List<Message> messagesOfKind(Kind kind) {
        return refusalMessages().stream().filter(message -> message.kind() == kind).toList();
    }

    private static List<Message> refusalMessages() {
        var found = new ArrayList<Message>();
        try (Stream<Path> files = Files.walk(JAVA_ROOT)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                found.addAll(messagesIn(file.toString().replace('\\', '/'), source));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    /**
     * Merges each maximal run of concatenated literals, keeping the runs that sit in a
     * message-carrying context &mdash; a refusal or a log line, tagged with which.
     */
    private static List<Message> messagesIn(String file, String source) {
        var messages = new ArrayList<Message>();
        List<Literal> literals = literalsIn(source);
        Map<String, String> constants = stringConstantsIn(source, literals);
        Map<String, String> helpers = refusalHelperBodies(source, literals, constants);
        int i = 0;
        while (i < literals.size()) {
            int runStart = i;
            var joined = new StringBuilder(literals.get(i).text());
            while (i + 1 < literals.size()
                   && isConcatenation(source.substring(literals.get(i).end(), literals.get(i + 1).start()))) {
                joined.append(' ').append(literals.get(i + 1).text());
                i++;
            }
            int at = literals.get(runStart).start();
            int runEnd = literals.get(i).end();
            String before = source.substring(Math.max(0, at - 90), at);
            Matcher refusal = REFUSAL_CONTEXT.matcher(before);
            Matcher logLine = LOG_CONTEXT.matcher(before);
            Kind kind = refusal.find() ? Kind.REFUSAL : logLine.find() ? Kind.LOG : null;
            if (kind != null) {
                int spanStart = Math.max(0, at - 90)
                                + (kind == Kind.REFUSAL ? refusal.start() : logLine.start());
                // Same-file constants named inside the message's own expression carry the property
                // and the variable at sites that hold neither as a literal.
                appendReferenced(joined, source.substring(spanStart, runEnd), constants);
                // ... and one hop of helper, so `throw new IllegalStateException(refusal("..."))`
                // is read with the sentence `refusal` actually assembles.
                Matcher call = REFUSAL_HELPER_CALL.matcher(before);
                if (kind == Kind.REFUSAL && call.find()) {
                    String body = helpers.get(call.group(1));
                    if (body != null) {
                        joined.append(' ').append(body);
                    }
                }
                int line = (int) source.substring(0, at).lines().count();
                messages.add(new Message(file, line, joined.toString(), kind,
                        argumentTailOf(source, runEnd)));
            }
            i++;
        }
        return messages;
    }

    /**
     * The rest of the call after a message's last literal, up to the next {@code ;}. A log line
     * carries its values as {@code {}} arguments, and a variable NAME handed over the same way
     * ({@code log.warn("{} is empty", "SEED_ADMIN_PASSWORD")}) would satisfy every check in this
     * class while the text an operator reads names no variable at all. This is the span that makes
     * that shape visible; the bound keeps the input short, as everything else here does.
     */
    private static String argumentTailOf(String source, int runEnd) {
        int limit = Math.min(source.length(), runEnd + 400);
        int semicolon = source.indexOf(';', runEnd);
        if (semicolon >= 0) {
            limit = Math.min(limit, semicolon);
        }
        return source.substring(Math.min(runEnd, limit), limit);
    }

    /** A quoted SCREAMING_SNAKE argument &mdash; a variable name handed over as a value. */
    private static final Pattern VARIABLE_AS_ARGUMENT = Pattern.compile("\"([A-Z][A-Z0-9_]{2,})\"");

    /**
     * The verdict over one population of messages, shared by both rules in this class rather than
     * written twice: the same mapping, the same exclusion, the same reasons. The {@code members}
     * list is keyed on (site, property) &mdash; the unit each rule's granularity control asserts.
     */
    private record Audit(List<String> offences, Set<String> checked, Map<String, String> excluded,
                         int namingNoProperty, List<String> members, Map<String, Integer> perFile,
                         Map<String, Integer> perSite) { }

    /**
     * For every message that names a configuration property, does it also name the variable an
     * operator sets? One body, two callers &mdash; the refusals and the log lines &mdash; because
     * the question, the mapping and the one exclusion are identical and a second copy of them is
     * how this test's two halves would drift apart.
     */
    private static Audit audit(List<Message> messages, Map<String, String> propertyToVariable,
                               Map<String, String> operatorFiles) {
        var offences = new ArrayList<String>();
        var checked = new TreeSet<String>();
        var excluded = new TreeMap<String, String>();
        var members = new ArrayList<String>();
        var perFile = new TreeMap<String, Integer>();
        var perSite = new TreeMap<String, Integer>();
        var namingNoProperty = 0;

        for (Message message : messages) {
            Set<String> named = propertiesNamedIn(message.text());
            if (named.isEmpty()) {
                namingNoProperty++;
                continue;
            }
            for (String property : named) {
                members.add("  " + message.site() + " names `" + property + "`");
                perFile.merge(message.file(), 1, Integer::sum);
                perSite.merge(message.site(), 1, Integer::sum);
                String variable = propertyToVariable.get(property);
                if (variable == null) {
                    String candidate = relaxedVariableName(property);
                    String settableIn = operatorFiles.entrySet().stream()
                            .filter(file -> containsToken(file.getValue(), candidate))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse(null);
                    if (settableIn == null) {
                        excluded.put(property, "no file an operator edits mentions " + candidate);
                    } else {
                        offences.add(("  %s names `%s`, which this test derived no variable for - "
                                      + "but %s sets `%s`, so an operator DOES have it. The mapping is "
                                      + "blind to this property, which excuses the message rather than "
                                      + "checking it.")
                                .formatted(message.site(), property, settableIn, candidate));
                    }
                    continue;
                }
                checked.add(property);
                if (!message.text().contains(variable)) {
                    offences.add("  %s names `%s` but never `%s`"
                            .formatted(message.site(), property, variable));
                }
                Matcher asArgument = VARIABLE_AS_ARGUMENT.matcher(message.argumentTail());
                if (asArgument.find()) {
                    offences.add(("  %s names `%s` and passes `\"%s\"` as an ARGUMENT - this test reads "
                                  + "the literal text and not the arguments, so write the variable into "
                                  + "the text and keep the arguments for values that vary")
                            .formatted(message.site(), property, asArgument.group(1)));
                }
            }
        }
        return new Audit(offences, checked, excluded, namingNoProperty, members, perFile, perSite);
    }

    private static void appendReferenced(StringBuilder joined, String span, Map<String, String> constants) {
        Matcher reference = CONSTANT_REFERENCE.matcher(span);
        while (reference.find()) {
            String value = constants.get(reference.group(1));
            if (value != null) {
                joined.append(' ').append(value);
            }
        }
    }

    /** {@code static final String NAME = "..." + "...";} declared in this file. */
    private static Map<String, String> stringConstantsIn(String source, List<Literal> literals) {
        var constants = new LinkedHashMap<String, String>();
        for (int i = 0; i < literals.size(); i++) {
            int at = literals.get(i).start();
            String before = source.substring(Math.max(0, at - 120), at);
            Matcher declaration = CONSTANT_DECLARATION.matcher(before);
            if (!declaration.find()) {
                continue;
            }
            var joined = new StringBuilder(literals.get(i).text());
            int j = i;
            while (j + 1 < literals.size()
                   && isConcatenation(source.substring(literals.get(j).end(), literals.get(j + 1).start()))) {
                joined.append(' ').append(literals.get(j + 1).text());
                j++;
            }
            constants.put(declaration.group(1), joined.toString());
        }
        return constants;
    }

    /**
     * For every method called directly inside a {@code throw new Illegal…Exception(…)} in this file,
     * the text of every literal in its body plus the value of every same-file constant it names. One
     * hop only, and the boundary is stated in this class's javadoc.
     */
    private static Map<String, String> refusalHelperBodies(
            String source, List<Literal> literals, Map<String, String> constants) {
        var bodies = new LinkedHashMap<String, String>();
        Matcher call = REFUSAL_HELPER_CALL.matcher(source);
        while (call.find()) {
            String name = call.group(1);
            if (bodies.containsKey(name)) {
                continue;
            }
            int[] body = bodySpanOf(source, name);
            if (body == null) {
                continue;
            }
            var joined = new StringBuilder();
            for (Literal literal : literals) {
                if (literal.start() >= body[0] && literal.end() <= body[1]) {
                    joined.append(' ').append(literal.text());
                }
            }
            appendReferenced(joined, source.substring(body[0], body[1]), constants);
            bodies.put(name, joined.toString());
        }
        return bodies;
    }

    /** {@code [openBrace, closeBrace)} of {@code String name(...) { … }}, or null if not found here. */
    private static int[] bodySpanOf(String source, String name) {
        Matcher declaration = Pattern.compile("\\bString\\s+" + Pattern.quote(name) + "\\s*\\(").matcher(source);
        if (!declaration.find()) {
            return null;
        }
        int open = source.indexOf('{', declaration.end());
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'' || (c == '/' && i + 1 < source.length()
                                          && (source.charAt(i + 1) == '/' || source.charAt(i + 1) == '*'))) {
                i = skipNonCode(source, i) - 1;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return new int[] {open, i};
            }
        }
        return null;
    }

    /** Character scan: skips comments, returns every string literal including text blocks. */
    private static List<Literal> literalsIn(String source) {
        var literals = new ArrayList<Literal>();
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"' && !source.startsWith("\"\"\"", i)) {
                int start = i++;
                var text = new StringBuilder();
                while (i < n && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        text.append(source.charAt(i + 1));
                        i += 2;
                    } else {
                        text.append(source.charAt(i));
                        i++;
                    }
                }
                i++;
                literals.add(new Literal(start, i, text.toString()));
            } else if (c == '"' || c == '\'' || (c == '/' && i + 1 < n
                                                 && (source.charAt(i + 1) == '/' || source.charAt(i + 1) == '*'))) {
                int start = i;
                int end = skipNonCode(source, i);
                // A text block IS a message in this codebase's newer style, so it is read rather
                // than skipped - the previous version jumped over `"""` entirely, which would have
                // let any refusal written as one walk straight past this test.
                if (source.startsWith("\"\"\"", start)) {
                    literals.add(new Literal(start, end,
                            source.substring(start + 3, Math.max(start + 3, end - 3))));
                }
                i = end;
            } else {
                i++;
            }
        }
        return literals;
    }

    /**
     * Index just past the comment, char literal or text block starting at {@code i}. Shared by the
     * literal scan and the brace matcher so the two cannot disagree about what is code.
     */
    private static int skipNonCode(String source, int i) {
        int n = source.length();
        if (source.startsWith("//", i)) {
            int end = source.indexOf('\n', i);
            return end < 0 ? n : end + 1;
        }
        if (source.startsWith("/*", i)) {
            int end = source.indexOf("*/", i + 2);
            return end < 0 ? n : end + 2;
        }
        if (source.startsWith("\"\"\"", i)) {
            int end = source.indexOf("\"\"\"", i + 3);
            return end < 0 ? n : end + 3;
        }
        if (source.charAt(i) == '\'') {
            int j = i + 1;
            while (j < n && source.charAt(j) != '\'') {
                j += source.charAt(j) == '\\' ? 2 : 1;
            }
            return Math.min(n, j + 1);
        }
        if (source.charAt(i) == '"') {
            int j = i + 1;
            while (j < n && source.charAt(j) != '"') {
                j += source.charAt(j) == '\\' ? 2 : 1;
            }
            return Math.min(n, j + 1);
        }
        return i + 1;
    }

    private static Set<String> propertiesNamedIn(String text) {
        var named = new LinkedHashSet<String>();
        Matcher m = PROPERTY.matcher(text);
        while (m.find()) {
            named.add(m.group(1));
        }
        return named;
    }
}
