package com.hamstrack.common.docs;

import com.hamstrack.ops.PublishedCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * <strong>HD-304 &mdash; the rules a session reads are held equal to the checklists that act on
 * them, and the bench and gate set are held equal to the router that dispatches them.</strong>
 *
 * <h2>The failure this replaces</h2>
 * The 2026-09 retrospective's third root: the agent files froze on 2026-08-06/13 while roughly
 * twenty rules landed in {@code CLAUDE.md}, so every reviewer spent a month hunting the previous
 * month's defect classes. HD-293 moved the rules into the agent files and made Phase 7 ask
 * <em>"which agent checklist grows from this ticket"</em> &mdash; but the honest answer is often
 * "none", and nothing fails when it is "none" for two months. That is the gap here: not a wrong
 * answer, an unread question.
 *
 * <h2>What it asserts</h2>
 * <ul>
 *   <li><strong>R1 &mdash; freshness.</strong> Every non-blank line of {@code CLAUDE.md}'s
 *       &sect; Gotchas and &sect; Quality rules sections is committed no more than
 *       {@link #AGENT_LEARNING_WINDOW} after the newest edit to any file in
 *       {@code .claude/agents/}. The comparison itself is
 *       {@link RepositoryHistory#staleLines} &mdash; a pure function, so it is controlled with
 *       instants rather than with a repository.</li>
 *   <li><strong>R2 &mdash; the bench.</strong> {@code .claude/agents/} holds exactly the eleven
 *       {@link #BENCH} files, each declaring a {@code model:} and an {@code effort:} from the
 *       lists the design permits, and the three copies of that statement (CLAUDE.md
 *       &sect; Subagents with its {@code (model/effort)} pairs, {@code SKILL.md} &sect; The bench,
 *       {@code check-gates.mjs}'s {@code AGENT} map) agree with the frontmatter.</li>
 *   <li><strong>R3 &mdash; the gate set.</strong> Four independently parsed sources each state
 *       {@link #GATES} exactly, and each yields at least one name &mdash; a regex that stopped
 *       matching is not agreement.</li>
 * </ul>
 *
 * <h2>Where it disarms, and where it refuses</h2>
 * The distinction is the lesson, not the mechanics: <em>a contract test disarms only where its
 * input cannot exist, and refuses where the input is present but untrue.</em>
 * <ul>
 *   <li><strong>No work tree</strong> (a source tarball, no {@code git} on {@code PATH}): the two
 *       history assertions are skipped with the reason printed, because the dates genuinely are
 *       not there. The bench and gate assertions still run &mdash; a tarball still has the
 *       files.</li>
 *   <li><strong>Shallow clone</strong>: refused. Every line blames to the boundary commit and
 *       {@code git log -1} returns the same commit, so both sides carry HEAD's date and the
 *       comparison passes for free. That is the exact vacuous green this test exists to forbid,
 *       and it is the CI default &mdash; hence {@code fetch-depth: 0} on the {@code build-and-test}
 *       checkout.</li>
 *   <li><strong>Under {@code GITHUB_ACTIONS} with history unreadable</strong>: refused by
 *       {@link #ciCannotLoseTheHistoryAssertionToASkip()}, which always runs. CI is a checkout by
 *       construction, so a skip there is a lost gate rather than an honest absence.</li>
 * </ul>
 *
 * <h2>What it structurally cannot see</h2>
 * Whether an agent edit <em>taught</em> anything. Touching a checklist resets this clock, so a
 * whitespace commit under {@code .claude/agents/} buys fourteen days and teaches nobody. The test
 * measures activity; the failure message says not to do that; nothing here can observe it.
 */
class AgentChecklistFreshnessTest {

    /**
     * How long a rule may sit in {@code CLAUDE.md} before some agent checklist has to have moved.
     *
     * <p><strong>Fourteen days, because that is the HD-303 checkpoint's cadence</strong>
     * ({@code dev-team-pipeline.md} &sect;12.6, first run 2026-09-21): a stale bench and the
     * numbers that would reveal it are then read on one rhythm rather than on two that drift.
     * Shorter goes red inside a normal two-week branch whose agent edit is queued for its Phase 7,
     * which trains people to ignore it. A month <em>is</em> the failure being replaced &mdash; the
     * bench froze for four weeks and nobody noticed.
     */
    static final Duration AGENT_LEARNING_WINDOW = Duration.ofDays(14);

    /** The eleven project-native agents (HD-293). Not a default: the router knows these names. */
    static final List<String> BENCH = List.of(
            "api-docs-sync", "backend-builder", "browser-qa", "dc-cloud-guard", "frontend-builder",
            "migration-reviewer", "ops-reviewer", "security-officer", "systems-analyst",
            "tenancy-reviewer", "test-runner");

    /**
     * The models the bench design permits. The runtime also accepts {@code haiku}, {@code inherit}
     * and a full model id; the design rules out {@code inherit} ("uniform inherit is wrong in both
     * directions") and puts nobody on {@code haiku}, so those arriving in a frontmatter is a
     * decision that was never written down anywhere.
     */
    static final Set<String> MODELS = Set.of("fable", "opus", "sonnet");

    /**
     * The efforts the bench design permits. {@code max} is accepted by the runtime and recommended
     * by the design nowhere; omitting {@code effort:} entirely is explicitly refused there.
     */
    static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh");

    /** The gate names the pipeline enforces. One set, four statements of it, checked against each. */
    static final Set<String> GATES = Set.of(
            "spec", "tenancy", "security", "tests", "migration", "dc_cloud", "api_docs",
            "ops_witness", "ui_qa");

    private static final Path ROOT = PublishedCredentials.REPO_ROOT;
    private static final String CLAUDE_MD = "CLAUDE.md";
    private static final String AGENTS_PATHSPEC = ".claude/agents";
    private static final Path AGENTS_DIR = ROOT.resolve(".claude/agents");
    private static final Path SKILL_MD = ROOT.resolve(".claude/skills/feature-pipeline/SKILL.md");
    private static final Path CHECK_GATES = ROOT.resolve(".claude/pipeline/check-gates.mjs");

    private static final Pattern HEADING = Pattern.compile("^## ");
    private static final Pattern GOTCHAS_HEADING = Pattern.compile("^## Gotchas\\b");
    private static final Pattern QUALITY_HEADING = Pattern.compile("^## Quality rules\\b");
    private static final Pattern SUBAGENTS_HEADING = Pattern.compile("^## Subagents\\b");
    private static final Pattern BENCH_HEADING = Pattern.compile("^## The bench\\b");

    /** {@code - **`agent-name`** (model/effort) — …}, the CLAUDE.md &sect; Subagents bullet shape. */
    private static final Pattern SUBAGENT_BULLET =
            Pattern.compile("^- \\*\\*`([a-z-]+)`\\*\\* \\((\\w+)/(\\w+)\\)");

    /** A backticked hyphenated identifier — what an agent name looks like in SKILL.md's prose. */
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
    private static final Pattern AGENT_NAME_SHAPE = Pattern.compile("^[a-z]+(-[a-z]+)+$");

    private static final Pattern AGENT_MAP =
            Pattern.compile("const\\s+AGENT\\s*=\\s*\\{(.*?)}\\s*;", Pattern.DOTALL);
    private static final Pattern AGENT_ENTRY = Pattern.compile("([a-z_]+)\\s*:\\s*\"([^\"]+)\"");
    /** Same widening as {@link #GATE_TAG}: a misspelt name is named, not dropped. */
    private static final Pattern REQUIRED_ADD = Pattern.compile("required\\.add\\(\"([a-z_-]+)\"\\)");
    /**
     * A phase-heading tag. The hyphen is inside the class on purpose: a gate misspelt {@code ui-qa}
     * must be <em>read and named</em>, not silently dropped along with the four correct names that
     * share its tag — a parser that stops matching reports the wrong four files.
     */
    private static final Pattern GATE_TAG = Pattern.compile("\\[gates?:\\s*([a-z_,\\s-]+)]");

    /**
     * A gate named in an agent's {@code description:} prose. Only a snake_case identifier is read
     * as a name: the word after "gate" is usually English ("Spec gate on features"), and an
     * identifier is what a rename would have to change.
     */
    private static final Pattern GATE_MENTION = Pattern.compile("\\bgate ([a-z]+(?:_[a-z]+)+)\\b");

    private static final Pattern FRONTMATTER_ENTRY = Pattern.compile("^([a-z_]+):\\s*(.*)$");

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    /** How many offending lines a failure prints before spilling to a count — see the 25-line bound. */
    private static final int MAX_LISTED = 15;

    // ------------------------------------------------------------------ R1: freshness

    @Test
    @EnabledIf(value = "historyIsMeasurable", // HD-304: a tarball has the files but not the dates; CI is refused below.
            disabledReason = "no readable git history here — see the printed [agent-freshness] disarmed line")
    void everyRuleLineHasAnAgentThatLearnedItInsideTheWindow() {
        String shallow = refuseTruncatedHistory();

        var population = ruleCorpus();
        var dates = datesOf(population);
        Instant agents = newestAgentEdit();

        var stale = RepositoryHistory.staleLines(dates, agents, AGENT_LEARNING_WINDOW);
        if (!stale.isEmpty()) {
            fail(staleMessage(stale, dates, population, agents));
        }

        var newest = dates.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow();
        long margin = Math.max(0, Duration.between(agents, newest.getValue()).toDays());
        System.out.println("[agent-freshness] armed: newest rule " + DAY.format(newest.getValue())
                           + " (CLAUDE.md:" + newest.getKey() + "), newest agent edit " + DAY.format(agents)
                           + ", margin " + margin + " d of " + AGENT_LEARNING_WINDOW.toDays()
                           + "; " + population.size() + " rule lines scanned"
                           // The measured read-back, not a literal: this line is what a reader greps to
                           // see that the run was armed AND that its history was whole.
                           + ", shallow=" + shallow);
    }

    /**
     * Positive control for the comparison itself, with instants instead of a repository: one line
     * past the window, one at exactly the window, one inside it. The boundary case is the point
     * &mdash; a run at the exact tick must land on the passing side deterministically.
     */
    @Test
    void staleLinesNamesWhatIsPastTheWindowAndKeepsTheBoundaryInside() {
        Instant agents = Instant.parse("2026-09-01T00:00:00Z");
        var lines = Map.of(
                10, agents.plus(Duration.ofDays(20)),
                20, agents.plus(AGENT_LEARNING_WINDOW),
                30, agents.plus(Duration.ofDays(1)));

        assertThat(RepositoryHistory.staleLines(lines, agents, AGENT_LEARNING_WINDOW))
                .withFailMessage("staleLines must name only the line past the window, and must treat a line "
                                 + "landing exactly at the window as inside it")
                .containsExactly(10);
    }

    /**
     * The same comparison over the <em>real</em> blame map, with the agent side pinned to a date
     * before the current rules were written. It is the witness that the parsing half works: the
     * synthetic control above proves the arithmetic, and this proves that {@code git blame} was
     * read, the sections were found, and the numbers a failure would print are real line numbers.
     */
    @Test
    @EnabledIf(value = "historyIsMeasurable", // HD-304: same input as the assertion above.
            disabledReason = "no readable git history here — see the printed [agent-freshness] disarmed line")
    void theSameComparisonNamesTodaysRulesWhenTheBenchIsPinnedToAnOlderDate() {
        refuseTruncatedHistory();

        var population = ruleCorpus();
        var dates = datesOf(population);
        Instant pinned = Instant.parse("2026-08-20T00:00:00Z");

        var stale = RepositoryHistory.staleLines(dates, pinned, AGENT_LEARNING_WINDOW);
        int qualityRuleThree = lineContaining(population, "The process definitions are artefacts");
        int tscGotcha = lineContaining(population, "type-check that type-checks NOTHING",
                "tsc --noEmit` in `src/main/frontend` type-checks NOTHING");

        assertThat(stale)
                .withFailMessage("%s", "With the bench pinned to " + DAY.format(pinned) + ", the rules written "
                                       + "on 2026-09-07/08 must be named. staleLines returned " + stale
                                       + "; expected it to contain CLAUDE.md:" + qualityRuleThree
                                       + " and CLAUDE.md:" + tscGotcha + ". If those lines were rewritten out of "
                                       + "the file, repoint this control at any two § Quality rules lines.")
                .contains(qualityRuleThree, tscGotcha);

        assertThat(staleMessage(stale, dates, population, pinned))
                .contains("CLAUDE.md:" + qualityRuleThree, "CLAUDE.md:" + tscGotcha);
    }

    // ------------------------------------------------------------------ R2: the bench

    @Test
    void theAgentDirectoryIsTheElevenAgentBench() {
        var problems = new ArrayList<String>();

        var expected = new TreeSet<>(BENCH.stream().map(name -> name + ".md").toList());
        var present = new TreeSet<String>();
        try (Stream<Path> entries = Files.list(AGENTS_DIR)) {
            entries.forEach(entry -> present.add(entry.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var unexpected = new TreeSet<>(present);
        unexpected.removeAll(expected);
        var missing = new TreeSet<>(expected);
        missing.removeAll(present);
        if (!unexpected.isEmpty() || !missing.isEmpty()) {
            problems.add("  unexpected: " + orNone(unexpected) + "        missing: " + orNone(missing));
        }

        Map<String, String> declaredModel = new LinkedHashMap<>();
        Map<String, String> declaredEffort = new LinkedHashMap<>();
        var gateMentions = new TreeSet<String>();
        for (String stem : BENCH) {
            var file = AGENTS_DIR.resolve(stem + ".md");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            var lines = physicalLines(file);
            var frontmatter = frontmatter(lines);
            if (frontmatter.isEmpty()) {
                problems.add("  .claude/agents/" + stem + ".md:1   no frontmatter block "
                             + "(a --- line, key: value lines, a closing --- line)");
                continue;
            }
            check(problems, stem, frontmatter, "name", Set.of(stem), declaredName -> {
            });
            check(problems, stem, frontmatter, "model", MODELS, value -> declaredModel.put(stem, value));
            check(problems, stem, frontmatter, "effort", EFFORTS, value -> declaredEffort.put(stem, value));

            String description = frontmatter.containsKey("description")
                    ? frontmatter.get("description").value() : "";
            var mentioned = GATE_MENTION.matcher(description);
            while (mentioned.find()) {
                gateMentions.add(mentioned.group(1));
                if (!GATES.contains(mentioned.group(1))) {
                    problems.add("  .claude/agents/" + stem + ".md:"
                                 + frontmatter.get("description").line() + "   names gate "
                                 + mentioned.group(1) + " — not a gate; GATES has " + sorted(GATES));
                }
            }
        }
        if (gateMentions.isEmpty()) {
            problems.add("  no agent description names a gate — this scan read nothing, which is not "
                         + "agreement. A gate an agent runs is named in its description as `gate <name>`.");
        }

        var claudeLines = physicalLines(ROOT.resolve(CLAUDE_MD));
        var bulletNames = new TreeSet<String>();
        for (Numbered line : section(claudeLines, SUBAGENTS_HEADING, "CLAUDE.md § Subagents")) {
            var bullet = SUBAGENT_BULLET.matcher(line.text());
            if (!bullet.find()) {
                continue;
            }
            String name = bullet.group(1);
            bulletNames.add(name);
            String pair = bullet.group(2) + "/" + bullet.group(3);
            String frontmatterPair = declaredModel.get(name) + "/" + declaredEffort.get(name);
            // Only when both halves were accepted: a rejected value is already reported above, and
            // repeating it as "the frontmatter says sonnet/null" points at the wrong file.
            if (declaredModel.containsKey(name) && declaredEffort.containsKey(name)
                && !pair.equals(frontmatterPair)) {
                problems.add("  CLAUDE.md:" + line.number() + "  says " + name + " (" + pair
                             + "); the frontmatter says " + frontmatterPair);
            }
        }
        addSetProblem(problems, "CLAUDE.md § Subagents bullets", bulletNames);

        var skillLines = physicalLines(SKILL_MD);
        var benchNames = new TreeSet<String>();
        for (Numbered line : section(skillLines, BENCH_HEADING, "SKILL.md § The bench")) {
            var backticked = BACKTICKED.matcher(line.text());
            while (backticked.find()) {
                if (AGENT_NAME_SHAPE.matcher(backticked.group(1)).matches()) {
                    benchNames.add(backticked.group(1));
                }
            }
        }
        addSetProblem(problems, "SKILL.md § The bench", benchNames);

        var routed = new TreeSet<>(agentMap().values());
        var offBench = new TreeSet<>(routed);
        offBench.removeAll(BENCH);
        if (!offBench.isEmpty()) {
            problems.add("  check-gates.mjs AGENT routes to " + offBench + " — not on the bench");
        }
        assertThat(routed)
                .withFailMessage("check-gates.mjs AGENT named no agent at all — the parser stopped matching, "
                                 + "which is not agreement. Check `const AGENT = { … };` in "
                                 + CHECK_GATES.toAbsolutePath())
                .isNotEmpty();

        if (!problems.isEmpty()) {
            fail(benchMessage(problems));
        }
        System.out.println("[agent-freshness] bench: " + present.size() + " files found, "
                           + bulletNames.size() + " CLAUDE.md bullets, " + benchNames.size()
                           + " SKILL.md names, " + routed.size() + " routed by the hook");
    }

    // ------------------------------------------------------------------ R3: the gate set

    @Test
    void everyStatementOfTheGateSetIsTheSameSet() {
        Map<String, Set<String>> sources = new LinkedHashMap<>();
        sources.put("check-gates.mjs AGENT keys", new TreeSet<>(agentMap().keySet()));
        sources.put("check-gates.mjs required.add", matches(REQUIRED_ADD, PublishedCredentials.read(CHECK_GATES)));

        String skill = PublishedCredentials.read(SKILL_MD);
        var tagged = new TreeSet<String>();
        var tags = GATE_TAG.matcher(skill);
        while (tags.find()) {
            for (String name : tags.group(1).split(",")) {
                if (!name.isBlank()) {
                    tagged.add(name.trim());
                }
            }
        }
        sources.put("SKILL.md phase headings", tagged);
        sources.put("SKILL.md run.json schema", schemaGateKeys(skill));

        // The floor on the population itself. Each source is put once above, so a dropped or
        // duplicated label makes this scan quietly smaller than the claim it states — four
        // independent statements of the gate set — while every surviving source still agrees.
        assertThat(sources)
                .withFailMessage("%s", "this scan compares FOUR independently parsed statements of the gate "
                                       + "set (check-gates.mjs AGENT keys, check-gates.mjs required.add, "
                                       + "SKILL.md phase headings, SKILL.md run.json schema) and found "
                                       + sources.size() + ": " + sources.keySet())
                .hasSize(4);

        var disagreeing = new ArrayList<String>();
        sources.forEach((label, names) -> {
            if (names.isEmpty()) {
                disagreeing.add("  " + pad(label) + ": nothing parsed — the regex stopped matching, "
                                + "which is not agreement");
            } else if (!names.equals(GATES)) {
                var extra = new TreeSet<>(names);
                extra.removeAll(GATES);
                var absent = new TreeSet<>(GATES);
                absent.removeAll(names);
                disagreeing.add("  " + pad(label) + ": " + describe(extra, absent));
            }
        });

        if (!disagreeing.isEmpty()) {
            fail(gateMessage(sources, disagreeing));
        }
        String perSource = sources.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().size())
                .collect(Collectors.joining(", "));
        System.out.println("[agent-freshness] gates: " + GATES.size() + " names agreed; "
                           + sources.size() + " sources parsed (" + perSource + ")");
    }

    // ------------------------------------------------------------------ R4: never vacuous

    /**
     * The disarm path has a reason, and it is produced by the same probe the condition uses. Always
     * runs: a skip whose reason is only asserted by a test that is itself skipped proves nothing.
     */
    @Test
    void theProbeSaysWhyWhenThereIsNoWorkTree() throws IOException {
        Path outside = Files.createTempDirectory("hamstrack-no-git");
        try {
            var verdict = RepositoryHistory.probe(outside);
            assertThat(verdict)
                    .withFailMessage("%s", "RepositoryHistory.probe must report a directory with no .git as "
                                           + "Unavailable, with the reason a skipped run prints. Got: " + verdict)
                    .isInstanceOf(RepositoryHistory.Unavailable.class);
            assertThat(((RepositoryHistory.Unavailable) verdict).reason())
                    .withFailMessage("the Unavailable reason must name the directory it probed, or a skipped "
                                     + "run says nothing a reader can act on")
                    .contains(outside.toAbsolutePath().toString());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /**
     * CI is a checkout by construction, so an unreadable history there is a lost gate rather than
     * an honest absence. Always runs, and is the one assertion the {@code @EnabledIf} cannot make
     * about itself.
     */
    @Test
    void ciCannotLoseTheHistoryAssertionToASkip() {
        var verdict = RepositoryHistory.probe(ROOT);
        boolean underCi = System.getenv("GITHUB_ACTIONS") != null;
        assertThat(!underCi || verdict instanceof RepositoryHistory.Available)
                .withFailMessage("%s", """
                        [agent-freshness] running under GITHUB_ACTIONS with no readable git history.
                        The freshness assertion would be skipped, and a skipped gate in CI is not a gate.
                        A workflow job that runs this suite checks the repository out with its history:
                          - uses: actions/checkout@v4
                            with:
                              fetch-depth: 0      # AgentChecklistFreshnessTest dates CLAUDE.md rules
                        Probe said:\s""" + verdict)
                .isTrue();
    }

    // ------------------------------------------------------------------ conditions and helpers

    /** The {@code @EnabledIf} condition — and the only place the disarm reason is printed. */
    static boolean historyIsMeasurable() {
        var verdict = RepositoryHistory.probe(ROOT);
        if (verdict instanceof RepositoryHistory.Unavailable unavailable) {
            System.out.println("[agent-freshness] disarmed: " + unavailable.reason());
            return false;
        }
        return true;
    }

    /**
     * The depth precondition, stated here because these two assertions are the ones it protects —
     * and <em>owned</em> by {@link RepositoryHistory#requireDeepHistory}, which the readers call
     * themselves. A precondition that lives only in its caller is one the next caller of the same
     * history does not get.
     */
    private static String refuseTruncatedHistory() {
        return RepositoryHistory.requireDeepHistory(ROOT);
    }

    /** The rule corpus: both sections, with the floors that stop an empty scan reading as a pass. */
    private static List<Numbered> ruleCorpus() {
        var lines = physicalLines(ROOT.resolve(CLAUDE_MD));
        var gotchas = section(lines, GOTCHAS_HEADING, "CLAUDE.md § Gotchas");
        var quality = section(lines, QUALITY_HEADING, "CLAUDE.md § Quality rules");
        floor(gotchas, 20, "CLAUDE.md § Gotchas");
        floor(quality, 3, "CLAUDE.md § Quality rules");
        var all = new ArrayList<>(gotchas);
        all.addAll(quality);
        return List.copyOf(all);
    }

    /** Blame date per population line, refusing a line history could not date. */
    private static Map<Integer, Instant> datesOf(List<Numbered> population) {
        var blame = RepositoryHistory.blameDates(ROOT, CLAUDE_MD);
        var undated = population.stream().map(Numbered::number).filter(n -> !blame.containsKey(n)).toList();
        assertThat(undated)
                .withFailMessage("%s", "git blame dated no commit for CLAUDE.md line(s) " + undated
                                       + ". A rule line with no date is a line this rule cannot ask about, "
                                       + "and skipping it would make the scan quietly smaller than the file. "
                                       + "Check that the blame output parsed (git blame -w -M --line-porcelain "
                                       + "-- CLAUDE.md) and that the file has no lone-CR line endings.")
                .isEmpty();
        var dates = new LinkedHashMap<Integer, Instant>();
        population.forEach(line -> dates.put(line.number(), blame.get(line.number())));
        return dates;
    }

    private static Instant newestAgentEdit() {
        Instant committed = RepositoryHistory.newestCommitTouching(ROOT, AGENTS_PATHSPEC)
                .orElseThrow(() -> new IllegalStateException(
                        "no commit in this history ever touched " + AGENTS_PATHSPEC
                        + " — the pathspec is wrong, or this is not the Hamstrack repository"));
        // Symmetry with blame, which dates an uncommitted line to now: an agent file edited in this
        // session but not yet committed is an edit that happened, and dating it to its last commit
        // would go red mid-session for the very behaviour the rule asks for.
        return RepositoryHistory.isDirty(ROOT, AGENTS_PATHSPEC) ? Instant.now() : committed;
    }

    private static String staleMessage(List<Integer> stale, Map<Integer, Instant> dates,
                                       List<Numbered> population, Instant agents) {
        var text = new LinkedHashMap<Integer, String>();
        population.forEach(line -> text.put(line.number(), line.text()));
        var out = new StringBuilder();
        out.append("CLAUDE.md gained ").append(stale.size()).append(" rule line(s) more than ")
                .append(AGENT_LEARNING_WINDOW.toDays())
                .append(" days after the last edit to any agent checklist.\n");
        out.append("  newest agent edit: ").append(DAY.format(agents))
                .append("    window closed: ").append(DAY.format(agents.plus(AGENT_LEARNING_WINDOW)))
                .append('\n');
        stale.stream().limit(MAX_LISTED).forEach(line -> out
                .append("  CLAUDE.md:").append(line)
                .append("  ").append(DAY.format(dates.get(line)))
                .append("  ").append(abbreviate(text.getOrDefault(line, ""))).append('\n'));
        if (stale.size() > MAX_LISTED) {
            out.append("  … and ").append(stale.size() - MAX_LISTED).append(" more\n");
        }
        out.append("""
                Which agent should have learned each of these? Put the rule where a builder or reviewer will
                act on it — .claude/agents/<agent>.md — and keep the CLAUDE.md line as a pointer. If no agent
                needs it, it is not a rule for this file: move it to docs/. An agent edit that changes nothing
                it checks resets this clock and teaches nobody; that is the one repair this test cannot see,
                so do not make it.""");
        return out.toString();
    }

    private static String benchMessage(List<String> problems) {
        var out = new StringBuilder(".claude/agents/ is not the eleven-agent bench (HD-293).\n");
        problems.stream().limit(MAX_LISTED).forEach(problem -> out.append(problem).append('\n'));
        if (problems.size() > MAX_LISTED) {
            out.append("  … and ").append(problems.size() - MAX_LISTED).append(" more\n");
        }
        out.append("""
                An agent is added, renamed or removed in BENCH (this test), CLAUDE.md § Subagents, SKILL.md
                § The bench and check-gates.mjs AGENT in one commit — and every backticked mention in
                SKILL.md's phase lines and in CLAUDE.md § Dev pipeline: grep the old name under `.claude/`
                and `CLAUDE.md`, because those mentions are prose no parser here reads. A model or effort
                change is dated in the bench-design note (HD-293 attachment) and mirrored in § Subagents.
                  models allowed:""").append(" ").append(sorted(MODELS))
                .append("\n  efforts allowed: ").append(sorted(EFFORTS));
        return out.toString();
    }

    private static String gateMessage(Map<String, Set<String>> sources, List<String> disagreeing) {
        var out = new StringBuilder("The gate set is stated in four places and they disagree "
                                    + "(dev-team-pipeline.md §12.2).\n");
        out.append("  ").append(pad("GATES")).append(": ").append(String.join(" ", sorted(GATES))).append('\n');
        sources.forEach((label, names) -> {
            String detail = disagreeing.stream()
                    .filter(problem -> problem.startsWith("  " + pad(label) + ":"))
                    .findFirst().orElse("  " + pad(label) + ": matches");
            out.append(detail).append('\n');
        });
        out.append("""
                Rename or add a gate in all four and in GATES in one commit. The hook derives what it
                requires from AGENT, so a name the skill uses and the hook does not is a gate that never
                blocks, and a name the hook requires and the skill never mentions is a gate nobody runs.""");
        return out.toString();
    }

    // ------------------------------------------------------------------ parsing

    /** One physical line and its 1-based number, so a failure can point at {@code file:line}. */
    record Numbered(int number, String text) {
    }

    /** One frontmatter entry and the line it sits on. */
    private record Entry(int line, String value) {
    }

    /**
     * Physical lines, split on {@code \n} exactly as git counts them, with a trailing {@code \r}
     * removed. {@code Files.readAllLines} would also break on a lone {@code \r} and silently
     * renumber the file against blame.
     */
    private static List<String> physicalLines(Path file) {
        var parts = PublishedCredentials.read(file).split("\n", -1);
        var out = new ArrayList<String>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            if (i == parts.length - 1 && parts[i].isEmpty()) {
                break;
            }
            out.add(parts[i].endsWith("\r") ? parts[i].substring(0, parts[i].length() - 1) : parts[i]);
        }
        return out;
    }

    /**
     * Every non-blank line strictly between {@code heading} and the next {@code ## }. The heading
     * must match exactly once: zero means the scan reads nothing and is green for free, two means
     * half the section is silently out of scope.
     */
    private static List<Numbered> section(List<String> lines, Pattern heading, String label) {
        var starts = new ArrayList<Integer>();
        for (int i = 0; i < lines.size(); i++) {
            if (heading.matcher(lines.get(i)).find()) {
                starts.add(i);
            }
        }
        assertThat(starts)
                .withFailMessage("%s", "the heading for " + label + " (/" + heading.pattern() + "/) matched "
                                       + starts.size() + " times, and this scan needs exactly one. A renamed or "
                                       + "split heading makes every rule under it invisible to the freshness "
                                       + "check — rename the heading back, or update the pattern in "
                                       + "AgentChecklistFreshnessTest in the same commit.")
                .hasSize(1);
        var out = new ArrayList<Numbered>();
        for (int i = starts.get(0) + 1; i < lines.size(); i++) {
            if (HEADING.matcher(lines.get(i)).find()) {
                break;
            }
            if (!lines.get(i).isBlank()) {
                out.add(new Numbered(i + 1, lines.get(i)));
            }
        }
        return out;
    }

    private static void floor(List<Numbered> section, int floor, String label) {
        assertThat(section)
                .withFailMessage("%s", label + " holds " + section.size() + " non-blank line(s); this scan "
                                       + "expects at least " + floor + ". Either the section really shrank — "
                                       + "in which case lower the floor deliberately in "
                                       + "AgentChecklistFreshnessTest — or the section boundaries moved and "
                                       + "the scan is now reading a fraction of the rules while passing.")
                .hasSizeGreaterThanOrEqualTo(floor);
    }

    private static Map<String, Entry> frontmatter(List<String> lines) {
        if (lines.isEmpty() || !"---".equals(lines.get(0).trim())) {
            return Map.of();
        }
        var out = new LinkedHashMap<String, Entry>();
        for (int i = 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).trim())) {
                return out;
            }
            var entry = FRONTMATTER_ENTRY.matcher(lines.get(i));
            if (entry.matches()) {
                out.put(entry.group(1), new Entry(i + 1, entry.group(2).trim()));
            }
        }
        return Map.of(); // unterminated block — not a frontmatter at all
    }

    private static void check(List<String> problems, String stem, Map<String, Entry> frontmatter,
                              String key, Set<String> allowed, java.util.function.Consumer<String> keep) {
        var entry = frontmatter.get(key);
        if (entry == null) {
            problems.add("  .claude/agents/" + stem + ".md   no `" + key + ":` in the frontmatter — allowed: "
                         + String.join(", ", sorted(allowed)));
            return;
        }
        String value = entry.value().replaceAll("^[\"']|[\"']$", "");
        if (!allowed.contains(value)) {
            problems.add("  .claude/agents/" + stem + ".md:" + entry.line() + "   " + key + ": " + value
                         + " — allowed: " + String.join(", ", sorted(allowed)));
            return;
        }
        keep.accept(value);
    }

    private static void addSetProblem(List<String> problems, String label, Set<String> found) {
        if (found.equals(new TreeSet<>(BENCH))) {
            return;
        }
        var extra = new TreeSet<>(found);
        extra.removeAll(BENCH);
        var absent = new TreeSet<>(BENCH);
        absent.removeAll(found);
        problems.add("  " + label + ": " + describe(extra, absent));
    }

    /** {@code gate -> agent} as the hook routes it. */
    private static Map<String, String> agentMap() {
        String source = PublishedCredentials.read(CHECK_GATES);
        var literal = AGENT_MAP.matcher(source);
        assertThat(literal.find())
                .withFailMessage("could not find `const AGENT = { … };` in " + CHECK_GATES.toAbsolutePath()
                                 + " — the hook's gate-to-agent routing is what both the bench and the gate "
                                 + "set are checked against, so a parser that stopped matching must refuse.")
                .isTrue();
        var out = new LinkedHashMap<String, String>();
        var entries = AGENT_ENTRY.matcher(literal.group(1));
        while (entries.find()) {
            out.put(entries.group(1), entries.group(2));
        }
        return out;
    }

    /**
     * The keys of the {@code "gates": { … }} object in SKILL.md's fenced {@code run.json} schema,
     * read at nesting depth 1 only — {@code "tests"} carries a nested object whose own keys are not
     * gates.
     */
    private static Set<String> schemaGateKeys(String skill) {
        int marker = skill.indexOf("\"gates\": {");
        assertThat(marker)
                .withFailMessage("SKILL.md no longer carries a `\"gates\": {` block in its run.json schema — "
                                 + "that block is one of the four statements of the gate set, and a source "
                                 + "that cannot be found is not a source that agrees.")
                .isNotNegative();
        assertThat(skill.indexOf("\"gates\": {", marker + 1))
                .withFailMessage("SKILL.md carries more than one `\"gates\": {` block; this scan reads the "
                                 + "first and would silently ignore the rest.")
                .isNegative();

        var out = new TreeSet<String>();
        int depth = 1;
        for (int i = skill.indexOf('{', marker) + 1; i < skill.length() && depth > 0; i++) {
            char c = skill.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == '"') {
                int end = skill.indexOf('"', i + 1);
                if (end < 0) {
                    break;
                }
                String token = skill.substring(i + 1, end);
                int after = end + 1;
                while (after < skill.length() && Character.isWhitespace(skill.charAt(after))) {
                    after++;
                }
                if (depth == 1 && after < skill.length() && skill.charAt(after) == ':') {
                    out.add(token);
                }
                i = end;
            }
        }
        return out;
    }

    private static Set<String> matches(Pattern pattern, String source) {
        var out = new TreeSet<String>();
        var found = pattern.matcher(source);
        while (found.find()) {
            out.add(found.group(1));
        }
        return out;
    }

    private static int lineContaining(List<Numbered> population, String... markers) {
        for (Numbered line : population) {
            for (String marker : markers) {
                if (line.text().contains(marker)) {
                    return line.number();
                }
            }
        }
        return fail("no line of the CLAUDE.md rule corpus contains any of " + List.of(markers)
                    + " — this control pins two lines it knows are newer than 2026-09-03; if they were "
                    + "rewritten out of the file, repoint it at any two recently written rule lines.");
    }

    private static String describe(Set<String> extra, Set<String> absent) {
        var parts = new ArrayList<String>();
        if (!extra.isEmpty()) {
            parts.add("unexpected " + extra);
        }
        if (!absent.isEmpty()) {
            parts.add("missing " + absent);
        }
        return String.join("; ", parts);
    }

    private static List<String> sorted(Set<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }

    private static String orNone(Set<String> values) {
        return values.isEmpty() ? "(none)" : String.join(", ", values);
    }

    private static String pad(String label) {
        return label.length() >= 30 ? label : label + " ".repeat(30 - label.length());
    }

    private static String abbreviate(String text) {
        return text.length() <= 70 ? text : text.substring(0, 70) + "…";
    }
}
