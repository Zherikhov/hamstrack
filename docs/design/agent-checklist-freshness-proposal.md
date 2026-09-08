# Agent checklist freshness — HD-304 (epic HD-294, fix version 0.18.2)

> **Status: proposal.** Spec date **2026-09-08**. Every claim about today's tree is labelled **measured**
> (command run / output quoted), **read** (file:line) or **inferred** (hypothesis the builder checks before code).
> 1 story point: one test class, one non-test-named helper, one shared `git` runner, one CI line, four doc lines.
> A build rule — no tenancy, no API, no schema, no profile.
>
> **Seat note.** This spec was written from a seat with no shell: nothing below was produced by running `git`. Dates
> come from `.git/logs/HEAD` (the reflog, **read**), file shapes from the files (**read**), populations from grep
> (**measured**). Every premise that needs `git blame`/`git log` is labelled **inferred** and carries the command;
> per the autopilot policy the builder's first report is that measurement.
>
> **As built (2026-09-08, HD-304).** Three deltas from the proposal, recorded here rather than left for a reader to
> discover by grep:
> 1. **D5 was not built.** There is no `HAMSTRACK_FRESHNESS_DRILL_AGENTS_EDITED_AT` (**measured**: repo grep for the
>    name outside this file returns nothing). The negative control it existed to enable is a *test* instead —
>    `theSameComparisonNamesTodaysRulesWhenTheBenchIsPinnedToAnOlderDate` runs the same `staleLines` over the real
>    blame map with the bench pinned to 2026-08-20, so the parsing half is witnessed on every run, on every day, by
>    something the build cannot forget to set. A knob is an input somebody has to supply; a test is not. Void with it:
>    **E14**, **§9**'s "the one env var", and **§11 Q5** (whose "Yes" answered a question that no longer exists).
> 2. **stdout and stderr are read apart** (`PublishedCredentials.GitResult.error()`). Merged, `GIT_TRACE=1` made
>    `git rev-parse --is-inside-work-tree` answer with a trace line, so the run disarmed itself and reported
>    `Skipped: 2` on a full checkout (**measured** before the fix).
> 3. **The depth check is tri-state and lives with the readers** (`RepositoryHistory.requireDeepHistory`, called by
>    `blameDates` and `newestCommitTouching`): exactly `false` proceeds, exactly `true` refuses as shallow, anything
>    else refuses as unreadable. The boolean it replaces read "git said something I did not expect" as "deep enough".
>
> Backlog search: repo grep `AgentChecklistFreshness|freshness|which agent checklist` outside `docs/retro`
> (2026-09-08) — no mechanism exists; the retro names this test as action **X4** (`2026-09-bug-priorities.md:16`,
> `2026-09-process-retro.md:58`). The tracker is not reachable from this seat; the orchestrator confirms against
> HD-294's children. Related: **HD-293** (the bench and hook this pins), **HD-295** (sibling contract test, the
> locator to mirror), **HD-303** (the 14-day checkpoint this shares a cadence with), **HD-265** (the suite guard
> that counts this class).

## 1. Problem & goal

Root 3 of the retrospective: the agent files froze on 2026-08-06/13 while ~20 rules landed in CLAUDE.md, so the
reviewers hunted last month's defect classes. HD-293 moved the rules into the agent files and made Phase 7 ask
*"which agent checklist grows from this ticket"* (**read** `SKILL.md:96`), but the honest answer to that question
is often "none", and nothing fails when it is "none" for two months. Goal: the build goes red when CLAUDE.md's rule
corpus outruns every agent checklist by more than 14 days, when the bench is not the eleven the router knows, or when
the hook and the skill disagree about the gate set — and it never goes green by reading nothing.

## 2. Scope / non-goals

