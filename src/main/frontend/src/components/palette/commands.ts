import {
  Home, CheckSquare, Globe, Columns3, ListTodo, LayoutGrid, Settings, Shield,
  Plus, Search, Keyboard, Folder, Filter, Building2, User as UserIcon,
  type LucideIcon,
} from 'lucide-react'
import { hqlAssigneeIs, hqlTextContains } from '../../hql'
import { canOpenProjectSettings, canOpenWorkspaceSettings } from '../../hooks/usePermissions'
import type { Permissions } from '../../hooks/usePermissions'
import type { Project, SavedFilter, SearchResultRow, User, Workspace, WorkspaceMember } from '../../types'

/**
 * The command-palette registry (HD-39 §5). PURE: it takes already-fetched data
 * and returns rows. No hooks, no store, no network — so the availability rules
 * (§5.1/§5.2) and the row shapes are unit-testable in isolation, and the palette
 * component stays a thin renderer.
 *
 * Availability is resolved from data the app has ALREADY cached — never an extra
 * request just to decide whether to draw a row. These are affordances only; the
 * server re-enforces every one of them.
 *
 * **Permissions arrive as an argument, not as a hook** (HD-116/HD-123 S5). This
 * builder is pure, so it has no hook context; that is exactly why the palette
 * kept its own copy of the project-settings predicate (`myRole === 'MANAGER'`)
 * long after the rail and the settings area had been widened, and why HD-116 was
 * filed as a second ticket for the same bug. The fix is not "be careful on both
 * surfaces": `input.permissions` carries the server's own answer, and the two
 * doors this palette opens are gated by the same shared functions the pages
 * themselves call, so a divergent predicate is no longer expressible here.
 */

export type CommandKind =
  | 'action' | 'nav' | 'issue' | 'project' | 'filter' | 'workspace' | 'person' | 'info'

export type SectionId =
  | 'open' | 'recent' | 'actions' | 'nav' | 'projects' | 'filters' | 'issues'
  | 'workspaces' | 'people' | 'info'

/**
 * Fixed render order (§6.4). Sections are NEVER reordered by score — predictable
 * muscle memory beats marginal relevance.
 */
export const SECTION_ORDER: SectionId[] = [
  'open', 'recent', 'actions', 'nav', 'projects', 'filters', 'issues', 'workspaces', 'people', 'info',
]

export const SECTION_TITLES: Record<SectionId, string> = {
  open: 'Open issue',
  recent: 'Recent',
  actions: 'Actions',
  nav: 'Navigation',
  projects: 'Projects',
  filters: 'Filters',
  issues: 'Issues',
  workspaces: 'Workspaces',
  people: 'People',
  info: '',
}

/** Row caps per §5.3. Sections without an entry are uncapped. */
export const SECTION_CAPS: Partial<Record<SectionId, number>> = {
  open: 1, recent: 5, projects: 5, filters: 5, issues: 6, workspaces: 3, people: 3, info: 1,
}

/** Sections that only ever appear once the user has typed something. */
export const DYNAMIC_SECTIONS: SectionId[] = [
  'open', 'projects', 'filters', 'issues', 'workspaces', 'people', 'info',
]

/**
 * Sections whose order is decided by the SOURCE, not by the fuzzy matcher:
 *  • `issues` — the server already matched (title AND description) and ranked
 *    them; re-filtering client-side on the title alone would silently drop
 *    description hits;
 *  • `open` / `info` — single synthetic rows that must always be offered.
 */
export const UNRANKED_SECTIONS: SectionId[] = ['open', 'issues', 'info']

/** What a command may do when it runs. Closing the palette is the caller's job. */
export interface RunContext {
  navigate: (to: string) => void
  openCreateIssue: () => void
  openHelp: () => void
}

export interface Command {
  /** Stable and unique. Static (`action.*` / `nav.*`) ids are persisted in recents. */
  id: string
  kind: CommandKind
  section: SectionId
  /** Primary text — the main fuzzy target. */
  label: string
  /** Muted secondary text on the same line (project name, owner, hql, email). */
  sublabel?: string
  /** Right-aligned mono chip — issue/project key. Weighted heavily when matching. */
  meta?: string
  /** Extra fuzzy targets, weighted 0.75. */
  keywords: string[]
  icon?: LucideIcon
  /** Issue rows draw a dot in their type colour instead of a lucide icon. */
  dotColor?: string
  /** Rendered as kbd chips, e.g. ['g','b']. */
  shortcut?: string[]
  /** Muted and unselectable — info/loading/not-found rows. Skipped by arrow keys. */
  disabled?: boolean
  run: (ctx: RunContext) => void
}

