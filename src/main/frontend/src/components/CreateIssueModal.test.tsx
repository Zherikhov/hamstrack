import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import CreateIssueModal from './CreateIssueModal'
import { sprintsApi, versionsApi } from '../api'
import { boardIssuesKey } from '../lib/queryKeys'
import type { CreateIssuePreset } from '../uiStore'
import type { BoardIssues, Issue, ProjectDelivery } from '../types'

// HD-70: the board's per-column quick-add opens the create dialog with a
// preset status ({ projectId, statusId }). The Status select must honour that
// preset instead of always defaulting to the workflow's first column — while
// staying editable, and falling back to the first column when the preset's
// status isn't part of the selected project's workflow (stale/foreign id).
//
// Plus a regression guard for the white-screen crash: the dialog shares its
// parent-picker cache entry with the board (`boardIssuesKey`), and the value
// cached there is the `BoardIssues` wrapper. Reading it with a queryFn that
// returned a bare array made the two disagree, and the dialog crashed the SPA
// with "projectIssues.filter is not a function" whenever it was opened over a
// board that had already populated the entry.

const STATUSES = [
  { id: 's1', name: 'To Do', color: '#999', category: 'TODO' as const, position: 0 },
  { id: 's2', name: 'In Progress', color: '#09f', category: 'IN_PROGRESS' as const, position: 1 },
  { id: 's3', name: 'Done', color: '#0a0', category: 'DONE' as const, position: 2 },
]

const TASK = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const EPIC = { id: 't2', name: 'Epic', color: '#0a0', position: 1, hierarchyLevel: 2 }

// Mutable mock state — the config's issue types decide whether a parent picker
// exists at all (it needs a type exactly one level above the selected one).
const mockState = vi.hoisted(() => ({
  issueTypes: [] as { id: string; name: string; color: string; position: number; hierarchyLevel: number }[],
  board: { issues: [], truncated: false, totalAvailable: 0, cap: 500 } as BoardIssues,
  // HD-102: the SELECTED project's declared delivery capabilities decide which
  // path-specific inputs this form offers — never whether it happens to have
  // sprints or versions yet. Rides the project list the dialog already fetches.
  delivery: { board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN' } as ProjectDelivery,
}))

vi.mock('../api', () => ({
  apiListWorkspaces: vi.fn(async () => [
    { id: 'w1', name: 'WS', slug: 'ws', myRole: 'OWNER', createdAt: '2026-01-01T00:00:00Z' },
  ]),
  apiListProjects: vi.fn(async () => [
    {
      id: 'p1', workspaceId: 'w1', name: 'Proj', key: 'PR', archived: false,
      myRole: 'MANAGER', delivery: mockState.delivery, createdAt: '2026-01-01T00:00:00Z',
    },
  ]),
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: STATUSES,
    transitions: [],
    priorities: [{ id: 'pr1', name: 'Medium', color: '#888', isDefault: true }],
    issueTypes: mockState.issueTypes,
    fields: [],
  })),
  apiListWorkspaceMembers: vi.fn(async () => []),
  apiListIssues: vi.fn(async () => mockState.board),
  apiCreateIssue: vi.fn(),
  // HD-30: the dialog's LabelPicker reads the workspace's labels and recovers
  // from a duplicate-name 409 — both come from this module.
  ApiResponseError: class ApiResponseError extends Error { status = 0 },
  labelsApi: { list: vi.fn(async () => []), create: vi.fn() },
  // HD-31: the dialog's Component select reads the project's components.
  componentsApi: { list: vi.fn(async () => []) },
  // HD-32: …and its fix/affects version pickers the project's versions.
  versionsApi: { list: vi.fn(async () => []) },
  // HD-22: …and its Sprint select the project's open sprints (none here, so the
  // select hides itself and only the Story points input renders).
  sprintsApi: { list: vi.fn(async () => ({ content: [], page: 0, size: 200, totalElements: 0, totalPages: 0, hasNext: false })) },
}))

