# Vacuous verification rules — HD-295 (epic HD-294, fix version 0.18.2)

> **Status: proposal.** Spec date **2026-09-07**. Every claim about today's tree is labelled
> **measured** (command run 2026-09-07), **read** (file:line) or **inferred**. 1 story point:
> one test class, one helper, one fixture directory, one `package.json` script, four doc lines.
>
> Backlog search: repo-side grep for `HD-295|HD-297|HD-164|VACUOUS|@Disabled|tsc --noEmit`
> (2026-09-07) — no existing rule; related: **HD-164** (assert conversion, `pom.xml:363-382`),
> **HD-297** (ArchUnit sibling), **HD-265** (suite coverage guard), **HD-242** (vitest in
> `verify`). `.claude/agents/test-runner.md:28` already states the skip rule as *prose* — this
> ticket makes it a mechanism.

## 1. Problem & goal

Three ways a guard passes while checking nothing, all shipped here: 1258 bare `assert`s inert
under any IDE (no `-ea`; HD-164 converted them 2026-09-04), `tsc --noEmit` type-checking zero
files while being the first command an agent was told to run (G06), and a skipped test reading
as a pass in a summary line. The HD-265 guard **cannot** see the third — it counts a class-level
`@Disabled` as present (**read** `ExecutedTestClassRecorder.java:43`). Goal: each of the three
is refused by the build, over the whole corpus, and the refusal is proven red before it is trusted.

## 2. Scope / non-goals

**In:** one JUnit class + predicate helper; a fixture directory; `typecheck` script; edits to
CLAUDE.md:51, `frontend-builder.md:36`, `test-runner.md:21,28`; one line in `project-state.md`.
**Out:** checking that a referenced HD ticket exists or is open (needs the tracker at build
time); conditional gates (`@DisabledIf*`/`@EnabledIf*`, `Assumptions.assume*`, `it.skipIf`/
`runIf` — they carry their own reason); `assert` in `src/main/java`; ESLint (**read**: no
`eslint.config.*` under `src/main/frontend`); rewriting the five historical proposals under
`docs/design/` that say `tsc --noEmit` — they are records, and editing them is the
"edit an applied migration" mistake.

## 3. Actors & permissions — n/a

A build rule. No request, no workspace, no `Permission`, no 404/403 question.

## 4. Behaviour & rules

### Category members (populations, counts measured 2026-09-07)

| Population | Definition (via `git ls-files`, reuse `PublishedCredentials.trackedFiles()`) | Today | Floor |
|---|---|---|---|
| P1 backend test sources | `src/test/java/**/*.java` (every file, helpers included) | **308** | ≥ 250 |
| P2 frontend test sources | `src/main/frontend/src/**/*.{test,spec}.{ts,tsx}` — **must equal vitest's `include`** (**read** `vitest.config.ts:13`) | **70** `.test.*`, 0 `.spec.*` | ≥ 50 |
| P3 `package.json` scripts | every value under `scripts` in `src/main/frontend/package.json` | 6, none `typecheck` | n/a |
| Skip markers in P1+P2 | see R1 | **0** (one javadoc mention, stripped) | — |
| Bare `assert` in P1 | see R2 | **0** | — |

Floors are ~80 % of today; raise deliberately, never lower (the `EmailLengthBoundTest:167` tripwire rule).

### D1 — home: one JUnit class, authoritative for all three rules

`src/test/java/com/hamstrack/common/testsupport/VacuousVerificationRulesTest.java` (beside
`SuiteCoverageGuard` and `LocaleIndependentFoldingTest`, the other guards over the corpus
itself) with the predicate in a sibling **not** named `*Test*` — `VacuousVerification.java`
(the `PublishedCredentials` split: rule once, test + positive control both call it; and a
helper wearing a test name is demanded by the HD-265 guard). Plain JUnit, no Spring context.
It runs inside `mvnw verify` on every push; a vitest-side copy of the frontend half would mean
two floors and two messages for one rule. **Until HD-297 lands, this class is authoritative for
R2 as well; if HD-297 re-implements R2 in ArchUnit, the regex R2 is deleted in the same commit**
— two rules for one thing diverge. (**Inferred**: ArchUnit sees a bare `assert` as a
`$assertionsDisabled` read; the builder of HD-297 checks that.)

### Text preparation (both sides)

Blank line and block comments **and string/template literals**, preserving line breaks
(`EmailLengthBoundTest.stripComments` shape, plus strings as `LocaleIndependentFoldingTest`
does). Consequences: the javadoc mention at `ExecutedTestClassRecorder.java:43` is invisible;
the scanner's own failure-message literals naming the markers are invisible to itself;
fixtures therefore live in **files**, not literals (§5).

### R1 — no skip marker without a ticket

| Side | Marker regex, applied to the **stripped** text |
|---|---|
| P1 | `@Disabled\b` (the `\b` excludes `@DisabledOnOs`/`@DisabledIf…`) |
| P2 | `\b(?:x(?:it|test|describe)|(?:it|test|describe)\.(?:skip|only|todo))\b` — `.only` is in: it leaves every *other* test unexecuted, and `allowOnly` is off only when `CI` is set |

**D2 — the reference is `\bHD-\d+\b` on the same physical line, read from the ORIGINAL
line at that line number** (the reference lives in the `@Disabled("HD-301 …")` argument or a
trailing `// HD-301`, both of which stripping removes — reading the stripped line reports
every legitimate skip). Same line only, one regex, no exceptions: one rule per side instead of
a comment parser in two languages; it agrees with the audit anyone actually runs
(`rg '@Disabled' | rg -v 'HD-'`); a reference on the line above detaches on the first
reorder and the rule then reports it — loud and a one-line fix. Cost: a longer line.

