package com.hamstrack.common.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.common.testsupport.VacuousVerification.Language;
import com.hamstrack.common.testsupport.VacuousVerification.Offence;
import com.hamstrack.ops.PublishedCredentials;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-295 — vacuous verification cannot be typed by habit.</strong>
 *
 * <p>Three guards that pass while checking nothing have shipped here: 1258 bare
 * {@code assert}s inert under any IDE (no {@code -ea}; HD-164), a type-check pointed at a
 * solution-style {@code tsconfig.json} where it checks zero files — while being the first
 * command an agent was told to run — and a skipped test reading as a pass in a summary line.
 * The HD-265 suite-coverage guard cannot see the third: it counts a class-level skip as
 * present. This class refuses all three over the whole corpus, not over the members that
 * happen to carry one today (there are none — see the positive control, which is why the
 * fixtures exist).
 *
 * <p><strong>The populations and their floors</strong> — P1, every backend test source
 * (floor 250); P2, every frontend test source, defined to equal vitest's own {@code include}
 * glob (floor 50); P3, every script in {@code package.json} (floor 6). Each scan asserts its
 * floor before it asserts that it found nothing, because "found nothing" and "looked at
 * nothing" produce the same green line. Raise a floor deliberately; never lower one to make a
 * run pass. The floor is the whole claim, and it is the only number written down here: a
 * message that also quoted today's population went stale one file before the list did, which
 * is how the round-1 review found three of them disagreeing with the corpus.
 *
 * <p><strong>The positive control</strong> feeds {@code src/test/resources/vacuous-verification}
 * through the same predicate the corpus scans use. Every case line in those fixtures carries
 * one of two sentinels — {@code EXPECT-REPORT} for a line the predicate must report,
 * {@code EXPECT-CLEAN} for one it must stay silent on — and the expectation is derived from
 * them, so a case that moves takes its expectation with it. The fixtures are files rather than
 * literals for a mechanical reason: string literals are stripped before matching, so a marker
 * written inline in this class would be invisible to it.
 *
 * <p><strong>What proves the wiring, as opposed to the predicate.</strong> A fixture cannot
 * show that the populations, the stripping and the line numbers agree on real files. That is
 * AC-4: the four markers were planted one at a time in real members — a bare skip annotation
 * in a P1 test, a bare {@code assert} in another, a skipped case in a P2 file, and a
 * {@code --noEmit} type-check in P3 — each watched red, each reverted, and the class watched
 * green afterwards. The red lines are in the ticket.
 *
 * <p>Until HD-297 lands this class is authoritative for R2 as well; if that ticket
 * re-implements the bare-{@code assert} rule in ArchUnit, the regex here is deleted in the
 * same commit. Two rules for one thing diverge, and the one nobody watches is the one that
 * matters.
 */
class VacuousVerificationRulesTest {

    /** P1 — every backend test source, helpers included. */
    private static final Pattern BACKEND_TEST_SOURCE =
            Pattern.compile("src/test/java/.*\\.java");

    /**
     * P2 — every frontend test source. This must stay equal to vitest's {@code include}
     * ({@code vitest.config.ts}); {@link #frontendPopulationStillEqualsTheVitestInclude()}
     * is what holds the two together, because a file vitest runs and this does not scan is
     * exactly the file a skip would hide in.
     */
    private static final Pattern FRONTEND_TEST_SOURCE =
            Pattern.compile("src/main/frontend/src/.*\\.(?:test|spec)\\.(?:ts|tsx)");

    private static final int BACKEND_FLOOR = 250;
    private static final int FRONTEND_FLOOR = 50;
    private static final int SCRIPT_FLOOR = 6;
    private static final int FIXTURE_FLOOR = 3;
    private static final int REPORT_SENTINEL_FLOOR = 18;
    private static final int CLEAN_SENTINEL_FLOOR = 26;

