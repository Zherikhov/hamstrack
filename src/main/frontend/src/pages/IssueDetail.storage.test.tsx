import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import IssueDetail from './IssueDetail'
import { PROJECT_ADMIN_PERMISSIONS, WORKSPACE_ADMIN_PERMISSIONS } from '../test/permissions'
import type {
  Issue, IssueType, PriorityOption, Status, WorkspaceStorageSummary,
} from '../types'

/**
 * **HD-191 §12.1 — what the upload control says about storage, and when it says
 * nothing at all.**
 *
 * Every case here is a *branch*, and the branches are the whole feature: a
 * storage figure on every issue page is noise, so the chrome is absent below the
 * operator's warn threshold and absent again when the instance enforces no
 * quota. An absence that is deliberate is indistinguishable from an absence that
 * was tidied away, which is why both are pinned rather than described.
 *
 * The two refusals are opposites and must never be rendered alike:
 *
 *  • the **409** is not a rate limit. It carries no `Retry-After` because
 *    waiting frees no bytes, and it prescribes no action to its reader — the
 *    reader may hold no delete grant, the space may be in a project they cannot
 *    see, and "ask your administrator" is a dead end in both deployment models.
 *    So this file asserts, negatively, that no wait-and-retry and no
 *    go-ask-somebody wording reaches the screen.
 *  • the **429** IS a rate limit, and the one refusal here that retrying fixes.
 *
 * And the figures in a 409 come from the **body**, never from the cached
 * summary: the cache is a minute old by design, so the fixtures below make the
 * two disagree on purpose and the assertion picks the body's numbers.
 *
 * ---
 *
 * **`npm test` runs on no automated path.** CI runs one command, `./mvnw -B
 * verify`, and nothing in it invokes vitest (filed as HD-242). This file
 * protects a reviewer and a local run, not a merge.
 */

const MB = 1024 * 1024
const GB = 1024 * MB

const STATUS_TODO: Status = { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 }
const TYPE_TASK: IssueType = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const PRIORITY: PriorityOption = { id: 'pr1', name: 'Medium', color: '#888', isDefault: true }

const ISSUE: Issue = {
  id: 'i1', number: 7, key: 'PR-7', title: 'Something to fix',
  type: TYPE_TASK, status: STATUS_TODO, priority: PRIORITY,
  reporter: { id: 'u1', displayName: 'Ada Lovelace' },
  childCount: 0, doneChildCount: 0,
  fields: [], version: 1,
  createdAt: '2026-08-10T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z',
}

function summaryOf(over: Partial<WorkspaceStorageSummary>): WorkspaceStorageSummary {
  return {
    quotaEnabled: true,
    quotaBytes: 10 * GB,
    usedBytes: Math.round(7.6 * GB),
    availableBytes: Math.round(2.4 * GB),
    attachmentCount: 12,
    percentUsed: 76,
    warnAtPercent: 80,
    maxFileBytes: 25 * MB,
    asOf: '2026-09-03T10:14:22Z',
    ...over,
  }
}

/** Comfortably below the threshold — the case where nothing must be drawn. */
const CALM = summaryOf({})
/** At the threshold. The operator's number decides this, never a constant. */
const NEAR_FULL = summaryOf({
  usedBytes: Math.round(8.2 * GB), availableBytes: Math.round(1.8 * GB), percentUsed: 82,
})
/** Nothing left: the control goes disabled rather than becoming a no-op. */
const FULL = summaryOf({ usedBytes: 10 * GB, availableBytes: 0, percentUsed: 100 })
/** A small ceiling, so a real `File` can be made that does not fit. */
const TIGHT = summaryOf({
  quotaBytes: 10 * MB, usedBytes: 9 * MB, availableBytes: 1 * MB, percentUsed: 90,
})
/** Plenty of room as far as this tab knows — the client pre-check cannot fire. */
const ROOMY = summaryOf({ usedBytes: 1 * GB, availableBytes: 9 * GB, percentUsed: 10 })
/** No ceiling anywhere: there is no threshold to be above, so no line either. */
const NO_QUOTA = summaryOf({
  quotaEnabled: false, quotaBytes: null, availableBytes: null, percentUsed: null,
})