// ── Issue-key fast path (§6.7) ────────────────────────────────────────────────

/** `HD-42` / `hd-42` — at least one digit after exactly one hyphen. */
const ISSUE_KEY_RE = /^([A-Za-z][A-Za-z0-9_]{0,9})-(\d{1,9})$/

export interface ParsedIssueKey {
  /** Uppercased project key prefix — project keys are stored uppercase. */
  prefix: string
  number: number
}

/** Parse a trimmed query as an issue key, or null when it isn't one. */
export function parseIssueKey(query: string): ParsedIssueKey | null {
  const m = ISSUE_KEY_RE.exec(query.trim())
  if (!m) return null
  const number = Number(m[2])
  if (!Number.isFinite(number)) return null
  return { prefix: m[1].toUpperCase(), number }
}

/** Resolution state of the fast-path row, owned by the palette. */
export interface FastPathState {
  /** The canonical `{PREFIX}-{n}` key. */
  key: string
  projectId: string
  number: number
  /** Issue title once `apiGetIssue` resolved. */
  title?: string
  /** True once `apiGetIssue` 404'd — the row becomes a disabled "not found". */
  notFound?: boolean
}

/** Loading/error state of the server-backed Issues section (§6.8). */
export type IssuesState = 'idle' | 'loading' | 'error' | 'ready'

export interface CommandInput {
  user: User | null
  /** Resolved palette workspace (§3.4); null for a user with zero workspaces. */
  wsId: string | null
  /** Name of that workspace — shown in the Issues section header so scope is explicit. */
  wsName?: string
  currentProject: { wsId: string; projectId: string; name?: string } | null
  /**
   * The caller's effective permissions for the palette's workspace AND its
   * current project, composed by the palette component from the cached
   * `['workspaces']` / `['projects', wsId]` entries (the two scopes' key spaces
   * are disjoint, so one object is unambiguous). Unknown ⇒ denies ⇒ the row is
   * not offered, which is the right way round for a conditionally-mounted row:
   * it can pop in, it can never flash in and then vanish.
   */
  permissions: Permissions
  projects: Project[]
  workspaces: Workspace[]
  filters: SavedFilter[]
  members: WorkspaceMember[]
  issues: SearchResultRow[]
  /** The trimmed query as typed (NOT the debounced one). */
  query: string
  fastPath: FastPathState | null
  issuesState: IssuesState
}

const searchPath = (wsId: string, hql: string) => `/w/${wsId}/search?q=${encodeURIComponent(hql)}`

// ── Static commands (§5.1 Actions, §5.2 Navigation) ──────────────────────────

/**
 * Actions + Navigation, filtered to what the current user/route can actually
 * reach. Exported separately from the dynamic sections because "Recent" (§5.4)
 * resolves persisted ids against exactly this list.
 */
