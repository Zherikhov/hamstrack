# Command palette & global keyboard shortcuts — spec (HD-39)

> **Ticket:** HD-39 (epic HD-12 "Productivity") · **Complexity:** Medium · **Dev type:** Frontend-only
> **Status:** proposed, ready to build · **Author:** systems-analyst · **Date:** 2026-08-15
>
> **Hard constraint:** no backend work. No new endpoint, no DTO change, no migration.
> Everything below is built on APIs that already ship (see §4 API inventory).
> **Highest-risk assumption is flagged in §16.**

---

## 0. Problem & goal

Hamstrack is mouse-driven today: to open an issue you click through rail → board → card; to
run a saved filter you go to Search and open a dropdown; to create an issue you aim at the
rail button. Power users (the exact "leaving Jira DC" buyer DESIGN.md targets, and the
Linear-grade polish it references) expect to never leave the keyboard. HD-39 adds one
always-available overlay — **Cmd/Ctrl-K** — that fuzzy-matches every navigation target,
action, project, saved filter and issue the current user can already reach, plus a small,
unambiguous set of global single-key shortcuts (`c`, `/`, `g`-chords, `?`).

**Success:** a user who never touches the mouse can, from any authenticated app screen,
open the palette, type 3–5 characters, and land on the right issue / board / filter, or
start a new issue — and can discover the whole shortcut map with `?`.

---

## 1. Scope

### In scope (v1)
- A global command palette overlay mounted in `AppShell`, opened with Cmd/Ctrl-K.
- A fixed, enumerated command registry: navigation targets + actions (§5).
- Live result sections fed by **existing** APIs: projects, saved filters, workspaces,
  workspace members, and workspace-scoped issue text search (§5.3).
- An ISSUE-KEY fast path (`HD-42` → "Open HD-42") resolved inside the **current workspace**.
- A deterministic, unit-testable fuzzy matcher + section ranking (§6).
- Full keyboard-only operation, focus trap, focus restoration (§7).
- Global shortcuts: `c`, `/`, `g`+key chords, `?`, with exact suppression rules (§8).
- A `?` help overlay (§9).
- Accessibility: combobox/listbox semantics, `aria-activedescendant`, live announcements,
  reduced-motion (§10).
- Minimal per-user persistence of *recent commands* (§11).

### Out of scope (v1) — do not build
- **Bulk actions from the palette** (HD-40).
- **Anything label-related** (HD-30) — `label` is a registered-but-unavailable HQL field and
  returns 422; the palette must never emit it.
- **Cross-workspace issue search / cross-workspace issue-key jump** — not reachable with
  today's API (§4.2). v1 searches the current workspace only.
- Contextual/"on the current issue" commands (assign, transition status, add comment).
- Creating a project / workspace / saved filter from the palette.
- Palette inside `/admin/**` and the `/welcome` onboarding flow (those routes are outside
  `AppShell`; see §3.3).
- Command aliases beyond the listed keywords, natural-language parsing, AI suggestions.
- Server-side "recently viewed issues", cross-device shortcut prefs, remappable keybindings.
- Mobile/touch affordance for the palette (it stays keyboard-first; the overlay is still
  usable with taps but no FAB/entry point is added).

### Non-goals
- Replacing `TopSearchBar`'s HQL search. The palette is a *jumper*, not a query builder; it
  always offers "Search all issues for …" as an escape hatch into the real HQL page.
- Replacing the NavRail. Every palette navigation target must also remain clickable.

---

## 2. Actors & permissions

| Actor | Can open palette | Notes |
|---|---|---|
| Any authenticated user on an `AppShell` route | yes | The overlay itself has no permission gate. |
| Anonymous visitor (`/`, `/login`, `/register`, legal, `/docs`) | no | Hook is not mounted; Cmd-K falls through to the browser. |
| User in onboarding (`/welcome*`) | no | Outside `AppShell`. |
| System admin inside `/admin/**` | no (v1) | Outside `AppShell`; listed in §15 "later". |

**Tenancy.** The palette performs **no aggregation the API doesn't already permit**. Every
row originates from a call the user can make today:

- `apiListWorkspaces()` → only the caller's memberships (server-scoped).
- `apiListProjects(wsId)` / `savedFilters.list(wsId)` / `apiListWorkspaceMembers(wsId)` /
  `apiSearch(wsId, …)` → workspace-scoped; a non-member gets 404 and the section renders
  empty. **A 404 from any palette source is silent** (empty section), never an error banner —
  it must not reveal whether a workspace exists.
- `apiGetIssue(wsId, projectId, number)` for the key fast path → 404 renders as a disabled
  "not found" row.

The palette therefore inherits tenancy automatically; there is no client-side join across
workspaces other than listing the user's own workspaces.

---

## 3. Trigger, dismissal, and where it lives

### 3.1 Mount point
A single `<CommandPalette />` + `<ShortcutsHelp />` + `useGlobalShortcuts()` are mounted in
`components/AppShell.tsx`, alongside the existing `CreateIssueModal`. Visibility state lives
in `uiStore` (zustand), which already owns `createIssueOpen` — this keeps one source of
truth that the shortcut hook can read to suppress itself (§8.3).

```
uiStore additions:
  paletteOpen: boolean
  openPalette(): void          // no-op when createIssueOpen === true
  closePalette(): void
  helpOpen: boolean
  openHelp(): void / closeHelp(): void
  searchFocusNonce: number
  requestSearchFocus(): void   // increments the nonce; TopSearchBar focuses on change
```

### 3.2 Open / close
| Input | Behaviour |
|---|---|
| `Cmd+K` (mac) / `Ctrl+K` | Toggle: opens the palette; if already open, closes it. Works **even while a text field is focused** (the only shortcut with that exemption). |
| `Esc` | Closes the palette (help overlay first if it is on top). No navigation. Restores focus (§7.4). |
| Click on the backdrop (outside the panel) | Closes. |
| Running any row | Closes first, then performs the action. |
| Route change (`location.pathname` changes) | Closes (safety net; every action already closes explicitly). |
| Browser back/forward | Same as above — closes. |

### 3.3 Interaction with other overlays
- **Create-issue modal open** → `Cmd/Ctrl-K` is a **no-op**. Rationale: two stacked focus
  traps, and a half-typed issue silently losing focus, is the worst possible outcome; the
  user can `Esc`/close the modal and then open the palette. `openPalette()` enforces this in
  the store, so any future caller inherits the rule.
- **Palette open** → all other global shortcuts are suppressed (§8.3); the palette owns the
  keyboard.
- **Help overlay open on top of the palette** → `Esc` closes only the help overlay and
  returns focus to the palette input.
- **IssueSidePanel drawer open** (board/backlog/search) → no interaction; it is not a modal.
  The palette renders above it (z-index, §12).

### 3.4 Routes without a workspace
The palette needs a workspace for its issue/project/filter/member sections. It resolves one
with the **existing fallback chain already used by `NavRail`/`TopSearchBar`**:

```
paletteWsId = useCurrentProject()?.wsId
           ?? getLastWorkspaceId(user.id)          // localStorage recency journal
           ?? workspaces[0]?.id                    // ['workspaces'] query cache
           ?? null
```

