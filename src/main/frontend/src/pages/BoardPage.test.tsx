import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import BoardPage from './BoardPage'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import type { BoardQuickFilters } from '../uiPrefs'
import type { Issue, IssueType, PriorityOption, Status, User } from '../types'

// HD-43 (revised): the board's client-side quick filters — two assignee chips
// plus a single-selection type <Select> shaped like the priority filter. The
// contract under test is the composition semantics, not the pixels:
//   • assignee dimension — "My issues" OR "Unassigned";
//   • type dimension — one selected issue type (or "All types");
//   • the two dimensions AND together;
//   • and the whole thing ANDs with the (server-side) priority filter, which
//     stays a query param and is NOT part of the client-side filtering.
// Plus: per-user/per-project persistence in localStorage, a stale (or legacy
// multi-select) persisted type being ignored, and the "everything hidden" empty
// state. HD-70: no "New issue" button in the board header — creation is the nav
// rail's job plus an always-visible per-column "+".

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }
const OTHER = { id: 'u-other', displayName: 'Other Person' }

const TODO: Status = { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 }
const DOING: Status = { id: 's2', name: 'In Progress', color: '#09f', category: 'IN_PROGRESS', position: 1 }

const TASK: IssueType = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const BUG: IssueType = { id: 't2', name: 'Bug', color: '#c00', position: 1, hierarchyLevel: 1 }

const PRIORITY: PriorityOption = { id: 'pr1', name: 'Medium', color: '#888', isDefault: true }

// HD-31: one project component, carried by a single fixture issue — enough to
// assert both the card display rule and the server-side filter round-trip.
const BILLING = { id: 'c1', name: 'Billing', archived: false }

let n = 0
function issue(
  title: string,
  type: IssueType,
  status: Status,
  assignee: { id: string; displayName: string } | undefined,
  component?: { id: string; name: string; archived: boolean },
): Issue {
  n += 1
  return {
    id: `i${n}`, number: n, key: `PR-${n}`, title,
    type, status, priority: PRIORITY,
    assignee,
    reporter: OTHER,
    childCount: 0, doneChildCount: 0,
    component,
    fields: [], version: 1,
    createdAt: '2026-08-10T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z',
  }
}

// 5 issues across both assignee buckets and both types, so every combination of
// chips selects a *different*, non-trivial subset.
const MINE_TASK = issue('Mine task', TASK, TODO, ME, BILLING)
const MINE_BUG = issue('Mine bug', BUG, TODO, ME)
const OTHER_TASK = issue('Other task', TASK, DOING, OTHER)
const OTHER_BUG = issue('Other bug', BUG, TODO, OTHER)
const UNASSIGNED_BUG = issue('Unassigned bug', BUG, DOING, undefined)
const ALL_ISSUES = [MINE_TASK, MINE_BUG, OTHER_TASK, OTHER_BUG, UNASSIGNED_BUG]

const apiListIssuesMock = vi.fn(async () => ({
  issues: ALL_ISSUES, truncated: false, totalAvailable: ALL_ISSUES.length, cap: 100,
}))

vi.mock('../api', () => ({
  ApiResponseError: class ApiResponseError extends Error { status = 0 },
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [TODO, DOING],
    transitions: [],
    priorities: [PRIORITY],
    issueTypes: [TASK, BUG],
    fields: [],
  })),
  apiListIssues: (...args: unknown[]) => apiListIssuesMock(...(args as [])),
  // Pulled in by IssueDetail (rendered through IssueSidePanel) — unused here,
  // but a mocked module must still expose every imported binding.
  apiGetIssue: vi.fn(),
  apiUpdateIssue: vi.fn(),
  apiDeleteIssue: vi.fn(),
  apiListComments: vi.fn(async () => ({ content: [] })),
  apiCreateComment: vi.fn(),
  apiDeleteComment: vi.fn(),
  apiListAttachments: vi.fn(async () => []),
  apiUploadAttachment: vi.fn(),
  apiDownloadAttachment: vi.fn(),
  apiDeleteAttachment: vi.fn(),
  apiGetIssueHistory: vi.fn(async () => ({ content: [] })),
  apiListWorkspaceMembers: vi.fn(async () => []),
  apiGetIssueChildren: vi.fn(async () => []),
  // HD-30: the (server-side) label filter and the drawer's label picker read the
  // workspace's labels through this group.
  labelsApi: { list: vi.fn(async () => []), create: vi.fn() },
  // HD-31: the (server-side) component filter and the drawer's Component cell
  // read the project's components through this one.
  componentsApi: { list: vi.fn(async () => [BILLING]), update: vi.fn() },
  // HD-32: the (server-side) fix-version filter and the drawer's version cells
  // read the project's versions through this one.
  versionsApi: { list: vi.fn(async () => []) },
}))

