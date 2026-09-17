package com.hamstrack.common.config;

import org.junit.jupiter.api.Test;

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
 * property, the remedy named as a variable, as in {@code DatabaseTimeoutConsistency.refusal} or
 * {@code StorageQuotaConsistency} &mdash; and it had the wrong shape in three. That is the ordinary
 * way a convention decays: it is carried by whoever remembers it. This test is the mechanism, so
 * the next refusal message is held by something other than review.
 *
 * <h2>Why the mapping is derived rather than listed</h2>
 * The property-to-variable mapping is not written down here. It is read out of
 * {@code src/main/resources/application*.properties}, where every operator-settable property is
 * bound as {@code some.property=${SOME_VARIABLE:default}} &mdash; the placeholder <em>is</em> the
 * mapping, and it is the same text Spring itself resolves. A hand-kept list would have to be
 * maintained alongside the properties files and would go stale in exactly the direction nothing
 * notices: a property that gains a variable, or a variable that is renamed. A property with no
 * {@code ${...}} placeholder is not operator-settable and is therefore not a member; those are
 * counted and reported rather than silently dropped, so a mapping that collapses is visible.
 *
 * <h2>What counts as a refusal message</h2>
 * A run of string literals joined by {@code +} (constants and variables between them are permitted,
 * so {@code "a " + FLOOR + " b"} is one message) whose first literal is the argument of a thrown
 * {@code IllegalStateException} / {@code IllegalArgumentException}, or the value of a bean-validation
 * {@code message = }. Javadoc cannot be a member by construction: this reads string literals, and
 * {@code {@code app.foo}} in a comment is not one. A message that names no property is not a member
 * either &mdash; the rule is about messages that have already chosen to name configuration.
 *
 * <h2>The floors</h2>
 * Three, because this test can go vacuous in three independent ways: the mapping could stop being
 * derived (no placeholders parsed), the walk could stop finding sources (no messages examined), and
 * the filter could stop matching (no properties checked). Each is asserted with a number below what
 * the tree holds today, so ordinary growth never trips them and a collapse always does.
 */
class StartupRefusalNamesTheVariableTest {

    private static final Path JAVA_ROOT = Path.of("src", "main", "java", "com", "hamstrack");
    private static final Path RESOURCES = Path.of("src", "main", "resources");

    /** {@code some.property=${SOME_VARIABLE:default}} &mdash; the placeholder is the mapping. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("^\\s*([A-Za-z0-9._-]+)\\s*=\\s*\\$\\{([A-Z0-9_]+)[:}]");

    /** A dotted, lower-case configuration property under one of this application's roots. */
    private static final Pattern PROPERTY =
            Pattern.compile("\\b((?:app|spring|seed|jwt)\\.[a-z0-9]+(?:[.-][a-z0-9]+)+)\\b");

    /**
     * String literals are found by a character scan, not by a pattern. The natural expression
     * ({@code "((?:[^"\\]|\\.)*)"}) recurses once per matched character in Java's engine, and this
     * codebase's messages are long enough to exhaust the stack &mdash; measured as a second
     * {@code StackOverflowError} from this test, after the first one was fixed elsewhere. Two
     * different patterns failing the same way is the reason there is no third.
     */
    private record Literal(int start, int end, String text) { }

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

    /** The contexts that make a literal run a refusal rather than prose. */
    private static final Pattern REFUSAL_CONTEXT = Pattern.compile(
            "(?:throw\\s+new\\s+Illegal(?:State|Argument)Exception\\s*\\(|message\\s*=\\s*)\\s*$");

    /**
     * A refusal that names a code constant &mdash; {@code MailThrottlePolicy.MAX_CEILING_WINDOW},
     * {@code AuthMailProperties.ANONYMOUS_EVENT_RETENTION} &mdash; is addressed to whoever can change
     * that constant, which is a developer holding the source, not an operator holding a {@code .env}.
     * Those messages mention an operator property while explaining <em>why</em> the invariant exists,
     * and demanding a variable name in them would be asking them to prescribe a remedy their reader
     * cannot perform. The exclusion is a property of the message, not a list of files, so a new
     * developer-facing invariant is covered on the day it is written.
     */
    private static final Pattern CODE_CONSTANT = Pattern.compile("\\b[A-Z][A-Za-z0-9]+\\.[A-Z][A-Z0-9_]{3,}\\b");