- `/home`, `/my-work`, `/workspaces` → usually resolvable → full palette.
- `paletteWsId === null` (brand-new user with zero workspaces) → palette still opens, showing
  only workspace-independent rows (Home, My work, All workspaces, Create issue, Keyboard
  shortcuts) plus a muted footnote row: *"Join or create a workspace to search issues."*
  No workspace-scoped query is fired.
- `/login`, `/register`, `/`, legal, `/docs`, `/welcome*`, `/admin/**` → not mounted at all.

---

## 4. API inventory (everything v1 uses — all pre-existing)

### 4.1 Used
| Source | Call | Query key | Notes |
|---|---|---|---|
| Workspaces | `apiListWorkspaces()` | `['workspaces']` | already cached by ProjectSwitcher/NavRail; `staleTime` 5 min |
| Projects | `apiListProjects(wsId)` | `['projects', wsId]` | carries `key`, `archived`, `myRole` |
| Saved filters | `savedFilters.list(wsId)` | `['savedFilters', wsId]` | own + shared |
| Members | `apiListWorkspaceMembers(wsId)` | `['wsMembers', wsId]` | for the People section |
| Issue text search | `apiSearch(wsId, { query: 'text ~ "…"', size: 6 })` | `['palette-search', wsId, text]` | `~` = case-insensitive substring on **title + description** |
| Issue by key | `apiGetIssue(wsId, projectId, number)` | `['issue', wsId, projectId, number]` | shares IssueDetail's cache key |
| Project config | *not used* | — | palette never needs taxonomy |

Reads only. The palette issues **no writes**; "Create issue" delegates to the existing
`CreateIssueModal`.

### 4.2 Wanted but NOT reachable today → explicit cuts + follow-ups

| Desired capability | Why unreachable | v1 behaviour | Follow-up |
|---|---|---|---|
| "Jump to any issue by title across **all** workspaces" | `POST /workspaces/{ws}/search` is workspace-scoped; there is no cross-workspace search endpoint, and fanning out client-side = 1 POST per workspace (unbounded, and each can 404) | Search the **resolved current workspace only**; the section header reads `Issues · {workspace name}` so the scope is explicit | new `POST /api/search` (cross-workspace, membership-scoped, capped) |
| "Open `HD-42` from any workspace" | Issue keys are `{projectKey}-{number}`; resolving a project key requires `apiListProjects(wsId)` per workspace, and `number` is only addressable via `/workspaces/{ws}/projects/{pid}/issues/{n}` | Fast path resolves the project key **within the current workspace only**; unknown prefix → no fast-path row | new `GET /api/issues/by-key?key=HD-42` returning `{ workspaceId, projectId, number }` (or 404) |
| Search by issue **key** as text | HQL has no `key` field (`FieldRegistry`: status/type/priority/assignee/reporter/parent/text/created/updated/due + custom fields) | Key handled by the fast path; free text uses `text ~` | add a `key` field to `FieldRegistry` |
| Match on **comments** or attachments | `text ~` covers title + description only | documented in the help overlay ("searches title and description") | extend `text` |
| Filter/jump by **label** | `label` is a registered-but-unavailable stub → 422 | never emitted | HD-30 |
| "Recently viewed issues" section | No server-side view journal; only projects are journaled locally | omitted | local issue-recency journal or a server endpoint |
| Jump to a **user's profile** | No user profile page exists | People rows jump to `Search ?q=assignee = "email"` instead | user profile page |

---

## 5. Command model

A command is a plain object produced by a pure builder — this is the unit-testable core:

```ts
type CommandKind = 'action' | 'nav' | 'issue' | 'project' | 'filter' | 'workspace' | 'person' | 'info'

interface Command {
  id: string                 // stable & unique; static ids are persisted in recents
  kind: CommandKind
  section: SectionId         // drives grouping/order (§6.4)
  label: string              // primary text, fuzzy-matched
  sublabel?: string          // muted secondary text (project name, owner, hql)
  meta?: string              // right-aligned mono chip (issue key, project key, shortcut)
  keywords: string[]         // extra fuzzy targets, weighted 0.75 (§6.2)
  icon: LucideIcon
  shortcut?: string[]        // rendered as kbd chips, e.g. ['g','b']
  disabled?: boolean         // rendered muted, not selectable (info/not-found rows)
  run(ctx: RunContext): void // ctx = { navigate, uiStore, wsId, projectId, user }
}
```

### 5.1 Static commands — Actions

| id | label | icon | keywords | available when | does |
|---|---|---|---|---|---|
| `action.createIssue` | **Create issue** | `Plus` | new, issue, task, add, create, c | always | `closePalette()` then `openCreateIssue()` (no preset). The modal already selects workspace/project itself, so it works even with no workspace context. shortcut chip `c` |
| `action.search` | **Search issues (HQL)** | `Search` | search, hql, query, find, filter | `wsId != null` | `navigate('/w/{ws}/search')`. shortcut chip `/` |
| `action.help` | **Keyboard shortcuts** | `Keyboard` | help, shortcuts, keys, keyboard, cheatsheet | always | `closePalette()` then `openHelp()`. shortcut chip `?` |

### 5.2 Static commands — Navigation

| id | label | icon | keywords | available when | target |
|---|---|---|---|---|---|
| `nav.home` | Go to Home | `Home` | home, dashboard, overview, start | always | `/home` · chip `g h` |
| `nav.myWork` | Go to My work | `CheckSquare` | my work, mine, assigned to me, todo | always | `/my-work` · chip `g m` |
| `nav.workspaces` | Go to All workspaces | `Globe` | workspaces, teams, switch, all | always | `/workspaces` · chip `g w` |
| `nav.board` | Go to Board — *{project name}* | `Columns3` | board, kanban, columns | `currentProject != null` | `/w/{ws}/p/{pid}` · chip `g b` |
| `nav.backlog` | Go to Backlog — *{project name}* | `ListTodo` | backlog, list, queue | `currentProject != null` | `/w/{ws}/p/{pid}/backlog` · chip `g l` |
| `nav.wsHome` | Go to Workspace overview | `LayoutGrid` | workspace, projects, overview | `wsId != null` | `/w/{ws}` |
| `nav.projectSettings` | Project settings — *{project name}* | `Settings` | project settings, config, workflow, fields | `currentProject != null` **and** `projects.find(p => p.id === pid)?.myRole === 'MANAGER'` | `/w/{ws}/p/{pid}/settings` |
| `nav.wsSettings` | Workspace settings | `Settings` | workspace settings, admin, taxonomy, statuses | `wsId != null` **and** `workspaces.find(w => w.id === wsId)?.myRole !== 'MEMBER'` | `/w/{ws}/settings` |
| `nav.admin` | System administration | `Shield` | admin, system, instance, users | `user.systemRole === 'ADMIN'` | `/admin` · chip `g a` |

