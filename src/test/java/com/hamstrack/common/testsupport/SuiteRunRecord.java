package com.hamstrack.common.testsupport;

import java.nio.file.Path;

/**
 * The naming convention for the per-run record of which test classes actually executed, shared by
 * the two halves of the HD-265 coverage guard: {@link ExecutedTestClassRecorder} writes it from
 * inside the test JVM, {@code SuiteCoverageGuard} reads it from a Maven-bound step afterwards.
 *
 * <p><strong>Why a record the run writes, and not {@code target/surefire-reports}.</strong> That
 * directory is cumulative and is never cleaned between runs: on the day HD-265 was filed it held
 * more {@code .txt} reports than the tree held test classes, two of them naming classes that had
 * been deleted weeks earlier. Anything derived from it counts history, not this run — and a guard
 * that counts history is a guard that reports a truncated run as complete, which is the exact
 * failure it exists to catch. Modification times are no better: they are a heuristic about when a
 * file was touched, not a statement about which invocation produced it.
 *
 * <p><strong>What makes a record fresh here is an identity, not a timestamp comparison.</strong>
 * Maven interpolates {@code ${maven.build.timestamp}} once per session and hands the same value to
 * both halves — to the test JVM as the {@value #RUN_ID_PROPERTY} system property, to the guard as an
 * argument. The reader accepts only files carrying the identity it was given, so a leftover from any
 * other invocation (an earlier Maven run, an IDE run, a crashed run) cannot be mistaken for this
 * one; it is ignored on sight rather than reasoned about.
 *
 * <p>The file name also carries the writing JVM's pid, so a build that ever raises Surefire's
 * {@code forkCount} keeps one file per fork and the reader unions them, rather than having the last
 * fork to finish truncate the evidence of the others.
 *
 * <p>This class deliberately depends on nothing but the JDK: the guard runs with only
 * {@code target/test-classes} on its classpath, so anything it touches must load without JUnit.
 *
 * <p><strong>The name is load-bearing in one dull way:</strong> nothing under {@code src/test/java}
 * may be called {@code Test…}, {@code …Test}, {@code …Tests} or {@code …TestCase} unless it really
 * is a test. Those are Surefire's default includes and therefore the guard's expected set; this
 * class was briefly {@code TestRunRecord} and the guard duly demanded that Surefire run a constants
 * holder. Support classes take a name outside the four patterns — the {@code …Base} helpers in this
 * tree are the established form.
 */
public final class SuiteRunRecord {

    /** System property carrying the Maven session identity into the test JVM. */
    public static final String RUN_ID_PROPERTY = "hamstrack.test-run.id";

    /** System property carrying {@code ${project.build.directory}} into the test JVM. */
    public static final String BUILD_DIRECTORY_PROPERTY = "hamstrack.test-run.build-dir";

    /** Directory name, under the build directory, holding the records. */
    public static final String DIRECTORY_NAME = "test-run-record";

    /** Identity used when no Maven session handed one over — an IDE run, or a bare Launcher. */
    public static final String NO_MAVEN_RUN = "outside-maven";

    private static final String PREFIX = "executed-";

    /**
     * The vitest half's prefix (HD-301). Two prefixes, one directory, one identity: the SPA suite
     * writes {@code vitest-<token>-<pid>.txt} from {@code src/test/suiteRecorder.ts} while Surefire
     * writes {@code executed-…}, so neither arm can read the other's record by accident and
     * {@code SuiteCoverageGuard}'s prune sweeps both. The token convention is shared — the reporter
     * reimplements {@link #token(String)} in eight lines of TypeScript because it cannot call this,
     * and {@code SuiteCoverageGuardTest} pins the two spellings against each other.
     */
    static final String VITEST_PREFIX = "vitest-";

    private static final String SUFFIX = ".txt";

    private SuiteRunRecord() {
    }

    /** Where records live for a build whose {@code project.build.directory} is {@code buildDir}. */
    public static Path directory(Path buildDir) {
        return buildDir.resolve(DIRECTORY_NAME);
    }

    /**
     * The build directory a recorder writes under, where <strong>blank means absent</strong>.
     *
     * <p>{@code System.getProperty(key, "target")} falls back when the property is missing and
     * <em>not</em> when it is explicitly empty, so {@code -Dhamstrack.test-run.build-dir=} produced
     * {@code Path.of("")} and a stray {@code test-run-record/} at the working directory. The vitest
     * half had the identical hole through {@code ??}, which coalesces only {@code null} and
     * {@code undefined}; both are read through the same rule now, and {@link #token(String)} two
     * methods down already read a blank run id the same way. An un-interpolated
     * {@code ${project.build.directory}} is how the empty value actually arrives.
     *
     * <p>The two halves are not symmetric in one respect, stated here rather than discovered:
     * {@code suiteRecorder.ts} additionally <em>refuses</em> a directory outside the repository,
     * because it is handed an environment variable that any ambient shell can set, while this side
     * reads a Maven property whose whole point is that a POM may legitimately relocate
     * {@code target}.
     */
    public static Path buildDirectory(String configured) {
        return Path.of(configured == null || configured.isBlank() ? "target" : configured.trim());
    }

    /**
     * The run identity reduced to characters legal in a file name on every platform we build on.
     * The default {@code maven.build.timestamp} format contains colons, which Windows rejects.
     */
    public static String token(String runId) {
        var raw = (runId == null || runId.isBlank()) ? NO_MAVEN_RUN : runId.trim();
        return raw.replaceAll("[^A-Za-z0-9]", "-");
    }

    /** The prefix every record file of one run shares; the reader matches on it. */
    public static String fileNamePrefix(String runId) {
        return PREFIX + token(runId) + "-";
    }

    /** The record file for one JVM of one run. */
    public static String fileName(String runId, long pid) {
        return fileNamePrefix(runId) + pid + SUFFIX;
    }

    /**
     * The prefix every vitest record file of one run shares (HD-301) — the SPA's answer to
     * {@link #fileNamePrefix(String)}. {@code src/main/frontend/src/test/suiteRecorder.ts} builds
     * the same name from the {@code HAMSTRACK_TEST_RUN_ID} environment variable the {@code npm-test}
     * execution hands it.
     */
    public static String vitestFileNamePrefix(String runId) {
        return VITEST_PREFIX + token(runId) + "-";
    }

    /**
     * True for any record file of either suite, of this run or an abandoned one.
     *
     * <p>Widened to the vitest prefix in the same change that introduced it: the prune that keeps
     * this directory from becoming a second {@code surefire-reports} is written once, and a record
     * shape it does not recognise is a file that outlives every run that could explain it.
     */
    public static boolean isRecordFile(String fileName) {
        return (fileName.startsWith(PREFIX) || fileName.startsWith(VITEST_PREFIX))
               && fileName.endsWith(SUFFIX);
    }
}
