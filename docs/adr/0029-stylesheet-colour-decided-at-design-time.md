# ADR-0029: A colour from the stylesheet is decided at design time and checked on the build; a colour from the database is computed at render time

Record date: 2026-09-04
Status: Accepted
Source: `docs/design/palette-text-contrast-proposal.md` §7.3, §7.4, §7.5 (HD-175);
ADR-0027 — the rule for a colour that came from the database;
the existing artefacts — `src/main/frontend/src/colour.ts`, `src/main/frontend/src/colour.test.ts`,
`src/main/frontend/src/components/ui.contrast.test.tsx`;
HD-242 — `npm test` runs on no automated path at all

## Context

After HD-176 the SPA has a working contrast primitive: `colour.ts` can measure a ratio, read a
token from the live document and **darken a hue to the threshold while preserving chromaticity**.
HD-175 brings a second population of unreadable colours — this time not from the database but from the
palette itself: `--color-text-muted` 2.58 against white, `--color-rail-muted` 3.34 against the rail
menu, white ink on the `--color-brand` fill 3.03, and 162 places where a semantic token
(`--color-warning` 2.35, `--color-success` 2.54, `--color-brand` 3.03, `--color-error` 3.76) is used as
a text colour.

The obvious and tempting solution is to extend the rule that is already written: pass token values
through `inkOn(token, surface)` at render time. One rule for the whole product, zero new hexes, a
guarantee by construction. That is exactly what the next contributor will propose, and that is exactly
why the fork has to be written down.

The difference between the two populations is not in the arithmetic but in **who can look at them and
when**. A status colour is chosen by a workspace admin in production, at a moment when there will be no
review at all — so the only place it can be fixed is the render. A token value sits in
`index.css`, lands in the diff, goes through review and lives until the next commit — everything about
it is known in advance.

## Decision

**A colour that came from the database is computed at render time, because nobody can look at it in
advance. A colour that came from the stylesheet is decided at design time and asserted by a test on the
build, because anyone can look at it.**

- **`colour.ts` is the shared arithmetic of both tickets.** `contrastRatio`, `parseColour`,
  `relativeLuminance`, `token`, `SURFACE`. Both import it, neither forks it. Two contrast
  implementations are two answers, and in a disagreement the one that wins is the one nobody tests.
- **HD-176 (ADR-0027) owns the derived functions** `inkOn` / `fillOf` / `ringOn` / `tintOf` /
  `onSolid` and their call sites. Its tests assert **properties of the output** and the parity of
  `TOKEN_FALLBACK` with `index.css`; they do not start asserting a token's design value.
- **HD-175 owns the declared values and `palette.contrast.test.ts`.** The test parses
  `index.css` as text, maps each token to a role (`ink` / `fill` / `surface` /
  `neutral-nontext`) and to the list of surfaces it is allowed on, and asserts the ratios. **It
  never calls `inkOn` on a token.**
- **Two tripwires, both in the form the project has already adopted:** every `--color-*` token
  declared in `index.css` must be present in the role map (a new token cannot be added without
  classifying it), and the number of classified tokens has a floor (an emptied map does not pass
  everything indiscriminately).
- **The static check does not replace the DOM check, and vice versa.** The test proves that no
  declared value is illegal on the surface declared for it. It does **not** see which token is
  actually applied to an element, the composited alpha of the ancestors, gradients, the size and weight
  of a particular element, or a hardcoded hex inside a component. That is proved by the browser
  audit (`npm run audit:contrast`), which likewise imports the arithmetic from `colour.ts` rather than
  writing its own.
- **A rule for the reviewer, so the seam does not grow over:** *if a hex can be found by grepping the
  repository, it belongs to the stylesheet; if it can be found only by a query to the database, it
  belongs to the renderer.*

### How the seam is enforced (added by HD-300, 2026-09-11)

The reviewer rule above is now backed by a **type**, not only by a scan. `Hex` (a template-literal
`#`-prefixed string, `src/types.ts`) is the declared type of every stored hue **in both directions**:

* **read** — `IssueType.color`, `Status.color`, `Priority.color`, `LabelRef.color`,
  `FieldConfig.options[].color`, and the props whose contract is "a hue from the database": `Badge`,
  `StatusBadge`, `ParentChip`, plus `ColorField` and `token()`;
