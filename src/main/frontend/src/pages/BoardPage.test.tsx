import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import BoardPage from './BoardPage'
import { useAuthStore } from '../auth'
import type { BoardQuickFilters } from '../uiPrefs'
import type { Issue, IssueType, PriorityOption, Status, User } from '../types'

// HD-43: the board's client-side quick-filter chips. The contract under test is
// the composition semantics, not the pixels:
//   • assignee dimension — "My issues" OR "Unassigned";
//   • type dimension — the type chips OR together;
//   • the two dimensions AND together;
//   • and the whole thing ANDs with the (server-side) priority filter, which
//     stays a query param and is NOT part of the client-side filtering.
// Plus: per-user/per-project persistence in localStorage, stale type ids being
// ignored, and the "everything hidden" empty state.

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }
const OTHER = { id: 'u-other', displayName: 'Other Person' }

const TODO: Status = { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 }
const DOING: Status = { id: 's2', name: 'In Progress', color: '#09f', category: 'IN_PROGRESS', position: 1 }

const TASK: IssueType = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const BUG: IssueType = { id: 't2', name: 'Bug', color: '#c00', position: 1, hierarchyLevel: 1 }

const PRIORITY: PriorityOption = { id: 'pr1', name: 'Medium', color: '#888', isDefault: true }

let n = 0
function issue(
  title: string,
  type: IssueType,
  status: Status,
  assignee: { id: string; displayName: string } | undefined,
): Issue {
  n += 1
  return {
    id: `i${n}`, number: n, key: `PR-${n}`, title,
    type, status, priority: PRIORITY,
    assignee,
    reporter: OTHER,
    childCount: 0, doneChildCount: 0,
    fields: [], version: 1,
    createdAt: '2026-08-10T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z',
  }
}

// 5 issues across both assignee buckets and both types, so every combination of
// chips selects a *different*, non-trivial subset.
const MINE_TASK = issue('Mine task', TASK, TODO, ME)
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

/** Wait for the board data + project config to land (chips are config-driven).
 *  The counter's "…5 issues" tail is the load signal — it holds whether or not
 *  restored chips are already hiding cards. */
async function boardReady() {
  expect(await screen.findByRole('button', { name: 'My issues' })).toBeInTheDocument()
  await screen.findByRole('button', { name: 'Bug' })
  await screen.findByText(/(^|of )5 issues$/)
}

const chip = (name: string) => screen.getByRole('button', { name })

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
  localStorage.setItem(
    `hamstrack.ui-prefs.${userId}`,
    JSON.stringify({ boardQuickFilters: { [projectId]: quick } }),
  )
}