**In:** `AgentChecklistFreshnessTest` + helper `RepositoryHistory` (package `common.docs`); a `git(String...)`
runner extracted from `PublishedCredentials.trackedFiles()` and reused by it; `fetch-depth: 0` on the
`build-and-test` checkout in `.github/workflows/build.yml`; one line each in `docs/project-state.md`, `SKILL.md`
Phase 7, CLAUDE.md Quality rule 3, and `.claude/agents/test-runner.md`.
**Out:** judging whether an agent edit *taught* anything (the test measures activity, not learning — §5 E1);
pinning the model/effort *pairing* per agent (an A/B is planned by design); reading `docs/retro/**` (gitignored,
`.gitignore:65` `retro/` — absent from a fresh clone); the prose copies of the bench and gate set in
`dev-team-pipeline.md` §12.1–12.2 and CLAUDE.md:133–134 (listed in §6 so the omission is deliberate); a check that
the freshness rule's CLAUDE.md pointer exists (that is the pointer itself).

## 3. Actors & permissions — n/a

No request, workspace or `Permission`. Actor: whoever runs `mvnw test` (developer, CI). Reader of the red line:
the orchestrator at Phase 7 and the owner.

## 4. Behaviour & rules

### 4.1 Premises, measured today

| # | Premise | Status | Evidence |
|---|---|---|---|
| P1 | Repo root and git are located one way in tests | **read** | `PublishedCredentials.REPO_ROOT = Path.of(".")` (`ops/PublishedCredentials.java:67`); `trackedFiles()` runs `git ls-files -z` via `ProcessBuilder` in `REPO_ROOT`, throws `IllegalStateException` on non-zero exit or empty listing (`:442-471`); `VacuousVerificationRulesTest` delegates root, reading and paths to it (`common/testsupport/VacuousVerificationRulesTest.java:332-350`). `PublishedClaimsTest`/`UpgradeNotesCoverageTest` use `Path.of("…")` relative to the module dir (`:213-219`, `:198-202`). No test reads `.claude/agents` today (`PublishedClaimsTest.java:200` names `.claude/` only inside a message). |
| P2 | Gotchas are one physical line per bullet | **measured** | `^## ` at CLAUDE.md:44 (`## Gotchas — don't re-debug these`) and :74; every line 46–72 starts with `- ` (27 lines; file-wide `^- ` = 56, of which 2 in DC/Cloud :10-11, 4 in Architecture :29-32, 11 in Subagents :114-124, 6 in Dev pipeline :132-137, 6 in Reference docs :151-157); no continuation lines. |
| P3 | Quality rules shape | **read** | `## Quality rules …` at :139; non-blank lines :141 (preamble), :143-145 (rules 1–3, one line each), :147 (tracker rules); next `## ` at :149. Five lines. |
| P4 | Line-68 anomaly | **measured, unresolved** | `^- \*\*BCrypt` → 0 hits; `^- \*\*.BCrypt` → 0; `^.{0,8}BCrypt refuses` (`-o`) → `68:- **BCrypt refuses`; no code point in `[\x200B-\x200D\xFEFF\x2060\xA0\xAD]` anywhere in the file. Builder resolves with a byte dump of :68's first 8 bytes. **The population rule in §4.3 is section-anchored, not bullet-shaped, precisely so it does not depend on this.** |
| P5 | Newest agent edit | **inferred** | Reflog `.git/logs/HEAD:235` `3794299` HD-293 "eleven-agent bench" at epoch 1788807788 = **2026-09-07 19:03:08 UTC**; `:236` `5c2adfe` HD-295 at 1788844915 = **2026-09-08 05:21:55 UTC** — `test-runner.md:28` cites HD-295 and `VacuousVerificationRulesTest` (**read**), and HD-295's spec §2 lists `frontend-builder.md:36`, `test-runner.md:21,28` as its edits (**read** `vacuous-verification-rules-proposal.md:24-25`); `:237` `e68ff20` HD-303 (09:30:37 UTC) lists no agent file in scope (**read** `pipeline-history-proposal.md:30-31`). Command: `git log -1 --format='%ct %cI %h %s' -- .claude/agents/`. |
| P6 | Newest rule line | **inferred** | CLAUDE.md:51 (the `tsc --noEmit` gotcha) cites HD-295 and is in that spec's edit list → blamed to `5c2adfe`; :72 (HD-265) → `9d69446` (`:220`, 2026-09-04 23:38 UTC); :141-147 → `3794299`. Command: `git blame -w -M --line-porcelain -- CLAUDE.md \| awk '/^committer-time/{t=$2} /^\t/{n++; if(n>=46&&n<=72\|\|n>=141&&n<=147) print t, n}' \| sort -n \| tail -3`. |
| P7 | **Today's margin** | **inferred from P5/P6** | rule − agent = **0 s** if both are in `5c2adfe`; at worst **+10 h 19 m** (agents last in `3794299`, :51 in `5c2adfe`). Either way the first run is **green**, and the first red *without* an agent edit is a rule line dated after **2026-09-22 05:21 UTC** (worst case 2026-09-21 19:03 UTC). Consequence for the ticket: its negative control "a fresh Gotcha bullet with no agent edit" **cannot be red before 2026-09-22** as written — see D5. |
| P8 | Local clone is full | **measured** | Glob `.git/shallow` → no file. |
| P9 | CI clone is shallow | **read + inferred** | `build.yml:77-78` `actions/checkout@v4` with no `with:` on `build-and-test`; `:175-178` `fetch-depth: 0` only on `build-and-push`. `actions/checkout` defaults to depth 1 (documented default; **inferred** here). On a depth-1 clone `git blame` attributes every line to the boundary commit and `git log -1 -- .claude/agents/` returns the same commit, so both sides carry HEAD's date and the comparison passes for free — the exact vacuous green this ticket forbids. |
| P10 | CRLF policy does not move blame | **read + inferred** | `.gitattributes` has no `text=auto` and no `*.md` rule (`:1-35`); `.git/config` has no `core.autocrlf` (`:1-20`) — the policy is whatever the global config says. Blame runs the clean filter over the work-tree file before matching history, so autocrlf changes no attribution (**inferred**). One-line confirmation: `git blame --line-porcelain -L 46,46 -- CLAUDE.md \| head -1` prints a real 40-hex sha on a clean tree, never `0000…`. |
| P11 | Frontmatter today | **measured** | All 11 files: `name:` (= file stem), `description:`, `tools:`, `model:`, `effort:` at lines 2–6. Values: api-docs-sync sonnet/high · backend-builder fable/high · browser-qa sonnet/medium · dc-cloud-guard opus/high · frontend-builder opus/xhigh · migration-reviewer opus/high · ops-reviewer fable/xhigh · security-officer fable/xhigh · systems-analyst fable/xhigh · tenancy-reviewer fable/xhigh · test-runner opus/xhigh. Equal to CLAUDE.md:114-124 and `dev-team-pipeline.md:305`. |
| P12 | Allowed lists, from the design | **read** (local `docs/retro/2026-09-agent-models.md:9,52,62` — canonical copy is the HD-293 attachment) | Runtime accepts `model:` = `fable\|opus\|sonnet\|haiku\|inherit\|<full id>` and `effort:` = `low\|medium\|high\|xhigh\|max`. The design rules out `inherit` ("uniform inherit is wrong in both directions"; "never omit effort:"), `max` ("not recommended anywhere by default"), and puts nobody on `haiku`. **MODELS = {fable, opus, sonnet}; EFFORTS = {low, medium, high, xhigh}.** |
| P13 | Gate set sources | **read** | `check-gates.mjs:155-156` `const AGENT = { tenancy, security, tests, migration, dc_cloud, api_docs, spec, ops_witness, ui_qa }` (9 keys → 9 agent names); `:145-153` nine `required.add("…")` literals, same names. `SKILL.md:47,57,70,75` headings `[gate: spec]`, `[gates: migration, dc_cloud, api_docs, ops_witness, ui_qa]`, `[gates: tenancy, security]`, `[gate: tests]`; `SKILL.md:114-118` schema block `"gates": { spec, tenancy, security, migration, dc_cloud, api_docs, ops_witness, ui_qa, tests }`. All four equal today. `check-gates.test.mjs` counts `allow()`/`block(` sites only (`:178-179`) — it does not pin the set. |
| P14 | Bench copies | **read** | `SKILL.md:13-18` "## The bench (eleven agents, nothing else)" names all eleven in backticks; CLAUDE.md:114-124 eleven bullets `- **`name`** (model/effort)`; `AGENT` values name nine (builders are dispatched by phase, not by gate — legitimately absent). |
| P15 | How the suite guard counts a class | **read** | `SuiteCoverageGuard.TEST_NAME_PATTERNS` = `Test.*`, `.*Test`, `.*Tests`, `.*TestCase` over `src/test/java/**/*.java`, minus abstract/interface (`:81-85`, `:212-255`). A conditionally skipped class counts as present (`ExecutedTestClassRecorder.java:42-45`). Helpers must not wear those names. |
| P16 | Legal disarm spelling | **read** | HD-295's skip rule excludes `@DisabledIf…`/`@EnabledIf…` by the trailing `\b` (`VacuousVerification.java:29-31,72-73`); `Assumptions.abort` is also outside it but "none used today" (`:36-45`). |

