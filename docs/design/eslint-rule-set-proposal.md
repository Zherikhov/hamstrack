# ESLint rule set — HD-300 (epic HD-294, wave 2)

> **Status: proposal.** Spec date **2026-09-11**. Evidence labels: **M** = grep/read run this session, quoted with
> `file:line`; **R** = read; **I** = inferred, the builder measures before code.
> **No shell was available in this session** — nothing here was executed. Every "today" count is a `ripgrep` over the
> working tree; every *timing* number in this document is absent on purpose and is produced by the builder (§10).
> Backlog search: repo grep `-i "eslint|lint"` over the tree excluding `node_modules` (2026-09-11) — **31 files, none
> of them a config, a dependency, a script or a proposal**. The hits are: `CLAUDE.md`/`frontend-builder.md`
> (the "ESLint rule set, wave 2" promise this ticket redeems), `docs/design/{archunit,vacuous-verification}-proposal.md`
> (which explicitly defer the frontend half here), `qodana.yaml` (JetBrains, `linter: jetbrains/qodana-jvm-community`
> — **JVM only**, and wired to no workflow: M, no `qodana` step in `.github/workflows/*.yml`), and 25 incidental
> `-lint`/`sprint`/`lint`-substring matches in SQL, Java and TSX. **No match; no duplicate.**
> Related: **HD-177** (px type sizes), **HD-117** (overlays), **HD-266/267/268** (colour paints), **HD-98/HD-116**
> (predicate copies), **HD-295** (`VacuousVerificationRulesTest`, which constrains `package.json`), **HD-242**
> (`npm-test` wiring), **HD-297** (the backend twin of this ticket).

---

## 1. Problem & goal

The frontend trap list is prose in `.claude/agents/frontend-builder.md:18-22` (R). Eight shipped defects were its
members. The SPA has no linter and no static gate other than `tsc -b`.

**Goal:** each trap that has a mechanical form is executed by exactly **one** mechanism whose failure text is the trap;
the tree is clean on day one for every trap whose debt is small enough to pay here; the one trap whose debt is not
(194 sites by the spec's own grep; **204** once the rule was built and could see arithmetic and conditional
spellings the grep could not — the implemented number lives in `lint.debt.mjs` and nowhere else) carries that debt
**at the sites, on a clock**, not in a regenerable file; and the prose in
`frontend-builder.md` becomes a pointer.

---

## 2. Scope / non-goals

**In:** ESLint 9 flat config + `typescript-eslint` parser as dev dependencies; four custom rules written as plain ESM
in `src/main/frontend/eslint-rules/`; one **type** change (`Hex`) that replaces the ticket's fifth rule; a
`lint` npm script that is a small runner (not a bare `eslint`); a fifth `frontend-maven-plugin` execution; three
accessibility fixes (§5 R3); 194 inline debt directives + one debt test; pointer edits in `frontend-builder.md`,
`CLAUDE.md`, `docs/project-state.md`; deletion of one now-redundant regex in `palette.contrast.test.ts`.

**Out:** any stock rule set (`eslint:recommended`, `react-hooks`, `jsx-a11y`) — Q1; ESLint's own bulk-suppressions
file (`--suppress-all` / `eslint-suppressions.json`) — **banned by a test**, see §6; auto-fixers for the four rules
(a fixer that rewrites a `fontSize` needs a design decision per site); backend lint; Qodana; the HD-177 sweep itself
and the HD-117 `Modal` migration (this ticket makes both mechanical and dates them).

**N/a:** actors & permissions, data model, API surface, DC/Cloud — a build-time gate; there is no runtime behaviour,
no profile and no env var. The one runtime-visible change in this ticket is §5 R3's three `role="dialog"` additions.

---

## 3. Premises the ticket states, measured

Everything in this section was measured this session by `ripgrep` over `src/main/frontend/src`.

- **P1 — there is no ESLint configuration and no lint dependency (M).** `src/main/frontend/` contains exactly
  `vite.config.ts`, `vitest.config.ts`, `tsconfig{,.app,.node}.json`, `package.json`, `package-lock.json` (M, glob).
  `package.json:29-44` lists 15 devDependencies; none is ESLint, a plugin or a parser (M). Eight scripts
  (`dev, build, typecheck, preview, test, test:watch, audit:contrast` — M `package.json:7-15`).
