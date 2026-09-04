# Design System — Hamstrack

> **Current visual language: "Beacon" (adopted 2026-08-09).** Slate neutrals + teal
> accent, a dark navigation rail, and a work-centric dashboard Home. Chosen from a
> 10-variant design study after user feedback (Beacon = `mockups/09-beacon.html`).
> The earlier "Industrial/Utilitarian" warm-neutral + navy-topbar scheme is retired;
> its rationale is preserved in the Decisions Log for history.

## Product Context
- **What this is:** Source-available task tracker (Elastic License 2.0 — free to self-host, modify and use commercially; may not be resold as a managed service), Jira-inspired but must not copy Jira's UI/implementation/naming. Core differentiator: safe AI-agent-driven self-service process customization (validators, transition rules, approval flows) with sandbox dry-run, human approval, and post-promotion rollback.
- **Who it's for:** Project owners and workspace admins at companies leaving Jira Data Center or evaluating new trackers — technical, skeptical buyers who have been burned by complexity and do not trust black-box AI by default.
- **Space/industry:** B2B SaaS / dev & ops process tooling.
- **Project type:** Web app (dashboard-style internal tool), dual-deployment — self-hosted DC-style and Cloud, same codebase.

## Aesthetic Direction
- **Direction:** Modern product SaaS — a calm, work-centric dashboard. Clean white cards on a cool slate canvas, anchored by a dark navigation rail. Confident but not flashy.
- **Decoration level:** Restrained. Soft elevation (1–3px resting shadow, ~18px on hover), generous rounding, thin hairlines. Color signals state and category, never pure ornament.
- **Mood:** "Этому AI здесь можно доверять" (this AI can be trusted here) — a serious, organized instrument that also feels welcoming on the daily Home screen. Approachable, not playful.
- **Reference sites:** linear.app (rail + keyboard-grade polish), ClickUp / Notion (work-centric "Home" dashboard the direction is built around), Asana / monday (view-tabbed project workspace). Deliberately avoided: generic indigo SaaS (`plane.so`) — teal keeps us non-derivative.

## Typography
- **Everything:** **Inter** (400/450/500/600/700/800). Single UI/display typeface — clean, predictable, excellent at small sizes in data-dense views. Used for hero, headings, body, and labels alike.
- **Data / keys / tables / audit:** **IBM Plex Mono**, tabular-nums — issue keys (`PAY-131`), diff/version hashes, audit-log values, any literal "inspectable" datum. This is a deliberate structural choice (not just code), signaling "inspectable, not magic."
- **Code:** **JetBrains Mono** — actual script/validator source.
- **Loading:** Inter + IBM Plex Mono + JetBrains Mono via Google Fonts CDN (`fonts.googleapis.com`). No Fontshare dependency anymore.
- **Scale:** Dashboard greeting 26px/800. Page title 20–24px/800. Section/widget heading 15px/800. Body 14px (UI default). Meta/labels 12–13px. Data/mono 11–12.5px. Marketing hero 40–52px/800.
- **Tracking:** Tight on large headings (`-0.02em`); normal for body; wide (`+0.05em`, uppercase) for small section eyebrows.

## Color
- **Approach:** Cool slate neutrals + a single teal brand accent. Color still doubles as a **safety-state indicator** (sandbox → pending → production) — a product-trust differentiator, not just branding.
- **Brand / production-trusted state:** `#0EA5A4` (teal), hover `#0C9188`, light companion `#14B8A6`. Bright rail-active teal `#5EEAD4` on dark.
- **Pending-approval / in-progress state:** `#F79009` (amber).
- **Sandbox / draft / neutral-info state:** `#606A7E` (slate).
- **Neutrals (cool slate):** app canvas `#F3F5F9` → raised `#EEF1F6` → card `#FFFFFF`; borders `#E8EBF1` / `#DDE1EA`; text `#5B6676` (secondary) → `#16202E` (primary).
  **There are two ink levels below primary, not three; the third level of emphasis is
  carried by size, weight and position.** A grey light enough to be a third level cannot
  clear 4.5:1 on the hover surface — the lightest one that can measures 1.13:1 against
  secondary. `--color-text-muted` is kept as a name (it documents where the design intends
  de-emphasis) and holds secondary's value.