    private static final Path PACKAGE_JSON = Path.of("src", "main", "frontend", "package.json");
    private static final Path VITEST_CONFIG = Path.of("src", "main", "frontend", "vitest.config.ts");
    private static final String VITEST_INCLUDE = "src/**/*.{test,spec}.{ts,tsx}";

    /**
     * The whole {@code include:} line, with exactly one element — {@code contains} on the glob
     * alone stays green while a second entry adds files this scan never reads, which is a
     * widened population wearing the old assertion's green line.
     */
    private static final Pattern VITEST_INCLUDE_LINE = Pattern.compile(
            "(?m)^\\s*include:\\s*\\[" + Pattern.quote("'" + VITEST_INCLUDE + "'") + "],?\\s*$");

    /** The type-check this repository has: the solution-style build, never a bare invocation. */
    private static final String TYPECHECK_COMMAND = "tsc -b";

    /**
     * An invocation of the compiler that is not the build. Both spellings of the build flag are
     * the build: {@code --build} is what {@code -b} abbreviates, and flagging it would teach
     * the next reader that this rule is about the characters rather than about what is checked.
     */
    private static final Pattern LOOSE_TSC = Pattern.compile("\\btsc\\b(?!\\s+(?:-b|--build)\\b)");

    private static final String FIXTURES = "src/test/resources/vacuous-verification/";
    private static final String EXPECT_REPORT = "EXPECT-REPORT";
    private static final String EXPECT_CLEAN = "EXPECT-CLEAN";

    private static final String SKIP_REMEDY = """
            Put HD-<n> on the same physical line, in the reason string or a trailing comment;
            a skip with no ticket is a pass nobody earned, and the suite-coverage guard counts
            a skipped class as present. A conditional gate (@DisabledIf…, .skipIf) carries its
            own reason and is outside this rule — if that is what you meant, use one.""";

    private static final String ASSERT_REMEDY = """
            Use assertThat(...): a bare `assert` runs only under -ea, which Surefire passes and
            no IDE does, so the check is inert for whoever is actually debugging it (HD-164).""";

    // ------------------------------------------------------------------ R1, over P1 and P2

    @Test
    void noBackendTestSkipsWithoutATicket() {
        var files = backendTestSources();
        var offences = new ArrayList<Offence>();
        for (Path file : files) {
            offences.addAll(VacuousVerification.skipsWithoutTicket(
                    PublishedCredentials.repositoryPath(file), read(file), Language.JAVA));
        }
        assertThat(offences)
                .as("%s", VacuousVerification.report(
                        "A backend test is skipped with no ticket on the line:",
                        offences, SKIP_REMEDY))
                .isEmpty();
    }

    @Test
    void noFrontendTestSkipsWithoutATicket() {
        var files = frontendTestSources();
        var offences = new ArrayList<Offence>();
        for (Path file : files) {
            offences.addAll(VacuousVerification.skipsWithoutTicket(
                    PublishedCredentials.repositoryPath(file), read(file), Language.TYPESCRIPT));
        }
        assertThat(offences)
                .as("%s", VacuousVerification.report(
                        "A frontend test is skipped (or narrowed by .only) with no ticket:",
                        offences, SKIP_REMEDY))
                .isEmpty();
    }

    /**
     * P2 is defined as "what vitest runs". Asserted rather than assumed: the day the glob is
     * widened, this scan keeps returning a green line about a population that no longer is
     * the population.
     */
    @Test
    void frontendPopulationStillEqualsTheVitestInclude() {
        assertThat(read(VITEST_CONFIG))
                .as("""
                        %s no longer declares include as exactly ['%s'] — one element, that one.

                        P2 in this class is defined AS that glob. A SECOND element is the case
                        this matches the whole line for: it leaves the first one in place, so a
                        `contains` check stays green while vitest runs files this scan never
                        reads, and a skip in one of them is invisible here. Adding an element
                        is therefore an edit to FRONTEND_TEST_SOURCE in the same commit — and
                        to nothing else here. Do not delete this assertion.""",
                        VITEST_CONFIG, VITEST_INCLUDE)
                .containsPattern(VITEST_INCLUDE_LINE);
    }