- **P2 — `npm run build` is `tsc -b && vite build`; `npm run typecheck` is `tsc -b` (M `package.json:9-10`).**
  `tsconfig.json` is solution-style (`"files": []` + two references, M `:1-7`), so `tsc --noEmit` checks zero files.
  `VacuousVerificationRulesTest#everyScriptThatTypeChecksRunsTheBuild` refuses any script matching
  `\btsc\b(?!\s+(?:-b|--build)\b)` and pins `typecheck` to exactly `tsc -b`, over a floor of **6** scripts
  (R `VacuousVerificationRulesTest.java:96-228`). **Nothing proposed here contains the string `tsc`.** Adding
  scripts is safe (the floor is a minimum); renaming or folding `typecheck` away is not.
- **P3 — the Maven wiring (R `pom.xml:200-334`).** One `frontend-maven-plugin` 1.15.1, `workingDirectory
  src/main/frontend`, plugin-level `<skip>${frontend.skip}</skip>` covering all executions (`:207-210`). Four
  executions: `install-node-and-npm` (v22.13.0) + `npm-install` (`ci`) + `npm-build` (`run build`) at
  **generate-resources**, and `npm-test` (`run test`) at **test** (`:327-332`). The long comment at `:265-273`
  records the load-bearing fact: `AbstractFrontendMojo.skipTestPhase()` honours `-DskipTests` **only** for an
  execution bound to `test` or `integration-test` (verified against 1.15.1 in HD-242). Ordering within a phase is
  declaration order after the default-lifecycle binding — stated and "verified in the build log for both" at
  `pom.xml:503-506` (R).
- **P4 — the Docker image build pays for `generate-resources`, not for `test` (R `Dockerfile:20-21`:
  `./mvnw clean package -DskipTests -B`).** Anything folded into `npm run build` is paid a **third** time per
  pipeline; anything bound to `test` is free there (`build-and-push` `needs: build-and-test`, R
  `.github/workflows/build.yml:192-193`). CI's only command is `./mvnw -B verify` (R `build.yml:181`).
- **P5 — `src/main/frontend/audit/*.json` and `audit/**/*.json` are gitignored (M `.gitignore:150-151`).** A
  baseline JSON dropped in the obvious place would be **silently absent from a fresh clone and from CI**. Recorded
  because it is a concrete instance of the general argument in §6.

---

## 4. The five rules as ticketed, and what each one's population actually is

