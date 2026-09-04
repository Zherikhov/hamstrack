# Taxonomy colour and contrast — the stored value is a hue, not a pixel (HD-176)

**Status:** proposal / design review. **Date:** 2026-09-03. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness).
**Migration:** **V27** (`V27__taxonomy_palette_alignment.sql`) — data-only. Guarded `UPDATE`s over the
global catalog seeds plus three `ALTER COLUMN … SET DEFAULT`. No DDL beyond the defaults, no new
table, no new column, no row created or deleted.
**Related:** HD-188 (Flyway chain squash — this migration is written to be folded into it, §6.4),
HD-30 (`LabelChip`, which already renders a stored colour the way this spec generalises),
HD-5 / HD-140 (the data-visualisation palette and the "a chart sliced by a taxonomy entity reads that
entity's colour" rule), ADR-0022 (a migration is not a tool for rewriting customer data),
ADR-0019 (one refusal shape per declared constraint).
**Touches:** `src/main/frontend/src/` — a new `colour.ts` primitive, `components/ui.tsx`
(`Badge`/`StatusBadge`/`PriorityBadge`/`PriorityIcon`/`ParentChip`), `components/labels.tsx`,
`components/fields.tsx`, `pages/admin/AdminStatusesPage.tsx` (`ColorField`),
`pages/admin/AdminFieldsPage.tsx`, `pages/admin/AdminPrioritiesPage.tsx`,
`pages/admin/AdminIssueTypesPage.tsx`, `pages/admin/AdminWorkflowsPage.tsx`,
`pages/settings/WorkspaceLabelsPage.tsx`, `pages/BoardPage.tsx`, `pages/BacklogPage.tsx`,
`pages/HomePage.tsx`, `pages/IssueDetail.tsx`, `pages/SearchResultsPage.tsx`,
`pages/MyWorkPage.tsx`, `pages/reports/SprintReviewPage.tsx`; backend —
`AdminFieldService` (one new format refusal, §7.2); `DESIGN.md` (one added subsection);
`V27`; tests.
**No new configuration property. No new environment variable. No profile gating.** (§9 says why the
threshold is deliberately not a property.)

---

## 0. The recommendation, first

**Ship option B: the stored colour is an identity hue, and every foreground drawn from it is derived
at render time.** Concretely, in the shape that costs the least:

> A stored colour is painted at full strength wherever it is a **fill** (dot, tile, tint, bar, chart
> segment) and is **dimmed until it clears the contrast threshold** wherever it is **ink**. The
> dimming preserves the colour's chromaticity exactly — it is the same hue with less light — and it
> is the *identity function* for every colour that already clears the threshold.

Three consequences decide it:

1. **A workspace that already tuned its colours to read as ink sees no change at all.** The
   derivation returns the input untouched above threshold. Nothing is repainted, no screenshot moves,
   no admin is told their choice was wrong.
2. **No new box appears anywhere.** Option B is often heard as "everything becomes a solid chip".
   That is a *different* option (B-solid, §2.4) and this spec rejects it. Bare inline text stays bare
   inline text; the tinted badge stays exactly the badge `DESIGN.md` already mandates. Board and
   backlog density is untouched, which is the single most expensive thing B could have cost.
3. **It is the only option that lets a tenant's own bad colour be fixed without editing a tenant's
   data.** Under option A the 1.64 status this ticket found in our own workspace stays unreadable
   forever unless somebody overwrites a value a human chose on purpose.

**Option A — guard the picker so an unreadable colour cannot be stored — is rejected**, and the
reason that ends the argument is not accessibility, it is arithmetic: at 4.5:1 against white, the
palette `DESIGN.md` itself declares would be refused by its own picker. Priority *High* `#F79009`
measures 2.35, *Medium* `#EAB308` measures 1.92, issue type *Epic* `#12B981` measures 2.54. A rule
that forbids the design system's own colours is not a guard, it is a second, contradictory design
system (§2.3).

Option A's best argument, stated so the owner is choosing rather than ratifying, is in §2.5. It is a
real argument and it is about trust, not correctness.

**What still changes in the picker:** it stops being a bare `<input type="color">` and starts showing
what the product will actually paint, with the measured ratio as a number. It refuses nothing on
contrast grounds — under B there is nothing left to refuse (§7.1).

**Highest-risk assumption, flagged:** that the owner accepts a rendered *text* colour that is not
byte-identical to the stored hex when — and only when — the stored hex cannot be read. Everything
else in this document is mechanical. If that assumption is rejected, option A follows and §3's
deliverable changes shape completely; nothing between here and §12 survives the swap.

---

## 1. Problem & goal

Statuses, priorities, issue types, labels and custom-field select options each carry a `color` chosen
by a human and stored in the database, and the SPA paints most of them as **text**. Legibility is
therefore an outcome of a colour-picker click that nobody checked, in a product where the picker is
offered to a workspace admin as a normal, encouraged act of configuration. Measured against the white
card surface the product draws on, most of the seeded catalog fails WCAG 1.4.3's 4.5:1 minimum, and
so does most of the palette `DESIGN.md` declares; the two worst offenders found in this workspace
(`In Testing` at 1.64, `Is Planned` at 1.79, per the ticket) were picked by a person using the
feature exactly as designed. The product invited the choice and then rendered it.

**Success:** every glyph the SPA draws from a stored colour clears 4.5:1 against the surface it is
actually painted on, and every fill drawn from one clears 3:1 (WCAG 1.4.11, non-text contrast), for
*any* colour any admin can pick — including `#FFFF00` — with no data edited in any tenant, no colour
refused, and no measurable loss of information density on the board or the backlog. The guarantee is
a property of the renderer, verifiable by a test that names a number.

---

## 2. Decision 2 — what the colour is FOR

### 2.1 Where a colour is ink and where it is a fill today

This is the map the decision has to be made against, stated as categories because the file-and-line
list goes stale on the next component.

**Painted as ink (a glyph in the stored colour):** the badge label inside `Badge`/`StatusBadge`
(tinted background, text in the raw colour) — which is what board cards, the backlog, My work, search
results, issue detail and the sprint-review report all render through; `PriorityBadge`'s name and
`PriorityIcon`'s stroked glyph; the issue-type name drawn as bare inline text on board cards, backlog
rows, issue detail and search results; `ParentChip`'s issue key; a custom field's SELECT value and
each MULTI_SELECT chip, in the issue form and in the admin field list.

**Painted as a fill (no glyph on top):** every swatch dot in the admin catalog tables, the workflow
editor and the label picker; the board column-header dot; Home's priority-breakdown bar and its
legend squares; label chips' dots; the option dot beside a SELECT value; chart series that are sliced
by a taxonomy entity.

**Painted as a fill with fixed white ink:** Home's issue-type tile (`HomePage.tsx`, a 20px rounded
square carrying the type's initial in `#fff`). This one is worth naming because it is already the
naive form of the "solid chip" idea, and it already fails: white on `#EAB308` measures 1.92 (verified independently — this spec first stated 1.09, which was wrong in the number though not in the verdict).

One structural fact matters more than the map: **the product already contains both answers.**
`LabelChip` (HD-30) renders a stored colour as a tint plus a dot and puts its text in a fixed neutral
token — colour as identity. `Badge` puts its text in the raw colour — colour as ink. The readable one
shipped later, for the colour source with the *widest* door (any workspace member with `label.create`
may pick a label colour; only an admin may pick a status colour). We are not choosing between two
untried futures; we are choosing which existing rule wins.

### 2.2 Option A — the colour is ink, and the picker refuses what cannot be read

Guard the write path: a colour below 4.5:1 against white is refused (or warned about) at the picker
and at the API.

**What it costs.** It permanently deletes a large region of the colour wheel from every admin: at
4.5:1 on white there is no yellow, no amber, no mid-orange, no bright green, no cyan, no light pink
and no mid-red. Those are not exotic choices; they are the hues people reach for first when marking
*urgent*, *blocked* and *done*.

**What it fails at.**

- **It contradicts `DESIGN.md`.** Of the catalog defaults the design system declares, *Low*
  `#667085` (4.97) and *Task* `#3B5BFD` (5.12) survive its own picker. *Urgent* `#F04438` (3.76),
  *High* `#F79009` (2.35), *Medium* `#EAB308` (1.92), *Bug* `#F04438`, *Story* `#7C6CF5` (3.95) and
  *Epic* `#12B981` (2.54) do not. Either the picker gets an exemption for colours the product itself
  seeds — in which case the guarantee is void exactly where the ticket found the problem — or
  `DESIGN.md`'s palette is rewritten into a set of dark colours chosen for a text role none of them
  is used in as a *swatch*.
- **It does nothing for rows that already exist.** A write guard does not repaint anything. The
  1.64 status in this workspace stays at 1.64 until somebody edits it, and "somebody" is a customer
  who liked it.
- **It refuses more than it must.** The same colour is perfectly legal as an 8px dot (3:1) and
  illegal as 11px text (4.5:1). A single write-time threshold has to pick the stricter one and
  therefore forbids colours that were never a problem in half the places they are drawn.
- **It is anchored to one background, and the product intends to grow another.** `DESIGN.md`
  records dark mode as a planned direction. A colour vetted against `#FFFFFF` is not vetted against
  `#101828`; A's guarantee would have to be re-established, and every stored colour re-refused,
  the day a dark theme lands. B takes the background as an argument and is correct in both on day
  one.

**What becomes impossible under A:** picking a bright hue as an identity, ever, for anything.

### 2.3 Option B — the colour is identity, the foreground is derived (recommended)

Keep the stored colour as the entity's hue. Compute what to paint from it and from the surface it is
being painted on:

- **Ink** (`inkOn(colour, surface)`): if the colour already clears the threshold against that
  surface, return it unchanged. Otherwise scale its **linear** RGB channels by a single factor
  `k = L_target / L_colour`, where `L_target = (L_surface + 0.05) / ratio − 0.05`. Scaling all three
  linear channels by one factor changes luminance by exactly `k` and leaves chromaticity *exactly*
  unchanged — it is the same hue with less light, not a different colour. There is no search, no
  colour library, and no failure case: on any light surface the factor converges toward black, which
  is 21:1.
- **Fill** stays the stored colour at full strength, plus a hairline ring computed by the same
  function at the 3:1 threshold, so a pale dot keeps a visible edge (WCAG 1.4.11).
- **Solid ink** (`onSolid(colour)`, for Home's type tile): black or white, whichever wins. Every
  possible fill luminance satisfies at least one of them — black clears 4.5:1 above L = 0.175, white
  clears it below L = 0.183, and the intervals overlap — so the guaranteed floor is 4.58:1 for any
  colour that exists.

**What it costs.**

- When a colour fails, the glyph is not literally the hex the admin picked. `#FFFF00` on a white
  card is painted as ≈`#7A7A00` beside a `#FFFF00` dot. That is the whole cost, and it is
  concentrated exactly where the alternative is unreadable.
- One more rendering primitive that every future component must go through; a new component writing
  `style={{ color: x.color }}` bypasses it silently. Sealed the way this project seals things — a
  category test over the SPA sources with a scanned-file tripwire (§10).
- The tinted chip background stops being an alpha overlay and becomes a computed opaque hex. That is
  a *fix*, not a cost: `${color}20` composites over whatever is behind it, so today's tint over a
  hovered row is not the background anyone measured.

**What it fails at.** Fidelity of text colour, for illegible colours only. And it cannot rescue a
colour used as the *only* encoding of meaning — two admins may still pick two indistinguishable
hues, and no renderer can fix that; the name beside the swatch is what carries the meaning, exactly
as `DESIGN.md` already requires of charts.

**What becomes impossible under B:** nothing. A future decision to also refuse illegible colours at
the picker remains available; B does not close that door, it removes the need for it.

### 2.4 Why not B-solid (fill with the colour, ink black or white)

This is the variant the ticket describes, and its guarantee is the strongest available: ≥4.58:1 for
every colour, from two candidate inks, with arithmetic anybody can check. It is rejected anyway.

- **`DESIGN.md` says no, twice.** "Restrained… soft elevation, thin hairlines. Color signals state
  and category, never pure ornament," and "Soft tints for badges use `color + 18–20` alpha on light."
  A board where every card carries two saturated blocks is a different visual language, not a
  refinement of this one. Recommending it would require overriding the design system; recommending
  B-tint requires only *adding* a subsection to it. That difference is worth real money in approval
  cost and in rework.
- **It is the option that actually costs density.** The issue-type name is bare inline text on board
  cards and backlog rows today. Making it a filled chip adds horizontal padding and a second
  bounding box to the two most information-dense surfaces in the product, at the width where the
  backlog already truncates the type name at 90px.
- **It repaints every tenant.** Including the ones whose colours were fine.
- **It is loudest exactly where meaning is weakest.** A solid fill reads as emphasis. Applying it
  uniformly to five statuses makes all five shout, which is how a board stops communicating.

Keep the solid form where the design already uses one (Home's type tile) and give it `onSolid`.

### 2.5 The losing option's best argument

> *What you see is what you get.* The admin picked `#EAB308`; the product should paint `#EAB308`.
> A tracker that silently darkens a chosen colour will be filed as a bug by the person who chose it,
> and being right does not make that conversation pleasant. A refusal is honest and teaches — it
> tells you the colour cannot be read *here* and offers a nearer one that can. A derivation is magic:
> it must be discovered, it cannot be turned off, and it makes the colour picker a suggestion box.
> Option A is also a fraction of the code, has no cross-surface primitive to police, cannot regress a
> screenshot, and cannot be quietly bypassed by the next component somebody writes.

**Why it still loses.** The fidelity it defends is preserved by B for every colour that is legible —
the function is the identity there — so the WYSIWYG complaint can only be raised about a colour whose
faithful rendering is unreadable. And it is not silent: the picker shows the derived ink and the
measured ratio at the moment of choosing (§7.1), which is precisely the "say what it needs" that this
project's refusal doctrine demands, delivered without shrinking anyone's palette. The bypass risk is
real and is answered by a test, not by a promise.

### 2.6 Does the derived foreground need storing? No.

Computed at render time, memoised in a bounded `Map` keyed by `(colour, surface)`. Storing it would
create a second source of truth that goes stale on every theme change, every surface change, every
threshold change and every colour written by raw SQL, and would need a migration to correct — for a
value that costs a few floating-point operations to recompute. No column, no API field, no cache
invalidation. This is also what keeps the backend entirely out of the visual decision.

---

## 3. Scope

**In scope**

- One SPA colour primitive (`contrastRatio`, `inkOn`, `ringOn`, `onSolid`, `tintOf`) and the
  conversion of every existing render site to it — statuses, priorities, issue types, labels,
  custom-field select options.
- The admin colour picker becomes a preview that discloses the measured ratio and the ink that will
  be drawn; one shared implementation for all four entity editors.
- `V27`: alignment of the **global** seeded catalog colours and the three column defaults to the
  palette `DESIGN.md` declares, applied only where the row is still at its V1 value.
- One backend refusal that is missing today: a custom-field select option's `color` is not validated
  as a colour at all (§7.2).
- A `DESIGN.md` subsection recording the rendering rule (this is the design-system half of decision
  2 and cannot be shipped by a builder without it).

**Out of scope, deliberately**

- **Any change to workspace- or project-scoped colours.** Not one `UPDATE` touches a row a customer
  owns. §5.2.
- **Refusing a colour for contrast**, at the picker or at the API. B removes the need; adding both
  would shrink the palette for a guarantee already held.
- **Static design tokens.** `--color-text-muted` `#98A2B3` measures **2.58:1** on white and is used
  for meta text across the app. It is a real 1.4.3 failure and it is *not* this ticket: it is a
  design-system value, not a stored one, and fixing it changes the look of every screen. File it
  separately (§11, VQ 3).
- Dark mode itself. B is built so the theme, when it comes, is a new surface argument rather than a
  re-litigation.
- Colour-blind distinguishability of admin-chosen hues. Unsolvable by rendering; mitigated by the
  existing rule that colour is never the only encoding.

**Non-goals.** No WCAG conformance claim for the product as a whole. This spec makes one class of
value safe and says so precisely; a conformance statement would be a promise about screens nobody
audited.

---

## 4. Actors & permissions

| Who | Can do what | Scoping |
|---|---|---|
| System administrator (`/api/admin/**`, system role ADMIN) | Edit the **global** catalog rows, including the ones `V27` corrects | Global scope only (`scope_workspace_id IS NULL AND scope_project_id IS NULL`) |
| Workspace administrator (delegated console, `ScopeContext` at workspace scope) | Create and edit **workspace-scoped** taxonomy rows and their colours; sees global rows read-only, tagged as inherited | Own workspace; `findByIdAtScope` makes a global row unreachable for write |
| Project manager (delegated console at project scope) | Same, for project-private rows | Own project |
| Any workspace member with `label.create` / `label.manage` | Pick and change label colours | Own workspace |
| Every authenticated reader | Sees the derived rendering | — |

Two facts follow and they carry the whole of §5.2. **A tenant cannot have edited the seeded rows** —
`AdminCatalogService.requireStatus/requirePriority/requireIssueType` resolve through
`findByIdAtScope`, which never matches a global row from a scoped console. So the only party who can
have deliberately changed a seeded colour is the *instance operator's* system admin, which in Cloud
is us and in DC is the self-hoster. And **nothing in this spec adds or moves an authorization
decision**: no new endpoint, no new permission, no change to who may write a colour. `tenancy-reviewer`
still applies to `V27` and to the one backend refusal, and to nothing else.

---

## 5. Behaviour & rules

### 5.1 The rendering rule (the deliverable)

Stated as a category so it does not go stale when a sixth colour-carrying entity ships:

> **Any colour stored in the database and chosen by a user is an identity hue. It is painted at full
> strength as a fill and never as a glyph unless it clears 4.5:1 against the surface it is painted
> on. A fill carries a 1px ring derived from the same hue at 3:1. Text over a solid fill of that hue
> is black or white, whichever measures higher.**

Rules that are the content of that sentence:

1. **The surface is an argument, never an assumption.** `inkOn(colour, surface)` takes the opaque
   background hex the glyph will sit on. A chip computes its own tint (`tintOf`) and passes *that*;
   a chip that can sit on either a card or a hovered row passes the darker of the two, so the ratio
   holds in both states.
2. **Above threshold, the function is the identity.** Byte-identical output. This is what makes the
   change invisible to a workspace that already tuned its colours, and it is an acceptance criterion,
   not a nicety.
3. **Below threshold, only luminance changes.** Chromaticity is preserved exactly by construction
   (one factor over the three linear channels). The rendered ink is the same hue, dimmer.
4. **Rounding is checked, not assumed.** After gamma-encoding to 8-bit, the resulting colour's ratio
   is re-measured and dimmed one more step if rounding put it below the threshold. A guarantee that
   fails on `#8A8A00` versus `#7A7A00` is a flaky test waiting to happen.
5. **The tint is a computed opaque colour, not an alpha overlay.** `${color}20` composites over
   whatever happens to be behind it; the composited hex is what we measured, so it is what we paint.
6. **A colour we cannot parse is not a colour.** Anything that is not `#RRGGBB` or `#RRGGBBAA` falls
   back to the existing neutral token. The renderer never throws and never guesses.
7. **Alpha composites before it measures.** Label colours may carry `AA`; composite over the surface
   first. Fully transparent is treated as absent.
8. **Fills keep the hue everywhere, including charts.** A chart series sliced by a taxonomy entity
   still reads that entity's colour (`DESIGN.md`); it gains the ring rule and its labels gain the ink
   rule. Nothing about series colour changes.
9. **One primitive, no local variants.** A component that needs a colour from data asks the module.
   The rule is enforced by a test, because a rule enforced by review survives until the next reviewer.

### 5.2 The rule for existing data

> **A migration may correct a value the product chose. It may never correct a value a customer
> chose.**

That is a direct application of ADR-0022 (customer data is not reset by a migration), and it splits
the population cleanly:

- **Global seeded rows** (`scope_workspace_id IS NULL AND scope_project_id IS NULL`): a product
  choice, shared by every tenant, unreachable from any tenant's console. `V27` corrects them —
  **only where they still hold the exact V1 literal**, so a DC operator who deliberately changed one
  keeps it.
- **Workspace- and project-scoped rows:** a customer choice. Untouched, forever, including the two
  this ticket measured. They are made readable by the renderer, which is the entire reason decision 2
  comes before decision 3.

### 5.3 What `V27` actually aligns, and why it is not an accessibility fix

`DESIGN.md` declares a catalog palette; the database seeds a different one, and the priorities table
still defaults to `#8B8680`, a warm grey from the *retired* visual language. `StatusBadge`'s
category fallback (`--color-sandbox` / `--color-pending` / `--color-brand`) disagrees with the seeded
status colours, so the same status renders amber or blue depending on whether a colour survived the
trip. This is drift between the design system and the schema, and closing it is the honest framing of
decision 3.

Measured against `#FFFFFF`, for the record and not as a target:

| Row (V1 seed) | Now | Ratio | Aligned to | Ratio |
|---|---|---|---|---|
| Status · To Do | `#6B7280` | 4.83 | `#667085` (slate, = category fallback) | 4.97 |
| Status · In Progress | `#3B82F6` | 3.68 | `#F79009` (amber, = category fallback) | 2.35 |
| Status · Done | `#10B981` | 2.54 | `#0EA5A4` (teal, = category fallback) | 3.03 |
| Priority · Urgent | `#B91C1C` | 6.47 | `#F04438` | 3.76 |
| Priority · High | `#EA580C` | 3.56 | `#F79009` | 2.35 |
| Priority · Medium | `#B45309` | 5.02 | `#EAB308` | 1.92 |
| Priority · Low | `#64748B` | 4.76 | `#667085` | 4.97 |
| Priority · None | `#8B8680` | 3.61 | `#667085` | 4.97 |
| Type · Bug | `#EF4444` | 3.76 | `#F04438` | 3.76 |
| Type · Task | `#3B82F6` | 3.68 | `#3B5BFD` | 5.12 |
| Type · Story | `#8B5CF6` | 4.24 | `#7C6CF5` | 3.95 |
| Type · Epic | `#F59E0B` | 2.15 | `#12B981` | 2.54 |
| Default · `statuses.color`, `issue_types.color` | `#6B7280` | 4.83 | `#667085` | 4.97 |
| Default · `priorities.color` | `#8B8680` | 3.61 | `#667085` | 4.97 |

**Several aligned values measure worse than what they replace, and that is not an error.** Under B
the seed's job is to be the declared identity hue and to be *paintable* as a swatch — the 3:1 ring
(WCAG 1.4.11) is derived from the hue by `ringOn` at render time, exactly like the ink, so no seed
value owes a ratio either. Several of the aligned values do not clear 3:1 on their own, and that is
as immaterial as their ink: the fill is the hue at full strength and the hairline is computed from
whatever hue it is handed. Readability is likewise the renderer's job, at every hue, forever. Writing the migration
as if it were the accessibility fix would be the most misleading sentence in the ticket — the
migration is palette alignment, and it would be worth doing with no accessibility problem at all.

`statuses.category` stays authoritative for board grouping; only the colour moves.

### 5.4 The picker

`ColorField` (already shared by the status, priority and issue-type editors, and by the label editor)
becomes the single picker and gains a preview strip that renders, from the currently selected value,
the real chip, the real dot with its ring, and one line of plain text: the measured ratio of the raw
colour against the card, and — only when the derivation engages — the hex that will be drawn instead.
The custom-field option editor, which today inlines its own `<input type="color">`, adopts it. Nothing
is disabled, nothing is refused, no request is blocked.

---

## 6. Data model impact

### 6.1 Schema

No new table. No new column. No type change. Three `ALTER COLUMN … SET DEFAULT` (a catalogue-only
change, no table rewrite) and a set of guarded `UPDATE`s.

The `VARCHAR(7)` width on `statuses.color` / `priorities.color` / `issue_types.color` is left alone
and is worth one sentence, because `ddl-auto=validate` does not compare column lengths: it fits
`#RRGGBB` exactly, the entities must stay at `length = 7`, and the 8-digit form that labels accept
(`labels.color`) is a different column and is not being introduced here.

### 6.2 `V27__taxonomy_palette_alignment.sql` — outline

```sql
-- Header states: data-only; every UPDATE is guarded on BOTH the global scope and the
-- exact V1 literal, so an operator's deliberate change survives. Fold-in note per §6.4.

UPDATE statuses SET color = '#667085'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'To Do' AND color = '#6B7280';
-- … one guarded UPDATE per seeded row, per §5.3 …

ALTER TABLE statuses     ALTER COLUMN color SET DEFAULT '#667085';
ALTER TABLE priorities   ALTER COLUMN color SET DEFAULT '#667085';
ALTER TABLE issue_types  ALTER COLUMN color SET DEFAULT '#667085';
```

The `name` predicate is not decoration: it is what keeps the statement from matching a *different*
seeded row that happens to share a hex (`In Progress` and `Task` both seed `#3B82F6` today). The
scope predicate is not decoration either: a workspace-scoped row may legitimately hold the same hex,
and matching on colour alone would rewrite a customer's choice while looking correct in review.

Seeded custom-field select-option colours (`severity`, `environment`) are **not** touched — §8.

### 6.3 Ordering and the entities

No entity changes, no `@Version` interaction, no counter columns, no JPA writes at all. The migration
runs before any application read and is invisible to Hibernate.

### 6.4 Folding into HD-188

`V27` is written so it is equally correct applied on top of the chain or folded into the squashed
baseline. **What becomes unnecessary once folded:** every `UPDATE` (the baseline's `INSERT`s carry
the corrected literals directly) and every `ALTER COLUMN … SET DEFAULT` (the baseline's `CREATE TABLE`
carries the corrected `DEFAULT`). **What must survive the fold:** nothing else — the migration
creates no state.

Two sequencing conditions, both cheap and both easy to get wrong:

1. The regenerated baseline must be produced from a database that has **already run V27**, or the
   fold silently reverts the correction on every new install.
2. Production has run `V27` before the squash rewrites `flyway_schema_history`, so the rewrite must
   account for it exactly as it accounts for `V26`. If HD-188 lands first instead, this content
   becomes the first migration of the new chain and loses its `UPDATE`s; nothing else about it
   changes.

---

## 7. API surface

### 7.1 Unchanged, on purpose

No new endpoint. No new field on `StatusResponse` / `PriorityResponse` / `IssueTypeResponse` /
`ProjectConfigResponse` / the `Admin*Response` family — no derived foreground is transmitted (§2.6).
No status code changes on any existing catalog write; a `PUT` carrying `#FFFF00` still answers **200**
and stores `#FFFF00`. That last one is a test, not a remark: it is the assertion that we chose B and
not A.

### 7.2 One refusal that is missing today

A custom field's `config.options[].color` is validated **as nothing**. `UpsertFieldRequest.config` is
a `JsonNode`; `AdminFieldService` bounds the document's size and the option `id`/`label` lengths, and
never looks at `color`. So `"color": "javascript:…"`, `"color": "red"` and `"color": ""` are all
stored today and re-served to every project member from the endpoint the SPA fetches for every board.

Add the format check, in the shape the sibling paths already use:

- `POST/PUT /api/admin/fields/{id}` (and the workspace- and project-scoped consoles' equivalents):
  each `options[].color`, when present, must match `^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$`.
- **422 Unprocessable Content**, matching every other refusal on that path (the request is
  well-formed and semantically wrong), with a message that names the expected shape — the same
  sentence `LabelService.requireValidColor` uses, so the product has one phrasing for one rule.

`openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` gain that documented 422 reason. Nothing else
in the API reference moves. (`api-docs-sync` gate: required, small.)

---

## 8. Custom-field select options — in scope for one half, and why the other half is not an oversight

The asymmetry is deliberate and splits along the same line as everything else in this spec:

- **The rendering rule covers them, identically and for the same reason.** They are a stored,
  user-chosen colour painted as ink (`FieldValueDisplay`'s SELECT value, MULTI_SELECT chips, the
  admin list). Excluding them would mean the same yellow is legible as a status and unreadable as a
  *Severity*, which is not a rule anybody could state.
- **The `V27` seed alignment does not cover them, because `V27` is palette alignment and no palette
  is declared for field options.** `DESIGN.md` declares catalog defaults for priorities and issue
  types; it declares nothing for a customer's own select field, and it should not — the option set of
  a field named *Environment* is not a product concept. There is nothing to align them to, and
  inventing one would be a design decision smuggled in through a migration.
- **They get the format refusal the other three already have** (§7.2), which is the actual gap on
  that path.

So: one rule about colour, one exception about seeds, and the exception is a statement about who owns
the value rather than about which table it lives in.

---

## 9. DC / Cloud

Identical behaviour. No profile gating, no property, no environment variable, nothing to wire into
`docker-compose`, `.env.prod.example` or the README. The whole change is a renderer plus a data
correction that both modes receive from the same migration.

**On making the threshold a property — decided: no.**

- A property nobody sets is a default with extra steps, and nobody will set this one.
- An accessibility floor an operator can lower is a promise the product cannot keep. The moment
  `CONTRAST_MIN=1.0` exists, "text in Hamstrack is readable" becomes "text in Hamstrack is readable
  unless someone turned it off", and every future support answer has to ask first.
- It cannot be tested. The guarantee in §10 is a number in a test; a configurable number is a family
  of behaviours, only one of which is ever exercised.
- 4.5 and 3.0 are not tuning parameters, they are the two thresholds WCAG 1.4.3 and 1.4.11 define.
  They belong in `DESIGN.md` and in one module, where they are visible and version-controlled.

If a stricter floor is ever wanted (AAA, 7:1), the honest shape is a *theme* — a coherent set of
surfaces and thresholds — not a knob on one constant.

---

## 10. Frontend impact

**New:** `src/main/frontend/src/colour.ts` — pure functions, no React, no dependencies:
`parseColour`, `relativeLuminance`, `contrastRatio`, `compositeOver`, `tintOf`, `inkOn`, `ringOn`,
`onSolid`; a bounded memo `Map` (cap 512, cleared on overflow — a workspace with thousands of labels
must not grow a cache without a ceiling). Thresholds as two exported constants,
`INK_MIN = 4.5`, `FILL_MIN = 3`.

**Converted:** `components/ui.tsx` (`Badge` → computed opaque tint + `inkOn`; `StatusBadge`;
`PriorityBadge`/`PriorityIcon` → `inkOn` against the card; `ParentChip`), `components/labels.tsx`
(chip keeps its neutral text — it already complies — dot gains `ringOn`), `components/fields.tsx`
(SELECT / MULTI_SELECT), the four admin catalog pages and the workflow editor (swatch rings, the
shared `ColorField`), `pages/settings/WorkspaceLabelsPage.tsx`, `pages/BoardPage.tsx`,
`pages/BacklogPage.tsx` (inline type name → `inkOn`, still bare text, still truncated at 90px),
`pages/HomePage.tsx` (legend squares get rings; the type tile keeps its solid fill and takes
`onSolid`), `pages/IssueDetail.tsx`, `pages/SearchResultsPage.tsx`, `pages/MyWorkPage.tsx`,
`pages/reports/SprintReviewPage.tsx`.

**Config-driven rendering is preserved exactly**: every colour still comes from the project `config`
endpoint or the label endpoint; the primitive transforms what arrives and never substitutes a
hardcoded hue. No literal hex is added to any component (the existing `LABEL_PALETTE` remains the one
sanctioned exception, and the tokens stay in `index.css`).

**`DESIGN.md`**: one new subsection under Color — *Colour that comes from data* — carrying the rule
from §5.1, the two thresholds, and one line stating that the badge tint rule already written there is
unchanged and is now computed rather than layered with alpha. Plus a Decisions Log row. This is the
part that needs owner approval before a builder starts.

**Layout, density and motion: unchanged.** No element changes size, padding, radius or position. That
is the check a reviewer should run first, because it is the thing B-solid would have broken.

---

## 11. Edge cases & failure modes

| Case | Required behaviour |
|---|---|
| Colour already ≥ 4.5:1 on the target surface | Returned byte-identical. No repaint for tuned workspaces. |
| `#FFFF00` on a white card | Chip tint ≈ `#FFFFDF`; ink ≈ `#7A7A00`; measured ratio ≥ 4.5; dot painted `#FFFF00` with a derived ring ≥ 3:1. |
| `#FFFFFF` as a colour | Chromaticity is neutral, so the derived ink is a grey at ≥ 4.5:1; the dot's ring is what keeps it visible. No special case, no crash. |
| `#000000` | 21:1 — identity. |
| 8-digit `#RRGGBBAA` (labels) | Composited over the surface before measuring. `…00` treated as absent → neutral token. |
| Malformed (`red`, `#FFF`, `#12345`, `''`, `null`, a 20MB string) | Neutral token, no throw, no layout shift. Applies to legacy option colours stored before §7.2. |
| Chip on a hoverable row | Derived against the darker of `--color-card` and `--color-surface-2`, so the ratio holds in both states. |
| 8-bit rounding lands just under threshold | Re-measured after encoding and dimmed one further step (§5.1 rule 4). |
| Dark surface (`--color-ink`, future dark theme) | The same function, lightening instead of dimming; falls back to white when the hue cannot reach the threshold by lightening. |
| Memo cache growth | Bounded at 512 entries; overflow clears rather than evicts (cheap, and colour sets are tiny in practice). |
| Chart export (PNG) | The SVG is serialised from the same DOM, so derived values travel into the export automatically; no second renderer, per `DESIGN.md`. |
| A workspace-scoped row holding the same hex as a seed | `V27` must not touch it — the scope predicate, not the colour predicate, is what guarantees this. Test it explicitly. |
| An operator who deliberately recoloured a global seed | `V27` no-ops on that row (the colour predicate). |
| `V27` on a database seeded after a partial edit | Every statement is independently guarded; a mixed database ends in a consistent, defensible state either way. |
| Concurrency / optimistic locking | None. No entity is written, no `@Version` moves, no row is locked. Stated so the absence is not read as an omission. |
| Idempotency | Flyway runs a versioned migration once; the guards are what make a re-run or a partially-applied state safe, not a claim of idempotency. |

---

## 12. Acceptance criteria

Each is a number or a byte comparison a test can assert.

**The primitive**

1. `contrastRatio('#FFFFFF', '#000000') === 21` (±0.01); `contrastRatio('#FFFFFF', '#FFFFFF') === 1`.
2. `contrastRatio('#EAB308', '#FFFFFF')` measures 1.92 ± 0.01, and `contrastRatio('#0072B2', '#FFFFFF')`
   measures 5.18 ± 0.01 — the fixture that proves the formula, not the app.
3. `inkOn('#0072B2', '#FFFFFF')` returns exactly `'#0072B2'` (identity above threshold).
4. `inkOn('#FFFF00', tintOf('#FFFF00', '#FFFFFF'))` returns a colour whose measured ratio against that
   tint is ≥ 4.5, and whose linear-RGB channel ratios match `#FFFF00`'s within 0.5% (hue preserved).
   Its value is ≈ `#7A7A00`.
5. Property test over the sRGB cube sampled every 17 steps (16 values per channel, 4 096 colours) × surfaces
   {`#FFFFFF`, `#EEF1F6`, `#101828`}: **every** returned ink measures ≥ 4.5 against its surface and
   **every** returned ring measures ≥ 3.0. No exceptions, no thrown errors.
6. `onSolid(c)` returns `#000000` or `#FFFFFF` with a measured ratio ≥ 4.58 for every colour in the
   same sample.
7. Malformed inputs (`'red'`, `'#FFF'`, `'#12345'`, `''`, `undefined`, a 1MB string) return the
   neutral token and throw nothing.

**The rendering**

8. `StatusBadge` rendered with `color="#EAB308"` produces a computed `color` whose ratio against the
   computed `background` is ≥ 4.5, asserted from the rendered DOM, not from the props.
9. `StatusBadge` rendered with `color="#667085"` produces `color: #667085` exactly — the
   tuned-workspace guarantee.
10. A board card and a backlog row render the issue-type name with no wrapping element added: the
    rendered node count and element tag names are unchanged from before the change.
11. Home's type tile renders `#000000` or `#FFFFFF` text over the type colour, ratio ≥ 4.58.
12. Category test: no `.tsx` outside `colour.ts` and the primitives in `components/ui.tsx` assigns a
    CSS `color` from a `*.color` data field. The test carries a tripwire on the number of files
    scanned so that deleting the corpus fails loudly, and its failure message names the primitive to
    use.

**The picker**

13. `ColorField` at `#EAB308` renders the string `1.92` and the derived ink hex; at `#667085` it
    renders `4.97` and no substitution notice.
14. `PUT` of a status with `color: "#FFFF00"` answers **200** and the stored value reads back
    `#FFFF00` — the assertion that contrast is never refused.
15. `PUT` of a field whose `config.options[].color` is `"red"` answers **422** and the body names the
    expected `#RRGGBB` shape; the same request with `"#FF0000"` answers 200.

**The migration**

16. On a database migrated from empty: every global catalog row's colour equals the §5.3 aligned
    value; `statuses.color`, `priorities.color` and `issue_types.color` all default to `#667085`
    (asserted from `information_schema.columns.column_default`).
17. Applying `V27`'s statements to a fixture where a global row was first changed to `#123456` leaves
    that row at `#123456`.
18. Applying them to a fixture holding a **workspace-scoped** status named `In Progress` with colour
    `#3B82F6` leaves that row byte-identical — the scope predicate, proven separately from the colour
    predicate.
19. No row's `id`, `name`, `category`, `position` or `archived_at` differs before and after; the
    migration writes `color` and nothing else.

---

## 13. Open questions

### Blocks the build

1. **Does the option-colour format refusal (§7.2) ship here or as its own ticket?**
   *Recommendation: here.* It is a few lines on a path this spec is already opening, and separating
   it leaves the documented asymmetry of §8 half-answered.
2. **Does Home's issue-type tile stay a solid fill?**
   *Recommendation: yes*, with `onSolid`. It is the one place the design already uses a solid fill
   deliberately, and converting it to a tint would be an unasked-for visual change.
3. **`V27` before HD-188, as the release plan assumes?**
   *Recommendation: yes, land it first.* The fold is free (§6.4) and the alternative — a `V27` that
   arrives after the squash — is the same content with fewer statements. Either order works; only the
   silent-revert trap in §6.4 condition 1 must be honoured.

### Blocks the visual decision (owner)

1. **Decision 2 itself — identity hue with a derived foreground, or ink with a guarded picker?**
   *Recommendation: identity hue, in the tint form (§2.3), not the solid form (§2.4).* Needs the
   `DESIGN.md` subsection in §10; it is an addition to the design system, not an override of it.
2. **Which palette is the truth — the one `DESIGN.md` declares, or the one `V1` seeded?**
   *Recommendation: `DESIGN.md` wins and the database aligns to it*, including seeding the three
   status colours as the category triple that `StatusBadge` already falls back to. The opposite
   choice (rewrite `DESIGN.md` to the seeds) is defensible and cheaper — it makes `V27` unnecessary
   entirely — but it means the retired visual language's warm grey `#8B8680` becomes the documented
   default, which is the wrong direction.
3. **`--color-text-muted` at 2.58:1 — a separate ticket, or nothing?**
   *Recommendation: a separate ticket, filed with this one.* It is a genuine 1.4.3 failure on a token
   used across every screen, it is out of scope here by kind (a static design value, not a stored
   one), and leaving it unnamed would make this spec look like it audited contrast and missed the
   most-used grey in the product.

---

## 14. Architectural decisions

One, and it is the reason this document exists.

**ADR-0027 — the stored colour is an identity hue, not ink; the readable foreground is derived at
render time and never stored.**

- **Chosen:** derive at render time from the stored hue and the target surface, preserving
  chromaticity, with the identity function above threshold.
- **Rejected:** (a) guard the picker and keep the colour as ink — contradicts the declared palette,
  cannot repair existing rows, and anchors the guarantee to one background; (b) solid fill with
  black/white ink — the strongest guarantee, but overrides `DESIGN.md`'s restraint and badge-tint
  rules and costs density on the two densest surfaces; (c) store a computed foreground column —
  a second source of truth that goes stale on every theme, surface or threshold change.
- **Trade-off:** a text colour that is not byte-identical to the stored hex, in exactly the cases
  where the faithful rendering is unreadable, in exchange for a readability guarantee that holds for
  every hue, every tenant and every future surface, with no customer data edited.

Draft written to `docs/adr/0027-stored-colour-is-identity-not-ink.md`, `Status: Proposed`. Note that
`docs/adr/` is gitignored pending translation, so the file will not appear in a clone.

The §5.2 rule about migrations and customer data is deliberately **not** a second ADR: it is
ADR-0022 applied to a new table, not a new fork.