* **write** — `UpsertCatalogPayload.color`, `UpsertLabelPayload.color` and `labelsApi.create`'s
  inline payload (`api.ts`). HD-300's first round typed only the read side, and the tests gate found
  the consequence: a `var(--…)` posted through a write door was stored and came back typed `Hex`, so
  the type told the truth about everything except how the value got there. Typing the three write
  sites cost **no** call-site change — `ColorField` already narrows at the one place a hue enters the
  app, `e.target.value as Hex` on an `<input type="color">`, whose value is `#rrggbb` by
  specification.

`<Badge color="var(--color-brand)" />` is therefore a `tsc -b` error, so is the same token arriving
through a `const` or a lookup table, and so is one handed to `labelsApi.update` (verified by planting
each shape and reading the compiler's refusal, 2026-09-11).

That last case is why this is a type and not a lint rule. `palette.contrast.test.ts` recorded, **by
measurement**, that a token parked in a lookup table was invisible to its regex — "the lookup-table
version was reinstated verbatim and the whole suite stayed green" — and that is how three of the four
badges in the original bug were actually written. A regex over source text cannot follow a value; a
type can. The scan's `color`-prop entry was **deleted** in the same change rather than kept beside the
type, because two mechanisms for one trap means the weaker one is the one people read. What remains in
that scan is the half no type can express: a token as the first argument of
`inkOn`/`tintOf`/`fillOf`/`ringOn`/`onSolid`, whose signatures take `unknown` on purpose because the
value arrives from JSONB.

`Hex` is lexical, not semantic — `'#nothex'` satisfies it. The parse at render time is still the
check for a malformed hex; what the type removes is the *category* error of a stylesheet colour
crossing into the renderer, which is what this ADR is about.

## Consequences

+ The palette stays **surveyable**: a colour's value is visible in the diff, not derived by an
  algorithm. A screenshot depends on a hex, not on a function.
+ A mistake in the palette goes red **on the development loop**, with no browser, no server and no
  populated database — where the mistake is actually made.
+ The ADR-0027 guarantee stays exactly what it was: above the threshold `inkOn` is the identity, and a
  workspace with tuned colours still sees its own render byte for byte.
+ A new token cannot be added silently: the tripwire demands that it be classified.
− **There are two guard mechanisms, and the seam between them has to be known.** It rests on one
  sentence above and on a reference from both tests.
+ **The vitest half now runs on the merge (HD-242, closed).** `./mvnw -B verify` is still CI's only
  command, but the `frontend-maven-plugin` executions now include `npm-test` (and, since **HD-300**,
  `npm-lint`), both bound to the `test` phase. The bullet that stood here said the opposite and was
  true when written; it stayed for the reason this ADR keeps repeating — *nothing about a sentence
  changes when a build file does*. What holds the replacement is `src/lint/debt.test.ts`, which reads
  `pom.xml` and goes red if either execution leaves it.
− **The browser audit still runs on nobody's merge.** `npm run audit:contrast` needs the system
  Chrome and a populated instance, so it protects the reviewer and the local run only.
− The DOM audit requires the system Chrome (`puppeteer-core`, `channel: 'chrome'`), a running
  instance and populated pages; it has floors on the number of elements found, or a green run over an
  empty backlog reads as clean.

## Alternatives

- **Pass token values through `inkOn` at render time** — rejected, even though it is one rule for
  everything. It hides a palette mistake behind a computation: an illegal value stops being visible and
  becomes "fixed on the fly", and with different results on different surfaces. The design
  system leaves review — the diff keeps a hex that nobody draws, and the drawn hex is nowhere. And it
  is extra work on every frame for values that are known on the build.
- **Two independent contrast implementations, one per ticket** — rejected: two answers to one
  question, and in a disagreement the one that wins is the one with no tests. The audit imports
  `colour.ts`.
- **Rely on the browser audit alone** — rejected: it needs a browser, a server and a populated
  database, so it does not run on the loop where the mistake is made. It catches what the static check
  cannot see; it does not replace it.
- **Rely on the static test alone** — rejected symmetrically: it sees neither the
  composited alpha, nor gradients, nor the size of an element, nor a hardcoded hex. A green `npm test`
  must not be read as a clean audit, and the spec says so out loud.
- **Move the token classification into `index.css` comments** — rejected: a comment is not an
  assertion, you cannot rig a tripwire on it, and it diverges from the code silently. The
  classification lives in the test as data.