Role data comes from the **already-cached** `['projects', wsId]` and `['workspaces']`
responses (`Project.myRole`, `Workspace.myRole`) — **no extra request**. These are UI
affordances only; the server re-enforces everything (`WorkspaceSettingsArea` already
redirects a `MEMBER`, `/api/admin/**` is role-guarded).

### 5.3 Dynamic sections (live queries)

| Section | Source | Row shape | Cap | Runs |
|---|---|---|---|---|
| **Open issue** (fast path) | key regex + `['projects', wsId]` + `apiGetIssue` | label `Open {KEY}` → `Open {KEY} — {title}` once resolved; meta = mono key | 1 | `/w/{ws}/p/{pid}/issues/{n}` |
| **Projects** | `['projects', wsId]`, `archived === false` | label = project name; meta = mono project key | 5 | `/w/{ws}/p/{id}` |
| **Filters** | `['savedFilters', wsId]` | label = filter name; sublabel = `hql` (truncated, plain text) or `all issues`; `· {ownerName}` when `!mine` | 5 | `/w/{ws}/search?q={encodeURIComponent(hql)}` |
| **Issues · {ws name}** | `apiSearch` (debounced, §6.5) | label = issue title; sublabel = project name; meta = mono issue key; icon = a dot in `issue.type.color` | 6 | `/w/{ws}/p/{projectId}/issues/{number}` (full page — the drawer only exists on board/backlog routes) |
| **Workspaces** | `['workspaces']` | label = workspace name | 3 | `/w/{id}` |
| **People** | `['wsMembers', wsId]` | label = `Issues assigned to {displayName}`; sublabel = email | 3 | `/w/{ws}/search?q=` + `assignee = "{email}"` (HQL-escaped, §6.6) |
| **info** (always last, when query is non-empty and `wsId != null`) | — | `Search all issues for "{q}"` | 1 | `/w/{ws}/search?q=` + `text ~ "{q}"` (escaped) |

Dynamic sections are hidden entirely when their query is empty **and** when the typed text
matches nothing in them. Archived projects are excluded (they are not navigable from the
switcher either).

### 5.4 Empty-query state
No text typed → the palette shows, in order:
1. **Recent** — up to 5 recently-run *static* commands (§11), if any.
2. **Actions** — all available.
3. **Navigation** — all available.

No `apiSearch` call is made with an empty query (an empty HQL returns *every* issue in the
workspace — exactly the trap `SearchResultsPage.StartView` already guards against).

---

## 6. Search, matching, ranking

### 6.1 `fuzzyScore(query, target): number | null` — the exact rule

Pure function in `src/lib/fuzzy.ts`. Deterministic, O(|target|), greedy-leftmost.

```
q = query.toLowerCase(); t = target.toLowerCase()
if q === ''            -> return 0
if q.length > t.length -> return null

score = 0; prev = -1
for each character c of q:
    i = index of c in t at position > prev      // greedy leftmost
    if i === -1 -> return null                  // not a subsequence => no match
    gap = i - prev - 1
    bonus = 0
    if i === 0                         -> bonus += 8    // start of target
    else if isBoundary(target, i)      -> bonus += 6    // word/camel boundary
    if gap === 0 && prev >= 0          -> bonus += 4    // contiguous run
    score += 1 + bonus - min(gap, 3)                    // capped distance penalty
    prev = i

// whole-candidate adjustments
if t.startsWith(q)      -> score += 15
else if t.includes(q)   -> score += 8
score -= min(target.length, 40) * 0.05                  // mild preference for short labels
return score
```

`isBoundary(target, i)` is true when `target[i-1]` is one of the separator characters
(space, hyphen, underscore, slash, dot, colon, comma, parenthesis, bracket) **or** when
`target[i-1]` is lowercase/a digit and `target[i]` is uppercase (camelCase step, tested on
the *original* casing).

Greedy-leftmost is a deliberate trade: it can under-score a candidate whose better alignment
is further right (`"bo"` vs `"Backlog board"` scores the `B…o` of *Backlog*). It is O(n),
easy to unit-test, and good enough at the label lengths we have. Do **not** substitute a
different algorithm without updating the tests.

### 6.2 Candidate scoring (multi-field)
```
candidateScore(q, cmd) = max(
    fuzzyScore(q, cmd.label),
    cmd.meta ? fuzzyScore(q, cmd.meta) + 10 : null,   // key/short-code match is a strong signal
    max over cmd.keywords of (fuzzyScore(q, kw) * 0.75),
    cmd.sublabel ? fuzzyScore(q, cmd.sublabel) * 0.5 : null
)   // null if every term is null  =>  candidate is filtered out
```

### 6.3 Multi-word queries
Split the trimmed query on `/\s+/`. **Every** token must produce a non-null
`candidateScore`; the candidate's score is the **sum** of the token scores. (`"pay bo"`
matches "Go to Board — Payments".) Tokens are matched independently against all fields.

### 6.4 Section order & within-section ordering
Sections are rendered in a **fixed order** (never re-ordered by score — predictable muscle
memory beats marginal relevance):

```
1 Open issue (fast path)    5 Filters
2 Recent (empty query only) 6 Issues
3 Actions                   7 Workspaces
4 Navigation                8 People
                            9 info row ("Search all issues for …")
```

Within a section: `score DESC`, then a section-native tie-break
(Projects → localStorage recency from `recentProjects.ts`, then name ASC; Issues → server
order preserved; everything else → label ASC), then `id ASC` for total determinism. Use a
stable sort.

### 6.5 Server-backed source: debounce & guards
- Fires only when: `wsId != null` **and** trimmed query length **≥ 2**.
- Debounce **200 ms** after the last keystroke (`useDebouncedValue`).
- Query: `apiSearch(wsId, { query: 'text ~ ' + hqlQuote(sanitizeForHql(trimmed)), page: 0, size: 6 })`.
- TanStack: `queryKey: ['palette-search', wsId, debouncedText]`, `placeholderData: prev => prev`,
  `retry: false`, `staleTime: 30_000`.
- Cancelled implicitly by key change; stale responses can never overwrite a newer one because
  the key changes.

### 6.6 HQL escaping (mandatory, unit-tested)
The lexer accepts only `\"`, `\'`, `\\` escapes; anything else is a parse error (422).

```ts
export function hqlQuote(s: string): string {
  return '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"'
}
```

`sanitizeForHql(s)` runs **first**, in this order:
1. `trim()`;
2. replace every ASCII control character — any code point below `0x20`, plus `0x7F`
   (build the character class with `String.fromCharCode` or `\u`-escapes; do **not** paste
   raw control bytes into the source) — with a single space;
3. collapse runs of whitespace to one space;
4. truncate to **100** characters.

