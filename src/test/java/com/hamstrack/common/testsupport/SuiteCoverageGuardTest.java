package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seals the derivation the HD-265 coverage guard is built on, because every way that derivation can
 * go wrong is quiet.
 *
 * <p>{@link SuiteCoverageGuard} fails a build that executed fewer test classes than the tree
 * contains. Its bound is the set of test-named sources under {@code src/test/java} — so a bug that
 * makes that set SMALLER does not turn anything red, it just narrows what the guard is willing to
 * ask for. Drop one of Surefire's four default include shapes, or start excusing classes on some
 * clever heuristic, and the guard keeps printing a confident line about a tree it has quietly
 * stopped seeing whole: a smaller, better-hidden copy of the failure it was written for. That is
 * what is checked here.
 *
 * <p>This class is, of course, selectable — the truncation it descends from could drop it. That is
 * not a hole in the design: the guard's own refusal lives in a Maven step precisely because it must
 * survive that, and this is the second-order seal on the guard's arithmetic, not the guard.
 */
class SuiteCoverageGuardTest {

    private static final Path TEST_SOURCES = Path.of("src", "test", "java");

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
    }

    @Test
    void treatsAnUnresolvedMavenPropertyAsUnset() {
        // How "the developer passed no filter" reaches the guard: Maven leaves an undefined property
        // as its own placeholder rather than blanking it. Reading that literally would arm the guard
        // with a filter named `${test}` on every full run and disarm it forever.
        var parsed = SuiteCoverageGuard.parse(new String[]{
                "test=${test}", "groups=", "skipTests=false", "runId=2026-09-04T21:00:00Z"});

        assertThat(parsed).doesNotContainKeys("test", "groups");
        assertThat(parsed).containsEntry("skipTests", "false").containsEntry("runId", "2026-09-04T21:00:00Z");
    }

    @Test
    void armsOnAFullRunAndStandsDownForEverySwitchThatNarrowsThePlan() {
        assertThat(SuiteCoverageGuard.firstNarrowingSwitch(Map.of()))
                .as("nothing was narrowed, so the run is answerable for the whole tree").isNull();
        assertThat(SuiteCoverageGuard.firstNarrowingSwitch(Map.of("skipTests", "false")))
                .as("the switch is present and off; that is still a full run").isNull();

        assertThat(SuiteCoverageGuard.firstNarrowingSwitch(Map.of("test", "FooTest")))
                .contains("FooTest");
        assertThat(SuiteCoverageGuard.firstNarrowingSwitch(Map.of("skipTests", "true"))).isNotNull();
        assertThat(SuiteCoverageGuard.firstNarrowingSwitch(Map.of("maven.test.skip", "true"))).isNotNull();
        assertThat(SuiteCoverageGuard.firstNarrowingSwitch(Map.of("groups", "slow"))).isNotNull();
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

        assertThat(expected.keySet())
                .as("support classes must stay outside Surefire's four name patterns -- one named"
                    + " into them is demanded by the guard on every build and can only be answered"
                    + " by renaming it")
                .doesNotContain(SuiteRunRecord.class.getName(),
                        SuiteCoverageGuard.class.getName(),
                        ExecutedTestClassRecorder.class.getName());
    }
}
