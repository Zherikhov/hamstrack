# ADR-0028: Beacon has two ink levels below the primary one, not three; the third level of emphasis is carried by size and weight, not by brightness

Record date: 2026-09-04
Status: Accepted
Source: `docs/design/palette-text-contrast-proposal.md` §0, §2.1, §5.1, §5.3 (HD-175);
the declared palette — `DESIGN.md` (the Color section, "Neutrals" and "Navigation rail");
the token values — `src/main/frontend/src/index.css`;
the rule "the darker of the two surfaces" — `src/main/frontend/src/colour.ts` (`SURFACE.row`,
`darkest`) and ADR-0027

## Context

Headless-browser measurements against production (1280×900, as a real member): **480 of 949**
elements with visible text fail the WCAG 1.4.3 threshold. A third of them are one token:
`--color-text-muted` `#98A2B3` gives **2.58** on the white card, **2.36** on the `#F3F5F9` canvas
and **2.27** on the `#EEF1F6` backdrop. It is used as a text colour in 378 places and fails even the
3:1 threshold for large text, so size cannot save it.

The palette declares **three** ink levels: `--color-text` `#16202E`, `--color-text-secondary` `#5B6676`
and `--color-text-muted` `#98A2B3`. The first two pass the threshold (16.40 and 5.82 on white), the
third does not. The obvious repair — "make muted darker" — runs into arithmetic, not into taste.

**The darkest light surface on which the product draws text is `--color-surface-2`
`#EEF1F6`** (the backdrop and the row hover state; a backlog row changes the background under
stationary text). Its relative luminance is 0.8774, which means any colour passing 4.5:1 on it has a
luminance of ≤ **0.1561**. `--color-text-secondary` is already at **0.1304**. That is, the whole band
left for a third, lighter but still lawful level is 0.1304 → 0.1561, and **the contrast between
its edges equals 1.13:1**. That is not a level of hierarchy, it is two tokens that look the same.

Exactly the same fork arrives a second time on the dark rail: `--color-rail-muted` `#6B7688` gives 3.86
on `--color-ink` and **3.34** on `--color-ink-menu` `#1C2536` (the menu pinned to the rail is a
surface lighter than the rail itself, a mirror of the `SURFACE.row` rule). The hue-preserving
candidate `#7F8CA1` passes both (5.21 / 4.51) and lands **1.37:1** away from `--color-rail-text` —
the same trap, one surface later.

## Decision

**Below the primary text colour Beacon has two ink levels, not three. The third level of emphasis is
carried by size, weight, case and position.**

- `--color-text-muted` is remapped to `#5B6676` — the same value as
  `--color-text-secondary`.
- `--color-rail-muted` is remapped to `#9AA5B5` — the same value as `--color-rail-text`
  (7.12 on the rail, 6.16 on the menu).
- **The token names are kept.** Beacon's strategy is stable names, remappable values
  (`DESIGN.md`, Decisions Log, 2026-08-09); one change of a value repaints all 428 places
  at once, whereas renaming would cost 428 edits for zero behavioural gain.
- **An ink token is measured against the darkest light surface it can land on**
  (`#EEF1F6`), and **a rail ink token against the lightest dark one** (`#1C2536`). This is one rule
  read from two sides, and both sides must be written down: the current rail bug arose precisely
  because the value was checked against `#101828` and was not checked against the menu.
- **Disabled controls and placeholders do not need a new token.** WCAG 1.4.3 takes inactive
  interface elements out from under the threshold, and `components/ui.tsx` already draws them as
  `opacity: 0.6` over passing ink. Declaring `--color-text-disabled` means opening a loophole with
  428 candidates and no mechanical way to tell a lawful use from a return to the old value.

## Consequences

+ Every text element coloured with these tokens starts passing 4.5:1 on **all three**
  light surfaces, including the hover state — and not only on the one the screenshot was taken against.
+ Along the way this closes 1.4.11 on the icons drawn with the same token (the drag handle in a
  backlog row, the select chevron, the filter icon): 2.58 also failed the 3:1 threshold for non-text elements.
+ Editing one value repaints 428 places; not a single component is rewritten, not a single element
  gains a border, padding or a box.
+ The dark theme, when it comes, gets a stated rule ("measure against the binding surface"),
  and not a precedent from one surface.
− **The application looks noticeably heavier.** Around two hundred light-grey elements become
  mid-slate. That is the price of the threshold, not of the token structure: the nearest lawful
  alternative is 1.13:1 away, i.e. it looks the same.
− The palette loses the level of nuance it was drawn with. The compensation is size (12–12.5px against 14px),
  weight (500/600 against 400), case and tracking on the overlines, position (the meta line under the
  heading). All four channels already exist in the code.
− Two token names get one value (`muted` = `secondary`, `rail-muted` = `rail-text`). This
  raises the question "why both?", which is answered by a comment in `index.css` linking here.

## Alternatives

- **A separate third value `#676E79`** (5.14 / 4.71 / 4.54 on the card / the canvas / the backdrop) —
  rejected. It is lawful, it preserves the hue and it is **invisible**: 1.13:1 from secondary. On top
  of that it sits 0.04 from the cliff on the hover surface, i.e. one edit to the hover background and
  it fails again. A distinction that nobody sees but that every next contributor will try
  to preserve costs more than two honestly declared levels.
- **Tune muted for the white card only** (`#707784`, 4.50 on white) — rejected
  by arithmetic: 4.13 on the canvas and **3.98** on the backdrop, i.e. exactly those surfaces on which
  the audit has already caught 14 failures. The token is used on all three, so the darkest one binds.
- **Keep three levels and declare small muted text decorative** — rejected: it carries issue
  keys, dates, counters and field labels. That is content, not decoration.
- **Run token values through `inkOn` at render time** — rejected by a separate decision,
  see ADR-0029.
- **Reduce the share of small text that uses muted at all** — not rejected, but this is different
  work: an information-architecture edit across 83 files, which does not lift the requirement from the
  text that remains. That door is open after this decision, not instead of it.