    // ------------------------------------------------------------------------- R2, over P1

    @Test
    void noBackendTestLeansOnABareAssert() {
        var files = backendTestSources();
        var offences = new ArrayList<Offence>();
        for (Path file : files) {
            offences.addAll(VacuousVerification.bareAsserts(
                    PublishedCredentials.repositoryPath(file), read(file)));
        }
        assertThat(offences)
                .as("%s", VacuousVerification.report(
                        "A backend test checks something with a bare `assert`:",
                        offences, ASSERT_REMEDY))
                .isEmpty();
    }

    // ------------------------------------------------------------------------- R3, over P3

    @Test
    void everyScriptThatTypeChecksRunsTheBuild() throws IOException {
        String raw = read(PACKAGE_JSON);
        JsonNode scripts = new ObjectMapper().readTree(raw).path("scripts");

        assertThat(scripts.size())
                .as("the scan saw %d scripts in %s, under the floor of %d — the `scripts` "
                        + "object moved or is no longer an object, and a rule about scripts that "
                        + "reads none of them is green for free. Do not lower this floor",
                        scripts.size(), PACKAGE_JSON, SCRIPT_FLOOR)
                .isGreaterThanOrEqualTo(SCRIPT_FLOOR);

        var offences = new ArrayList<Offence>();
        for (Iterator<String> names = scripts.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            String command = scripts.path(name).asText("");
            if (LOOSE_TSC.matcher(command).find()) {
                offences.add(new Offence(repositoryPathOf(PACKAGE_JSON), lineOf(raw, name),
                        "script `" + name + "` runs `" + command + "`"));
            }
        }
        assertThat(offences)
                .as("%s", VacuousVerification.report(
                        "A script invokes tsc without -b:",
                        offences, """
                                Run the compiler as `tsc -b`. The root tsconfig.json is
                                solution-style (files: [] + references), so any other
                                invocation — --noEmit above all — type-checks ZERO files and
                                exits 0: a gate that cannot fail."""))
                .isEmpty();

        assertThat(scripts.path("typecheck").asText(""))
                .as("""
                        %s has no `typecheck` script equal to `%s`.

                        It is the name the docs and the agents point at, so that "type-check
                        the frontend" resolves to one command that actually checks files
                        rather than to whatever the reader remembers.""",
                        PACKAGE_JSON, TYPECHECK_COMMAND)
                .isEqualTo(TYPECHECK_COMMAND);
    }

    // ------------------------------------------------------- the positive control (AC-1, AC-2)