describe('BoardPage quick filters (HD-43)', () => {
  it('shows every issue and the plain counter with no chips selected', async () => {
    renderBoard()
    await boardReady()

    expect(visibleTitles()).toEqual(ALL_ISSUES.map(i => i.title).sort())
    expect(screen.getByText('5 issues')).toBeInTheDocument()
    // Chips are accessible toggles, all off.
    expect(chip('My issues')).toHaveAttribute('aria-pressed', 'false')
    expect(chip('Unassigned')).toHaveAttribute('aria-pressed', 'false')
    expect(chip('Task')).toHaveAttribute('aria-pressed', 'false')
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

  it('ANDs the type dimension with the assignee dimension (types OR together)', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(chip('My issues'))
    await userEvent.click(chip('Unassigned'))
    await userEvent.click(chip('Bug'))

    // (mine OR unassigned) AND type=Bug
    await waitFor(() => expect(visibleTitles())
      .toEqual([MINE_BUG.title, UNASSIGNED_BUG.title].sort()))
    expect(screen.getByText('2 of 5 issues')).toBeInTheDocument()

    // Adding the second type widens the type dimension (Bug OR Task).
    await userEvent.click(chip('Task'))
    await waitFor(() => expect(visibleTitles())
      .toEqual([MINE_TASK.title, MINE_BUG.title, UNASSIGNED_BUG.title].sort()))
    expect(screen.getByText('3 of 5 issues')).toBeInTheDocument()
  })

  it('filters by type alone when no assignee chip is on', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(chip('Task'))

    await waitFor(() => expect(visibleTitles()).toEqual([MINE_TASK.title, OTHER_TASK.title].sort()))
    expect(screen.getByText('2 of 5 issues')).toBeInTheDocument()
  })

  it('shows the empty state when the chips hide every issue, and resets from it', async () => {
    renderBoard()
    await boardReady()

    // Unassigned AND type=Task — no such issue exists in the fixture.
    await userEvent.click(chip('Unassigned'))
    await userEvent.click(chip('Task'))

    expect(await screen.findByText('No issues match the current filters.')).toBeInTheDocument()
    expect(visibleTitles()).toEqual([])
    expect(screen.getByText('0 of 5 issues')).toBeInTheDocument()
    // Not a board of empty columns: the kanban columns are gone entirely.
    expect(screen.queryByText('No issues')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /reset quick filters/i }))

    await waitFor(() => expect(visibleTitles()).toEqual(ALL_ISSUES.map(i => i.title).sort()))
    expect(screen.getByText('5 issues')).toBeInTheDocument()
    expect(chip('Unassigned')).toHaveAttribute('aria-pressed', 'false')
    expect(chip('Task')).toHaveAttribute('aria-pressed', 'false')
    expect(savedQuick()).toBeUndefined()   // reset clears the persisted entry
  })

  it('persists the selection per user + project and restores it on a fresh render', async () => {
    const first = renderBoard()
    await boardReady()

    await userEvent.click(chip('My issues'))
    await userEvent.click(chip('Bug'))

    await waitFor(() => expect(savedQuick()).toEqual({ mine: true, unassigned: false, typeIds: [BUG.id] }))
    first.unmount()

    renderBoard()
    await boardReady()

    await waitFor(() => expect(chip('My issues')).toHaveAttribute('aria-pressed', 'true'))
    expect(chip('Bug')).toHaveAttribute('aria-pressed', 'true')
    expect(chip('Unassigned')).toHaveAttribute('aria-pressed', 'false')
    expect(visibleTitles()).toEqual([MINE_BUG.title])
    expect(screen.getByText('1 of 5 issues')).toBeInTheDocument()
  })

  it('ignores a persisted type id that no longer exists in the project config', async () => {
    seedQuick({ mine: false, unassigned: false, typeIds: ['t-deleted'] })

    renderBoard()
    await boardReady()

    // The stale id must not filter the board away — it degrades to "no type filter".
    await waitFor(() => expect(visibleTitles()).toEqual(ALL_ISSUES.map(i => i.title).sort()))
    expect(screen.queryByText('No issues match the current filters.')).not.toBeInTheDocument()
    expect(screen.getByText('5 issues')).toBeInTheDocument()
    expect(chip('Task')).toHaveAttribute('aria-pressed', 'false')
    expect(chip('Bug')).toHaveAttribute('aria-pressed', 'false')

    // …and the next chip click writes back a cleaned list, without the stale id.
    await userEvent.click(chip('Bug'))
    await waitFor(() => expect(savedQuick()?.typeIds).toEqual([BUG.id]))
  })

  it('keeps another project\'s saved chips untouched', async () => {
    seedQuick({ mine: true, unassigned: false, typeIds: [] }, ME.id, 'p-other')

    renderBoard()
    await boardReady()

    // This board starts unfiltered — the other project's entry is not applied…
    expect(screen.getByText('5 issues')).toBeInTheDocument()
    await userEvent.click(chip('Unassigned'))

    // …and is still there after this board persists its own.
    await waitFor(() => expect(savedQuick()).toEqual({ mine: false, unassigned: true, typeIds: [] }))
    expect(savedQuick(ME.id, 'p-other')).toEqual({ mine: true, unassigned: false, typeIds: [] })
  })

  it('drops the filtered issues from their column counts', async () => {
    renderBoard()
    await boardReady()

    await userEvent.click(chip('My issues'))

    // Both of "my" issues live in To Do, so In Progress empties out.
    await waitFor(() => expect(visibleTitles()).toEqual([MINE_BUG.title, MINE_TASK.title].sort()))
    const column = (name: string) => screen.getByText(name).closest('div.group')!
    expect(within(column('To Do')).getByText('2')).toBeInTheDocument()
    expect(within(column('In Progress')).getByText('No issues')).toBeInTheDocument()
    expect(screen.getAllByText('No issues')).toHaveLength(1)
  })
})