Used for `text ~ …` and `assignee = …`. A user typing `he said "hi"\` must never produce a 422.

### 6.7 ISSUE-KEY fast path
- Regex (case-insensitive, applied to the trimmed query):
  `^([A-Za-z][A-Za-z0-9_]{0,9})-(\d{1,9})$`
- Uppercase the captured prefix, look it up in `['projects', wsId]` by `project.key`.
  - **No match** → no fast-path row (the normal sections still render; a project key from a
    different workspace is simply unknown here — §4.2).
  - **Match** → render the row immediately (optimistic, `Open {KEY}`) and fire
    `apiGetIssue(wsId, projectId, number)` in the background:
    - resolves → row becomes `Open {KEY} — {title}`;
    - 404 → row becomes `{KEY} — not found` with `disabled: true` (not selectable, skipped by
      arrow keys);
    - other error → keep the optimistic row selectable (navigating shows the page's own state).
- The fast path is **additive**: the text search still runs for the same string (a key-shaped
  token can legitimately appear in a title).
- Archived project → still offered (issues in archived projects remain readable).

### 6.8 States
| State | Rendering |
|---|---|
| Loading (issues section, no previous data) | one disabled row in the Issues section: `searching…` (mono, muted) |
| Refetching with previous data | previous rows stay; a 2px indeterminate hairline under the input |
| Issue search error (any status) | one disabled row: `Issue search unavailable` — muted, no HQL error text ever surfaced. Other sections unaffected. |
| No section produced any row | centered empty state: `No matches for "{q}"` + the always-present `Search all issues for "{q}"` row (selectable) |
| No workspace resolvable | workspace-independent rows + muted note `Join or create a workspace to search issues.` |

---

## 7. Keyboard model (inside the palette)

### 7.1 Selection
A flat, ordered array of **selectable** rows is derived from the rendered sections
(`disabled` rows and section headers excluded). `activeIndex` starts at `0` and is **clamped
and reset to 0 whenever the result list identity changes** (query typed, async results
arrive).

| Key | Behaviour |
|---|---|
| `ArrowDown` | next selectable row; wraps from last → first |
| `ArrowUp` | previous; wraps from first → last |
| `Tab` | `preventDefault()`; same as `ArrowDown` (focus never leaves the input) |
| `Shift+Tab` | `preventDefault()`; same as `ArrowUp` |
| `PageDown` / `PageUp` | ±5 rows, clamped (no wrap) |
| `Enter` | run the active row; no-op when the list is empty |
| `Esc` | close (§7.4) |
| Any printable char / `Backspace` | goes to the input (never closes the palette, even on empty) |
| `Home` / `End` | **not intercepted** — they belong to the text caret |

Section headers are pure presentation and are always skipped; arrowing past the last row of a
section lands on the first row of the next one. `scrollIntoView({ block: 'nearest' })` keeps
the active row visible — guard with `typeof el?.scrollIntoView === 'function'` (jsdom does not
implement it and will throw otherwise).

### 7.2 Focus trap
The panel contains exactly **one** focusable element: the input. It is focused on open and
keeps focus for the palette's whole lifetime (`onBlur` → refocus while `paletteOpen`, unless
the palette is closing). Rows are `div[role=option]` with `tabIndex={-1}`; they are clickable
and `mouseenter` sets `activeIndex` (mouse and keyboard share one selection).

### 7.3 Mouse
Hovering a row sets `activeIndex`; clicking runs it. Clicking a `disabled` row does nothing.
Backdrop click closes.

### 7.4 Focus restoration
On open, store `document.activeElement as HTMLElement | null`. On close, if the stored
element is still `document.contains(...)`, call `.focus()`; otherwise focus `document.body`.
When the palette closes *because an action ran*, skip restoration (the new page/modal owns
focus) — `closePalette({ restoreFocus: false })`.

---

## 8. Global shortcuts

Registered by one hook, `useGlobalShortcuts()`, mounted in `AppShell`:
`window.addEventListener('keydown', handler)` in the **bubble** phase (so a component that
`stopPropagation()`s — e.g. `HqlInput`'s autocomplete — wins) and removed on unmount.

### 8.1 The map

| Keys | Action | Requires |
|---|---|---|
| `Cmd+K` / `Ctrl+K` | toggle command palette | — |
| `c` | open the create-issue modal (`openCreateIssue()`) | — |
| `/` | focus the top-bar HQL search input (`requestSearchFocus()`), `preventDefault()` so `/` isn't typed | a resolvable workspace (otherwise the input isn't rendered → no-op) |
| `?` | toggle the shortcuts help overlay | — |
| `g` then `h` | Home | — |
| `g` then `m` | My work | — |
| `g` then `b` | Board (current project) | `currentProject != null`; otherwise no-op |
| `g` then `l` | Backlog (current project) | `currentProject != null` |
| `g` then `s` | Search | `wsId != null` |
| `g` then `w` | All workspaces | — |
| `g` then `a` | System administration | `user.systemRole === 'ADMIN'` |
| `Esc` | close the topmost of: help → palette | — |

**Chord rules.** `g` arms a pending chord for **1200 ms**. While armed, a small mono chip
(`g …`) fades in at the bottom-left (120 ms, suppressed under reduced motion) — cheap
discoverability and a testable artifact. The chord is cancelled by: the timeout, `Esc`, any
key not in the chord table, any key with a modifier, or losing window focus (`blur`).
Pressing `g` again re-arms (resets the timer). Both the `g` and the second key are
`preventDefault()`ed only when they resolve to an action.

Key matching is on `e.key` **exactly and case-sensitively**: `'c'`, `'/'`, `'?'`, `'g'`,
`'h'`, `'m'`, `'b'`, `'l'`, `'s'`, `'w'`, `'a'`. Caps Lock (`'C'`) therefore does **not**
trigger — intentional, and it makes the Shift question moot (`?` is naturally `Shift+/`, and
we match the produced character, not the physical key).

### 8.2 Mac vs Windows labelling
`isMac()` = `/mac|iphone|ipad/i.test(navigator.userAgentData?.platform ?? navigator.platform ?? '')`.
Renders `⌘K` vs `Ctrl+K` in the placeholder, footer and help overlay. Behaviour accepts
**either** `metaKey` or `ctrlKey` on both platforms (harmless, avoids platform-detection bugs).

### 8.3 When a global shortcut MUST NOT fire (normative)

A **single-key** shortcut (`c`, `/`, `?`, `g`, and any chord second key) fires **only if all**
of the following hold. Implement as one `shouldHandleGlobalKey(e)` predicate in
`src/lib/keyboard.ts` and unit-test each clause.

1. `e.defaultPrevented === false`.
2. `e.ctrlKey === false && e.metaKey === false && e.altKey === false`.
   (`e.shiftKey` is *ignored* — it only changes which character `e.key` reports.)
3. `e.isComposing === false` **and** `e.keyCode !== 229` (IME composition in progress).
4. `e.repeat === false` (auto-repeat from a held key).
5. **The event target is not editable.** With `el = (e.composedPath?.()[0] ?? e.target) as Element`,
   `isEditableTarget(el)` is true — and the shortcut is suppressed — when
   `el.closest(...)` matches any of:
   - `textarea`, `select`
   - `input:not([type=checkbox]):not([type=radio]):not([type=button]):not([type=submit]):not([type=reset]):not([type=range]):not([type=color]):not([type=file]):not([type=image])`
     (so `text`, `search`, `email`, `password`, `number`, `date`, `url`, and **type-less**
     inputs all suppress)
   - `[contenteditable]:not([contenteditable="false"])`
   - `[role="textbox"]`, `[role="combobox"]`, `[role="searchbox"]`
   - `[data-no-shortcuts]` (opt-out hook for any future widget with its own key handling)
6. **No overlay owns the keyboard:** `!createIssueOpen && !paletteOpen && !helpOpen`
   **and** `document.querySelector('[data-modal-open="true"]') === null`.
   The DOM check covers locally-owned modals that aren't in `uiStore`; each of
   `CreateProjectModal`, `SaveFilterDialog`, `WorkspaceMembersModal`, `AboutModal`,
   `CreateIssueModal` gains `data-modal-open="true"` on its overlay `div` (one attribute
   each, no logic change).

**`Cmd/Ctrl+K` exemptions:** clause 2 obviously does not apply (it *requires* a modifier) and
clause 5 does **not** apply (Cmd-K works while typing anywhere). Clauses 1, 3, 4 and 6 still
apply — Cmd-K never opens over another modal. While the palette is open, Cmd-K closes it
(the palette's own handler, not the global hook).

**`Esc` exemption:** handled by the overlays themselves, not by the global hook, so it never
interferes with inline-edit `Esc` handlers (`IssueDetail` title/description/field edits).

---

## 9. Help overlay (`?`)

A centered dialog (`role="dialog"`, `aria-modal="true"`, `aria-label="Keyboard shortcuts"`),
560px wide, `--radius-xl`, `--shadow-lg`, `max-height: 80vh`, body scrolls.

**Content — four groups, each an 11px/700 uppercase eyebrow + rows of `kbd chips + description`:**

- **General** — `⌘K`/`Ctrl K` command palette · `c` create issue · `/` search · `?` this help · `Esc` close
- **Go to** — `g h` Home · `g m` My work · `g b` Board · `g l` Backlog · `g s` Search · `g w` Workspaces · `g a` System administration *(admins only — row hidden otherwise)*
- **In the palette** — `↑ ↓` move · `Tab`/`Shift Tab` move · `↵` open · `Esc` close · type an issue key (e.g. `HD-42`) to jump straight to it
- **Notes** — one muted paragraph: *"Text search matches issue titles and descriptions in the current workspace. Use Search for full HQL queries."* (This is the honest statement of the §4.2 limits.)

Rows whose command is unavailable for the current user/route are hidden (not greyed) —
except the `g b`/`g l` rows, which stay visible with a muted "needs a project" note.

**Dismissal:** `Esc`, backdrop click, the ✕ button, or pressing `?` again. When opened from
the palette, closing it returns focus to the palette input; otherwise focus is restored to
the previously focused element (§7.4). Also reachable from the NavRail user menu as a
"Keyboard shortcuts" item (discoverability for mouse users).

---

## 10. Accessibility

| Concern | Requirement |
|---|---|
| Dialog | Overlay panel: `role="dialog"`, `aria-modal="true"`, `aria-label="Command palette"` |
| Input | `role="combobox"`, `aria-expanded="true"`, `aria-controls="cmdk-list"`, `aria-autocomplete="list"`, `aria-activedescendant="cmdk-opt-{activeId}"` (omitted when the list is empty), `autoComplete="off"`, `spellCheck={false}`, `aria-label="Search commands, projects and issues"` |
| List | `id="cmdk-list"`, `role="listbox"`, `aria-label="Results"` |
| Sections | `role="group"` + `aria-label="{section title}"`; the visible header is `aria-hidden="true"` to avoid double announcement |
| Rows | `role="option"`, `id="cmdk-opt-{cmd.id}"`, `aria-selected={i === activeIndex}`, `aria-disabled={!!cmd.disabled}`, `tabIndex={-1}` |
| Announcements | A visually-hidden `aria-live="polite" aria-atomic="true"` node reporting, 300 ms after the result set settles: `"{n} results"` / `"No results"` / `"Searching issues"` |
| Focus | Focus input on open; trap (§7.2); restore on close (§7.4) |
| Contrast | Active-row tint is `color-mix(brand 10%, white)` with unchanged `--color-text` label — ≥ 4.5:1 preserved. Selection is **not** conveyed by colour alone: the active row also shows a 2px teal left indicator bar |
| Reduced motion | `useReducedMotion()` → no entrance transform/animation at all (render final state); the chord chip appears instantly; the loading hairline becomes a static bar |
| Screen-reader limitation | v1 does **not** set `aria-hidden`/`inert` on the app root behind the overlay (no portal today). Accepted; `role=dialog aria-modal` + the focus trap carry it. Listed in §15 |

---

## 11. Persistence — decision

**Ship it, minimally.** `uiPrefs` gains:

```ts
/** Ids of the last static palette commands the user ran, most recent first (max 5). */
paletteRecentIds?: string[]
```

- Written by `run()` **only for `kind === 'action' | 'nav'`** commands (stable, workspace-free ids).
- **Never** stores issue / project / filter / workspace / member ids. Justification: those are
  tenant data in `localStorage`, go stale on access revocation, and a "Recent" row pointing at
  a resource the user can no longer reach is both a poor experience and an information leak of
  a name they may have lost access to. `recentProjects.ts` already covers project recency, and
  the Projects section reuses it as a tie-break (§6.4).
- Read on open; ids not present in the current registry (feature removed, permission lost) are
  filtered out silently.
- Shown as a **Recent** section only when the query is empty (§5.4), capped at 5.
- Best-effort: `setUiPref` already swallows storage errors.

The palette does **not** persist the last typed query (it opens empty every time — a stale
query is a worse default than none).

---

## 12. Visual spec (Beacon — `DESIGN.md` compliant)

All values are tokens from `index.css`; **no hardcoded hex** except the documented ink-alpha
backdrop (matching the rail's own literal-alpha convention).

**Backdrop** — `position: fixed; inset: 0; z-index: 60;`
`background: rgba(16,24,40,0.45)` (= `--color-ink` @ 45%), `backdrop-filter: blur(2px)`
(same treatment as `CreateIssueModal`). z-index 60 sits above rail menus (40) and the
create-issue modal (50); the two are mutually exclusive anyway (§3.3). Help overlay: 70.

**Panel** — `width: 640px; max-width: 92vw; margin-top: 12vh;` (top-aligned, not centered —
the eye lands where the typing happens), `background: var(--color-card)`,
`border: 1px solid var(--color-border-2)`, `border-radius: var(--radius-xl)` (18px — the
panel/modal step), `box-shadow: var(--shadow-lg)`, `max-height: min(60vh, 560px)`,
`display: flex; flex-direction: column; overflow: hidden`.

**Input row** — height 56, padding `0 18`, `Search` icon 17 in `--color-text-muted`, borderless
input, Inter 15px/450, placeholder `Search issues, projects and commands…`, right side shows a
mono `esc` chip. Bottom hairline `1px solid var(--color-border)`.

**Section header** — 11px/700, `letter-spacing: .08em`, uppercase, `--color-text-muted`,
padding `12px 18px 4px`. The first header has no extra top padding beyond 8px.

**Row** — height 40, `margin: 0 6px`, `padding: 0 12px`, `border-radius: var(--radius-md)`,
`gap: 11`. Icon 16 `--color-text-secondary`. Label Inter 13.5/600 `--color-text` (single line,
`text-overflow: ellipsis`). Sublabel 12 `--color-text-muted`, same line, after a `·`.
Right meta: `.mono` 11 `--color-text-muted` (issue/project key) or kbd chips.
**Active row:** `background: color-mix(in srgb, var(--color-brand) 10%, white)`,
icon → `--color-brand`, plus a 2px × 18px `--color-brand` bar inset at the left edge.
Disabled row: label `--color-text-muted`, `cursor: default`, no hover/active tint.

**kbd chip** — `.mono` 10.5px, `padding: 2px 6px`, `border: 1px solid var(--color-border-2)`,
`border-radius: var(--radius-sm)` (6px), `background: var(--color-surface-2)`,
`color: var(--color-text-secondary)`. Chord chips render as two adjacent chips (`g` `b`).

**Footer** — height 34, hairline top, `.mono` 11 `--color-text-muted`:
`↑↓ navigate · ↵ open · esc close` (left) and `⌘K`/`Ctrl K` (right).

**Chord indicator** — bottom-left, 16px inset, `.mono` 11, ink pill
(`background: var(--color-ink-menu)`, `color: #fff`, `--radius-sm`, `padding: 4px 9px`),
content `g …`.