- **Navigation rail (dark "ink"):** background `#101828`; item label `#9AA5B5`, hover `rgba(255,255,255,0.06)`, active label `#5EEAD4` on a `rgba(14,165,164,0.18)` fill; menus anchored on the rail use `#1C2536`. **Rail ink is measured against `#1C2536`, the *lighter* of the two dark surfaces** — the mirror of the hover-surface rule on the light side, and the reason the rail also has two levels rather than three.
- **Semantic:** success `#12B981`, warning `#F79009` (shared with pending), error `#F04438`, info `#606A7E` (shared with sandbox). Soft tints for badges use `color + 18–20` alpha on light.
- **Every product hue is declared twice: a FILL and an INK.** The hue itself is the fill —
  a dot, a bar, a tile, a solid button, a badge tint — and is never dimmed. Its `-ink`
  sibling is *the same hue with less light*, dark enough to be read: `--color-brand-ink`
  `#077877`, `--color-error-ink` `#C3362C`, `--color-warning-ink` `#A15C03`,
  `--color-success-ink` `#087D56`. The ink is the fill's own linear RGB scaled by one
  factor, so chromaticity is exactly preserved; the palette's identity does not move, only
  the glyph does. A sibling is worth declaring only when the two values are visibly
  different — `--color-sandbox` / `--color-info` were nudged in place instead, because
  their ink form is 1.06:1 from their fill form.
  **The surface an ink is bound by is the darkest one it lands on**, and for a semantic hue
  that is not the raised surface: a badge paints its own hue at 14% over white, which is
  darker still. Amber darkened to 4.5:1 is brown; that is what the hue is when it has to be
  read rather than seen.
- **Text over a solid fill of a product hue is black or white, whichever measures higher.**
  On `--color-brand` that is the brand's own near-black, declared once as
  **`--color-on-brand` `#04211F`** (5.58:1; white measures 3.03:1) and used by every solid
  brand surface — the primary button, the avatar, the rail's New issue button, the landing
  page's CTA. The one deliberate exception is the `danger` button: black on bright red is
  the letter of the rule and reads as a hazard sign, so the fill darkens to
  `--color-error-ink` and the text stays white (5.41:1).
- **Priority palette (catalog defaults):** Urgent `#F04438`, High `#F79009`, Medium `#EAB308`, Low `#667085`.
- **Issue-type palette (catalog defaults):** Bug `#F04438`, Task `#3B5BFD`, Story `#7C6CF5`, Epic `#12B981`. (Catalog `color`/`icon` always win at runtime — these are only seed defaults.)
- **Explicitly avoided:** indigo/violet as the *primary* brand accent (category default; Plane uses it). Violet is fine as a *type* color only.
- **Dark mode:** Not yet themed app-wide (the rail is the only permanently-dark surface). When added: dedicated surface/border/text overrides, not inverted lightness; state tints darken while preserving hue so safety-state meaning stays legible.

### Colour that comes from data (added 2026-09-03, HD-176)

**Any colour stored in the database and chosen by a user — a status, priority,
issue type, label or custom-field select option — is an identity hue, not ink.**
It is painted at full strength wherever it is a **fill** (dot, tile, tint, bar,
chart segment) and is **dimmed until it clears 4.5:1 against the surface it is
actually painted on** wherever it is a **glyph**. The dimming scales all three
linear RGB channels by one factor, so it preserves chromaticity exactly — the
same hue with less light — and it is the **identity function** for every colour
that already clears the threshold, which is why a workspace that has already
tuned its colours sees a byte-identical render.

- **Two thresholds, both from WCAG, neither configurable:** **4.5:1** for a glyph
  (1.4.3) and **3:1** for a fill (1.4.11). A fill carries a 1px ring derived from
  the same hue at 3:1, so a pale dot keeps a visible edge. An accessibility floor
  an operator can lower is a promise the product cannot keep.
- **The surface is an argument, never an assumption.** The same hue needs a
  different factor on a white card, on a hoverable row and on the dark rail — an
  element that can sit on either measures against the darker. This is also what
  makes dark mode a new argument rather than a re-litigation.