### 4.2 Decisions

- **D1 — one universe on both sides: this checkout.** Rule lines are blamed in the work tree (`git blame -w -M
  --line-porcelain -- CLAUDE.md`; an uncommitted line is reported with sha `0000…`, `Not Committed Yet`, and a
  `committer-time` of *now* — **inferred**, builder probes). The agent side is
  `A = max(git log -1 --format=%ct -- .claude/agents/, now if git status --porcelain -- .claude/agents/ is non-empty)`.
  The owner commits at the end of a session; during it both edits are uncommitted, and an asymmetric universe would
  go red mid-session for the very behaviour the test asks for. In CI the tree is clean and both collapse to history.
- **D2 — committer time on both sides** (`committer-time` / `%ct`): a rebase or amend rewrites both alike; author
  time would let a cherry-picked old rule look fresh or stale at random. Compared as epoch seconds; printed as
  ISO dates in UTC.
- **D3 — `AGENT_LEARNING_WINDOW = Duration.ofDays(14)`**, one constant, javadoc beside it: the same 14-day cadence as
  the HD-303 checkpoint (`dev-team-pipeline.md` §12.6, first run 2026-09-21), so a stale bench and the numbers that
  would show it are read on one rhythm; shorter goes red inside a normal two-week branch whose agent edit is queued
  for Phase 7; a month is the failure this replaces (agents froze 2026-08-06/13, ~20 rules, unnoticed).