**Motion** (DESIGN.md band) — backdrop `opacity 0→1, 120ms ease-out`; panel
`opacity 0→1 + translateY(-6px)→0, 160ms ease-out` (short band). Exit: immediate unmount
(no exit animation — an exiting palette must never eat a keystroke). `prefers-reduced-motion`
→ all of the above become instant.

**Responsive** — below 720px the panel is `width: 100%; margin: 8px; max-height: 80vh`; the
footer hint row is hidden.

---

## 13. Data model, API, DC/Cloud impact

- **Data model:** none. No table, column, migration, or entity change.
- **API surface:** none. No new endpoint, no DTO change, no `openapi.yaml` / `docs/api-*.md`
  update → the `api-docs-sync` gate is **n/a** for this ticket.
- **Backend:** none. `backend-builder` is not involved; the `tenancy-reviewer` /
  `migration-reviewer` gates are n/a (no backend diff, no migration).
- **DC vs Cloud:** identical in both modes — the palette only calls endpoints that exist in
  both, and every capability is derived from the caller's own memberships. **No new env var,
  no property, no profile gate.** (`dc-cloud-guard` n/a.) The only conditional behaviour is
  the mac/windows key *label*, which is a client platform detail, not a deployment mode.
- **Security:** no new attack surface. Two rules are load-bearing and must be honoured:
  (a) saved-filter `name`/`hql`, project names, issue titles and member names are rendered as
  **plain text only** (React text nodes, never `dangerouslySetInnerHTML`) — the same rule
  `SavedFiltersPanel`/`StartView` already follow; (b) typed text reaching HQL goes through
  `sanitizeForHql` + `hqlQuote` (§6.6).

