# Resizable panels & board fluidity — spec

Status: proposed (2026-08-12) · Owner: systems-analyst · Type: **frontend-only** (no backend/API/schema)

Covers four closely-related SPA UX tasks:

1. Resizable + collapsible **NavRail** (left dark rail).
2. Resizable **IssueSidePanel** on the Board (no collapse).
3. Fluid **kanban zone** on the Board (adapts to viewport + panel open/close).
4. Smooth **open/close animation** for the issue panel on the Board.

All four touch only `src/main/frontend/`. There is **no backend, API, migration, or config change** — so no DC/Cloud divergence, no env vars, no `openapi.yaml`/`api-*.md` work, no tenancy/security surface. State persists client-side in `localStorage` (device-local, best-effort), mirroring `recentProjects.ts`. `DESIGN.md`'s Motion section is the sole source for durations/easing.

---

## 0. Problem & goal

The app shell is rigid. The NavRail is a hardcoded 214px; on a laptop it eats horizontal space users would rather give the board, and there is no way to reclaim it. The issue detail panel is a hardcoded 440px slab that pops in and out instantly, and when it opens the board columns get squeezed with no smoothing. Power users on wide monitors want a wider rail/panel; users on small laptops want them narrow or gone. Success: the user controls the width of both the rail and the issue panel within sane bounds, can collapse the rail to icons, those preferences survive reloads, and opening/closing an issue feels like a smooth drawer rather than a layout jump — all without breaking drag-and-drop, horizontal board scroll, or narrow viewports.

## 1. Scope

**In scope (all frontend):**
- Drag-to-resize handle on the NavRail's right edge, clamped; a collapse/expand toggle to an icon-only rail; persistence of width + collapsed state.
- Drag-to-resize handle on the IssueSidePanel's left edge **on the Board only**, clamped; persistence of width.
- Making the Board's kanban zone respond fluidly to viewport size and to the panel opening/closing.
- Animating the issue panel's enter/exit on the Board and the reflow of the columns.