- **Nothing gains a box.** The badge-tint rule above is unchanged; the tint is
  now *computed* to an opaque colour instead of layered as `color + 18–20` alpha,
  because an overlay composites over whatever is behind the element and so was
  never the background anybody measured. Bare inline text — the issue type on a
  board card and a backlog row — stays bare inline text: no padding, no radius,
  no fill. A board where every card carries two saturated blocks is a different
  visual language, not a refinement of this one.
- **Text over a solid fill of the hue** is black or white, whichever measures
  higher (floor 4.58:1 for any colour that exists) — the general rule stated in the
  Color section above, which is a property of solid fills and not of the database.
  Home's issue-type tile is where a *stored* hue takes it.
- **No colour is refused.** The picker discloses — the real chip, the real dot,
  the measured ratio, and the derived hex when one is used — and stores exactly
  what was picked. Refusing below 4.5:1 would delete yellow, amber, mid-orange,
  bright green and light pink from every admin's palette, including most of the
  catalog defaults declared above.
- **Derived at render time, never stored.** One primitive
  (`src/main/frontend/src/colour.ts`); a component that needs a colour from data
  asks it rather than writing `style={{ color: x.color }}`. A stored foreground
  would be a second source of truth going stale on every theme, surface and
  threshold change.

## Data Visualisation (charts — added 2026-08-19, reports epic HD-5)

Charts are read, not decorated. The palette below is the **only** source of series colour
for a chart whose series are *measures* (created, resolved, open, cycle time, scope,
completed). It is deliberately disjoint from the colours that already mean something.

- **The categorical ramp (5 hues + context).** Derived from the Okabe–Ito colour-blind-safe
  set (deuteranopia / protanopia / tritanopia distinguishable), adjusted only for
  separability from each other and from the semantic hues. The ramp makes no WCAG contrast
  claim and does not need one: a series hue appears only inside the `aria-hidden` chart and
  on its `aria-hidden` legend swatch, over a real `<table>` carrying the same numbers.
  `--color-chart-5` (2.31) and `--color-chart-context` (2.96) would not clear 3:1 on a white
  card if they were load-bearing, and are deliberately left alone: darkening them costs the
  colour-blind separability the ramp exists for. Everything a reader actually reads — legend
  label, table, axis label — is a neutral text token and carries the ratio.
  | Token | Hex | Role |
  |---|---|---|
  | `--color-chart-1` | `#0072B2` | first series (blue) |
  | `--color-chart-2` | `#D55E00` | second series (vermillion) |
  | `--color-chart-3` | `#009E73` | third series (green) |
  | `--color-chart-4` | `#CC79A7` | fourth series (rose-purple) |
  | `--color-chart-5` | `#56B4E9` | fifth series (sky) |
  | `--color-chart-context` | `#8B97A8` | context / derived / reference series, always secondary — dashed lines, percentile rules, "open at end" |
  | `--color-chart-grid` | `#E8EBF1` | grid lines (= `--color-border`) |
  | `--color-chart-axis` | `#5B6676` | axis lines, ticks and labels (= `--color-text-muted`) |
- **No red, amber, yellow, teal or slate in the ramp — on purpose.** Those five hues are
  already spoken for: priority (Urgent red / High amber / Medium yellow / Low slate),
  status category (To do slate / In progress amber / Done teal) and the safety-state
  machine (sandbox slate → pending amber → production teal). A series colour that reuses
  one of them lets a bar be misread as a state. `--color-chart-3`'s green is a different,
  darker green from `--color-success` and never means "good".
- **Series colour carries no meaning.** It is an index, not a verdict; the legend and the
  table under the chart are the source of truth. The one exception is the rule below.
- **When the series IS a taxonomy entity, use the entity's own colour.** A chart sliced by
  status / priority / issue type reads `color` from the project `config` endpoint, exactly
  as badges do — never the ramp, never a hardcoded hex. Config-driven rendering does not
  stop at the chart boundary. Series colour itself does not change under *Colour that comes
  from data* — a segment is a fill and keeps the hue at full strength — but it takes that
  section's ring rule, and any label drawn in the hue takes its ink rule.