- **D4 — `-w -M`.** Whitespace-only edits and lines moved within CLAUDE.md keep their original date; a reworded
  rule is re-dated on purpose — a rule someone rewrote is a rule someone should have placed (§5 E2).
- **D5 — a tighten-only drill knob.** Env var `HAMSTRACK_FRESHNESS_DRILL_AGENTS_EDITED_AT=YYYY-MM-DD` sets
  `A = min(A_measured, value)`; a value newer than measured is ignored and printed. It exists because of P7: with the
  bench edited in the same commit as the newest rule line, the ticket's first negative control is unreachable for 14
  days, and a knob that can only make the test stricter cannot be used to pass it. An env var, not a system property:
  the surefire fork inherits the environment with no `pom.xml` change (**inferred**, standard fork behaviour), and it
  matches the hook's `HAMSTRACK_*` override convention (`check-gates.mjs:32-36`).
- **D6 — where it disarms and where it refuses.** *No work tree* (tarball, no `git` binary): the history assertion
  is `@EnabledIf(…"historyIsMeasurable", disabledReason = …)` — skipped, reason printed, `Skipped: 1` in the summary;
  the static assertions (bench, gates) still run, because a tarball still has the files. *Shallow clone*
  (`git rev-parse --is-shallow-repository` → `true`): **fails** — history is present but lies (P9). *`GITHUB_ACTIONS`
  set and history not measurable*: **fails** — CI is a checkout by construction, and a skip there is a lost gate.
  The existing convention for git-dependent tests is to throw (`trackedFiles()`, P1), so a tarball suite is already
  red elsewhere; skipping here follows the ticket and is Open question 3.
