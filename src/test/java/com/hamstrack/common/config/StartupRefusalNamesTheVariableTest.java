package com.hamstrack.common.config;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *       ({@link #relaxedVariableName}). These properties have no line in any properties file at all
 *       &mdash; the compose files pass {@code SEED_ADMIN_EMAIL} straight into the container's
 *       environment &mdash; so a mapping built from placeholders alone cannot see them. It could not
 *       see the three {@code seed.admin.*} properties, which is how the boot refusal at
 *       {@code DataSeeder.run} named {@code seed.admin.email} twice and {@code SEED_ADMIN_EMAIL}
 *       never while this test stayed green (HD-315 review). That the relaxed name is the one Spring
 *       resolves is not assumed here: {@link #theRelaxedVariableNameIsTheOneSpringActuallyResolves}
 *       asks the real {@code SystemEnvironmentPropertySource}.</li>
 * </ul>
 * A hand-kept list would have to be maintained alongside both and would go stale in exactly the
 * direction nothing notices: a property that gains a variable, or a variable that is renamed.
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
 * <h2>What counts as a refusal message</h2>
 * A run of string literals (text blocks included) joined by {@code +} &mdash; constants and
 * expressions between them are permitted, so {@code "a " + FLOOR + " b"} is one message &mdash;
 * whose first literal is the argument of a thrown {@code IllegalStateException} /
 * {@code IllegalArgumentException}, or the value of a bean-validation {@code message = }. Two things
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
 * Five, because this test can go vacuous in five independent ways: the placeholder mapping could
 * stop being derived, the {@code @Value} mapping could stop being derived, the walk could stop
 * finding sources, the filter could stop matching any property, and the operator-file search backing
 * the exclusion could stop finding files. Each is asserted with a number below what the tree holds
 * today, so ordinary growth never trips them and a collapse always does.
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
     * The contexts that make a literal run a refusal rather than prose: the argument of a thrown
     * {@code Illegal…Exception} or a bean-validation {@code message = }, optionally through one
     * message-assembling helper call, and optionally after a constant or two that the message starts
     * with ({@code throw new IllegalStateException(PROPERTY + " (" + VAR + ") …")}).
     *
     * <p>The constant prefix repeats at most three times <em>on purpose</em>. An unbounded
     * {@code (…)*} of a group that itself contains quantifiers is the nested shape that overflowed
     * this test's stack twice before; the input here is the 90 characters before a literal, so three
     * is past anything this codebase writes and the repetition can never run away.
     */
    private static final Pattern REFUSAL_CONTEXT = Pattern.compile(
            "(?:throw\\s+new\\s+Illegal(?:State|Argument)Exception\\s*\\(|message\\s*=\\s*)"
            + "\\s*(?:[a-z][A-Za-z0-9_]*\\s*\\(\\s*)?(?:[A-Z][A-Z0-9_]{2,}\\s*\\+\\s*){0,3}$");

    /**
     * String literals are found by a character scan, not by a pattern. The natural expression
     * ({@code "((?:[^"\\]|\\.)*)"}) recurses once per matched character in Java's engine, and this
     * codebase's messages are long enough to exhaust the stack &mdash; measured as a second
     * {@code StackOverflowError} from this test, after the first one was fixed elsewhere. Two
     * different patterns failing the same way is the reason there is no third.
     */
    private record Literal(int start, int end, String text) { }

    private record Message(String file, int line, String text) { }

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
     * {@link #theRelaxedVariableNameIsTheOneSpringActuallyResolves} rather than remembered.
     */
    private static String relaxedVariableName(String property) {
        return property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    @Test
    void everyStartupRefusalThatNamesAPropertyAlsoNamesTheVariableAnOperatorSets() {
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

        Map<String, String> fromValueBindings = valueBindingMapping();
        assertThat(fromValueBindings)
                .withFailMessage("""
                        NO `@Value("${...}")` BINDING WAS FOUND under %s, so this test is blind to
                        every property that reaches the application through the environment WITHOUT a
                        line in any properties file. That blindness is not hypothetical: it is why
                        `seed.admin.email` was exempt from its own category while a boot refusal named
                        it twice and SEED_ADMIN_EMAIL never.

                        If those bindings genuinely moved (to a @ConfigurationProperties class, say),
                        teach this test where they live now - do not delete the assertion.""", JAVA_ROOT)
                .hasSizeGreaterThanOrEqualTo(3);

        var propertyToVariable = new LinkedHashMap<>(fromPlaceholders);
        fromValueBindings.forEach(propertyToVariable::putIfAbsent);

        List<Message> messages = refusalMessages();
        assertThat(messages)
                .withFailMessage("""
                        NO REFUSAL MESSAGE WAS FOUND under %s, so this test is checking nothing.

                        A refusal is a run of string literals thrown as IllegalStateException /
                        IllegalArgumentException, or given as a bean-validation `message = `. If the
                        fail-fast sites moved or changed shape, point this test at them.""", JAVA_ROOT)
                .hasSizeGreaterThanOrEqualTo(55);

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

        var offences = new ArrayList<String>();
        var checked = new TreeSet<String>();
        // The exclusion set, and the evidence for each entry. Printed below whatever the outcome.
        var excluded = new TreeMap<String, String>();
        var namingNoProperty = 0;

        for (Message message : messages) {
            Set<String> named = propertiesNamedIn(message.text());
            if (named.isEmpty()) {
                namingNoProperty++;
                continue;
            }
            for (String property : named) {
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
                        offences.add(("  %s:%d names `%s`, which this test derived no variable for - "
                                      + "but %s sets `%s`, so an operator DOES have it. The mapping is "
                                      + "blind to this property, which excuses the message rather than "
                                      + "checking it.")
                                .formatted(message.file(), message.line(), property, settableIn, candidate));
                    }
                    continue;
                }
                checked.add(property);
                if (!message.text().contains(variable)) {
                    offences.add("  %s:%d names `%s` but never `%s`"
                            .formatted(message.file(), message.line(), property, variable));
                }
            }
        }

        // Reported on every run, green or red. The javadoc used to promise this and two counters
        // delivered it to nobody: one was incremented and never read, the other was interpolated
        // only into an assertion that could not fire. A silent exclusion is how the seed refusal
        // stayed hidden inside the population that was supposed to contain it.
        System.out.println("[HD-315] " + messages.size() + " refusal message(s); "
                           + namingNoProperty + " name no property; "
                           + checked.size() + " operator-settable propert(ies) checked against "
                           + propertyToVariable.size() + " mapped ("
                           + fromPlaceholders.size() + " from placeholders, "
                           + fromValueBindings.size() + " from @Value); "
                           + operatorFiles.size() + " tracked operator file(s) searched.");
        // The NAMES, not just the count: a population that quietly gained an untracked `.env` or lost
        // a shipped template is the defect this print exists to make visible on a green run too.
        System.out.println("[HD-315] operator files: " + operatorFiles.keySet());
        System.out.println("[HD-315] excluded as not operator-settable (" + excluded.size() + "): "
                           + (excluded.isEmpty() ? "none" : ""));
        excluded.forEach((property, why) -> System.out.println("[HD-315]   " + property + " - " + why));
        System.out.println("[HD-315] checked: " + checked);

        assertThat(checked)
                .withFailMessage("""
                        NO OPERATOR-SETTABLE PROPERTY WAS CHECKED, so this test passed without
                        comparing anything. %d message(s) were read, %d of them named no property at
                        all, and %d property name(s) were excluded as unsettable: %s

                        Either the PROPERTY pattern stopped matching this codebase's naming, or the
                        mapping keys stopped agreeing with the names the messages use.""",
                        messages.size(), namingNoProperty, excluded.size(), excluded.keySet())
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

        assertThat(offences)
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
                        String.join("\n", offences))
                .isEmpty();
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

        String read = String.join("\n", messagesIn("Sample.java", source).stream().map(Message::text).toList());

        assertThat(read)
                .withFailMessage("""
                        THE REFUSAL SCANNER NO LONGER READS A SHAPE ITS JAVADOC CLAIMS IT READS, so
                        every production refusal written that way is exempt from this test while
                        looking covered. What it read from the sample source was:

                        %s""", read)
                .contains("app.sample.plain (SAMPLE_PLAIN)")
                .contains("app.sample.constant")
                .contains("SAMPLE_CONSTANT")
                .contains("app.sample.helper (SAMPLE_HELPER)")
                .contains("app.sample.block (SAMPLE_BLOCK)");

        assertThat(read)
                .withFailMessage("""
                        THE REFUSAL SCANNER READ SOMETHING THAT IS NOT A REFUSAL (a comment, or a log
                        line), which turns this test into a source of false accusations against
                        messages that are not its business. What it read was:

                        %s""", read)
                .doesNotContain("app.sample.comment")
                .doesNotContain("app.sample.log");
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
                    if (!line.contains("@Value(")) {
                        continue;
                    }
                    Matcher m = VALUE_PLACEHOLDER.matcher(line);
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

    /** Merges each maximal run of concatenated literals, keeping only runs in a refusal context. */
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
            String before = source.substring(Math.max(0, at - 90), at);
            Matcher context = REFUSAL_CONTEXT.matcher(before);
            if (context.find()) {
                int spanStart = Math.max(0, at - 90) + context.start();
                // Same-file constants named inside the message's own expression carry the property
                // and the variable at sites that hold neither as a literal.
                appendReferenced(joined, source.substring(spanStart, literals.get(i).end()), constants);
                // ... and one hop of helper, so `throw new IllegalStateException(refusal("..."))`
                // is read with the sentence `refusal` actually assembles.
                Matcher call = REFUSAL_HELPER_CALL.matcher(before);
                if (call.find()) {
                    String body = helpers.get(call.group(1));
                    if (body != null) {
                        joined.append(' ').append(body);
                    }
                }
                int line = (int) source.substring(0, at).lines().count();
                messages.add(new Message(file, line, joined.toString()));
            }
            i++;
        }
        return messages;
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
