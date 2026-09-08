# Pipeline history and the two-week checkpoint — HD-303 (epic HD-294, fix version 0.18.2)

> **Status: proposal.** Spec date **2026-09-08**. Claims about today's tree are **measured** (command/fetch run
> 2026-09-08), **read** (file:line) or **inferred**. 2 story points: one append in the hook, one script, one
> `node:test` file with fixtures, two `.gitignore` lines, one doc section, one skill line, one local table.
> Process tooling under `.claude/pipeline/` — no tenancy, no API, no schema, no profile.
>
> Backlog search: repo grep `history\.jsonl|checkpoint\.mjs|checkpoints\.jsonl|HD-303|HD-294` (2026-09-08) —
> only HD-295's header; the tracker is not reachable from this seat, orchestrator confirms against HD-294's
> children. Related: **HD-293** (the fields this measures), **HD-295** (sibling: guards that pass while checking
> nothing), `dev-team-pipeline.md` §6.5 ("optimizes the honest-but-forgetful case, not an adversarial orchestrator").

> **Implementation notes (2026-09-08, as built):** the first window starts after `5c2adfe` (HD-295, the last commit
> before the history existed), not `3794299` — the earlier commits cannot have a record by construction; the seal runs as
> `node --test .claude/pipeline/check-gates.test.mjs` (Node 24 does not accept a directory); Phase 7 of the skill runs the
> hook by hand so a session that rolls into the next task still records its finish; a fixture run says on stderr that it
> recorded nothing; foreign or torn history lines are skipped by both readers and counted by the checkpoint.

## 1. Problem & goal

The hook checks shape, not truth: **read** `check-gates.mjs:112-113` — any `{"n/a": "<non-empty>"}` satisfies
`catShape`; `:132` — anything matching `/^(seen|n\/a)\s*:\s*\S/` is a negative control. **Read** `run.json:1`
today: `"category":{"n/a":"pending — the builder fills it"}` — a placeholder the hook would accept as final.
How often a run finishes like that is unmeasurable: `run.json` is ignored (`.gitignore:5`) and overwritten per
task. Goal: every allowed finish leaves one immutable line; a 14-day ritual turns the lines plus `git log` into
three numbers with a 50 % threshold each; the ritual is written where git keeps it.

## 2. Scope / non-goals

**In:** append in `check-gates.mjs`; `.claude/pipeline/checkpoint.mjs`; `.claude/pipeline/check-gates.test.mjs`
(+ `fixtures/`); `.gitignore`; `dev-team-pipeline.md` §12.6; one SKILL.md line; local `docs/retro/README.md`.
**Out:** judging whether an `n/a` text is *true* (the checkpoint reader does that); changing what the hook
blocks; a tracker query; a CI job or Maven step; a `SubagentStop` hook; tamper-proofing (§6.5 stands).

## 3. Actors & permissions — n/a

No request, workspace or `Permission`. Writer: the hook. Reader: the owner or orchestrator running the
checkpoint. Sink: HD-294 (monthly attachment).

## 4. Behaviour & rules

### 4.1 Files
| File | Tracked | Written by |
|---|---|---|
| `.claude/pipeline/history.jsonl` | **ignored** (new line beside `.gitignore:5`) | hook, append-only, never truncated |
| `.claude/pipeline/checkpoints.jsonl` | **ignored** | `checkpoint.mjs`, append-only |
| `checkpoint.mjs`, `check-gates.test.mjs`, `fixtures/**` | tracked | builder |