- **Exception — a chart above the project uses the ramp** (added 2026-08-20, search
  insights, HD-140). The rule above assumes one project's `config`. A workspace-scoped
  chart has none: the same status name can carry different configured colours in two
  projects, so there is no correct single answer to inherit, only a guess about which
  project wins. The ramp is the honest answer there — "this is series three" reads as a
  position, where a borrowed teal reads as *done* and is wrong wherever the borrowing was.
  Reach for the entity's colour again the moment a chart is back inside one project.
- **Five is the limit.** A slice with more than five categories groups the tail into
  **"Other"** rather than inventing a sixth hue: colours 6–10 of any ramp are not reliably
  distinguishable for colour-blind readers, and a 12-slice legend is unreadable for
  everyone.
- **Colour is never the only encoding.** Every series is also identified in the legend, in
  the tooltip, and in the table underneath — and dashed vs solid separates a derived
  series from a measured one.
- **Every chart ships a table equivalent directly underneath it**, with the same numbers in
  the same order. It is the accessibility answer (a chart is `aria-hidden` decoration over
  a real `<table>`), and it is what CSV export serialises.
- **Axes are zero-based with fixed ticks**, never auto-scaled to the data range, so two
  screenshots of the same report taken a week apart are comparable. Dates are UTC and the
  chart says so.
- **Provenance is part of the chart, not a tooltip.** Anything drawn from a bounded query
  prints when it was computed and how many issues it saw, and says out loud when a cap
  truncated it or when a first/last bucket is partial.
- **Chart type:** line for a time series, bar for a categorical breakdown, scatter for
  per-issue distributions. Stroke 2px, dots hidden at rest and shown on hover, `--radius-sm`
  on bar corners. Chart surface = a normal white card (`--radius-lg`, `--shadow-card`).
- **Library:** Recharts (MIT), imported **only** inside the lazy `/reports` chunk so the
  main bundle never carries it (precedent: the Swagger UI chunk).
- **An exported chart is a surface of its own** (added 2026-08-20, R7). It gets an **opaque
  white** background — never a transparent PNG, which is unreadable the moment it is pasted
  onto a dark surface, and these images are pasted into chats for a living — the report name
  above it, and a **footer band** carrying project, window and `computedAt`, plus the words
  *axes zero-based, fixed ticks*. The claim is in the picture because that is where it is
  used: the value of the axis rule is entirely in a reader holding two exports side by side,
  and one who does not know the rule holds will not try. The image is the chart's own SVG,
  serialised — never a second renderer drawing the same numbers a second way.

## Spacing
- **Base unit:** 8px (4px half-step for 2xs/xs). Scale: 2xs(2) xs(4) sm(8) md(16) lg(24) xl(32) 2xl(48) 3xl(64).
- **Density:** Comfortable on Home/dashboard and cards (16–24px padding); compact in tables and the board. Widgets and cards breathe — this is a dashboard, not a spreadsheet.

## Layout
- **App shell (two-pane):** a **dark navigation rail** (~214px, `#101828`) on the left + a light content column. The rail carries: brand, a prominent teal **"New issue"** button, global items (**Home**, **My work**), the current project's section (Board / Backlog / Reports / Settings) under a switchable project header, and a user footer (avatar + name → user menu: About, System administration, Sign out). A slim light **top bar** over the content holds global search (future HQL) + notifications. Create and primary nav live in the rail, not the top bar.
- **Home is the default post-login screen:** a work-centric dashboard — greeting, stat cards (assigned to me / in progress / completed / progress %), "Assigned to me", a board snapshot, "Due soon", priority breakdown, and a recent-activity feed. Widgets read live data where an endpoint exists and show a clearly-labelled "coming soon" placeholder where the backend doesn't exist yet.
- **Cards & surfaces:** white cards on the slate canvas, `--radius-lg` (14px) corners, `--shadow-card` at rest, `--shadow-pop` on hover. Panels/modals `--radius-xl` (18px).
- **Issue detail:** a right-hand **drawer** overlay (≈440–460px) — Details / Comments / Files / History — sliding in over the current view.
- **Responsive:** rail collapses to icons / a drawer below ~960px; a dedicated mobile form factor (bottom nav + FAB) is a future direction (see `mockups/08-pocket.html`).
- **Max content width:** 1180px for the dashboard; 1080px for marketing sections.
- **Border radius (hierarchical):** sm 6px (chips, small controls) · md 10px (buttons, inputs, badges) · lg 14px (cards) · xl 18px (panels, modals, sheets) · full 9999px (avatars, pills only).

