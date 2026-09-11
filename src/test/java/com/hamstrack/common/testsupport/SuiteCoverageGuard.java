package com.hamstrack.common.testsupport;

import com.hamstrack.ops.PublishedCredentials;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fails the build when a test run executed less of a tree than the tree contains, and names what it
 * never reached — for <strong>every</strong> automated suite in this repository (HD-265, HD-301).
 *
 * <p><strong>What went wrong.</strong> An unfiltered {@code mvn test} printed
 * {@code Tests run: 1463, Failures: 0, Errors: 0, Skipped: 0} and {@code BUILD SUCCESS} while
 * executing a strict subset of the tree: a contiguous alphabetical prefix — whole packages — was
 * never selected, and the run said nothing at all about it. The author had just written a class
 * inside that gap, so the green run was evidence about everything except the thing being verified.
 * It was caught only because the total looked low against a remembered figure, which is not a
 * mechanism. <em>An unfiltered run is not evidence either, unless the count is checked</em> — this
 * checks it.
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
 * <p><strong>Two arms, one decision.</strong> The claim is about a category — <em>every automated
 * suite proves how much of its own tree it executed</em> — so the SPA's vitest suite is a second
 * {@link SuiteCoverage.Arm} here rather than a second mechanism somewhere else, and both are judged
 * by {@link SuiteCoverage#verdict}. They differ in vocabulary, in where the record comes from and in
 * which switches stand them down; they do not differ in what an incomplete run means. A third suite
 * is a third {@code Arm}.
 *
 * <p><strong>Where the assertion lives, and why not somewhere easier.</strong> A guard written as a
 * test class is selected by the very mechanism that failed: a dropped prefix can drop the guard, and
 * it is then silent exactly when it is needed. A JUnit {@code TestExecutionListener} is immune to
 * test selection but cannot fail a build — the Launcher swallows what its callbacks throw, and
 * {@code System.exit} from inside a fork surfaces as "the forked VM terminated without properly
 * saying goodbye", which sends the reader to an unrelated question. A vitest reporter has the
 * matching problem one layer out: it is code <em>inside</em> the run, so it is silent in exactly the
 * case this exists for, and a guard shaped as a sibling {@code frontend-maven-plugin} execution is
 * switched off by the same {@code frontend.skip} and deleted in the same block as the suite it
 * guards. So detection and refusal are split: {@link ExecutedTestClassRecorder} and
 * {@code src/main/frontend/src/test/suiteRecorder.ts} record what ran, and this runs as a
 * Maven-bound step after both, where nothing either <em>suite</em> does can reach it. Its own
 * absence from {@code target/test-classes} fails the build as well, for the same reason.
 *
 * <p><strong>A plugin-level property is outside both suites and did reach it</strong>, so that
 * sentence is bounded rather than absolute: {@code maven-antrun-plugin}'s {@code run} goal declares
 * an editable {@code skip} defaulting to {@code ${maven.antrun.skip}}, and
 * {@code -Dmaven.antrun.skip=true} ran both suites in full, skipped this step and exited BUILD
 * SUCCESS (measured 2026-09-11). The execution now sets {@code <skip>false</skip>} explicitly, which
 * wins over the expression; {@code suiteGuard.test.ts} asserts that line beside the
 * {@code resultproperty} / {@code <fail>} pair. The general form is the thing to keep: a guard's
 * host plugin is a third off-switch next to the two suites', and it is closed in the POM rather than
 * described here.
 *
 * <p><strong>Each bound is derived, never pinned.</strong> The JVM arm's is the test-named sources
 * under {@code src/test/java}; the SPA arm's is what {@code git ls-files} reports under
 * {@code src/main/frontend}, filtered to the shape vitest would call a test. Adding a test cannot
 * make this red on its own and no number anywhere has to be maintained. Sources rather than compiled
 * classes, git rather than a filesystem walk: if a truncated compile is ever the cause, a set derived
 * from {@code target/test-classes} would be truncated in the same stroke and the guard would agree
 * with the bug — and a hand-maintained exclusion list is a standing disarm path, whereas
 * {@code --exclude-standard} follows {@code .gitignore}, whose collapse the floors catch. The
 * compiled tree is read too, but only to annotate the report.
 *
 * <p><strong>The JVM arm's expected set is a question about NAMES, and deliberately not about
 * annotations.</strong> A class carrying a test-shaped name that Jupiter then declines to run is
 * reported absent, and the remedy is to rename it — this file's own {@link SuiteRunRecord} helper was
 * called {@code TestRunRecord} for about an hour, which matched Surefire's {@code Test*} include and
 * made the guard demand a class with no tests in it. Skipping such classes by scanning for
 * {@code @Test} would trade that loud, one-line fix for a silent one: a class whose test methods are
 * all inherited from a {@code …Base} declares no annotation of its own, so an annotation filter
 * quietly shrinks the expected set — a smaller, better-hidden copy of the bug this exists to catch.
 * The SPA arm makes the same trade by bounding on vitest's <em>default</em> include shape rather than
 * this repo's narrower configured one. Loud and wrong about a helper beats quiet and wrong about a
 * test.
 *
 * <p><strong>It stays out of the way of every run that is narrower on purpose — and the two arms do
 * not stand down on the same switches.</strong> {@code -Dtest=…} and {@code -Dgroups=…} narrow
 * Surefire and leave the vitest suite running in full, so they disarm the JVM arm and the SPA arm
 * <em>stays armed</em>; {@code -Dfrontend.skip=true} is the exact reverse. Getting that symmetric is
 * how an arm comes to be permanently off. Each switch is handed in from the POM; Maven leaves an
 * undefined property literal, so an argument still reading {@code ${test}} is how "the developer
 * asked for no filter" arrives here, and blank says the same thing. An IDE builds its own command
 * line and never runs a Maven phase, so it is untouched.
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
     * Everything that narrows what Surefire selects. Phrased over the category — the next such
     * property belongs on this list, not in a second mechanism somewhere else.
     */
    static final List<String> JVM_NARROWING_SWITCHES =
            List.of("test", "groups", "excludedGroups", "skipTests", "maven.test.skip",
                    "maven.test.skip.exec");

    /**
     * Everything that narrows what the {@code npm-test} execution runs, which is a <em>different
     * list</em> and the single thing most likely to be got wrong here.
     *
     * <p>Measured from {@code frontend-maven-plugin} 1.15.1's own plugin descriptor on 2026-09-11:
     * the {@code npm} goal binds {@code skipTests} to {@code ${skipTests}} and to nothing else, and
     * {@code skip} to the plugin-level {@code <skip>${frontend.skip}</skip>}. So {@code -Dtest=Foo},
     * {@code -Dgroups=…} and {@code -Dmaven.test.skip.exec=true} narrow Surefire and leave the whole
     * 45-70 s vitest suite running in full — that run is answerable for the SPA tree and this arm
     * stays armed for it. {@code -Dmaven.test.skip=true} is the same: it is not on this list because
     * the plugin does not read it (see the note on the {@code -Dmaven.test.skip=true} edge in the
     * POM).
     */
    static final List<String> VITEST_NARROWING_SWITCHES = List.of("skipTests", "frontend.skip");

    /**
     * A collapse detector on the JVM bound, with deliberate slack: 301 test-named sources on
     * 2026-09-11 (measured), and HD-265's truncated run still executed 224. Its job is the collapse
     * — a scan that stopped seeing {@code src/test/java} at all — and not the drift, which is what
     * the printed count is for. Never lower it to make a run pass.
     *
     * <p>Its causes are its own, and that is the rule rather than a detail of this constant: the
     * remedies for "this scan stopped seeing its tree" are different for a filesystem walk of
     * {@code src/test/java}, for {@code git ls-files} over the SPA, and for the {@code Doors}
     * bytecode scan whose message all three used to borrow.
     */
    static final SuiteCoverage.Floor JVM_TREE_FLOOR = new SuiteCoverage.Floor(240,
            "The comparison below is `tree - executed`, so a bound that collapsed makes it EMPTY and"
            + " prints the most confident possible green line about a tree nobody looked at.",
            List.of("the working directory is not the project root -- this scan reads"
                    + " src/test/java relative to it, and the antrun step inherits Maven's;",
                    "src/test/java really did lose that many classes -- if that was deliberate,"
                    + " lower this floor in the SAME commit and name the classes in its message;",
                    "the name predicate narrowed -- Surefire's four default includes are"
                    + " SuiteCoverageGuard.TEST_NAME_PATTERNS, and dropping one of them shrinks the"
                    + " bound rather than turning anything red."));

    /**
     * A collapse detector on the SPA bound: 73 files matched on 2026-09-11 before this ticket added
     * its own seals, <strong>75</strong> after them (measured through the Maven path, one full
     * {@code mvnw test}). Same slack, same rule: it catches the collapse, not the drift, and the
     * drift is what the printed count and the job summary are for.
     */
    static final SuiteCoverage.Floor FRONTEND_TEST_FILE_FLOOR = new SuiteCoverage.Floor(55,
            "The comparison below is `tree - executed`, so a bound that collapsed makes it EMPTY and"
            + " prints the most confident possible green line about a tree nobody looked at.",
            List.of("the bound is derived from `git ls-files` in src/main/frontend and this step"
                    + " has no checkout, or its working directory is not the project root;",
                    "a .gitignore rule now swallows the SPA's test files -- `git ls-files --others"
                    + " --exclude-standard` follows it, and an ignored file is not in the bound;",
                    "the SPA really did lose that many test files -- if that was deliberate, lower"
                    + " this floor in the SAME commit and name them in its message."));

    /**
     * The answer to "every module ran and contained nothing" — without it, emptying every
     * {@code describe} body is a green run in which every module is present. Set at ~75 % of the
     * 1218 tests measured on 2026-09-11 by the same run (1231 once this ticket's own seals landed,
     * over 75 files).
     * The JVM arm has no counterpart because nothing hands it a test count; that is stated as
     * {@code null} rather than defaulted to 0, which would be a floor of "any".
     *
     * <p>It is judged <strong>after</strong> the set difference, so by the time it can fire every
     * file in the tree has been shown to have run and "every member empty" is a statement about the
     * modules rather than about a truncated plan.
     */
    static final SuiteCoverage.Floor FRONTEND_TEST_COUNT_FLOOR = new SuiteCoverage.Floor(900,
            "Every test file in the tree ran, so this is what was INSIDE them, and a suite whose"
            + " modules all load and assert nothing is the greenest run there is.",
            List.of("a `describe` body, or a whole file's worth of them, was emptied or commented"
                    + " out -- `git diff --stat src/main/frontend/src` on the last few commits;",
                    "tests were deleted outright, which is the only edit that moves this number --"
                    + " a bulk `it.skip`/`it.todo` does NOT: the count comes from the reporter's"
                    + " allTests(), which yields skipped and todo tests, so one skipped of six"
                    + " still records tests=6 (measured 2026-09-11, both spellings). Emptying the"
                    + " bodies is what hides from this floor, and"
                    + " VacuousVerificationRulesTest#noFrontendTestSkipsWithoutATicket is what"
                    + " sees the skips;",
                    "the SPA really did shed that many tests -- if that was deliberate, lower this"
                    + " floor in the SAME commit and say what went."));

    /** Where the summary both arms write goes; CI appends it to the job summary with {@code if: always()}. */
    static final String SUMMARY_FILE_NAME = "test-tree-summary.md";

    private SuiteCoverageGuard() {
    }

    public static void main(String[] args) {
        var arguments = parse(args);
        var refusals = new ArrayList<String>();
        var buildDirectory = arguments.get("buildDirectory");
        if (buildDirectory == null) {
            // The paths that leave no summary file are the two about the directory the summary goes
            // in -- this one, and an unusable value for it below. Both say so; everything
            // downstream of them leaves a row, a throw included.
            refusals.add(missingPomArgument("[test-tree]", "buildDirectory",
                    "the directory holding this run's records, the spill files and the summary"));
        } else {
            refusals.addAll(judgeOrRefuse(arguments, buildDirectory));
        }
        refusals.forEach(System.err::println);
        System.exit(refusals.isEmpty() ? 0 : 1);
    }

    /**
     * {@link #judge} with its throws turned into the guard's own voice.
     *
     * <p>{@code main} used to declare {@code throws IOException} and catch nothing, so a wrong
     * working directory, an unreadable {@code target/} or a {@code git} that is not on the PATH left
     * a Java stack trace, exit 1, <strong>no summary</strong> — and Maven's last line pointing the
     * reader at a {@code [test-tree]} report that had never printed. That is the exact shape
     * {@link #missingPomArgument} was written to delete one level down, and it survived one level
     * up. The stack trace is still printed, because it is the diagnostic; the sentence follows it,
     * because that is the part a reader can act on.
     */
    private static List<String> judgeOrRefuse(Map<String, String> arguments, String configured) {
        Path buildDirectory;
        try {
            buildDirectory = Path.of(configured);
        } catch (RuntimeException e) {
            // The third and last path with no summary, because there is nowhere to put one.
            return List.of("[test-tree] REFUSED: buildDirectory=" + configured + " is not a usable"
                           + " path (" + e + "), so this run has no records to read and nowhere to"
                           + " write its summary." + System.lineSeparator()
                           + "It comes from <arg value=\"buildDirectory=${project.build.directory}\"/>"
                           + " on the test-tree-coverage-guard execution in pom.xml. Nothing was"
                           + " compared.");
        }
        try {
            return judge(arguments, buildDirectory);
        } catch (IOException | RuntimeException e) {
            e.printStackTrace(System.err);
            var refusal = threwBeforeJudging(e);
            try {
                if (alreadySummarised(buildDirectory, arguments.get("runId"))) {
                    // Thrown AFTER the table was written -- spilling the names, or pruning. The
                    // rows already there are this run's real counts and are strictly better than a
                    // one-line crash row, so they stay.
                    refusal += System.lineSeparator() + "(this run's summary table was already"
                               + " written and stands: the throw came after it, while spilling the"
                               + " absent members or pruning the records.)";
                } else {
                    writeSummary(buildDirectory, List.of(SuiteCoverage.summaryRow("guard", null,
                            null, null, "REFUSED: threw before judging")), arguments.get("runId"));
                    refusal += System.lineSeparator() + "(the summary row for this run reads"
                               + " `REFUSED: threw before judging`, so the table and this sentence"
                               + " agree about what happened.)";
                }
            } catch (IOException | RuntimeException alsoFailed) {
                // The one failure path here that could end in silence, so it ends in a sentence
                // instead: a reader looking for the row this refusal promises must be told it is
                // not there, in the refusal itself.
                refusal += System.lineSeparator() + "(and " + buildDirectory + "/"
                           + SUMMARY_FILE_NAME + " could not be written either: " + alsoFailed
                           + " -- so there is no row for this run and the CI step will refuse for"
                           + " the same reason.)";
            }
            return List.of(refusal);
        }
    }

    /**
     * Whether the summary under {@code buildDirectory} is <em>this</em> run's, by the identity line
     * rather than by existence — a leftover from the previous local build is the very thing that
     * line was added for, and reading it as this run's here would be that defect with a new author.
     */
    private static boolean alreadySummarised(Path buildDirectory, String runId) throws IOException {
        var summary = buildDirectory.resolve(SUMMARY_FILE_NAME);
        return Files.isRegularFile(summary)
               && Files.readString(summary, StandardCharsets.UTF_8)
                       .contains(SuiteCoverage.summaryIdentityPrefix(SuiteRunRecord.token(runId)));
    }

    /** ≤ 25 lines, naming the action, for the run that never reached a verdict. */
    private static String threwBeforeJudging(Exception e) {
        var line = System.lineSeparator();
        return "[test-tree] REFUSED: the guard threw and did not finish: " + e + line
               + "An unfinished check is not a stand-down -- whatever it had compared, it did not"
               + " get to say so, and a guard that cannot see its subject must not pass it. The"
               + " stack trace above is the diagnostic; in the order these actually happen:" + line
               + "  - the working directory is not the project root -- this step reads"
               + " src/test/java, src/main/frontend and target/ relative to it, and inherits"
               + " Maven's;" + line
               + "  - target/ or its " + SuiteRunRecord.DIRECTORY_NAME + "/ directory cannot be"
               + " read or written by this JVM (a stale container-owned target/ does this);" + line
               + "  - git is not on the PATH for THIS step -- the SPA bound comes from"
               + " `git ls-files`, and the arm's own refusal covers a failing git, not a missing"
               + " one.";
    }

    /** Both arms, their witness lines, their summary and their spill files. */
    private static List<String> judge(Map<String, String> arguments, Path buildDirectory)
            throws IOException {
        var recordDirectory = SuiteRunRecord.directory(buildDirectory);
        var runId = arguments.get("runId");

        var verdicts = new ArrayList<SuiteCoverage.Verdict>();
        for (var member : members(buildDirectory, recordDirectory, runId,
                arguments.get("frontendRoot"))) {
            verdicts.add(member.verdict().judge(arguments, member.arm(), recordDirectory, runId));
        }

        var refusals = new ArrayList<String>();
        for (var verdict : verdicts) {
            verdict.witness().forEach(System.out::println);
            refusals.addAll(verdict.refusals());
        }
        // BEFORE the spill loop, and that order is the finding: the counts matter most on the run
        // that failed, and spilling first meant an IO error while writing the names of the absent
        // members took the summary down with it. The paths that leave no summary are now the one in
        // main (no buildDirectory) and a throw from this line itself, which judgeOrRefuse reports.
        writeSummary(buildDirectory, verdicts.stream().map(SuiteCoverage.Verdict::summaryRow).toList(),
                runId);
        for (var verdict : verdicts) {
            spill(verdict, buildDirectory);
        }
        if (refusals.isEmpty()) {
            // A record is evidence about one invocation; keeping them past a clean run is how
            // target/surefire-reports became a pile that outlived the classes it names. A red run
            // keeps its own, because that one is being read.
            prune(recordDirectory);
        }
        return refusals;
    }

    // ------------------------------------------------------------------ the category

    /** How one member of the category turns this run's arguments into a verdict. */
    @FunctionalInterface
    interface ArmVerdict {
        SuiteCoverage.Verdict judge(Map<String, String> arguments, SuiteCoverage.Arm arm,
                                    Path recordDirectory, String runId) throws IOException;
    }

    /** One suite: what it is answerable for, and how this run finds out what it did. */
    record Member(SuiteCoverage.Arm arm, ArmVerdict verdict) {
    }

    /**
     * <strong>The enumeration of the category, read by the guard and by its seal.</strong>
     *
     * <p>{@code judge} used to build its two verdicts inline while
     * {@code SuiteCoverageGuardTest#givesEveryFloorRemediesItsOwnReaderCanPerform} built its own
     * hand-written list of the same two arms — so a third suite would have reached the guard and not
     * the test, and that test's floor of 3 on the population of floors cannot notice a member it was
     * never handed. Same shape as {@code common.testsupport.Doors}: one enumeration, two readers.
     */
    static List<Member> members(Path buildDirectory, Path recordDirectory, String runId,
                                String frontendRoot) {
        return List.of(
                new Member(jvmArm(buildDirectory, recordDirectory, runId),
                        SuiteCoverageGuard::jvmVerdict),
                new Member(vitestArm(buildDirectory, recordDirectory, runId, frontendRoot),
                        SuiteCoverageGuard::vitestVerdict));
    }

    /** The arms of {@link #members}, for a reader that only needs their vocabulary and floors. */
    static List<SuiteCoverage.Arm> arms(Path buildDirectory, Path recordDirectory, String runId,
                                        String frontendRoot) {
        return members(buildDirectory, recordDirectory, runId, frontendRoot).stream()
                .map(Member::arm).toList();
    }

    // ------------------------------------------------------------------- the JVM arm

    /** The JVM arm's vocabulary, floors and remedies; separated so a test can enumerate them. */
    static SuiteCoverage.Arm jvmArm(Path buildDirectory, Path recordDirectory, String runId) {
        return new SuiteCoverage.Arm("jvm", "[test-tree]", "test classes", "src/test/java",
                buildDirectory.resolve("missing-test-classes.txt").toString(),
                JVM_TREE_FLOOR, null,
                List.of("The bracket is the first fork in the road: [never compiled] puts it before"
                        + " test-compile finished, [compiled, never selected] puts it in Surefire's"
                        + " scan.",
                        "Re-run the suite. If the same classes are absent twice the tree really did"
                        + " change; if they come back on a re-run, HD-265's truncation has been"
                        + " reproduced and the cause is still open -- keep this output, the Surefire"
                        + " log and " + recordDirectory + ".",
                        "-Dtest=... and -DskipTests switch this arm off honestly."),
                List.of("the recorder is no longer registered -- src/test/resources/META-INF/services/"
                        + "org.junit.platform.launcher.TestExecutionListener names it, and without"
                        + " that file the suite runs, passes, and nothing knows how much of it ran;",
                        "the listener is registered and its write failed -- it prints"
                        + " `[test-run-record] ...` on stderr rather than throwing;",
                        "the run identity never reached the test JVM -- surefire's"
                        + " <systemPropertyVariables> hamstrack.test-run.id and"
                        + " hamstrack.test-run.build-dir. A record of this run would be named "
                        + SuiteRunRecord.fileNamePrefix(runId) + "<pid>.txt under "
                        + recordDirectory + ";",
                        "the recorder wrote somewhere this step does not read -- "
                        + SuiteRunRecord.BUILD_DIRECTORY_PROPERTY + " points it at a directory of"
                        + " its own, and unlike the vitest reporter this half does NOT refuse one"
                        + " outside the repository, because a POM may legitimately relocate target"
                        + " (SuiteRunRecord.buildDirectory says why). Compare that property against"
                        + " the buildDirectory= argument on the guard's execution: both come from"
                        + " ${project.build.directory} and a run where they differ writes a record"
                        + " nobody reads."));
    }

    static SuiteCoverage.Verdict jvmVerdict(Map<String, String> arguments, SuiteCoverage.Arm arm,
                                            Path recordDirectory, String runId) throws IOException {
        var narrowing = firstNarrowingSwitch(arguments, JVM_NARROWING_SWITCHES);
        if (narrowing != null) {
            return SuiteCoverage.verdict(arm, new SuiteCoverage.Run(null, null, null, null,
                    narrowing, null));
        }

        var needed = new LinkedHashMap<String, String>();
        needed.put("testSources", "the tree this arm is answerable for");
        needed.put("testClasses", "the compiled classes, which annotate the report");
        var missingArguments = missingPomArguments(arm, needed, arguments);
        if (!missingArguments.isEmpty()) {
            return refusedVerdict(arm, "REFUSED: the POM handed this arm no inputs", missingArguments);
        }

        var expected = scanSources(Path.of(arguments.get("testSources")));
        var compiled = scanCompiled(Path.of(arguments.get("testClasses")));
        var records = recordsOfThisRun(recordDirectory, SuiteRunRecord.fileNamePrefix(runId));
        var lines = readLines(records);

        return SuiteCoverage.verdict(arm, new SuiteCoverage.Run(
                expected.keySet(), SuiteCoverage.membersIn(lines), records.size(), null, null,
                member -> compiled.contains(member) ? "   [compiled, never selected]"
                        : "   [never compiled]"));
    }

    // ----------------------------------------------------------------- the vitest arm

    /**
     * The vitest arm's vocabulary, floors and remedies; separated so a test can enumerate them.
     *
     * @param frontendRoot the SPA root, or {@code null} when the POM handed none over — the arm
     *                     still has to be able to say so, in its own words
     */
    static SuiteCoverage.Arm vitestArm(Path buildDirectory, Path recordDirectory, String runId,
                                       String frontendRoot) {
        var where = frontendRoot == null ? "src/main/frontend" : frontendRoot;
        return new SuiteCoverage.Arm("vitest", "[vitest-tree]", "test files", where,
                buildDirectory.resolve("missing-test-files.txt").toString(),
                FRONTEND_TEST_FILE_FLOOR, FRONTEND_TEST_COUNT_FLOOR,
                List.of("The first fork in the road is whether the file is INSIDE vitest's"
                        + " configured include (src/**/*.{test,spec}.{ts,tsx}) or outside it. This"
                        + " bound is vitest's DEFAULT include shape rooted at " + where
                        + ", which is wider on purpose.",
                        "  OUTSIDE it -- the file is a test nobody runs. Move it under " + where
                        + "/src/, or widen `include` in vitest.config.ts AND"
                        + " VacuousVerificationRulesTest.VITEST_INCLUDE in the same diff.",
                        "  INSIDE it and not run -- an `exclude` was added to vitest.config.ts, a"
                        + " filter reached the runner, or the module was never offered to the plan."
                        + " Re-run `npm test` in " + where + " and read its file list.",
                        "-Dfrontend.skip=true and -DskipTests switch this arm off honestly."),
                List.of("the `npm-test` execution did not run -- it must still be in pom.xml, bound"
                        + " to the `test` phase, with <arguments>run test</arguments>;",
                        "`./src/test/suiteRecorder.ts` is no longer in `reporters` in"
                        + " vitest.config.ts, or a --reporter=... flag on the command line replaced"
                        + " that whole list;",
                        "the recorder ran and refused or failed to write -- it prints"
                        + " `[vitest-run-record] ...` on stderr rather than throwing, and it refuses"
                        + " a HAMSTRACK_TEST_RUN_DIR outside the repository;",
                        "the run identity never reached the reporter -- the <environmentVariables>"
                        + " HAMSTRACK_TEST_RUN_ID and HAMSTRACK_TEST_RUN_DIR on the npm-test"
                        + " execution. A record of this run would be named "
                        + SuiteRunRecord.vitestFileNamePrefix(runId) + "<pid>.txt under "
                        + recordDirectory + "."));
    }

    static SuiteCoverage.Verdict vitestVerdict(Map<String, String> arguments, SuiteCoverage.Arm arm,
                                               Path recordDirectory, String runId)
            throws IOException {
        var frontendRoot = arguments.get("frontendRoot");

        var narrowing = firstNarrowingSwitch(arguments, VITEST_NARROWING_SWITCHES);
        if (narrowing != null) {
            return SuiteCoverage.verdict(arm, new SuiteCoverage.Run(null, null, null, null,
                    narrowing, null));
        }
        if (frontendRoot == null) {
            // Not a stand-down: an input the POM failed to hand over is a guard that cannot see its
            // subject, and a guard that cannot see its subject must not pass it.
            return refusedVerdict(arm, "REFUSED: the POM handed this arm no inputs",
                    List.of(missingPomArgument(arm.tag(), "frontendRoot",
                            "the SPA root this arm is answerable for")));
        }

        Set<String> tree;
        try {
            tree = treeFromGit(frontendRoot);
        } catch (RuntimeException e) {
            return refusedVerdict(arm, "REFUSED: the tree could not be read",
                    List.of(arm.tag() + " REFUSED: the bound under " + arm.where()
                            + " could not be derived: " + e
                            + System.lineSeparator()
                            + "It comes from `git ls-files --cached --others --exclude-standard`,"
                            + " which is how every matching file inside node_modules stays out of it"
                            + " without a hand-maintained exclusion list. This step therefore needs a"
                            + " checkout; CI already does one. A guard that cannot see the tree must"
                            + " not pass it, so this is a refusal and not a stand-down."));
        }

        var records = recordsOfThisRun(recordDirectory, SuiteRunRecord.vitestFileNamePrefix(runId));
        var lines = readLines(records);
        return SuiteCoverage.verdict(arm, new SuiteCoverage.Run(tree, SuiteCoverage.membersIn(lines),
                records.size(), SuiteCoverage.summaryTestCount(lines), null, null));
    }

    /**
     * The SPA bound: repo-relative POSIX paths, exactly the spelling
     * {@code src/test/suiteRecorder.ts} writes into the record.
     *
     * <p>From git rather than a filesystem walk, because {@code src/main/frontend/node_modules}
     * holds <strong>155</strong> files matching the same shape (measured 2026-09-11) and an
     * exclusion list maintained by hand is a thing that can be widened silently. {@code --others
     * --exclude-standard} rather than {@code --cached} alone because a test file written five
     * minutes ago is untracked and vitest runs it.
     */
    static Set<String> treeFromGit(String frontendRoot) {
        Set<String> tree = new TreeSet<>();
        for (var path : PublishedCredentials.publishableFiles(frontendRoot)) {
            var posix = path.toString().replace(File.separatorChar, '/').replace('\\', '/');
            if (SuiteCoverage.matchesVitestDefaultInclude(posix)
                && !SuiteCoverage.excludedByVitestDefaults(posix)) {
                tree.add(posix);
            }
        }
        return tree;
    }

    private static SuiteCoverage.Verdict refusedVerdict(SuiteCoverage.Arm arm, String status,
                                                        List<String> refusals) {
        return new SuiteCoverage.Verdict(arm, status, List.of(), List.copyOf(refusals),
                null, null, null, Set.of());
    }

    /**
     * Every argument the POM was supposed to hand this arm and did not, as judged refusals.
     *
     * <p>This used to be an {@code IllegalArgumentException} out of a {@code required()} helper,
     * which is the shape {@link SuiteCoverage}'s own first property calls a defect: a stack trace
     * where a sentence belongs, no summary row, and the {@code <fail>} line telling the reader to
     * consult a {@code [test-tree]} report that was never printed. Each one is a sentence naming the
     * {@code <arg>} to restore.
     *
     * @param needed argument name to what it is for, in the order they should be reported
     */
    private static List<String> missingPomArguments(SuiteCoverage.Arm arm, Map<String, String> needed,
                                                    Map<String, String> arguments) {
        var refusals = new ArrayList<String>();
        needed.forEach((name, what) -> {
            if (arguments.get(name) == null) {
                refusals.add(missingPomArgument(arm.tag(), name, what));
            }
        });
        return refusals;
    }

    static String missingPomArgument(String tag, String name, String what) {
        var line = System.lineSeparator();
        return tag + " REFUSED: the POM passed no " + name + " -- " + what + "." + line
               + "Nothing was compared, and this is not a stand-down: a guard that cannot see its"
               + " subject must not pass it." + line
               + "Restore `<arg value=\"" + name + "=...\"/>` on the test-tree-coverage-guard"
               + " execution in pom.xml; the <arg> list there is the whole input set, and"
               + " src/main/frontend/src/lint/suiteGuard.test.ts asserts the ones a build cannot run"
               + " without.";
    }

    // ----------------------------------------------------------------- the witness

    /**
     * The summary table, under a line saying <em>which run wrote it</em>.
     *
     * <p>Without that line a copy left by the previous local build reads exactly like this run's: a
     * failure before this step (surefire, lint, vitest — four of the five red paths) stops Maven
     * first, so the file is never rewritten and a reader takes yesterday's counts for today's.
     * Measured 2026-09-11, mtime 30 seconds older than the run that was being read. CI cannot hit it
     * (fresh workspace), which is precisely why it had to be said rather than relied on.
     */
    private static void writeSummary(Path buildDirectory, List<String> armRows, String runId)
            throws IOException {
        var rows = new ArrayList<String>();
        rows.add(SuiteCoverage.summaryIdentity(SuiteRunRecord.token(runId),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()));
        rows.add("");
        rows.addAll(SuiteCoverage.summaryHeader());
        rows.addAll(armRows);
        Files.createDirectories(buildDirectory);
        Files.write(buildDirectory.resolve(SUMMARY_FILE_NAME), rows, StandardCharsets.UTF_8);
    }

    /**
     * The spilled names, or the removal of a spill file this run did not write — a stale one outlives
     * the run that explains it and is read as this run's, the same defect the summary header closes.
     */
    private static void spill(SuiteCoverage.Verdict verdict, Path buildDirectory) throws IOException {
        if (verdict.missing().size() <= SuiteCoverage.MAX_LISTED) {
            Files.deleteIfExists(Path.of(verdict.arm().spillFileName()));
            return;
        }
        Files.createDirectories(buildDirectory);
        Files.write(Path.of(verdict.arm().spillFileName()), new TreeSet<>(verdict.missing()),
                StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------------- arming

    /**
     * The switch that makes a short plan intentional for this arm, or {@code null} when the run
     * asked for everything it is answerable for.
     */
    static String firstNarrowingSwitch(Map<String, String> arguments, List<String> keys) {
        for (var key : keys) {
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

    private static List<Path> recordsOfThisRun(Path directory, String prefix) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith(prefix)).sorted().toList();
        }
    }

    private static List<String> readLines(List<Path> records) throws IOException {
        List<String> lines = new ArrayList<>();
        for (var record : records) {
            lines.addAll(Files.readAllLines(record, StandardCharsets.UTF_8));
        }
        return lines;
    }

    /**
     * Sweeps every record, not only this run's, so that a filtered or IDE run cannot leave one
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
}
