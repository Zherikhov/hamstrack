# Issue detail view — redesign spec

Status: proposed (2026-08-13) · Owner: systems-analyst · Type: **frontend-only** (no backend / API / schema / config change) — but now adds **one client-side dependency** (markdown renderer) and **one new SPA route** (full-page issue), see Decisions locked.

Redesigns `src/main/frontend/src/pages/IssueSidePanel.tsx` — the right-hand drawer that shows an issue over the Board (resizable) and the Backlog (fixed width). The rebuild collapses the current four-tab + separate-edit-mode structure into a **single continuous scroll** that inlines details, description, sub-issues, attachments and activity, with a **lightweight in-panel jump nav** that appears only when the issue is tall enough to need it. Everything must stay inside "Beacon" (see `DESIGN.md`) and reuse the existing tokens and building-block components.

---

## Decisions locked (2026-08-13, from the user)

The four open questions were answered — this **overrides** the recommended defaults in §12 where they differ, and the sections below (§1, §4.3, §4.4, §4.8, §4.9, §6, and the new §6.5) reflect these:

1. **Q2 — Status editing: FILTER to allowed transitions** (not "list all + 422"). The inline Status editor shows only statuses reachable from the current one per the workflow. The data is already present: `ProjectConfig.transitions` (`TransitionRule[]`) is returned by the config endpoint, and `BoardPage.isMoveAllowed(from, toStatusId)` already encodes the exact semantics (source-specific rules + `fromStatusId: null` wildcards grant). **Extract `isMoveAllowed` into a shared helper** (e.g. `src/lib/transitions.ts`) and thread `transitions` into the panel's props (the panel today receives `statuses`/`priorities`/`fields` from config but not `transitions` — add it). This *removes* the highest-risk assumption: no more surprise 422s. The server 422 stays as a backstop only.
2. **Q1 — Activity order: NEWEST → OLDEST.** The merged feed renders most-recent first (newest at the top of the Activity section). The sticky composer stays pinned at the panel bottom; posting a comment prepends it to the top of the feed and clears the composer (scroll the feed top into view on post).
3. **Q3 — Markdown: INCLUDE NOW.** Description and comment bodies render as Markdown; the composer/description editors get a lightweight formatting affordance. Bodies are still **stored as-is plain text** (no backend/DTO change) — Markdown is a **render + authoring** concern only. See the new §6.5 for the dependency, the XSS/sanitization requirement, and the security-review mandate.
4. **Q4 — Full-page issue route: INCLUDE NOW.** Add a full-page issue view at a route like `/browse/:key` (final path TBD in build) reusing the same single-scroll layout, for deep-linking/sharing and very large issues. The drawer and the page share the body component. See the new §6.6.

---

## 0. Problem & goal

Today an issue is split across four tabs (Details / Comments / Files / History) **and** a wholly separate edit render toggled by an "Edit" button. That means: to read a comment you leave the details you were looking at; to fix a typo in the title you enter a different screen and lose your place; a small three-field issue forces the same tab-hunting as a 40-comment epic. The two biggest costs are **context loss** (tab switching hides sibling information) and **mode friction** (view vs edit are different layouts).

**Goal.** One scrollable space where — for a *small* issue — the whole thing (metadata, description, files, comments) is visible or one flick away, and — for a *large* issue — the user can still jump straight to the part they care about (typically "the comments at the bottom of a long thread") without scrolling past everything. Editing happens **in place**: click a value, change it, it saves — no separate edit mode. Success = fewer clicks to both read and change an issue, no lost scroll position, and a layout that visibly matches the app.

---

## 1. Scope

