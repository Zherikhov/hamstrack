import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, act, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import CommandPalette from './CommandPalette'
import ShortcutsHelp from './ShortcutsHelp'
import { useUiStore } from '../uiStore'
import { useAuthStore } from '../auth'
import {
  PROJECT_ADMIN_PERMISSIONS, PROJECT_CONTRIBUTOR_PERMISSIONS,
  WORKSPACE_ADMIN_PERMISSIONS, WORKSPACE_MEMBER_PERMISSIONS,
} from '../test/permissions'
import type { Issue, Project, SavedFilter, SearchResultRow, User, Workspace, WorkspaceMember } from '../types'

// HD-39 §17.1 (25–42). The palette is a jumper: what matters is that the right
// rows appear for the right user, that the keyboard alone can reach all of them,
// and that nothing user-typed is ever interpolated raw into an HQL query.

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me', systemRole: 'USER' }
const ADMIN: User = { ...ME, systemRole: 'ADMIN' }

const WS: Workspace = {
  id: 'w1', name: 'Acme', slug: 'acme', myRole: 'OWNER',
  myPermissions: WORKSPACE_ADMIN_PERMISSIONS, createdAt: '2026-01-01T00:00:00Z',
}
const WS_MEMBER_ONLY: Workspace = {
  ...WS, myRole: 'MEMBER', myPermissions: WORKSPACE_MEMBER_PERMISSIONS,
}

const PROJ_BOATS: Project = {
  id: 'p1', workspaceId: 'w1', name: 'Boats', key: 'BOA',
  archived: false, myRole: 'MEMBER', myPermissions: PROJECT_CONTRIBUTOR_PERMISSIONS,
  createdAt: '2026-01-01T00:00:00Z',
}
const PROJ_PAYMENTS: Project = { ...PROJ_BOATS, id: 'p2', name: 'Payments', key: 'PAY' }
const PROJ_ARCHIVED: Project = { ...PROJ_BOATS, id: 'p3', name: 'Boatyard (old)', key: 'OLD', archived: true }

const FILTER: SavedFilter = {
  id: 'f1', name: 'My open bugs', hql: 'status = "To Do"', shared: false,
  ownerId: ME.id, ownerName: 'Me', mine: true,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
}

const MEMBER: WorkspaceMember = {
  userId: 'u-ann', email: 'ann@example.com', displayName: 'Ann Lee',
  roleId: 'r-ws-member', role: 'MEMBER',
}

const ISSUE: Issue = {
  id: 'i1', number: 42, key: 'BOA-42', title: 'Hull leaks under load',
  type: { id: 't1', name: 'Bug', color: '#c00', position: 0, hierarchyLevel: 1 },
  status: { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 },
  priority: { id: 'pr1', name: 'Medium', color: '#888' },
  reporter: { id: 'u1', displayName: 'Rep' },
  childCount: 0, doneChildCount: 0, fields: [], version: 1,
  createdAt: '2026-08-10T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z',
}
const SEARCH_ROW: SearchResultRow = {
  issue: ISSUE, projectId: PROJ_BOATS.id, projectKey: 'BOA', projectName: 'Boats',
}

// Every imported binding must be stubbed — the module is fully replaced. The
// error class is declared INSIDE the factory: vi.mock is hoisted above every
// top-level declaration in this file.
vi.mock('../api', () => ({
  ApiResponseError: class ApiResponseError extends Error {
    constructor(public status: number, public detail: string) { super(detail) }
  },
  apiListWorkspaces: vi.fn(),
  apiListProjects: vi.fn(),
  apiListWorkspaceMembers: vi.fn(),
  apiGetIssue: vi.fn(),
  apiSearch: vi.fn(),
  savedFilters: { list: vi.fn() },
}))

import { ApiResponseError, apiGetIssue, apiListProjects, apiListWorkspaceMembers, apiListWorkspaces, apiSearch, savedFilters } from '../api'

const mocks = {
  workspaces: vi.mocked(apiListWorkspaces),
  projects: vi.mocked(apiListProjects),
  members: vi.mocked(apiListWorkspaceMembers),
  issue: vi.mocked(apiGetIssue),
  search: vi.mocked(apiSearch),
  filters: vi.mocked(savedFilters.list),
}

function PathProbe() {
  const loc = useLocation()
  return <div data-testid="path">{loc.pathname + loc.search}</div>
}

