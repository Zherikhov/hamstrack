package com.hamstrack.common.testsupport;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fails the build when a test run executed fewer test classes than the tree contains, and names the
 * ones it never reached (HD-265).
 *
 * <p><strong>What went wrong.</strong> An unfiltered {@code mvn test} printed
 * {@code Tests run: 1463, Failures: 0, Errors: 0, Skipped: 0} and {@code BUILD SUCCESS} while
 * executing a strict subset of the tree: a contiguous alphabetical prefix — whole packages — was
 * never selected, and the run said nothing at all about it. The author had just written a class
 * inside that gap, so the green run was evidence about everything except the thing being verified.
 * It was caught only because the total looked low against a remembered figure, which is not a
 * mechanism. <em>An unfiltered run is not evidence either, unless the class count is checked</em> —
 * this checks it.
 *
 * <p><strong>The cause is not known and nothing here claims one.</strong> Ruled out on the tree as
 * it stood: a partial incremental {@code test-compile} (touching a source inside the absent range
 * recompiled the tree whole, and the compiled class count did not move), a build-cache extension or
 * {@code .mvn/maven.config} (neither exists here), and any {@code <includes>}/{@code <excludes>}
 * narrowing in the POM (Surefire runs on its defaults). The state has not been reproduced since. A
 * fix that cannot explain the alphabetical prefix has not found the cause, so what ships is the
 * detector — if it fires, the run that fired it is the reproduction, and the report separates
 * "never compiled" from "compiled but never selected", which is the first fork in the road the next
 * time it happens.
 *
 * <p><strong>Where the assertion lives, and why not somewhere easier.</strong> A guard written as a
 * test class is selected by the very mechanism that failed: a dropped prefix can drop the guard, and
 * it is then silent exactly when it is needed. A JUnit {@code TestExecutionListener} is immune to
 * test selection but cannot fail a build — the Launcher swallows what its callbacks throw, and
 * {@code System.exit} from inside a fork surfaces as "the forked VM terminated without properly
 * saying goodbye", which sends the reader to an unrelated question. So detection and refusal are
 * split: {@link ExecutedTestClassRecorder} records what ran, and this runs as a Maven-bound step
 * after Surefire, where no test selection can reach it. Its own absence from
 * {@code target/test-classes} fails the build as well, for the same reason.
 *
 * <p><strong>The expected set is derived, never pinned.</strong> It is the test-named sources under
 * {@code src/test/java}, so adding a test class cannot make this red on its own and no number
 * anywhere has to be maintained. Sources rather than compiled classes: if a truncated compile is
 * ever the cause, a set derived from {@code target/test-classes} would be truncated in the same
 * stroke and the guard would agree with the bug. The compiled tree is read too, but only to annotate
 * the report.
 *
 * <p><strong>The expected set is a question about NAMES, and deliberately not about annotations.</strong>
 * A class carrying a test-shaped name that Jupiter then declines to run is reported absent, and the
 * remedy is to rename it — this file's own {@link SuiteRunRecord} helper was called
 * {@code TestRunRecord} for about an hour, which matched Surefire's {@code Test*} include and made
 * the guard demand a class with no tests in it. Skipping such classes by scanning for {@code @Test}
 * would trade that loud, one-line fix for a silent one: a class whose test methods are all inherited
 * from a {@code …Base} declares no annotation of its own, so an annotation filter quietly shrinks
 * the expected set — a smaller, better-hidden copy of the bug this exists to catch. Loud and wrong
 * about a helper beats quiet and wrong about a test.
 *
 * <p><strong>It stays out of the way of every run that is narrower on purpose.</strong>
 * {@code -Dtest=…}, {@code -Dgroups=…}, {@code -DskipTests}, {@code -Dmaven.test.skip} and their
 * kind all mean the plan is not supposed to match the tree, and each is handed in from the POM.
 * Maven leaves an undefined property literal, so an argument still reading {@code ${test}} is how
 * "the developer asked for no filter" arrives here; blank says the same thing, and both read as
 * unset. An IDE builds its own command line and never runs a Maven phase, so it is untouched.
 */
public final class SuiteCoverageGuard {

    /**
     * Surefire's default {@code <includes>}, which this POM does not override. Getting this set
     * wrong under-counts the tree, i.e. builds a smaller copy of the bug being guarded against —
     * note that {@code HamstrackApplicationTests} matches only the third of them.
     */
    private static final List<Pattern> TEST_NAME_PATTERNS = List.of(
            Pattern.compile("Test.*"),
            Pattern.compile(".*Test"),
            Pattern.compile(".*Tests"),
            Pattern.compile(".*TestCase"));

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /**
     * How many absent classes to print before the rest spill to a file. A bare delta sends the
     * reader to the wrong question ("28 fewer than what?"), so the names are the message.
     */
    private static final int MAX_LISTED = 40;

    private SuiteCoverageGuard() {
    }