beforeAll(() => {
  // jsdom ships no ResizeObserver; the issue panel in the board's drawer observes.
  if (!('ResizeObserver' in globalThis)) {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
  // …and no matchMedia; `useReducedMotion` queries prefers-reduced-motion.
  if (typeof window.matchMedia !== 'function') {
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia
  }
})

beforeEach(() => {
  localStorage.clear()
  apiListIssuesMock.mockClear()
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
  useUiStore.setState({ createIssueOpen: false, createIssuePreset: undefined })
})

function renderBoard() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/p/${PROJECT_ID}/board`]}>
        <Routes>
          <Route path="/w/:wsId/p/:projectId/board" element={<BoardPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Wait for the board data + project config to land (the type select and the
 *  columns are config-driven). The counter's "…5 issues" tail is the load signal
 *  — it holds whether or not restored filters are already hiding cards. */
async function boardReady() {
  expect(await screen.findByRole('button', { name: 'My issues' })).toBeInTheDocument()
  // A workflow column header only renders once the project config resolved.
  await screen.findByText('In Progress')
  await screen.findByText(/(^|of )5 issues$/)
}

const chip = (name: string) => screen.getByRole('button', { name })

/** The type filter — the custom <Select> (a button + a listbox popover). */
const typeFilter = () => screen.getByRole('button', { name: 'Filter by type' })

/** Open the type filter and pick an option by its visible label. */
async function selectType(optionLabel: string) {
  await userEvent.click(typeFilter())
  await userEvent.click(await screen.findByRole('option', { name: optionLabel }))
}

/** Titles of the issue cards currently on the board, order-insensitive. */
function visibleTitles() {
  return ALL_ISSUES.filter(i => screen.queryByText(i.title) !== null).map(i => i.title).sort()
}

function savedQuick(userId = ME.id, projectId = PROJECT_ID): BoardQuickFilters | undefined {
  const raw = localStorage.getItem(`hamstrack.ui-prefs.${userId}`)
  if (!raw) return undefined
  return (JSON.parse(raw).boardQuickFilters ?? {})[projectId]
}

function seedQuick(quick: BoardQuickFilters, userId = ME.id, projectId = PROJECT_ID) {
  seedRawQuick(quick as Record<string, unknown>, userId, projectId)
}

/** Seed an arbitrary (e.g. legacy-shaped) persisted entry. */
function seedRawQuick(quick: Record<string, unknown>, userId = ME.id, projectId = PROJECT_ID) {
  localStorage.setItem(
    `hamstrack.ui-prefs.${userId}`,
    JSON.stringify({ boardQuickFilters: { [projectId]: quick } }),
  )
}

describe('BoardPage quick filters (HD-43)', () => {
  it('shows every issue and the plain counter with nothing selected', async () => {
    renderBoard()
    await boardReady()

    expect(visibleTitles()).toEqual(ALL_ISSUES.map(i => i.title).sort())
    expect(screen.getByText('5 issues')).toBeInTheDocument()
    // Chips are accessible toggles, both off…
    expect(chip('My issues')).toHaveAttribute('aria-pressed', 'false')
    expect(chip('Unassigned')).toHaveAttribute('aria-pressed', 'false')
    // …and the type dimension is a select resting on "All types" (no type chips).
    expect(typeFilter()).toHaveTextContent('All types')
    expect(screen.queryByRole('button', { name: 'Bug' })).not.toBeInTheDocument()
  })

  it('offers every project issue type in the type select', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(typeFilter())
    const options = (await screen.findAllByRole('option')).map(o => o.textContent)
    expect(options).toEqual(['All types', TASK.name, BUG.name])
  })

  it('"My issues" keeps only the current user\'s issues and switches the counter', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(chip('My issues'))

    await waitFor(() => expect(visibleTitles()).toEqual([MINE_BUG.title, MINE_TASK.title].sort()))
    expect(chip('My issues')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('2 of 5 issues')).toBeInTheDocument()
    // The priority filter stays server-side: no refetch is triggered by chips.
    expect(apiListIssuesMock).toHaveBeenCalledTimes(1)
  })

  it('ORs "My issues" with "Unassigned" within the assignee dimension', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(chip('My issues'))
    await userEvent.click(chip('Unassigned'))

    // mine OR unassigned — NOT the (empty) intersection.
    await waitFor(() => expect(visibleTitles())
      .toEqual([MINE_TASK.title, MINE_BUG.title, UNASSIGNED_BUG.title].sort()))
    expect(screen.getByText('3 of 5 issues')).toBeInTheDocument()
    expect(chip('Unassigned')).toHaveAttribute('aria-pressed', 'true')
  })

  it('ANDs the selected type with the assignee dimension', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(chip('My issues'))
    await userEvent.click(chip('Unassigned'))
    await selectType(BUG.name)

    // (mine OR unassigned) AND type=Bug
    await waitFor(() => expect(visibleTitles())
      .toEqual([MINE_BUG.title, UNASSIGNED_BUG.title].sort()))
    expect(screen.getByText('2 of 5 issues')).toBeInTheDocument()
    expect(typeFilter()).toHaveTextContent(BUG.name)

    // Switching the type REPLACES the selection (it is single-select now):
    // (mine OR unassigned) AND type=Task.
    await selectType(TASK.name)
    await waitFor(() => expect(visibleTitles()).toEqual([MINE_TASK.title]))
    expect(screen.getByText('1 of 5 issues')).toBeInTheDocument()

    // …and "All types" drops the type dimension entirely.
    await selectType('All types')
    await waitFor(() => expect(visibleTitles())
      .toEqual([MINE_TASK.title, MINE_BUG.title, UNASSIGNED_BUG.title].sort()))
    expect(screen.getByText('3 of 5 issues')).toBeInTheDocument()
  })

  it('filters by type alone when no assignee chip is on', async () => {
    renderBoard()
    await boardReady()

    await selectType(TASK.name)

    await waitFor(() => expect(visibleTitles()).toEqual([MINE_TASK.title, OTHER_TASK.title].sort()))
    expect(screen.getByText('2 of 5 issues')).toBeInTheDocument()
    // Client-side only — selecting a type never refetches the board.
    expect(apiListIssuesMock).toHaveBeenCalledTimes(1)
  })

  it('shows the empty state when the filters hide every issue, and resets from it', async () => {
    renderBoard()
    await boardReady()

    // Unassigned AND type=Task — no such issue exists in the fixture.
    await userEvent.click(chip('Unassigned'))
    await selectType(TASK.name)

    expect(await screen.findByText('No issues match the current filters.')).toBeInTheDocument()
    expect(visibleTitles()).toEqual([])
    expect(screen.getByText('0 of 5 issues')).toBeInTheDocument()
    // Not a board of empty columns: the kanban columns are gone entirely.
    expect(screen.queryByText('No issues')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /reset quick filters/i }))

    await waitFor(() => expect(visibleTitles()).toEqual(ALL_ISSUES.map(i => i.title).sort()))
    expect(screen.getByText('5 issues')).toBeInTheDocument()
    expect(chip('Unassigned')).toHaveAttribute('aria-pressed', 'false')
    expect(typeFilter()).toHaveTextContent('All types')
    expect(savedQuick()).toBeUndefined()   // reset clears the persisted entry
  })

  it('"Clear filters" resets the priority select, the chips and the type select', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(chip('My issues'))
    await selectType(BUG.name)
    // The priority filter is server-side — changing it refetches.
    await userEvent.click(screen.getByRole('button', { name: 'Filter by priority' }))
    await userEvent.click(await screen.findByRole('option', { name: PRIORITY.name }))
    await waitFor(() => expect(apiListIssuesMock).toHaveBeenCalledTimes(2))

    await userEvent.click(screen.getByRole('button', { name: /clear filters/i }))

    await waitFor(() => expect(visibleTitles()).toEqual(ALL_ISSUES.map(i => i.title).sort()))
    expect(chip('My issues')).toHaveAttribute('aria-pressed', 'false')
    expect(typeFilter()).toHaveTextContent('All types')
    expect(screen.getByRole('button', { name: 'Filter by priority' })).toHaveTextContent('All priorities')
    expect(screen.queryByRole('button', { name: /clear filters/i })).not.toBeInTheDocument()
  })

  it('persists the selection per user + project and restores it on a fresh render', async () => {
    const first = renderBoard()
    await boardReady()

    await userEvent.click(chip('My issues'))
    await selectType(BUG.name)

    await waitFor(() => expect(savedQuick()).toEqual({ mine: true, unassigned: false, typeId: BUG.id }))
    first.unmount()

    renderBoard()
    await boardReady()

    await waitFor(() => expect(chip('My issues')).toHaveAttribute('aria-pressed', 'true'))
    expect(typeFilter()).toHaveTextContent(BUG.name)
    expect(chip('Unassigned')).toHaveAttribute('aria-pressed', 'false')
    expect(visibleTitles()).toEqual([MINE_BUG.title])
    expect(screen.getByText('1 of 5 issues')).toBeInTheDocument()
  })

  it('ignores a persisted type id that no longer exists in the project config', async () => {
    seedQuick({ mine: false, unassigned: false, typeId: 't-deleted' })

    renderBoard()
    await boardReady()

    // The stale id must not filter the board away — it degrades to "no type filter".
    await waitFor(() => expect(visibleTitles()).toEqual(ALL_ISSUES.map(i => i.title).sort()))
    expect(screen.queryByText('No issues match the current filters.')).not.toBeInTheDocument()
    expect(screen.getByText('5 issues')).toBeInTheDocument()
    expect(typeFilter()).toHaveTextContent('All types')

    // …and the next change writes back a cleaned entry, without the stale id.
    await selectType(BUG.name)
    await waitFor(() => expect(savedQuick()?.typeId).toEqual(BUG.id))
  })

  it('ignores a legacy multi-select `typeIds` entry and drops it on the next write', async () => {
    // Pre-revision shape: HD-43 originally persisted an array of type ids.
    seedRawQuick({ mine: false, unassigned: false, typeIds: [BUG.id] })

    renderBoard()
    await boardReady()

    // Not resurrected as a filter…
    await waitFor(() => expect(visibleTitles()).toEqual(ALL_ISSUES.map(i => i.title).sort()))
    expect(typeFilter()).toHaveTextContent('All types')
    expect(screen.getByText('5 issues')).toBeInTheDocument()

    // …and the next write persists the new shape only.
    await userEvent.click(chip('My issues'))
    await waitFor(() => expect(savedQuick()).toEqual({ mine: true, unassigned: false }))
    expect(savedQuick()).not.toHaveProperty('typeIds')
  })

  it('keeps another project\'s saved filters untouched', async () => {
    seedQuick({ mine: true, unassigned: false }, ME.id, 'p-other')

    renderBoard()
    await boardReady()

    // This board starts unfiltered — the other project's entry is not applied…
    expect(screen.getByText('5 issues')).toBeInTheDocument()
    await userEvent.click(chip('Unassigned'))

    // …and is still there after this board persists its own.
    await waitFor(() => expect(savedQuick()).toEqual({ mine: false, unassigned: true }))
    expect(savedQuick(ME.id, 'p-other')).toEqual({ mine: true, unassigned: false })
  })

  it('drops the filtered issues from their column counts', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(chip('My issues'))

    // Both of "my" issues live in To Do, so In Progress empties out.
    await waitFor(() => expect(visibleTitles()).toEqual([MINE_BUG.title, MINE_TASK.title].sort()))
    // `closest` is typed as Element; `within` wants an HTMLElement.
    const column = (name: string) => screen.getByText(name).closest('div.group') as HTMLElement
    expect(within(column('To Do')).getByText('2')).toBeInTheDocument()
    expect(within(column('In Progress')).getByText('No issues')).toBeInTheDocument()
    expect(screen.getAllByText('No issues')).toHaveLength(1)
  })
})

// HD-31: the component filter is SERVER-side (a query param + part of the cache
// key), unlike the HD-43 chips — and the card drops its component line while that
// filter is active, since every visible card would then repeat the same name.
describe('BoardPage component filter (HD-31)', () => {
  const componentFilter = () => screen.getByRole('button', { name: 'Filter by component' })

  /** The card of one issue — the filter button also renders the component NAME
   *  once it is selected, so the display rule has to be asserted inside a card. */
  function cardOf(title: string) {
    return screen.getByText(title).closest('div.rounded-lg') as HTMLElement
  }

  /** The (ws, project, filters) triple the board last asked the API for. */
  function lastListArgs() {
    const calls = apiListIssuesMock.mock.calls
    return calls[calls.length - 1] as unknown as
      [string, string, { componentId?: string } | undefined]
  }

  it('shows the component under the card title while unfiltered', async () => {
    renderBoard()
    await boardReady()

    expect(cardOf(MINE_TASK.title).textContent).toContain(BILLING.name)
    // …only on the card that actually has one.
    expect(cardOf(MINE_BUG.title).textContent).not.toContain(BILLING.name)
    expect(componentFilter()).toHaveTextContent('All components')
    // Nothing selected ⇒ no componentId travels to the server.
    expect(lastListArgs()[2]?.componentId).toBeUndefined()
  })

  it('refetches server-side with ?componentId and hides the now-redundant card line', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(componentFilter())
    await userEvent.click(await screen.findByRole('option', { name: BILLING.name }))

    // Server-side: a new request, carrying the id (the label filter's contract).
    await waitFor(() => expect(apiListIssuesMock).toHaveBeenCalledTimes(2))
    expect(lastListArgs()[2]?.componentId).toBe(BILLING.id)

    // …and the per-card component line is gone (every card shares it now).
    await waitFor(() => expect(cardOf(MINE_TASK.title).textContent).not.toContain(BILLING.name))
    // The issues themselves are untouched — this is a display rule, not a filter.
    expect(visibleTitles()).toEqual(ALL_ISSUES.map(i => i.title).sort())
  })

  it('is cleared by "Clear filters" together with the other dimensions', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(componentFilter())
    await userEvent.click(await screen.findByRole('option', { name: BILLING.name }))
    await waitFor(() => expect(componentFilter()).toHaveTextContent(BILLING.name))

    await userEvent.click(screen.getByRole('button', { name: /clear filters/i }))

    await waitFor(() => expect(componentFilter()).toHaveTextContent('All components'))
    expect(cardOf(MINE_TASK.title).textContent).toContain(BILLING.name)   // the card line is back
    expect(screen.queryByRole('button', { name: /clear filters/i })).not.toBeInTheDocument()
  })
})

describe('BoardPage issue creation entry points (HD-70)', () => {
  it('has no "New issue" button in the board header', async () => {
    renderBoard()
    await boardReady()

    // Creation is the nav rail's global button — the board header must not
    // duplicate it (the rail is not rendered in this isolated page test).
    expect(screen.queryByRole('button', { name: /new issue/i })).not.toBeInTheDocument()
  })

  it('exposes an always-visible quick-add button in every column', async () => {
    renderBoard()
    await boardReady()

    for (const status of [TODO, DOING]) {
      const btn = screen.getByRole('button', { name: `Create issue in ${status.name}` })
      expect(btn).toBeInTheDocument()
      expect(btn).toHaveAttribute('title', `Create issue in ${status.name}`)
      // Permanent affordance — not hidden behind a hover-only opacity swap.
      expect(btn.className).not.toContain('opacity-0')
      expect(btn.className).not.toContain('group-hover')
      expect(btn.style.color).toBe('var(--color-text-muted)')
    }
    // One per workflow column, no more.
    expect(screen.getAllByRole('button', { name: /^Create issue in / })).toHaveLength(2)
  })

  it('opens the create dialog with this project and the column status preset', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(screen.getByRole('button', { name: `Create issue in ${DOING.name}` }))

    expect(useUiStore.getState().createIssueOpen).toBe(true)
    expect(useUiStore.getState().createIssuePreset)
      .toEqual({ projectId: PROJECT_ID, statusId: DOING.id })
  })
})
