package com.hamstrack.common.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * <strong>The one decision both suite-coverage arms are refused by (HD-301).</strong>
 *
 * <p>The rule this holds is phrased over the category, not over a suite: <em>every automated suite
 * in this repository proves how much of its own tree it executed, and refuses a run that cannot show
 * it.</em> There are two members — Surefire over {@code src/test/java} (HD-265) and vitest over
 * {@code src/main/frontend} (HD-301) — and this is the function that judges both, so that a third
 * suite is a new {@link Arm} rather than a second copy of the comparison. The arms differ in
 * vocabulary, in where their record comes from and in which switches stand them down; they do not
 * differ in what "this run executed less than its tree" means.
 *
 * <p>It is deliberately <strong>not</strong> named into Surefire's four default include patterns
 * ({@code Test*}, {@code *Test}, {@code *Tests}, {@code *TestCase}): those are the JVM arm's own
 * expected set, and a helper wearing one is demanded as a class that must execute. {@link
 * SuiteRunRecord} carries the same warning and earned it.
 *
 * <p><strong>Four properties, each of which is a defect some earlier guard in this tree had.</strong>
 *
 * <ol>
 *   <li><strong>A malformed call is refused before anything is compared.</strong> A missing tree, a
 *       missing executed set, a missing record count, a missing test count where the arm declares a
 *       test floor — each is a refusal that names the input, and the rest is <em>not</em> judged.
 *       This is {@code lint.debt.mjs}'s {@code verdict} in Java, and the Java failure mode is
 *       different but no better: {@code Set.containsAll(null)} and an unboxing NPE are a stack trace
 *       where a sentence belongs, and an empty set defaulted in by a helper is a comparison that
 *       passes. A comparison against a missing value is not a weaker check; it is an absent one.</li>
 *   <li><strong>Sets, never counts.</strong> {@code tree.size() == executed.size()} is satisfied by
 *       a stray file masking a missing one — {@code {a,c}} against {@code {a,b}} has equal sizes and
 *       must still refuse. The refusal is {@code tree − executed}; {@code executed − tree} is a
 *       <em>note</em>, because a stale artefact is not an unexecuted test.</li>
 *   <li><strong>Absent input never disarms.</strong> Only an explicitly-passed, explicitly-true
 *       narrowing switch stands an arm down. A missing record, a missing root, a git failure are
 *       refusals — a guard that cannot see its subject must not pass it.</li>
 *   <li><strong>The entry point exits on exactly what this returned, and on nothing else.</strong>
 *       HD-300 measured what a second term on that line costs: a text assertion pinned it, the first
 *       term was deleted, a real violation was printed, the run exited 0 and 21 tests stayed green.
 *       So every new condition belongs in here, where a fixture can reach it.</li>
 * </ol>
 */
public final class SuiteCoverage {

    /**
     * The first token of the vitest recorder's last line, which carries the test count. It is the
     * only source of that number and is deliberately <em>not</em> part of the file comparison: a
     * missing summary line is a short record, which is already the incomplete-run refusal.
     */
    public static final String SUMMARY_PREFIX = "#summary";

    /**
     * How many absent members to print before the rest spill to a file. A bare delta sends the
     * reader to the wrong question ("28 fewer than what?"), so the names are the message.
     *
     * <p><strong>12, because the message is also bound at 25 lines.</strong> At the original 40 the
     * incomplete-run refusal reached ~48 lines — over the house bound — and the spill branch was
     * nearly unreachable on the vitest arm, which would need 41 absent files whose surviving
     * siblings still hold 900 tests. Twelve names, a spill line, two header lines, a blank and up
     * to four lines of help come to 20, and the file holds the rest. The names are the message only
     * for as long as a reader reads them.
     */
    public static final int MAX_LISTED = 12;

    private SuiteCoverage() {
    }

    // --------------------------------------------------------------------- the arms