function renderPalette() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/home']}>
        <button data-testid="opener">opener</button>
        <Routes><Route path="*" element={<PathProbe />} /></Routes>
        <CommandPalette />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const openPalette = () => act(() => { useUiStore.setState({ paletteOpen: true, paletteRestoreFocus: true }) })
const input = () => screen.getByRole('combobox')
const rowNames = () => screen.getAllByRole('option').map(o => o.textContent ?? '')
const activeRow = () => screen.getAllByRole('option').find(o => o.getAttribute('aria-selected') === 'true')

beforeAll(() => {
  // jsdom has no matchMedia; `useReducedMotion` queries prefers-reduced-motion.
  if (typeof window.matchMedia !== 'function') {
    window.matchMedia = ((query: string) => ({
      matches: false, media: query, onchange: null,
      addEventListener: () => {}, removeEventListener: () => {},
      addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia
  }
})

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  useAuthStore.setState({ user: ME })
  useUiStore.setState({
    createIssueOpen: false, createIssuePreset: undefined,
    paletteOpen: false, paletteRestoreFocus: true,
    helpOpen: false, helpFocusReturn: null, searchFocusNonce: 0,
  })
  mocks.workspaces.mockResolvedValue([WS])
  mocks.projects.mockResolvedValue([PROJ_BOATS, PROJ_PAYMENTS, PROJ_ARCHIVED])
  mocks.filters.mockResolvedValue([FILTER])
  mocks.members.mockResolvedValue([MEMBER])
  mocks.search.mockResolvedValue({ content: [SEARCH_ROW], page: 0, size: 6, totalElements: 1, totalPages: 1, hasNext: false })
  mocks.issue.mockResolvedValue(ISSUE)
})

afterEach(() => { vi.useRealTimers() })

describe('CommandPalette — shell & a11y', () => {
  it('renders the combobox/listbox structure and focuses the input on open', async () => {
    renderPalette()
    openPalette()
    expect(screen.getByRole('dialog', { name: 'Command palette' })).toBeInTheDocument()
    expect(screen.getByRole('listbox', { name: 'Results' })).toBeInTheDocument()
    await waitFor(() => expect(input()).toHaveFocus())
    expect(input()).toHaveAttribute('aria-expanded', 'true')
    expect(input()).toHaveAttribute('aria-controls', 'cmdk-list')
  })

  it('lists Actions and Navigation with an empty query and fires NO issue search', async () => {
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')
    expect(screen.getByText('Go to Home')).toBeInTheDocument()
    expect(screen.getByText('Go to My work')).toBeInTheDocument()
    expect(screen.getByText('Actions')).toBeInTheDocument()
    expect(screen.getByText('Navigation')).toBeInTheDocument()
    // An empty HQL would match EVERY issue in the workspace — never send one.
    expect(mocks.search).not.toHaveBeenCalled()
    // Dynamic sections stay hidden until something is typed.
    expect(screen.queryByText('Projects')).not.toBeInTheDocument()
  })
})

describe('CommandPalette — matching & sections', () => {
  it('filters to matching rows and drops the rest', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.type(input(), 'boa')

    await waitFor(() => expect(screen.getByText('Boats')).toBeInTheDocument())
    expect(screen.getByText('Projects')).toBeInTheDocument()
    // Archived projects are excluded from the Projects section.
    expect(screen.queryByText('Boatyard (old)')).not.toBeInTheDocument()
    // A row that cannot match "boa" is gone.
    expect(screen.queryByText('Go to My work')).not.toBeInTheDocument()
  })

  it('always ends a non-empty query with a selectable "Search all issues" row', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.type(input(), 'boa')
    const info = await screen.findByText('Search all issues for "boa"')
    expect(info.closest('[role="option"]')).not.toHaveAttribute('aria-disabled')
  })

  it('shows the People section for a member, jumping to an HQL-escaped assignee search', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.type(input(), 'ann')
    const row = await screen.findByText('Issues assigned to Ann Lee')
    await user.click(row)
    await waitFor(() => expect(screen.getByTestId('path').textContent)
      .toBe(`/w/w1/search?q=${encodeURIComponent('assignee = "ann@example.com"')}`))
  })

  it('renders an empty state when nothing matches', async () => {
    const user = userEvent.setup()
    mocks.search.mockResolvedValue({ content: [], page: 0, size: 6, totalElements: 0, totalPages: 0, hasNext: false })
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.type(input(), 'zzzqqq')
    await screen.findByText('Search all issues for "zzzqqq"')
    // The escape hatch is the only row left, alongside the empty-state message.
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(1))
    expect(screen.getByText('No matches for "zzzqqq"')).toBeInTheDocument()
  })
})