export function buildStaticCommands(input: CommandInput): Command[] {
  const { user, wsId, currentProject: cur, projects, permissions } = input
  const out: Command[] = []

  // ── Actions ────────────────────────────────────────────────────────────────
  out.push({
    id: 'action.createIssue',
    kind: 'action', section: 'actions',
    label: 'Create issue',
    keywords: ['new', 'issue', 'task', 'add', 'create', 'c'],
    icon: Plus, shortcut: ['c'],
    // No preset: the modal picks workspace/project itself, so this works even
    // with no workspace context at all.
    run: ctx => ctx.openCreateIssue(),
  })

  if (wsId) {
    out.push({
      id: 'action.search',
      kind: 'action', section: 'actions',
      label: 'Search issues (HQL)',
      keywords: ['search', 'hql', 'query', 'find', 'filter'],
      icon: Search, shortcut: ['/'],
      run: ctx => ctx.navigate(`/w/${wsId}/search`),
    })
  }

  out.push({
    id: 'action.help',
    kind: 'action', section: 'actions',
    label: 'Keyboard shortcuts',
    keywords: ['help', 'shortcuts', 'keys', 'keyboard', 'cheatsheet'],
    icon: Keyboard, shortcut: ['?'],
    run: ctx => ctx.openHelp(),
  })

  // ── Navigation ─────────────────────────────────────────────────────────────
  out.push({
    id: 'nav.home',
    kind: 'nav', section: 'nav',
    label: 'Go to Home',
    keywords: ['home', 'dashboard', 'overview', 'start'],
    icon: Home, shortcut: ['g', 'h'],
    run: ctx => ctx.navigate('/home'),
  })
  out.push({
    id: 'nav.myWork',
    kind: 'nav', section: 'nav',
    label: 'Go to My work',
    keywords: ['my work', 'mine', 'assigned to me', 'todo'],
    icon: CheckSquare, shortcut: ['g', 'm'],
    run: ctx => ctx.navigate('/my-work'),
  })
  out.push({
    id: 'nav.workspaces',
    kind: 'nav', section: 'nav',
    label: 'Go to All workspaces',
    keywords: ['workspaces', 'teams', 'switch', 'all'],
    icon: Globe, shortcut: ['g', 'w'],
    run: ctx => ctx.navigate('/workspaces'),
  })

  if (cur) {
    const projectName = projects.find(p => p.id === cur.projectId)?.name ?? cur.name
    const suffix = projectName ? ` — ${projectName}` : ''
    out.push({
      id: 'nav.board',
      kind: 'nav', section: 'nav',
      label: `Go to Board${suffix}`,
      keywords: ['board', 'kanban', 'columns'],
      icon: Columns3, shortcut: ['g', 'b'],
      run: ctx => ctx.navigate(`/w/${cur.wsId}/p/${cur.projectId}`),
    })
    out.push({
      id: 'nav.backlog',
      kind: 'nav', section: 'nav',
      label: `Go to Backlog${suffix}`,
      keywords: ['backlog', 'list', 'queue'],
      icon: ListTodo, shortcut: ['g', 'l'],
      run: ctx => ctx.navigate(`/w/${cur.wsId}/p/${cur.projectId}/backlog`),
    })
  }

  if (wsId) {
    out.push({
      id: 'nav.wsHome',
      kind: 'nav', section: 'nav',
      label: 'Go to Workspace overview',
      keywords: ['workspace', 'projects', 'overview'],
      icon: LayoutGrid,
      run: ctx => ctx.navigate(`/w/${wsId}`),
    })
  }

  // Project settings — HD-116: the SAME function the rail's Settings link and
  // the settings area itself apply. This row used to require project MANAGER
  // while both of those admitted the wider curator predicate, so a workspace
  // admin was offered no way in from here either.
  if (cur && canOpenProjectSettings(permissions)) {
    const projectName = projects.find(p => p.id === cur.projectId)?.name ?? cur.name
    out.push({
      id: 'nav.projectSettings',
      kind: 'nav', section: 'nav',
      label: `Project settings${projectName ? ` — ${projectName}` : ''}`,
      keywords: ['project settings', 'config', 'workflow', 'fields'],
      icon: Settings,
      run: ctx => ctx.navigate(`/w/${cur.wsId}/p/${cur.projectId}/settings`),
    })
  }

  // Workspace settings — the same function the area's own guard applies. The
  // answer must be KNOWN: an unresolved permission set denies, so nothing is
  // flashed that the area would then bounce them out of.
  if (wsId && canOpenWorkspaceSettings(permissions)) {
    out.push({
      id: 'nav.wsSettings',
      kind: 'nav', section: 'nav',
      label: 'Workspace settings',
      keywords: ['workspace settings', 'admin', 'taxonomy', 'statuses'],
      icon: Settings,
      run: ctx => ctx.navigate(`/w/${wsId}/settings`),
    })
  }

  // Instance admin — independent of any workspace role (§14 case 30).
  if (user?.systemRole === 'ADMIN') {
    out.push({
      id: 'nav.admin',
      kind: 'nav', section: 'nav',
      label: 'System administration',
      keywords: ['admin', 'system', 'instance', 'users'],
      icon: Shield, shortcut: ['g', 'a'],
      run: ctx => ctx.navigate('/admin'),
    })
  }

  return out
}

// ── Dynamic sections (§5.3) ──────────────────────────────────────────────────

/**
 * Live rows built from already-cached workspace data plus the debounced issue
 * search. Only produced for a non-empty query — an empty query must never fire
 * a search (an empty HQL matches EVERY issue in the workspace).
 */