    public static void main(String[] args) throws IOException {
        var arguments = parse(args);
        var testSources = Path.of(required(arguments, "testSources"));
        var testClasses = Path.of(required(arguments, "testClasses"));
        var buildDirectory = Path.of(required(arguments, "buildDirectory"));
        var runId = arguments.get("runId");

        var narrowing = firstNarrowingSwitch(arguments);
        if (narrowing != null) {
            System.out.println("[test-tree] coverage check disarmed by " + narrowing
                               + " -- this run asked for a narrower plan than the tree.");
            prune(SuiteRunRecord.directory(buildDirectory));
            return;
        }

        var expected = scanSources(testSources);
        if (expected.isEmpty()) {
            fail("[test-tree] no test-named sources found under " + testSources.toAbsolutePath()
                 + " -- the expected set could not be derived, so this run proves nothing."
                 + " A guard that cannot see the tree must not pass it.");
        }

        var recordDirectory = SuiteRunRecord.directory(buildDirectory);
        var records = recordsOfThisRun(recordDirectory, runId);
        if (records.isEmpty()) {
            fail("[test-tree] no execution record for this run under " + recordDirectory.toAbsolutePath()
                 + " -- the suite ran and nothing was written down."
                 + System.lineSeparator()
                 + "  Expected a file named " + SuiteRunRecord.fileNamePrefix(runId) + "<pid>.txt."
                 + System.lineSeparator()
                 // Named as text, not as ExecutedTestClassRecorder.class.getName(): that expression
                 // loads the listener, whose interface is JUnit's, and this JVM's classpath is
                 // target/test-classes and nothing else -- the message would die of
                 // NoClassDefFoundError instead of being read. The service file is the thing to go
                 // and look at anyway, and naming it rather than the class keeps this true across a
                 // rename of either.
                 + "  The recorder is registered by ServiceLoader from src/test/resources/META-INF/"
                 + "services/org.junit.platform.launcher.TestExecutionListener. If that file is gone,"
                 + " or the listener it names no longer writes, the suite still runs and passes and"
                 + " nothing knows how much of it ran.");
        }

        var executed = read(records);
        var compiled = scanCompiled(testClasses);

        var missing = new TreeSet<>(expected.keySet());
        missing.removeAll(executed);
        if (!missing.isEmpty()) {
            fail(report(missing, expected, compiled, testSources, buildDirectory));
        }

        // Printed on the happy path too, and on purpose: the count is the thing a reader was
        // supposed to notice and did not, so it is stated rather than left to be remembered.
        System.out.println("[test-tree] all " + expected.size() + " test classes under " + testSources
                           + " executed in this run.");
        var strays = new TreeSet<>(executed);
        strays.removeAll(expected.keySet());
        if (!strays.isEmpty()) {
            System.out.println("[test-tree] note: " + strays.size() + " class(es) ran with no matching"
                               + " source -- stale output under " + testClasses + " left by a deleted"
                               + " or renamed test: " + String.join(", ", strays));
        }
        prune(recordDirectory);
    }

    // ----------------------------------------------------------------- arming

    /**
     * The switch that makes a short plan intentional, or {@code null} when the run asked for
     * everything. Phrased over the category — anything that narrows what Surefire selects — because
     * the next such property belongs on this list, not in a second mechanism somewhere else.
     */
    static String firstNarrowingSwitch(Map<String, String> arguments) {
        for (var key : List.of("test", "groups", "excludedGroups", "skipTests", "maven.test.skip",
                "maven.test.skip.exec")) {
            var value = arguments.get(key);
            if (value != null && !value.equalsIgnoreCase("false")) {
                return "-D" + key + "=" + value;
            }
        }
        return null;
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (var arg : args) {
            var split = arg.indexOf('=');
            if (split < 0) {
                continue;
            }
            var value = arg.substring(split + 1).trim();
            // An undefined Maven property arrives with its own placeholder intact; that spelling and
            // the empty one both mean the developer did not pass this switch.
            if (value.isEmpty() || value.startsWith("${")) {
                continue;
            }
            parsed.put(arg.substring(0, split), value);
        }
        return parsed;
    }