let summary: WorkspaceStorageSummary = CALM
const storageMock = vi.fn(async () => summary)
const uploadMock = vi.fn()

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetIssue: vi.fn(async () => ISSUE),
  apiUpdateIssue: vi.fn(),
  apiDeleteIssue: vi.fn(),
  apiListIssues: vi.fn(async () => ({ issues: [], truncated: false })),
  apiListComments: vi.fn(async () => ({ content: [] })),
  apiCreateComment: vi.fn(),
  apiDeleteComment: vi.fn(),
  apiListAttachments: vi.fn(async () => []),
  apiUploadAttachment: (...a: unknown[]) => uploadMock(...a),
  apiDownloadAttachment: vi.fn(),
  apiDeleteAttachment: vi.fn(),
  apiGetIssueHistory: vi.fn(async () => ({ content: [] })),
  apiListWorkspaceMembers: vi.fn(async () => []),
  apiGetIssueChildren: vi.fn(async () => []),
  apiGetWorkspaceStorage: (...a: unknown[]) => storageMock(...(a as [])),
  labelsApi: { list: vi.fn(async () => []), create: vi.fn() },
  componentsApi: { list: vi.fn(async () => []) },
  versionsApi: { list: vi.fn(async () => []) },
  sprintsApi: {
    list: vi.fn(async () => ({
      content: [], page: 0, size: 200, totalElements: 0, totalPages: 1, hasNext: false,
    })),
  },
  apiGetProject: vi.fn(async () => ({
    id: 'p1', workspaceId: 'w1', name: 'Proj', key: 'PR',
    archived: false, myRole: 'MANAGER', myPermissions: PROJECT_ADMIN_PERMISSIONS,
    delivery: { board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN' },
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: 'w1', name: 'WS', slug: 'ws', myRole: 'OWNER',
    myPermissions: WORKSPACE_ADMIN_PERMISSIONS, createdAt: '2026-01-01T00:00:00Z',
  })),
}))

