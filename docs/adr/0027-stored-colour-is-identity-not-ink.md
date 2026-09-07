# ADR-0027: The stored colour is identity (a hue), not ink: the readable foreground is computed at render time and is not stored

Record date: 2026-09-03
Status: Accepted
Source: `docs/design/taxonomy-colour-contrast-proposal.md` §2, §5.1, §6.4 (HD-176);
the existing render points — `src/main/frontend/src/components/ui.tsx` (`Badge`, `StatusBadge`,
`PriorityBadge`, `ParentChip`) and `src/main/frontend/src/components/labels.tsx` (`LabelChip`);
the palette seed — `V1__init_schema.sql`; the declared palette and the soft-tint rule — `DESIGN.md`
(the Color section, the catalogue defaults for priorities and issue types);
ADR-0022 — why a migration does not rewrite tenant data

## Context

Statuses, priorities, issue types, labels and custom-field options carry a `color` chosen by a human
and sitting in the database. The SPA draws it mostly as **text**. That is, the legibility of a caption
is the result of a click in the palette that nobody checks, in a product where that click is offered to
the workspace admin as a normal configuration action. Measurements against the white card: most of the
seed palette fails 4.5:1 (WCAG 1.4.3), and most of the palette **declared in `DESIGN.md`** fails too.
The two worst values in this workspace (1.64 and 1.79) were chosen by a human using the feature exactly
as it is meant to be used.

The fork: **what the stored colour is — ink or identity.**

- **Ink.** Then the only guard is refusing to store the unreadable: a check in the palette and at the
  API. It breaks in three places at once. First, at a threshold of 4.5:1 against white, of the declared
  catalogue defaults the ones that pass that check are `#667085` (4.97) and `#3B5BFD` (5.12), while
  `#F04438` (3.76), `#F79009` (2.35), `#EAB308` (1.92), `#7C6CF5` (3.95) and `#12B981` (2.54) do not;
  the rule would forbid the colours of our own design system. Second, a check on write repaints
  nothing: a row with 1.64 stays that way forever, until it is overwritten by the owner who chose it in
  the first place. Third, the threshold is bound to one background, while the dark theme is written
  down in `DESIGN.md` as a direction: a colour checked against `#FFFFFF` is not checked against
  `#101828`.
- **Identity.** Then the colour is the entity's hue, and what it is drawn with is computed from it and
  from the surface.

It matters that **both readings already live in the code.** `LabelChip` (HD-30) draws the stored colour
as a tint and a dot and takes the text from a neutral token; `Badge` puts the stored colour straight
into the text. The readable variant arrived later — and for a colour source with the **wider** door: a
label's colour is chosen by any member with `label.create`, a status's colour only by an admin. The
choice is not between two hypotheses but between two rules that are already written.

## Decision

**The stored colour is identity. It is drawn at full strength everywhere it is a FILL, and darkened to
the threshold everywhere it is INK.**

- **The darkening is one multiplier over three linear channels.** `k = L_target / L_colour`, where
  `L_target = (L_surface + 0.05) / 4.5 − 0.05`. Multiplying all three linear channels by a common
  factor changes the luminance by exactly `k` and **does not change the chromaticity at all** — it is
  the same hue with less light. No search, no colour library, no unreachable case: on a light surface
  the multiplier converges to black, and that is 21:1.
- **Above the threshold the function is the identity.** Byte for byte the same colour. That is what
  makes a workspace that has already tuned its colours for ink see not a single change, and it is an
  acceptance criterion, not a pleasant detail.
- **The surface is an argument, not an assumption.** `inkOn(colour, surface)` is given the opaque
  background the glyph will be drawn on; a chip computes its own tint and passes it, and a chip that can
  end up both on a card and on a highlighted row passes the darker of the two. Tomorrow's dark theme
  comes from the same place: it is a new value of the argument, not a new fork.
- **The tint becomes a computed opaque colour rather than an alpha overlay.** `${color}20`
  blends with whatever ends up behind it — that is, one thing was measured and another was painted.
- **The fill keeps the hue and gets a hairline ring**, computed by the same function at the threshold
  of 3:1 (WCAG 1.4.11), so that a pale dot does not dissolve into the card. Text over a solid fill
  (the issue-type tile on Home) is black or white, whichever wins: at any fill luminance at least
  one of them gives ≥ 4.58:1, the intervals overlap.
