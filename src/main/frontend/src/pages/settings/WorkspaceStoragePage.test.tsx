import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import WorkspaceStoragePage from './WorkspaceStoragePage'
import { WORKSPACE_ADMIN_PERMISSIONS, WORKSPACE_MEMBER_PERMISSIONS } from '../../test/permissions'
import type { WorkspaceStorageByProject, WorkspaceStorageSummary } from '../../types'

/**
 * **HD-191 — the quota is visible before it refuses anybody, and it is visible
 * when there is no quota at all.**
 *
 * The ticket's own phrase is that *a quota nobody can see is a trap rather than
 * a limit*, and this page is the surface that answers it. The three properties
 * pinned here are the three that a later tidy-up would quietly delete, because
 * each of them is a branch that renders *more* than the obvious reading needs:
 *
 *  1. **Rule C.** With `quotaEnabled` false the page still renders every figure
 *     and says plainly that nothing is enforced. A section that vanishes when
 *     unconfigured is unreachable for exactly the operator deciding whether to
 *     configure it — the defect this project has already shipped once, on the
 *     delivery capabilities.
 *  2. **Over 100% renders as over 100%.** Clamping would hide the one state that
 *     explains why uploads are being refused, and a bar pinned at "full" looks
 *     identical whether a workspace is at 100% or at 180%.
 *  3. **`unattributedBytes` gets its own row.** Folding it into the total would
 *     make the page lie in the one state it exists for: a non-zero value is the
 *     gap between the counter the quota enforces and the rows it should be a sum
 *     of.
 *
 * Plus the permission split, which is the tenancy half: the *summary* is
 * readable by any member and the *breakdown* names projects and volumes, so a
 * member without `workspace.edit` sees the first and never even asks for the
 * second.
 *
 * ---
 *
 * **`npm test` runs on no automated path.** CI runs exactly one command,
 * `./mvnw -B verify`, whose frontend executions are `npm ci` and `npm run build`
 * — nothing invokes vitest (filed as HD-242). So this file protects a reviewer
 * and a local run, not a merge; treat a green suite here as evidence somebody
 * typed the command, and keep the seals that must gate a merge in JUnit.
 */

const WS_ID = 'w1'

const GB = 1024 * 1024 * 1024

const QUOTA_ON: WorkspaceStorageSummary = {
  quotaEnabled: true,
  quotaBytes: 10 * GB,
  usedBytes: Math.round(7.6 * GB),
  availableBytes: Math.round(2.4 * GB),
  attachmentCount: 1842,
  percentUsed: 76,
  warnAtPercent: 80,
  maxFileBytes: 25 * 1024 * 1024,
  asOf: '2026-09-03T10:14:22Z',
}

/** The deployment with no ceiling: usage is still counted, still reported. */
const QUOTA_OFF: WorkspaceStorageSummary = {
  ...QUOTA_ON,
  quotaEnabled: false,
  quotaBytes: null,
  availableBytes: null,
  percentUsed: null,
}

/**
 * A quota lowered under an existing total — §6.9's "shows a bar over 100%".
 * `availableBytes` is `0`, not a negative: the server clamps it, so the overflow
 * is only ever legible from `percentUsed` and the two byte figures. A page that
 * subtracted its way to "past by" would read `0` here and say nothing.
 */
const OVER_QUOTA: WorkspaceStorageSummary = {
  ...QUOTA_ON,
  quotaBytes: 5 * GB,
  usedBytes: Math.round(5.6 * GB),
  availableBytes: 0,
  percentUsed: 112,
}

const BREAKDOWN: WorkspaceStorageByProject = {
  asOf: '2026-09-03T10:14:22Z',
  totalBytes: 4 * GB,
  unattributedBytes: 0,
  projects: [
    { projectId: 'p1', key: 'HD', name: 'Hamstrack', bytes: 3 * GB, attachmentCount: 902 },
    { projectId: 'p2', key: 'OPS', name: 'Ops', bytes: 1 * GB, attachmentCount: 140 },
  ],
}

let summary: WorkspaceStorageSummary = QUOTA_ON
let breakdown: WorkspaceStorageByProject = BREAKDOWN
let workspacePermissions: string[] = WORKSPACE_ADMIN_PERMISSIONS

const storageMock = vi.fn(async () => summary)
const byProjectMock = vi.fn(async () => breakdown)

