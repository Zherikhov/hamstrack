# Palette text contrast — the third ink level does not fit, and the fix is in the tokens (HD-175)

**Status:** proposal / design review. **Date:** 2026-09-04. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness).
**Migration:** none. **API change:** none. **Backend change:** none.
**Related:** HD-176 / ADR-0027 (`src/main/frontend/src/colour.ts` — colour that comes from *data*;
this ticket is colour that comes from *the stylesheet*, and §7 states the seam), HD-242 (`npm test`
runs on no CI path — the reason §7.4 exists), HD-5 / HD-140 (the chart ramp and the aria-hidden
chart-over-a-table rule this spec verifies rather than assumes), HD-58 (the landing page, which
already made the white-on-teal decision the product UI did not).
**Touches:** `src/main/frontend/src/index.css` (token values — the only place a value changes),
`src/main/frontend/src/components/ui.tsx` (`Button` `primary`/`danger`), `DESIGN.md` (Color section,
Data Visualisation section, Decisions Log), a new audit harness under
`src/main/frontend/audit/`, a new `src/main/frontend/src/palette.contrast.test.ts`,
`package.json` (two scripts + one devDependency).
**No new configuration property. No new environment variable. No profile gating.**

---

## 0. The recommendation, first

Four things, in the order they must be done.

1. **Re-run the audit before designing the tail.** The harness that produced the ticket's numbers is
   **not in this repository** (verified: no `puppeteer`, no `playwright`, no audit script anywhere in
   the tree, nothing in `package.json`). A measurement that cannot be repeated is not a gate, and
   AC#1 asks for a re-run. **Task one is committing the harness**, not fixing a hex. Until it exists,
   the 37 unlisted combinations are unknown and the "480 failures" number cannot be reproduced by
   anyone but its author.

2. **Beacon has two ink levels below primary, not three.** This is not a preference. On the darkest
   light surface the product paints on (`--color-surface-2` `#EEF1F6`), *every* colour that clears
   4.5:1 has relative luminance ≤ 0.1561. `--color-text-secondary` `#5B6676` already sits at 0.1304.
   The entire remaining band for a lighter-but-still-legal third level is 0.1304 → 0.1561, and the
   contrast **between those two inks is 1.13:1** — a difference no reader can see. The lightest
   hue-preserving value `--color-text-muted` can take and still pass everywhere is `#676E79`
   (5.14 / 4.71 / 4.54 on card / canvas / raised), and it is 1.13:1 from secondary. So the third
   level cannot survive as a *colour*; it survives as size, weight and position, which the design
   already uses. **Remap `--color-text-muted` to `#5B6676`** — the same value as secondary — rather
   than to a near-identical-but-different hex that every future contributor will try to preserve.