    private record Message(String file, int line, String text) { }

    @Test
    void everyStartupRefusalThatNamesAPropertyAlsoNamesTheVariableAnOperatorSets() {
        Map<String, String> propertyToVariable = derivedMapping();
        assertThat(propertyToVariable)
                .withFailMessage("""
                        NO PROPERTY-TO-VARIABLE MAPPING WAS DERIVED, so this test is checking nothing.

                        The mapping is read from `%s/application*.properties`, where an
                        operator-settable property is bound as `some.property=${SOME_VARIABLE:default}`.
                        If those files moved, changed shape, or stopped using placeholders, teach this
                        test where the mapping lives now - do not delete the assertion.""", RESOURCES)
                .hasSizeGreaterThanOrEqualTo(60);

        List<Message> messages = refusalMessages();
        assertThat(messages)
                .withFailMessage("""
                        NO REFUSAL MESSAGE WAS FOUND under %s, so this test is checking nothing.

                        A refusal is a run of string literals thrown as IllegalStateException /
                        IllegalArgumentException, or given as a bean-validation `message = `. If the
                        fail-fast sites moved or changed shape, point this test at them.""", JAVA_ROOT)
                .hasSizeGreaterThanOrEqualTo(12);

        var offences = new ArrayList<String>();
        var checked = new TreeSet<String>();
        var unsettable = new TreeSet<String>();

        var developerFacing = 0;
        for (Message message : messages) {
            Set<String> named = propertiesNamedIn(message.text());
            if (named.isEmpty()) {
                continue;
            }
            if (CODE_CONSTANT.matcher(message.text()).find()) {
                developerFacing++;
                continue;
            }
            for (String property : named) {
                String variable = propertyToVariable.get(property);
                if (variable == null) {
                    unsettable.add(property);
                    continue;
                }
                checked.add(property);
                if (!message.text().contains(variable)) {
                    offences.add("  %s:%d names `%s` but never `%s`"
                            .formatted(message.file(), message.line(), property, variable));
                }
            }
        }

        assertThat(checked)
                .withFailMessage("""
                        NO OPERATOR-SETTABLE PROPERTY WAS CHECKED, so this test passed without
                        comparing anything. %d message(s) were read and %d property name(s) were seen,
                        but none of them mapped to an environment variable. Either the PROPERTY pattern
                        stopped matching this codebase's naming, or the mapping keys stopped agreeing
                        with the names the messages use.""",
                        messages.size(), unsettable.size())
                .hasSizeGreaterThanOrEqualTo(10);

        assertThat(offences)
                .withFailMessage("""
                        A STARTUP REFUSAL NAMES A PROPERTY THE OPERATOR DOES NOT HAVE.

                        Spring reports the property, because that is the name binding failed on. The
                        operator set an environment variable, and a search for the property across
                        their own `.env` and compose file finds nothing. Name both: the property for
                        the fault, the variable for the remedy.

                        %s

                        The shape to copy is `DatabaseTimeoutConsistency.refusal` or
                        `StorageQuotaConsistency`, which already do this. The variable name is not
                        invented here - it is read from the `${...}` placeholder that binds the
                        property in src/main/resources/application*.properties.""",
                        String.join("\n", offences))
                .isEmpty();
    }

    private static Map<String, String> derivedMapping() {
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
            if (REFUSAL_CONTEXT.matcher(before).find()) {
                int line = (int) source.substring(0, at).lines().count();
                messages.add(new Message(file, line, joined.toString()));
            }
            i++;
        }
        return messages;
    }

    /** Character scan: skips comments and text blocks, returns every {@code "..."} literal. */
    private static List<Literal> literalsIn(String source) {
        var literals = new ArrayList<Literal>();
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i = source.indexOf("*/", i + 2);
                i = i < 0 ? n : i + 2;
            } else if (c == '\'') {
                i++;
                while (i < n && source.charAt(i) != '\'') {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                int close = source.indexOf("\"\"\"", i + 3);
                i = close < 0 ? n : close + 3;
            } else if (c == '"') {
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
            } else {
                i++;
            }
        }
        return literals;
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