beforeEach(() => {
  mockState.issueTypes = [TASK]
  mockState.board = { issues: [], truncated: false, totalAvailable: 0, cap: 500 }
  mockState.delivery = { board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN' }
})

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

/** The Status control is the custom <Select> — a labelled button showing the current option. */
async function statusSelect(expected: string) {
  const btn = screen.getByLabelText('Status')
  await waitFor(() => expect(btn).toHaveTextContent(expected))
  return btn
}

function renderModal(preset: CreateIssuePreset) {
  render(<CreateIssueModal wsId="w1" preset={preset} onClose={() => {}} />, { wrapper })
}

describe('CreateIssueModal status preset (board quick-add)', () => {
  it('pre-selects the preset status and leaves the Status select editable', async () => {
    renderModal({ projectId: 'p1', statusId: 's2' })

    const btn = await statusSelect('In Progress')
    // Not the workflow's first column…
    expect(btn).not.toHaveTextContent('To Do')
    // …and still changeable by the user.
    expect(btn).not.toBeDisabled()
  })

  it('falls back to the first workflow status when the preset status is not in the workflow', async () => {
    renderModal({ projectId: 'p1', statusId: 'not-in-this-workflow' })

    // Wait for the config to land, then assert the fallback (first column).
    await waitFor(() => expect(screen.getByLabelText('Type')).toHaveTextContent('Task'))
    const btn = await statusSelect('To Do')
    expect(btn).not.toHaveTextContent('In Progress')
  })
})

describe('CreateIssueModal shares the board cache entry without a shape clash', () => {
  const EPIC_ISSUE: Issue = {
    id: 'i-epic', number: 9, key: 'PR-9', title: 'Big epic',
    type: EPIC,
    status: STATUSES[0],
    priority: { id: 'pr1', name: 'Medium', color: '#888' },
    reporter: { id: 'u1', displayName: 'Reporter' },
    childCount: 0, doneChildCount: 0,
    fields: [], version: 1,
    createdAt: '2026-08-10T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z',
  }

  /** A board page has already cached its list under the shared key. */
  function renderOverCachedBoard() {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    qc.setQueryData<BoardIssues>(boardIssuesKey('w1', 'p1'), mockState.board)
    render(
      <QueryClientProvider client={qc}>
        <CreateIssueModal wsId="w1" defaultProjectId="p1" onClose={() => {}} />
      </QueryClientProvider>,
    )
    return qc
  }

  beforeEach(() => {
    mockState.issueTypes = [TASK, EPIC]
    mockState.board = { issues: [EPIC_ISSUE], truncated: false, totalAvailable: 1, cap: 500 }
  })

  it('renders over a board-populated cache entry and offers its issues as parents', async () => {
    // Pre-fix this threw during render — the queryFn cached a bare Issue[] under
    // the same key the board fills with the wrapper, so `projectIssues.filter`
    // ran against `{ issues, truncated, … }` and took the whole SPA down.
    const qc = renderOverCachedBoard()

    expect(await screen.findByText('New Issue')).toBeInTheDocument()

    const parent = await screen.findByLabelText('Parent')
    await userEvent.click(parent)
    expect(await screen.findByRole('option', { name: 'PR-9 — Big epic' })).toBeInTheDocument()

    // …and the dialog leaves the wrapper in place for the board to keep reading.
    await waitFor(() =>
      expect(qc.getQueryData<BoardIssues>(boardIssuesKey('w1', 'p1')))
        .toMatchObject({ issues: expect.any(Array), truncated: false, cap: 500 }))
  })
})

/**
 * HD-102 §6 — which path-specific inputs the create form offers is decided by the
 * selected project's DECLARED delivery capabilities, not by whether it happens to
 * have sprints or versions yet (`sprintOptions.length > 0` / `versionOptions.length
 * > 0` are gone — Rule C, §5.3). A create form carries no existing values, so this
 * is pure control gating; Rule B has nothing to preserve here.
 */
describe('CreateIssueModal delivery capabilities (HD-102)', () => {
  it('offers none of the path-specific inputs on a lean Kanban project', async () => {
    render(<CreateIssueModal wsId="w1" defaultProjectId="p1" onClose={() => {}} />, { wrapper })

    await waitFor(() => expect(screen.getByLabelText('Type')).toHaveTextContent('Task'))
    expect(screen.queryByLabelText('Sprint')).toBeNull()
    expect(screen.queryByLabelText('Story points')).toBeNull()
    expect(screen.queryByText(/fix version/i)).toBeNull()
    expect(screen.queryByText(/more fields/i)).toBeNull()
  })

  it('offers the sprint select and the points input on a Scrum project with NO sprints yet', async () => {
    // The removed heuristic gated the sprint select on the sprint list being
    // non-empty — the same "infer the mode from the data" check that produced the
    // production dead end.
    mockState.delivery = { board: 'SCRUM', releases: false, estimation: true, preset: 'SCRUM' }
    render(<CreateIssueModal wsId="w1" defaultProjectId="p1" onClose={() => {}} />, { wrapper })

    expect(await screen.findByLabelText('Sprint')).toBeInTheDocument()
    expect(screen.getByLabelText('Story points')).toBeInTheDocument()
    expect(screen.queryByText(/fix version/i)).toBeNull()
  })

  it('offers the version pickers when releases are on, with none curated yet', async () => {
    mockState.delivery = { board: 'KANBAN', releases: true, estimation: false, preset: 'RELEASES' }
    render(<CreateIssueModal wsId="w1" defaultProjectId="p1" onClose={() => {}} />, { wrapper })

    expect(await screen.findByText('Fix version(s)')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /more fields/i })).toBeInTheDocument()
    expect(screen.queryByLabelText('Sprint')).toBeNull()
    expect(screen.queryByLabelText('Story points')).toBeNull()
  })
})

/**
 * HD-102 §12 — the two eager prefetches this dialog used to run *purely to decide
 * what to render* (`useOpenSprints` + `useProjectVersions`, both fired on every
 * open and again on every project switch) are gone: the declared capabilities
 * answer that question for free, off the project list the dialog already has.
 *
 * Asserting the request is NOT made is the only way to keep them gone — a
 * "fetch it, then decide" refactor is invisible in every rendering assertion
 * above.
 */
describe('CreateIssueModal spends no request to decide what to offer (HD-102)', () => {
  beforeEach(() => {
    vi.mocked(sprintsApi.list).mockClear()
    vi.mocked(versionsApi.list).mockClear()
  })

  it('fetches neither sprints nor versions for a lean Kanban project', async () => {
    render(<CreateIssueModal wsId="w1" defaultProjectId="p1" onClose={() => {}} />, { wrapper })

    await waitFor(() => expect(screen.getByLabelText('Type')).toHaveTextContent('Task'))
    expect(sprintsApi.list).not.toHaveBeenCalled()
    expect(versionsApi.list).not.toHaveBeenCalled()
  })

  it('fetches sprints only once the capability puts a sprint picker on screen', async () => {
    mockState.delivery = { board: 'SCRUM', releases: false, estimation: false, preset: 'CUSTOM' }
    render(<CreateIssueModal wsId="w1" defaultProjectId="p1" onClose={() => {}} />, { wrapper })

    expect(await screen.findByLabelText('Sprint')).toBeInTheDocument()
    // The picker owns its own option list…
    await waitFor(() => expect(sprintsApi.list).toHaveBeenCalled())
    // …and releases being off still costs nothing.
    expect(versionsApi.list).not.toHaveBeenCalled()
  })
})
