import { describe, it, expect, vi } from 'vitest'
import {
  parseIssueKey, buildStaticCommands, buildDynamicCommands, resolvePaletteWorkspaceId,
  SECTION_ORDER, SECTION_CAPS, DYNAMIC_SECTIONS,
  type CommandInput, type RunContext,
} from './commands'
import { permissionsFrom } from '../../hooks/usePermissions'
import {
  PROJECT_ADMIN_PERMISSIONS, PROJECT_CONTRIBUTOR_PERMISSIONS, PROJECT_CURATOR_BYPASS_PERMISSIONS,
  PROJECT_VIEWER_PERMISSIONS, WORKSPACE_ADMIN_PERMISSIONS, WORKSPACE_MEMBER_PERMISSIONS,
} from '../../test/permissions'
import type { Project, SavedFilter, User, Workspace, WorkspaceMember } from '../../types'

// HD-39 §17.1 (36–37, 40) + §6.7 / §5.1 / §5.2 at the pure-registry level. The
// palette component is a thin renderer over these functions, so the fast-path
// regex and the availability gates are asserted here where every branch is cheap
// to reach (the DOM-level checks live in CommandPalette.test.tsx).

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me', systemRole: 'USER' }
const ADMIN: User = { ...ME, systemRole: 'ADMIN' }

const WS: Workspace = {
  id: 'w1', name: 'Acme', slug: 'acme', myRole: 'OWNER',
  myPermissions: WORKSPACE_ADMIN_PERMISSIONS, createdAt: '2026-01-01T00:00:00Z',
}

const PROJ: Project = {
  id: 'p1', workspaceId: 'w1', name: 'Boats', key: 'BOA',
  archived: false, myRole: 'MEMBER', myPermissions: PROJECT_CONTRIBUTOR_PERMISSIONS,
  createdAt: '2026-01-01T00:00:00Z',
}
const PROJ_ARCHIVED: Project = { ...PROJ, id: 'p9', name: 'Old boats', key: 'OLD', archived: true }

const FILTER: SavedFilter = {
  id: 'f1', name: 'My open bugs', hql: '', shared: false,
  ownerId: ME.id, ownerName: 'Me', mine: true,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
}

const MEMBER: WorkspaceMember = { userId: 'u-ann', email: 'ann@example.com', displayName: 'Ann Lee', role: 'MEMBER' }

function input(over: Partial<CommandInput> = {}): CommandInput {
  const base = {
    user: ME,
    wsId: 'w1' as string | null,
    wsName: 'Acme',
    currentProject: null as CommandInput['currentProject'],
    projects: [] as Project[],
    workspaces: [WS],
    filters: [] as SavedFilter[],
    members: [] as WorkspaceMember[],
    issues: [],
    query: '',
    fastPath: null,
    issuesState: 'idle' as const,
    ...over,
  }
  return {
    ...base,
    // Composed exactly the way `CommandPalette` composes it, so a test that
    // varies `projects`/`workspaces` varies the gate the same way the app does
    // — including the "not fetched yet ⇒ denied" case (HD-116).
    permissions: over.permissions ?? permissionsFrom([
      base.workspaces.find(w => w.id === base.wsId),
      base.currentProject
        ? base.projects.find(p => p.id === base.currentProject!.projectId)
        : undefined,
    ]),
  }
}

const ids = (cmds: { id: string }[]) => cmds.map(c => c.id)

/** Run a command and report where it navigated (null = it did something else). */
function navTarget(cmd: { run: (ctx: RunContext) => void }): string | null {
  let to: string | null = null
  const ctx: RunContext = {
    navigate: (t: string) => { to = t },
    openCreateIssue: vi.fn(),
    openHelp: vi.fn(),
  }
  cmd.run(ctx)
  return to
}

