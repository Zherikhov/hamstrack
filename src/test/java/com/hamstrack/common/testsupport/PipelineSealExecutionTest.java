package com.hamstrack.common.testsupport;

import com.hamstrack.ops.PublishedCredentials;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * <strong>HD-330 round 2 — the pipeline's own seals run on an automated path.</strong>
 *
 * <p>{@code .claude/pipeline/check-gates.test.mjs} is the seal of the Stop hook that gates every
 * finish in this repository, and of {@code checkpoint.mjs}, which turns the recorded history into
 * the two-week numbers. Measured 2026-10-08: <strong>nothing ran it and nothing asserted it
 * existed</strong> — not CI ({@code .github/workflows/*.yml}), not {@code pom.xml}, not
 * {@code package.json}, and neither arm of the {@code test-tree-coverage-guard}. Its one caller
 * was a human typing {@code node --test} by hand. The gap was found by the tests gate on HD-330
 * and is the reason this class exists.
 *
 * <p><strong>Why neither coverage arm could ever have caught it</strong> (measured through the
 * guard's own functions, not read): {@code SuiteCoverage.matchesVitestDefaultInclude} returns
 * {@code true} for {@code .claude/pipeline/check-gates.test.mjs} and no default exclusion drops
 * it — the file is invisible to the vitest arm only because that arm's population is a git
 * pathspec rooted at {@code src/main/frontend} (75 files; rooted at {@code .} the same function
 * returns 76 and the extra member is exactly this file). The JVM arm cannot see it for two
 * independent reasons: it walks {@code src/test/java} and it accepts {@code .java} only. Widening
 * the vitest root was the obvious fix and is the wrong one — it would demand a vitest <em>run
 * record</em> for a file vitest cannot run. So the seal is executed from here, where a JUnit
 * class is already inside the arm that counts it.
 *
 * <p><strong>No skip path, by choice.</strong> This class never skips, never assumes and carries
 * no {@code @Disabled}: the input is a {@code node} binary, and the environment guarantees one
 * (see {@link #node()}). A guard that stands down when it cannot find its tool is a guard that
 * cannot fail, which is the shape the 2026-09 retrospective exists to delete — and "a skip in CI
 * is a lost gate". When neither lookup resolves, the refusal names both of them and the repair.
 *
 * <p><strong>The silent-green shape this class is built around, measured rather than assumed.</strong>
 * {@code node --test} on a pattern that matches nothing prints {@code # tests 0} and
 * <strong>exits 0</strong> — reproduced on both node versions in play here (v22.13.0, the version
 * {@code frontend-maven-plugin} pins, and v24.14.0, the system install). A missing <em>file</em>
 * exits 1, but a missing <em>match</em> does not, so an exit code alone is not evidence that a
 * seal ran. Hence the counts are parsed and compared against a bound derived from the seal's own
 * source, and the reporter is pinned to {@code tap}: the default reporter differs between those
 * two node versions ({@code # tests 11} vs {@code ℹ tests 11}), which would have made the parse a
 * coin flip on the runner's node.
 *
 * <p><strong>The category.</strong> Rule: <em>every executable mechanism under
 * {@code .claude/pipeline/} is reached by a seal that something runs without a human.</em> The
 * members are enumerated from git on every run rather than listed here, each population carries a
 * floor, and both halves are needed: {@link #everySealUnderThePipelineDirectoryRunsGreen()} runs
 * the seals, {@link #everyExecutableMechanismIsNamedByASealThisClassRuns()} refuses a mechanism no
 * seal mentions. A new {@code .mjs} dropped into that directory with no seal is a red build.
 */
class PipelineSealExecutionTest {

    private static final Path ROOT = PublishedCredentials.REPO_ROOT;
    private static final String PIPELINE = ".claude/pipeline";

    /**
     * The seal's own test count is the bound, counted from its SOURCE and not from a report: a
     * truncated or half-executed run would truncate a compiled/reported bound in the same stroke
     * and agree with the bug. One {@code test(} per physical line start is the shape this tree
     * writes them in.
     */
    private static final Pattern DECLARED_TEST = Pattern.compile("(?m)^test\\(");

    /**
     * A floor on the DECLARED count, which the derived bound cannot supply on its own: a seal
     * gutted to one trivial case lowers {@code declared} and the run would still agree with it.
     * 13 on 2026-10-09 — 11 shipped by HD-303, the §6 comparison added with this class, and HD-328's
     * four-state run against a real throwaway repository. Set EQUAL to the declared count rather
     * than below it: the floor's only job is to notice a deletion, and leaving slack permits exactly
     * as many silent deletions as the slack. Raise it deliberately; never lower it to make a run
     * pass — say instead which rule stopped mattering.
     */
    private static final int SEAL_TEST_FLOOR = 13;

    /** One seal exists today. The floor is what notices the file being deleted or renamed away. */
    private static final int SEAL_FLOOR = 1;

    /** {@code check-gates.mjs} and {@code checkpoint.mjs} on 2026-10-09. */
    private static final int MECHANISM_FLOOR = 2;

    /**
     * A hang detector, not a budget: the seal spawns one short-lived node process per table row
     * plus a handful of {@code git} invocations, and took ~8 s here on 2026-10-09 — so this leaves
     * an order of magnitude of margin and is still finite. No count of rows is quoted: a number in
     * a javadoc goes stale one entry before the list does, and that list grows every ticket. A
     * bound without margin is how a suite goes red for reasons that are not about the code.
     */
    private static final int TIMEOUT_SECONDS = 180;

    // ----------------------------------------------------------------- the populations

    /** Every seal under the pipeline directory, from git — the index plus untracked, never ignored. */
    private static List<Path> seals() {
        return PublishedCredentials.publishableFiles(PIPELINE).stream()
                .filter(p -> p.getFileName().toString().endsWith(".test.mjs"))
                .sorted()
                .toList();
    }

    /** Every executable mechanism under the pipeline directory: a {@code .mjs} that is not a seal. */
    private static List<Path> mechanisms() {
        return PublishedCredentials.publishableFiles(PIPELINE).stream()
                .filter(p -> p.getFileName().toString().endsWith(".mjs"))
                .filter(p -> !p.getFileName().toString().endsWith(".test.mjs"))
                .sorted()
                .toList();
    }

    /**
     * A repo-relative path for anything inside the tree, and the absolute one for anything outside
     * it — a {@code node} found on {@code PATH} relativises to a ladder of {@code ../..} that reads
     * like a mistake in the very message whose job is to say which lookup won.
     */
    private static String rel(Path file) {
        String relative = ROOT.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize())
                .toString().replace(File.separatorChar, '/');
        return relative.startsWith("..")
                ? file.toAbsolutePath().normalize().toString().replace(File.separatorChar, '/')
                : relative;
    }

    // ----------------------------------------------------------------- the tool

    /**
     * The {@code node} binary, resolved in a defined order, or a refusal naming every lookup that
     * failed.
     *
     * <p>Order, and why: <strong>(1)</strong> {@code target/node/node[.exe]} — the binary
     * {@code frontend-maven-plugin} installs in its {@code install-node-and-npm} execution.
     * {@code installDirectory} is {@code target} and the phase is {@code generate-resources},
     * which runs before {@code test}, so on CI's single {@code ./mvnw -B verify} this binary
     * exists by the time Surefire starts, and it is the version the build pins
     * ({@code v22.13.0}) rather than whatever the runner image happens to ship.
     * <strong>(2)</strong> {@code node}/{@code node.exe} on {@code PATH} — the developer's own
     * install, which is what a {@code -Dfrontend.skip=true} loop on a clean {@code target/} has.
     *
     * <p>There is no third branch and no skip. Anyone who can run the SPA suite has (2), and CI
     * has (1); a machine with neither cannot build this project, and answering "green" there would
     * be worse than answering "red".
     */
    private static Path node() {
        var tried = new ArrayList<String>();
        var pinned = ROOT.resolve("target").resolve("node");
        for (String name : List.of("node.exe", "node")) {
            var candidate = pinned.resolve(name);
            tried.add(rel(candidate) + " (frontend-maven-plugin install)");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        String path = System.getenv("PATH");
        var entries = path == null || path.isBlank() ? List.<String>of() : List.of(path.split(Pattern.quote(File.pathSeparator)));
        for (String dir : entries) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : List.of("node.exe", "node")) {
                try {
                    var candidate = Path.of(dir.replace("\"", "")).resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                } catch (InvalidPathException ignored) {
                    // a PATH entry this platform cannot parse is not a node install
                }
            }
        }
        tried.add("node / node.exe on PATH (" + entries.size() + " entries searched)");
        return fail("""
                No `node` binary resolved, so the pipeline's own seals did not run — and this class \
                refuses instead of standing down, because a guard that cannot run must not report \
                green. Lookups tried, in order:
                %s
                Repair either one: run a build WITHOUT -Dfrontend.skip=true once so \
                frontend-maven-plugin populates target/node (its install-node-and-npm execution, \
                phase generate-resources, pins v22.13.0), or install node on PATH. Do not add a \
                skip here: the seals this runs are the only automated check on \
                .claude/pipeline/check-gates.mjs, which gates every finish in this repository.\
                """.formatted(tried.stream().map(t -> "  - " + t).reduce((a, b) -> a + "\n" + b).orElse("  - none")));
    }

    // ----------------------------------------------------------------- running one seal

    /** What one {@code node --test} run of one seal reported. */
    private record SealRun(Path seal, int exit, Map<String, Integer> counts, String output) {
        int count(String name) {
            return counts.getOrDefault(name, -1);
        }
    }

    private static SealRun run(Path node, Path seal) throws IOException, InterruptedException {
        var command = List.of(node.toAbsolutePath().toString(), "--test", "--test-reporter=tap", rel(seal));
        var builder = new ProcessBuilder(command)
                .directory(ROOT.toFile())
                .redirectErrorStream(true);
        // The seal runs everything under HAMSTRACK_* overrides and clears them itself; stripping
        // them here too means a developer's exported variable cannot aim a fixture run at the real
        // history.jsonl through this class.
        builder.environment().keySet().removeIf(key -> key.startsWith("HAMSTRACK_"));
        var process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return fail("`%s` did not finish within %d s — the seal hung rather than failing. "
                        + "Run it by hand and read where it stopped; output so far:%n%s",
                    String.join(" ", command), TIMEOUT_SECONDS, output);
        }
        var counts = new LinkedHashMap<String, Integer>();
        for (String name : List.of("tests", "pass", "fail", "skipped", "cancelled", "todo")) {
            var matcher = Pattern.compile("(?m)^# " + name + " (\\d+)$").matcher(output);
            if (matcher.find()) {
                counts.put(name, Integer.parseInt(matcher.group(1)));
            }
        }
        return new SealRun(seal, process.exitValue(), counts, output);
    }

    // ----------------------------------------------------------------- the tests

    /**
     * The resolution itself, asserted in a method that always runs (HD-304): the guarantee that a
     * {@code node} exists is the premise every other method here leans on, and a premise checked
     * only inside a conditional is a premise that can be swallowed.
     */
    @Test
    void theNodeBinaryResolvesAndReportsItsVersion() throws IOException, InterruptedException {
        Path node = node();
        var builder = new ProcessBuilder(node.toAbsolutePath().toString(), "--version")
                .directory(ROOT.toFile())
                .redirectErrorStream(true);
        var process = builder.start();
        String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("`node --version` did not finish within %d s", TIMEOUT_SECONDS)
                .isTrue();
        assertThat(process.exitValue())
                .as("`%s --version` exited %d, so the binary resolved but does not run: %s",
                        rel(node), process.exitValue(), version)
                .isZero();
        assertThat(version)
                .as("`%s --version` printed %s — a resolved path that is not a node binary is "
                    + "not a resolved tool", rel(node), version)
                .matches("v\\d+\\.\\d+\\.\\d+");
        System.out.println("[pipeline-seal] node " + version + " resolved from " + rel(node)
                           + "; lookup order: target/node (frontend-maven-plugin), then PATH");
    }

    /**
     * Every seal runs, and its reported counts agree with its own source.
     *
     * <p>Four separate ways this can go wrong, and all four are refused: the seal file is gone
     * (the population floor), the run reported a failure, the run was all-green but executed
     * fewer tests than the file declares (the {@code # tests 0} / exit-0 shape above), and a test
     * stood down inside the seal — {@code skipped} must be zero, because
     * {@code VacuousVerificationRulesTest}'s populations are {@code src/test/java/**.java} and
     * {@code src/main/frontend/src/**.{test,spec}.{ts,tsx}}, so a {@code .mjs} seal is in neither
     * and nothing else refuses a silent skip in it.
     */
    @Test
    void everySealUnderThePipelineDirectoryRunsGreen() throws IOException, InterruptedException {
        Path node = node();
        List<Path> seals = seals();
        assertThat(seals)
                .as("""
                        No seal found under %s/. The pipeline's Stop hook and checkpoint are then \
                        held by nothing that runs: a *.test.mjs there was deleted or renamed out of \
                        the shape, or `git ls-files` stopped reporting it. Restore the seal, or if \
                        the mechanism it sealed is gone, delete the mechanism too — the one thing \
                        this must not become is an empty scan that passes.""", PIPELINE)
                .hasSizeGreaterThanOrEqualTo(SEAL_FLOOR);

        var witness = new TreeSet<String>();
        for (Path seal : seals) {
            String source = PublishedCredentials.read(seal);
            long declared = DECLARED_TEST.matcher(source).results().count();
            assertThat(declared)
                    .as("""
                            %s declares %d test(s); the floor is %d. A seal reduced to a handful of \
                            cases reports green against its own shrunken bound, which is why the \
                            derived count cannot be the only check. If tests were genuinely \
                            retired, say which rule stopped mattering and lower this floor in the \
                            same change.""", rel(seal), declared, SEAL_TEST_FLOOR)
                    .isGreaterThanOrEqualTo(SEAL_TEST_FLOOR);

            SealRun result = run(node, seal);
            assertThat(result.exit())
                    .as("`node --test %s` exited %d. Output:%n%s", rel(seal), result.exit(), result.output())
                    .isZero();
            assertThat(result.count("fail"))
                    .as("`node --test %s` reported %d failing test(s). Output:%n%s",
                            rel(seal), result.count("fail"), result.output())
                    .isZero();
            assertThat(result.count("tests"))
                    .as("""
                            `node --test %s` reported %d test(s) but the file declares %d. \
                            `node --test` exits 0 on a pattern that matches NOTHING (measured on \
                            v22.13.0 and v24.14.0: `# tests 0`, exit 0), so a green exit code is \
                            not evidence that the seal ran. Either the run selected fewer files \
                            than it was given, or tests are declared in a shape this bound cannot \
                            count — the bound is `^test(` at the start of a line.""",
                            rel(seal), result.count("tests"), declared)
                    .isGreaterThanOrEqualTo((int) declared);
            assertThat(result.count("pass"))
                    .as("`node --test %s`: %d of %d test(s) passed. Output:%n%s",
                            rel(seal), result.count("pass"), result.count("tests"), result.output())
                    .isEqualTo(result.count("tests"));
            assertThat(result.count("skipped"))
                    .as("""
                            `node --test %s` skipped %d test(s). A skip here is a lost gate and \
                            nothing else refuses it: this file is outside both populations \
                            VacuousVerificationRulesTest scans. If the skip is right, the input \
                            really cannot exist — say so in the seal and reference the ticket.""",
                            rel(seal), result.count("skipped"))
                    .isZero();
            witness.add(rel(seal) + " -> " + result.count("tests") + " tests, "
                        + result.count("pass") + " pass, " + result.count("fail") + " fail, "
                        + result.count("skipped") + " skipped (declares " + declared + ")");
        }
        System.out.println("[pipeline-seal] " + seals.size() + " seal(s) executed: "
                           + String.join("; ", witness));
    }

    /**
     * Every executable mechanism under the pipeline directory is named by a seal this class runs.
     *
     * <p>This is the half that notices a <em>new</em> mechanism rather than a broken one: the
     * failure HD-330 found was not a seal going red, it was a file nothing was answerable for. A
     * name in a seal is a weaker claim than an invocation and is deliberately the bound chosen —
     * the strong half is {@link #everySealUnderThePipelineDirectoryRunsGreen()}, which actually
     * executes the seal, and inside it the two mechanisms are spawned.
     */
    @Test
    void everyExecutableMechanismIsNamedByASealThisClassRuns() {
        List<Path> mechanisms = mechanisms();
        List<Path> seals = seals();
        assertThat(mechanisms)
                .as("""
                        No executable mechanism found under %s/, which cannot be right while the \
                        Stop hook gates every finish in this repository. `git ls-files` over that \
                        directory returned no non-test .mjs — check that the pathspec still \
                        matches and that a .gitignore rule has not swallowed the directory.""",
                        PIPELINE)
                .hasSizeGreaterThanOrEqualTo(MECHANISM_FLOOR);

        var sealSources = new LinkedHashMap<Path, String>();
        for (Path seal : seals) {
            sealSources.put(seal, PublishedCredentials.read(seal));
        }
        var unsealed = new ArrayList<String>();
        for (Path mechanism : mechanisms) {
            String name = mechanism.getFileName().toString();
            boolean named = sealSources.values().stream().anyMatch(source -> source.contains(name));
            if (!named) {
                unsealed.add(rel(mechanism));
            }
        }
        assertThat(unsealed)
                .as("""
                        %d mechanism(s) under %s/ are named by no seal, so nothing runs them and \
                        nothing would notice them breaking: %s. The seals that were searched: %s. \
                        Add cases for the new mechanism to an existing seal (it is then executed by \
                        everySealUnderThePipelineDirectoryRunsGreen with no further wiring), or add \
                        a *.test.mjs beside it — the shape is `node --test`, everything under \
                        HAMSTRACK_* overrides so no run reaches the real history.""",
                        unsealed.size(), PIPELINE, unsealed, seals.stream().map(PipelineSealExecutionTest::rel).toList())
                .isEmpty();
        System.out.println("[pipeline-seal] " + mechanisms.size() + " mechanism(s) sealed: "
                           + mechanisms.stream().map(PipelineSealExecutionTest::rel).toList());
    }
}