- **Nothing is stored.** No column, no DTO field, no field in `ProjectConfigResponse`. The computation
  is a few floating-point operations, memoised by a bounded map.
- **The threshold is not a configuration property.** 4.5 and 3.0 are two thresholds defined by WCAG,
  not tuning dials. A property nobody sets is a default with extra steps; a property with which an
  operator can lower the accessibility floor turns "text in Hamstrack is readable" into "readable, if
  it has not been switched off".
- **The rule is phrased about the category:** *any colour that sits in the database and was chosen by a
  user is a hue; it does not become a glyph until it passes the threshold against the surface that
  glyph is drawn on.* The phrasing "statuses, priorities and issue types" would have gone stale on
  labels, and labels already exist.

## Consequences

+ Legibility stops depending on the taste of whoever clicked in the palette and becomes a property of
  the renderer — at any hue, including `#FFFF00`.
+ **Not a single tenant row is edited.** This workspace's two unreadable statuses are fixed without
  touching a choice a human made deliberately — that is, the decision about the rows (§5.2 of the spec)
  reduces to ADR-0022 and needs no fork of its own.
+ The admin's palette stays complete: not a single colour is refused, neither in the UI nor at the API.
  No new contrast 4xx appears.
+ Screen density does not change: no element gains a border, padding or a new box. The board and the
  backlog stay as they were.
+ The dark theme, when it comes, gets the guarantee for free.
+ An existing latent defect is closed along the way: the tint is no longer an alpha overlay on top of
  an unknown background.
− **The drawn text colour does not match the stored hex exactly where the stored one is unreadable.**
  That is the price, and it must not be hidden: the palette is obliged to show both the measured ratio
  and the hex it will be drawn with. Otherwise it is magic, which the owner of the colour will discover
  as a bug.
− A render primitive appears that one can silently walk past: a new component will write
  `style={{ color: x.color }}` and nobody will notice. Closed by a category test with a counter of the
  files scanned, not by an agreement.
− The rule lives in two places — in `DESIGN.md` and in one SPA module — and must stay consistent.
− The guarantee rests on the caller passing the **real** surface. A mistake here neither crashes nor
  goes red — it simply gives slightly less contrast. That is why a chip computes its own background
  rather than accepting it from a component.

## Alternatives

- **Check the colour in the palette, leaving it as ink** — rejected. The rule would forbid the
  catalogue defaults declared in `DESIGN.md` itself; it fixes not one already existing row; it refuses
  more strictly than needed (the same colour is legal as a 3:1 dot and illegal as 4.5:1 text); and it
  is bound to a single background, whereas the dark theme is already written down as a direction. That
  door does not close: the decision does not prevent adding such a check later — it removes the need
  for it.
- **A solid colour fill + black/white ink** — rejected, even though its guarantee is the strongest
  (≥ 4.58:1 out of two candidates, checkable in your head). It requires **revoking** two provisions of
  `DESIGN.md` ("restraint… colour means state, not decoration" and "soft badge tints —
  `color + 18–20` alpha"), turns the inline issue-type text on the board card and in the backlog row
  into one more box on the two densest screens of the product, and repaints every tenant, including
  those whose colours were fine. The solid form stays exactly where the design already uses it
  deliberately.
- **Store the computed foreground in a column** — rejected: a second source of truth that goes stale on
  a change of theme, surface or threshold and on any colour write by raw SQL, and is repaired by a
  migration — for the sake of a value that costs a few arithmetic operations.
- **The threshold as a configuration property** (`CONTRAST_MIN`) — rejected: an accessibility floor the
  operator can lower is a promise the product cannot keep, and a family of behaviours of which exactly
  one is tested. If AAA is ever needed, the honest form is a whole theme, not a dial on a single
  constant.
- **Leave it as it is and fix only the seeds** — rejected: the seeds cannot be touched without
  answering "and what do we do about tenant rows", and the only answers there are to rewrite somebody
  else's choice or to leave the knowingly unreadable in place. It is exactly this dead end that makes
  the fork unavoidable.