vi.mock('../../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'Acme', slug: 'acme', myRole: 'ADMIN',
    myPermissions: workspacePermissions,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspaceStorage: (...a: unknown[]) => storageMock(...(a as [])),
  apiGetWorkspaceStorageByProject: (...a: unknown[]) => byProjectMock(...(a as [])),
}))

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/settings/storage`]}>
        <Routes>
          <Route path="/w/:wsId/settings/storage" element={<WorkspaceStoragePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  summary = QUOTA_ON
  breakdown = BREAKDOWN
  workspacePermissions = WORKSPACE_ADMIN_PERMISSIONS
  storageMock.mockClear()
  byProjectMock.mockClear()
})

describe('Workspace settings → Storage (HD-191 §12.2)', () => {
  it('renders the fill, the remaining space and the threshold that drives the issue-page line', async () => {
    renderPage()

    expect(await screen.findByText(/7\.6 GB/)).toBeInTheDocument()
    expect(screen.getByText(/of 10 GB/)).toBeInTheDocument()
    expect(screen.getByText('76% of the quota')).toBeInTheDocument()
    // The warn threshold is the operator's number and is named, not assumed.
    expect(screen.getByText(/2\.4 GB remaining\. The mark on the bar is 80%/)).toBeInTheDocument()
    // Grouped by the runner's locale, so the separator is deliberately not pinned.
    expect(screen.getByText(/1\D?842/)).toBeInTheDocument()
  })

  // Rule C. The page is the affordance that has to exist WHILE the capability is
  // off, so this case is the one that must not be "simplified" into an early
  // return on `!quotaEnabled`.
  it('still renders every figure when the instance enforces no quota, and says so', async () => {
    summary = QUOTA_OFF
    renderPage()

    expect(await screen.findByText(/does not enforce a storage quota/i)).toBeInTheDocument()
    // Usage is counted and reported either way — this is the number an operator
    // reads while deciding what to set.
    expect(screen.getByText(/7\.6 GB/)).toBeInTheDocument()
    // Grouped by the runner's locale, so the separator is deliberately not pinned.
    expect(screen.getByText(/1\D?842/)).toBeInTheDocument()
    // And the breakdown is still there: nothing about it depends on a ceiling.
    expect(await screen.findByText('Hamstrack')).toBeInTheDocument()

    // No ceiling means no fraction of one — never a sentinel rendered as a number.
    expect(screen.queryByText(/of the quota/)).not.toBeInTheDocument()
    expect(screen.queryByText(/remaining/)).not.toBeInTheDocument()
    expect(screen.queryByText(/-1|NaN|null/)).not.toBeInTheDocument()
  })

  it('renders a workspace past its quota as past it, rather than clamping to 100%', async () => {
    summary = OVER_QUOTA
    renderPage()

    expect(await screen.findByText('112% of the quota')).toBeInTheDocument()
    expect(screen.getByText(/Past the quota by 614\.4 MB/)).toBeInTheDocument()
    // Reads are never quota-gated, and the page says so rather than implying a
    // full workspace is a broken one.
    expect(screen.getByText(/Existing\s+files stay readable and downloadable/)).toBeInTheDocument()
  })

  it('shows unattributed bytes as their own row instead of folding them away', async () => {
    breakdown = { ...BREAKDOWN, unattributedBytes: 512 * 1024 * 1024, totalBytes: 4 * GB + 512 * 1024 * 1024 }
    renderPage()

    const row = await screen.findByText('Not attributed to a project')
    expect(row).toBeInTheDocument()
    expect(screen.getByText('512 MB')).toBeInTheDocument()
  })

  it('hides the row when it is zero — the normal case is not an anomaly to explain', async () => {
    renderPage()

    await screen.findByText('Hamstrack')
    expect(screen.queryByText('Not attributed to a project')).not.toBeInTheDocument()
  })

  // The disclosure split: the summary is the same aggregate the refusal already
  // hands this person, the breakdown names projects they may not be able to see.
  it('gives a member without `workspace.edit` the total and never asks for the breakdown', async () => {
    workspacePermissions = WORKSPACE_MEMBER_PERMISSIONS
    renderPage()

    expect(await screen.findByText(/7\.6 GB/)).toBeInTheDocument()
    expect(await screen.findByText(/needs the/)).toBeInTheDocument()
    expect(screen.getByText('workspace.edit')).toBeInTheDocument()
    expect(screen.queryByText('Hamstrack')).not.toBeInTheDocument()
    // Held behind the permission answer rather than fired and refused: spending a
    // budgeted request on a guaranteed 403 helps nobody.
    await waitFor(() => expect(storageMock).toHaveBeenCalled())
    expect(byProjectMock).not.toHaveBeenCalled()
  })

  it('renders a budget refusal on the breakdown as its own sentence, leaving the total readable', async () => {
    const { ApiResponseError } = await import('../../api')
    byProjectMock.mockRejectedValueOnce(
      new ApiResponseError(429, 'Too many report requests — retry in 12s') as never,
    )
    renderPage()

    expect(await screen.findByText('Too many report requests — retry in 12s')).toBeInTheDocument()
    expect(screen.getByText(/7\.6 GB/)).toBeInTheDocument()
  })
})