---

## 14. Edge cases & failure modes

| # | Case | Required behaviour |
|---|---|---|
| 1 | Cmd-K while the create-issue modal is open | no-op (§3.3) |
| 2 | Cmd-K while the palette is open | closes it (toggle) |
| 3 | `c` typed inside the HQL search box / issue title inline edit / comment textarea / markdown editor | the character is typed; **no modal opens** (§8.3 clause 5) |
| 4 | `?` typed into any text field | typed literally, help does not open |
| 5 | `g` pressed, then the user waits >1.2 s, then `b` | chord expired; `b` does nothing (and is not typed anywhere) |
| 6 | `g` then an unmapped key (`g x`) | chord cancelled silently, no navigation |
| 7 | `g b` with no current project | no-op (the rail also hides Board in that state) |
| 8 | Cmd-K on `/workspaces` (no route ws) | palette opens; workspace-scoped sections use the fallback ws (§3.4) |
| 9 | Brand-new user, zero workspaces | palette opens with workspace-free rows + the muted note; no query fires |
| 10 | `apiSearch` 404 (user lost workspace access mid-session) | Issues section shows `Issue search unavailable`; palette stays usable; **no** 403/404 detail exposed |
| 11 | `apiSearch` 422 | impossible by construction (we always emit `text ~ "…"` with escaping), but handled identically to case 10 |
| 12 | Typed text contains `"` / `\` / newline / 500 chars | escaped, control-stripped, truncated to 100 (§6.6) |
| 13 | `HD-42` where `HD` is not a project key in this workspace | no fast-path row; text search still runs |
| 14 | `HD-99999` where the issue doesn't exist | disabled `HD-99999 — not found` row, skipped by arrows |
| 15 | `hd-42` lowercase | matched; prefix uppercased for lookup |
| 16 | `HD-`, `-42`, `HD--42` | no fast-path row (the regex requires at least one digit after a single hyphen); `HD-0` is offered and resolves to 404 → case 14 |
| 17 | Results arrive after the user typed more | impossible to render stale: the TanStack key includes the debounced text |
| 18 | Active index points past the end after results shrink | reset to 0 whenever the list identity changes (§7.1) |
| 19 | Enter with an empty list | no-op |
| 20 | Enter on the info row with no ws | the row isn't rendered without a ws |
| 21 | Archived project | excluded from the Projects section; still reachable via the fast path / issue rows |
| 22 | Saved filter with empty `hql` | sublabel `all issues`; navigates to `/w/{ws}/search?q=` (the page's own "start view" guard applies) |
| 23 | Two projects whose names both fuzzy-match equally | deterministic order via the tie-break chain (§6.4) |
| 24 | Palette open, user clicks the browser back button | the route-change effect closes the palette |
| 25 | Window loses focus mid-chord | chord cancelled on `blur` |
| 26 | Rapid Cmd-K spam | idempotent toggle; the stored `previouslyFocused` element is only captured on a false→true transition |
| 27 | Two browser tabs, different users | `uiPrefs`/`recentProjects` are already keyed by user id |
| 28 | IME (Japanese/Chinese) composition typing `c` | suppressed via `isComposing`/`keyCode 229` |
| 29 | Palette opened while an `IssueSidePanel` drawer is open | renders above it; `Esc` closes the palette only |
| 30 | User with `systemRole === 'ADMIN'` but only a MEMBER of the current workspace | `nav.admin` shown, `nav.wsSettings` hidden — the two gates are independent |

---

## 15. Later (explicitly deferred, in priority order)

1. Palette inside `/admin/**` and `/welcome` (needs the hook mounted outside `AppShell`).
2. Cross-workspace issue search + `GET /api/issues/by-key` (removes the §4.2 cuts).
3. `Esc`-to-close on `CreateIssueModal` (it currently has only ✕/backdrop).
4. Contextual commands when an issue is open: assign to me, change status, copy key/link.
5. `Create project` / `Create saved filter` actions (needs modal ownership hoisted to `uiStore`).
6. Recently-viewed **issues** section (local journal, mirroring `recentProjects.ts`).
7. `aria-hidden`/`inert` on the app root while the overlay is open (needs a React portal).
8. User-remappable shortcuts; a "shortcuts" tab in user settings.
9. `j`/`k` list navigation on Board/Backlog and `Enter` to open (a separate, page-level concern).

---

## 16. Highest-risk assumption (flagged)

> **The `isEditableTarget` suppression (§8.3 clause 5) is the single point of failure for this
> feature.** If it misses one editable surface, the app develops a "typing `c` randomly opens
> a dialog and eats my text" bug that is intermittent, infuriating, and hard to reproduce.
> The known editable surfaces today are: `HqlInput` (top bar + search page, an
> `input[role=combobox]` with its own key handling), `IssueDetail`'s inline title input,
> description textarea and per-field editors, the comment textarea, every admin form input,
> `SaveFilterDialog`, `CreateIssueModal`, `WorkspaceMembersModal`, `CreateProjectModal`,
> and the login/register/reset forms (not mounted under `AppShell`, so out of reach).
> **Mitigation:** clause 5 is written as a `closest()` selector (ancestor-aware, so it also
> covers a keystroke landing on a wrapper), the `[data-no-shortcuts]` opt-out exists, and the
> vitest suite must cover *each* clause independently (§17). Reviewers: check this predicate
> first.

Second-order risk: the hook is instantiated once per `AppShell` mount — verify the listener is
removed on unmount so a route transition can never leave two handlers registered
(double-firing `c`).

---

## 17. Test plan

### 17.1 Unit-testable in vitest/jsdom (must be written)

Follow the existing harness patterns in `src/pages/BoardPage.test.tsx` and
`src/components/CreateIssueModal.test.tsx`: `vi.mock('../api', …)` with **every imported
binding stubbed**, `QueryClientProvider` + `MemoryRouter` wrapper, `userEvent` for keys,
`useAuthStore.setState` for the current user.

**`src/lib/fuzzy.test.ts`** (pure, no DOM)
1. Exact prefix beats mid-string beats scattered subsequence (`"bo"`: `Board` > `Backlog` > `Bug report`).
2. Non-subsequence returns `null` (`"xyz"` vs `Board`).
3. Empty query returns `0` for every target.
4. Boundary bonus: `"gb"` scores higher on `Go Board` than on `Gobbledegook`.
5. Contiguity bonus: `"boar"` on `Board` > `"bor"` on `Board`.
6. Multi-word AND semantics: `"pay bo"` matches `Board — Payments`, `"pay zz"` does not.
7. Case-insensitivity in both directions.
8. Determinism: same inputs → identical score across 100 runs (guards accidental `Math.random`/`Date`).

**`src/hql.test.ts`** (or a new sibling test file)
9. `hqlQuote('he said "hi"')` → `"he said \"hi\""`; backslash doubled; control characters
   replaced by a space; input longer than 100 chars truncated.

**`src/lib/keyboard.test.ts`**
10. `isEditableTarget` true for `input` (no type, `text`, `search`, `email`, `number`, `date`),
    `textarea`, `select`, `[contenteditable]`, `[role=textbox|combobox|searchbox]`, an element
    **inside** a contenteditable, and `[data-no-shortcuts]`.
11. `isEditableTarget` false for `button`, `div`, `input[type=checkbox]`, `contenteditable="false"`.

**`src/hooks/useGlobalShortcuts.test.tsx`** (harness component that mounts the hook)
12. `c` opens the create-issue modal (asserts `uiStore.createIssueOpen`).
13. `c` while focus is in a `textarea`/`input` → store unchanged.
14. `Ctrl+c` / `Alt+c` / `Meta+c` → store unchanged.
15. `c` with `e.repeat === true` → unchanged (dispatch a synthetic `KeyboardEvent`).
16. `c` while `createIssueOpen`/`paletteOpen`/`helpOpen` → unchanged.
17. `c` while a `[data-modal-open="true"]` node exists in the DOM → unchanged.
18. `g` then `b` navigates to the board path; `g` then `x` does not navigate.
19. `g`, then past the timeout (fake timers), then `b` → no navigation.
20. `?` opens help; `?` inside an input does not.
21. `/` calls `requestSearchFocus` and `preventDefault`s; inside an input it does neither.
22. `Cmd+K` **inside an input** still opens the palette (the documented exemption).
23. `Cmd+K` while `createIssueOpen` → palette does **not** open.
24. Unmount removes the listener (a subsequent `c` is a no-op).

**`src/components/CommandPalette.test.tsx`**
25. Opens with the input focused; `role=dialog` / `role=combobox` / `role=listbox` present.
26. Empty query lists Actions + Navigation, and fires **no** `apiSearch` call.
27. Typing `boa` filters to the matching project/nav rows; unmatched rows are removed.
28. Arrow Down/Up move `aria-selected` and `aria-activedescendant`, wrapping at both ends;
    disabled rows are skipped.
29. `Tab` / `Shift+Tab` move the selection and do not move DOM focus (input keeps focus).
30. `Enter` on a nav row navigates (assert via a route-probe component) and closes the palette.
31. `Enter` on `Create issue` closes the palette and sets `createIssueOpen`.
32. `Esc` closes and restores focus to the element focused before opening.
33. Backdrop click closes; panel click does not.
34. Debounce: typing `bug` fires exactly **one** `apiSearch` after advancing 200 ms (fake timers);
    a 1-char query fires none.
35. The emitted HQL is exactly `text ~ "bug"` (assert the mock's call args) — and
    `he "x"` emits `text ~ "he \"x\""`.
36. Issue-key fast path: `HD-42` with a known project key renders `Open HD-42` and calls
    `apiGetIssue('w1','p1',42)`; on resolve the title appears; on 404 the row is
    `aria-disabled` and arrows skip it.
37. Unknown prefix `ZZ-1` renders no fast-path row.
38. `apiSearch` rejection renders `Issue search unavailable` and leaves other sections intact.
39. No results → empty state + a selectable `Search all issues for "…"` row.
40. Permission gating: `nav.admin` absent for a `USER`, present for an `ADMIN`;
    `nav.projectSettings` absent unless `myRole === 'MANAGER'`; `nav.wsSettings` absent for a
    workspace `MEMBER`.
41. Recents: running `nav.home` writes `paletteRecentIds` for that user id; reopening with an
    empty query shows a **Recent** section; an unknown persisted id is ignored.
42. No workspace resolvable → the muted note renders and no workspace-scoped api mock is called.

**`src/components/ShortcutsHelp.test.tsx`**
43. `?` opens it; the groups and documented rows render; the `g a` row is admin-only.
44. `Esc`, backdrop click and ✕ each close it; `?` again toggles it closed.

Target: **~44 new assertions**, roughly doubling the current 39-test frontend suite. Run with
`npm test` in `src/main/frontend` (or the Maven frontend phase); the backend suite is untouched.

### 17.2 Manual / visual QA (cannot be asserted in jsdom)
- Beacon fidelity: panel radius/shadow/tint, active-row bar, kbd chips, footer hints against
  `DESIGN.md` §Color / §Layout / §Motion.
- Entrance motion timing and the `prefers-reduced-motion` variant (OS-level toggle).
- `backdrop-filter: blur(2px)` support/appearance across Chrome/Firefox/Safari.
- Real macOS `⌘K` vs Windows `Ctrl K` (label + behaviour), and a non-US keyboard layout where
  `/` and `?` need AltGr — confirm clause 2 (no Alt) doesn't wrongly block `?` on such layouts;
  if it does, relax to "match on `e.key === '?'` regardless of `altKey`" and record it here.
- IME composition with a real IME.
- Screen-reader pass (NVDA or VoiceOver): announcements on open, on arrowing, on result-count updates.
- Scroll behaviour with 30+ rows; the active row stays visible.
- Perceived latency of the 200 ms debounce on a slow connection.
- Overlay stacking with the IssueSidePanel drawer open and with the rail collapsed.

---

## 18. Acceptance criteria (reviewer checklist)

**Palette basics**
- [ ] `Cmd+K` / `Ctrl+K` opens the palette from `/home`, `/my-work`, `/workspaces`, a board, a backlog, an issue full page, search results, workspace settings and project settings.
- [ ] It does **not** open on `/login`, `/register`, `/`, `/terms`, `/docs`, `/welcome`, `/admin/**`.
- [ ] `Esc`, backdrop click and running any row all close it; focus returns to the element that had it (except when an action took over).
- [ ] `Cmd+K` while the create-issue modal is open does nothing.

**Content**
- [ ] With an empty query the palette lists (Recent, if any), Actions and Navigation — and fires no issue search.
- [ ] Every command in §5.1/§5.2 is present exactly when its availability condition holds, and absent otherwise (verified for a MEMBER, a project MANAGER, a workspace ADMIN and a system ADMIN).
- [ ] Typing ≥2 chars produces Projects / Filters / Issues / Workspaces / People sections in the fixed §6.4 order, capped at the documented row counts, archived projects excluded.
- [ ] Typing `HD-42` offers `Open HD-42` above everything else, enriched with the title, or a disabled `not found` row.
- [ ] A non-empty query always ends with a selectable `Search all issues for "…"` row (when a workspace is resolvable).
- [ ] No result row ever renders HTML from server data (plain text only).

**Keyboard**
- [ ] The whole palette is operable with keyboard only: open, type, `↑`/`↓`/`Tab`/`Shift+Tab`, `Enter`, `Esc` — the mouse is never required. *(Ticket AC)*
- [ ] Arrow keys skip section headers and disabled rows and wrap at both ends.
- [ ] `Tab` never moves focus out of the input.
- [ ] `c`, `/`, `?`, `g`-chords work from any shell page and perform the §8.1 actions.
- [ ] None of them fire while typing in **any** input, textarea, select, contenteditable, or the HQL box; with `Ctrl`/`Alt`/`Meta` held; during IME composition; on key auto-repeat; or while any modal/overlay is open. *(§8.3 — verify each clause)*
- [ ] The `g …` chord indicator appears while a chord is armed and disappears on timeout/cancel.

**Help**
- [ ] `?` opens the help overlay from any shell page; it lists General / Go to / In the palette / Notes; admin-only rows are hidden for non-admins; `Esc`, backdrop, ✕ and `?` all dismiss it.
- [ ] The NavRail user menu has a "Keyboard shortcuts" entry that opens the same overlay.

**Matching**
- [ ] Fuzzy matching is subsequence-based and prefers prefix > substring > scattered; `fuzzy.test.ts` passes. *(Ticket AC)*
- [ ] Multi-word queries AND their tokens.
- [ ] The issue search debounces to one request per 200 ms idle and never fires below 2 chars or with an empty query.
- [ ] Typed quotes/backslashes never produce a 422.

**Non-functional**
- [ ] No backend file, migration, `openapi.yaml`, or `docs/api-*.md` change appears in the diff.
- [ ] Reduced-motion users get no palette animation.
- [ ] `npm run build` and `tsc` are clean; no new eslint errors; all new + existing frontend tests pass.

---

## 19. Files touched (implementation map)

**New**
- `src/main/frontend/src/lib/fuzzy.ts` — `fuzzyScore`, `candidateScore`, `rank` (pure)
- `src/main/frontend/src/lib/keyboard.ts` — `isEditableTarget`, `shouldHandleGlobalKey`, `isMac`
- `src/main/frontend/src/components/palette/commands.ts` — the registry builder (pure; takes user/ws/project/query data, returns `Command[]`)
- `src/main/frontend/src/components/CommandPalette.tsx`
- `src/main/frontend/src/components/ShortcutsHelp.tsx`
- `src/main/frontend/src/hooks/useGlobalShortcuts.ts`
- `src/main/frontend/src/hooks/useDebouncedValue.ts` (if not already present)
- tests per §17.1

**Edited**
- `src/main/frontend/src/uiStore.ts` — palette/help state + `searchFocusNonce`
- `src/main/frontend/src/components/AppShell.tsx` — mount the hook + both overlays
- `src/main/frontend/src/components/TopSearchBar.tsx` — react to `searchFocusNonce`
- `src/main/frontend/src/components/HqlInput.tsx` — optional `focusNonce` prop
- `src/main/frontend/src/components/NavRail.tsx` — "Keyboard shortcuts" user-menu item
- `src/main/frontend/src/uiPrefs.ts` — `paletteRecentIds`
- `src/main/frontend/src/hql.ts` — `hqlQuote` + `sanitizeForHql`
- `CreateIssueModal.tsx`, `CreateProjectModal.tsx`, `SaveFilterDialog.tsx`,
  `WorkspaceMembersModal.tsx`, `AboutModal.tsx` — add `data-modal-open="true"` to the overlay
  div (attribute only, no logic change)

Estimated: one focused frontend session (the registry, palette and hook are the bulk; the
fuzzy scorer and keyboard predicate are small and test-first).

---

## 20. Open questions (with recommended defaults — build the default unless told otherwise)

1. **Should `g b` / Board rows use the *last-visited* project when the user is on `/home`?**
   → **Yes** (default). `useCurrentProject()` already falls back to the recency journal, and the
   rail behaves the same way, so the palette matches the visible chrome.
2. **Should the Issues section search the fallback workspace when the user is on `/home`?**
   → **Yes**, with the workspace name in the section header (`Issues · Acme`) so the scope is
   never ambiguous.
3. **People section — worth it in v1?**
   → **Yes**: one already-cached call, and "what is Ann working on" is a top-5 daily need. It is
   the first thing to cut if the session runs long.
4. **`Cmd+Enter` to open a result in a new tab?**
   → **No** in v1 (only meaningful for nav/issue rows; adds a second Enter code path).
5. **Chord timeout 1200 ms?**
   → **Yes**. Long enough for two deliberate presses, short enough that a stray `g` doesn't
   swallow the next keystroke.
6. **Persist the last typed query?**
   → **No** (§11).
7. **Should `Esc` also close the create-issue modal (it currently has no Esc handler)?**
   → **Not in this ticket** — closing a half-typed issue with a stray `Esc` needs its own
   confirm-on-dirty decision. Deferred to §15.