describe('CommandPalette — keyboard', () => {
  it('moves the selection with the arrow keys and wraps at both ends', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    // Wait for the workspace-dependent rows to land before snapshotting the list.
    await screen.findByText('Workspace settings')

    const names = rowNames()
    expect(activeRow()?.textContent).toBe(names[0])

    await user.keyboard('{ArrowDown}')
    expect(activeRow()?.textContent).toBe(names[1])
    expect(input()).toHaveAttribute('aria-activedescendant', activeRow()!.id)

    await user.keyboard('{ArrowUp}{ArrowUp}')
    expect(activeRow()?.textContent).toBe(names[names.length - 1]) // wrapped
    await user.keyboard('{ArrowDown}')
    expect(activeRow()?.textContent).toBe(names[0])               // wrapped back
  })

  it('Tab and Shift+Tab move the selection without moving DOM focus', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')
    const names = rowNames()

    await user.keyboard('{Tab}')
    expect(activeRow()?.textContent).toBe(names[1])
    expect(input()).toHaveFocus()

    await user.keyboard('{Shift>}{Tab}{/Shift}')
    expect(activeRow()?.textContent).toBe(names[0])
    expect(input()).toHaveFocus()
  })

  it('Enter on a navigation row navigates and closes the palette', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Go to My work')

    await user.type(input(), 'my work')
    await waitFor(() => expect(activeRow()?.textContent).toContain('Go to My work'))
    await user.keyboard('{Enter}')

    expect(useUiStore.getState().paletteOpen).toBe(false)
    expect(screen.getByTestId('path')).toHaveTextContent('/my-work')
  })

  it('Enter on Create issue closes the palette and opens the create dialog', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.keyboard('{Enter}')
    expect(useUiStore.getState().paletteOpen).toBe(false)
    expect(useUiStore.getState().createIssueOpen).toBe(true)
  })

  it('Escape closes and restores focus to the element that had it', async () => {
    const user = userEvent.setup()
    renderPalette()
    const opener = screen.getByTestId('opener')
    opener.focus()
    openPalette()
    await waitFor(() => expect(input()).toHaveFocus())

    await user.keyboard('{Escape}')
    expect(useUiStore.getState().paletteOpen).toBe(false)
    await waitFor(() => expect(opener).toHaveFocus())
  })

  it('walks every selectable row exactly once, across section boundaries, skipping headers', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Workspace settings')

    const names = rowNames()
    // Two sections at least (Actions + Navigation), so the walk really crosses a
    // header — headers are presentation only and must never be selectable.
    expect(screen.getAllByRole('group').length).toBeGreaterThan(1)
    expect(names.length).toBeGreaterThan(3)

    const visited: string[] = [activeRow()!.textContent!]
    for (let i = 1; i < names.length; i++) {
      await user.keyboard('{ArrowDown}')
      visited.push(activeRow()!.textContent!)
    }
    expect(visited).toEqual(names)
    // One more step wraps back to the top (§7.1).
    await user.keyboard('{ArrowDown}')
    expect(activeRow()!.textContent).toBe(names[0])
  })

  it('PageDown/PageUp jump 5 rows and clamp at both ends (no wrap)', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Workspace settings')
    const names = rowNames()
    expect(names.length).toBeGreaterThan(5)

    await user.keyboard('{PageDown}')
    expect(activeRow()!.textContent).toBe(names[5])
    await user.keyboard('{PageDown}{PageDown}')
    expect(activeRow()!.textContent).toBe(names[names.length - 1]) // clamped, not wrapped
    await user.keyboard('{PageUp}{PageUp}{PageUp}{PageUp}')
    expect(activeRow()!.textContent).toBe(names[0])                // clamped at the top
  })

  it('never lets the arrows land on a disabled row (§17.1 item 28)', async () => {
    const user = userEvent.setup()
    mocks.issue.mockRejectedValue(new ApiResponseError(404, 'gone'))
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.type(input(), 'boa-999')
    const notFound = (await screen.findByText('BOA-999 — not found')).closest('[role="option"]')!
    // Walk the whole list twice — the disabled row must never be selected.
    for (let i = 0; i < rowNames().length * 2; i++) {
      expect(notFound).toHaveAttribute('aria-selected', 'false')
      expect(activeRow()).not.toBe(notFound)
      await user.keyboard('{ArrowDown}')
    }
    // …and Enter can therefore never run it.
    expect(useUiStore.getState().paletteOpen).toBe(true)
  })

  it('runs the row reached by arrowing alone — no mouse, no typing', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Workspace settings')

    const target = rowNames().findIndex(n => n.includes('Go to All workspaces'))
    expect(target).toBeGreaterThan(0)
    for (let i = 0; i < target; i++) await user.keyboard('{ArrowDown}')
    expect(activeRow()!.textContent).toContain('Go to All workspaces')

    await user.keyboard('{Enter}')
    expect(useUiStore.getState().paletteOpen).toBe(false)
    expect(screen.getByTestId('path')).toHaveTextContent('/workspaces')
  })

  it('Cmd/Ctrl+K while open closes it again (§14 case 2)', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.keyboard('{Meta>}k{/Meta}')
    expect(useUiStore.getState().paletteOpen).toBe(false)
  })

  it('Enter with no selectable row is a no-op (§14 case 19)', async () => {
    const user = userEvent.setup()
    // No workspace at all → no info row, so a non-matching query leaves zero rows.
    mocks.workspaces.mockResolvedValue([])
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.type(input(), 'zzzqqq')
    await waitFor(() => expect(screen.queryAllByRole('option')).toHaveLength(0))
    await user.keyboard('{Enter}')
    expect(useUiStore.getState().paletteOpen).toBe(true)
    expect(screen.getByTestId('path')).toHaveTextContent('/home')
    expect(input()).not.toHaveAttribute('aria-activedescendant')
  })

  it('closes on a backdrop click but not on a panel click', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.click(screen.getByRole('dialog'))
    expect(useUiStore.getState().paletteOpen).toBe(true)

    const backdrop = screen.getByRole('dialog').parentElement!
    await user.click(backdrop)
    expect(useUiStore.getState().paletteOpen).toBe(false)
  })
})