| # | Rule as ticketed | Population of the **ticketed shape** (M) | Population of the **real shape** (M) |
|---|---|---|---|
| R1 | forbid `max-w-2xs…3xl` classes | **0** | **0.** All 10 text hits of `max-w-xl` are inside comments *warning about the trap* — `AccountPage.tsx:65`, `BacklogPage.tsx:1482`, `components/roles.tsx:309`, `pages/admin/common.tsx:18`, `NotFoundPage.tsx:53`, `ReleasesPage.tsx:130`, `reports/ReportsArea.tsx:64`, `settings/WorkspaceGeneralPage.tsx:361`, `settings/WorkspacePeoplePage.tsx:369`, `settings/WorkspaceStoragePage.tsx:229`. Live `max-w-*` in a `className`: `max-w-full` ×6, `max-w-44` ×1 (`ProjectSwitcher.tsx:110`), `max-w-24` ×2 (`SearchResultsPage.tsx:491,498`) — all legal (numeric and `full` do not read the `--spacing-*` namespace). |
| R2 | forbid numeric `fontSize:` in style objects | ticket says ~157 | **194 across 59 files**, all production (`!*.test.*` filter returns the same 194). `fontSize={12}` JSX-prop form: **0**. `fontSize: '12px'` string form: **0**. Top files: `HomePage` 18, `CommandPalette` 10, `MyWorkPage` 10, `NavRail` 9, `admin/AdminArea` 8, `reports/AgingChart` 7, `reports/CycleTimeReportPage` 7, `ShortcutsHelp` 6, `reports/VelocityPage` 6. SVG-chart subtotal (`{Aging,Burnup,CycleTime,Flow,Velocity,Insights}Chart`): **28**. |
| R3 | forbid `fixed inset-0` overlays outside `Modal` | ticket says four | **The Tailwind class pair does not exist in this repo: `fixed inset-0` → 0 matches; `inset-0` → 1 match, and it is `absolute inset-0` (`IssueDetail.tsx:973`, an image-placeholder centre, not an overlay).** The real shape is the inline style `position: 'fixed', inset: 0`: **8 sites**, of which 1 is `Modal` itself (`pages/admin/common.tsx:195-200`) and 1 is a popover click-catcher (`pages/admin/common.tsx:89`). **6 hand-rolled overlays** outside `Modal`: `AboutModal.tsx:16→34`, `CommandPalette.tsx:394`, `CreateIssueModal.tsx:314→337`, `CreateProjectModal.tsx:216→234`, `ShortcutsHelp.tsx:131`, `WorkspaceMembersModal.tsx:38→57`. **Three of the six carry no `role="dialog"` and no `aria-modal`** — `AboutModal`, `CreateIssueModal`, `CreateProjectModal` (M: grep `role=['"{]\|aria-modal` over those files returns only `CreateProjectModal.tsx:131 role="radiogroup"`). Three `position: 'fixed'` sites **without** `inset: 0` are not overlays and must not fire: `AppShell.tsx:59` (toast), `NotificationBell.tsx:108` (portal menu), `ui.tsx:327` (dropdown). |
| R4 | forbid a `var(--` literal flowing into a prop typed as a stored hex | **0** | **0 detected — and already sealed.** `palette.contrast.test.ts:426-471` (`TOKEN_INTO_DERIVED`) is a two-regex scan over every `{components,pages}/**/*.tsx`, with a positive control at `:440-452` and a floor of 60 files. Its javadoc at `:404-411` records the blind spot **by measurement**: a token reaching the prop through a variable or a lookup table (`color={STATUS_COLOR[u.status]}`) is invisible to it, "the lookup-table version was reinstated verbatim and the whole suite stayed green". That blind spot — not the literal — is what is worth closing here. |
| R5 | forbid `myRole ===` / role-name literals in render gates | **0** in production render gates | **0.** Every production `myRole` mention is a type declaration (`types.ts:35,129`), a javadoc, or `WorkspacesPage.tsx:43-46`'s `roleLabel`, which `:38` documents as "the one legitimate reader of `myRole` left in the SPA". A naive role-literal regex hits **10 legitimate production sites**: six `user?.systemRole === 'ADMIN'` (`NavRail.tsx:297,320`, `ShortcutsHelp.tsx:88`, `palette/commands.ts:316`, `useGlobalShortcuts.ts:93`, `admin/AdminUsersPage.tsx:103,121,122`, `admin/AdminArea.tsx:126`) — a *different axis*, the instance admin flag, which is not in any `PermissionSet` — and three `r.builtIn && r.key === 'MEMBER'` fallback lookups (`roles.tsx:548`, `settings/ProjectPeoplePage.tsx:380`, `settings/WorkspacePeoplePage.tsx:232`), which `roles.tsx:531` documents as the sanctioned way to find the built-in Contributor. |

**Four of the five rules have population 0 today.** That is the single most important measurement in this document and
it drives §6, §7 and §9: a rule with population 0 can be shipped at `error` with no debt at all, and *can never be
seen red on real code*, so its only possible evidence is a planted instance.

**Corrected premises, plainly:** the ticket's "~157 `fontSize` sites" is **194**; its "four overlays" is **six**,
none of which uses the Tailwind classes the rule was to be written against, and **three** of which are live
accessibility defects rather than style debt; and `max-w-*`'s ten apparent hits are ten comments — a rule written as
text search would ship with a baseline of ten false positives and be believed.

---

## 5. The mechanism, per rule

All four ESLint rules are **syntactic** — they read the ESTree/TSESTree AST produced by `@typescript-eslint/parser`
with **no `projectService`** and no type information. That is a deliberate decision (D2): type-aware linting costs a
full program build per run, and none of the four needs a type. **The one thing that does need types is R4, and it
does not need a lint rule at all** — see below.