    private static String required(Map<String, String> arguments, String key) {
        var value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "[test-tree] the POM did not pass " + key + " to the coverage guard");
        }
        return value;
    }

    // ------------------------------------------------------------- the sets

    /** Fully-qualified name to source file, for every source Surefire would look at. */
    static Map<String, Path> scanSources(Path testSources) throws IOException {
        Map<String, Path> found = new LinkedHashMap<>();
        if (!Files.isDirectory(testSources)) {
            return found;
        }
        try (Stream<Path> paths = Files.walk(testSources)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(path -> {
                        var simpleName = fileNameWithout(path, ".java");
                        if (!matchesTestName(simpleName)) {
                            return;
                        }
                        String source;
                        try {
                            source = Files.readString(path, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        if (cannotBeRun(source, simpleName)) {
                            return;
                        }
                        var matcher = PACKAGE_DECLARATION.matcher(source);
                        var packageName = matcher.find() ? matcher.group(1) : "";
                        found.put(packageName.isEmpty() ? simpleName : packageName + "." + simpleName,
                                path);
                    });
        }
        return found;
    }

    /**
     * True only for a declaration Surefire provably cannot execute — an abstract class, or an
     * interface wearing a test-shaped name. Anything this cannot read is expected to run: a guard in
     * doubt asks for the class, it does not excuse it.
     */
    static boolean cannotBeRun(String source, String simpleName) {
        var declaration = Pattern.compile("(?m)^(.*)\\b(class|interface|@interface|enum|record)\\s+"
                                          + Pattern.quote(simpleName) + "\\b").matcher(source);
        if (!declaration.find()) {
            return false;
        }
        return declaration.group(2).endsWith("interface") || declaration.group(1).contains("abstract");
    }

    /** Top-level test-named classes in the compiled output — for the report, never for the bound. */
    private static Set<String> scanCompiled(Path testClasses) throws IOException {
        Set<String> found = new TreeSet<>();
        if (!Files.isDirectory(testClasses)) {
            return found;
        }
        try (Stream<Path> paths = Files.walk(testClasses)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .forEach(path -> {
                        var simpleName = fileNameWithout(path, ".class");
                        if (simpleName.indexOf('$') >= 0 || !matchesTestName(simpleName)) {
                            return;
                        }
                        var parent = testClasses.relativize(path).getParent();
                        var packageName = parent == null ? ""
                                : parent.toString().replace(File.separatorChar, '.').replace('/', '.');
                        found.add(packageName.isEmpty() ? simpleName : packageName + "." + simpleName);
                    });
        }
        return found;
    }

    private static String fileNameWithout(Path path, String extension) {
        var name = path.getFileName().toString();
        return name.substring(0, name.length() - extension.length());
    }

    static boolean matchesTestName(String simpleName) {
        return TEST_NAME_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(simpleName).matches());
    }

    // ---------------------------------------------------------------- records

    private static List<Path> recordsOfThisRun(Path directory, String runId) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        var prefix = SuiteRunRecord.fileNamePrefix(runId);
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith(prefix)).sorted().toList();
        }
    }

    private static Set<String> read(List<Path> records) throws IOException {
        Set<String> executed = new TreeSet<>();
        for (var record : records) {
            for (var line : Files.readAllLines(record, StandardCharsets.UTF_8)) {
                var trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    executed.add(trimmed);
                }
            }
        }
        return executed;
    }

    /**
     * A record is evidence about one invocation; keeping them past a clean run is how
     * {@code target/surefire-reports} became a pile that outlived the classes it names. A red run
     * keeps its own, because that one is being read.
     *
     * <p>It sweeps every record, not only this run's, so that a filtered or IDE run cannot leave one
     * behind for ever. Two Maven builds sharing a single {@code target/} would tread on each other
     * here — and already do, over {@code surefire-reports}, {@code test-classes} and
     * {@code target/antrun}; this adds no case that was previously safe.
     */
    private static void prune(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            for (var path : paths.toList()) {
                if (SuiteRunRecord.isRecordFile(path.getFileName().toString())) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    // ----------------------------------------------------------------- report

    private static String report(Set<String> missing, Map<String, Path> expected, Set<String> compiled,
                                 Path testSources, Path buildDirectory) throws IOException {
        var line = System.lineSeparator();
        var message = new StringBuilder();
        message.append("[test-tree] INCOMPLETE RUN: ").append(missing.size()).append(" of ")
                .append(expected.size()).append(" test classes under ").append(testSources)
                .append(" were never executed.").append(line)
                .append("The suite result above is evidence about the other ")
                .append(expected.size() - missing.size())
                .append(" and says nothing about these:").append(line);

        var listed = 0;
        for (var className : missing) {
            if (listed++ == MAX_LISTED) {
                break;
            }
            message.append("  ").append(className)
                    .append(compiled.contains(className) ? "   [compiled, never selected]"
                            : "   [never compiled]")
                    .append(line);
        }
        if (missing.size() > MAX_LISTED) {
            var spill = buildDirectory.resolve("missing-test-classes.txt");
            Files.write(spill, missing, StandardCharsets.UTF_8);
            message.append("  ... and ").append(missing.size() - MAX_LISTED)
                    .append(" more, not listed here; all of them are in ").append(spill).append(line);
        }
        message.append(line)
                .append("The bracket is the first fork in the road: [never compiled] puts it before")
                .append(" test-compile finished, [compiled, never selected] puts it in Surefire's scan.")
                .append(line)
                .append("Re-run the suite. If the same classes are absent twice the tree really did")
                .append(" change; if they come back on a re-run, HD-265's truncation has been")
                .append(" reproduced and the cause is still open -- keep this output, the Surefire log")
                .append(" and ").append(SuiteRunRecord.directory(buildDirectory)).append(".").append(line)
                .append("This bound is derived from the tree, so it cannot be satisfied by editing a")
                .append(" number; -Dtest=... and -DskipTests switch it off honestly.");
        return message.toString();
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
