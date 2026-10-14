package com.hamstrack.common.testsupport;

import com.hamstrack.ops.OpsYaml;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

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

    /**
     * <strong>HD-343 &mdash; the staleness step is wired, early, unskippable, and covers every
     * report the guard writes.</strong>
     *
     * <h2>Why this exists</h2>
     * The guard is bound to {@code test} and is therefore <em>not reached</em> when Surefire fails
     * the build first, so its reports survive from the previous run. Measured on HD-343's own
     * negative control: after the fork died with {@code OutOfMemoryError}, {@code
     * target/test-tree-summary.md} was byte-identical to the previous green run's table, mtime
     * unchanged, still saying {@code | jvm | 310 | 310 | - | complete |}. The remedy is a
     * {@code test-tree-summary-staleness} antrun execution that deletes those reports before
     * Surefire can fail, so a run that dies early leaves them ABSENT &mdash; which is the case
     * {@code build.yml} already turns into an explicit error.
     *
     * <p>That execution arrived with <strong>no seal of any kind</strong>: it appeared in exactly
     * two places in the repository, {@code pom.xml} and one javadoc sentence. Both of its
     * load-bearing properties are ones this project has already lost once, one execution over
     * &mdash; the phase binding and the {@code <skip>false</skip>} pin, the latter being what stops
     * {@code -Dmaven.antrun.skip=true} from exiting BUILD SUCCESS with nothing compared.
     *
     * <h2>Why here and not in {@code suiteGuard.test.ts} beside members 1-7</h2>
     * The SPA file holds member 7 because of a <em>mutual custody</em> argument: the antrun guard
     * keeps the vitest suite whole, so the vitest suite is made to keep the antrun guard wired, and
     * neither can be removed in silence. That argument does not apply here &mdash; this step guards
     * neither suite, it guards a <em>report</em>, so custody from the Surefire side has no
     * circularity to escape: under {@code verify} this class runs, and in the one case it does not
     * ({@code -DskipTests}) the deletion has already happened and there is no staleness to catch.
     * Keeping it on the Java side also follows the constraint stated at
     * {@link #keepsTheFourConstantsTheRecorderReimplementsEqualToTheJavaOnes} &mdash; cross-file
     * comparisons live here.
     *
     * <p><strong>A member 8 in the SPA file would be worse than absent</strong>, and this paragraph
     * says so because an earlier draft of it called such a member a harmless cosmetic addition. The
     * population below is <em>derived</em> from {@link SuiteCoverageGuard#SUMMARY_FILE_NAME} and
     * every {@link SuiteCoverage.Arm#spillFileName()}; a TypeScript member could only carry a
     * hand-copied list of file names, i.e. a second enumeration maintained by memory, which is the
     * exact defect class this ticket exists to remove &mdash; added in the name of symmetry.
     *
     * <h2>The population is derived, not listed</h2>
     * The artefacts are read off the guard itself &mdash; {@link SuiteCoverageGuard#SUMMARY_FILE_NAME}
     * plus every {@link SuiteCoverage.Arm#spillFileName()} &mdash; so a third arm's spill file joins
     * this rule by existing rather than by being remembered. That is the half that would otherwise
     * rot: HD-343 first deleted only the summary, leaving both spill files stale <em>and</em>
     * removing the one file that carried a run identity to date them by.
     */
    @Test
    void theStalenessStepDeletesEveryReportTheGuardWritesBeforeSurefireCanFail() throws IOException {
        var pom = pomWithoutComments();

        var execution = java.util.regex.Pattern
                .compile("(?s)<execution>\\s*<id>test-tree-summary-staleness</id>.*?</execution>")
                .matcher(pom);

        assertThat(execution.find())
                .as("""
                    The `test-tree-summary-staleness` execution is gone from pom.xml, so the guard's
                    reports survive a run that dies before the guard and are read as that run's
                    verdict. Measured once already: an OutOfMemoryError left a `complete` table for
                    a run that executed 160 tests of 2064. Restore it: maven-antrun-plugin,
                    phase process-test-classes, <skip>false</skip>, deleting every file named by
                    SuiteCoverageGuard.SUMMARY_FILE_NAME and SuiteCoverage.Arm#spillFileName.""")
                .isTrue();
        var block = execution.group();

        assertThat(PHASES_BEFORE_SUREFIRE)
                .as("""
                    The staleness execution is not bound to a phase that runs BEFORE Surefire, so it
                    deletes last run's reports after this run could already have failed - which is
                    the staleness it exists to remove, merely moved later. Its phase must be one of
                    the pre-test phases listed here. Declared block:
                    %s""".formatted(block.strip()))
                .anyMatch(phase -> block.contains("<phase>" + phase + "</phase>"));

        assertThat(block)
                .as("""
                    The staleness execution no longer pins <skip>false</skip>, so
                    -Dmaven.antrun.skip=true switches it off and the stale reports come back on
                    exactly the invocation least likely to be looked at. The guard below pins it for
                    the same reason, and that flag was measured exiting BUILD SUCCESS with nothing
                    compared (HD-301 round 2).""")
                .contains("<skip>false</skip>");

        var artefacts = new TreeSet<String>();
        artefacts.add(SuiteCoverageGuard.SUMMARY_FILE_NAME);
        artefacts.add(fileNameOf(SuiteCoverageGuard.jvmArm(
                Path.of("target"), Path.of("target"), "probe").spillFileName()));
        artefacts.add(fileNameOf(SuiteCoverageGuard.vitestArm(
                Path.of("target"), Path.of("target"), "probe", "src/main/frontend").spillFileName()));

        assertThat(artefacts)
                .as("The artefact population collapsed, so this rule had nothing to demand.")
                .hasSizeGreaterThanOrEqualTo(3);

        // THE FILESET'S includes ATTRIBUTE, NOT THE WHOLE BLOCK. Checking the block was green with
        // a spill file deleted from the fileset, because the <fail> message beside it NAMES all
        // three files as part of its remedy text - the check was satisfied by the refusal's own
        // prose, which is the identical defect this ticket already fixed once in JvmBoundsTest.
        // Caught only by planting it. The deletion is what must list the file, so that is what is
        // read.
        var includes = java.util.regex.Pattern
                .compile("(?s)<fileset\\b[^>]*\\bincludes\\s*=\\s*\"([^\"]*)\"").matcher(block);
        var deleted = new TreeSet<String>();
        while (includes.find()) {
            Arrays.stream(includes.group(1).split(",")).map(String::strip)
                    .filter(entry -> !entry.isEmpty()).forEach(deleted::add);
        }

        assertThat(deleted)
                .as("""
                    No <fileset includes="..."> could be read out of the staleness execution, so
                    this rule cannot see what the step actually deletes. If the deletion was
                    rewritten in another form (a <delete file="..."> per artefact, say), teach this
                    assertion that form - do not fall back to searching the whole execution block,
                    which the <fail> message's remedy text satisfies on its own. Block:
                    %s""".formatted(block.strip()))
                .isNotEmpty();

        var uncovered = artefacts.stream().filter(name -> !deleted.contains(name)).toList();
        assertThat(uncovered)
                .as("""
                    A report the guard writes is NOT deleted by the staleness step, so after a run
                    that dies before the guard it survives with last run's content. The spill files
                    are the dangerous ones: unlike the summary they carry NO run-identity line, so a
                    stale one is an undated list of "absent test classes" with nothing to date it
                    by. The population is derived from the guard (SUMMARY_FILE_NAME plus every
                    Arm#spillFileName), so add the name below to that execution's fileset.
                    Uncovered:""")
                .isEmpty();
    }

    /**
     * <strong>HD-311 &mdash; an antrun step that forks a class out of {@code target} decides for
     * itself what an absent class file means, and prints whichever answer it reaches.</strong>
     *
     * <h2>What went wrong</h2>
     * {@code -Dmaven.test.skip=true} skips test <em>compilation</em>, so
     * {@code target/test-classes} &mdash; and with it {@link SuiteCoverageGuard}'s own class file
     * &mdash; is never produced. The {@code test-tree-coverage-guard} fork then died with
     * {@code Could not find or load main class} at WARNING, {@code Java Result: 1} at ERROR, and a
     * final {@code <fail>} that sent its reader to <em>"the {@code [test-tree]} report above"</em>
     * &mdash; a report that had never been printed. Measured 2026-10-13 before the fix, on a tree
     * with {@code target/test-classes} removed: BUILD FAILURE, exit 1. The guard's own judgement
     * about that flag was already correct ({@code maven.test.skip} is on
     * {@link SuiteCoverageGuard#JVM_NARROWING_SWITCHES} and arrives as an {@code <arg>}); what the
     * flag removes is its ability to be <em>asked</em>. So the decision has to be made in Ant,
     * where no class file is needed.
     *
     * <h2>Two branches, and why they must not collapse into one</h2>
     * "The binary is absent" is a different proposition from "a narrowing flag was passed". An
     * absence a flag explains is a stand-down and prints the disarm line; an absence nothing
     * explains is a <em>refusal</em>, because a truncated or half-cleaned test compile removes this
     * guard in the same stroke as the classes it was meant to notice were missing &mdash; which is
     * this step's whole subject. A single branch on the file's existence would excuse exactly that
     * state, and would do so silently for ever if the checked path ever went stale.
     *
     * <h2>The population, the unit and the control</h2>
     * The population is <strong>every {@code maven-antrun-plugin} execution in {@code pom.xml}</strong>
     * (two today, floored at two), keyed on its {@code <id>}; the rule is asked of every
     * {@code (execution id, forked classname)} pair inside them, which is the unit the rule is
     * actually about &mdash; a second fork in the same execution is a second member and cannot be
     * satisfied by the first one's row. {@code test-tree-summary-staleness} is a member of the
     * population and forks nothing (its one task is a {@code <delete>}, for which an absent file is
     * the desired post-state), so it is <em>classified</em> rather than excused: grow a
     * {@code <java>} inside it and it acquires the same three obligations here. The granularity
     * control fires if two executions ever collapse onto one row.
     *
     * <p><strong>Two things this rule deliberately does not do, both reported by HD-311's tests
     * gate, so that the next reader does not take either for an oversight.</strong> (a) The
     * duplicate-{@code <id>} half of that control is <em>unreachable</em>: Maven's own model
     * validation refuses the POM before any test runs
     * ({@code '…executions.execution.id' must be unique but found duplicate execution with id
     * test-tree-coverage-guard}), so it is a cheap backstop and not a seal — do not count it as
     * one. (b) The two branch conditions are asserted to <em>mention</em> the presence property,
     * not to negate it. Dropping a {@code <not>} is statically green here, and at run time under
     * the flag it falls through to the REFUSAL and fails the build loudly: the un-asserted
     * negation can only regress to HD-311's own defect with a better-worded message, and there is
     * no silent-green direction to seal.
     *
     * <h2>Why the structural attributes and not the text</h2>
     * Every assertion below reads an <em>attribute</em> &mdash; {@code <available file>},
     * {@code <istrue value>}, each task's {@code if:set} &mdash; and never the block. The step's own
     * {@code message} prose names the flag, the class file and the remedy, so a {@code contains}
     * check over the execution block is satisfied by the refusal explaining itself with the whole
     * mechanism deleted. That is not hypothetical here: the sibling rule above was green with a
     * spill file dropped from the deletion, because the neighbouring {@code <fail>} message listed
     * it. The checked path is <strong>derived from the {@code classname} attribute</strong> for the
     * same reason a hand-copied path rots: a stale one reads "absent" for ever, which under the flag
     * is permanent silence.
     */
    @Test
    void everyAntrunForkOutOfTargetAnswersForItsOwnMissingClassFile() throws IOException {
        var antrun = antrunPluginBlock(pomWithoutComments());

        var executions = new LinkedHashMap<String, String>();
        var declared = 0;
        var blocks = Pattern.compile("(?s)<execution>(.*?)</execution>").matcher(antrun);
        while (blocks.find()) {
            declared++;
            var block = blocks.group(1);
            var id = Pattern.compile("<id>([^<]*)</id>").matcher(block);
            assertThat(id.find())
                    .as("""
                        A maven-antrun-plugin execution in pom.xml has no <id>, so this rule cannot
                        name the member it is judging and a second unnamed execution would collapse
                        onto the first. Give it one. Block:
                        %s""".formatted(block.strip()))
                    .isTrue();
            executions.put(id.group(1), block);
        }

        assertThat(executions.keySet())
                .as("""
                    The maven-antrun-plugin execution population collapsed, so this rule had nothing
                    to demand. Both the coverage guard (test-tree-coverage-guard) and the staleness
                    delete (test-tree-summary-staleness) are declared there; if one was deliberately
                    removed, the sibling rules above say what it held, and this floor comes down in
                    the SAME commit.""")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(executions)
                .as("""
                    Two maven-antrun-plugin executions share one <id>, so %d declared blocks
                    collapsed onto %d rows and one of them is judged by nobody. Ids must be
                    distinct.""".formatted(declared, executions.size()))
                .hasSize(declared);

        var forks = new LinkedHashMap<String, String>();
        var forkTags = 0;
        for (var execution : executions.entrySet()) {
            var tags = Pattern.compile("<java\\b[^>]*>").matcher(execution.getValue());
            while (tags.find()) {
                forkTags++;
                var classname = attribute(tags.group(), "classname");
                assertThat(classname)
                        .as("""
                            A <java> task in the %s execution declares no classname, so this rule
                            cannot derive the class file whose absence it must answer for.
                            Tag: %s""".formatted(execution.getKey(), tags.group()))
                        .isNotNull();
                forks.put(execution.getKey() + "#" + classname, execution.getValue());
            }
        }

        assertThat(forks.keySet())
                .as("""
                    No <java> fork is declared by any maven-antrun-plugin execution in pom.xml, so
                    this rule is vacuous. The test-tree coverage guard runs as exactly such a fork
                    (its class file is in target/test-classes, which -Dmaven.test.skip=true
                    removes). If that step was rewritten into another shape, teach this rule the new
                    shape rather than letting it pass on an empty population.""")
                .isNotEmpty();
        assertThat(forks)
                .as("""
                    %d <java> forks were declared across the antrun executions but only %d rows came
                    out, so two forks share one (execution, classname) key and one of them is
                    answered by the other's guard. The unit of this rule is the pair.
                    """.formatted(forkTags, forks.size()))
                .hasSize(forkTags);

        for (var fork : forks.entrySet()) {
            var executionId = fork.getKey().substring(0, fork.getKey().indexOf('#'));
            var classname = fork.getKey().substring(fork.getKey().indexOf('#') + 1);
            var block = fork.getValue();
            var classFile = classname.replace('.', '/') + ".class";

            var presence = new ArrayList<String>();
            var available = Pattern.compile("<available\\b[^>]*>").matcher(block);
            while (available.find()) {
                var file = attribute(available.group(), "file");
                if (file != null && file.endsWith(classFile)) {
                    presence.add(attribute(available.group(), "property"));
                }
            }

            assertThat(presence)
                    .as("""
                        The %s execution forks %s but no <available> in it checks that class file
                        first, so an absent one is answered by Ant's default: "Could not find or
                        load main class" at WARNING, then a <fail> naming a report that was never
                        printed. Measured 2026-10-13 under -Dmaven.test.skip=true, which skips test
                        COMPILATION and therefore removes this very file. Add
                        <available property="..." file="${project.build.testOutputDirectory}/%s"/>
                        and gate the fork on it. The path must be DERIVED from the classname above:
                        a hand-copied one that goes stale reads "absent" for ever, which under that
                        flag is permanent silence.""".formatted(executionId, classname, classFile))
                    .hasSize(1);
            var compiled = presence.getFirst();

            assertThat(attribute(tag(block, "java", classname), "if:set"))
                    .as("""
                        The %s execution's fork is not gated on the <available> that proves its own
                        class file exists (property %s), so it runs anyway and dies on a
                        ClassNotFoundException whose message belongs to Ant and not to this build.
                        Put if:set="%s" on the <java> task (the ant:if namespace is declared on the
                        <target>).""".formatted(executionId, compiled, compiled))
                    .isEqualTo(compiled);

            var conditions = new LinkedHashMap<String, String>();
            var declaredConditions =
                    Pattern.compile("(?s)<condition\\s+property=\"([^\"]*)\"\\s*>(.*?)</condition>")
                            .matcher(block);
            while (declaredConditions.find()) {
                conditions.put(declaredConditions.group(1), declaredConditions.group(2));
            }

            var explained = conditions.entrySet().stream()
                    .filter(c -> c.getValue().contains("<istrue"))
                    .filter(c -> c.getValue().contains("<isset property=\"" + compiled + "\""))
                    .map(Map.Entry::getKey)
                    .toList();
            assertThat(explained)
                    .as("""
                        The %s execution has no condition that reads BOTH its own missing class file
                        (%s) and a build flag that would explain the absence, so it cannot tell a
                        stand-down from a truncated compile and must be answering one of them
                        wrongly. The flag branch is: <condition property="..."> <and> <not><isset
                        property="%s"/></not> <istrue value="${maven.test.skip}"/> </and>
                        </condition>. Reading the <fail> message's prose is not enough - it names
                        the flag either way.""".formatted(executionId, compiled, compiled))
                    .hasSize(1);

            var flags = new TreeSet<String>();
            var istrue = Pattern.compile("<istrue\\b[^>]*>").matcher(conditions.get(explained.getFirst()));
            while (istrue.find()) {
                var value = attribute(istrue.group(), "value");
                if (value != null) {
                    flags.add(value.replaceAll("^\\$\\{|}$", ""));
                }
            }
            assertThat(flags)
                    .as("""
                        The flag the %s execution accepts as an explanation for its own missing
                        class file is not one the guard itself treats as narrowing
                        (SuiteCoverageGuard.JVM_NARROWING_SWITCHES = %s). Two lists that disagree
                        here means the step stands down on a flag the guard would still have
                        answered for, which is a silent unjudged suite. Accepted here: %s"""
                            .formatted(executionId, SuiteCoverageGuard.JVM_NARROWING_SWITCHES, flags))
                    .isNotEmpty()
                    .allMatch(SuiteCoverageGuard.JVM_NARROWING_SWITCHES::contains);

            assertThat(attribute(gatedTag(block, "echo", explained.getFirst()), "level"))
                    .as("""
                        The %s execution's flag branch prints nothing a default run can see: either
                        there is no <echo if:set="%s"> or it is not at level="warning". A step that
                        quietly does nothing is the exact shape this guard exists to delete, so the
                        stand-down is printed one level ABOVE the guard's own INFO witness lines.
                        (Measured 2026-10-13: Maven's -q is errors-only and hides it anyway, which
                        is why the OTHER branch is a <fail>.)""".formatted(executionId, explained.getFirst()))
                    .isEqualTo("warning");

            var unexplained = conditions.entrySet().stream()
                    .filter(c -> !c.getValue().contains("<istrue"))
                    .filter(c -> c.getValue().contains("<isset property=\"" + compiled + "\""))
                    .filter(c -> c.getValue().contains("<isset property=\"" + explained.getFirst() + "\""))
                    .map(Map.Entry::getKey)
                    .toList();
            assertThat(unexplained)
                    .as("""
                        The %s execution has no branch for its class file being absent with NO flag
                        to explain it, so that state is either excused or answered by Ant. It must
                        REFUSE: a truncated or half-cleaned test compile removes this guard in the
                        same stroke as the classes it should have missed, which is this step's whole
                        subject. Add <condition property="..."> <and> <not><isset property="%s"/>
                        </not> <not><isset property="%s"/></not> </and> </condition> and a <fail>
                        gated on it.""".formatted(executionId, compiled, explained.getFirst()))
                    .hasSize(1);
            assertThat(gatedTag(block, "fail", unexplained.getFirst()))
                    .as("""
                        The %s execution computes the unexplained-absence case (%s) and then does
                        nothing with it, which is worse than not computing it: the build passes with
                        no check and no line. Gate a <fail> on it - a refusal survives -q, a
                        stand-down does not.""".formatted(executionId, unexplained.getFirst()))
                    .isNotNull();

            var resultProperty = attribute(tag(block, "java", classname), "resultproperty");
            assertThat(resultProperty)
                    .as("""
                        The %s fork no longer captures its exit code into a resultproperty, so
                        nothing turns a refusal from the forked check into a build failure: it is
                        printed and the build stays green, which is the same build as not checking
                        at all.""".formatted(executionId))
                    .isNotNull();
            // LAZY, because <fail .../> is self-closing here and a greedy [^>]* eats the `/` and
            // then matches on to the NEXT </fail> - which made this rule read the unexplained
            // refusal and the exit-code <fail> as one element and red on the first run. Measured.
            var elements = Pattern.compile("(?s)<fail\\b[^>]*?(?:/>|>.*?</fail>)").matcher(block);
            var exitFails = new ArrayList<String>();
            while (elements.find()) {
                if (elements.group().contains("${" + resultProperty + "}")) {
                    exitFails.add(elements.group());
                }
            }

            // THE ELEMENT MUST EXIST BEFORE ITS PROPERTIES ARE WORTH ASKING ABOUT. Without this
            // line the two assertions below are vacuous when no <fail> reads the exit code at all:
            // an empty population is "no ungated fails" and "no wrong comparisons", which is the
            // greenest possible answer to a step that no longer refuses anything. The SPA seal
            // asks the same question from outside; this one is derived, so it also covers a second
            // fork.
            assertThat(exitFails)
                    .as("""
                        No <fail> in the %s execution reads ${%s}, so the forked check's exit code
                        decides nothing: a refusal is PRINTED and the build stays green, which is
                        the same build as not checking at all. Turn the captured exit code into a
                        <fail> whose condition compares it against 0.""".formatted(
                                executionId, resultProperty))
                    .isNotEmpty();

            var ungated = exitFails.stream()
                    .filter(fail -> !compiled.equals(attribute(fail, "if:set")))
                    .toList();
            assertThat(ungated)
                    .as("""
                        The %s execution's exit-code <fail> is not gated on %s. When the fork does
                        not run, resultproperty is never set, so the comparison is made against the
                        literal `${%s}`, finds it unequal to 0 and fails the build - turning BOTH
                        branches above into a BUILD FAILURE carrying the wrong message. Measured
                        while HD-311 was being built. Add if:set="%s".
                        Ungated:""".formatted(executionId, compiled, resultProperty, compiled))
                    .isEmpty();

            // AND THE COMPARISON ITSELF, AS A PAIR OF OPERANDS. Pre-existing and found by the
            // HD-311 tests gate: arg2="${hamstrack.test-tree.exit}" makes the condition
            // `not(x equals x)`, i.e. never true, so the refusal is UNFIREABLE - and both seals
            // stayed green over a real armed run that then passed whatever the fork said. It is
            // asserted here rather than as a text pattern in suiteGuard.test.ts because the
            // operands are DERIVED on this side (resultproperty is read off the <java> tag), so a
            // second fork gets the check for free and nothing is hand-copied; the SPA file could
            // only pin both literals by memory, which its own javadoc gives as the reason member 8
            // would be worse than absent. A SET of the two operands, so neither `arg1="0"
            // arg2="0"` nor the two swapped reads as the comparison.
            //
            // READ FROM THE EXIT <fail>'S OWN BODY, not from the execution block (round 3). Over
            // the block, a <condition> orphaned from any <fail> - or one belonging to a different
            // one - satisfied this, so the assertion was wider than its own message claimed. Ant
            // would refuse an orphaned <condition> anyway, which makes this a claim being narrowed
            // to its check rather than a hole being closed; the check and the sentence now ask the
            // same question, which is the part that rots when they do not.
            var comparisons = new ArrayList<Set<String>>();
            for (var fail : exitFails) {
                var equals = Pattern.compile("<equals\\b[^>]*>").matcher(fail);
                while (equals.find()) {
                    comparisons.add(new TreeSet<>(Arrays.asList(
                            String.valueOf(attribute(equals.group(), "arg1")),
                            String.valueOf(attribute(equals.group(), "arg2")))));
                }
            }
            assertThat(comparisons)
                    .as("""
                        The %s execution's exit-code <fail> no longer compares that exit code
                        against success INSIDE ITS OWN CONDITION. It must be `${%s}` against the
                        literal 0 and nothing else: with the SAME property on both sides it reads
                        `not(x equals x)`,
                        which is never true, so the refusal cannot fire at all - a green build over
                        whatever the check printed. Found by HD-311's tests gate with both seals
                        green over a real armed run, which is how long that shape can live. Read
                        comparisons: %s""".formatted(executionId, resultProperty, comparisons))
                    .contains(new TreeSet<>(List.of("${" + resultProperty + "}", "0")));
        }
    }

    /**
     * <strong>HD-311 &mdash; the stand-down's only witness in CI is held by something.</strong>
     *
     * <h2>Why this exists</h2>
     * Branch 2 of the guard's execution lets a build PASS with nothing judged, and the argument
     * that this is safe rests on one step somewhere else: {@code build.yml}'s
     * "Test-tree counts in the job summary" runs {@code if: always()}, and when
     * {@code target/test-tree-summary.md} is absent it raises
     * {@code ::error title=Test-tree guard did not run} and exits 1. An absent summary is the ONLY
     * signal that a run judged nothing &mdash; the guard writes that file on every path where it
     * reached a verdict, including a disarmed one &mdash; so in CI that step is what turns "no
     * verdict" into a red job.
     *
     * <p>Nothing held it. Measured on this ticket: every other mention of it in the repository was
     * prose (a design doc and an agent checklist), and no test read the step, its {@code always()}
     * or its {@code exit 1}. Worse, its own comment records that it <em>used to</em>
     * {@code echo} and exit 0 &mdash; "a note in a place nobody looks" &mdash; so the silent
     * version of this exact witness has already shipped here once. A deliberate pass-with-nothing-
     * judged path may not point at an unheld mechanism.
     *
     * <h2>Read, not matched</h2>
     * Through {@link OpsYaml}, which parses, because the comment directly above that step names
     * the summary file AND the words {@code if: always()} &mdash; a text scan over this file is
     * satisfied by the prose explaining the mechanism with the mechanism deleted. That is the
     * HD-343 shape, live, in the file this rule reads. The file name comes from
     * {@link SuiteCoverageGuard#SUMMARY_FILE_NAME} so a rename moves the rule with it.
     */
    @Test
    void ciStillRefusesAJobWhoseTestTreeSummaryIsAbsent() {
        var job = OpsYaml.map(OpsYaml.map(OpsYaml.parse(BUILD_WORKFLOW).get("jobs")).get(TEST_JOB));
        var steps = OpsYaml.list(job.get("steps"));

        assertThat(steps)
                .as("""
                    jobs.%s.steps could not be read out of %s at all, so this rule is asserting
                    nothing about CI. Either the job was renamed - in which case rename TEST_JOB
                    here in the same commit - or the workflow stopped parsing the way OpsYaml reads
                    it.""".formatted(TEST_JOB, BUILD_WORKFLOW))
                .isNotEmpty();

        var summaryReaders = steps.stream()
                .map(OpsYaml::map)
                .filter(step -> String.valueOf(step.get("run"))
                        .contains(SuiteCoverageGuard.SUMMARY_FILE_NAME))
                .toList();

        assertThat(summaryReaders)
                .as("""
                    No step of jobs.%s in %s reads %s any more, so CI no longer notices a run that
                    judged NOTHING. That file's absence is the only signal there is: the guard
                    writes it whenever it reached a verdict, disarmed included, so "no file" means
                    "nothing compared either suite against its tree". HD-311 added a branch that
                    passes the build with no verdict on purpose (-Dmaven.test.skip=true removes the
                    guard's own class), and this step is the only thing standing under that
                    decision. Restore it: `if: always()`, and on the absent branch an `::error`
                    plus `exit 1`.""".formatted(TEST_JOB, BUILD_WORKFLOW,
                            SuiteCoverageGuard.SUMMARY_FILE_NAME))
                .isNotEmpty();

        for (var step : summaryReaders) {
            var name = OpsYaml.text(step.get("name"));
            assertThat(OpsYaml.text(step.get("if")))
                    .as("""
                        The %s step in %s no longer runs `if: always()`, so it is skipped on exactly
                        the jobs it matters on: a job that already failed is where the counts are
                        needed, and a summary read only on green is read when nobody needs it. It
                        is also then silent about a run that judged nothing while failing for an
                        unrelated reason.""".formatted(name, BUILD_WORKFLOW))
                    .isEqualTo("always()");

            var script = String.valueOf(step.get("run"));
            var absentBranch = script.contains("else") ? script.substring(script.indexOf("else"))
                                                       : script;
            assertThat(absentBranch)
                    .as("""
                        The %s step in %s no longer REFUSES when %s is absent - its absent branch
                        has lost its `::error` or its `exit 1`. That is not a smaller version of
                        the check: this step shipped once as an `echo` with exit 0, and its own
                        comment calls that "a note in a place nobody looks". With HD-311's
                        stand-down branch in the build, a note is all that would stand between a
                        pass with nothing judged and a green CI run. Absent branch read:
                        %s""".formatted(name, BUILD_WORKFLOW,
                                SuiteCoverageGuard.SUMMARY_FILE_NAME, absentBranch.strip()))
                    .contains("::error")
                    .containsPattern("(?m)^\\s*exit 1\\s*$");
        }
    }

    private static final Path BUILD_WORKFLOW = Path.of(".github", "workflows", "build.yml");

    /** The job that runs the suites, and therefore the one whose summary step is load-bearing. */
    private static final String TEST_JOB = "build-and-test";

    /**
     * <strong>HD-343's lesson, applied to this file's own reader.</strong> A scan that reads a build
     * file expecting elements must blank its comments first: a {@code DOTALL} capture over
     * {@code pom.xml} matched the literal {@code <argLine>} inside the comment explaining why a
     * property was used instead of an element, so deleting the real bound left the check green. This
     * POM's comments are long and quote the very tags, flags and file names the rules above look
     * for, so every reader here goes through one derivation &mdash; and a shared derivation needs
     * its own witness in BOTH directions, because consolidating trades two parsers disagreeing
     * loudly for one parser being blind everywhere.
     */
    @Test
    void blanksEveryCommentInTheBuildFileAndNothingElse() throws IOException {
        var mustSee = Map.of(
                "a plain element", "<id>test-tree-coverage-guard</id>",
                "an element after a comment", "<!-- why --><skip>false</skip>",
                "an attribute after two comments on one line",
                "<!--a--><istrue value=\"${maven.test.skip}\"/><!--c-->");
        for (var row : mustSee.entrySet()) {
            assertThat(withoutXmlComments(row.getValue()))
                    .as("""
                        The shared POM reader lost %s, so every rule built on it now scans less
                        than the build file declares and can pass on an element it never saw.
                        Fixture: %s""".formatted(row.getKey(), row.getValue()))
                    .contains(row.getValue().replaceAll("<!--.*?-->", ""));
        }

        var mustNotSee = Map.of(
                "a one-line comment", "<!-- <skip>false</skip> -->",
                "a multi-line comment quoting an element",
                "<!--\n  <java classname=\"x.Y\"/> is what this step forks\n-->",
                "a comment quoting a flag", "<!-- -Dmaven.test.skip=true removes it -->");
        for (var row : mustNotSee.entrySet()) {
            assertThat(withoutXmlComments(row.getValue()).strip())
                    .as("""
                        The shared POM reader kept %s, so a rule above can be satisfied by the
                        prose that explains the mechanism it is supposed to find - which is how a
                        deleted bound stayed green in HD-343. Fixture: %s""".formatted(
                                row.getKey(), row.getValue().replace("\n", "\\n")))
                    .isEmpty();
        }

        var raw = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        var read = pomWithoutComments();
        assertThat(read)
                .as("a comment survived the real pom.xml, so the rules above are reading prose")
                .doesNotContain("<!--");
        assertThat(raw.length() - read.length())
                .as("stripping comments from the real pom.xml removed almost nothing, so either the"
                    + " file lost the notes that explain its guards or this reader stopped working"
                    + " on the file it is pointed at")
                .isGreaterThan(2000);
        assertThat(read)
                .as("the real pom.xml no longer carries the antrun executions these rules judge")
                .contains("maven-antrun-plugin");
    }

    /** The POM as every rule here must read it: elements only, with the prose blanked. */
    private static String pomWithoutComments() throws IOException {
        return withoutXmlComments(Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8));
    }

    private static String withoutXmlComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    /** The {@code maven-antrun-plugin} {@code <plugin>} element, comments already blanked. */
    private static String antrunPluginBlock(String pom) {
        var plugin = Pattern
                .compile("(?s)<plugin>(?:(?!</plugin>).)*?maven-antrun-plugin.*?</plugin>")
                .matcher(pom);
        assertThat(plugin.find())
                .as("""
                    maven-antrun-plugin is gone from pom.xml. It hosts the test-tree coverage guard
                    (HD-265/HD-301) and the report-staleness delete (HD-343); without it an
                    incomplete suite run is evidence again and nothing says so. Restore the plugin,
                    or move those two steps and point these rules at wherever they went.""")
                .isTrue();
        return plugin.group();
    }

    /** The opening tag of the named task that declares {@code classname}, or {@code null}. */
    private static String tag(String block, String task, String classname) {
        var tags = Pattern.compile("<" + task + "\\b[^>]*>").matcher(block);
        while (tags.find()) {
            if (classname.equals(attribute(tags.group(), "classname"))) {
                return tags.group();
            }
        }
        return null;
    }

    /** The opening tag of the named task gated on {@code property} via {@code if:set}, or null. */
    private static String gatedTag(String block, String task, String property) {
        var tags = Pattern.compile("<" + task + "\\b[^>]*>").matcher(block);
        while (tags.find()) {
            if (property.equals(attribute(tags.group(), "if:set"))) {
                return tags.group();
            }
        }
        return null;
    }

    private static String attribute(String tag, String name) {
        if (tag == null) {
            return null;
        }
        var value = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"")
                .matcher(tag);
        return value.find() ? value.group(1) : null;
    }

    /** Phases of the default lifecycle that are guaranteed to run before Surefire's {@code test}. */
    private static final List<String> PHASES_BEFORE_SUREFIRE = List.of(
            "generate-test-sources", "process-test-sources", "generate-test-resources",
            "process-test-resources", "test-compile", "process-test-classes");

    private static String fileNameOf(String path) {
        return Path.of(path).getFileName().toString();
    }

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
