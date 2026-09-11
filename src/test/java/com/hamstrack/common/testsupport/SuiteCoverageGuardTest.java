package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Seals the derivation and the decision both suite-coverage arms are built on, because every way
 * either of them can go wrong is quiet.
 *
 * <p>{@link SuiteCoverageGuard} fails a build that executed less of a tree than the tree contains —
 * {@code src/test/java} through Surefire (HD-265), {@code src/main/frontend} through vitest
 * (HD-301). Each bound is derived, so a bug that makes one SMALLER does not turn anything red; it
 * just narrows what the guard is willing to ask for. Drop one of Surefire's four default include
 * shapes, start excusing classes on a clever heuristic, let the SPA bound collapse to nothing, or
 * weaken the comparison from sets to sizes, and the guard keeps printing a confident line about a
 * tree it has quietly stopped seeing whole: a smaller, better-hidden copy of the failure it was
 * written for. That is what is checked here.
 *
 * <p>The fixtures below are the rows of HD-301 §7 — every way the guard can pass while verifying
 * nothing — driven through {@link SuiteCoverage#verdict}, which is a pure function precisely so they
 * can be. The wiring rows (the reporter unregistered, the execution deleted, the script rewritten)
 * are sealed from the other side, in {@code src/main/frontend/src/lint/suiteGuard.test.ts}, because
 * a guard living inside the artifact it guards goes quiet in the same commit that needs it.
 *
 * <p>This class is, of course, selectable — the truncation it descends from could drop it. That is
 * not a hole in the design: the refusal lives in a Maven step precisely because it must survive
 * that, and this is the second-order seal on the guard's arithmetic, not the guard.
 */
class SuiteCoverageGuardTest {

    private static final Path TEST_SOURCES = Path.of("src", "test", "java");
    private static final Path GUARD_SOURCE = TEST_SOURCES.resolve(
            "com/hamstrack/common/testsupport/SuiteCoverageGuard.java");

    /** A stand-in arm with small floors, so a fixture can sit one under each of them. */
    private static final SuiteCoverage.Arm ARM = new SuiteCoverage.Arm(
            "probe", "[probe]", "test files", "some/root", "target/missing.txt",
            new SuiteCoverage.Floor(3, "the bound went.", List.of("tree cause one", "tree cause two")),
            new SuiteCoverage.Floor(10, "the members went.", List.of("test cause one")),
            List.of("the fork in the road"),
            List.of("cause one", "cause two", "cause three"));

    private static SuiteCoverage.Run run(Set<String> tree, Set<String> executed) {
        return new SuiteCoverage.Run(tree, executed, 1, 50, null, null);
    }

    // ------------------------------------------------------------------ the JVM bound

    @Test
    void recognisesEveryShapeSurefireIncludesByDefault() {
        // The four are `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`, and
        // this repo overrides none of them. Recognising only `*Test` is the tempting simplification
        // and it would drop HamstrackApplicationTests — a class that matches through the THIRD
        // pattern alone — out of the expected set, silently.
        assertThat(SuiteCoverageGuard.matchesTestName("TestSomething"))
                .as("Test*: Surefire runs it, so the guard must expect it").isTrue();
        assertThat(SuiteCoverageGuard.matchesTestName("SomethingTest")).isTrue();
        assertThat(SuiteCoverageGuard.matchesTestName("HamstrackApplicationTests"))
                .as("*Tests: dropping this pattern loses a real class from the bound").isTrue();
        assertThat(SuiteCoverageGuard.matchesTestName("SomethingTestCase")).isTrue();

        assertThat(SuiteCoverageGuard.matchesTestName("SprintTestBase"))
                .as("a support class outside the four patterns; Surefire ignores it and so must the"
                    + " guard, or every build demands that a base class run")
                .isFalse();
        assertThat(SuiteCoverageGuard.matchesTestName("SuiteRunRecord")).isFalse();
        assertThat(SuiteCoverageGuard.matchesTestName("SuiteCoverage"))
                .as("the new decision helper must stay outside the four patterns too -- a helper"
                    + " named into them is demanded by the guard as a class that must execute")
                .isFalse();
    }

    @Test
    void excusesOnlyWhatJupiterCannotRunAtAll() {
        assertThat(SuiteCoverageGuard.cannotBeRun("package p;\npublic abstract class FooTest {}", "FooTest"))
                .as("abstract: Surefire will not run it, so demanding it would be a standing false red")
                .isTrue();
        assertThat(SuiteCoverageGuard.cannotBeRun("package p;\ninterface FooTest {}", "FooTest")).isTrue();

        assertThat(SuiteCoverageGuard.cannotBeRun("package p;\nclass FooTest {}", "FooTest")).isFalse();
        assertThat(SuiteCoverageGuard.cannotBeRun(
                "package p;\nclass FooTest extends SprintTestBase {\n  // inherits its @Test methods\n}",
                "FooTest"))
                .as("a class that declares no test of its own still runs; excusing it on the absence"
                    + " of an annotation is how the bound would shrink without anything going red")
                .isFalse();
    }

    @Test
    void derivesTheExpectedSetFromTheRealTreeIncludingItself() throws IOException {
        var expected = SuiteCoverageGuard.scanSources(TEST_SOURCES);

        assertThat(expected)
                .as("the guard reads %s relative to the module directory, which is where Surefire"
                    + " and the antrun step both run; an empty result here means it would pass every"
                    + " run by seeing no tree at all", TEST_SOURCES)
                .isNotEmpty();
        assertThat(expected).containsKey(getClass().getName());
        assertThat(expected).containsKey("com.hamstrack.HamstrackApplicationTests");
        assertThat(expected.size())
                .as("the guard's own JVM floor must sit UNDER the live tree, or every build is red"
                    + " for a reason that is not about the tree")
                .isGreaterThanOrEqualTo(SuiteCoverageGuard.JVM_TREE_FLOOR.value());

        assertThat(expected.keySet())
                .as("support classes must stay outside Surefire's four name patterns -- one named"
                    + " into them is demanded by the guard on every build and can only be answered"
                    + " by renaming it")
                .doesNotContain(SuiteRunRecord.class.getName(),
                        SuiteCoverage.class.getName(),
                        SuiteCoverageGuard.class.getName(),
                        ExecutedTestClassRecorder.class.getName());
    }

    // ------------------------------------------------------------------ the SPA bound

    @Test
    void boundsTheSpaOnVitestsDefaultIncludeShapeRatherThanThisReposNarrowerOne() {
        // Wider than `src/**/*.{test,spec}.{ts,tsx}` on purpose: a test file outside the configured
        // include is a test NOBODY RUNS, and the only way anything says so is for the bound to
        // demand it and the comparison to report it absent.
        assertThat(SuiteCoverage.matchesVitestDefaultInclude("src/main/frontend/src/api.test.ts")).isTrue();
        assertThat(SuiteCoverage.matchesVitestDefaultInclude("src/main/frontend/src/x.test.tsx")).isTrue();
        assertThat(SuiteCoverage.matchesVitestDefaultInclude("src/main/frontend/src/x.spec.ts")).isTrue();
        assertThat(SuiteCoverage.matchesVitestDefaultInclude("src/main/frontend/eslint-rules/r.test.js"))
                .as("a .js test outside src/ is exactly the file the configured include cannot see")
                .isTrue();
        assertThat(SuiteCoverage.matchesVitestDefaultInclude("src/main/frontend/audit/a.test.mjs")).isTrue();
        assertThat(SuiteCoverage.matchesVitestDefaultInclude("src/main/frontend/audit/a.test.cts")).isTrue();

        assertThat(SuiteCoverage.matchesVitestDefaultInclude("src/main/frontend/src/api.ts")).isFalse();
        assertThat(SuiteCoverage.matchesVitestDefaultInclude("src/main/frontend/src/test/setup.ts"))
                .as("the suite's setup file is not a test module and vitest never collects it")
                .isFalse();
        assertThat(SuiteCoverage.matchesVitestDefaultInclude("src/main/frontend/src/test/suiteRecorder.ts"))
                .as("the recorder must not match its own bound, or it becomes a member of the set"
                    + " it exists to measure")
                .isFalse();

        // node_modules holds 155 files of exactly this shape (measured 2026-09-11): a bound derived
        // from "**/*.test.ts* in the tree" -- the ticket's own phrasing -- would demand that vitest
        // run zod's and entities' suites and would be red on every build.
        assertThat(SuiteCoverage.excludedByVitestDefaults(
                "src/main/frontend/node_modules/zod/src/x.test.ts")).isTrue();
        assertThat(SuiteCoverage.excludedByVitestDefaults("src/main/frontend/dist/x.test.js")).isTrue();
        assertThat(SuiteCoverage.excludedByVitestDefaults("src/main/frontend/coverage/x.test.ts")).isTrue();
        assertThat(SuiteCoverage.excludedByVitestDefaults("src/main/frontend/cypress/x.test.ts")).isTrue();
        assertThat(SuiteCoverage.excludedByVitestDefaults("src/main/frontend/.cache/x.test.ts")).isTrue();
        assertThat(SuiteCoverage.excludedByVitestDefaults("src/main/frontend/vitest.config.test.ts"))
                .as("vitest's own defaultExclude drops **/{…,vitest,…}.config.*").isTrue();
        assertThat(SuiteCoverage.excludedByVitestDefaults("src/main/frontend/src/api.test.ts")).isFalse();
    }

    @Test
    void derivesTheSpaTreeFromTheRealCheckoutAndClearsItsOwnFloor() {
        var tree = SuiteCoverageGuard.treeFromGit("src/main/frontend");

        assertThat(tree)
                .as("the SPA bound comes from `git ls-files` at the module root, which is where the"
                    + " antrun step runs; an empty result means the guard would pass every run by"
                    + " seeing no tree at all")
                .isNotEmpty();
        assertThat(tree).contains("src/main/frontend/src/lint/debt.test.ts");
        assertThat(tree.size())
                .as("the SPA floor must sit UNDER the live population, or every build is red for a"
                    + " reason that is not about the tree")
                .isGreaterThanOrEqualTo(SuiteCoverageGuard.FRONTEND_TEST_FILE_FLOOR.value());
        assertThat(tree)
                .as("every member is a repo-relative POSIX path -- the exact spelling"
                    + " suiteRecorder.ts writes -- because the comparison is a set of strings and a"
                    + " separator drift makes EVERY file look absent")
                .allSatisfy(member -> assertThat(member).startsWith("src/main/frontend/")
                        .doesNotContain("\\"));
        assertThat(tree).noneMatch(member -> member.contains("/node_modules/"));
    }

    // ------------------------------------------------------------------ the decision

    @Test
    void refusesARunWhoseExecutedSetIsAStrictSubsetOfItsTreeAndNamesWhatIsAbsent() {
        var verdict = SuiteCoverage.verdict(ARM,
                run(Set.of("a", "b", "c", "d"), Set.of("a", "b", "d")));

        assertThat(verdict.refused()).isTrue();
        assertThat(verdict.refusals()).singleElement().asString()
                .contains("INCOMPLETE RUN: 1 of 4 test files under some/root were never executed")
                .contains("the fork in the road")
                .contains("  c");
        assertThat(verdict.status()).isEqualTo("INCOMPLETE (1 absent)");
    }

    @Test
    void comparesSetsAndNotSizes() {
        // Row 14. A stray file masking a missing one gives the two sets EQUAL SIZES, and every
        // version of this comparison that counted instead of differencing passed it.
        var verdict = SuiteCoverage.verdict(ARM, run(Set.of("a", "c", "x"), Set.of("a", "b", "x")));

        assertThat(verdict.treeSize()).isEqualTo(verdict.executedSize());
        assertThat(verdict.refused())
                .as("tree={a,c,x} and executed={a,b,x} have the same size and are not the same run")
                .isTrue();
        assertThat(verdict.refusals()).singleElement().asString().contains("  c");
    }

    @Test
    void treatsAStrayAsANoteAndNeverAsARefusal() {
        // Row Q8: a stale artefact is not an unexecuted test, so `executed − tree` is printed and
        // the run passes.
        var verdict = SuiteCoverage.verdict(ARM, run(Set.of("a", "b", "c"), Set.of("a", "b", "c", "z")));

        assertThat(verdict.refused()).isFalse();
        assertThat(verdict.status()).isEqualTo("complete");
        assertThat(verdict.witness()).anySatisfy(line -> assertThat(line).contains("1 member(s) ran")
                .contains("z"));
    }

    @Test
    void refusesARunWithNoRecordAndNamesEveryCauseRatherThanOne() {
        // Row 7, and the row that decides where the refusal lives at all: "the npm-test execution
        // was deleted" produces NO record, and a refusal naming one of several causes sends the
        // reader to the wrong one.
        var verdict = SuiteCoverage.verdict(ARM,
                new SuiteCoverage.Run(Set.of("a", "b", "c"), Set.of(), 0, null, null, null));

        assertThat(verdict.refused()).isTrue();
        assertThat(verdict.refusals()).singleElement().asString()
                .contains("no execution record for this run")
                .contains("cause one").contains("cause two").contains("cause three");
        assertThat(verdict.status()).isEqualTo("REFUSED: no record");
    }

    @Test
    void refusesARunThatDidNotTellItEverythingRatherThanJudgingTheGaps() {
        // Row 13. Every check below a missing input is a subset test or a `<`, both vacuous against
        // null -- so an omitted input does not loosen one check, it deletes it. In Java the failure
        // mode is a stack trace or an unboxing NPE where a sentence belongs, which is no better.
        record Case(String what, SuiteCoverage.Run run, String names) {
        }
        var malformed = List.of(
                new Case("a run with no tree",
                        new SuiteCoverage.Run(null, Set.of("a"), 1, 50, null, null), "no tree"),
                new Case("a run with no executed set",
                        new SuiteCoverage.Run(Set.of("a"), null, 1, 50, null, null), "no executed"),
                new Case("a run that never looked for a record",
                        new SuiteCoverage.Run(Set.of("a"), Set.of("a"), null, 50, null, null),
                        "no recordCount"),
                new Case("a run with no test count, on an arm that floors one",
                        new SuiteCoverage.Run(Set.of("a"), Set.of("a"), 1, null, null, null),
                        "no testCount"));

        for (var malformedCase : malformed) {
            var verdict = SuiteCoverage.verdict(ARM, malformedCase.run());
            assertThat(verdict.refused())
                    .as("verdict() accepted %s and judged the rest anyway", malformedCase.what())
                    .isTrue();
            assertThat(verdict.refusals()).singleElement().asString()
                    .contains(malformedCase.names())
                    .contains("Nothing below was compared");
            assertThat(verdict.status()).isEqualTo("REFUSED: malformed call");
        }

        // …and the arm WITHOUT a test floor must not demand a test count, or the JVM arm refuses
        // every build over a number nothing hands it.
        var noTestFloor = new SuiteCoverage.Arm("probe", "[probe]", "test classes", "some/root",
                "target/missing.txt", new SuiteCoverage.Floor(3, "the bound went.", List.of("c")),
                null, List.of("help"), List.of("cause"));
        assertThat(SuiteCoverage.verdict(noTestFloor,
                new SuiteCoverage.Run(Set.of("a", "b", "c"), Set.of("a", "b", "c"), 1, null, null, null))
                .refused()).isFalse();
    }

    @Test
    void refusesAPopulationUnderEitherFloorWithTheHouseMessage() {
        var collapsedBound = SuiteCoverage.verdict(ARM, run(Set.of("a", "b"), Set.of("a", "b")));
        assertThat(collapsedBound.refused())
                .as("a collapsed bound produces an EMPTY missing set -- the most confident possible"
                    + " green line about a tree the guard has stopped seeing")
                .isTrue();
        assertThat(collapsedBound.refusals()).singleElement().asString()
                .contains("the scan saw 2, under the floor of 3")
                .contains("Do not lower a floor to make a run pass");

        var everyModuleEmpty = SuiteCoverage.verdict(ARM,
                new SuiteCoverage.Run(Set.of("a", "b", "c"), Set.of("a", "b", "c"), 1, 9, null, null));
        assertThat(everyModuleEmpty.refused())
                .as("every module present and nothing inside them is a green run with no other"
                    + " refusal in the table")
                .isTrue();
        assertThat(everyModuleEmpty.refusals()).singleElement().asString()
                .contains("the scan saw 9, under the floor of 10");
    }

    /**
     * <strong>AC1 at scale, which is where it failed.</strong> Both floors used to be judged before
     * the set difference, so a run that executed 15 of 74 modules was refused as
     * {@code REFUSED: every member empty} — over 15 modules that were full of tests — and named
     * <em>none</em> of the 59 files that never ran, which is the one thing AC1 asks for. The tree
     * floor stays first (a collapsed bound makes the difference empty and the green line confident);
     * the test-count floor now comes after, where "every member" is a set the run has been shown to
     * have executed.
     */
    @Test
    void namesTheAbsentMembersOfALargeTruncationRatherThanCallingThemEmpty() {
        var tree = new TreeSet<String>();
        for (var i = 0; i < 74; i++) {
            tree.add(String.format("src/main/frontend/src/f%02d.test.ts", i));
        }
        var executed = new TreeSet<>(tree).headSet("src/main/frontend/src/f15.test.ts");

        // 15 modules, 185 tests: under the arm's test floor AND short of its tree. The measured
        // shape from the gate's plant, with this fixture's floors.
        var verdict = SuiteCoverage.verdict(ARM,
                new SuiteCoverage.Run(tree, executed, 1, 5, null, null));

        assertThat(verdict.status())
                .as("a truncated plan is an INCOMPLETE run, not an empty one: 'every member empty' is"
                    + " a claim about members that RAN, and 59 of these did not")
                .isEqualTo("INCOMPLETE (59 absent)");
        assertThat(verdict.refusals()).singleElement().asString()
                .as("the absent files are the message; a refusal that names none of them sends the"
                    + " reader to count modules by hand")
                .contains("INCOMPLETE RUN: 59 of 74 test files")
                .contains("src/main/frontend/src/f15.test.ts")
                .contains("and 47 more, not listed here; all of them are in target/missing.txt")
                .doesNotContain("under the floor of 10");
        assertThat(verdict.refusals().getFirst().lines().count())
                .as("a failure message names the action in at most 25 lines; MAX_LISTED is what"
                    + " bounds this one")
                .isLessThanOrEqualTo(25);
    }

    /**
     * <strong>Every floor in this guard prescribes an action its own reader can perform.</strong>
     *
     * <p>The category is the floors, not the arms: all three borrowed {@link Population}'s message
     * whole, so a vitest bound that collapsed told the reader to run {@code mvnw clean compile} and
     * "fix Doors" — three causes about {@code target/classes}, {@code HamstrackApplication} and a
     * bytecode harness, none of which either arm touches. The frame is still shared; the remedies
     * are the floor's own, and {@link SuiteCoverage.Floor} cannot be constructed without them —
     * which is a compact constructor since round 3, not a sentence: this loop iterates
     * {@code causes()}, so an empty list iterated zero times and passed.
     *
     * <p>The arms come from {@link SuiteCoverageGuard#arms}, the same enumeration {@code judge}
     * runs, because the hand-written copy that used to be here meant a third suite would reach the
     * guard and not this test — and the floor of 3 below cannot notice a member nobody handed it.
     */
    @Test
    void givesEveryFloorRemediesItsOwnReaderCanPerform() {
        record Subject(SuiteCoverage.Arm arm, SuiteCoverage.Floor floor, SuiteCoverage.Run run) {
        }
        var buildDirectory = Path.of("target");
        var records = SuiteRunRecord.directory(buildDirectory);
        var arms = SuiteCoverageGuard.arms(buildDirectory, records, "probe-run", "src/main/frontend");

        var subjects = new ArrayList<Subject>();
        for (var arm : arms) {
            subjects.add(new Subject(arm, arm.treeFloor(),
                    new SuiteCoverage.Run(members(arm.treeFloor().value() - 1),
                            Set.of(), 1, arm.testFloor() == null ? null : arm.testFloor().value(),
                            null, null)));
            if (arm.testFloor() != null) {
                var whole = members(arm.treeFloor().value());
                subjects.add(new Subject(arm, arm.testFloor(), new SuiteCoverage.Run(whole, whole, 1,
                        arm.testFloor().value() - 1, null, null)));
            }
        }
        assertThat(subjects)
                .as("two arms, three floors: a floor added without a fixture here is a floor whose"
                    + " refusal nobody has read")
                .hasSizeGreaterThanOrEqualTo(3);

        for (var subject : subjects) {
            assertThat(subject.floor().causes())
                    .as("%s's floor prescribes nothing, and the loop below would iterate it zero"
                        + " times and pass -- this assertion is that loop's own floor",
                            subject.arm().key())
                    .isNotEmpty();
            var refusal = SuiteCoverage.verdict(subject.arm(), subject.run()).refusals();
            assertThat(refusal).as("%s did not refuse under its own floor", subject.arm().key())
                    .singleElement().asString()
                    .contains(subject.arm().tag())
                    .contains("Do not lower a floor to make a run pass")
                    .doesNotContain(Population.POPULATION_CAUSES.getFirst())
                    .doesNotContain("fix Doors")
                    .doesNotContain("mvnw clean compile");
            for (var cause : subject.floor().causes()) {
                assertThat(refusal.getFirst())
                        .as("%s's floor drops one of its own causes, so a reader gets some of the"
                            + " remedies and has to guess the rest", subject.arm().key())
                        .contains(cause);
            }
            assertThat(refusal.getFirst().lines().count())
                    .as("%s's floor message is over the house's 25 lines", subject.arm().key())
                    .isLessThanOrEqualTo(25);
        }
    }

    /**
     * <strong>A refusal that prescribes nothing cannot be built.</strong>
     *
     * <p>The test above iterates the remedies; an empty list iterates zero times, so the seal that
     * exists to demand them passed a floor that had none — measured 2026-09-11, {@code
     * JVM_TREE_FLOOR} rebuilt with {@code List.of()} gave {@code Tests run: 22, Failures: 0} and
     * BUILD SUCCESS while two javadocs and the proposal's AC4 said such a floor could not exist. A
     * scan over floors with no floor of its own is this ticket's own subject, one level up.
     *
     * <p>The category is <em>every list a refusal message iterates</em>, and it is enumerated from
     * the record components rather than from a sentence: {@code Floor.causes} was the finding,
     * {@code Arm.incompleteHelp} and {@code Arm.noRecordCauses} are its siblings — an arm with an
     * empty {@code noRecordCauses} prints "Exactly one of these is true:" and then stops. A fourth
     * list joins this test by existing.
     */
    @Test
    void refusesAFloorOrAnArmThatPrescribesNothing() throws ReflectiveOperationException {
        record Prescription(Class<?> owner, int index, String name, Object[] valid) {
        }
        var validFloor = new Object[]{3, "the bound went.", List.of("a cause, in the second person")};
        var validArm = new Object[]{"probe", "[probe]", "test files", "some/root",
                "target/missing.txt", new SuiteCoverage.Floor(3, "the bound went.",
                List.of("a cause")), null, List.of("the fork in the road"), List.of("one cause")};

        var subjects = new ArrayList<Prescription>();
        for (var owner : List.of(SuiteCoverage.Floor.class, SuiteCoverage.Arm.class)) {
            var valid = owner == SuiteCoverage.Floor.class ? validFloor : validArm;
            var components = owner.getRecordComponents();
            for (var index = 0; index < components.length; index++) {
                if (components[index].getType() == List.class) {
                    subjects.add(new Prescription(owner, index, components[index].getName(), valid));
                }
            }
        }
        assertThat(subjects)
                .as("the remedy lists were enumerated from the record components and fewer than"
                    + " three came back -- Floor.causes, Arm.incompleteHelp and Arm.noRecordCauses"
                    + " are the live ones, so this scan has stopped seeing its own subject")
                .hasSizeGreaterThanOrEqualTo(3);

        for (var subject : subjects) {
            for (var prescribesNothing : List.of(List.of(), List.of("   "))) {
                var arguments = subject.valid().clone();
                arguments[subject.index()] = prescribesNothing;
                var thrown = catchThrowable(() -> canonical(subject.owner()).newInstance(arguments));
                assertThat(thrown)
                        .as("%s.%s accepted %s: a refusal built from it names no action at all, and"
                            + " the seal that reads it iterates the list -- so it passes",
                                subject.owner().getSimpleName(), subject.name(), prescribesNothing)
                        .isInstanceOf(InvocationTargetException.class)
                        .hasCauseInstanceOf(IllegalArgumentException.class);
                assertThat(thrown.getCause())
                        .hasMessageContaining(subject.owner().getSimpleName() + "." + subject.name());
            }
        }
    }

    /** The canonical constructor of a record, by its components' types. */
    private static Constructor<?> canonical(Class<?> record) throws NoSuchMethodException {
        var types = Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getType).toArray(Class<?>[]::new);
        return record.getDeclaredConstructor(types);
    }

    /**
     * An input the POM stopped passing is <strong>judged</strong>, in the guard's own voice.
     *
     * <p>It used to throw {@code IllegalArgumentException} out of a {@code required()} helper: a
     * Java stack trace where a sentence belongs, no summary file, and Maven's last line pointing the
     * reader at a {@code [test-tree]} report that was never printed. {@link SuiteCoverage}'s first
     * property names that exact shape as a defect, and the vitest arm already did it right.
     */
    @Test
    void judgesAnInputThePomStoppedPassingRatherThanThrowingAtIt() throws IOException {
        var source = Files.readString(GUARD_SOURCE, StandardCharsets.UTF_8);
        assertThat(source)
                .as("a missing <arg> must not reach the reader as a stack trace: route it through a"
                    + " judged refusal naming the argument, the way the vitest arm does")
                .doesNotContain("throw new IllegalArgumentException");

        var refusal = SuiteCoverageGuard.missingPomArgument("[vitest-tree]", "frontendRoot",
                "the SPA root this arm is answerable for");
        assertThat(refusal)
                .contains("[vitest-tree] REFUSED: the POM passed no frontendRoot")
                .contains("<arg value=\"frontendRoot=...\"/>")
                .contains("test-tree-coverage-guard");
        assertThat(refusal.lines().count()).isLessThanOrEqualTo(25);
    }

    /**
     * <strong>§7 row 12 — a tree that cannot be derived is a refusal, never a stand-down.</strong>
     *
     * <p>This is the one row whose branch had no fixture: the {@code catch} lives inside
     * {@link SuiteCoverageGuard#vitestVerdict} and the gate drove its neighbours instead, so "git
     * unavailable / non-checkout build" was held by a reading of the code. It is driven here through
     * the real branch — a {@code frontendRoot} outside the repository makes {@code git ls-files}
     * exit 128 ({@code fatal: … is outside repository}, measured 2026-09-11), {@code
     * PublishedCredentials.git} turns that into an {@code IllegalStateException}, and the arm must
     * answer with its own sentence rather than with a stack trace or, worse, an empty tree.
     *
     * <p>An empty tree would be the dangerous outcome and is why this is a refusal: {@code
     * tree - executed} over nothing is empty, which is the confident green line the whole mechanism
     * exists to delete. The file floor would also catch it — this makes sure the reader is told
     * <em>why</em>, not just that the number is low.
     */
    @Test
    void refusesTheSpaArmWhenItsTreeCannotBeDerivedAtAll() throws IOException {
        var outside = Path.of("").toAbsolutePath().resolve("..")
                .resolve("hd301-not-a-checkout").normalize().toString();
        var buildDirectory = Path.of("target");
        var records = SuiteRunRecord.directory(buildDirectory);
        var arm = SuiteCoverageGuard.vitestArm(buildDirectory, records, "probe-run", outside);

        var verdict = SuiteCoverageGuard.vitestVerdict(Map.of("frontendRoot", outside), arm, records,
                "probe-run");

        assertThat(verdict.refused())
                .as("a tree that could not be read must never be a stand-down: the difference"
                    + " `tree - executed` over an empty tree is empty, which prints the most"
                    + " confident possible green line about a tree nobody looked at")
                .isTrue();
        assertThat(verdict.status()).isEqualTo("REFUSED: the tree could not be read");
        assertThat(verdict.refusals()).singleElement().asString()
                .contains("[vitest-tree] REFUSED: the bound under " + outside)
                .contains("git ls-files")
                .contains("This step therefore needs a checkout");
        assertThat(verdict.refusals().getFirst().lines().count()).isLessThanOrEqualTo(25);
    }

    /** {@code n} members of a tree, for a fixture that only cares how many there are. */
    private static Set<String> members(int n) {
        var members = new TreeSet<String>();
        for (var i = 0; i < n; i++) {
            members.add(String.format("member-%04d", i));
        }
        return members;
    }

    @Test
    void standsAnArmDownOnlyOnAnExplicitSwitchAndSaysWhichOne() {
        var disarmed = SuiteCoverage.verdict(ARM,
                new SuiteCoverage.Run(null, null, null, null, "-Dfrontend.skip=true", null));

        assertThat(disarmed.refused()).isFalse();
        assertThat(disarmed.status()).isEqualTo("disarmed by -Dfrontend.skip=true");
        assertThat(disarmed.witness()).singleElement().asString()
                .contains("coverage check disarmed by -Dfrontend.skip=true");
        assertThat(disarmed.summaryRow()).contains("disarmed by -Dfrontend.skip=true");

        // A blank switch is "the developer passed no filter", not a stand-down.
        assertThat(SuiteCoverage.verdict(ARM,
                new SuiteCoverage.Run(Set.of("a", "b", "c"), Set.of("a", "b", "c"), 1, 50, "  ", null))
                .status()).isEqualTo("complete");
    }

    // ------------------------------------------------------------------ arming, per arm

    @Test
    void treatsAnUnresolvedMavenPropertyAsUnset() {
        // How "the developer passed no filter" reaches the guard: Maven leaves an undefined property
        // as its own placeholder rather than blanking it. Reading that literally would arm the guard
        // with a filter named `${test}` on every full run and disarm it forever.
        var parsed = SuiteCoverageGuard.parse(new String[]{
                "test=${test}", "groups=", "skipTests=false", "frontend.skip=false",
                "runId=2026-09-04T21:00:00Z"});

        assertThat(parsed).doesNotContainKeys("test", "groups");
        assertThat(parsed).containsEntry("skipTests", "false")
                .containsEntry("frontend.skip", "false")
                .containsEntry("runId", "2026-09-04T21:00:00Z");
    }

    @Test
    void armsEachSuiteOnItsOwnSwitchesLestAnArmGoPermanentlyQuiet() {
        assertThat(narrowing(SuiteCoverageGuard.JVM_NARROWING_SWITCHES, Map.of()))
                .as("nothing was narrowed, so the run is answerable for the whole tree").isNull();
        assertThat(narrowing(SuiteCoverageGuard.JVM_NARROWING_SWITCHES, Map.of("skipTests", "false")))
                .as("the switch is present and off; that is still a full run").isNull();
        assertThat(narrowing(SuiteCoverageGuard.VITEST_NARROWING_SWITCHES,
                Map.of("frontend.skip", "false")))
                .as("frontend.skip has a POM-declared default, so it ALWAYS arrives -- reading the"
                    + " literal `false` as a switch would disarm this arm on every build")
                .isNull();

        // The asymmetry. -Dtest=Foo narrows surefire and leaves the whole vitest suite running, so
        // that run is still answerable for the SPA tree; -Dfrontend.skip=true is the exact reverse.
        // Getting this the same on both lists is how an arm comes to be permanently off.
        assertThat(narrowing(SuiteCoverageGuard.JVM_NARROWING_SWITCHES, Map.of("test", "FooTest")))
                .contains("FooTest");
        assertThat(narrowing(SuiteCoverageGuard.VITEST_NARROWING_SWITCHES, Map.of("test", "FooTest")))
                .as("-Dtest= does not reach frontend-maven-plugin; that run pays the whole 45-70s"
                    + " vitest suite and must answer for it")
                .isNull();
        assertThat(narrowing(SuiteCoverageGuard.VITEST_NARROWING_SWITCHES,
                Map.of("frontend.skip", "true"))).isNotNull();
        assertThat(narrowing(SuiteCoverageGuard.JVM_NARROWING_SWITCHES,
                Map.of("frontend.skip", "true")))
                .as("-Dfrontend.skip= does not reach surefire")
                .isNull();

        // Shared off-switches, and the one that only looks shared: frontend-maven-plugin 1.15.1's
        // npm goal binds ${skipTests} and nothing else (read from its plugin descriptor), so
        // -Dmaven.test.skip=true silences surefire and NOT the vitest suite.
        assertThat(narrowing(SuiteCoverageGuard.JVM_NARROWING_SWITCHES, Map.of("skipTests", "true")))
                .isNotNull();
        assertThat(narrowing(SuiteCoverageGuard.VITEST_NARROWING_SWITCHES, Map.of("skipTests", "true")))
                .isNotNull();
        assertThat(narrowing(SuiteCoverageGuard.JVM_NARROWING_SWITCHES,
                Map.of("maven.test.skip", "true"))).isNotNull();
        assertThat(narrowing(SuiteCoverageGuard.VITEST_NARROWING_SWITCHES,
                Map.of("maven.test.skip", "true")))
                .as("the npm goal does not read maven.test.skip, so the vitest suite really runs")
                .isNull();
        assertThat(narrowing(SuiteCoverageGuard.JVM_NARROWING_SWITCHES, Map.of("groups", "slow")))
                .isNotNull();
    }

    private static String narrowing(List<String> switches, Map<String, String> arguments) {
        return SuiteCoverageGuard.firstNarrowingSwitch(arguments, switches);
    }

    // ------------------------------------------------------------------ records & witness

    @Test
    void keepsTheTwoSuitesRecordsApartAndPrunesBoth() {
        // Row 16: freshness is an IDENTITY, never a timestamp, so a leftover from an earlier run,
        // an IDE run or a crashed run is ignored on sight rather than reasoned about.
        var runId = "2026-09-11T02:58:12Z";
        assertThat(SuiteRunRecord.fileNamePrefix(runId)).isEqualTo("executed-2026-09-11T02-58-12Z-");
        assertThat(SuiteRunRecord.vitestFileNamePrefix(runId)).isEqualTo("vitest-2026-09-11T02-58-12Z-");
        assertThat(SuiteRunRecord.vitestFileNamePrefix(null))
                .as("a bare `npm test` writes under the outside-maven identity, which the guard"
                    + " ignores on sight and prunes on its next run")
                .isEqualTo("vitest-outside-maven-");

        assertThat(SuiteRunRecord.isRecordFile("executed-x-1.txt")).isTrue();
        assertThat(SuiteRunRecord.isRecordFile("vitest-x-1.txt"))
                .as("a record shape the prune does not recognise outlives every run that could"
                    + " explain it -- which is how surefire-reports became a pile")
                .isTrue();
        assertThat(SuiteRunRecord.isRecordFile("test-tree-summary.md")).isFalse();
    }

    @Test
    void keepsTheFourConstantsTheRecorderReimplementsEqualToTheJavaOnes() throws IOException {
        // The recorder cannot call SuiteRunRecord, so it spells the convention again in
        // TypeScript: the directory, the prefix, the no-Maven identity and the summary marker. A
        // drift in any of the four is silent in the worst way -- the record is written somewhere
        // or under a name the guard does not look at, and the refusal says "the suite did not
        // run" about a suite that ran in full. This is the only thing that compares them, and it
        // is on the Java side because the SPA suite may read pom.xml and nothing else outside its
        // root.
        var recorder = Files.readString(
                Path.of("src/main/frontend/src/test/suiteRecorder.ts"), StandardCharsets.UTF_8);

        assertThat(recorder).contains("const DIRECTORY_NAME = '" + SuiteRunRecord.DIRECTORY_NAME + "'");
        assertThat(recorder).contains("const NO_MAVEN_RUN = '" + SuiteRunRecord.NO_MAVEN_RUN + "'");
        assertThat(recorder).contains("const SUMMARY_PREFIX = '" + SuiteCoverage.SUMMARY_PREFIX + "'");
        assertThat(recorder)
                .as("the file-name prefix the recorder writes must be the one the guard looks for")
                .contains("const FILE_PREFIX = '" + SuiteRunRecord.VITEST_PREFIX + "'");
        assertThat(recorder)
                .as("the recorder must keep using the hooks that exist: onFinished is deprecated"
                    + " in the installed vitest 3.2.7 and is what marginReporter still uses")
                .contains("onTestModuleEnd").contains("onTestRunEnd").doesNotContain("onFinished(");
    }

    @Test
    void readsTheTestCountFromTheSummaryLineAndNeverIntoTheComparison() {
        var lines = List.of("src/main/frontend/src/a.test.ts", "src/main/frontend/src/b.test.ts", "",
                "#summary modules=2 tests=41 reason=passed");

        assertThat(SuiteCoverage.membersIn(lines))
                .as("the #summary line is not a test file and must not become a stray")
                .containsExactly("src/main/frontend/src/a.test.ts", "src/main/frontend/src/b.test.ts");
        assertThat(SuiteCoverage.summaryTestCount(lines)).isEqualTo(41);
        assertThat(SuiteCoverage.summaryTestCount(List.of("a.test.ts")))
                .as("an interrupted run leaves no summary line; that is a null the arm refuses as a"
                    + " missing input, not a 0 it compares against the floor")
                .isNull();
        assertThat(SuiteCoverage.summaryTestCount(
                List.of("#summary modules=1 tests=10 reason=passed",
                        "#summary modules=1 tests=5 reason=passed")))
                .as("several records of one run are unioned, exactly as the JVM half unions its"
                    + " per-fork files")
                .isEqualTo(15);
    }

    @Test
    void leavesBothCountsWhereAReaderFindsThemOnEveryPath() {
        // AC7. The summary is the witness for the drift the floors deliberately do not catch, and
        // it has to exist on the red run as much as on the green one.
        var complete = SuiteCoverage.verdict(ARM, run(Set.of("a", "b", "c"), Set.of("a", "b", "c")));
        assertThat(complete.summaryRow()).isEqualTo("| probe | 3 | 3 | 50 | complete |");

        var incomplete = SuiteCoverage.verdict(ARM, run(Set.of("a", "b", "c", "d"), Set.of("a", "b", "c")));
        assertThat(incomplete.summaryRow()).isEqualTo("| probe | 3 | 4 | 50 | INCOMPLETE (1 absent) |");

        var disarmed = SuiteCoverage.verdict(ARM,
                new SuiteCoverage.Run(null, null, null, null, "-DskipTests=true", null));
        assertThat(disarmed.summaryRow())
                .as("a disarmed arm prints em-dashes rather than zeroes: it counted nothing, and a"
                    + " 0 in this column reads as a measurement")
                .isEqualTo("| probe | — | — | — | disarmed by -DskipTests=true |");

        assertThat(SuiteCoverage.summaryHeader()).hasSize(2);
        assertThat(SuiteCoverage.summaryHeader().getFirst()).contains("| arm |").contains("| tests |");

        // …and the file says WHICH run wrote it. Four of the five red paths stop Maven before this
        // step, so an unstamped file from the previous local build reads exactly like this run's.
        assertThat(SuiteCoverage.summaryIdentity("2026-09-11T02-58-12Z", "2026-09-11T03:01:00Z"))
                .as("a summary a reader cannot date to a run is a summary about no run")
                .contains("2026-09-11T02-58-12Z").contains("2026-09-11T03:01:00Z")
                .contains("leftover");
    }

    @Test
    void exitsOnExactlyWhatTheVerdictReturnedAndOnNothingElse() throws IOException {
        // AC5, and HD-300 measured what a second term on that line costs: a text assertion pinned
        // it, the first term was deleted, a real violation was PRINTED, the run exited 0 and all 21
        // tests stayed green. One term is a line a text match can pin in full; a second condition
        // belongs inside verdict(), where the fixtures above can reach it.
        var source = Files.readString(GUARD_SOURCE, StandardCharsets.UTF_8);

        assertThat(source)
                .as("SuiteCoverageGuard no longer exits on exactly what SuiteCoverage.verdict"
                    + " returned. Printing a refusal and exiting 0 is the same green build as not"
                    + " checking at all, and a second term on this line is a decision no fixture"
                    + " can reach. Write `System.exit(refusals.isEmpty() ? 0 : 1);` and put any new"
                    + " condition inside verdict().")
                .containsPattern("(?m)^\\s*System\\.exit\\(refusals\\.isEmpty\\(\\) \\? 0 : 1\\);$");
        assertThat(source.split("System\\.exit\\(").length - 1)
                .as("more than one System.exit in the guard: the exit condition is no longer one"
                    + " decision in one place")
                .isEqualTo(1);
        assertThat(source)
                .as("both arms must go through the one decision, or the category has two shapes"
                    + " that drift")
                .contains("SuiteCoverage.verdict(arm,");
    }
}
