package com.hamstrack.common.docs;

import com.hamstrack.ops.PublishedCredentials;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <strong>The git history this repository's document contracts read, and the one comparison they
 * make (HD-304).</strong>
 *
 * <p>It lives beside {@code AgentChecklistFreshnessTest} rather than inside it for the reason
 * {@code VacuousVerification} states for itself: the positive control has to feed fixtures through
 * <em>the same</em> code the live scan uses, and a helper wearing a {@code Test*}/{@code *Test}
 * name would be demanded as a class that must execute by the HD-265 suite-coverage guard.
 *
 * <h2>Which clock</h2>
 * <strong>Committer time on both sides</strong> ({@code committer-time} from blame, {@code %ct}
 * from {@code git log}). A rebase or an amend rewrites both alike, whereas author time would let a
 * cherry-picked old rule look fresh — or a freshly written one look stale — at random.
 *
 * <h2>Which universe</h2>
 * <strong>This checkout, on both sides.</strong> {@code git blame} reports an uncommitted line with
 * sha {@code 0000…}, author/committer {@code Not Committed Yet} and a {@code committer-time} of
 * <em>now</em> (measured 2026-09-08 on git 2.54.0.windows.1: appending one line to {@code CLAUDE.md}
 * and blaming it printed {@code committer-time 1788860945}, equal to the wall clock of the same
 * second). The agent side is measured symmetrically — {@link #newestCommitTouching} for history,
 * pushed to {@code now} by {@link #isDirty} when the directory has uncommitted edits. The owner
 * commits at the end of a session; during one, both sides are uncommitted, and an asymmetric
 * universe would go red mid-session for exactly the behaviour the rule asks for.
 *
 * <h2>Why {@code -w -M}</h2>
 * A whitespace-only edit and a line moved within the file keep their original date; a reworded rule
 * is re-dated on purpose, because a rule somebody rewrote is a rule somebody should have placed.
 */
public final class RepositoryHistory {

    private RepositoryHistory() {
    }

    /**
     * A blame record header: {@code <40 hex sha> <original line> <final line> [<lines in group>]}.
     * Content lines are the only ones beginning with a tab, so the two can never be confused.
     */
    private static final Pattern BLAME_HEADER = Pattern.compile("^([0-9a-f]{40}) (\\d+) (\\d+)(?: (\\d+))?$");

    private static final String COMMITTER_TIME = "committer-time ";

    /**
     * The depth probe, as one array, so the refusal quotes the command that <em>ran</em>. Written
     * out twice it did not: planting {@code --is-shallow-repositoryX} to see the third branch go red
     * produced a message naming {@code --is-shallow-repository}, which sends its reader to look at a
     * command that behaves perfectly.
     */
    private static final String[] SHALLOW_PROBE = {"rev-parse", "--is-shallow-repository"};

    /** Whether history can be read here at all — never whether it can be <em>believed</em>. */
    public sealed interface Availability permits Available, Unavailable {
    }

    /**
     * A work tree with a working {@code git}. Says nothing about depth; see
     * {@link #requireDeepHistory}.
     */
    public record Available() implements Availability {
    }

    /** No work tree, or no {@code git} — carrying the reason, because a silent skip is a lost gate. */
    public record Unavailable(String reason) implements Availability {
    }

    /**
     * Can this directory's history be read? {@code git rev-parse --is-inside-work-tree} answers
     * both questions at once — a missing binary fails to start, a tarball answers {@code false} or
     * exits non-zero.
     *
     * <p>Deliberately <em>not</em> consulted about shallowness: a shallow clone has history that
     * reads cleanly and lies, so it is a refusal (with the {@code fetch-depth} remedy), never a
     * skip. Only an input that cannot exist earns a skip.
     */
    public static Availability probe(Path directory) {
        var result = PublishedCredentials.runGit(directory, "rev-parse", "--is-inside-work-tree");
        if (result.status() == PublishedCredentials.GitResult.NOT_STARTED) {
            return new Unavailable("git could not be started in " + directory.toAbsolutePath()
                                   + " (" + result.error().strip() + ")");
        }
        if (!result.ok() || !"true".equals(result.output().strip())) {
            return new Unavailable("not a git work tree: " + directory.toAbsolutePath()
                                   + " (`git rev-parse --is-inside-work-tree` exited " + result.status()
                                   + ", " + result.detail() + ")");
        }
        return new Available();
    }

    /**
     * Refuses a checkout whose history is truncated, and refuses one that would not say
     * &mdash; <strong>three answers, not two</strong>.
     *
     * <p>A shallow clone ({@code --depth}, {@code actions/checkout}'s default of 1) blames every
     * line to the boundary commit and returns that same commit from every {@code git log -1}, so
     * both sides of a freshness comparison carry HEAD's date and agree for free. The numbers are
     * there and they lie, which is a refusal and never a skip.
     *
     * <p>The third answer is the one a boolean threw away. {@code result.ok() && "true".equals(…)}
     * read <em>everything else</em> &mdash; a git that could not start, a {@code rev-parse} that
     * exited 128, a stdout of {@code trace: …} &mdash; as "not shallow, carry on", which is the
     * vacuous green this class exists to forbid wearing the mask of a passing precondition. Only an
     * exact {@code false} proceeds.
     *
     * <p>It lives here, called by {@link #blameDates} and {@link #newestCommitTouching}, so the
     * precondition travels with the readers it protects: a second caller of this history gets the
     * guard by construction rather than by remembering to copy a line out of a test.
     *
     * @return the answer git actually gave — {@code "false"} — so a caller printing its read-back
     *         prints what was measured rather than the literal it hoped for
     * @throws AssertionError      when the checkout is shallow &mdash; a rule refusing, with the
     *                             remedy its reader can perform
     * @throws IllegalStateException when git would not answer at all, in the same shape as the
     *                             sibling readers below: command, status, stdout, stderr
     */
    public static String requireDeepHistory(Path directory) {
        var result = PublishedCredentials.runGit(directory, SHALLOW_PROBE);
        String answer = result.output().strip();
        if (result.ok() && "false".equals(answer)) {
            return answer;
        }
        if (result.ok() && "true".equals(answer)) {
            org.assertj.core.api.Assertions.fail("""
                    [agent-freshness] this checkout is shallow, so its history is present and untrue.
                    `git rev-parse --is-shallow-repository` said true: every line blames to the boundary
                    commit and `git log -1 -- .claude/agents` returns that same commit, so both sides of
                    the freshness comparison carry HEAD's date and it passes for free. That vacuous green
                    is the thing this test exists to forbid, so it refuses rather than skips.
                      locally: git fetch --unshallow
                      CI:      actions/checkout with `fetch-depth: 0` on the job that runs the suite
                               (.github/workflows/build.yml, job build-and-test)""");
        }
        throw new IllegalStateException("could not determine whether this checkout is shallow: "
                                        + "`git " + String.join(" ", SHALLOW_PROBE) + "` exited "
                                        + result.status() + ", " + result.detail()
                                        + ". Only an exact `false` is read as deep history; anything else "
                                        + "is refused rather than assumed, because assuming it is what "
                                        + "turns a truncated clone into a green freshness check. If a "
                                        + "trace or an alias is writing to stdout here, that is the fix.");
    }

    /**
     * Committer time per <em>final</em> line number of {@code file}, 1-based, from
     * {@code git blame -w -M --line-porcelain}.
     *
     * <p>Preceded by {@link #requireDeepHistory}: a blame taken in a truncated clone answers every
     * line with the boundary commit, which is a date rather than the date.
     *
     * @throws IllegalStateException if git refuses, or if the output carries no line at all — a
     *                               blame map that came back empty would make every freshness
     *                               question pass by having nothing to ask it about.
     */
    public static Map<Integer, Instant> blameDates(Path directory, String file) {
        requireDeepHistory(directory);
        var result = PublishedCredentials.runGit(directory, "blame", "-w", "-M", "--line-porcelain", "--", file);
        if (!result.ok()) {
            throw new IllegalStateException("`git blame -w -M --line-porcelain -- " + file + "` exited "
                                            + result.status() + ", " + result.detail());
        }
        Map<Integer, Instant> dates = new LinkedHashMap<>();
        Integer line = null;
        Instant committed = null;
        for (String raw : result.output().split("\n")) {
            String text = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (text.startsWith("\t")) {
                if (line != null && committed != null) {
                    dates.put(line, committed);
                }
                line = null;
                committed = null;
                continue;
            }
            var header = BLAME_HEADER.matcher(text);
            if (header.matches()) {
                line = Integer.valueOf(header.group(3));
            } else if (text.startsWith(COMMITTER_TIME)) {
                committed = Instant.ofEpochSecond(Long.parseLong(text.substring(COMMITTER_TIME.length()).trim()));
            }
        }
        if (dates.isEmpty()) {
            throw new IllegalStateException("`git blame -- " + file + "` attributed no line; the freshness rule "
                                            + "cannot be asked of a file whose history did not parse");
        }
        return dates;
    }

    /**
     * Committer time of the newest commit touching {@code pathspec}, or empty when no commit ever
     * did. Empty is not "infinitely old": the caller must refuse, because a path that never
     * appeared in history is a path the caller has misspelt.
     */
    public static java.util.Optional<Instant> newestCommitTouching(Path directory, String pathspec) {
        requireDeepHistory(directory);
        var result = PublishedCredentials.runGit(directory, "log", "-1", "--format=%ct", "--", pathspec);
        if (!result.ok()) {
            throw new IllegalStateException("`git log -1 --format=%ct -- " + pathspec + "` exited "
                                            + result.status() + ", " + result.detail());
        }
        String epoch = result.output().trim();
        return epoch.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(Instant.ofEpochSecond(Long.parseLong(epoch)));
    }

    /** Whether {@code pathspec} has uncommitted changes — staged, unstaged or untracked. */
    public static boolean isDirty(Path directory, String pathspec) {
        var result = PublishedCredentials.runGit(directory, "status", "--porcelain", "--", pathspec);
        if (!result.ok()) {
            throw new IllegalStateException("`git status --porcelain -- " + pathspec + "` exited "
                                            + result.status() + ", " + result.detail());
        }
        return !result.output().isBlank();
    }

    /**
     * The whole comparison, as a pure function so it can be controlled with instants instead of
     * with a repository: the lines of {@code lineDates} committed <strong>more than</strong>
     * {@code window} after {@code newestAgentEdit}, ascending.
     *
     * <p>Strictly greater: a line landing exactly {@code window} after the newest agent edit is
     * inside the window, so the boundary belongs to the passing side and a run at the exact tick
     * is not a coin toss.
     */
    static List<Integer> staleLines(Map<Integer, Instant> lineDates, Instant newestAgentEdit, Duration window) {
        Instant deadline = newestAgentEdit.plus(window);
        var stale = new ArrayList<Integer>();
        lineDates.forEach((line, committed) -> {
            if (committed.isAfter(deadline)) {
                stale.add(line);
            }
        });
        stale.sort(null);
        return List.copyOf(stale);
    }
}