describe('parseIssueKey (§6.7 fast-path regex)', () => {
  it('accepts a well-formed key and uppercases the prefix for lookup', () => {
    expect(parseIssueKey('HD-42')).toEqual({ prefix: 'HD', number: 42 })
    expect(parseIssueKey('hd-42')).toEqual({ prefix: 'HD', number: 42 })   // §14 case 15
    expect(parseIssueKey('  hd-42  ')).toEqual({ prefix: 'HD', number: 42 })
    expect(parseIssueKey('A-1')).toEqual({ prefix: 'A', number: 1 })
    expect(parseIssueKey('HD-0')).toEqual({ prefix: 'HD', number: 0 })     // §14 case 16 — offered, resolves to 404
  })

  it('accepts digits and underscores inside the prefix, up to 10 characters', () => {
    expect(parseIssueKey('a_b1-7')).toEqual({ prefix: 'A_B1', number: 7 })
    expect(parseIssueKey('ABCDEFGHIJ-1')).toEqual({ prefix: 'ABCDEFGHIJ', number: 1 })
    expect(parseIssueKey('ABCDEFGHIJK-1')).toBeNull() // 11 chars — one too many
  })

  it('accepts up to 9 digits and rejects a longer number', () => {
    expect(parseIssueKey('HD-999999999')).toEqual({ prefix: 'HD', number: 999999999 })
    expect(parseIssueKey('HD-1234567890')).toBeNull()
  })

  it('rejects every malformed shape (§14 case 16)', () => {
    for (const bad of [
      '', '   ', 'HD', 'HD-', '-42', '42', 'HD--42', 'HD-42-1', 'HD 42', 'HD-4.2', 'HD-42a',
      '1HD-42', '_HD-42', 'HD-+42', 'HD--', 'HD-42 ZZ', 'text ~ "x"',
    ]) {
      expect(parseIssueKey(bad), bad).toBeNull()
    }
  })

  it('is not fooled by a leading/trailing sign or whitespace inside the number', () => {
    expect(parseIssueKey('HD- 42')).toBeNull()
    expect(parseIssueKey('HD-4 2')).toBeNull()
    expect(parseIssueKey('HD--1')).toBeNull()
  })
})

describe('buildStaticCommands — availability gates (§5.1/§5.2)', () => {
  it('always offers Create issue and Keyboard shortcuts, even with no workspace', () => {
    const out = ids(buildStaticCommands(input({ wsId: null, workspaces: [] })))
    expect(out).toContain('action.createIssue')
    expect(out).toContain('action.help')
    expect(out).toContain('nav.home')
    expect(out).toContain('nav.myWork')
    expect(out).toContain('nav.workspaces')
    // Everything workspace-scoped is gone.
    expect(out).not.toContain('action.search')
    expect(out).not.toContain('nav.wsHome')
    expect(out).not.toContain('nav.wsSettings')
  })

  it('offers Board/Backlog only when a current project is resolvable', () => {
    expect(ids(buildStaticCommands(input()))).not.toContain('nav.board')
    const withProject = ids(buildStaticCommands(input({
      currentProject: { wsId: 'w1', projectId: 'p1', name: 'Boats' },
    })))
    expect(withProject).toContain('nav.board')
    expect(withProject).toContain('nav.backlog')
  })

  it('offers Project settings on the settings PERMISSIONS, not on a role name (§17.1 item 40)', () => {
    const cur = { wsId: 'w1', projectId: 'p1', name: 'Boats' }
    const asContributor = buildStaticCommands(input({ currentProject: cur, projects: [PROJ] }))
    expect(ids(asContributor)).not.toContain('nav.projectSettings')

    const viewer = { ...PROJ, myPermissions: PROJECT_VIEWER_PERMISSIONS }
    expect(ids(buildStaticCommands(input({ currentProject: cur, projects: [viewer] }))))
      .not.toContain('nav.projectSettings')

    // …and not while ['projects', wsId] is still loading (nothing known yet).
    const loading = buildStaticCommands(input({ currentProject: cur, projects: [] }))
    expect(ids(loading)).not.toContain('nav.projectSettings')

    const admin = { ...PROJ, myPermissions: PROJECT_ADMIN_PERMISSIONS }
    const asAdmin = buildStaticCommands(input({ currentProject: cur, projects: [admin] }))
    expect(ids(asAdmin)).toContain('nav.projectSettings')
    const row = asAdmin.find(c => c.id === 'nav.projectSettings')!
    expect(row.label).toBe('Project settings — Boats')
    expect(navTarget(row)).toBe('/w/w1/p/p1/settings')
  })

  /**
   * **HD-116, closed.** The palette gated this row on `myRole === 'MANAGER'`
   * while the rail's Settings link and `ProjectSettingsArea` both admitted the
   * wider curator predicate — so a workspace admin curating a project they are
   * not a member of was offered the door by neither the rail nor the palette,
   * and could only reach their own settings page by typing the URL. The row now
   * calls the same `canOpenProjectSettings` those two call, over the permission
   * set the server sends, so the three cannot disagree.
   */
  it('offers Project settings to a workspace admin who is not a project member (HD-116)', () => {
    const cur = { wsId: 'w1', projectId: 'p1', name: 'Boats' }
    // Exactly what the server sends such a caller: no project role of their own,
    // plus the `project.curate.all` bypass — and `myRole` still reads VIEWER.
    const bypass: Project = {
      ...PROJ, myRole: 'VIEWER', myPermissions: PROJECT_CURATOR_BYPASS_PERMISSIONS,
    }
    expect(ids(buildStaticCommands(input({ currentProject: cur, projects: [bypass] }))))
      .toContain('nav.projectSettings')
  })

  it('offers Workspace settings on the settings permissions (and not while they are unknown)', () => {
    expect(ids(buildStaticCommands(input()))).toContain('nav.wsSettings')
    const member = { ...WS, myPermissions: WORKSPACE_MEMBER_PERMISSIONS }
    expect(ids(buildStaticCommands(input({ workspaces: [member] })))).not.toContain('nav.wsSettings')
    expect(ids(buildStaticCommands(input({ workspaces: [] })))).not.toContain('nav.wsSettings')
  })

  it('gates System administration on systemRole alone — independent of the workspace role (§14 case 30)', () => {
    expect(ids(buildStaticCommands(input()))).not.toContain('nav.admin')
    const adminButMember = buildStaticCommands(input({
      user: ADMIN, workspaces: [{ ...WS, myPermissions: WORKSPACE_MEMBER_PERMISSIONS }],
    }))
    expect(ids(adminButMember)).toContain('nav.admin')
    expect(ids(adminButMember)).not.toContain('nav.wsSettings')
  })

  it('produces only stable, workspace-free ids (they are persisted as recents, §11)', () => {
    const all = ids(buildStaticCommands(input({
      user: ADMIN,
      currentProject: { wsId: 'w1', projectId: 'p1' },
      projects: [{ ...PROJ, myPermissions: PROJECT_ADMIN_PERMISSIONS }],
    })))
    for (const id of all) {
      expect(id, id).toMatch(/^(action|nav)\.[A-Za-z]+$/)
      expect(id, id).not.toContain('w1')
      expect(id, id).not.toContain('p1')
    }
    expect(new Set(all).size).toBe(all.length) // unique
  })
})