- **D7 — location and names.** `src/test/java/com/hamstrack/common/docs/AgentChecklistFreshnessTest.java`, beside
  the other repository-document contracts (`PublishedClaimsTest`, `UpgradeNotesCoverageTest`); not
  `common/framework/`, which CLAUDE.md:144 and `SKILL.md:97` reserve for framework-trap observation tests. Root,
  reading and repository paths via `PublishedCredentials` (P1) — no second locator. The `ProcessBuilder("git", …)`
  in `trackedFiles()` is extracted to `PublishedCredentials.git(String... args)` (same failure shape: non-zero exit →
  `IllegalStateException` quoting the output) and reused; `RepositoryHistory` (not test-shaped, P15) wraps blame/log/
  status parsing and the pure comparison `staleLines(Map<Integer, Instant>, Instant newestAgentEdit, Duration)`.
- **D8 — `fetch-depth: 0` on the `build-and-test` checkout** (`build.yml:78`), with a comment naming this test. Not a
  bounded depth: a boundary commit can only make an old line look *newer*, which is the false-red direction, and 0
  removes the question. Arms `ops_witness` (§13).

### 4.3 Rules, as a category

**R1 — every rule line CLAUDE.md asks reviewers to hunt has an agent that learned it within 14 days.**
Population: every non-blank physical line strictly between the heading matching `^## Gotchas\b` and the next
`^## `, and likewise for `^## Quality rules\b`. Each anchor must match **exactly once** or the test fails
("section not found — a scan that reads nothing is green for free"); floors: Gotchas ≥ 20 lines (27 today),
Quality ≥ 3 (5 today). A wrapped bullet is several members and its newest continuation dates it; today none wraps
(P2). Red iff `t(line) − A > 14 d`; the message lists each offending line as `CLAUDE.md:<n>  <date>  <first 70 chars>`.

**R2 — every agent that exists is one the router knows, and each states what runs it.**
`BENCH` = the eleven stems. `Files.list(.claude/agents)` must equal `BENCH + ".md"` exactly (any other entry,
including a non-`.md` file, is "unexpected"). Per file: a frontmatter block between the first two `---` lines;
`name:` = stem; `model:` ∈ MODELS; `effort:` ∈ EFFORTS (P12). Copies: CLAUDE.md `## Subagents` bullets
(`^- \*\*`([a-z-]+)`\*\* \((\w+)/(\w+)\)`) — names = BENCH and `(model/effort)` = frontmatter; `SKILL.md`
`## The bench` section — hyphenated backticked identifiers (`^[a-z]+(-[a-z]+)+$`) = BENCH; `check-gates.mjs`
`AGENT` values ⊆ BENCH; every `gate <name>` an agent `description:` mentions ∈ GATES.

**R3 — every gate the hook enforces is one the skill names.**
`GATES` = {spec, tenancy, security, tests, migration, dc_cloud, api_docs, ops_witness, ui_qa}. Four parsed
sources must each equal GATES: (a) keys of the `const AGENT = { … };` literal in `check-gates.mjs`; (b) the string
literals in `required.add("…")`; (c) the union of names in `\[gates?:\s*([a-z_,\s]+)\]` over `SKILL.md`;
(d) the keys of the `"gates": {` … `}` block in `SKILL.md`'s fenced schema. Each source must yield ≥ 1 name (a
regex that stopped matching is not "agreement").

**R4 — never vacuous.** D6; every population has a floor; the happy path prints one line (§13).

## 5. Edge cases & failure modes