## Motion
- **Approach:** Minimal-functional — only transitions that aid comprehension of a state change.
- **Easing:** enter `ease-out` · exit `ease-in` · move `cubic-bezier(.32,.72,0,1)` for drawers/sheets.
- **Duration:** micro 50–120ms (hover/focus) · short 160–260ms (drawer/modal/panel) · medium 260–400ms. No long/expressive choreography — deliberate, not playful.

## Decisions Log
| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-06-24 | Initial design system created (Industrial/Utilitarian, warm neutrals) | Created by `/design-consultation`, grounded in the office-hours doc + competitor research |
| 2026-06-24 | Color as a safety-state machine (slate → amber → teal) instead of pure branding | Reinforces the product's core trust differentiator (sandbox → pending → production). **Retained in Beacon.** |
| 2026-06-24 | Monospace for diff/audit/key data as a structural typographic choice | Signals "inspectable, not magic." **Retained in Beacon** (IBM Plex Mono for keys/tables/audit) |
| 2026-06-24 | Avoided indigo/violet as *primary* accent | Category default (Plane); teal keeps us non-derivative. **Retained.** |
| 2026-07-09 | App shell: dark global top bar + light contextual project sidebar | Superseded 2026-08-09. |
| 2026-07-13 | Top bar recolored to a navy gradient, grown to 56px | Superseded 2026-08-09 (rail is now flat ink `#101828`, no navy). |
| 2026-08-09 | **Adopted the "Beacon" visual language** — slate + teal, dark left navigation rail, work-centric dashboard Home as the default screen; retired warm-neutral palette and the navy top bar | Chosen from a 10-variant design study (`mockups/`, 5 style + 5 UX-layout concepts); friends' feedback overwhelmingly picked Beacon (`09-beacon.html`). The dashboard-first IA surfaces "my work" across projects; the dark rail frees the top bar and gives global actions a permanent home |
| 2026-08-09 | **Typography → Inter everywhere** (dropped Cabinet Grotesk + Instrument Sans) | Beacon is drawn on Inter; user chose exact fidelity to the mockup. Mono roles (IBM Plex Mono for data, JetBrains Mono for code) are kept, preserving the "inspectable, not magic" rule |
| 2026-08-09 | Token strategy: keep CSS variable **names** stable in `index.css`, remap only **values** | Lets the whole app re-skin from one file; the shell + core project screens (Board, drawer, Backlog, create, Home, My work) then get bespoke Beacon layouts on top |
| 2026-08-19 | **Added a data-visualisation palette** — a 5-hue colour-blind-safe categorical ramp (Okabe–Ito derived) plus context/grid/axis tokens, disjoint from every semantic hue | Charts arrived with the reports epic (HD-5) and the system had no series colours at all. Reusing priority/status/safety-state hues would let a bar be misread as a state, so the ramp deliberately contains no red, amber, yellow, teal or slate; a chart sliced BY a taxonomy entity still reads that entity's configured colour (config-driven rendering does not stop at the chart) |
| 2026-08-19 | **Recharts (MIT), lazy-loaded inside the `/reports` chunk only** | Owner decision (reports-proposal §9 OQ 1). Hand-rolled SVG is fine for lines and bars but not for the scatter-with-reference-lines that carries the most value in R3. Lazy import keeps the main bundle unchanged — same pattern as the Swagger UI chunk |
| 2026-08-19 | **Every chart ships a table equivalent underneath it** | It is the accessibility answer (the chart is decoration over a real table) and it is the series CSV export R7 needs — one artefact, two requirements |
| 2026-08-20 | **Exported images are opaque, titled and footed** — white background, report name, and a footer carrying project / window / `computedAt` / the axis claim | R7 (HD-141). A PNG leaves the app's theme and lands anywhere, so a transparent background plus dark text is unreadable on half the surfaces it reaches; and a chart pasted into a chat is separated from its URL the instant it is pasted, so everything needed to say what it is a picture of has to be inside it |
| 2026-09-03 | **A stored colour is an identity hue, not ink** — painted at full strength as a fill, dimmed to the same hue until it clears 4.5:1 as a glyph, derived at render time against the surface it lands on | HD-176. Legibility was an outcome of a colour-picker click nobody checked: most of the seeded catalog, and most of the palette this document declares, measures below 4.5:1 on the white card the product draws on. The two alternatives both cost more — guarding the picker would forbid the design system's own colours and could not repair a row a customer already chose, and a solid chip with black/white ink would override this document's restraint and badge-tint rules and add a second bounding box to the board and the backlog, the two densest surfaces. Deriving costs a text colour that is not byte-identical to the stored hex in exactly the cases where the faithful rendering is unreadable, and is the identity function everywhere else |
| 2026-09-04 | **Two ink levels below primary, not three** — `--color-text-muted` holds `--color-text-secondary`'s value, `--color-rail-muted` holds `--color-rail-text`'s, and the third level of emphasis is size, weight, case and position | HD-175 / ADR-0028. `#98A2B3` measured 2.58 / 2.36 / 2.27 on card / canvas / raised — under half the required ratio and under even the 3:1 large-text floor, so no size or weight rescued it. There is no room for a distinct third value: on the raised surface every colour that clears 4.5:1 has luminance ≤ 0.1561 and secondary already sits at 0.1304, so the lightest legal alternative (`#676E79`) measures **1.13:1** against secondary — a step no reader can see, sitting 0.04 above the cliff, that every future contributor would try to preserve. The rail hits the same wall one surface later (`#7F8CA1`, 1.37:1 from rail-text), and its binding surface is the *lighter* dark one, `#1C2536`. The cost is accepted deliberately: ~200 elements move from light grey to mid-slate and the app reads heavier |
| 2026-09-04 | **Every product hue is declared as a FILL and an INK** — `-ink` siblings for brand / error / warning / success, the same hue with less light; `--color-sandbox` and `--color-info` nudged in place instead | HD-175 / ADR-0029. A semantic token was used as text in 162 places across 54 files, at 2.35 (warning) to 3.76 (error) on white — the bulk of the failures and invisible in the ticket's own table. Splitting fill from ink keeps every dot, bar and tint at full strength and moves only the glyph, and the one-factor linear scale preserves chromaticity exactly so the palette's identity does not change. The binding surface is the darkest one the ink lands on, which for a badge is **its own 14% tint**, not the raised surface — measuring against the raised surface alone passes values that fail inside the badge they were introduced for. A sibling is declared only where the two values differ visibly; sandbox's ink form is 1.06:1 from its fill form, so it moved in place rather than gaining a second token |
| 2026-09-04 | **Text over a solid product-hue fill is black or white, whichever measures higher** — `--color-on-brand` `#04211F` declared once, used by the primary button, the avatar, the rail and the landing page; the `danger` button is the single deliberate exception | HD-175. The rule was already declared for colour that comes from data; it is a property of solid fills, not of the database, so it was unscoped rather than duplicated. White on `--color-brand` measured 3.03:1 and the landing page had already made the opposite call in five hardcoded places — one decision, one literal, both surfaces. The exception is named because it is a real override: on `--color-error` black measures 5.59 and reads as a hazard sign rather than a destructive action, so that fill darkens to `--color-error-ink` and keeps white text at 5.41:1 |
| 2026-09-04 | **The chart ramp is narrowed in prose, not darkened** — `--color-chart-5` (2.31) and `--color-chart-context` (2.96) stay exactly as they are; `--color-chart-axis` moves with the muted token | HD-175. The claim "darkened where needed for contrast on the white card surface" was verified and is false — neither value clears even 3:1. It does not need to: every chart body is `aria-hidden` over a real `<table>` carrying the same numbers, the legend swatch is `aria-hidden` too, and the legend *label* is a neutral text token, so a series hue is never text and never the sole carrier of a meaning. Darkening the two would spend the colour-blind separability the ramp exists for, to satisfy a criterion that does not bind. The axis is different: it is a neutral rather than an Okabe–Ito hue, so there is no separability to spend, and the exported PNG — which travels away from its table — gets a readable axis for free |
