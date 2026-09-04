package com.hamstrack.common.testsupport;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes down which test classes this JVM actually executed, so that something outside the JVM can
 * compare that against the tree (HD-265).
 *
 * <p><strong>The problem.</strong> An unfiltered {@code mvn test} reported success over a plan that
 * was missing a contiguous alphabetical prefix of the tree — whole packages were never selected, the
 * run said nothing about them, and the class the author was verifying at the time was inside the
 * gap. A green suite is evidence only about the classes that ran, and until this listener existed
 * nothing in the build knew which those were. The cause of that truncation is still open; this
 * records the fact, and {@code SuiteCoverageGuard} turns the fact into a build failure.
 *
 * <p><strong>Why a listener rather than a test.</strong> Registration is by
 * {@code ServiceLoader} through
 * {@code src/test/resources/META-INF/services/org.junit.platform.launcher.TestExecutionListener},
 * which the Launcher consults <em>before</em> and independently of the test plan. A guard shaped as
 * a test class is selected by the same mechanism that failed — it can be dropped by exactly the
 * truncation it watches for, and would then be silent precisely when it is needed. Detection
 * therefore lives here, where no test selection can reach it, and the refusal lives in a
 * Maven-bound step, which no test selection can reach either. Neither half can fail the build alone:
 * a listener cannot (the Launcher swallows what its callbacks throw), and the step cannot see what
 * ran.
 *
 * <p><strong>Both outcomes count as present.</strong> A class that ran and a class the engine
 * skipped (a class-level {@code @Disabled}, an unmet {@code @EnabledIf…}) were both offered to the
 * plan and are both reported; only a class the plan never contained is absent. Recording the
 * finished ones alone would make every conditional class look like a hole.
 *
 * <p><strong>Appending, not truncating.</strong> Nothing here assumes how many test plans one JVM
 * is given. Rewriting the file at each plan boundary is correct for a single batched plan and keeps
 * a record of one class if the runner ever drives the Launcher class by class — and which of those
 * happens is a property of Surefire's provider and its configuration, not of this suite, so it is
 * not worth depending on. Writes are therefore appends of whatever this JVM has not yet written, to
 * a file keyed by run identity and pid (see {@link SuiteRunRecord}), which the reader unions.
 *
 * <p>Nothing here may throw. An exception escaping a listener callback is not a test failure and not
 * a build failure — it is noise attached to whatever test happened to be running. A write that fails
 * is reported on stderr and leaves the record short, which the guard reports as a hole in the run:
 * loud in the right place, rather than fatal in the wrong one.
 */
public final class ExecutedTestClassRecorder implements TestExecutionListener {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Set<String> written = ConcurrentHashMap.newKeySet();

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        record(identifier);
    }

    @Override
    public void executionSkipped(TestIdentifier identifier, String reason) {
        record(identifier);
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        flush();
    }

    private void record(TestIdentifier identifier) {
        identifier.getSource().ifPresent(source -> {
            String className = null;
            if (source instanceof ClassSource classSource) {
                className = classSource.getClassName();
            } else if (source instanceof MethodSource methodSource) {
                className = methodSource.getClassName();
            }
            if (className != null && !className.isBlank()) {
                seen.add(topLevel(className));
            }
        });
    }

    /**
     * A {@code @Nested} class is reported under its own binary name; the tree that the guard scans
     * only knows top-level ones, so the enclosing class is what gets recorded.
     */
    private static String topLevel(String className) {
        var nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    private synchronized void flush() {
        List<String> pending = new ArrayList<>();
        for (var className : seen) {
            if (written.add(className)) {
                pending.add(className);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        try {
            var buildDir = System.getProperty(SuiteRunRecord.BUILD_DIRECTORY_PROPERTY, "target");
            var directory = SuiteRunRecord.directory(Path.of(buildDir));
            Files.createDirectories(directory);
            var file = directory.resolve(SuiteRunRecord.fileName(
                    System.getProperty(SuiteRunRecord.RUN_ID_PROPERTY), ProcessHandle.current().pid()));
            Files.writeString(file, String.join(System.lineSeparator(), pending) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            written.removeAll(pending);
            System.err.println("[test-run-record] could not record executed test classes: " + e);
        }
    }
}