| # | Case | Behaviour |
|---|---|---|
| E1 | An agent file is touched without changing what it checks | Resets the clock; the message says so and says not to. Out of scope to detect (§2). |
| E2 | A CLAUDE.md rule line is reworded / rewrapped | Re-dated (D4). Within 14 d of an agent edit: nothing. Beyond: red, same remedy — the rewrite is the moment to ask which agent owns it. Whitespace-only and in-file moves keep their date. |
| E3 | A rule is deleted | Population shrinks; floors catch a mass deletion; nothing else — deletion is not the failure watched. |
| E4 | A section is moved, or its heading split in two | Anchors are heading text, not line numbers; two matches → "exactly once" fails. |
| E5 | Agent renamed / deleted / a twelfth added | Red naming `unexpected:` and `missing:`; the message lists the four places a rename edits in one commit (BENCH, CLAUDE.md § Subagents, SKILL.md § The bench, `AGENT`). |
| E6 | Frontmatter absent, unterminated, `model:`/`effort:` missing or outside the lists | Red naming `file:line` and the allowed list. |
| E7 | Uncommitted edits on either side | D1: rule line → now; dirty `.claude/agents` → `A = now`. Symmetric. |
| E8 | Shallow clone (CI today, P9) | Fails with the `fetch-depth: 0` / `git fetch --unshallow` line — never skips (D6). |
| E9 | Tarball / no `git` on PATH | History assertion skipped with a printed reason; bench and gate assertions run; under `GITHUB_ACTIONS` → fails. |
| E10 | Committer clock skew (a rule line dated in the future) | Red with both dates printed — visible, not silent; rare, accepted. |
| E11 | Branch squashed or rebased | Both sides re-dated together; a squash puts rule and agent edits in one commit → margin 0. |
| E12 | `git worktree` checkout | `--is-inside-work-tree` is true, blame works; nothing special. |
| E13 | Windows | Same `git` precondition `trackedFiles()` already has; `ProcessBuilder("git")` resolves `git.exe`; porcelain output read as UTF-8, timestamps are epoch. |
| E14 | Drill knob newer than measured, or malformed | Ignored / refused with a printed line; can never loosen (D5). |

## 6. Category — members (the builder's `category` block starts here)

Rule: *every statement of the bench, the gate set and the rule corpus that a session reads is held equal to its
source by one test.*

| Member | Source of truth | Sealed by |
|---|---|---|
| CLAUDE.md § Gotchas lines (27) | `git blame` | R1 |
| CLAUDE.md § Quality rules lines (5) | `git blame` | R1 |
| `.claude/agents/*.md` (11) — existence, frontmatter | directory + BENCH/MODELS/EFFORTS | R2 |
| CLAUDE.md § Subagents bullets (11, with `(model/effort)`) | frontmatter | R2 |
| `SKILL.md` § The bench | BENCH | R2 |
| `check-gates.mjs` `AGENT` values (9) | BENCH | R2 |
| Agent `description:` gate mentions (`browser-qa.md:3`, `ops-reviewer.md:3`) | GATES | R2 |
| `check-gates.mjs` `AGENT` keys · `required.add` literals | GATES | R3 |
| `SKILL.md` phase-heading tags · run.json schema block | GATES | R3 |
| `build.yml` `build-and-test` checkout depth | D8 | R4 (E8) + §13 |

Listed, **not sealed** (prose; a deliberate omission): `dev-team-pipeline.md:305` (pairing) and `:308-317`
(gate table); CLAUDE.md:133-134 (gate names in prose); `docs/retro/**` (gitignored).

## 7–9. Data model / API / Frontend / DC–Cloud — none

No migration, entity, endpoint, DTO, component or profile. The one env var (D5) is a build-time drill knob, not a
runtime toggle: no profile default, no wiring list.

## 10. Acceptance criteria (over the category; test shape named; negative control per assertion)

1. **Every rule line dated more than 14 days after the newest agent edit is named.** Shape: the live method over
   the real blame output, plus a positive control feeding `staleLines(...)` synthetic instants (one stale, one at
   exactly 14 d, one fresh) and asserting the one. **Negative control:** an uncommitted bullet appended to § Gotchas
   with `HAMSTRACK_FRESHNESS_DRILL_AGENTS_EDITED_AT=2026-08-20` → red, the message names `CLAUDE.md:<n>`; bullet
   removed → green; knob removed → green (the red line pasted on the ticket).
2. **Every file in `.claude/agents/` is one of the eleven, every one of the eleven exists, every frontmatter carries
   a model and an effort from the lists, every copy agrees.** **Negative controls:** (a) `zzz-twelfth.md` with valid
   frontmatter → red `unexpected:`; (b) `effort: max` in one file → red `file:6`; (c) CLAUDE.md:115 changed to
   `(opus/xhigh)` → red naming the bullet; each reverted → green.