describe('CommandPalette — issue search', () => {
  it('never queries below 2 characters and debounces to a single request', async () => {
    const user = userEvent.setup()
    const settle = () => act(() => new Promise<void>(r => setTimeout(r, 450)))
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    // 1 character is below the floor — no request, ever.
    await user.type(input(), 'b')
    await settle()
    expect(mocks.search).not.toHaveBeenCalled()

    // Typing the rest quickly coalesces into exactly ONE request.
    await user.type(input(), 'ug')
    await waitFor(() => expect(mocks.search).toHaveBeenCalledTimes(1))
    await settle()
    expect(mocks.search).toHaveBeenCalledTimes(1)
  })

  it('emits exactly `text ~ "…"`, with quotes escaped', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.type(input(), 'bug')
    await waitFor(() => expect(mocks.search).toHaveBeenCalled())
    expect(mocks.search).toHaveBeenCalledWith('w1', { query: 'text ~ "bug"', page: 0, size: 6 })

    mocks.search.mockClear()
    await user.clear(input())
    await user.type(input(), 'he "x"')
    await waitFor(() => expect(mocks.search).toHaveBeenCalled())
    const calls = mocks.search.mock.calls
    expect(calls[calls.length - 1][1].query).toBe('text ~ "he \\"x\\""')
  })

  it('surfaces a search failure as one muted row and leaves other sections intact', async () => {
    const user = userEvent.setup()
    mocks.search.mockRejectedValue(new ApiResponseError(404, 'nope'))
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.type(input(), 'boa')
    const row = await screen.findByText('Issue search unavailable')
    expect(row.closest('[role="option"]')).toHaveAttribute('aria-disabled', 'true')
    // No status/HQL detail leaks, and the rest of the palette still works.
    expect(screen.queryByText(/404/)).not.toBeInTheDocument()
    expect(screen.getByText('Boats')).toBeInTheDocument()
  })
})

