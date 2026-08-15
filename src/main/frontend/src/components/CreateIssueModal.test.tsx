import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import CreateIssueModal from './CreateIssueModal'
import type { CreateIssuePreset } from '../uiStore'

// HD-70: the board's per-column quick-add opens the create dialog with a
// preset status ({ projectId, statusId }). The Status select must honour that
// preset instead of always defaulting to the workflow's first column — while
// staying editable, and falling back to the first column when the preset's
// status isn't part of the selected project's workflow (stale/foreign id).

const STATUSES = [
  { id: 's1', name: 'To Do', color: '#999', category: 'TODO' as const, position: 0 },
  { id: 's2', name: 'In Progress', color: '#09f', category: 'IN_PROGRESS' as const, position: 1 },
  { id: 's3', name: 'Done', color: '#0a0', category: 'DONE' as const, position: 2 },
]

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
    issueTypes: [{ id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }],
    fields: [],
  })),
  apiListWorkspaceMembers: vi.fn(async () => []),
  apiListIssues: vi.fn(async () => ({ issues: [], truncated: false })),
  apiCreateIssue: vi.fn(),
}))

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
