import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import CreateIssueModal from './CreateIssueModal'
import { boardIssuesKey } from '../lib/queryKeys'
import type { CreateIssuePreset } from '../uiStore'
import type { BoardIssues, Issue } from '../types'

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
}))

vi.mock('../api', () => ({
  apiListWorkspaces: vi.fn(async () => [
    { id: 'w1', name: 'WS', slug: 'ws', myRole: 'OWNER', createdAt: '2026-01-01T00:00:00Z' },
  ]),
  apiListProjects: vi.fn(async () => [
    { id: 'p1', workspaceId: 'w1', name: 'Proj', key: 'PR', archived: false, myRole: 'MANAGER', createdAt: '2026-01-01T00:00:00Z' },
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