    /**
     * A collapse detector, <strong>and the remedies its own reader can perform</strong>.
     *
     * <p>The causes travel with the number on purpose. The first version of this took an {@code int}
     * and borrowed {@link Population}'s message wholesale, so the vitest arm's floors prescribed
     * {@code mvnw clean compile} and "fix Doors" — three causes about {@code target/classes},
     * {@code HamstrackApplication} and the {@code Doors} harness, none of which either arm touches —
     * to a reader whose real problem was an {@code include} line in {@code vitest.config.ts}. A
     * refusal may only prescribe an action its reader can perform, so a floor that cannot say what
     * to do about itself cannot be constructed.
     *
     * <p><strong>"Cannot be constructed" is the compact constructor, not this paragraph.</strong>
     * The sentence above was true of the intent and false of the code for one round: the record had
     * no constructor, and the seal that exists to demand remedies — {@code
     * SuiteCoverageGuardTest#givesEveryFloorRemediesItsOwnReaderCanPerform} — iterates
     * {@code causes()}, so a floor with none iterated zero times and passed. Measured 2026-09-11:
     * {@code JVM_TREE_FLOOR} rebuilt with {@code List.of()} gave {@code Tests run: 22, Failures: 0}
     * and BUILD SUCCESS. A scan over floors with no floor of its own is this ticket's own shape, one
     * level up, so the demand moved into the type where a caller cannot iterate past it.
     *
     * @param value  the number the subject must not fall under; never lowered to pass a run
     * @param why    one sentence on what goes vacuous when it does, ending in a full stop
     * @param causes every cause, because a refusal naming one of several sends the reader to the
     *               wrong one; about ten before the message passes the house's 25-line bound
     */
    public record Floor(int value, String why, List<String> causes) {

        public Floor {
            why = prose("Floor.why", why);
            causes = remedies("Floor.causes", causes);
        }
    }

    /**
     * One member of the category: what it is answerable for, and the words its refusals use.
     *
     * @param key            short name for the summary table ({@code jvm}, {@code vitest})
     * @param tag            the bracketed prefix on every line this arm prints
     * @param noun           what its members are, in the plural ({@code test classes})
     * @param where          the tree it is answerable for, as a reader would go and look at it
     * @param spillFileName  where the names go when there are more than {@link #MAX_LISTED}
     * @param treeFloor      a collapse detector on the bound itself — never lowered to pass a run
     * @param testFloor      a collapse detector on what ran inside the members, or {@code null}
     *                       when this arm has no such number
     * @param incompleteHelp the fork in the road a reader takes after an incomplete run
     * @param noRecordCauses every cause of "nothing was written down", because a refusal naming one
     *                       of several sends the reader to the wrong one
     */
    public record Arm(String key, String tag, String noun, String where, String spillFileName,
                      Floor treeFloor, Floor testFloor, List<String> incompleteHelp,
                      List<String> noRecordCauses) {

        /**
         * The same demand {@link Floor} makes, over the other two lists a refusal iterates — an arm
         * with an empty {@code noRecordCauses} prints "Exactly one of these is true:" and then
         * nothing at all. The three lists are one category and this is the whole of it; {@code
         * SuiteCoverageGuardTest#refusesAFloorOrAnArmThatPrescribesNothing} enumerates them from
         * the record components rather than from this sentence, so a fourth joins by existing.
         */
        public Arm {
            key = prose("Arm.key", key);
            tag = prose("Arm.tag", tag);
            noun = prose("Arm.noun", noun);
            where = prose("Arm.where", where);
            spillFileName = prose("Arm.spillFileName", spillFileName);
            incompleteHelp = remedies("Arm.incompleteHelp", incompleteHelp);
            noRecordCauses = remedies("Arm.noRecordCauses", noRecordCauses);
        }
    }