### R1 — `no-shadowed-max-width` (syntactic)
`@theme` in `index.css:133-141` declares `--spacing-{2xs,xs,sm,md,lg,xl,2xl,3xl}` (M), which is the exact range the
rule names. **Written against the AST, never the file text**: report a `Literal`/`TemplateElement` **only when it is
reachable from a `className` JSXAttribute** — directly, through a template literal, or as an argument to `clsx(...)`
(the repo's only class helper, `clsx@^2.1.1`, M `package.json:19`). Pattern on the *class token*, not the substring:
`/(?:^|\s)max-w-(3xs|2xs|xs|sm|md|lg|xl|2xl|3xl)(?=\s|$)/`. Comments are not `className` and are therefore invisible
to the rule — which is exactly the difference between this and the ten false positives §4 measured. Message: *"the
`--spacing-*` scale shadows Tailwind's `max-w-{2xs..3xl}`; `max-w-xl` resolves to 32px. Use `style={{ maxWidth: n }}`."*
**Day-one population 0; ships at `error`; no debt.**

### R2 — `no-numeric-font-size` (syntactic)
Report a `Property` whose key is the identifier or string `fontSize` and whose value is a numeric `Literal`, a
unary-minus numeric, or a string/template ending in `px`. Not restricted to a variable named `style`: `fontSize` as an
object key is unambiguous, and restricting it would miss `tick={{ fontSize: 11 }}` (recharts), which is 28 of the 194.
Message: *"the type scale is rem-based so the browser's font-size preference applies. Use a Tailwind text-* class or
a rem string (HD-177)."*
**Day-one population 194; ships at `error` with 194 inline directives — see §6.**

### R3 — `overlay-has-dialog-semantics` (syntactic, structural)
Fires on a JSX element whose `style` object expression contains `position: 'fixed'` **and** `inset: 0` (or all four of
`top/right/bottom/left: 0`), **when no descendant element in the same JSX tree carries `role="dialog"` together with
`aria-modal="true"` and an accessible name (`aria-label` or `aria-labelledby`)**. `pages/admin/common.tsx` is the one
allow-listed file (it *is* `Modal`, and its `:89` click-catcher is not a dialog). All six overlays are written as a
backdrop element with the panel as a descendant in the same expression (M, the eight sites in §4), so a local
descendant walk is sufficient — no cross-file analysis, no type information.

**This formulation is why the rule is worth having.** The ticketed formulation ("no hand-rolled overlay") fires on
`CommandPalette`, `ShortcutsHelp` and `WorkspaceMembersModal`, which are *correct* — they carry full dialog
semantics — and would demand three suppressions on working code. This one fires on exactly the three that are
broken. `data-modal-open` is deliberately **not** part of the rule: `CommandPalette.tsx:390` documents why it omits
it (its flag lives in `uiStore`), and a rule that a correct site must suppress teaches people to suppress.

**Day-one action: fix all three** (`AboutModal`, `CreateIssueModal`, `CreateProjectModal` gain `role="dialog"`
`aria-modal="true"` and a name on their panel div, matching `WorkspaceMembersModal.tsx:58`). ~3 lines each; arms
`ui_qa` (`browser-qa` verifies dialog semantics and focus behaviour). **No debt.** Migrating the six to `Modal`
remains HD-117's job and is not blocked by this.

### R4 — **not a lint rule: a branded type** (`Hex`)
`type Hex = string & { readonly __hex: unique symbol }`. Apply it to the props whose contract is "a hue that came
from the database" — `BadgeProps.color` (`ui.tsx:373`), `StatusBadge.color` (`:598`), `ParentChip.color` (`:614`) —
and to their sources in `types.ts` (`:176,187,196,270` and the `options[].color` at `:223`). Then
`<Badge color="var(--color-brand)" />` is a **`tsc -b` error**, and so is the same token arriving through a `const`
or a lookup table — the exact indirection `palette.contrast.test.ts:404-411` states it cannot see. `LegendItem.color`
(`reports/common.tsx:304`) and `StateChip` (`LandingPage.tsx:286`) legitimately take tokens and stay `string`.

An ESLint rule here would be a **second, weaker copy** of a scan that already exists — Quality rule 1 forbids exactly
that. So: the type replaces both the proposed rule *and* `TOKEN_INTO_DERIVED`'s first entry, which is **deleted** in
the same commit (its javadoc updated). The second entry — a token as the **first argument** of
`inkOn/tintOf/fillOf/ringOn/onSolid` — **stays**, because those signatures take `unknown` on purpose (`colour.ts:324,
439,453,468,491`: the value arrives from JSONB and may be anything), so no type can express it. One home each.

The gate is `tsc -b`, which already runs in `npm run build` (P2) and therefore already runs in `mvnw verify` and in
the image build. **This is the highest-risk item in the ticket** — see §10.

### R5 — `no-role-name-gate` (syntactic)
Report a `MemberExpression` whose property is `myRole` when it is an operand of `===`/`!==`, a `switch`
discriminant, or the test of a conditional/logical expression — **anywhere except** an exported function named
`roleLabel` (`WorkspacesPage.tsx:43`, the documented display-only reader). `systemRole` is **out of scope by name**
(a different axis, 6 legitimate sites) and `role.key === '…'` built-in lookups are out of scope (3 legitimate sites,
sanctioned at `roles.tsx:531`). Message: *"a role name cannot express a custom role. Derive the gate from
`myPermissions` via `usePermissions` (HD-116)."*
**Day-one population 0; ships at `error`; no debt.**

---

## 6. The design question: is a ratchet baseline acceptable here?

**No, and it is not needed for four of the five rules.** Stating it as the epic's own lesson:

1. **A ceiling set at today's count only fires on a regression.** That is the same shape two gates rejected inside
   HD-299 — a floor equal to its population asserts nothing about the population. It is defensible only where the
   debt genuinely cannot be paid in this ticket.
2. **Here the debt *can* be paid almost everywhere.** R1, R5: 0 sites. R4: 0 sites, and it becomes a type error
   rather than a lint finding. R3: 3 sites, ~9 lines, fixed in this ticket. **Only R2 (194) needs a debt record at
   all.** A single-rule debt record is not a "ratchet baseline" for the rule set; it is one rule's HD-177 backlog.
3. **The baseline mechanism the ticket implies is the decorative one.** ESLint's own bulk suppressions
   (`--suppress-all` → `eslint-suppressions.json`) are regenerated by one flag, and the regeneration is a
   *machine-written file* nobody reads in a diff. P5 adds the local twist: dropped in `audit/` it would be
   gitignored outright.

**What ships instead, for R2 only:**

- **The debt lives at the sites** (194 estimated here, 204 measured once the rule existed), as
  `// eslint-disable-next-line hamstrack/no-numeric-font-size -- HD-177`.
  Consequences that a file-level or count-level baseline does not have: a **new** `fontSize: 12` in `HomePage.tsx`
  (which already has 18) is still an error; the HD-177 sweep is a mechanical grep-and-delete; and the debt is
  visible to every reader of the file, not to whoever opens a JSON.
- **`linterOptions: { reportUnusedDisableDirectives: 'error' }`.** Each directive **expires the moment its site is
  fixed** — a leftover directive is itself a build failure. This is the property no baseline file has: the ratchet
  tightens without anyone editing it.
- **A debt test with a clock**, `src/lint/debt.test.ts`, in the shape of `palette.contrast.test.ts:351`'s
  `DECLARED_OVERRIDES` and `AgentChecklistFreshnessTest`'s 14-day blame:

  | assertion | what it stops |
  |---|---|
  | directive count ≤ `DECLARED = 194` | the number can only go **down**; going up is a hand edit in a reviewed diff whose failure message says what to write |
  | every directive carries `-- HD-\d+` | an unexplained suppression |
  | **`today > DECLARED_AS_OF + 90 days` and `DECLARED > 0` → red** | *the ratchet going quiet.* A ceiling with no clock is a permanent parking space. Red says: fix sites and lower the number, or restate the date with a reason. |
  | no `eslint-suppressions.json` anywhere in the tree; no `--suppress` in any `package.json` script or in `pom.xml` | the regeneration escape hatch, closed **from outside ESLint's own config** |
  | the flat config sets none of the four rules to `'off'`/`'warn'`, and its `ignores` equals a declared list | disarming by config edit |
  | floor: the scan read ≥ 120 source files | "found nothing" reading the same as "looked at nothing" |

  The last three are the answer to *what stops it from being quietly regenerated*: the guard is not inside the
  artifact it guards. It lives in the vitest suite, which runs inside `mvnw verify` (HD-242), and its failure
  message is the instruction.

- **The ticket's "error on new and changed files" is dropped.** It needs git plumbing in the lint path, it is
  fragile on a rebase, and inline directives already produce the same effect for free: a new file has no directives,
  so every rule is `error` in it from the first line.

**Cost, stated plainly:** 194 comment lines land in 59 production files. That is the price of a debt record that
cannot be regenerated and that deletes itself as it is paid. Q2 offers the cheaper alternative and recommends against it.

---

## 7. Wiring

**`package.json`** gains one script: `"lint": "node lint.mjs"` (9 scripts; floor is 6, P2). No script gains the
string `tsc`. `npm run build` is **unchanged** — folding lint into it charges the Docker image build a third time
(P4) for a gate CI has already run.

**`lint.mjs`** (frontend root, ~30 lines) uses ESLint's Node API once: lint `src` and `eslint-rules`, **assert a
floor on the number of files linted**, print one witness line, print the stylish report, exit non-zero on any error.
A bare `eslint .` exits 0 when its `ignores` accidentally match everything — the same failure `pom.xml:299-315`
records for `passWithNoTests`, and the same answer HD-265 chose for the backend: a guard shaped as a **program**,
because the thing it guards against would swallow a guard shaped as a test. Witness line:
`[eslint] 142 files linted (floor 120) · 0 errors · 194 suppressed (HD-177)`.

**`pom.xml`** gains a fifth execution, declared **before** `npm-test`:

```xml
<execution>
  <id>npm-lint</id>
  <goals><goal>npm</goal></goals>
  <phase>test</phase>
  <configuration><arguments>run lint</arguments></configuration>
</execution>
```

`test`, not `generate-resources`, for HD-242's reason exactly (P3/P4): it is one of the two bindings that inherit
`-DskipTests` — `AbstractFrontendMojo.isTestingPhase()` accepts `test` and `integration-test`, read from the 1.15.1
bytecode — so the image build does not pay and a `-DskipTests` local package does not either. (The plugin binds that
flag to `${skipTests}` only: `-Dmaven.test.skip=true` silences surefire and would **not** silence these.) The
plugin-level `<skip>${frontend.skip}</skip>` already covers it, so `-Dfrontend.skip=true` switches every execution of
the plugin off together rather than all-but-one plus a flag someone must remember. Declaration order puts lint (seconds) before vitest (45–70 s),
so the cheaper gate reports first — though surefire, being the default-lifecycle binding, still runs before both
(`pom.xml:503-506`). `pom.xml` is the ops area → **arms `ops_witness`** and **`dc-cloud-guard` is not armed**
(no property, no profile, no env var).

**Windows.** The change does not alter the build path — `npm ci` already runs at `generate-resources` on every
non-skipped build — but the builder will run `npm install` to add the dev dependencies and update
`package-lock.json`, and `npm ci` deletes `node_modules` first, so **the Vite dev server must be stopped** or the
build dies on `EPERM` over `lightningcss.win32-x64-msvc.node`. `-Dfrontend.skip=true` avoids it for backend loops.

**`.gitignore`** gains `src/main/frontend/.eslintcache` **only if** a cache is used; the default is **no cache**
(a cache turns a clean run into a claim about a previous run).

---

## 8. Category members (for the hook's `category` block)

```
{"rule": "every mechanical trap in frontend-builder.md is executed by exactly one mechanism whose failure text is the trap",
 "members": ["R1 hamstrack/no-shadowed-max-width",
             "R2 hamstrack/no-numeric-font-size",
             "R3 hamstrack/overlay-has-dialog-semantics",
             "R4 the Hex brand (tsc -b) + palette.contrast.test.ts TOKEN_INTO_DERIVED entry 2",
             "R5 hamstrack/no-role-name-gate"],
 "sealedBy": "src/lint/eslintRules.test.ts"}
```

Second category, **the doors the gate must reach**, enumerated so the builder starts from a list rather than from a
command: `npm run lint` · `npm run build` (unchanged, recorded as a deliberate non-member) · `mvnw package` ·
`mvnw verify` · `mvnw test` · CI `./mvnw -B verify` · `Dockerfile`'s `package -DskipTests` (non-member by design) ·
`-Dfrontend.skip=true` · `-DskipTests`. Each gets a row in the builder's report saying whether lint ran, and why.

Third category, **the prose that must move**: `frontend-builder.md:17-22` (five bullets → pointers);
`CLAUDE.md` § Gotchas gains no new line but the `npx eslint .` mention at `frontend-builder.md:36` becomes
`npm run lint`; `docs/project-state.md` gains the tooling line; `palette.contrast.test.ts:387-424` javadoc loses the
half the type now holds; `palette.contrast.test.ts:34-38` states "this suite runs on no automated path", which has
been **false since HD-242** (M, `pom.xml:327-332`) — fix it in passing.

---

## 9. Acceptance criteria — over the category

- **AC1 — every rule in the set has a watched red.** For each of the four ESLint rules, a `RuleTester` case in
  `src/lint/eslintRules.test.ts` with ≥ 1 `invalid` **and** ≥ 2 `valid` fixtures, where the `valid` fixtures are the
  measured legitimate neighbours from §4 verbatim, not invented ones: R1 gets a `max-w-xl` **inside a comment** and
  `max-w-full`; R3 gets `AppShell.tsx:59`'s `position: 'fixed'` without `inset` and `WorkspaceMembersModal`'s
  correct overlay; R5 gets `user?.systemRole === 'ADMIN'` and `r.key === 'MEMBER'`. A rule whose `valid` set is
  empty has not been shown to discriminate. **Plus** one end-to-end plant per rule: the offending line added to a
  real file, `npm run lint` output pasted, reverted.
- **AC2 — a rule with population 0 says so.** For R1, R4 and R5 the report states the day-one count is 0 **and**
  quotes the plant, because a rule that can never fire on the tree has no other evidence.
- **AC3 — every mechanism in the set is reachable from `mvnw verify`.** Demonstrated by the door table of §8: a
  plant is red under `./mvnw -B verify`; green under `-Dfrontend.skip=true`; green under `-DskipTests`; and the
  image-build command `mvnw clean package -DskipTests` does not run it. Four pasted outcomes, not one.
- **AC4 — no gate in the set is green for free.** `lint.mjs` fails when the file floor is not met (drill: point it
  at an empty directory, paste the failure); the debt test fails when its scan reads fewer than 120 files (same
  drill); `vitest run` still exits 1 on an empty match (`pom.xml:299-315`, unchanged) and `passWithNoTests` appears
  nowhere.
- **AC5 — the debt record can only shrink, and it has a clock.** Raising `DECLARED` fails no test — it is a hand
  edit — so the criterion is behavioural: adding one new `fontSize: 12` **without** a directive is red; adding it
  **with** one is red at the count assertion; deleting a fixed site's directive without deleting the site is red at
  `reportUnusedDisableDirectives`. Three pasted reds. Additionally: `DECLARED_AS_OF = 2026-09-11`, and the date test
  is proven by moving the constant back 91 days and pasting the failure.
- **AC6 — the suppression escape hatch is closed from outside.** Creating `eslint-suppressions.json`, adding
  `--suppress-all` to a script, and setting one rule to `'warn'` in the flat config each turn the debt test red.
  Three pasted reds. (This is the criterion that distinguishes this design from a baseline; it is not optional.)
- **AC7 — every trap that has a mechanism has exactly one.** After the change: grep for `TOKEN_INTO_DERIVED`
  returns one entry, not two; no trap in §4 has both a lint rule and a regex scan; `frontend-builder.md:18-22` is
  five pointers naming the rule or the type, and `AgentChecklistFreshnessTest` is green in the same run.
- **AC8 — the type brand is proven by a compile failure, not by a lint pass.** `<Badge color="var(--color-brand)" />`
  and the indirect form `const c = 'var(--color-brand)'; <Badge color={c} />` **and** the lookup-table form
  `<Badge color={TONE[x]} />` each produce a `tsc -b` error; all three pasted; `npm run build` green after revert.
  The third is the case `palette.contrast.test.ts:404-411` records as undetectable — this criterion is the whole
  reason R4 is a type.
- **AC9 — build-time cost measured and recorded, three ways, in the shape `pom.xml:282-297` uses:** `npm run lint`
  alone (two runs, same tree — the spread is the point); the execution alone through Maven
  (`mvnw frontend:npm@npm-lint`); and the lifecycle delta (`mvnw test -DskipTests` against a run that includes it).
  Recorded in a comment on the new execution, with the date and the machine.
- **AC10 — suite counts.** The unfiltered run reports the frontend `Test Files … / Tests …` line with counts equal
  to previous + the new files; the backend `[test-tree]` line lists no absent classes.

---

## 10. Observability contract

A build-time rule set has a build-time witness. Every row's drill is an AC.

| Failure mode | Witness (what a human actually sees) | Drill |
|---|---|---|
| A rule fires on a real change | Maven log under `--- frontend:1.15.1:npm (npm-lint) ---`: the stylish report (`file:line:col  error  message  hamstrack/<rule>`) then `[eslint] … · N errors`; Maven ends with `BUILD FAILURE` naming `npm-lint`. Locally: `npm run lint`, same text. | AC1 plants |
| A rule fires **on a correct site** (over-firing) | The `valid` fixtures — the only witness that exists for this, and the reason §4 measured the legitimate neighbours | AC1 |
| The lint step runs and asserts nothing (`ignores` swallowed the tree, config failed to load) | `[eslint] N files linted (floor 120)` — the count line, red when under floor. This is the `passWithNoTests` failure mode, and the guard is a program, not a test | AC4 |
| The debt is quietly regenerated / disarmed | `src/lint/debt.test.ts` red, message names the ticket and the forbidden mechanism; runs inside `mvnw verify` via `npm-test` | AC6 |
| The debt stops shrinking | The 90-day clock in the same test; message says "fix sites and lower `DECLARED`, or restate `DECLARED_AS_OF` with a reason" | AC5 |
| A `Hex` violation | `tsc -b` error in `npm-build` at **generate-resources** — i.e. *earlier* than lint, and in the image build too | AC8 |
| The lint execution is silently dropped from the pom | **None today.** Q3: the debt test can assert `pom.xml` contains an execution running `run lint` bound to `test`, the same way `VacuousVerificationRulesTest` asserts the vitest include line. Recommended. |
| A rule's message goes stale (names a fix that no longer exists) | **None.** Accepted: messages are ≤ 3 lines and name an action, and AC1's plant pastes them. |

Nothing here reaches production; there is **no metric, alert or log line** in the running system, and no delivery
drill applies. That is stated rather than omitted so a reviewer does not go looking for one.

---

## 11. Open questions (each with a recommended default)

- **Q1 — stock rule sets?** Default **no**: custom rules only, as ticketed. But `react-hooks/rules-of-hooks` and
  `exhaustive-deps` are the highest-value frontend rules in existence and this SPA has never run them. Cheap
  compromise, recommended: the builder enables `eslint-plugin-react-hooks` for **one throwaway run**, records the
  violation count as a number in the report, reverts, and that number becomes the premise of a follow-up ticket.
  One extra run; no scope change.
- **Q2 — R2's debt as 194 inline directives (default) vs a 59-file `overrides` block in the flat config?** The
  block is 59 lines instead of 194 and is quieter in the source; it also makes a **new** violation in an
  already-dirty file invisible, and it cannot self-expire. Default: **inline directives**. Take the block only if
  the owner judges the comment noise unacceptable, and then AC5's first red is lost — say so in the ticket.
- **Q3 — should the debt test assert the pom binding?** Default **yes** (one regex, closes the last silent-drop
  path in §10).
- **Q4 — do SVG chart labels (28 of the 194) get a permanent exemption?** Default **no**: an axis label is read,
  and `rem` works in SVG. If the owner wants the exemption it is a rule option, not a suppression, and it drops
  `DECLARED` to 166.
- **Q5 — rule sources in `eslint-rules/*.js` + a hand-written `index.d.ts` (default) vs `eslint.config.ts` with
  `jiti`?** Default the former: no extra dependency, and `tsconfig.node.json:19` includes only the two Vite
  configs, so nothing outside `src` enters `tsc -b` (M).
- **Q6 — `Hex` on `types.ts` colour fields (default) vs only on the three component props?** The narrow version is
  a smaller diff and still catches the literal, but the brand cannot *flow*, so the lookup-table case (AC8's third
  plant) stays undetected — which is the case worth catching. Default: brand the sources.

---

## 12. ADR

**None.** A dev dependency, a config and four rules are reversible in one commit. The `Hex` brand is an
*implementation* of a decision already recorded — ADR-0027 (stored colour is identity, not ink) and **ADR-0029**
(stylesheet colour decided at design time) — so it gets a line appended to ADR-0029 under "how it is enforced",
not a new number.

---

## 13. Builder's first report — highest-risk assumptions, in order

1. **The `Hex` brand's blast radius (R4).** Unmeasurable without a compiler run. Brand `types.ts`, run `tsc -b`,
   **paste the error count before writing a single cast**, and stop if it is large — Q6's narrow variant is the
   fallback and the ticket can ship on it. This is the one item that can turn a tooling ticket into a refactor.
2. **`typescript-eslint` without `projectService` parses `.tsx` at all four rules' node types.** Probe: one rule,
   one file, `--debug`, quote the report.
3. **ESLint's current major and its flat-config surface** (`linterOptions.reportUnusedDisableDirectives`, the Node
   API's file list, whether bulk suppressions still spell `--suppress-all`). Resolve the versions, record them, do
   not trust this document's spelling — it is **I**, written from a May-2026 memory against a September-2026
   registry.
4. **`frontend-maven-plugin` 1.15.1 honours `-DskipTests` for a *second* `test`-bound execution** (P3 read it for
   `npm-test`; that it generalises is **I**). Probe: `mvnw test -DskipTests`, quote the `Skipping execution` line
   for `npm-lint`.
5. **R3's descendant walk.** Confirm on all six real overlays that the panel is a descendant in the same JSX
   expression before writing the rule; if any is behind a prop or a portal call, the rule shrinks to "the three
   defects" and the others need an explicit allow-list entry with a reason.