3. **White on a saturated product fill is the wrong ink, on both surfaces.** `DESIGN.md` already
   declares the correct rule for text over a solid fill — *black or white, whichever measures
   higher* — and scopes it to colour that comes from data. Unscope it. The landing page's `#04211F`
   is the brand's existing near-black and measures 5.58 on `--color-brand`; white measures 3.03.
   Declare it once as `--color-on-brand` and use it in both places (AC#3).

4. **Do not touch the chart ramp; narrow the sentence.** Verified in code, not assumed: every chart
   is `aria-hidden="true"` over a real `<table>` carrying the same numbers, the legend swatch is
   `aria-hidden` too, and the legend *label* is `--color-text-secondary`. So the series hue is never
   text and never the sole carrier of meaning. `--color-chart-5` (2.31) and `--color-chart-context`
   (2.96) stay exactly as they are; the false half of the `DESIGN.md` sentence — "darkened where
   needed for contrast on the white card surface" — is replaced with what is true (§5.4).

**The scope finding that changes the size of the job:** the ticket's top five rows are five
*combinations*, not five *problems*, and the tail of 37 is not small change. The failures fall into
**four families**, and the fourth is invisible in the ticket's table:

| # | family | cause | fix |
|---|---|---|---|
| 1 | `--color-text-muted` as ink on a light surface | one token, ~195 shown | one value |
| 2 | white ink on a saturated token fill | `Button` `primary` + `danger`, one component | one component |
| 3 | `--color-rail-muted` on `--color-ink` / `--color-ink-menu` | one token, 4 sites in `NavRail.tsx` | one value |
| 4 | **a semantic token used as ink** (`--color-warning` 2.35, `--color-success` 2.54, `--color-brand` 3.03, `--color-error` 3.76 — all on white) | **162 occurrences across 54 files** | four new sibling values |

Family 4 is why the tail must be enumerated before the work is sized. It is also why the answer is
still *tokens*: 162 sites, four values.

**Highest-risk assumption, flagged for the owner:** **that the owner accepts the app looking
measurably heavier.** Roughly two hundred elements of light grey become mid-slate, and that cost is
imposed by the 4.5:1 threshold on a hoverable row, not by the token structure — the alternative
value (`#676E79`) is 1.13:1 away from the one recommended, i.e. it looks the same. If the weight
change is rejected, the only lever left is *using muted for less small text*, which is an IA edit
across 83 files and does not remove the requirement from the text that remains. Everything else in
this document is arithmetic.

**Second flag, per the instruction not to bury it:** this spec **changes declared values of the
Beacon palette**, which `CLAUDE.md` says requires explicit owner approval. The exact list of moving
values is §5.6 — five remapped, six added, nothing renamed.

---

## 1. Problem & goal

Text in the product is unreadable at the WCAG 1.4.3 threshold in 480 of 949 sampled rendered
elements, and the cause is concentrated in a handful of declared palette values rather than in
component code. A workspace member reading a backlog is reading 12px `#98A2B3` on white at 2.58:1 —
under half the required ratio, and under even the 3:1 large-text floor, so no size or weight rescues
it. Success is: every text element on the sampled pages clears its applicable threshold, the fix
lives in `index.css` where one value repaints every consumer, and there is a repeatable artefact
that proves it and keeps proving it.

The secondary goal is structural: after this ticket, "is this colour legible?" must be a question
with a mechanical answer, checkable without a browser and without a reviewer's eye.

---

## 2. What is actually broken, measured

All ratios below were computed independently with the WCAG relative-luminance formula and
cross-checked against the ten values the ticket measured in a real browser; **all ten agree to two
decimal places** (muted/white 2.58, muted/`#EEF1F6` 2.27, muted/`#F3F5F9` 2.36, secondary/white
5.82, secondary/canvas 5.33, white-on-brand 3.03, `#04211F`-on-brand 5.58, rail-muted/ink 3.86,
chart-5/white 2.31, chart-context/white 2.96). The derived candidates in §5 are computed the same
way and are therefore trustworthy to the same precision — but every one of them is still subject to
the 8-bit quantisation trap `colour.ts` documents (`#7B7B00` measures 4.49 where `#7A7A00` measures
4.55), so **the builder re-measures after encoding** and the harness in §7 is what proves it.

### 2.1 The three light surfaces, and the one that decides everything

| surface | token | relative luminance | max ink luminance for 4.5:1 |
|---|---|---|---|
| card | `--color-card` `#FFFFFF` | 1.0000 | 0.1833 |
| canvas | `--color-surface` `#F3F5F9` | 0.9120 | 0.1638 |
| raised / hover | `--color-surface-2` `#EEF1F6` | 0.8774 | **0.1561** |

A row that tints on hover moves between these surfaces under the same text. HD-176 already settled
this as `SURFACE.row = darkest(card, surface, surface-2)`; a *token* is used on all three, so a
token is bound by the **darkest**, i.e. 0.1561. This single number is the reason §5.1 concludes what
it does, and it is why "make muted pass on white" is not a viable middle option — the value that
does (`#707784`, 4.50 on white) measures **4.13 on canvas and 3.98 on raised**, so it fails on
exactly the surfaces the ticket already caught it failing on.

### 2.2 Family 1 — `--color-text-muted`

| | card | canvas | raised |
|---|---|---|---|
| `--color-text-muted` `#98A2B3` (L 0.3577) | **2.58** | **2.36** | **2.27** |
| `--color-text-secondary` `#5B6676` (L 0.1304) | 5.82 | 5.33 | 5.14 |
| `--color-text` `#16202E` (L 0.0140) | 16.40 | — | — |

428 occurrences across 83 files (the ticket counts 378 *text* sites; the difference is `index.css`
declarations, tests, and non-text uses). **It also fails 1.4.11 at 3:1**, which the ticket does not
mention: the backlog row's `GripVertical` handle, the `Select` chevron and the filter icon are drawn
in it at 2.58 and are not decorative — they identify a control. Fixing the token fixes those for
free; fixing only the text would leave them.

**Backlog is one component repeated, not 375 problems.** `BacklogPage.tsx`'s row (line ~1199) paints
the issue key, the empty-assignee dash and the grip in muted, plus a `--color-warning` overdue date
and a `--color-text-secondary` due date; the sprint/section headers add a few more. 375 rendered
failures on that page come from roughly a dozen source expressions multiplied by row count. The
ticket's per-page distribution is therefore a *density* map, not a work estimate — which is exactly
why the fix belongs in tokens and why the audit must attribute failures to source, not just count
them (§7.2).

### 2.3 Family 2 — white ink on a saturated token fill

`components/ui.tsx` `Button`: `primary: 'text-white'` over `background: var(--color-brand)`, and
`danger: 'text-white'` over `var(--color-error)`.

| fill | white ink | `#04211F` ink | black ink |
|---|---|---|---|
| `--color-brand` `#0EA5A4` | **3.03** | 5.58 | 6.94 |
| `--color-error` `#F04438` | **3.76** | 4.50 (fails by 0.003) | 5.59 |

`--color-brand-hover` `#0C9188` is **declared and never used** (one hit, its own declaration), so
there is no hover state to fix — but see §6.4, because that is a trap for whoever adds one: white on
it is 3.87 and `#04211F` on it is **4.36**, both failing. The rail's own "New issue" button already
uses `#04211F` (`NavRail.tsx:204`) and is correct today; it is the content-column button that is
wrong.

### 2.4 Family 3 — the dark rail

The rail is **not** the same problem as the white card and must not get the same move. Two dark
surfaces exist, and a rail token lands on both:

| ink | on `--color-ink` `#101828` (L 0.00916) | on `--color-ink-menu` `#1C2536` (L 0.01836) |
|---|---|---|
| `#FFFFFF` | 17.75 | 15.36 |
| `--color-rail-active` `#5EEAD4` | 12.00 | 10.38 |
| `--color-rail-text` `#9AA5B5` | 7.12 | 6.16 |
| `--color-rail-muted` `#6B7688` | **3.86** | **3.34** |

The binding surface here is the *lightest* dark one (`#1C2536`, where the user menu's email line
lives, `NavRail.tsx:320`) — the mirror image of the light-surface rule. A hue-preserving rail-muted
that clears 4.5:1 on **both** is `#7F8CA1` (5.21 / 4.51), and it lands **1.37:1** from
`--color-rail-text` — the same collapse, one surface later.

### 2.5 Family 4 — a semantic token used as ink (the tail)

Against white, as text:

| token | value | ratio | verdict |
|---|---|---|---|
| `--color-warning` / `--color-pending` | `#F79009` | **2.35** | fail |
| `--color-success` | `#12B981` | **2.54** | fail |
| `--color-brand` | `#0EA5A4` | **3.03** | fail (also every teal link in `.markdown-body` / `.legal-prose`) |
| `--color-error` | `#F04438` | **3.76** | fail |
| `--color-sandbox` / `--color-info` | `#667085` | 4.97 card / 4.56 canvas / **4.39 raised** | fails only on a hovered row |

`color: 'var(--color-{error,success,warning,brand})'` appears **162 times across 54 files**. This is
the bulk of the 37 unlisted combinations and it is invisible in the ticket's top-five table.
`--color-sandbox` is the nastiest of them: it passes everywhere a screenshot was taken and fails
only in the hover state, which is precisely the failure mode HD-176's `SURFACE.row` exists for.

---

## 3. Scope

**In scope**

- The declared values in `src/main/frontend/src/index.css` — the `@theme` token block and the
  hardcoded hexes in the landing-page and prose rules below it.
- `components/ui.tsx` `Button` (`primary`, `danger`) — the one component whose failure is not a
  token value.
- `DESIGN.md`: the Color section (text levels, the on-fill ink rule), the Data Visualisation
  section (the narrowed ramp claim), and one Decisions Log row per decision.
- The audit harness, the static palette test, and their wiring into `package.json`.
- Per-site overrides, *only* where the re-run audit proves a token fix insufficient, each justified
  in a comment at the site.

**Out of scope, explicitly**

- **Colour that comes from the database** — HD-176 / ADR-0027 owns it, it shipped, and no CSS change
  reaches it. §7.5 states the seam so the two guard sets do not grow into each other.
- **The chart ramp's hexes.** `--color-chart-1..5` and `--color-chart-context` do not move (§5.4).
  `--color-chart-axis` does, because it is a neutral and not part of the Okabe–Ito set.
- **WCAG 1.4.11 for borders and control boundaries.** `--color-border` `#E8EBF1` measures **1.19:1**
  on white and `--color-border-2` is not much better; an input's own outline is arguably a "visual
  boundary required to identify a control". That is a real finding, it is a *different* success
  criterion with a different threshold, and folding it in would change every card, table and input
  in the product. **File it as a follow-up ticket** rather than smuggling it in here. The one
  exception already in scope: icons drawn in `--color-text-muted` (§2.2), because they are fixed
  incidentally by a change that has to happen anyway.
- **Dark mode.** Not themed app-wide; the rail is the only permanently-dark surface and it is
  handled as itself (§5.3).
- **The exported chart PNG.** It carries the chart out of the page and away from its table, so the
  aria-hidden argument does not travel with it — but WCAG binds the page, the PNG's own axis labels
  improve for free via `--color-chart-axis`, and re-deciding the ramp for an export is a separate
  argument. Noted, not taken.
- Any backend, API, schema or configuration change. There are none.

**Non-goals**

- Reaching WCAG AAA (7:1). The threshold is AA, it is not configurable, and ADR-0027 already
  recorded why an operator-lowerable accessibility floor is a promise the product cannot keep.
- Preserving the *current* light-grey look. It is not preservable at 4.5:1 (§0 flag).

---

## 4. Actors & permissions

None. No endpoint, no role, no workspace scoping, no tenancy surface. This is a stylesheet and a
build-time test. It is stated explicitly so the pipeline's tenancy gate can be marked `n/a` with a
reason rather than skipped.

The affected *readers* are every authenticated user on every page, plus every anonymous visitor on
the landing and legal pages (`.lp-*`, `.legal-prose` — both consume the same tokens, and
`.lp-licence` / `.lp-datanote` are `--color-text-muted` at 13.5px, so they are in family 1).

---

## 5. The decisions

### 5.1 Three text levels become two — and the third becomes size and weight

**Decision: `--color-text-muted` is remapped to `#5B6676`, the same value as
`--color-text-secondary`.** The name stays (the Beacon token strategy is names-stable /
values-remapped, and that is the property this ticket exploits — one value repaints 428 sites for
free).

Why not a distinct third value:

| candidate | card | canvas | raised | vs `#5B6676` |
|---|---|---|---|---|
| `#98A2B3` (today) | 2.58 | 2.36 | 2.27 | 2.26:1 — a real step, and illegal |
| `#707784` (lightest that passes on **white only**) | 4.50 | **4.13** | **3.98** | 1.41:1 — fails two surfaces |
| `#676E79` (lightest that passes on **all three**) | 5.14 | 4.71 | 4.54 | **1.13:1** — legal and invisible |
| `#5B6676` (= secondary) | 5.82 | 5.33 | 5.14 | 1.00:1 — honest |

A 1.13:1 difference between two greys is not a hierarchy level; it is two tokens that look identical
and must both be re-measured on every future theme change. Worse, `#676E79` sits 0.04 above the
cliff on the raised surface — one hover-fill tweak from failing again.

**What carries the third level instead:** size (12–12.5px meta vs 14px body), weight (500/600 vs
400), position (a meta line under a title, an eyebrow above a section), and case+tracking for
eyebrows. The design already uses all four; the colour was the fourth channel and the only illegal
one. This is the same reasoning `DESIGN.md` already applies to charts — *colour is never the only
encoding*.

**The disabled/placeholder case does not need a token.** WCAG 1.4.3 exempts inactive UI components.
`components/ui.tsx` already renders disabled controls with `opacity: 0.6` on the passing ink (see
`Select`, `buttonBase`'s `disabled:opacity-50`), which composites to roughly `#9DA3AD` — visually
the old muted, legally exempt, and requiring no new declared value. **Do not introduce a
`--color-text-disabled`**: it would be a loophole with 428 candidate sites and no mechanical way to
police which uses are genuinely exempt.

### 5.2 White on a product fill → the rule `DESIGN.md` already declares

**Decision: unscope the existing rule.** `DESIGN.md` says, of colour from data: *"Text over a solid
fill of the hue is black or white, whichever measures higher (floor 4.58:1 for any colour that
exists)."* That is a property of solid fills, not a property of the database. Applied to tokens:

- **`--color-brand`:** declare **`--color-on-brand: #04211F`** — the value the landing page already
  uses in five places (`.lp-btn-primary`, `.lp-logo`, `.lp-rlogo`, `.lp-rnew`, and the rail's New
  issue button at `NavRail.tsx:204`). 5.58:1. `Button variant="primary"` drops `text-white` and
  takes `styles.color = 'var(--color-on-brand)'`. **One decision, one literal, both surfaces**
  (AC#3) — and it removes five hardcoded `#04211f` literals in the process.
- **`--color-error` (the `danger` button):** black measures 5.59 and `#04211F` measures 4.50 (a
  0.003 miss — do not use it). **Recommended: keep white ink and darken the button's fill to
  `--color-error-ink` `#C9372D`** (white on it: 5.16). A red button with black text reads as a
  hazard sign rather than a destructive action, so this is the one place the black-or-white rule is
  overridden — **justified in place, in a comment on the line**, which is exactly the escape hatch
  AC#2 allows.

The invariant that makes this mechanical, and worth stating once: **any ink value dark enough to
clear 4.5:1 against white (L ≤ 0.1833) automatically carries white text at ≥ 5.09:1.** So a solid
button in any product hue can always be built from that hue's ink value plus white.

### 5.3 The dark rail is solved by the same *structure*, not the same *value*

**Decision: `--color-rail-muted` is remapped to `#9AA5B5`, the same value as `--color-rail-text`.**

The rail already has three passing levels above the threshold — white (17.75 / 15.36), rail-active
teal (12.00 / 10.38) and rail-text (7.12 / 6.16) — so collapsing the fourth costs nothing that the
light surface did not already cost, needs no new hex, and clears both dark surfaces with margin.
The alternative, a distinct `#7F8CA1` (5.21 / 4.51), lands 1.37:1 from rail-text: the same
invisible-distinction trap, plus a 0.01 margin on the menu surface.

The rail's section eyebrows keep their identity through **10.5px / weight 700 / uppercase / 0.1em
tracking** — three differentiating channels already in the code (`NavRail.tsx:226, 262`).

**The rail's binding surface is `--color-ink-menu` `#1C2536`, not `--color-ink`.** This is the
inverse of `SURFACE.row` and it must be written down, because measuring a rail token against
`#101828` alone passes a value that fails in the user menu — which is precisely how `#6B7688` got
here (3.86 on the rail, 3.34 in the menu, and only the first number is in the ticket).

### 5.4 The chart ramp: narrow the sentence, keep the hexes — verified, not assumed

**Verified in code** (this was the ticket's explicit instruction and the answer is yes):

- Every chart body is wrapped in `aria-hidden="true"`: `BurnupChart.tsx:55`, `FlowChart.tsx:39`,
  `CycleTimeChart.tsx:73`, `VelocityChart.tsx:53`, `AgingChart.tsx:52`, `InsightsChart.tsx:56`, plus
  the two in-page wrappers at `FlowReportPage.tsx:445` and `CycleTimeReportPage.tsx:629, 781`.
- The table underneath is real and shared: `SeriesTable` (`pages/reports/common.tsx:244–301`,
  `<table>` at :254), used by the report pages and by `InsightsPanel.tsx:378`.
- The **legend swatch is itself `aria-hidden`** (`common.tsx:308`) and the legend **label** is
  `--color-text-secondary` (`common.tsx:306`) — so the series hue is never text and never the sole
  carrier of a meaning.
- It is already pinned by tests: `CycleTimeReportPage.test.tsx:475–476`,
  `FlowReportPage.test.tsx:360`, `InsightsPanel.test.tsx:217`.

So the ramp carries no 1.4.3 obligation and no 1.4.11 obligation (a graphic whose information is
fully conveyed by an adjacent table and a labelled legend is decorative). **Darkening
`--color-chart-5` and `--color-chart-context` would spend the colour-blind separability the ramp was
chosen for, to satisfy a criterion that does not bind.**

**The false half of the sentence is "darkened where needed for contrast on the white card
surface"** — that clause claims a ratio the ramp does not have. Replace it (§5.6 gives the text).
Record the choice and its reason in the Decisions Log, per AC#4.

**`--color-chart-axis` does move**, from `#98A2B3` to the new muted value. It is a neutral, not an
Okabe–Ito hue, so there is no separability cost; the `/* = --color-text-muted */` comment stays true
instead of going stale; and the exported PNG — which travels away from its table — gets a readable
axis for free. `--color-chart-grid` (`= --color-border`) does not move (it is a grid line, and
borders are the out-of-scope follow-up).

### 5.5 Family 4: every semantic hue declares a fill value and an ink value

**Decision: each semantic hue keeps today's value as its FILL and gains a darker, same-hue sibling
as its INK.** The ink siblings are derived by the identical one-factor linear-channel scale
`colour.ts` uses, so they are the same hue with less light — the palette's identity is untouched and
only the glyph moves. Targets are 4.5:1 against `#EEF1F6` (§2.1).

| new token | value | vs card | vs canvas | vs raised | white on it |
|---|---|---|---|---|---|
| `--color-brand-ink` | `#077A79` | 5.16 | 4.73 | 4.56 | 5.16 |
| `--color-error-ink` | `#C9372D` | 5.16 | 4.73 | 4.56 | 5.16 |
| `--color-warning-ink` | `#A15C03` | 5.19 | 4.75 | 4.58 | 5.19 |
| `--color-success-ink` | `#087D56` | 5.15 | 4.72 | 4.55 | 5.15 |

And one in-place nudge, not a sibling:

- **`--color-sandbox` / `--color-info` `#667085` → `#646E83`** (4.52 on raised). The change measures
  **1.03:1** against the current value — literally invisible — so there is no reason to carry a
  second token for it. This is the correct treatment whenever the ink form is within a hair of the
  fill form; a sibling is only worth declaring when the two values are visibly different.

**`--color-warning-ink` reads brown, and that is an owner question, not a bug** (§8, OQ2). Amber
darkened to legibility is brown; that is what the hue is. The alternative is to stop using amber as
ink at all — a dot or chip in `--color-warning` beside neutral text — which is more consistent with
`DESIGN.md`'s restraint rule but is per-site work rather than a token swap.

### 5.6 The complete list of declared values that move

Because this is a change to the declared visual language, here it is in one place, with nothing
buried.

**Remapped (5) — same name, new value:**

| token | from | to | why |
|---|---|---|---|
| `--color-text-muted` | `#98A2B3` | `#5B6676` | §5.1 |
| `--color-rail-muted` | `#6B7688` | `#9AA5B5` | §5.3 |
| `--color-chart-axis` | `#98A2B3` | `#5B6676` | §5.4 (keeps `= --color-text-muted` true) |
| `--color-sandbox` | `#667085` | `#646E83` | §5.5, a 1.03:1 change |
| `--color-info` | `#667085` | `#646E83` | §5.5, same |

**Added (6) — no existing value changes:** `--color-on-brand` `#04211F`, `--color-brand-ink`
`#077A79`, `--color-error-ink` `#C9372D`, `--color-warning-ink` `#A15C03`, `--color-success-ink`
`#087D56`. (Five; the sixth slot is reserved by OQ2 if the owner splits `--color-pending` from
`--color-warning`.)

**Unchanged, deliberately:** `--color-brand`, `--color-brand-hover`, `--color-accent-2`,
`--color-error`, `--color-warning`, `--color-pending`, `--color-success`, `--color-rail-text`,
`--color-rail-active`, `--color-ink*`, all `--color-chart-1..5`, `--color-chart-context`,
`--color-chart-grid`, all borders, all surfaces, `--color-text`, `--color-text-secondary`.

**`DESIGN.md` prose edits:**

- Color § "Neutrals": `text #98A2B3 (muted) → #5B6676 (secondary) → #16202E (primary)` becomes a
  two-level statement plus the sentence *"There are two ink levels below primary, not three; the
  third level of emphasis is carried by size, weight and position. A grey light enough to be a third
  level cannot clear 4.5:1 on the hover surface — the lightest one that can measures 1.13:1 against
  secondary."*
- Color § "Navigation rail": drop `section labels #6B7688`; state that rail ink is measured against
  `--color-ink-menu`, the lighter of the two dark surfaces.
- Color: promote the on-fill ink rule out of *Colour that comes from data* into a property of any
  solid product-hue fill, and name `--color-on-brand`.
- Color: add the fill/ink pair rule and the four `-ink` tokens.
- Data Visualisation § "The categorical ramp": replace *"darkened where needed for contrast on the
  white card surface"* with *"adjusted only for separability from each other and from the semantic
  hues. The ramp makes no WCAG contrast claim and does not need one: a series hue appears only
  inside the `aria-hidden` chart and on its `aria-hidden` legend swatch, over a real `<table>`
  carrying the same numbers. `--color-chart-5` (2.31) and `--color-chart-context` (2.96) would not
  clear 3:1 on a white card if they were load-bearing, and are deliberately left alone: darkening
  them costs the colour-blind separability the ramp exists for. Everything a reader actually reads —
  legend label, table, axis label — is a neutral text token and carries the ratio."*
- Decisions Log: one row per decision in §11.

---

## 6. Edge cases & failure modes

1. **The hover surface.** A row that tints on hover changes the background under text that did not
   change. Every token bound in §5 is measured against `--color-surface-2`, not `--color-card`. Any
   *new* surface introduced later widens this obligation — that is what the static test in §7.3
   enforces via an explicit surface list per token.
2. **The rail menu.** The mirror of (1): `--color-ink-menu` is *lighter* than `--color-ink`, so a
   rail token must be measured against the lighter one. Missing this is exactly how the current bug
   shipped (§5.3).
3. **Composited alpha.** `rgba(255,255,255,0.06)` rail hover, `color-mix(in srgb, var(--color-brand)
   12%, var(--color-card))` in `ui.tsx:333`, `${brand}18` in `ui.tsx:525`, and
   `rgba(94,234,212,.08)` on the landing page all produce an *effective* background no declaration
   states. The static test cannot see these; the DOM harness can and must (it is what "walking
   ancestors and alpha-compositing translucent layers" in the ticket means).
4. **Gradients.** `.lp-btn-primary`, `.lp-logo`, `.lp-rlogo`, `.lp-rnew` and the rail's New-issue
   button are `linear-gradient(brand → accent-2)`. `getComputedStyle` returns the gradient, not a
   colour. The harness must **fall back to the darkest stop for light-ink measurement and the
   lightest stop for dark-ink measurement** (here: `#04211F` on `#0EA5A4` = 5.58 is the binding end;
   on `#14B8A6` it is 6.79). A harness that silently skips gradient backgrounds reports a clean
   button that is not clean.
5. **Text over an image or an unresolvable ancestor.** None exists today. The harness must **report
   these as `indeterminate` and fail the run**, never silently pass them — an unmeasurable element
   is a hole, not a success.
6. **`opacity` on an ancestor.** An `opacity: 0.4` dragged backlog row multiplies both fore- and
   background toward the parent; the ratio changes. The dragged state is transient and
   pointer-driven, so the harness measures the resting state, and the spec states the exemption
   rather than leaving a silent gap.
7. **Large-text exemption.** 3:1 applies at ≥24px, or ≥18.66px at weight ≥700. The harness must
   apply the exemption per element, from computed size and weight — and must **not** apply it to
   `--color-text-muted`'s old value, which fails even 3:1 (that is the sentence in the ticket that
   makes the token the answer).
8. **Icons and non-text.** Out of scope as a criterion (§3), but the muted icons in §2.2 are fixed
   incidentally. The harness reports 1.4.11 findings in a **separate, non-failing section** so the
   follow-up ticket has data and this ticket has a clean gate.
9. **A per-site override becomes the loophole.** AC#2 allows one as a last resort. Bound it: an
   override is a literal hex or a non-token colour in a component, and the static test's tripwire
   counts them. If the count grows past the number this ticket ships with, the test fails and the
   next contributor has to argue rather than append.
10. **The audit passes vacuously on an empty database.** A backlog with no issues has no rows and no
    failures. The harness asserts a **floor on the element count per page** (Backlog ≥ 400 text
    elements, etc.) — the same tripwire pattern `colour.test.ts` uses (`scanned.length > 60`) and
    `RequestFieldLengthBoundTest` uses on the backend.
11. **The quantisation trap.** Every derived hex in §5 was computed in continuous space and rounded
    to 8 bits; two of the four ink siblings landed *under* 4.5 on the first try and needed one
    further step (`#087B7A` → 4.4962, `#A35D04` → 4.4973). The builder must re-measure after
    encoding. `colour.ts`'s `derive` already does this correctly and is the reference.
12. **Concurrency / idempotency / soft-delete / optimistic locking:** not applicable. No data, no
    rows, no state. Recorded so a reviewer can see the question was asked.

---

## 7. Verification — the hard part, and the ticket's AC understates it

AC#1 says *"verified by re-running the audit rather than by inspection."* The audit was run against
**production, logged in as a real member**. A fix cannot be verified that way before it deploys, and
the script does not exist in the repository. So AC#1 as written is unsatisfiable today. Here is what
makes it satisfiable pre-merge.

### 7.1 First task: commit the harness

`src/main/frontend/audit/contrast-audit.mjs`, plus `npm run audit:contrast`.

- **Browser:** `puppeteer-core` with `channel: 'chrome'` (devDependency: `puppeteer-core` only —
  never `puppeteer`, which downloads a Chromium binary). This project's environment blocks
  downloaded Chromium under Smart App Control; the **system-signed Chrome does run headless** via
  `channel: 'chrome'` / Playwright's equivalent. A harness that ships the browser cannot be run by
  the person who most needs to run it.
- **Target:** `AUDIT_BASE_URL`, default `http://localhost:5173` (Vite dev) with
  `http://localhost:8080` (packaged JAR) as the documented alternative. `AUDIT_EMAIL` /
  `AUDIT_PASSWORD` drive a real login through the login form, so the harness exercises the same
  authenticated render the production measurement did.
  **It may not run against the box that measurement came from.** This section tells a reader that
  the reference numbers are production numbers, so reproducing them by pointing the harness at
  production is the obvious next step — and it would type the credentials into that host, write its
  DOM into the report, and mint a 30-day refresh token that only signing out deletes. So a
  non-loopback `AUDIT_BASE_URL` is **refused** unless `AUDIT_CONFIRM_REMOTE` names today's UTC
  date, in the shape `ops/loadtest` already uses for `LOAD_TARGET` / `LOAD_CONFIRM`; every run
  states its target before logging in, and signs the session out in a `finally`. The local numbers
  will not equal the ticket's — a demo seed is not that workspace (§6.10's floors say so) — and the
  property the harness proves is per-combination, not a total.
- **Pages:** the six the ticket sampled — Home, My work, Board, Backlog, Issue detail, Search — as a
  declared list in the script, extended when a page is added. Viewport 1280×900, matching the
  original measurement so the two numbers are comparable.
- **Measurement:** for every element holding a visible text node, computed colour, size and weight,
  and the effective background by ancestor walk with alpha compositing, gradient handling per §6.4,
  and `indeterminate` per §6.5.
- **Arithmetic:** **imports `contrastRatio` / `parseColour` / `relativeLuminance` from
  `../src/colour.ts`.** It does not reimplement them. Two contrast implementations is two answers,
  and the one in the harness would be the one nobody tests.

### 7.2 What the harness must emit

- A JSON report at `audit/contrast-report.json` and a human summary on stdout. **Both are
  copies of a logged-in workspace's DOM.** `audit/*.json` is gitignored as a directory rather
  than as one filename, because `AUDIT_REPORT` can redirect the write; the report also carries a
  `_warning` key naming what it holds, since an ignore rule protects the repository and protects
  no ticket, chat thread or email — and the file is what gets pasted into those. The stdout copy
  has no ignore rule at all, so it prints selectors, colours and counts and never an `outerHTML`
  prefix or a text sample.
- Per failing element: ratio, required threshold, computed fg/bg, size, weight, **and source
  attribution** — the DOM path plus the element's `outerHTML` prefix — so 375 rendered failures
  collapse to the dozen source expressions that cause them (§2.2). Without attribution the tail of
  37 combinations cannot be turned into a work list, which is the whole reason the re-run is task
  one.
- A distinct-combination table (fg × bg × size × weight → count), which is the artefact that
  reproduces and then supersedes the ticket's table.
- Element-count floors per page (§6.10), failing the run if a page came back thin.
- 1.4.11 findings in a separate, non-failing section (§6.8).
- Exit non-zero on any 1.4.3 failure or any `indeterminate`.

### 7.3 The static test — cheap, browserless, and it proves a different thing

`src/main/frontend/src/palette.contrast.test.ts`, running under `npm test`, reusing HD-176's
`index.css?raw` parsing pattern.

- A **declared classification**: every `--color-*` token maps to a role (`ink` / `fill` / `surface` /
  `neutral-nontext`) and, for inks, the list of surfaces it is allowed on.
- Assert: every `ink` token clears 4.5:1 against **every** surface in its list; every `fill` token
  used as a solid button background carries its declared ink at ≥4.5:1; every `-ink` sibling has the
  same chromaticity as its fill (it is the same hue, dimmed — the property that stops a "fix" from
  quietly becoming a different colour).
- **Two tripwires**, both in the pattern this codebase already uses: (a) every `--color-*` declared
  in `index.css` appears in the classification map, so a new token cannot be added without being
  classified; (b) the count of classified tokens has a floor, so an emptied map does not pass
  everything.
- **What it can prove:** that no declared value is illegal on a surface it is declared for. That is
  a real guarantee and it costs no browser.
- **What it cannot prove:** which token any given *element* uses, composited alpha from ancestors,
  gradients, per-element size and weight, and any hardcoded hex in a component. Those need the DOM.
  **Both are required; neither replaces the other**, and the spec says so rather than letting a
  green `npm test` be read as a clean audit.

### 7.4 The honest caveat about "tested"

**`npm test` runs on no automated path.** CI executes exactly one command, `./mvnw -B verify`, and
the `frontend-maven-plugin` executions are `npm ci` and `npm run build` — nothing invokes `npm test`
(HD-242, already recorded in `colour.test.ts`'s own header). So the static test protects a reviewer
and a local run, not a merge, and neither does the harness. **Do not write "the property is tested"
in this ticket's artefacts without that sentence attached.** Wiring `npm test` and
`npm run audit:contrast` into the build is HD-242's job; this ticket adds the second reason it
matters and should say so on the ticket rather than absorb it.

### 7.5 The seam between HD-175 and HD-176

They share one module and must not share one rule.

> **A colour that comes from the database is derived at render time, because nobody can review it in
> advance. A colour that comes from the stylesheet is decided at design time and asserted at build
> time, because everybody can.**

Concretely:

- **`colour.ts` is the shared arithmetic** — `contrastRatio`, `parseColour`, `relativeLuminance`,
  `token`, `SURFACE`. Both tickets import it. Neither forks it.
- **HD-176 owns `inkOn` / `fillOf` / `ringOn` / `tintOf` / `onSolid` and their call sites.** Its
  tests (`colour.test.ts`, `ui.contrast.test.tsx`) assert *properties of the derivation* and the
  `TOKEN_FALLBACK` ⇄ `index.css` parity. They must not start asserting a token's design value.
- **HD-175 owns the declared values and `palette.contrast.test.ts`.** It must never call `inkOn` on
  a token. Deriving a palette at runtime would hide a palette bug behind a computation and make
  every screenshot depend on an algorithm rather than on a reviewable hex — and it would put the
  design system beyond the reach of a diff.
- **The reviewer's rule of thumb:** *if the hex can be found by grepping the repository, it is
  HD-175's; if it can only be found by querying the database, it is HD-176's.*

---

## 8. Frontend impact

| file | change |
|---|---|
| `src/main/frontend/src/index.css` | 5 remapped values, 5 added tokens, 5 hardcoded `#04211f` literals replaced by `var(--color-on-brand)`, comment on `--color-chart-axis` kept true |
| `src/main/frontend/src/components/ui.tsx` | `Button`: `primary` drops `text-white` and sets `color: var(--color-on-brand)`; `danger` keeps white and takes `background: var(--color-error-ink)` with the override comment (§5.2) |
| `src/main/frontend/src/components/NavRail.tsx` | no change required (already uses `#04211f`); optionally swap the literal for the new token |
| ~54 files using a semantic token as `color:` | swap `var(--color-X)` → `var(--color-X-ink)` where the audit says the site is text. Mechanical, and the audit's source attribution is the work list |
| `src/main/frontend/src/palette.contrast.test.ts` | new (§7.3) |
| `src/main/frontend/audit/contrast-audit.mjs` | new (§7.1) |
| `src/main/frontend/package.json` | `"audit:contrast"` script, `puppeteer-core` devDependency |

**No page, component, store, route or config-driven rendering path changes.** No element gains or
loses a box, a border, a padding or a radius — the density guarantee HD-176 asserted for the board
and backlog rows holds here by construction, since nothing but a colour value moves.

`DESIGN.md` compliance: the changes *are* `DESIGN.md` changes and are enumerated in §5.6 for the
owner to approve before the builder starts.

---

## 9. DC/Cloud implications

None. No profile gating, no property, no environment variable, no wiring target. The SPA is built
once and served identically from both modes; the stylesheet ships inside the JAR
(`BOOT-INF/classes/static/`) in both. Stated explicitly so `dc-cloud-guard` can be marked `n/a` with
a reason.

The audit harness is a **developer tool, not a runtime artefact**: it lives under
`src/main/frontend/audit/`, is excluded from the Vite build (outside `src/`), and adds
`puppeteer-core` to `devDependencies` only — so it must not appear in the production bundle or in
the JAR. The builder verifies this by checking the built asset manifest, not by assuming it.

---

## 10. Acceptance criteria

Each names the artefact that checks it.

1. **`npm run audit:contrast` exists, runs against a local instance with system Chrome headless, and
   exits 0.** Artefact: `src/main/frontend/audit/contrast-audit.mjs`. Zero 1.4.3 failures and zero
   `indeterminate` elements across the six sampled pages at 1280×900.
2. **The audit's element-count floors are met**, so a green run cannot be a thin-page run. Artefact:
   the floors declared in the harness; a run against an unseeded instance must **fail**.
3. **The full distinct-combination table from the re-run is attached to the ticket**, including the
   37 combinations the original ticket did not list, each mapped to either a fixed token, a
   justified per-site override, or a documented exemption. Artefact: the harness's
   **stdout combination table** — colours, sizes, ratios, page keys and one selector per row — plus
   the ticket comment. **Not `contrast-report.json`, and not its `combinations` array either:** each
   row there carries `samples[].html` and `samples[].text`, the `outerHTML` prefixes and text
   excerpts §7.2 requires for source attribution, taken from a logged-in workspace. An AC that says
   "attach the report" is a standing instruction to paste customer content into a tracker; the
   stdout table is the same rows with the content left out, which is why it prints what it prints.
   The file stays local, is gitignored as `audit/*.json`, and says so in its own `_warning` key.
   *This is the AC that must be satisfied first, not last.*
4. **`npm test` passes, including `palette.contrast.test.ts`**, whose assertions are: every ink token
   clears 4.5:1 on every surface it is declared for; every solid-fill/ink pair clears 4.5:1; every
   `-ink` sibling matches its fill's chromaticity; and every tripwire in that file holds — the
   classification is total over `index.css`, the per-site override count does not grow, and no
   stylesheet token reaches a parameter that measures its argument. Artefact:
   `src/main/frontend/src/palette.contrast.test.ts`.
5. **`colour.test.ts` still passes unchanged**, in particular the `TOKEN_FALLBACK` ⇄ `index.css`
   parity assertion — five token values moved, and `--color-sandbox` is in `TOKEN_FALLBACK`, so that
   map must be updated in lockstep. Artefact: `colour.test.ts` "surfaces … the fallback still agrees
   with index.css".
6. **No component paints a raw hex where a token exists.** The five `#04211f` literals are gone;
   the count of deliberate per-site overrides equals the number declared in the test's tripwire, and
   each carries a comment saying why the token was not enough. Artefact:
   `palette.contrast.test.ts` override tripwire.
7. **`npx tsc -b` and `npm run build` are clean.** Artefact: the build. (`tsc --noEmit` proves
   nothing here — see `CLAUDE.md`.)
8. **The white-on-brand decision is applied to both surfaces from one literal.** Artefact: grep —
   `--color-on-brand` is declared once and `#04211f` appears nowhere else.
9. **`DESIGN.md` states only what is true.** The chart-ramp sentence is narrowed per §5.6, the two
   failing ramp values are named with their measured ratios and the reason they stay, the text-level
   count says two, and the rail's binding surface is named. Artefact: `DESIGN.md` diff.
10. **The choice made on the chart claim is recorded with its reason** — narrowed, not darkened,
    because the chart is `aria-hidden` over a real table and darkening costs colour-blind
    separability. Artefact: `DESIGN.md` Decisions Log row (AC#4 of the ticket).
11. **Two ADRs are drafted** (§11) with `Status: Proposed` and indexed in `docs/adr/README.md`.
12. **No backend, schema, API, property or environment change appears in the diff.** Artefact:
    `git diff --stat` — every path under `src/main/frontend/`, plus `DESIGN.md`.
13. **A follow-up ticket exists for WCAG 1.4.11 on borders and control boundaries** (`--color-border`
    measures 1.19:1 on white), with the audit's non-failing 1.4.11 section attached as its data.

---

## 11. Open questions

**OQ1 — Do two tokens with the same value stay, or does one get retired?** `--color-text-muted` and
`--color-text-secondary` both become `#5B6676`; likewise `--color-rail-muted` and
`--color-rail-text`. **Recommendation: keep both names.** Renaming touches 428 + 4 sites for zero
behavioural gain, the Beacon token strategy is explicitly names-stable, and the duplicate name
documents where the design *intended* de-emphasis — information a future dark theme or a future
third channel will want. The cost is a "why are these the same?" question, answered by a comment in
`index.css` pointing at ADR-0028.

**OQ2 — Does `--color-warning-ink` `#A15C03` (a brown) belong in the palette at all?** Amber
darkened to 4.5:1 is brown; that is the hue, not a mistake. The alternative is to stop using amber
as ink — a `--color-warning` dot or chip beside neutral text — which is more consistent with
`DESIGN.md`'s restraint rule and with HD-176's "nothing gains a box, the identity is the fill", but
is per-site work across the overdue-date and pending-state sites rather than a token swap.
**Recommendation: declare the token and ship it**, then let the audit's site list tell the owner how
many places actually render brown; converting them to dot-plus-neutral-text later is a UI refinement
that needs no token change. Also note `--color-pending` and `--color-warning` are the same hue with
two names by design (the safety-state machine); if the owner wants pending to keep the bright amber
as a *fill* while warning text goes brown, they are already separate tokens and can diverge.

**OQ3 — Is the visual weight change acceptable?** This is the §0 flag restated as a question the
owner must answer before the builder starts, because it is not recoverable by a later tweak: roughly
two hundred elements move from light grey to mid-slate. **Recommendation: accept it.** The lightest
legal alternative is 1.13:1 away, i.e. it looks the same, so the weight change is the price of the
threshold and not of the token structure. If it is rejected, the ticket becomes an IA exercise
(*use muted for less text*) with a different shape and a much larger diff.

**OQ4 — Should the `danger` button be black-on-red or white-on-darker-red?** §5.2 recommends the
second (`--color-error-ink` fill + white, 5.16). The first is the letter of the black-or-white rule
(5.59) and reads as a hazard sign. This is the only deliberate override in the spec and the owner
should see it named.

**OQ5 — Which local instance does the audit run against?** Vite dev server (fast, matches the
developer loop, but serves unminified CSS) or the packaged JAR (matches production exactly). The
computed values are identical either way. **Recommendation: default to the dev server for the loop
and document the JAR URL for the pre-merge run**, since the JAR is what CI would eventually run
under HD-242.

---

## 12. Architectural decisions (ADR)

Two decisions here are hard to reverse and will draw a "why?" from a future contributor. Both are
drafted as ADRs with `Status: Proposed`.

**ADR-0028 — Beacon has two ink levels below primary; the third level of emphasis is size and
weight, not luminance.**
*Chosen:* collapse `--color-text-muted` into `--color-text-secondary`'s value (and `--color-rail-muted`
into `--color-rail-text`'s), and carry de-emphasis with size, weight, case and position.
*Rejected:* (a) a distinct third value `#676E79` — legal, but 1.13:1 from secondary, invisible, and
0.04 from the cliff on the hover surface; (b) muted tuned for the white card only (`#707784`) —
fails on canvas (4.13) and on a hovered row (3.98), the exact surfaces the audit already caught;
(c) keep three levels and exempt small muted text as decorative — it is not decorative, it carries
issue keys, dates and counts.
*Trade-off:* the app reads heavier and the palette loses a level of visual nuance it was designed
with, in exchange for every text element being readable. The alternative that preserved the look
does not exist — the arithmetic does not leave room for one.

**ADR-0029 — A colour from the stylesheet is decided at design time and asserted at build time; a
colour from the database is derived at render time.**
*Chosen:* the palette declares reviewable hexes and a browserless test asserts their ratios; only
data colour goes through `inkOn`/`ringOn`/`tintOf`.
*Rejected:* (a) run every token through HD-176's derivation at render time — one rule everywhere,
tempting, and it would hide a palette bug behind a computation, make every screenshot depend on an
algorithm, and put the design system beyond the reach of a diff; (b) two independent contrast
implementations, one per ticket — two answers, and the untested one wins wherever they disagree;
(c) rely on the DOM audit alone — it needs a browser, a server and a seeded database, so it will not
run on the loop where the mistake is made.
*Trade-off:* two guard mechanisms to keep straight, and a seam a reviewer has to know. Bought with
one sentence: *if the hex can be found by grepping the repository it is the stylesheet's; if it can
only be found by querying the database it is the renderer's.*

The white-on-brand decision (§5.2) is deliberately **not** an ADR: it applies a rule `DESIGN.md`
already declares to a second class of fill. It gets a Decisions Log row, not a fork record. Likewise
the chart-claim narrowing (§5.4) — it corrects a sentence, it does not choose a path.