describe('CommandPalette — issue-key fast path', () => {
  it('offers the row immediately and enriches it with the title', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.type(input(), 'boa-42')
    await waitFor(() => expect(mocks.issue).toHaveBeenCalledWith('w1', 'p1', 42))
    await waitFor(() => expect(screen.getByText('Open BOA-42 — Hull leaks under load')).toBeInTheDocument())
    // It heads the list, above everything else.
    expect(rowNames()[0]).toContain('Open BOA-42')
  })

  it('renders a disabled "not found" row on 404 and skips it with the arrows', async () => {
    const user = userEvent.setup()
    mocks.issue.mockRejectedValue(new ApiResponseError(404, 'gone'))
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.type(input(), 'boa-999')
    const row = await screen.findByText('BOA-999 — not found')
    const option = row.closest('[role="option"]')!
    expect(option).toHaveAttribute('aria-disabled', 'true')
    expect(activeRow()).not.toBe(option)
  })

  it('debounces the lookup: one request for a key typed character by character', async () => {
    const user = userEvent.setup()
    const settle = () => act(() => new Promise<void>(r => setTimeout(r, 450)))
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    // `boa-4`, `boa-42`, `boa-421` and `boa-4212` are all valid keys, so an
    // undebounced fast path would issue four GETs, three of them 404s.
    await user.type(input(), 'boa-4212')
    // The optimistic row is still immediate — it tracks the live text.
    expect(screen.getByText('Open BOA-4212')).toBeInTheDocument()

    await waitFor(() => expect(mocks.issue).toHaveBeenCalled())
    await settle()
    expect(mocks.issue).toHaveBeenCalledTimes(1)
    expect(mocks.issue).toHaveBeenCalledWith('w1', 'p1', 4212)
  })

  it('renders no fast-path row for an unknown project key', async () => {
    const user = userEvent.setup()
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')

    await user.type(input(), 'zz-1')
    await waitFor(() => expect(screen.getByText('Search all issues for "zz-1"')).toBeInTheDocument())
    expect(screen.queryByText(/Open ZZ-1/)).not.toBeInTheDocument()
    expect(mocks.issue).not.toHaveBeenCalled()
  })
})

describe('CommandPalette — permissions & persistence', () => {
  it('hides System administration from a non-admin and shows it to an ADMIN', async () => {
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')
    expect(screen.queryByText('System administration')).not.toBeInTheDocument()

    act(() => { useAuthStore.setState({ user: ADMIN }) })
    await waitFor(() => expect(screen.getByText('System administration')).toBeInTheDocument())
  })

  it('hides Workspace settings from a workspace MEMBER', async () => {
    mocks.workspaces.mockResolvedValue([WS_MEMBER_ONLY])
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')
    await waitFor(() => expect(screen.getByText('Go to Workspace overview')).toBeInTheDocument())
    expect(screen.queryByText('Workspace settings')).not.toBeInTheDocument()
  })

  it('hides Project settings from a project MEMBER and shows it to a MANAGER', async () => {
    // The palette resolves a "current project" from the recency journal on
    // non-project routes, exactly like the rail does (§3.4 / §20 Q1).
    localStorage.setItem(
      `hamstrack.recent-projects.${ME.id}`,
      JSON.stringify([{ wsId: 'w1', projectId: 'p1', key: 'BOA', name: 'Boats', visitedAt: Date.now() }]),
    )
    const asMember = renderPalette()
    openPalette()
    await screen.findByText('Go to Board — Boats')
    expect(screen.queryByText(/^Project settings/)).not.toBeInTheDocument()
    asMember.unmount()

    mocks.projects.mockResolvedValue([
      { ...PROJ_BOATS, myPermissions: PROJECT_ADMIN_PERMISSIONS }, PROJ_PAYMENTS,
    ])
    renderPalette()
    openPalette()
    await waitFor(() => expect(screen.getByText('Project settings — Boats')).toBeInTheDocument())
  })

  it('records a run static command as Recent, and ignores unknown persisted ids', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `hamstrack.ui-prefs.${ME.id}`,
      JSON.stringify({ paletteRecentIds: ['nav.thisCommandNoLongerExists'] }),
    )
    const first = renderPalette()
    openPalette()
    await screen.findByText('Go to Home')
    // The unknown id produced no Recent section.
    expect(screen.queryByText('Recent')).not.toBeInTheDocument()

    await user.type(input(), 'home')
    await waitFor(() => expect(activeRow()?.textContent).toContain('Go to Home'))
    await user.keyboard('{Enter}')

    expect(JSON.parse(localStorage.getItem(`hamstrack.ui-prefs.${ME.id}`)!).paletteRecentIds)
      .toEqual(['nav.home', 'nav.thisCommandNoLongerExists'])

    first.unmount()
    renderPalette()
    openPalette()
    const recent = await screen.findByText('Recent')
    const group = recent.parentElement!
    expect(within(group).getByText('Go to Home')).toBeInTheDocument()
  })

  it('survives a corrupt persisted recents value and degrades to no recents', async () => {
    const user = userEvent.setup()
    // localStorage is user-writable: any shape can come back. A non-array here
    // used to reach `.map()` during render and take the palette down for good.
    localStorage.setItem(
      `hamstrack.ui-prefs.${ME.id}`,
      JSON.stringify({ paletteRecentIds: 'nav.home', railWidth: 240 }),
    )
    renderPalette()
    openPalette()
    await screen.findByText('Go to Home')
    expect(screen.queryByText('Recent')).not.toBeInTheDocument()

    // …and the next run repairs the stored value instead of appending to junk.
    await user.type(input(), 'home')
    await waitFor(() => expect(activeRow()?.textContent).toContain('Go to Home'))
    await user.keyboard('{Enter}')

    const stored = JSON.parse(localStorage.getItem(`hamstrack.ui-prefs.${ME.id}`)!)
    expect(stored.paletteRecentIds).toEqual(['nav.home'])
    // Unrelated preferences are untouched.
    expect(stored.railWidth).toBe(240)
  })

  it('with no resolvable workspace shows the note and makes no workspace-scoped call', async () => {
    mocks.workspaces.mockResolvedValue([])
    renderPalette()
    openPalette()
    await screen.findByText('Create issue')
    await waitFor(() => expect(screen.getByText('Join or create a workspace to search issues.')).toBeInTheDocument())

    expect(mocks.projects).not.toHaveBeenCalled()
    expect(mocks.filters).not.toHaveBeenCalled()
    expect(mocks.members).not.toHaveBeenCalled()
    expect(mocks.search).not.toHaveBeenCalled()
    // Workspace-free rows are still offered.
    expect(screen.getByText('Go to Home')).toBeInTheDocument()
    expect(screen.queryByText('Search issues (HQL)')).not.toBeInTheDocument()
  })
})