describe('buildDynamicCommands (§5.3)', () => {
  it('produces nothing at all for an empty query', () => {
    expect(buildDynamicCommands(input({ query: '   ', projects: [PROJ], workspaces: [WS] }))).toEqual([])
  })

  it('excludes archived projects but still allows the fast path into one', () => {
    const rows = buildDynamicCommands(input({ query: 'old', projects: [PROJ, PROJ_ARCHIVED] }))
    expect(ids(rows)).toContain('project:p1')
    expect(ids(rows)).not.toContain('project:p9')

    const viaKey = buildDynamicCommands(input({
      query: 'OLD-3',
      projects: [PROJ, PROJ_ARCHIVED],
      fastPath: { key: 'OLD-3', projectId: 'p9', number: 3 },
    }))
    expect(ids(viaKey)).toContain('open:OLD-3')
  })

  it('renders the fast-path row optimistically, enriched, then disabled on 404', () => {
    const base = { query: 'BOA-42', projects: [PROJ] }
    const optimistic = buildDynamicCommands(input({
      ...base, fastPath: { key: 'BOA-42', projectId: 'p1', number: 42 },
    })).find(c => c.section === 'open')!
    expect(optimistic.label).toBe('Open BOA-42')
    expect(optimistic.disabled).toBeFalsy()
    expect(navTarget(optimistic)).toBe('/w/w1/p/p1/issues/42')

    const resolved = buildDynamicCommands(input({
      ...base, fastPath: { key: 'BOA-42', projectId: 'p1', number: 42, title: 'Hull leaks' },
    })).find(c => c.section === 'open')!
    expect(resolved.label).toBe('Open BOA-42 — Hull leaks')

    const missing = buildDynamicCommands(input({
      ...base, fastPath: { key: 'BOA-99', projectId: 'p1', number: 99, notFound: true },
    })).find(c => c.section === 'open')!
    expect(missing.label).toBe('BOA-99 — not found')
    expect(missing.disabled).toBe(true)
  })

  it('escapes user text in the "Search all issues" escape hatch (§6.6)', () => {
    const info = buildDynamicCommands(input({ query: 'he said "hi"\\' })).find(c => c.section === 'info')!
    expect(info.label).toBe('Search all issues for "he said "hi"\\"') // plain text — React escapes it
    expect(navTarget(info))
      .toBe(`/w/w1/search?q=${encodeURIComponent('text ~ "he said \\"hi\\"\\\\"')}`)
  })

  it('escapes a member email into the People row query', () => {
    const person = buildDynamicCommands(input({ query: 'ann', members: [MEMBER] })).find(c => c.section === 'people')!
    expect(navTarget(person)).toBe(`/w/w1/search?q=${encodeURIComponent('assignee = "ann@example.com"')}`)
  })

  it('labels a saved filter with an empty hql as "all issues" (§14 case 22)', () => {
    const f = buildDynamicCommands(input({ query: 'bugs', filters: [FILTER] })).find(c => c.section === 'filters')!
    expect(f.sublabel).toBe('all issues')
    expect(navTarget(f)).toBe('/w/w1/search?q=')
  })

  it('credits a shared filter to its owner', () => {
    const shared: SavedFilter = { ...FILTER, id: 'f2', hql: 'status = "To Do"', mine: false, ownerName: 'Ann Lee' }
    const f = buildDynamicCommands(input({ query: 'bugs', filters: [shared] })).find(c => c.section === 'filters')!
    expect(f.sublabel).toBe('status = "To Do" · Ann Lee')
  })

  it('surfaces the loading and error states as ONE disabled row each, leaking no detail', () => {
    const loading = buildDynamicCommands(input({ query: 'bug', issuesState: 'loading' }))
      .filter(c => c.section === 'issues')
    expect(loading).toHaveLength(1)
    expect(loading[0].disabled).toBe(true)

    const failed = buildDynamicCommands(input({ query: 'bug', issuesState: 'error' }))
      .filter(c => c.section === 'issues')
    expect(failed).toHaveLength(1)
    expect(failed[0].label).toBe('Issue search unavailable')
    expect(failed[0].disabled).toBe(true)
    // Other sections survive an issue-search failure (§14 case 10).
    const all = buildDynamicCommands(input({ query: 'boa', issuesState: 'error', projects: [PROJ] }))
    expect(ids(all)).toContain('project:p1')
    expect(ids(all)).toContain('info.searchAll')
  })

  it('emits nothing workspace-scoped — including the info row — without a workspace (§14 case 20)', () => {
    const rows = buildDynamicCommands(input({
      wsId: null, query: 'boa', projects: [PROJ], filters: [FILTER], members: [MEMBER],
      fastPath: { key: 'BOA-1', projectId: 'p1', number: 1 },
    }))
    expect(ids(rows)).toEqual(['workspace:w1']) // only the user's own membership list
  })
})