    @Test
    void thePredicateReportsExactlyThePlantedLines() {
        var fixtures = trackedUnder(FIXTURES);
        assertThat(fixtures)
                .as("the scan saw %d fixtures under %s, under the floor of %d — both real "
                        + "populations are clean today, so these files are the ONLY proof this "
                        + "predicate reports anything at all. Do not lower this floor",
                        fixtures.size(), FIXTURES, FIXTURE_FLOOR)
                .hasSizeGreaterThanOrEqualTo(FIXTURE_FLOOR);

        var reported = new ArrayList<String>();
        var expected = new ArrayList<String>();
        var cleanCases = 0;
        for (Path fixture : fixtures) {
            String path = repositoryPathOf(fixture);
            String source = read(fixture);
            boolean java = path.endsWith(".java.txt");
            var offences = new ArrayList<>(VacuousVerification.skipsWithoutTicket(
                    path, source, java ? Language.JAVA : Language.TYPESCRIPT));
            if (java) {
                offences.addAll(VacuousVerification.bareAsserts(path, source));
            }
            offences.stream().map(o -> path + ":" + o.line()).distinct().forEach(reported::add);

            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(EXPECT_REPORT)) {
                    expected.add(path + ":" + (i + 1));
                } else if (lines[i].contains(EXPECT_CLEAN)) {
                    cleanCases++;
                }
            }
        }

        assertThat(expected.size())
                .as("the fixtures declare %d lines that must be reported, under the floor of %d "
                        + "— a case that lost its sentinel is a case this control no longer "
                        + "makes. Do not lower this floor",
                        expected.size(), REPORT_SENTINEL_FLOOR)
                .isGreaterThanOrEqualTo(REPORT_SENTINEL_FLOOR);
        assertThat(cleanCases)
                .as("the fixtures declare %d lines that must NOT be reported, under the floor of "
                        + "%d — the near misses (a ticket in the reason string, a conditional "
                        + "gate, a run-order modifier with no skip behind it, a marker in a "
                        + "comment or a literal) are what stops this rule from reporting "
                        + "everything. Do not lower this floor",
                        cleanCases, CLEAN_SENTINEL_FLOOR)
                .isGreaterThanOrEqualTo(CLEAN_SENTINEL_FLOOR);

        assertThat(reported)
                .as("""
                        The predicate did not report exactly the planted lines.

                        Every case line in %s carries a sentinel: %s means the predicate must
                        report that line, %s means it must stay silent. A missing line is a
                        marker the rule can no longer see; an extra one is a legitimate skip
                        the rule would now refuse. Fix the predicate, not the sentinel.""",
                        FIXTURES, EXPECT_REPORT, EXPECT_CLEAN)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    // ---------------------------------------------------------------- populations and plumbing

    /**
     * P1, with its floor. The floor lives here rather than in a test of its own so that every
     * scan over this population asserts it: a rule of the form "nothing offends" is green over
     * an empty list, and a mis-rooted or narrowed population produces exactly that list.
     */
    private List<Path> backendTestSources() {
        var files = tracked(BACKEND_TEST_SOURCE);
        assertThat(files.size())
                .as("the scan saw %d backend test sources, under the floor of %d — find which "
                        + "population stopped matching; do not lower the "
                        + "floor. Every rule below is of the form \"nothing offends\", so a scan "
                        + "that stopped seeing files reports clean forever",
                        files.size(), BACKEND_FLOOR)
                .isGreaterThanOrEqualTo(BACKEND_FLOOR);
        return files;
    }

    /** P2, with its floor — same reasoning as {@link #backendTestSources()}. */
    private List<Path> frontendTestSources() {
        var files = tracked(FRONTEND_TEST_SOURCE);
        assertThat(files.size())
                .as("the scan saw %d frontend test sources, under the floor of %d — find which "
                        + "population stopped matching; do not lower the "
                        + "floor. Check FRONTEND_TEST_SOURCE against vitest's include glob "
                        + "first: a file vitest runs and this does not read is invisible here",
                        files.size(), FRONTEND_FLOOR)
                .isGreaterThanOrEqualTo(FRONTEND_FLOOR);
        return files;
    }

    /**
     * Read from the index, not from the working tree ({@code PublishedCredentials.trackedFiles},
     * which already refuses an empty listing): a file nobody else has is not part of any
     * population, and a checkout must not pass or fail on files that are not in it.
     */
    private static List<Path> tracked(Pattern path) {
        return PublishedCredentials.trackedFiles().stream()
                .filter(file -> path.matcher(PublishedCredentials.repositoryPath(file)).matches())
                .toList();
    }

    private static List<Path> trackedUnder(String directory) {
        return PublishedCredentials.trackedFiles().stream()
                .filter(file -> PublishedCredentials.repositoryPath(file).startsWith(directory))
                .toList();
    }

    private static String repositoryPathOf(Path file) {
        return PublishedCredentials.repositoryPath(file);
    }

    private static String read(Path file) {
        return PublishedCredentials.read(file);
    }

    /** The first line carrying {@code "key"} — a JSON parser has no line numbers to offer. */
    private static int lineOf(String raw, String key) {
        String[] lines = raw.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("\"" + key + "\"")) {
                return i + 1;
            }
        }
        return 1;
    }
}