**Out of scope / non-goals:**
- No resize/collapse of the light `TopSearchBar`.
- No resize of the panel on **Backlog** (task 2 & 4 are Board-only; Backlog's panel keeps its current fixed behavior — see §Edge cases for why, and Open Question Q3 for the optional follow-up).
- No collapse for the issue panel (explicitly excluded by the request).
- No server-synced/cross-device preferences (device-local only, like recents).
- No changes to drag-and-drop semantics, workflow rules, or the issue panel's internal content/tabs.
- No new dependency (no `react-resizable-panels` etc.) unless a reviewer insists — a ~40-line pointer-event hook is enough and avoids bundle growth. **Recommendation: hand-rolled hook, no library.**

## 2. Actors & permissions

Any authenticated user viewing the shell / Board. No role gate, no tenant scoping — this is pure per-device UI state. Nothing leaves the browser.

## 3. Shared infrastructure (build once, use across tasks 1 & 2)

Create `src/main/frontend/src/hooks/useResizable.ts` and a small persistence helper `src/main/frontend/src/uiPrefs.ts` (sibling to `recentProjects.ts`).

**`uiPrefs.ts`** — per-user keyed, JSON, try/catch, fail-safe defaults (copy the exact shape of `recentProjects.ts`):

```
key: `hamstrack.ui-prefs.${userId}`
value: { railWidth?: number; railCollapsed?: boolean; boardPanelWidth?: number }
getUiPrefs(userId) / patchUiPrefs(userId, partial)
```

Rationale for **per-user keying**: matches `recentProjects.ts` isolation (shared browser, multiple accounts). Use the current `user.id` from `useAuthStore`. If `user` is null (shouldn't happen inside the shell), fall back to reading nothing and using defaults — never crash.

**`useResizable`** hook — returns `{ width, isDragging, handleProps }` and manages a pointer-drag on an edge handle:
- Uses Pointer Events (`onPointerDown` → `setPointerCapture`, `pointermove`, `pointerup`) — one code path for mouse + touch + pen.
- Clamps to `[min, max]` on every move.
- Optionally clamps `max` to a fraction of `window.innerWidth` (see per-task numbers) and re-clamps on `window.resize`.
- Calls an `onCommit(width)` on pointer-up (persist there, not on every move — avoids hammering localStorage).
- Adds `user-select: none` + `cursor: col-resize` on `document.body` while dragging (removed on up) so text isn't selected mid-drag.
- Exposes `data-dragging` so the handle can show an active color.

Accessibility for both handles: render the handle as a focusable element with `role="separator"`, `aria-orientation="vertical"`, `aria-label` ("Resize navigation" / "Resize issue panel"), `tabIndex={0}`, and `aria-valuenow/min/max`. **Keyboard resize:** ArrowLeft/ArrowRight nudge width by 16px (Shift+Arrow by 48px), clamped, committing on each keypress. `Home`/`End` jump to min/max. This makes resize usable without a mouse and satisfies WCAG.

---

## Task 1 — Resizable + collapsible NavRail

### Behavior & rules
- The `<nav>` in `NavRail.tsx` currently hardcodes `width: 214`. Replace with a controlled width from `useResizable`, defaulting to **220px** (current 214 is fine; round to 220 for a cleaner default; keep 214 if preferred — either is acceptable, pick 220).
- **Expanded range: min 180px, max 320px.** Justification: below ~180 the "New issue" label + longest nav labels ("System administration" lives in the footer menu, but "All projects"/"Backlog"/project-name headers) start truncating awkwardly; above ~320 the rail wastes space that belongs to content. 320 is a hard cap so it can't be dragged across the screen. Additionally cap `max` at `min(320, 40% of innerWidth)` so on a small window it can't dominate.
- **Collapse toggle:** a small chevron button (e.g. `PanelLeftClose`/`PanelLeft` from lucide) placed in the rail — recommended location: top-right of the brand row, or a dedicated affordance at the bottom above the user footer. When collapsed, the rail goes to a fixed **icon-only width of 60px**: brand mark "H" only (hide "Hamstrack" wordmark), "New issue" becomes an icon-only square (`+`), every `RailLink` shows only its icon (hide label), the project-name section header is hidden, and the user footer collapses to just the avatar. The collapse state is independent of the dragged width (collapsing does not lose the remembered expanded width; expanding restores it).
- **Tooltips when collapsed:** because labels are hidden, each icon must carry a `title` (native tooltip is acceptable for v1) so the rail stays navigable. The "New issue", each RailLink, and the user avatar get titles.
- **Drag affordance:** a 6px-wide invisible hit strip on the rail's right edge, `cursor: col-resize`, that shows a 2px teal (`var(--color-brand)`) line on hover and while dragging. Hit area is 6px but visually 2px — generous enough to grab, subtle at rest. Disabled (hidden) while collapsed (you resize by expanding first).
- **Persistence:** `railWidth` and `railCollapsed` in `uiPrefs`. On mount, read both; apply width only when expanded. Collapsing/expanding and finishing a drag persist immediately.

### Interaction with layout
- The rail is a flex child of `AppShell`'s outer flex row (`flex-shrink-0`). Changing its `width` naturally reflows the `flex:1` content column — no other change needed there. Keep `flex-shrink-0` so it never compresses below its set width.
- The collapse transition should animate `width` over **200ms `ease` (short band)**; wrap in `prefers-reduced-motion` (see Task 4's shared rule) so it snaps instantly for users who opted out. Labels should not reflow-flicker: fade/hide them at collapse (simplest: conditionally render label text on `collapsed`, accept a hard swap — acceptable, or fade opacity 120ms).

### Edge cases
- Window shrinks below where 40%-cap < current width → on `resize`, clamp down and persist the clamped value.
- Collapsed state + very narrow viewport: 60px is always fine.
- Dragging the rail while a card drag is in progress is impossible (different pointer gesture; the handle only starts a resize on pointerdown on the handle itself). No conflict with board DnD (that lives in the content column).
- New user with no `uiPrefs` → defaults (220px, expanded).

### Complexity
**Medium** — new shared hook + persistence + a genuine collapsed-mode restyle of an existing component with many inline styles; more surface than a pure width drag.

---

## Task 2 — Resizable IssueSidePanel (Board only)

### Behavior & rules
- `IssueSidePanel.tsx` hardcodes `width: 440; minWidth: 440`. Introduce a controlled width **only when the panel is used on the Board**. Cleanest approach: pass an optional `width?: number` + `onWidthChange?: (n)=>void` (and a `resizable?: boolean`) prop into `IssueSidePanel`; the Board owns the `useResizable` state and renders the drag handle. Backlog keeps calling the panel without those props → falls back to the current fixed 440.
- **Range: min 360px, max 720px, default 440px.** Justification: the panel's internal grid is `grid-cols-2` with form controls; below ~360 the two-column metadata grid and date inputs get cramped; above ~720 the reading line-length is fine but the board is starved. Also cap `max` at `min(720, 55% of innerWidth)` so on a small window the panel can't crowd out the board entirely.
- **Drag affordance:** a 6px hit strip on the panel's **left** edge (`borderLeft` side), `cursor: col-resize`, 2px teal line on hover/drag. Same visual language as the rail handle for consistency.
- **Persistence:** `boardPanelWidth` in `uiPrefs`, committed on pointer-up and on keyboard nudge. Read on Board mount; applied to the panel's width when it opens.
- No collapse button (per request). Closing is the existing `X` / toggle.

### Interaction with layout
- On the Board, the panel is a flex sibling of the `flex:1` main content (`BoardPage.tsx` line ~251). Giving the panel an explicit `width` + `flex-shrink-0` makes the main content (and thus the kanban zone) absorb the remaining space — this is exactly the fluidity Task 3 needs. Change `minWidth:440` to the dynamic min so a resized-narrow panel isn't overridden.
- The handle lives on the panel's left border. Because the panel sits to the right of a horizontally-scrolling kanban zone, the handle must be positioned on the panel element itself (not overlapping the scroll area), so it never intercepts board scroll/drag.

### Edge cases
- Panel open + user drags the rail at the same time: impossible with one pointer; independent handles.
- Window resize below the 55% cap → clamp + persist (shared hook behavior).
- Switching issues (`key={openIssueNumber}` remounts the panel) must **not** reset width — width lives in the Board's state / `uiPrefs`, above the remount boundary. Confirm the `useResizable` state is held in `BoardPage`, not inside `IssueSidePanel`.
- Backlog: unaffected (no handle, fixed 440).

### Complexity
**Medium** — needs a small prop-drill into a large shared component and careful placement of state above the remount key; less restyling than Task 1 but the "Board-only" conditional and the remount-boundary detail add real thought.

---

## Task 3 — Fluid kanban zone

### Behavior & rules
The board should adapt to viewport width and reflow gracefully as the panel opens/closes.

- **Current state is already mostly fluid:** the kanban zone is `flex-1 ... overflow-x-auto` and columns use `flex:'1 1 280px'; minWidth:240; maxWidth:420`. When the panel opens as a flex sibling, the main content already shrinks. The gaps to close are: (a) making the reflow *smooth* (Task 4 covers the timing), (b) sensible column sizing across viewport sizes, and (c) verified behavior at small widths.
- **Column sizing:** keep `flex: '1 1 <basis>'` with `minWidth` and `maxWidth`. Recommend lowering `minWidth` from 240 to **220** and keeping `maxWidth: 420`, so more columns fit before horizontal scrolling kicks in on mid-size laptops. Columns grow to fill wide viewports (up to 420 each) and shrink to 220 before the zone starts scrolling horizontally — the existing `overflow-x-auto` handles the overflow.
- **Panel-open reflow:** because the panel now takes an explicit width (Task 2) and the main content is `flex:1 minWidth:0`, the kanban zone automatically narrows when the panel opens and widens when it closes. Ensure the main-content column has `minWidth: 0` (it does via the wrapper) so flex can actually shrink it and the `overflow-x-auto` engages rather than overflowing the viewport.
- **Small viewports (< ~640px):** the two-level shell + a 220px column + panel is too much. Acceptable v1 behavior: the board horizontally scrolls (already supported); the panel, when open on a narrow viewport, should cap at 55% (Task 2) so at least part of one column stays visible. A full mobile redesign (panel as full-screen overlay) is **out of scope** — flag as Open Question Q2.

### Edge cases
- Zero statuses (empty workflow) — existing empty rendering unaffected.
- Many statuses (e.g. 8+) on a narrow viewport → horizontal scroll; columns clamp at `minWidth`. Verify the drag-over highlight and DnD still work while scrolled.
- Dragging a card while the zone is horizontally scrolled: native HTML5 DnD auto-scroll is browser-dependent; note as a known limitation, not a regression (unchanged by this work).

### Complexity
**Light** — mostly tuning existing flex values and confirming `minWidth:0` chains; the heavy lifting is verification across widths, not new code.

## Task 4 — Smooth issue open/close on the Board

### Behavior & rules
Today `{panelOpen && <IssueSidePanel/>}` mounts/unmounts instantly — a hard layout jump. Make it a drawer-style slide + the column reflow ease.

- **Enter:** panel slides in from the right and the columns reflow to make room. **Exit:** panel slides out to the right and columns reflow back.
- **The hard part:** an unmount can't animate. Two acceptable approaches — **recommendation: keep the panel mounted during exit** via a short "closing" state:
  1. When closing, set `closing=true`, play the exit transition (~200ms), then actually unmount (drop `openIssueNumber`) on `transitionend`/`setTimeout`. During the same window animate the panel's `width`/`transform` to collapsed.
  - Alternative (simpler, slightly less clean): wrap the panel container in a fixed-width animated shell whose `width` transitions 0↔panelWidth; the kanban zone reflows because the shell width animates. `transform: translateX` alone won't reflow the columns (it's off the layout flow), so **animate `width` (or `margin-right`) to get the reflow**, optionally combined with a `translateX` for the slide-in feel.
- **Timing:** DESIGN.md "short" band — **200ms**, enter `ease-out`, exit `ease-in` (or the drawer `cubic-bezier(.32,.72,0,1)` for the move). Do not exceed 260ms.
- **`prefers-reduced-motion`:** must honor it. Add a single shared guard — recommend a tiny `useReducedMotion()` hook (or a CSS `@media (prefers-reduced-motion: reduce)` rule that zeroes the durations). When reduced motion is on, the panel appears/disappears instantly (duration 0) — same for the rail collapse (Task 1) and the column reflow.
- Animating `width` triggers layout on each frame; for ~200ms over one panel this is fine performance-wise. Prefer `transform` for the panel's own slide and `width` on the reflow spacer only if profiling shows jank (don't over-engineer — plain `width`/`margin` transition is acceptable at this scale).

### Edge cases
- Rapidly toggling issues (open A, immediately open B): since switching issues keeps the panel open (only `key` changes), there's no exit animation between issues — only a content remount. Good; no flicker. Only true close (→ undefined) plays the exit.
- Close mid-drag of a card: closing is a click on `X`, not during a DnD gesture; no conflict.
- Reduced-motion users: instant, no timers left dangling (clear the close timeout on unmount).
- Panel resized very wide, then closed: exit animates from the current width to 0 — reads fine.

### Complexity
**Medium** — the mounted-during-exit dance (closing state + timer + transitionend, cleaned up on unmount) is the fiddly bit; the animation itself is trivial. If the team accepts a plain fade/instant-unmount for exit, this drops to **Light**, but that misses the "smooth close" the request explicitly asks for.

---

## Data model / API / DC-Cloud impact

None. No tables, endpoints, DTOs, env vars, or profile gating. Zero backend involvement. `openapi.yaml` and `api-*.md` are untouched. Tenancy and security surfaces are unaffected (no data crosses the client boundary).

## Frontend files touched (summary)

- `src/main/frontend/src/hooks/useResizable.ts` — **new** (shared resize hook, pointer + keyboard).
- `src/main/frontend/src/uiPrefs.ts` — **new** (per-user localStorage prefs, mirrors `recentProjects.ts`).
- `src/main/frontend/src/hooks/useReducedMotion.ts` — **new** (or a CSS media rule).
- `src/main/frontend/src/components/NavRail.tsx` — resizable + collapsible (Task 1).
- `src/main/frontend/src/pages/BoardPage.tsx` — owns panel width state + resize handle, fluid columns, open/close animation (Tasks 2–4).
- `src/main/frontend/src/pages/IssueSidePanel.tsx` — accept optional `width`/`onWidthChange`/`resizable` props; dynamic min-width (Task 2). Backlog usage unchanged.

## Acceptance criteria (cross-cutting)

- Rail width persists across reload; collapsed state persists; both are per-user (different account in same browser sees its own).
- Rail cannot be dragged narrower than 180 or wider than 320 (or 40% of window, whichever is smaller); collapsed rail is 60px, icon-only, with tooltips.
- Board issue-panel width persists; clamped to [360, 720] and ≤55% of window; not reset when switching issues; Backlog panel unchanged (fixed 440).
- Board columns fill wide viewports, clamp and horizontally scroll on narrow ones; opening the panel narrows the columns and closing widens them.
- Opening/closing an issue on the Board animates in ≤260ms; `prefers-reduced-motion` disables all four animations.
- Keyboard: both handles are focusable, Arrow keys resize, Home/End jump to bounds; no text is selected while dragging.
- No console errors; no regression in card drag-and-drop or workflow transition rules.

## Open questions (with recommended defaults)

- **Q1 — Collapse toggle placement:** brand-row chevron vs bottom affordance. **Recommend:** a chevron at the bottom of the rail just above the user footer (common pattern, out of the way). Decide during build.
- **Q2 — True mobile layout:** should the panel become a full-screen overlay under ~640px? **Recommend:** out of scope for these four; file a separate issue. v1 keeps horizontal scroll + 55% cap.
- **Q3 — Resizable panel on Backlog too:** the request scopes tasks 2 & 4 to the Board. **Recommend:** ship Board-only; if it lands well, a one-line follow-up passes the same props from `BacklogPage`. Highest-value now is the Board.
- **Highest-risk assumption (flagged):** that animating `width`/`margin` for the reflow performs acceptably and that a plain hand-rolled pointer hook (no library) covers all cases cleanly, including the mounted-during-exit close. If close-animation cleanup proves flaky, fall back to instant unmount on close (still animate open) — a documented, acceptable degrade.