describe('section contract (§6.4/§5.3)', () => {
  it('pins the fixed section order and the documented caps', () => {
    expect(SECTION_ORDER).toEqual([
      'open', 'recent', 'actions', 'nav', 'projects', 'filters', 'issues', 'workspaces', 'people', 'info',
    ])
    expect(SECTION_CAPS).toMatchObject({ open: 1, recent: 5, projects: 5, filters: 5, issues: 6, workspaces: 3, people: 3, info: 1 })
    // Actions/Navigation are the only always-on sections.
    expect(DYNAMIC_SECTIONS).not.toContain('actions')
    expect(DYNAMIC_SECTIONS).not.toContain('nav')
  })
})

describe('resolvePaletteWorkspaceId (§3.4 fallback chain)', () => {
  it('prefers the route workspace, then the recency journal, then the first membership', () => {
    expect(resolvePaletteWorkspaceId('route', 'last', [WS])).toBe('route')
    expect(resolvePaletteWorkspaceId(null, 'last', [WS])).toBe('last')
    expect(resolvePaletteWorkspaceId(null, null, [WS])).toBe('w1')
    expect(resolvePaletteWorkspaceId(null, null, [])).toBeNull()
    expect(resolvePaletteWorkspaceId(undefined, undefined, undefined)).toBeNull()
  })
})