**In scope (all frontend, single component + small helpers):**
- Replace the tabbed body with **one vertical scroll**: header → title → metadata → description → custom fields → sub-issues → attachments → **activity (comments + history)**.
- Kill the separate edit mode. Convert every field to **inline editing** (click-to-edit / focus-to-edit) that saves on commit, preserving the existing partial-diff PATCH + optimistic-lock (`version`) semantics.
- Add an **in-panel jump nav** (sticky sub-header) that appears only past a content-height threshold, letting the user jump to Description / Sub-issues / Files / Activity.
- Inline attachment add (drag-and-drop onto the panel + keep an explicit button).
- Keep comment composer + `@mention` autocomplete; move the composer to a **sticky footer** so it's reachable regardless of scroll.
- Merge Comments and History into one **Activity** section with a filter toggle (All / Comments / History), preserving both today's renderings; **newest-first** order (locked Q1).
- **Filter the inline Status editor to allowed workflow transitions** (locked Q2) via a shared `isMoveAllowed` helper + `transitions` threaded into the panel.
- **Markdown rendering + a lightweight formatting affordance** for description and comment bodies (locked Q3, see §6.5) — client-side only, bodies still stored plain.
- **A full-page issue route** reusing the single-scroll body component (locked Q4, see §6.6).
- Beacon restyle throughout (tokens only, no hardcoded hex except where a token genuinely doesn't exist — e.g. the existing history diff red/green, see §4.7).

**Out of scope / non-goals:**
- **No backend, API, DTO, migration, or config change.** The component already has every endpoint it needs (`apiGetIssue`, `apiUpdateIssue`, comments/attachments/history/children/members). This redesign consumes them differently; it does not add or alter any. **Markdown is stored verbatim (plain text) — no body-format field, no server-side rendering or sanitization change.**
- No change to permissions, workflow/transition rules (we only *surface* the existing transition rules, we don't change them), hierarchy adjacency rules, or the delete flow's semantics.
- **One new client dependency is now allowed** (a small, well-maintained Markdown renderer — see §6.5); everything else (mention autocomplete, drag-resize) stays hand-rolled.
- No real-time comment/activity streaming into the open panel.
- No mobile-specific form factor (the drawer already inherits the shell's >960px assumption).

---

## 2. Actors & permissions

Any authenticated workspace member who can open the panel. **All permission logic is unchanged and already enforced by the backend** — the redesign only changes *where* controls sit, never *who* may use them:
- Inline edits go through the same `PATCH …/issues/{n}` a MANAGER-or-not user can already call; the server is the authority (a 403/404/409/422 surfaces as an inline error exactly as the save handler does today).
- Delete stays MANAGER-gated on the server; the trigger stays in an overflow menu (§4.1).
- Comment delete stays author-only (`user.id === c.authorId`), attachment delete stays uploader-or-MANAGER — both already computed client-side and enforced server-side.
- Tenant scoping is entirely in the API layer (`wsId`/`projectId` in every path). The drawer reshuffle introduces **no** new tenancy surface. **Exception:** the full-page route (§6.6) *could* if it resolves issues by global key — the spec mandates keeping resolution workspace/project-scoped so no new surface is created; if a key-lookup is added anyway, a `tenancy-reviewer` pass is required. Otherwise no repository/service/query is touched.

---

## 3. Competitor research → decisions

Distilled from Jira (2024 redesign), Linear, Asana, ClickUp, GitHub Issues, Notion, Height, Shortcut, and YouTrack. Sources at the end.

**Strengths to adopt**
- **Linear / GitHub / Notion — single continuous scroll, no content tabs.** Description is a large calm area; properties sit in a compact metadata block; activity/comments live at the **bottom** in one feed. Praised as "clean, dense without clutter, everything at a glance." This is the dominant modern pattern and directly serves requirement #1.
- **Linear / Asana — inline click-to-edit, no edit mode.** Asana's click-to-edit title/fields are explicitly praised; ClickUp is explicitly *criticized* for making you "open a modal, wait, click ellipsis then a pencil just to change a name." Kill our separate edit mode.
- **GitHub / Linear — activity is a single chronological feed** with comments and system events interleaved (or filterable). Users like reading comments "in context" with the events around them.
- **Jira / Linear — activity filter (All / Comments / History).** Lets a user who only wants the conversation hide the noise of field-change events. Adopt as our Activity toggle.
- **Notion / GitHub — sticky comment composer** at the bottom so "add a comment" is always reachable.
- **YouTrack — a filterable activity stream** (its issue "Activity" pane lets you toggle which event kinds show — comments only, or comments + changes + VCS + work items). This is the strongest precedent for our All / Comments / History toggle and directly serves the "reach the comments of a long issue fast" requirement: a user drowning in field-change noise flips to *Comments only*. Adopt the filter; keep our three-way toggle simple (not YouTrack's many event categories).
- **YouTrack — keyboard-first field editing on the issue page.** Single-key shortcuts and the "Command" action let power users retype a field or run several changes without a mouse. We already lean keyboard-friendly (Enter/Escape/Cmd+Enter in §5.2); treat a full command palette as an *optional* future nicety (Open Question / follow-up), not v1 — but the takeaway (editing should never require a mouse) is adopted.

**Weaknesses to avoid**
- **Jira (pre-2024) — clutter from 200 fields dumped on one screen.** The 2024 redesign helped but "underlying complexity remains." Guard: show **only filled** fields by default; never render an empty field row just because the field exists on the set (matches today's `fieldValues[f.id] !== undefined` filter). Empty fields are reachable via an "Add field value" affordance, not always-on.
- **ClickUp — multi-step editing.** Every inline edit must be **one interaction to open, one to commit** — no nested menus to reach a value.
- **Long-thread scroll pain (Height / Shortcut / Figma-comments requests for collapsible/jump nav).** A long issue must offer a **jump affordance**; users repeatedly ask for a way to reach the end of a long feed without scrolling. This drives our jump nav + sticky composer.
- **Tabs hide siblings** — the core complaint that motivated this task. No content tabs in the result.
- **YouTrack — powerful but dated/cluttered, steep learning curve.** Reviewers consistently praise YouTrack's flexibility while criticizing a busy, information-dense UI that feels less polished than Linear and buries newcomers under options/commands. Guard: we take its *filterable activity* and *keyboard editing* ideas but NOT its density — keep the metadata block compact, hide empty fields (§3 anti-clutter guard), and make every affordance discoverable by click, with keyboard as an accelerator rather than a prerequisite.

### The three load-bearing decisions
1. **Single continuous scroll, zero content tabs** (replaces the 4 tabs). Rationale: Linear/GitHub/Notion consensus + requirement #1 (small issue fully reachable).
2. **Inline editing everywhere; delete the separate edit mode.** Rationale: Asana-praised, ClickUp-criticized; removes the "lose your place" mode switch.
3. **Height-threshold jump nav + sticky composer, not tabs, for large issues.** Rationale: satisfies requirement #2 (fast jump to comments of a long issue) without reintroducing the tab clutter we're removing; the affordance *only appears when needed* so small issues stay pristine.

We deliberately do **not** copy Jira's UI, naming, field layout, or its right-sidebar-metadata split; our metadata is an inline compact grid under the title (closer to GitHub/Notion), tinted with Beacon tokens.

---

## 4. Recommended layout

One scroll container. Regions top-to-bottom: **sticky header** → **title** → **metadata grid** → **[jump nav, conditional]** → **Description** → **Custom fields** → **Sub-issues** → **Attachments** → **Activity** → **sticky comment composer**. Section headings (Description / Sub-issues / Files / Activity) are the jump anchors.

### 4.0 ASCII wireframe — SMALL issue (short description, 0–1 comments)
No jump nav (below threshold). Everything reachable in ≤1 scroll flick.

```
┌───────────────────────────────────────────────┐
│ PAY-131            ·· (overflow ⋯)        ✕     │  sticky header (key, ⋯ menu, close)
├───────────────────────────────────────────────┤
│ Fix login redirect loop            [click=edit]│  title — inline text, click to edit
│                                                │
│  Status ▾  Priority ▾   Type ▾   Assignee ▾   │  metadata grid — each cell is a
│  [In Progress] [High]  [Bug]     [◑ V. K.]    │  click-to-open inline control
│  Reporter        Due date ▾   Parent ↳         │
│  ◔ A. Petrov     Aug 20       PAY-40 · Epic    │
│                                                │
│  DESCRIPTION                                    │  section (no jump nav needed)
│  Users hitting /login twice get bounced …      │  click anywhere → inline textarea
│                                                │
│  FIELDS  · Story points 3 · Severity S2        │  filled custom fields only
│                                                │
│  SUB-ISSUES                        + Sub-task  │  roll-up bar + child rows (if any)
│  ▓▓▓▓░░░░ 1 of 3 done                          │
│  • PAY-132  Add guard        [To Do]           │
│                                                │
│  FILES                             + Attach    │  drop zone; button; list
│  (drop files here)                             │
│                                                │
│  ACTIVITY            [All][Comments][History]  │  merged feed, filter toggle
│  ◔ A.Petrov  changed Status  To Do → In Prog…  │
│  ◑ V.K.  "Looks good, shipping."   Aug 12      │
├───────────────────────────────────────────────┤
│ [Add a comment…  @mention                ] Post│  sticky composer footer
└───────────────────────────────────────────────┘
```

### 4.0b ASCII wireframe — LARGE issue (long description, many comments/children)
Jump nav appears (content exceeds threshold). Sticky sub-header under the main header.

```
┌───────────────────────────────────────────────┐
│ PAY-131            ·· (overflow ⋯)        ✕     │  sticky header
├───────────────────────────────────────────────┤
│ Description · Sub-issues(8) · Files(3) · Activity(41)│ ← sticky JUMP NAV (anchors)
├───────────────────────────────────────────────┤
│ Migrate auth service to OIDC       [click=edit]│  ← scrolls under the jump nav
│  Status ▾ … metadata grid …                    │
│                                                │
│  DESCRIPTION ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔ │  (target of "Description")
│  …long multi-paragraph body…                   │
│  … … … … … … … … … … … … … … … … … … … … … …  │
│  SUB-ISSUES (8)                    + Sub-task  │  (target of "Sub-issues")
│  ▓▓▓▓▓▓░░ 5 of 8 done                          │
│  • … 8 child rows …                            │
│  FILES (3)                         + Attach    │  (target of "Files")
│  • design.pdf  • trace.log  • diagram.png      │
│  ACTIVITY (41)      [All][Comments][History]   │  (target of "Activity")
│  … 41 interleaved items …                      │
├───────────────────────────────────────────────┤
│ [Add a comment…  @mention                ] Post│  sticky composer — reach it via
└───────────────────────────────────────────────┘   jump-nav "Activity" or scroll
```

**Clicking a jump-nav item** scrolls its section heading to just under the sticky header (use `scrollIntoView` + `scroll-margin-top` on headings equal to header+nav height — see the `scroll-margin-top` note in research). The active section is highlighted in teal (`--color-brand`) via an `IntersectionObserver` on the section headings (or a simpler scroll handler — implementer's choice, IO preferred for perf).

### 4.1 Sticky header (always visible)
- Left: issue **key** in `mono`, `--color-text-muted` — clicking it copies the key (nice-to-have; keep as plain text if trimming scope).
- Right: an **overflow `⋯` menu** (replaces the always-visible Edit + Delete buttons — Edit is gone entirely; Delete moves inside, still `--color-error`, still `window.confirm`). Then the **✕ close**. Keeping delete behind `⋯` de-clutters the header and prevents mis-clicks.
- Header keeps `borderBottom: 1px var(--color-border)`, `flex-shrink:0`, ~48px tall.

### 4.2 Title (inline edit)
- Renders as an `h2` (14–16px/600, `--color-text`). Click (or Tab into) → becomes a borderless auto-growing text input seeded with the current title; **commit on blur or Enter**, **cancel on Escape**. Empty title is rejected (revert to previous, show the existing inline error). On commit → diffed PATCH (title only if changed).

### 4.3 Metadata grid (inline edit, 2-col → 1-col responsive)
The current view/edit split disappears: each cell shows the *value as a control*.
- **Status / Priority / Type / Assignee / Parent / Due date** each render their current value using the existing display components (`StatusBadge`, `PriorityBadge`, type dot+name, `Avatar`, `ParentChip`, mono date). Clicking a cell opens the existing `Select` popover (already Beacon-styled) / date input in place; choosing a value fires the diffed PATCH immediately (per-field save — see §5.1).
- **Reporter** stays read-only (it is today).
- **Status** — the inline editor **lists only allowed transitions** from the issue's current status (locked Q2). Use a shared `isMoveAllowed(from, toStatusId, transitions)` helper extracted from `BoardPage` (same wildcard/source-rule semantics) against the `transitions` now threaded into the panel; the current status stays shown/selected. The server 422 remains only a backstop. (This resolves the former highest-risk assumption — no more surprise 422s on a normal status change.)
- **Parent** keeps the full hierarchy adjacency logic already in the file (`eligibleParentTypeIds`, `parentConflict`, lazy `candidateParents` load). Because there's no more "enter edit mode" trigger, load `candidateParents` when the parent cell is first opened (replace the `editing &&` gate with an "opened parent editor" gate).
- **Type change** re-runs the same parent-conflict guard; if a type change would strand the current parent, block the save and show the existing message inline on the parent cell.
- Grid: `grid-cols-2` at comfortable width; **collapses to `grid-cols-1` below ~380px** panel width (see §7). Each cell: 12px muted label above the control, exactly as the current view mode.

### 4.4 Description (inline edit, Markdown)
- Filled: rendered as **Markdown** (§6.5), `--color-text`. Empty: a muted "Add a description…" placeholder button.
- Click anywhere in the block → in-place `Textarea` (auto-grow, min 4 rows) seeded with the **raw Markdown source**; the editor shows the lightweight formatting affordance (§6.5). **Commit on blur or Cmd/Ctrl+Enter**, cancel on Escape. Diffed PATCH stores the raw text verbatim. (Blur-commit chosen over an explicit Save button to match the inline model; Cmd+Enter is the discoverable shortcut.)

### 4.5 Custom fields
- **Filled fields only**, in config display order, using `FieldValueDisplay`. Each is click-to-edit → `FieldInput` inline (both already exist and are Beacon-styled; `USER` options = workspace members). Diffed against original via the existing `changedFields()` logic (keep it — it already does JSON-null-clears and required-field protection).
- **Empty but available** fields: hidden behind a single **"+ Add field"** menu listing the set's unfilled fields; picking one reveals its `FieldInput`. This is the anti-clutter guard (§3). Required fields that are somehow empty on load surface inline with the existing `missingRequired` treatment.
- `TEXTAREA`/`MULTI_SELECT` still span full width (`col-span-2`).

### 4.6 Sub-issues
- Unchanged content: roll-up `ChildrenProgress`, child rows (type dot + key + title + `StatusBadge`, click → `onOpenIssue`), and the "+ Create sub-task" affordance (`openCreateIssue({parentId,…})`). Section heading "Sub-issues (N)" doubles as a jump anchor. Keep the `canHaveChildren || children.length>0` visibility gate.

### 4.7 Attachments ("Files")
- List identical to today (paperclip, filename→download, size + uploader + date, uploader/MANAGER delete on hover).
- **New: whole-section drop zone.** Dragging files anywhere over the panel body shows a teal dashed overlay ("Drop to attach"); drop uploads via the existing `apiUploadAttachment` loop (support **multiple** files → sequential/`Promise.all` uploads, each appended on success; keep per-file error handling). **Keep the explicit "+ Attach file" button** for non-drag users. Reuse the current `uploading`/`fileError` states (make `uploading` a count or keep boolean + a small spinner per in-flight file — boolean is fine for v1).

### 4.8 Activity (comments + history merged)
- Section heading "Activity (N)" + a segmented toggle **All / Comments / History** (default **All**). N = comments + history count under the active filter.
- Build one array of `{kind:'comment'|'history', at, node}` sorted by timestamp **descending — newest first** (locked Q1). The most recent event sits at the top of the Activity section. The composer stays pinned at the panel bottom; **posting a comment prepends** the new item to the top of the feed, clears the composer, and scrolls the feed's top into view so the author sees their comment land.
- **Comment** items: existing avatar + author + date + hover-delete (author only). Body renders as **Markdown** now (§6.5) instead of `whitespace-pre-wrap`. `@mentions` render as before (plain `@Name` text unless/until a mention-linking effort lands — no change to mention behavior here).
- **History** items: existing dot + "X changed <Field>" + red/green old→new diff chips. The diff chip colors (`#fee2e2/#991b1b`, `#dcfce7/#166534`) are the one place hardcoded hex is currently used — **acceptable to keep** (semantic diff red/green, no exact token). Optional: map to `--color-error`/`--color-success` tints if a clean mapping exists; not required.

### 4.9 Sticky comment composer (footer, Markdown)
- Pinned to the panel bottom (`flex-shrink:0`, top border), always visible regardless of scroll — this is how a user on a 41-comment issue posts without scrolling. Contains the existing `Textarea` + `@mention` autocomplete popover (position it **above** the textarea since it's at the panel bottom — the current popover already anchors `bottom:100%`, so it's correct) + Post button. Reuse `handleCommentChange` / `insertMention` / `handlePostComment` (post now **prepends** to the newest-first feed, §4.8).
- **Markdown authoring affordance** (§6.5): a minimal toolbar (bold / italic / link / code / list) that wraps the current selection, plus an optional Write/Preview toggle. Keep it lightweight — do not pull in a heavy WYSIWYG editor; a textarea + selection-wrapping helpers + the same renderer used for display is enough. The same affordance is shared by the Description editor (§4.4).

---

## 5. Interaction details

### 5.1 Save semantics (preserve exactly)
- **Per-field commit.** Each inline editor commits **its own** diffed PATCH on blur/Enter (not a batch Save). The PATCH body is built with the same diff helpers already in the file (`changedFields()`, the `assigneePatch`/`duePatch`/`parentPatch` clear-flag conventions) — reduced to the single field being edited. Always include `version` from the current `issue`.
- **Optimistic lock.** On a 409 (version conflict) surface an inline, non-destructive banner ("This issue changed elsewhere — reloading") and re-fetch via `loadIssue()`; the edited value is discarded (server wins) — safe default, avoids clobbering. Keep the current `setError` inline treatment for other failures (422 required/hierarchy, 404).
- **After each successful save**, update local `issue` from the response and run the same cache invalidations the current `handleSave` does (`['issues', wsId, projectId]` and `['issue', wsId, projectId]`), plus refresh history + children when a field that affects them changed (type/parent/status). Refreshing history after every field edit keeps the Activity feed live without streaming.
- **No global "Save changes" / "Cancel" buttons** — they disappear with edit mode. (This is the visible behavioral change; call it out in QA.)

### 5.2 Inline edit affordances
- Hover on an editable value shows a subtle affordance (cursor `pointer`, faint `--color-surface-2` hover fill, optional tiny pencil on hover). Keyboard: values are focusable (`tabIndex`), Enter/Space opens the editor.
- Open editor: Escape cancels (revert), Enter (single-line) / Cmd+Enter (multiline) commits, blur commits. Only **one** editor open at a time is not required, but opening a new one should commit/cancel the previous cleanly.

### 5.3 Attachments
- Drag-drop (§4.7) + button. Multiple files supported. Downloads via existing `apiDownloadAttachment`.

### 5.4 Comments & mentions
- Composer in sticky footer; `@` triggers the existing member autocomplete (`filteredMembers`, `startsWith`, top 6); selecting inserts `@Name `. No change to trigger logic.

### 5.5 Jump nav mechanics
- Appears when scrollHeight of the body exceeds a threshold (recommend: **body content taller than ~1.5× the panel viewport height**, measured via a `ResizeObserver` on the scroll content, re-checked on width/data change). Below threshold it's absent (small issue stays clean).
- Items reflect present sections only (e.g. hide "Sub-issues" when the issue can't/doesn't have children; hide "Files" when zero attachments *and* not currently a useful target — recommend always show Files/Activity, conditionally show Sub-issues).
- Counts in the nav (e.g. `Activity(41)`) give the "how big is this" signal Height/Shortcut users want.

---

## 6. Small vs large behavior — concrete rule

| Signal | Small issue | Large issue |
|---|---|---|
| Jump nav | **Hidden** (body ≤ ~1.5× viewport) | **Shown** as sticky sub-header |
| Activity section | Rendered expanded inline | Rendered expanded inline (reachable via jump nav) |
| Composer | Sticky footer (always) | Sticky footer (always) |
| Sub-issues / Files | Inline, expanded | Inline, expanded |

Rule of thumb, stated for the builder: **the layout never changes structure between small and large — only the jump nav toggles in/out based on a height threshold, and section headings gain counts.** No section auto-collapses by default (collapsing hides content, which is the tab problem in disguise). *Optional enhancement, not required for v1:* let the user manually collapse a section by clicking its heading (state per-section, not persisted) — offer only if cheap; do not auto-collapse.

Threshold detail: measure with `ResizeObserver`; debounce; compare `content.scrollHeight` to `viewport.clientHeight * 1.5`. Hysteresis (show at 1.5×, hide at 1.3×) avoids flicker when editing near the boundary.

---

## 6.5 Markdown rendering & authoring (locked Q3)

Description and comment bodies become **Markdown**. This is purely a client concern — bodies are still POST/PATCHed and stored **verbatim as today** (plain text in the same columns); we only change how they're *rendered* and add an authoring affordance. Backend, DTOs, and storage are untouched.

- **Renderer.** Use a small, well-maintained, **HTML-safe-by-default** renderer — recommend `react-markdown` (+ `remark-gfm` for tables/task-lists/strikethrough/autolinks). `react-markdown` does **not** render raw HTML unless you add `rehype-raw`, so **do not add `rehype-raw`** — that default is our primary XSS defense (user-authored bodies must never become live HTML). If any raw-HTML passthrough is ever wanted, it must go through `rehype-sanitize` with an allowlist — flag for `security-officer`.
- **Security (mandatory).** User-authored content rendered as Markdown is an injection surface. Requirements: no raw HTML; `link` renderer forces `rel="noopener noreferrer"` and constrains `href` schemes to `http/https/mailto` (drop `javascript:`/`data:`); images either disabled or same-origin/allowlisted (recommend **disable remote images** in v1 to avoid tracking-pixel/SSRF-adjacent concerns — decide in build). **A `security-officer` review of the render path is required before merge.**
- **Bundle.** `react-markdown`+`remark-gfm` is ~40–50KB gzipped. The panel is already lazy-loaded on the Board/Backlog routes; keep the renderer in that chunk (or lazy-load it) so it doesn't hit initial load. Note: this is the *only* new dependency this redesign introduces.
- **Authoring.** A minimal toolbar (bold/italic/link/inline-code/list) that wraps the textarea selection, shared by the Description editor and the comment composer (§4.4/§4.9). Optional Write/Preview toggle using the same renderer. No heavy WYSIWYG.
- **Styling.** Markdown output must be Beacon-tokenized (headings, `code`/`pre` in `--font-mono`/`JetBrains Mono`, links in `--color-brand`, lists/quotes with token colors). Add a scoped `.markdown-body` style block; do not let raw browser defaults leak in.
- **Backward compatibility.** Existing plain-text bodies render fine as Markdown (plain text is valid Markdown); the only visible change is that `#`, `*`, backticks etc. now format. Acceptable — call it out in QA.

## 6.6 Full-page issue view (locked Q4)

Add a standalone full-page issue view (route TBD in build, e.g. `/w/:wsId/p/:projectId/browse/:number` or a key-based `/browse/:key`) that renders the **same single-scroll body** as the drawer, for deep-linking, sharing, and comfortable reading of very large issues.

- **Shared component.** Extract the panel's body (everything except the drawer chrome: fixed width, `ResizeHandle`, `borderLeft`) into a reusable `IssueDetail` body used by *both* the drawer and the page. The drawer wraps it in the sliding/resizable shell; the page wraps it in a centered, max-width column (use inline `maxWidth` — **never** Tailwind `max-w-*`, per the shadow trap) inside `AppShell`.
- **Layout on the page.** No resize handle; comfortable reading width (~760–900px column, decide in build); the sticky header/jump-nav/composer become sticky within the page column. The 2-col metadata grid has room to stay 2-col.
- **Navigation.** Opening an issue from Board/Backlog still uses the drawer (fast, keeps board context). The page is reached by: a "open full page" / expand affordance in the drawer header (`⋯` or an expand icon), direct URL, and parent/child links when already on the page. Deep links (`/browse/KEY`) load the issue standalone.
- **Data & guards.** Same `apiGetIssue`/config calls; same tenancy scoping (`wsId`/`projectId` in the path). A missing/forbidden issue 404s exactly as elsewhere (never reveal existence). If a key-based route is chosen, it must still resolve within a workspace/project the user belongs to — do **not** introduce a cross-workspace key lookup that bypasses membership (would be a tenancy regression; keep the resolution workspace-scoped). **This is the one part of the redesign that touches routing/tenancy surface — a `tenancy-reviewer` pass is warranted if key-based resolution is added.**
- **Scope guard.** If building the page reveals a need for a new endpoint (e.g. resolve issue by global key), **stop and re-spec** — the "no backend change" promise breaks there and needs its own decision.

---

## 7. Responsive / width behavior

- **Board:** resizable drawer, bounds already set by the parent — `PANEL_MIN=360`, `PANEL_DEFAULT=440`, `PANEL_MAX=720`, clamped to ≤55% viewport (`BoardPage.tsx`). The left-edge `ResizeHandle` stays (its props are already threaded through). **No change to bounds** — but note the metadata grid must look right across 360→720.
- **Backlog:** fixed-width drawer (no `onResize` → no handle), currently 440-ish. Unchanged.
- **Metadata grid:** `grid-cols-2` is comfortable ≥ ~400px; **switch to `grid-cols-1` below ~380px** (container query or a width prop check — the panel already knows `width`; simplest is a JS breakpoint on `width ?? 440`). Long values (`Avatar` + name, `ParentChip`) already truncate; keep `min-w-0`/`truncate`.
- **Sticky elements:** header, jump nav, composer are `position: sticky`/flex-pinned within the panel — they must not overlap the `ResizeHandle` (handle is on the panel's left edge, sticky elements are full-width inside; fine). Ensure `scroll-margin-top` on section headings = header height + (nav height when shown) so jump targets aren't hidden.
- **Min usable width:** at 360px the 1-col grid + full-width description/composer all fit; verify the `@mention` popover and `Select` popover (both `position:fixed`, measured off the button rect) still place correctly.
- **`max-w-*` trap:** per CLAUDE.md, never use Tailwind `max-w-2xs..3xl` — use inline `maxWidth`. Applies to any width caps added (e.g. attachment filename, URL field display already do this).

---

## 8. Content inventory & information hierarchy

Every existing element mapped to the new single-scroll layout, in priority order (top = most prominent):

| # | Element | Source | New location | Edit |
|---|---|---|---|---|
| 1 | Issue **key** | `issue.key` | Sticky header left (mono) | read-only |
| 2 | **Title** | `issue.title` | Below header, h2 | inline text |
| 3 | **Status** | `issue.status` | Metadata grid | inline Select |
| 4 | **Priority** | `issue.priority` | Metadata grid | inline Select |
| 5 | **Type** | `issue.type` | Metadata grid | inline Select (+parent guard) |
| 6 | **Assignee** | `issue.assignee` | Metadata grid | inline Select (clear→unassigned) |
| 7 | **Due date** | `issue.dueDate` | Metadata grid | inline date (clear supported) |
| 8 | **Parent** breadcrumb | `issue.parentKey/…` | Metadata grid (`ParentChip`, click→open) | inline Select (adjacency-filtered) |
| 9 | **Reporter** | `issue.reporter` | Metadata grid | read-only |
| 10 | **Description** | `issue.description` | Description section | inline textarea |
| 11 | **Custom fields** (filled) | `issue.fields` / `fields` | Fields section | inline `FieldInput`; "+ Add field" for empties |
| 12 | **Sub-issues** + roll-up | `children` + `ChildrenProgress` | Sub-issues section | create sub-task; navigate |
| 13 | **Attachments** | `attachments` | Files section | drop-zone + button; hover-delete |
| 14 | **Comments** (+@mentions) | `comments` | Activity feed | composer footer; hover-delete |
| 15 | **History** | `history` | Activity feed | read-only |

Priority reasoning: identity + editable metadata first (what people scan and change most), then the narrative (description), then structured extras (fields), then relations (sub-issues), then artifacts (files), then the conversation/audit (activity) at the bottom next to the composer — matching the GitHub/Linear reading order and requirement #1's "everything reachable."

---

## 9. DC/Cloud implications

**None.** This is a client-side change against existing, profile-agnostic endpoints. No env var, no property, no profile gate, no storage/email/auth/billing touch. Attachments still go through the `FileStorage`-backed endpoints unchanged (local FS on DC, S3 on Cloud) — the panel is oblivious to which. The two locked additions stay deployment-agnostic: **Markdown** renders in the browser (no server render, no per-mode config) and the **full-page route** is an SPA route served identically in both modes. **No `dc-cloud-guard` action needed.** (Explicitly flagged per the mandate: nothing here diverges by deployment model.)

---

## 10. Out of scope (what this does NOT change)

- Backend: no controller/service/repository/DTO/entity/migration edits.
- API surface: no new/changed endpoints; **no `openapi.yaml` or `api-*.md` update** (nothing to sync — verify this holds during build; if a builder finds themselves adding an endpoint, stop and re-spec).
- Permissions & roles, workflow/transition rules (we *surface* existing transitions, don't change them), hierarchy adjacency rules, optimistic-locking mechanism (reused, not changed).
- **Body storage format** — Markdown renders client-side but bodies are stored **verbatim plain text** (no new format field, no server-side rendering/sanitization). See §6.5.
- Notifications, SSE, real-time updates into the open panel.
- Board DnD, Backlog table, create-issue modal (only the drawer's internals change; its props stay compatible — but note `transitions` is now also passed in, §4.3).
- **No new backend endpoint** — the full-page route (§6.6) must reuse existing workspace/project-scoped issue reads; adding a key-lookup endpoint is a re-spec trigger.

---

## 11. Acceptance criteria

A reviewer/QA can verify:

**Structure**
- [ ] The panel has **no content tabs** and **no "Edit" button**; Details/Comments/Files/History are all visible in one vertical scroll.
- [ ] Delete lives in the header `⋯` overflow menu, still confirms, still MANAGER-gated by the server (a non-MANAGER's delete is rejected and surfaced).
- [ ] For a **small** issue (short description, ≤1 comment) the jump nav is **absent** and metadata+description+files+activity are all reachable within one scroll.
- [ ] For a **large** issue the **sticky jump nav** appears; clicking Description / Sub-issues / Files / Activity scrolls that heading to just under the sticky header (not hidden behind it); the active section highlights teal.
- [ ] The **comment composer is sticky** at the bottom and reachable at any scroll position.

**Inline editing (each saves independently, preserves version)**
- [ ] Title: click → edit → Enter/blur saves; Escape reverts; empty title rejected.
- [ ] Status/Priority/Type/Assignee/Due date/Parent: click cell → change → immediate diffed PATCH with `version`; UI reflects server response.
- [ ] Type change that would strand the current parent is blocked inline with the existing message; parent picker only offers adjacency-valid parents.
- [ ] Due date and assignee can be **cleared** (clear-flags sent), and required custom fields **cannot** be cleared.
- [ ] Description: click → textarea → Cmd/Ctrl+Enter or blur saves; Escape reverts.
- [ ] Custom fields: only filled fields show by default; "+ Add field" reveals an unfilled field; edits diff correctly (JSON null clears).
- [ ] A **409** version conflict shows a non-destructive "changed elsewhere" notice and reloads the issue (no silent clobber).

- [ ] Status inline editor **offers only allowed transitions** from the current status (shared `isMoveAllowed` + config `transitions`); picking one saves; the current status stays selectable; an illegal move isn't even offered (server 422 is just a backstop).

**Attachments / activity**
- [ ] Dragging file(s) over the panel shows a teal drop overlay; dropping uploads them (multiple supported); the "+ Attach file" button still works; upload errors surface inline.
- [ ] Activity feed shows comments **and** history interleaved, **newest-first**; posting a comment prepends it to the top and clears the composer; the All/Comments/History toggle filters correctly; counts are accurate.

**Markdown (locked Q3)**
- [ ] Description and comment bodies render Markdown (headings, bold/italic, lists, links, code) via an HTML-safe renderer; **raw HTML in a body is NOT executed** (e.g. `<script>` / `<img onerror>` render inert or escaped).
- [ ] Links get `rel="noopener noreferrer"` and only `http/https/mailto` schemes resolve; `javascript:`/`data:` hrefs are neutralized; remote images behave per the build decision (recommended disabled).
- [ ] The editor/composer shows a lightweight formatting affordance that wraps the selection; Markdown output is Beacon-tokenized (mono code, brand links).
- [ ] Existing plain-text bodies still render correctly.
- [ ] `security-officer` has reviewed the render path.

**Full-page issue view (locked Q4)**
- [ ] The same single-scroll body renders both in the drawer and on a full-page route; there's an affordance to open an issue full-page from the drawer, plus a direct URL.
- [ ] The full-page route resolves the issue **within the user's workspace/project membership**; a non-member / missing issue 404s (existence never revealed); no new backend endpoint was added.
- [ ] Page layout uses inline `maxWidth` (no Tailwind `max-w-*`); sticky header/jump-nav/composer work within the page column.
- [ ] Comment `@mention` autocomplete still triggers on `@`, filters by name prefix, inserts `@Name `.
- [ ] Comment delete (author) and attachment delete (uploader/MANAGER) still work from hover.

**Beacon / responsive**
- [ ] All colors/fonts come from `var(--color-*)` / `var(--font-*)` tokens (except the pre-existing history diff red/green); no new hardcoded hex; `mono` used for key/date/audit values.
- [ ] No Tailwind `max-w-2xs..3xl` anywhere; width caps use inline `maxWidth`.
- [ ] On the Board the panel still resizes (360–720, ≤55% vw) with the left handle; on Backlog it's fixed-width with no handle.
- [ ] Metadata grid is 2-col at comfortable width and collapses to 1-col at ≤~380px; `Select`/mention popovers place correctly at 360px.
- [ ] Switching issues via parent/child navigation still remounts cleanly (the `key={num}` remount is preserved by the parents).

---

## 12. Open questions — RESOLVED (2026-08-13)

- **Q1 — Activity order.** ✅ **Newest→oldest** (user choice). Feed most-recent-first; composer pinned at bottom; posting prepends to the top. See §4.8.
- **Q2 — Status options.** ✅ **Filter to allowed transitions** (user choice). Reuse `isMoveAllowed` (extracted to a shared helper) + `transitions` from config. See §4.3. The former highest-risk assumption is **retired** by this choice.
- **Q3 — Markdown.** ✅ **Include now** (user choice). Client-side render + lightweight authoring; bodies stored plain. See §6.5 — note the **mandatory `security-officer` review** of the render path (XSS).
- **Q4 — Full-page issue route.** ✅ **Include now** (user choice). Shared body component, new route. See §6.6 — note the **`tenancy-reviewer` caveat** if key-based resolution is used, and the scope-guard against adding an endpoint.
- **Q5 — Manual per-section collapse.** Still optional (§6). Recommend **skip for v1**; do not auto-collapse. (Not asked; default stands — raise if you want it.)
- **Q6 — Copy-key-on-click** in the header. Minor nicety. Include if cheap. (Not asked; default stands.)

### Remaining build-time decisions (small, no user sign-off needed unless you care)
- Exact **full-page route shape** (workspace/project-scoped path vs key-based `/browse/KEY`). Recommend the **ws/project-scoped path** to keep membership scoping trivial and avoid a new lookup endpoint (§6.6 scope guard).
- **Remote images in Markdown**: recommend **disabled in v1** (tracking-pixel/SSRF-adjacent); revisit if users ask.
- Markdown renderer package (`react-markdown`+`remark-gfm` recommended, §6.5).

---

## Sources

- [Linear vs Jira — Nuclino](https://www.nuclino.com/solutions/linear-vs-jira)
- [Linear vs Jira: Why 30% of Teams Switched (2026) — tech-insider](https://tech-insider.org/linear-vs-jira-2026/)
- [Linear sidebar / issue metadata — Linear changelog](https://linear.app/changelog/2022-02-11-sidebar-update)
- [Linear display options (list/board, shown info) — Linear docs](https://linear.app/docs/display-options)
- [Notion ⟷ GitHub: comments sync as activity — Notion help](https://www.notion.com/help/github)
- [GitHub Issues overview (markdown, mentions, attachments, task lists)](https://www.alternativeto.net/software/github-issues/about/)
- [ClickUp inline-edit friction vs Asana click-to-edit — ClickUp Canny](https://clickup.canny.io/feature-requests/p/easier-way-to-edit-names-of-spaces-projects-and-lists)
- [YouTrack issue activity stream & filtering — JetBrains docs](https://www.jetbrains.com/help/youtrack/server/activity-stream.html)
- [YouTrack apply-command / keyboard field editing — JetBrains docs](https://www.jetbrains.com/help/youtrack/server/apply-commands-to-issues.html)
- [YouTrack reviews — flexibility vs. dated/cluttered UI & learning curve (G2)](https://www.g2.com/products/youtrack/reviews)
- [scroll-margin-top for sticky-header anchor jumps — Publii](https://getpublii.com/blog/one-line-css-solution-to-prevent-anchor-links-from-scrolling-behind-a-sticky-header.html)
- [Requests for collapsible sections in long comment views — Figma forum](https://forum.figma.com/t/collapsible-sections-in-comments-view/10078)