### R2 — no bare `assert` in P1

`\bassert\b` on the stripped text. `assert` is a reserved word, so any survivor is the
statement (`assertThat`/`assertTrue` fail the trailing `\b`). Remedy in the message: AssertJ.

### R3 — `tsc -b` is the only type-check

`package.json` gains `"typecheck": "tsc -b"`. **D3 — only P3 is asserted mechanically**:
`scripts.typecheck` equals `tsc -b`, and every script that invokes `tsc` invokes it as `tsc -b`
(covers `build`, `typecheck`, and the next one). Living docs are edited **once** by the builder
and **not sealed**: a classifier that tells "run `tsc --noEmit`" from "never run `tsc --noEmit`"
in prose is the rule `PublishedCredentials.java:35-38` warns against — it would be answered by
deleting the warning. Edits: CLAUDE.md:51 keeps the explanation and ends "the gate is
`npm run typecheck`"; `frontend-builder.md:36` and `test-runner.md:21` name `npm run typecheck`
(keep the parenthetical warning); `test-runner.md:28` points at the test class.
**Measured**: `docs/self-hosting.md`, `docs/release-checklist.md` mention no `tsc` — nothing to do.
`pom.xml` is unchanged: `npm run build` already runs `tsc -b` on every `verify`; the script names
the gate, it does not add one.

### Failure message (≤ 25 lines, names the action)

One line per offence `path:line — <marker or assert> …`, then: skip → "put `HD-<n>` on this
line, in the reason string or a trailing comment; a skip with no ticket is a pass nobody
earned"; assert → "use `assertThat(...)`: a bare `assert` runs only under `-ea` (HD-164)";
floor → "the scan saw N files, fewer than the F that exist — find which population stopped
matching; do not lower F".

## 5. Edge cases & failure modes

- **Both real populations are empty today**, so the only proof of life is the positive control:
  fixtures under **`src/test/resources/vacuous-verification/`** with suffixes `.java.txt` /
  `.test.tsx.txt` — outside P1/P2 by directory **and** extension, by population definition
  rather than by an exemption list. Each is fed through the same predicate and must report
  exactly its planted line: `@Disabled` alone; `@Disabled` with `// HD-301` on the **next**
  line (reported); `@Disabled("HD-301: …")` and `@Disabled // HD-301` (**not** reported —
  proves D2's original-line lookup); `assert x > 0;` and `assert (x);` (reported);
  `assertThat(x)` at line start (not); `it.skip(`, `describe.only(`, `xit(`, `test.todo(`
  (reported); `it.skip('… HD-301')` and `it.skipIf(cond)` (not); a marker inside a comment
  and inside a string (not).
- Mis-rooted run: `trackedFiles()` already refuses an empty listing; the floors refuse a scan
  that stopped seeing a population (e.g. the `.spec` alternative dropped).
- A `\r\n` checkout: line numbers computed on `\n` only; fixtures are read the same way.
- A marker on a line whose reference is a different ticket shape (`hd-301`, `HD301`):
  reported. Case and hyphen are the format.

## 6–9. Data model / API / Frontend / DC-Cloud

None / none / `package.json` script only, no component / none — no env var, profile or
compose change. `migration-reviewer`, `api-docs-sync`, `dc-cloud-guard`: n/a.

## 10. Acceptance criteria (over the category)

1. **Every file in P1 and P2** carrying a skip marker without `HD-\d+` on the same line fails
   the build with `path:line`; test shape: fixture cases of §5 through the real predicate.
2. **Every file in P1** containing a bare `assert` statement fails the build; same shape.
3. **Every script in P3** that invokes `tsc` invokes `tsc -b`, and `typecheck` exists and is
   exactly `tsc -b`; test shape: parse `package.json` (Jackson 2 is on the classpath).
4. **Each rule is watched red on a real member before it is trusted**: the builder plants a
   bare `@Disabled` in a real P1 test, a bare `assert` in another, an `it.skip(` in a real P2
   file and `"typecheck": "tsc --noEmit"` in P3, runs the class, pastes the four red lines
   into the closing comment, reverts, and shows green. A fixture proves the predicate; the
   plant proves the wiring (population, stripping, line numbers).
5. The class asserts the floors of §4 and fails when either population scans below them.
6. `rg -n "tsc --noEmit"` over CLAUDE.md, `.claude/agents/*.md`, `docs/self-hosting.md`,
   `docs/release-checklist.md` and `package.json` returns only lines that *warn against* it.
7. `category` block on the ticket: rule = "vacuous verification cannot be typed by habit",
   members = P1 ∪ P2 ∪ P3, sealedBy = `VacuousVerificationRulesTest`.

## 11. Open questions (recommended default)

1. Include `.only`? **Yes** (§4 R1). 2. Widen R1 to the conditional families? **No** — a
condition is a reason; revisit if one is used to hide a failure. 3. Widen R2 to
`src/main/java`? **No**, separate question, separate ticket. **Highest-risk assumption:**
string-stripping in TS (nested template literals) — a marker inside `${…}` could be blanked;
accepted, because every real marker is a call at statement position, and the plant in AC-4
exercises real files.

## 12. ADR — none

A build rule, not an architectural fork.

## 13. Observability contract — n/a

A build rule has no production runtime; its witness is the red build itself, and its drill is
AC-4.