### 4.2 Record — one JSON object per line
| Field | Type | Source |
|---|---|---|
| `schema` | int `1` | literal, first key so a reader can branch |
| `ts` | string, ISO-8601 UTC | `new Date().toISOString()` |
| `task` | string | `run.task ?? ""` |
| `class` | `"feature"\|"light"\|"trivial"` | `run.class ?? "feature"` (the hook's own default, `:38`) |
| `head` | string, 40-hex or `""` | `git rev-parse HEAD`; `""` when git fails — a record is never skipped for it |
| `touched` | string[] | keys of `areas` that are true (`:56-73`), `[]` for none |
| `gates` | object | `run.gates ?? {}` verbatim (strings or `{status, negativeControl}`) |
| `category` | object \| null | `run.category` verbatim; null only on a trivial record (the hook never checks it there) |
| `negativeControl` | string \| null | what `:131` resolves: `gates.tests.negativeControl`, else `run.negativeControl` |
| `verdict` | `"pass"\|"escaped"` | D6 |
| `unmet` | string[] | only with `escaped`: first line of the block reason |
| `runHash` | string, 64-hex | sha256 (`node:crypto`) of `JSON.stringify(run)` with keys sorted recursively |

Not recorded: `changed` (absolute paths of one machine — noise in a tracker attachment), `spec`.

### 4.3 When a line is written
- Only at the finishing exits of §6, after `run.json` parsed. `try { appendFileSync } catch { one stderr line }`
  — **measured** 2026-09-08 (docs fetch): stderr of an exit-0 hook goes to the debug log only, so a failed
  append can neither block nor disturb a session.
- **D1 — trivial runs are logged** when a `run.json` exists and the hook allows (`:82`); a trivial run touching
  code is blocked (`:81`) and not logged. They are the bypass candidates, and `class` lets the checkpoint keep
  them out of N1/N2.
- **D2 — dedupe key `(runHash, verdict)`.** Read the last 64 KiB, parse complete lines, skip if any has the same
  pair. Not `task+head`: the owner commits *after* a finish, so HEAD changes under an unchanged run (false second
  finish), while a fix-loop re-finish with edited gates keeps HEAD (swallowed). Hence a re-run of the same task
  with edited gates writes a **second line with the same `task`**; the checkpoint keeps the last per task
  (§4.5). A tail scan rather than "last line only" so two sessions on one checkout, alternating status turns,
  do not ping-pong duplicates.
- **D6 — `stop_hook_active` (highest-risk assumption).** The parent asked that this Stop not be logged. **Read**
  `:33`: it is allowed *before any evaluation*. **Inferred** (the docs page fetched 2026-09-08 does not define the
  field; training-time definition: "true when Claude Code is already continuing as a result of a stop hook"):
  after a block the sequence is *block → orchestrator fixes → Stop with `stop_hook_active=true`*, so the finish
  after a fix loop — the run most likely to have been closed with text — is the one that would go unlogged, and
  if the owner opens the next task at once it never gets a record and surfaces as bypass. Recommendation: under
  the flag the hook **evaluates and records but never blocks** — `:33` sets `LOOP_GUARD`; `block()` under it
  appends `{verdict:"escaped", unmet:[…]}` and allows; a clean evaluation appends `pass`. Cost: git commands the
  hook already runs. **Builder verifies first:** a fixture that blocks, then a second Stop, stdin dumped to the
  scratchpad; both objects pasted into the closing comment. If the field appears on any other Stop, fall back to
  "no record under the flag" and say so in the comment.

### 4.4 Fixture isolation
`HISTORY = HAMSTRACK_HISTORY ?? (any of HAMSTRACK_RUN_JSON | HAMSTRACK_FAKE_FILES | HAMSTRACK_FAKE_ADDED set
? null : ROOT/.claude/pipeline/history.jsonl)`; `null` → no write. The checkpoint mirrors it with
`HAMSTRACK_CHECKPOINTS` and `HAMSTRACK_FAKE_LOG` (a file in the `git log` format below). A fixture cannot reach
the real files (AC-4).

### 4.5 Checkpoint — `node .claude/pipeline/checkpoint.mjs [--since <sha>] [--dry-run]`
**Inputs:** the whole `history.jsonl`; the last line of `checkpoints.jsonl`, else `--since`, else **`3794299`**
(HD-293 codification, **read** `.git/logs/HEAD:235`); `git log --format=%H%x09%P%x09%D%x09%s <since>..HEAD`.
**Definitions.** W = records with `ts` > previous checkpoint's `ts` (all, on the first run). F = per distinct
`task` in W, the record with the greatest `ts`. F_fl = F with class ∈ {feature, light} and verdict `pass`.
Keyed(s) = the set of numbers `\bHD-(\d+)\b` in s. C = commits in `since..HEAD`; Merge = more than one parent
(`%P`); Release = `%D` contains `tag: v`; Unkeyed = Keyed(subject) = ∅; **K = C − Merge − Release − Unkeyed**.
Covered(c) = ∃ record r in the **entire** history (an owner commits late) with verdict `pass` and
Keyed(r.task) ∩ Keyed(c.subject) ≠ ∅.
**Formulas.**
- **N1** = |{f ∈ F_fl : `"n/a"` ∈ keys(f.category)}| / |F_fl|
- **N2** = |{f ∈ F_fl : f.negativeControl is null ∨ matches `/^n\/a\s*:/`}| / |F_fl|
- **N3** = |{c ∈ K : ¬Covered(c)}|, share = N3 / |K|

Flag when share **≥ 0.50**; a zero denominator prints `no data`, no flag. **Output** (plain text): window
(`since7..head7`, dates); `N1 category n/a: a/b = p% [FLAG]`; `N2 …`; `N3 uncovered commits: n/k = p% [FLAG]`
then one `sha7  subject` line per uncovered commit; then three listings **never counted**: covered by a
trivial run, merges and release tags, unkeyed commits (**D5**); then `escaped` records in W by task. Exit 1 iff
any flag, else 0. Unless `--dry-run`, append `{schema:1, ts, since, head, n1:{num,den}, n2:{…}, n3:{…}, flags:[…]}`
to `checkpoints.jsonl`.
**D5 evidence:** the key convention holds — **measured** 90 of 203 `commit` reflog lines end in `(HD-nnn)`,
12 of the last 12 (**read** `.git/logs/HEAD:225-236`); merges exist on main (**read** `:114,128,144,155,164,175,190`,
`merge feat/…: Merge made by the 'ort' strategy`); tags `v0.15.0`–`v0.18.1` exist (**read** `.git/refs/tags/`).
Trivial-class `pass` records cover their commits (the hook saw the run) and are listed apart; `escaped` records
cover nothing.

### 4.6 Where the procedure lives — D3
**`docs/design/dev-team-pipeline.md` §12.6 "Two-week checkpoint"** is the canonical tracked copy: §12.5 already
holds the measures pointer (`:330-331`), CLAUDE.md already routes readers there, and a ritual the *owner* runs
does not belong in SKILL.md, whose text loads into every orchestrated task (prose budget). SKILL.md gets one line
under "Three rules": *every allowed finish is appended to `.claude/pipeline/history.jsonl`; never edit or truncate
it — §12.6 reads it every 14 days.* §12.6 states: the command; the three formulas by name; the threshold; the
cadence — **every 14 days from 2026-09-07: first run 2026-09-21**, then 10-05, 10-19, …; the monthly export
(first checkpoint of each month: attach `history.jsonl` and the output to HD-294); the reaction rule — a flag
opens one ticket under HD-294 naming the runs, never a silent threshold change. The local `docs/retro/README.md`
gets a table **"Two-week checkpoints"** under Measures — `date | since..head | N1 | N2 | N3 (listed) | flags |
action ticket` — first row on 2026-09-21, and `checkpoint.mjs` / `check-gates.test.mjs` join the codified list
(README:19).

## 5. Edge cases & failure modes
- No history file → created on first append. A torn trailing line (crash mid-write) → tail parser skips it,
  dedupe works on earlier lines; the checkpoint skips bad lines and prints how many.
- `run.json` present but unparsable → `:37` allows, nothing logged (unchanged); its commit shows as bypass —
  correct.
- One task finished under two `task` strings → two entries in F; key matching still covers the commit. A task
  string without a key (`"hotfix"`) → its commit is listed under N3 with its subject: visible, not silent.
- Git unavailable in the hook → `head: ""`, record still written. Checkpoint without git → refuses (N3 needs it).
- Two sessions, one checkout → interleaved lines; tail-window dedupe; the checkpoint groups by task.
- Growth ~1 KB/record; the hook reads 64 KiB, never the whole file; rotation is the monthly export, never a
  truncation.
- Tiny denominators: 1 of 2 flags. Accepted — the listing shows which run; the threshold is the ticket's.

## 6. Category — every exit of `check-gates.mjs` (read 2026-09-08)
Rule: *every exit of the hook states whether it is a finish and what it records.*

| # | Line | Exit | Records |
|---|---|---|---|
| A1 | 33 | `stop_hook_active` | D6: evaluate; clean → `pass`, would-block → `escaped`; never blocks |
| A2 | 37 | no / unparsable `run.json` | nothing (no run) |
| A3 | 82 | trivial, no code touched | `pass`, class trivial, `category` null |
| A4 | 137 | all checks satisfied | `pass` |
| B1 | 81 | trivial touching code | nothing |
| B2 | 105 | gates unmet | nothing |
| B3 | 114 | category shape | nothing |
| B4 | 118 | one-member category + guard in diff | nothing |
| B5 | 124 | `sealedBy` names no test | nothing |
| B6 | 132 | tests gate without negative control | nothing |

Seal: `check-gates.test.mjs` counts `allow()` and `block(` call sites in the hook source (**4** and **6** today)
and fails when a count changes without this table changing — a new exit is a deliberate row, never an omission.
`category.sealedBy: ".claude/pipeline/check-gates.test.mjs"` passes the hook via `existsSync` (`:124`); the
name matches `TEST_RE` (`:55`), so the `tests` gate arms for this very run.

## 7–9. Data model / API / Frontend / DC-Cloud — none

## 10. Acceptance criteria (over the category; shape: `node --test .claude/pipeline/`, all under `HAMSTRACK_*` overrides)
1. **Every finishing exit (A1-pass, A3, A4) appends exactly one line; every blocking exit (B1–B6) and A2 append
   none.** One fixture `run.json` per row of §6, the hook run as a child process with stdin `{}` (and
   `{"stop_hook_active":true}` for A1), line count of a temp history before/after.
2. **Once per `(runHash, verdict)`:** the same fixture three times → 1 line; a gate flipped → 2; reverted → 2.
3. **Survives the next task:** two fixtures in sequence → 2 lines, the first byte-identical.
4. **No override reaches the real file:** `HAMSTRACK_RUN_JSON` set, `HAMSTRACK_HISTORY` unset → the real path's
   size is unchanged.
5. **Checkpoint on a synthetic history:** 10 feature `pass` records, 6 with `{"n/a": …}` and 6 with `"n/a: …"` →
   prints `6/10 = 60%` twice, both flagged, exit 1; with 4 → no flag, exit 0; trivial records added → denominators
   unchanged.
6. **Uncovered commits listed by sha and subject:** `HAMSTRACK_FAKE_LOG` with a keyed uncovered, a keyed covered,
   a merge, a tagged and an unkeyed commit → exactly the first under N3, the others in their own listings; the
   N3 flag follows the share.
7. **The write never blocks or slows a Stop:** history path pointed at a directory → exit 0, empty stdout; Stop
   wall time measured before and after the change, both pasted.
8. **Negative control is the mechanism:** test-runner comments out the append, watches AC-1 red, pastes the line.
9. **Documented:** §12.6 exists with the command and date 2026-09-21; SKILL.md carries the line; the local README
   table exists; `git check-ignore -v` names the rule for both `.jsonl` files (the `.gitignore:146` habit).
10. `category` on the ticket: rule = §6, members = A1…B6, sealedBy = `.claude/pipeline/check-gates.test.mjs`.

## 11. Open questions (recommended default)
1. Log under `stop_hook_active`? **Yes, per D6** — after the builder's measured drill. 2. Record `changed`? **No**
(§4.2). 3. Threshold `≥` or `>`? **≥ 50 %** — a tie is a flag. 4. Wire the checkpoint or the hook test into
`npm-test`/Maven? **No** — a dated human ritual, not a build gate; revisit when a second `.mjs` test exists.
5. Track `checkpoints.jsonl`? **No** — a ritual must not dirty the tree; the README row and the HD-294 attachment
are the durable copy, `--since` restores a lost file.

## 12. ADR — none

## 13. Observability contract
| Failure mode | Witness | Drill |
|---|---|---|
| Hook stopped appending (regression, hook unregistered) | N3: keyed commits with no record, listed by sha | finish a fixture task with the append commented out; run the checkpoint; the commit is listed |
| Fields closed with text | N1 / N2 ≥ 50 % flag | AC-5 fixture |
| Finish escaped through the loop guard | `escaped` listing | A1 fixture with an unmet gate |
| The ritual itself not run | §12.6 date and the README's last row older than 14 days; no monthly attachment on HD-294 | none by design (Q4) — the owner's calendar is the alert |