3. **Every source of the gate set equals GATES.** **Negative control:** `ui_qa` → `ui-qa` in `SKILL.md:57` only →
   red naming the heading source and the three that still match; reverted → green.
4. **Outside a work tree the history assertion is skipped with a printed reason and the static ones still run; in a
   shallow clone it fails.** Shape: `RepositoryHistory.probe(tempDirWithoutGit)` → `Unavailable(reason)` (a unit
   test that always runs — the witness that the disarm path has a reason), `probe(REPO_ROOT)` → `Available`; drill:
   `git clone --depth 1 file://<repo> <scratch>` then `mvnw test -Dtest=AgentChecklistFreshnessTest -Dfrontend.skip=true`
   in the scratch (valid for one class — no Spring context, no DB) → red with the fetch-depth line.
5. **Counted by the suite guard.** `[test-tree] all N test classes … executed` shows N+1 after the change (both
   numbers pasted); no helper wears a `Test*`/`*Test`/`*Tests`/`*TestCase` name.
6. **Windows and prose.** `AGENT_LEARNING_WINDOW` is one constant with the D3 reasoning in its javadoc; every failure
   message ≤ 25 lines and names the action; the happy path prints the §13 line.
7. **Docs.** `project-state.md` gains a bullet after `:578`; `SKILL.md:96` gains "(`AgentChecklistFreshnessTest`
   turns a two-month 'none' into a red build)"; CLAUDE.md:145 gains the same pointer; `test-runner.md` gains the
   lesson in §13's last row — which also keeps today's margin at 0.