export function buildDynamicCommands(input: CommandInput): Command[] {
  const { wsId, projects, workspaces, filters, members, issues, query, fastPath, issuesState } = input
  const q = query.trim()
  if (!q) return []
  const out: Command[] = []

  // Open issue (fast path) — rendered optimistically, enriched or disabled once
  // apiGetIssue settles.
  if (wsId && fastPath) {
    const notFound = fastPath.notFound === true
    out.push({
      id: `open:${fastPath.key}`,
      kind: 'issue', section: 'open',
      label: notFound
        ? `${fastPath.key} — not found`
        : fastPath.title ? `Open ${fastPath.key} — ${fastPath.title}` : `Open ${fastPath.key}`,
      meta: fastPath.key,
      keywords: [fastPath.key],
      icon: Folder,
      disabled: notFound,
      run: ctx => ctx.navigate(`/w/${wsId}/p/${fastPath.projectId}/issues/${fastPath.number}`),
    })
  }

  // Projects — archived ones are excluded (they aren't navigable from the
  // switcher either); they stay reachable via the fast path and issue rows.
  if (wsId) {
    for (const p of projects) {
      if (p.archived) continue
      out.push({
        id: `project:${p.id}`,
        kind: 'project', section: 'projects',
        label: p.name,
        meta: p.key,
        keywords: [p.key],
        icon: Folder,
        run: ctx => ctx.navigate(`/w/${wsId}/p/${p.id}`),
      })
    }
  }

  // Saved filters — own + shared. `hql` is rendered as PLAIN TEXT only.
  if (wsId) {
    for (const f of filters) {
      const hql = f.hql?.trim()
      out.push({
        id: `filter:${f.id}`,
        kind: 'filter', section: 'filters',
        label: f.name,
        sublabel: (hql || 'all issues') + (f.mine ? '' : ` · ${f.ownerName}`),
        keywords: ['filter', 'saved'],
        icon: Filter,
        run: ctx => ctx.navigate(searchPath(wsId, hql ?? '')),
      })
    }
  }

  // Issues — from the debounced workspace text search. Loading/error surface as
  // ONE disabled row each; no HQL/status detail is ever leaked into the UI.
  if (wsId) {
    if (issuesState === 'loading') {
      out.push({
        id: 'issues:loading',
        kind: 'info', section: 'issues',
        label: 'searching…', keywords: [], disabled: true, run: () => {},
      })
    } else if (issuesState === 'error') {
      out.push({
        id: 'issues:error',
        kind: 'info', section: 'issues',
        label: 'Issue search unavailable', keywords: [], disabled: true, run: () => {},
      })
    } else {
      for (const row of issues) {
        out.push({
          id: `issue:${row.issue.id}`,
          kind: 'issue', section: 'issues',
          label: row.issue.title,
          sublabel: row.projectName,
          meta: row.issue.key,
          keywords: [row.issue.key],
          dotColor: row.issue.type?.color,
          run: ctx => ctx.navigate(`/w/${wsId}/p/${row.projectId}/issues/${row.issue.number}`),
        })
      }
    }
  }

  // Workspaces — the caller's own memberships, nothing else.
  for (const w of workspaces) {
    out.push({
      id: `workspace:${w.id}`,
      kind: 'workspace', section: 'workspaces',
      label: w.name,
      keywords: ['workspace', 'team'],
      icon: Building2,
      run: ctx => ctx.navigate(`/w/${w.id}`),
    })
  }

  // People — "what is Ann working on". No user profile page exists, so these
  // jump to a scoped search instead (§4.2).
  if (wsId) {
    for (const m of members) {
      out.push({
        id: `person:${m.userId}`,
        kind: 'person', section: 'people',
        label: `Issues assigned to ${m.displayName}`,
        sublabel: m.email,
        keywords: [m.displayName, m.email],
        icon: UserIcon,
        run: ctx => ctx.navigate(searchPath(wsId, hqlAssigneeIs(m.email))),
      })
    }
  }

  // The escape hatch into the real HQL page — always last, always selectable.
  if (wsId) {
    out.push({
      id: 'info.searchAll',
      kind: 'info', section: 'info',
      label: `Search all issues for "${q}"`,
      keywords: [],
      icon: Search,
      run: ctx => ctx.navigate(searchPath(wsId, hqlTextContains(q))),
    })
  }

  return out
}

/** Everything the palette can currently offer, unranked and uncapped. */
export function buildCommands(input: CommandInput): Command[] {
  return [...buildStaticCommands(input), ...buildDynamicCommands(input)]
}

/**
 * Resolve the palette's workspace with the same fallback chain the rail and top
 * bar already use (§3.4), so the palette always agrees with the visible chrome.
 */
export function resolvePaletteWorkspaceId(
  currentWsId: string | null | undefined,
  lastWsId: string | null | undefined,
  workspaces: Workspace[] | undefined,
): string | null {
  return currentWsId ?? lastWsId ?? workspaces?.[0]?.id ?? null
}