    /**
     * A list of actions, refused when it prescribes none — the demand {@link Floor} and {@link Arm}
     * share rather than state twice.
     *
     * <p>It throws where the house would normally count a drop, because there is no metric on this
     * surface to count into: the guard is a {@code java} fork with {@code target/test-classes} on
     * its classpath and no Spring context. Every live floor and arm is a static field of {@link
     * SuiteCoverageGuard}, so the refusal is an {@code ExceptionInInitializerError} on the first
     * build after the edit — louder than a counter and impossible to postpone.
     */
    private static List<String> remedies(String field, List<String> value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " prescribes nothing."
                    + " A refusal that names no action sends its reader back to this source to"
                    + " guess, and the seal that reads this list iterates it -- so an empty one"
                    + " iterates zero times and passes. Name at least one action THIS refusal's"
                    + " reader can perform, in the second person, quoting the file or command.");
        }
        var checked = List.copyOf(value);
        for (var entry : checked) {
            if (entry.isBlank()) {
                throw new IllegalArgumentException(field + " carries a blank entry, which prints as"
                        + " a bullet with nothing after it. Say the action or drop the entry.");
            }
        }
        return checked;
    }

    /** A field that is printed into a refusal, refused when it is absent or blank. */
    private static String prose(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is blank, and it is printed into every"
                    + " refusal this arm makes -- the reader would get a sentence with a hole in it."
                    + " Give it a value.");
        }
        return value;
    }

    /**
     * What one arm observed. Every field is passed explicitly, including the ones that are
     * {@code null} — "the caller did not look" and "the caller looked and found nothing" are
     * different facts and this record keeps them apart.
     *
     * @param tree        the members the run was answerable for, derived from the tree
     * @param executed    the members the run's own record says it executed
     * @param recordCount how many record files of <em>this</em> run identity were found; {@code 0}
     *                    is the "the suite did not run" refusal and is not the same as an empty
     *                    {@code executed}
     * @param testCount   the number of tests inside those members, or {@code null} for an arm that
     *                    declares no {@link Arm#testFloor()}
     * @param disarmedBy  the switch that made a short plan intentional, or {@code null}
     * @param annotate    a cosmetic per-member suffix for the report (never a comparison input);
     *                    {@code null} means none
     */
    public record Run(Set<String> tree, Set<String> executed, Integer recordCount, Integer testCount,
                      String disarmedBy, UnaryOperator<String> annotate) {
    }

    /**
     * What one arm decided: the lines it prints whatever happened, the refusals that are the exit
     * condition, and the counts a reader needs without doing arithmetic.
     */
    public record Verdict(Arm arm, String status, List<String> witness, List<String> refusals,
                          Integer executedSize, Integer treeSize, Integer testCount,
                          Set<String> missing) {

        public boolean refused() {
            return !refusals.isEmpty();
        }

        /** One row of {@code target/test-tree-summary.md}; written on red runs as well as green. */
        public String summaryRow() {
            return SuiteCoverage.summaryRow(arm.key(), executedSize, treeSize, testCount, status);
        }
    }

    /**
     * One row of {@code target/test-tree-summary.md}, for a judged arm <em>and</em> for the run that
     * threw before it had an arm to judge — {@link SuiteCoverageGuard} writes the second kind, and a
     * second spelling of this line is how the two would drift into different tables.
     */
    public static String summaryRow(String key, Integer executedSize, Integer treeSize,
                                    Integer testCount, String status) {
        return "| " + key + " | " + count(executedSize) + " | " + count(treeSize)
               + " | " + count(testCount) + " | " + status + " |";
    }

    private static String count(Integer value) {
        return value == null ? "—" : String.valueOf(value);
    }

    /**
     * The line above the table, which says <em>which run wrote this file</em>.
     *
     * <p>Without it a copy left by the previous local build reads exactly like this run's: four of
     * the five red paths (compile, surefire, lint, vitest) stop Maven before the guard, so the file
     * is not rewritten and yesterday's counts are read as today's — measured 2026-09-11, mtime 30
     * seconds older than the run being read. CI has a fresh workspace and cannot hit it, which is
     * why it had to be stated rather than relied on.
     */
    public static String summaryIdentity(String runToken, String judgedAt) {
        return summaryIdentityPrefix(runToken) + ", judged " + judgedAt
               + ". A run id that is not this session's means the step never ran and this file is a"
               + " leftover.";
    }

    /**
     * The part of that line that identifies the run — the one spelling, because a reader of the
     * file and a writer deciding whether the file is already this run's must agree on it.
     */
    public static String summaryIdentityPrefix(String runToken) {
        return "Test-tree coverage for Maven run `" + runToken + "`";
    }

    /** The header of {@code target/test-tree-summary.md}, so both writers agree on its shape. */
    public static List<String> summaryHeader() {
        return List.of("| arm | executed | in tree | tests | verdict |",
                "|---|---|---|---|---|");
    }

    // ------------------------------------------------------------------ the decision

    /**
     * The whole judgement for one arm. Pure: it reads nothing, writes nothing and throws nothing, so
     * every row of HD-301 §7 can be driven through it with fixtures.
     */
    public static Verdict verdict(Arm arm, Run run) {
        // Disarm first and only on an explicit switch: the caller that stands an arm down has not
        // derived a tree or read a record, and judging those absences would print a refusal about a
        // run nobody asked for.
        if (run.disarmedBy() != null && !run.disarmedBy().isBlank()) {
            var status = "disarmed by " + run.disarmedBy();
            return new Verdict(arm, status,
                    List.of(arm.tag() + " coverage check " + status
                            + " -- this run asked for a narrower plan than " + arm.where() + "."),
                    List.of(), null, null, null, Set.of());
        }

        var malformed = malformed(arm, run);
        if (!malformed.isEmpty()) {
            return refusal(arm, "REFUSED: malformed call", malformed, null, null, null);
        }

        var treeSize = run.tree().size();
        // Before the test count is demanded, because a run that wrote no record legitimately has
        // none — and "the suite did not run" is the refusal a reader needs, not "you passed me no
        // number".
        if (run.recordCount() == 0) {
            return refusal(arm, "REFUSED: no record", List.of(noRecord(arm)), 0, treeSize, null);
        }
        if (arm.testFloor() != null && run.testCount() == null) {
            return refusal(arm, "REFUSED: malformed call",
                    List.of(missingInput(arm, "testCount", "the number of tests inside the members,"
                                                           + " which this arm floors at "
                                                           + arm.testFloor().value())),
                    run.executed().size(), treeSize, null);
        }

        // The TREE floor comes before the set difference on purpose. A collapsed bound produces an
        // EMPTY missing set -- the most confident possible green line about a tree the guard has
        // quietly stopped seeing, which is a smaller, better-hidden copy of the bug being guarded.
        if (treeSize < arm.treeFloor().value()) {
            return refusal(arm, "REFUSED: bound collapsed",
                    List.of(floorRefusal(arm.tag() + " " + arm.noun() + " under " + arm.where(),
                            treeSize, arm.treeFloor())),
                    run.executed().size(), treeSize, run.testCount());
        }

        // The set difference comes before the TEST-COUNT floor, and that order is the whole of
        // HD-301 AC1. Both are true of a large truncation -- 15 of 74 modules ran 185 tests -- and
        // only one of them names the 59 files that never ran. Judged the other way round the
        // refusal read "REFUSED: every member empty" over 15 modules that were full of tests and
        // listed nothing, which is the one thing the reader came for.
        var missing = new TreeSet<>(run.tree());
        missing.removeAll(run.executed());
        if (!missing.isEmpty()) {
            return new Verdict(arm, "INCOMPLETE (" + missing.size() + " absent)",
                    List.of(), List.of(incomplete(arm, run, missing)),
                    run.executed().size(), treeSize, run.testCount(), missing);
        }

        // Reached only when every member of the tree ran, so "every member empty" is now a
        // statement this arm has the evidence for.
        if (arm.testFloor() != null && run.testCount() < arm.testFloor().value()) {
            return refusal(arm, "REFUSED: every member empty",
                    List.of(floorRefusal(arm.tag() + " tests inside " + arm.where(),
                            run.testCount(), arm.testFloor())),
                    run.executed().size(), treeSize, run.testCount());
        }

        // Printed on the happy path too, and on purpose: the count is the thing a reader was
        // supposed to notice and did not, so it is stated rather than left to be remembered.
        var witness = new ArrayList<String>();
        witness.add(arm.tag() + " all " + treeSize + " " + arm.noun() + " under " + arm.where()
                    + " executed in this run"
                    + (run.testCount() == null ? "." : " (" + run.testCount() + " tests)."));
        var strays = new TreeSet<>(run.executed());
        strays.removeAll(run.tree());
        if (!strays.isEmpty()) {
            witness.add(arm.tag() + " note: " + strays.size() + " member(s) ran with nothing matching"
                        + " in " + arm.where() + " -- a renamed or deleted test leaving a stale"
                        + " artefact behind: " + String.join(", ", strays)
                        + ". A stale artefact is not an unexecuted test, so this is a note and not a"
                        + " refusal.");
        }
        return new Verdict(arm, "complete", List.copyOf(witness), List.of(),
                run.executed().size(), treeSize, run.testCount(), Set.of());
    }

    /**
     * The house floor message — the frame from {@link Population}, the remedies from the
     * {@link Floor} itself.
     */
    private static String floorRefusal(String name, int size, Floor floor) {
        return Population.floorMessage(name, size, floor.value(),
                name + ": " + size + " (floor " + floor.value() + ")", floor.why(), floor.causes());
    }

    private static Verdict refusal(Arm arm, String status, List<String> refusals,
                                   Integer executedSize, Integer treeSize, Integer testCount) {
        return new Verdict(arm, status, List.of(), List.copyOf(refusals),
                executedSize, treeSize, testCount, Set.of());
    }

    // ------------------------------------------------------------------ the refusals

    /**
     * Every input the caller had to look up and did not. Returned as a list rather than the first
     * one, because a caller that forgot one thing usually forgot the one next to it too.
     */
    private static List<String> malformed(Arm arm, Run run) {
        var out = new ArrayList<String>();
        if (run.tree() == null) {
            out.add(missingInput(arm, "tree", "the set of " + arm.noun() + " under " + arm.where()
                                              + " this run was answerable for"));
        }
        if (run.executed() == null) {
            out.add(missingInput(arm, "executed", "the set this run's own record says it executed"));
        }
        if (run.recordCount() == null) {
            out.add(missingInput(arm, "recordCount", "how many records of this run identity were"
                                                     + " found; 0 and \"nobody looked\" are"
                                                     + " different facts"));
        }
        return out;
    }

    private static String missingInput(Arm arm, String name, String what) {
        return arm.tag() + " REFUSED: the guard was handed no " + name + " -- " + what + "."
               + System.lineSeparator()
               + "Nothing below was compared. A comparison against a missing value is not a weaker"
               + " check, it is an absent one: every test here is a subset or a `<`, both of which"
               + " are vacuous against null, so the check disappears instead of firing and the run"
               + " stays green. Pass the value, even when it is empty or 0.";
    }

    private static String noRecord(Arm arm) {
        var line = System.lineSeparator();
        var message = new StringBuilder();
        message.append(arm.tag()).append(" REFUSED: no execution record for this run.").append(line)
                .append("The suite result above is not evidence about ").append(arm.where())
                .append(": nothing wrote down how much of it ran. Exactly one of these is true:")
                .append(line);
        for (var cause : arm.noRecordCauses()) {
            message.append("  - ").append(cause).append(line);
        }
        message.append("A record is matched by the identity of THIS Maven session, never by a file")
                .append(" timestamp, so a leftover from an earlier or IDE run cannot stand in for")
                .append(" one.");
        return message.toString();
    }

    private static String incomplete(Arm arm, Run run, Set<String> missing) {
        var line = System.lineSeparator();
        var annotate = run.annotate() == null ? (UnaryOperator<String>) member -> "" : run.annotate();
        var message = new StringBuilder();
        message.append(arm.tag()).append(" INCOMPLETE RUN: ").append(missing.size()).append(" of ")
                .append(run.tree().size()).append(" ").append(arm.noun()).append(" under ")
                .append(arm.where()).append(" were never executed.").append(line)
                .append("The suite result above is evidence about the other ")
                .append(run.tree().size() - missing.size()).append(" and says nothing about these:")
                .append(line);

        var listed = 0;
        for (var member : missing) {
            if (listed++ == MAX_LISTED) {
                break;
            }
            message.append("  ").append(member).append(annotate.apply(member)).append(line);
        }
        if (missing.size() > MAX_LISTED) {
            message.append("  ... and ").append(missing.size() - MAX_LISTED)
                    .append(" more, not listed here; all of them are in ").append(arm.spillFileName())
                    .append(line);
        }
        message.append(line);
        for (var help : arm.incompleteHelp()) {
            message.append(help).append(line);
        }
        message.append("This bound is derived from the tree, so it cannot be satisfied by editing a"
                       + " number.");
        return message.toString();
    }

    // ------------------------------------------------- the SPA bound, as vitest would see it

    /**
     * True for a path vitest's <em>default</em> {@code include} would match —
     * {@code **}{@code /*.{test,spec}.?(c|m)[jt]s?(x)}, read from the installed 3.2.7's
     * {@code defaults} chunk.
     *
     * <p><strong>Wider than this repo's configured {@code include} on purpose.</strong> The config
     * is {@code src/}{@code **}{@code /*.{test,spec}.{ts,tsx}} — rooted at {@code src/} and limited
     * to two extensions — so a {@code *.test.js} in {@code eslint-rules/}, or a {@code *.test.ts} in
     * {@code audit/}, is a test nobody runs and nothing says so. Bounding on the default shape makes
     * that file <em>demanded</em> and therefore reported absent, loudly, with both remedies named.
     * Loud and wrong about a misplaced file beats quiet and wrong about a test — the same trade the
     * JVM arm makes by asking about names rather than annotations.
     */
    public static boolean matchesVitestDefaultInclude(String posixPath) {
        var slash = posixPath.lastIndexOf('/');
        var fileName = slash < 0 ? posixPath : posixPath.substring(slash + 1);
        return fileName.matches(".+\\.(test|spec)\\.[cm]?[jt]sx?");
    }

    /**
     * True for a path vitest's {@code defaultExclude} drops, plus {@code coverage/} (which
     * {@code eslint.config.js} already ignores and which holds generated copies of the sources).
     *
     * <p>The exclusions are applied even though the bound is derived from {@code git ls-files
     * --exclude-standard}, which already drops every matching file inside
     * {@code src/main/frontend/node_modules} by way of {@code .gitignore} (155 of them on
     * 2026-09-11, and the number is not the point — the directory is). Two mechanisms, because
     * they fail differently: an ignore rule can be edited and the exclusion here cannot be reached
     * by anything in the SPA.
     */
    public static boolean excludedByVitestDefaults(String posixPath) {
        for (var segment : posixPath.split("/")) {
            if (segment.equals("node_modules") || segment.equals("dist") || segment.equals("cypress")
                || segment.equals("coverage")
                || segment.equals(".idea") || segment.equals(".git") || segment.equals(".cache")
                || segment.equals(".output") || segment.equals(".temp")) {
                return true;
            }
        }
        var slash = posixPath.lastIndexOf('/');
        var fileName = slash < 0 ? posixPath : posixPath.substring(slash + 1);
        return fileName.matches("(karma|rollup|webpack|vite|vitest|jest|ava|babel|nyc|cypress|tsup"
                                + "|build|eslint|prettier)\\.config\\..*");
    }

    /**
     * The test count from the recorder's {@code #summary} line, or {@code null} when the record
     * carries none — which is what an interrupted run leaves behind, and is reported as a hole by
     * the file comparison rather than argued about here.
     */
    public static Integer summaryTestCount(List<String> recordLines) {
        Integer found = null;
        for (var raw : recordLines) {
            var line = raw.trim();
            if (!line.startsWith(SUMMARY_PREFIX)) {
                continue;
            }
            for (var token : line.split("\\s+")) {
                if (token.startsWith("tests=")) {
                    try {
                        // Summed, not replaced: one run may write several records (a future
                        // multi-process pool), exactly as the JVM half unions its per-fork files.
                        found = (found == null ? 0 : found)
                                + Integer.parseInt(token.substring("tests=".length()));
                    } catch (NumberFormatException e) {
                        return found;
                    }
                }
            }
        }
        return found;
    }

    /** Every line of a record that is a member rather than the trailing {@code #summary} line. */
    public static Set<String> membersIn(List<String> recordLines) {
        Set<String> members = new TreeSet<>();
        for (var raw : recordLines) {
            var line = raw.trim();
            if (!line.isEmpty() && !line.startsWith(SUMMARY_PREFIX)) {
                members.add(line);
            }
        }
        return members;
    }
}