8. **CI, observed with a date.** The first `build-and-test` run after merge prints the armed line, which carries the
   depth read-back as its last token — `[agent-freshness] armed: … rule lines scanned, shallow=false` — so arming and
   depth are one thing to grep rather than two that can drift; the checkout step's duration before/after
   `fetch-depth: 0` is read from the two run logs and pasted (ops-reviewer's read-back).

## 11. Open questions (recommended default)

1. Include the `## Subagents` / `## Dev pipeline` sections in R1? **No** — they describe the bench, they are not rules
   a reviewer hunts; the populations are the two the ticket names.
2. Pin the model/effort pairing? **No** — the backend-builder A/B is designed (fable/high → opus/xhigh, dated in the
   README); lists plus CLAUDE.md-copy parity catch the drift that matters.
3. Tarball: skip or fail? **Skip**, per the ticket, with the CI exception (D6) — the suite is already red elsewhere in a
   tarball (P1), so consistency argues for failing; the orchestrator may flip this in one line.
4. `fetch-depth: 0` or a bounded depth? **0** (D8).
5. Keep the drill knob once the calendar makes a planted bullet red on its own? **Yes** — the negative control must
   be repeatable on any day, and a tighten-only knob has no failure mode.

## 12. ADR — none

Reversible; no fork.

## 13. Observability contract

| Failure mode | Witness | Drill |
|---|---|---|
| Rules outrun the bench | Red build naming the lines and asking *which agent should have learned this?* | AC-1 |
| Bench drifts (12th file, missing field, stale copy) | Red build naming file:line and the four places a rename edits | AC-2 |
| Gate renamed in one place | Red build naming the disagreeing source | AC-3 |
| The scan reads nothing (heading renamed, regex rotted) | Anchor "exactly once" + floors → red, never an empty pass | rename `## Gotchas` locally → red |
| History unavailable | `Skipped: 2` in the surefire summary (both `@EnabledIf` assertions) + `[agent-freshness] disarmed: <reason>`; under `GITHUB_ACTIONS` a failure | AC-4 |
| History truncated (shallow) | Failure with the `fetch-depth` line; CI carries `fetch-depth: 0` (D8) | AC-4, AC-8 |
| The test itself decays silently | Three happy-path lines per run in surefire stdout and the CI log, as printed on 2026-09-08 (**measured**): `[agent-freshness] armed: newest rule 2026-09-08 (CLAUDE.md:145), newest agent edit 2026-09-08, margin 0 d of 14; 32 rule lines scanned, shallow=false` · `[agent-freshness] bench: 11 files found, 11 CLAUDE.md bullets, 11 SKILL.md names, 9 routed by the hook` · `[agent-freshness] gates: 9 names agreed; 4 sources parsed (check-gates.mjs AGENT keys=9, check-gates.mjs required.add=9, SKILL.md phase headings=9, SKILL.md run.json schema=9)` — the counts a reader glances at on the HD-303 checkpoint day | read them on 2026-09-21 |
| The parsing half rots while the arithmetic still passes | `theSameComparisonNamesTodaysRulesWhenTheBenchIsPinnedToAnOlderDate` — the same `staleLines` over the real blame map, bench pinned to 2026-08-20 — goes red if blame stopped parsing, a section moved, or the line numbers stopped being real | every run (it replaces D5) |
| The test is deleted or renamed | Nothing mechanical (the HD-265 bound is derived from the tree). Named here and in `project-state.md`; accepted. | — |
| **Lesson for an agent file** | `test-runner.md`: *a repository-contract test disarms only where its input cannot exist, refuses where history is present but truncated or expected (CI), and prints its populations on the happy path — a skip in CI is a lost gate.* | Phase 7 of this ticket |

### Failure message drafts (≤ 25 lines each)

R1:
```
CLAUDE.md gained 2 rule line(s) more than 14 days after the last edit to any agent checklist.
  newest agent edit: 2026-09-08 (.claude/agents/test-runner.md)    window closed: 2026-09-22
  CLAUDE.md:73   2026-10-05   - **A counter written by a trigger is never refreshed by …
  CLAUDE.md:146  2026-10-03   4. **A throttle is earned by the work a handler does, not …
Which agent should have learned each of these? Put the rule where a builder or reviewer will act
on it — .claude/agents/<agent>.md — and keep the CLAUDE.md line as a pointer. If no agent needs it,
it is not a rule for this file: move it to docs/. An agent edit that changes nothing it checks resets
this clock and teaches nobody; that is the one repair this test cannot see, so do not make it.
```
R2:
```
.claude/agents/ is not the eleven-agent bench (HD-293).
  unexpected: zzz-twelfth.md        missing: (none)
  .claude/agents/api-docs-sync.md:6   effort: max — allowed: low, medium, high, xhigh
  CLAUDE.md:115  says backend-builder (fable/high); the frontmatter says opus/xhigh
An agent is added, renamed or removed in BENCH (this test), CLAUDE.md § Subagents, SKILL.md § The bench
and check-gates.mjs AGENT in one commit. A model or effort change is dated in docs/retro/README.md
(HD-293 attachment) and mirrored in CLAUDE.md § Subagents.
```
R3:
```
The gate set is stated in four places and they disagree (dev-team-pipeline.md §12.2).
  GATES                          : api_docs dc_cloud migration ops_witness security spec tenancy tests ui_qa
  SKILL.md phase headings        : ui-qa is not a gate; ui_qa is missing
  SKILL.md run.json schema       : matches
  check-gates.mjs AGENT keys     : matches
  check-gates.mjs required.add   : matches
Rename or add a gate in all four and in GATES in one commit. The hook derives what it requires from
AGENT, so a name the skill uses and the hook does not is a gate that never blocks.
```

### Highest-risk assumptions (builder's first report)

1. P7 — the margin and therefore that the first run is green; and that the ticket's AC-1 negative control is
   unreachable without D5 (two commands, outputs pasted).
2. D1 — blame reports an uncommitted line with `committer-time` = now (`echo "- test" >> CLAUDE.md; git blame
   --line-porcelain -L '$' -- CLAUDE.md`; then `git checkout -- CLAUDE.md`).
3. P9 — the CI clone is shallow today (`git rev-parse --is-shallow-repository` printed from the test in the first
   CI run *before* D8 lands, or read from the checkout step's log).