// The palette and the `?` sheet are the two single-element focus traps in the
// app, and the palette can hand over to the sheet. Both handovers are tested
// here because neither component can show it alone.
describe('CommandPalette — handing over to the shortcuts sheet', () => {
  function renderPaletteWithHelp() {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/home']}>
          <button data-testid="opener">opener</button>
          <CommandPalette />
          <ShortcutsHelp />
        </MemoryRouter>
      </QueryClientProvider>,
    )
  }

  const helpDialog = () => screen.queryByRole('dialog', { name: 'Keyboard shortcuts' })

  it('never stacks the sheet on the open palette, but its own command still opens it', async () => {
    const user = userEvent.setup()
    renderPaletteWithHelp()
    openPalette()
    await screen.findByText('Keyboard shortcuts')

    // Store-level guard: a future call site that opens help while the palette is
    // up would leave two focus traps fighting over focus forever.
    act(() => { useUiStore.getState().openHelp() })
    expect(useUiStore.getState().helpOpen).toBe(false)
    expect(helpDialog()).not.toBeInTheDocument()

    // The palette's own row still works — it closes FIRST, in the same handler,
    // so the guard sees a palette that is already shut.
    await user.click(screen.getByText('Keyboard shortcuts'))
    expect(useUiStore.getState().paletteOpen).toBe(false)
    expect(useUiStore.getState().helpOpen).toBe(true)
    await waitFor(() => expect(helpDialog()).toBeInTheDocument())
  })

  it('returns focus to the pre-palette element after the sheet is dismissed', async () => {
    const user = userEvent.setup()
    renderPaletteWithHelp()
    const opener = screen.getByTestId('opener')
    act(() => { opener.focus() })

    openPalette()
    await waitFor(() => expect(input()).toHaveFocus())

    // The palette skips focus restoration here (a command took over), so the
    // sheet would otherwise capture <body> and dump the keyboard user at the top.
    await user.click(await screen.findByText('Keyboard shortcuts'))
    const closeBtn = await screen.findByRole('button', { name: 'Close' })
    await waitFor(() => expect(closeBtn).toHaveFocus())

    await user.keyboard('{Escape}')
    expect(useUiStore.getState().helpOpen).toBe(false)
    await waitFor(() => expect(opener).toHaveFocus())
  })
})