beforeAll(() => {
  if (!('ResizeObserver' in globalThis)) {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
})

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

function renderDetail() {
  return render(
    <IssueDetail
      wsId="w1" projectId="p1" issueNumber={7}
      issueTypes={[TYPE_TASK]} statuses={[STATUS_TODO]} transitions={[]}
      priorities={[PRIORITY]} fields={[]}
    />,
    { wrapper },
  )
}

const attachButton = () => screen.getByRole('button', { name: /Attach file/ })
const fileInput = () => document.querySelector('input[type="file"]') as HTMLInputElement

/** A real `File` of exactly `bytes` bytes — `File.size` is what the door reads. */
function fileOf(name: string, bytes: number): File {
  return new File([new Uint8Array(bytes)], name, { type: 'application/octet-stream' })
}

beforeEach(() => {
  summary = CALM
  storageMock.mockClear()
  uploadMock.mockReset()
})

describe('Issue attachments — the storage line (HD-191 §12.1)', () => {
  it('draws no storage chrome at all below the warn threshold', async () => {
    renderDetail()

    await screen.findByText(/Files \(/)
    await waitFor(() => expect(storageMock).toHaveBeenCalled())

    expect(screen.queryByText(/remaining/)).not.toBeInTheDocument()
    expect(screen.queryByText(/attachment storage/i)).not.toBeInTheDocument()
    expect(attachButton()).toBeEnabled()
  })

  it('names what is left once the workspace reaches the operator’s threshold', async () => {
    summary = NEAR_FULL
    renderDetail()

    expect(await screen.findByText('1.8 GB of 10 GB remaining.')).toBeInTheDocument()
    // Still uploadable — a warning is not a refusal.
    expect(attachButton()).toBeEnabled()
  })

  // Rule C's other half: with no quota there is no threshold to be above, so
  // there is no true sentence to put here. The settings page carries the number
  // in both deployments.
  it('says nothing when the instance enforces no quota, and leaves the control alone', async () => {
    summary = NO_QUOTA
    renderDetail()

    await screen.findByText(/Files \(/)
    await waitFor(() => expect(storageMock).toHaveBeenCalled())

    expect(screen.queryByText(/remaining/)).not.toBeInTheDocument()
    expect(attachButton()).toBeEnabled()
  })

  it('disables the control when nothing fits, instead of letting a click do nothing', async () => {
    summary = FULL
    renderDetail()

    expect(await screen.findByText(/has used all of its attachment storage/)).toBeInTheDocument()
    expect(screen.getByText(/10 GB of 10 GB/)).toBeInTheDocument()
    await waitFor(() => expect(attachButton()).toBeDisabled())
    // Describes the situation, dispatches nobody.
    expect(screen.getByText(/Storage is freed by deleting attachments/)).toBeInTheDocument()
    expect(screen.queryByText(/administrator|support|contact us/i)).not.toBeInTheDocument()
  })
})

describe('Issue attachments — refusals (HD-191 §12.1)', () => {
  it('refuses a file bigger than what is left, naming both numbers, without sending it', async () => {
    summary = TIGHT
    renderDetail()

    await screen.findByText(/Files \(/)
    await waitFor(() => expect(storageMock).toHaveBeenCalled())

    await userEvent.upload(fileInput(), fileOf('big.zip', 3 * MB))

    expect(await screen.findByText(
      'big.zip is 3 MB and this workspace has 1 MB of 10 MB remaining.',
    )).toBeInTheDocument()
    expect(uploadMock).not.toHaveBeenCalled()
  })

  it('renders a 409 from the body’s own figures, not from the cached summary', async () => {
    const { ApiResponseError, STORAGE_QUOTA_EXCEEDED } = await import('../api')
    // The cache says 1 GB of 10 GB used; the server refused on very different
    // numbers. Rendering the cache here would describe a moment that is not the
    // one the upload was refused in.
    summary = ROOMY
    uploadMock.mockRejectedValueOnce(new ApiResponseError(
      409,
      'This workspace has used all of its attachment storage (9.8 GB of 10 GB). '
        + 'This file needs 12.4 MB. Storage is freed by deleting attachments that are no longer needed.',
      undefined, undefined,
      {
        errorType: STORAGE_QUOTA_EXCEEDED,
        storage: {
          quotaBytes: 10 * GB,
          usedBytes: Math.round(9.8 * GB),
          availableBytes: Math.round(0.2 * GB),
          fileBytes: Math.round(12.4 * MB),
        },
      },
    ))

    renderDetail()
    await screen.findByText(/Files \(/)
    await waitFor(() => expect(storageMock).toHaveBeenCalled())

    await userEvent.upload(fileInput(), fileOf('notes.pdf', 1024))

    expect(await screen.findByText(/This file needs 12\.4 MB/)).toBeInTheDocument()
    // The body's numbers, not the cache's 1 GB.
    expect(screen.getByText(/9\.8 GB of 10 GB used/)).toBeInTheDocument()
    expect(screen.queryByText(/1 GB of 10 GB used/)).not.toBeInTheDocument()

    // It is a 409, not a 429: nothing here may suggest that waiting helps, and
    // nothing may send the reader to a person who cannot act either.
    expect(screen.queryByText(/retry|try again|shortly|moment/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/administrator|support|upgrade/i)).not.toBeInTheDocument()

    // Being refused is the strongest evidence there is that the cached total was
    // stale, so the read is re-issued.
    await waitFor(() => expect(storageMock.mock.calls.length).toBeGreaterThan(1))
  })

  it('treats a 429 as the opposite: it pauses the control for as long as the header says', async () => {
    const { ApiResponseError } = await import('../api')
    summary = ROOMY
    uploadMock.mockRejectedValueOnce(new ApiResponseError(
      429, 'Too many uploaded bytes — retry in 24s',
      undefined, undefined, { retryAfter: 24 },
    ))

    renderDetail()
    await screen.findByText(/Files \(/)
    await waitFor(() => expect(storageMock).toHaveBeenCalled())

    await userEvent.upload(fileInput(), fileOf('notes.pdf', 1024))

    expect(await screen.findByText(/Too many uploaded bytes — retry in 24s/)).toBeInTheDocument()
    expect(await screen.findByText('Retry in 24s.')).toBeInTheDocument()
    await waitFor(() => expect(attachButton()).toBeDisabled())
  })
})
