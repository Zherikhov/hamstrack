import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import RoleEditorModal from './RoleEditorModal'
import type { WorkspaceCapabilities } from './RoleEditorModal'
import type { PermissionCatalogEntry, Role, RolePermissionEntry } from '../types'

/**
 * **HD-123 S6 — the role editor.**
 *
 * Two properties, and both are about a client not being allowed to re-decide
 * something the server already decided:
 *
 *  • **Compose-time ceiling feedback is server-derived.** The editor dry-runs
 *    the set being composed through `POST …/roles/preview` and renders the block
 *    it gets back — `canAssign`, and each blocker's `missing` **key**, which is
 *    the identical string the runtime 403 quotes. Nothing here re-implements
 *    `firstNotCovered`: the ceiling compares permission sets with per-grant
 *    width, and a second implementation of a server predicate in the SPA is the
 *    HD-98 / HD-116 bug class.
 *  • **Capability hiding is UI, and only UI.** A permission belonging to a
 *    delivery capability no project in the workspace uses is not *offered* — but
 *    hiding never reaches a request, never drops a grant the role already holds,
 *    and never happens before the answer is known. Enforcement does not consult
 *    a capability at all, so a hidden-and-granted permission that survived a save
 *    is not a curiosity: it is the contract.
 */

const WS_ID = 'w1'

const CATALOG: PermissionCatalogEntry[] = [
  { key: 'project.edit', scope: 'PROJECT', supportsOwn: false, ownRequired: false, capability: null },
  { key: 'issue.create', scope: 'PROJECT', supportsOwn: false, ownRequired: false, capability: null },
  { key: 'issue.edit', scope: 'PROJECT', supportsOwn: true, ownRequired: false, capability: null },
  { key: 'comment.edit', scope: 'PROJECT', supportsOwn: true, ownRequired: true, capability: null },
  { key: 'sprint.manage', scope: 'PROJECT', supportsOwn: false, ownRequired: false, capability: 'BOARD' },
  { key: 'version.manage', scope: 'PROJECT', supportsOwn: false, ownRequired: false, capability: 'RELEASES' },
  { key: 'workspace.edit', scope: 'WORKSPACE', supportsOwn: false, ownRequired: false, capability: null },
]

function role(permissions: RolePermissionEntry[]): Role {
  return {
    id: 'r-qa', key: 'QA_LEAD', name: 'QA lead', description: 'Triages, does not file',
    scope: 'PROJECT', builtIn: false, position: 3, version: 7,
    permissions,
    assignment: { managesMembers: false, canAssign: [], cannotAssign: [], warnings: [] },
    usage: null,
  }
}

const NOTHING_USED: WorkspaceCapabilities = { BOARD: false, RELEASES: false, known: true }
const ALL_USED: WorkspaceCapabilities = { BOARD: true, RELEASES: true, known: true }
const NOT_KNOWN_YET: WorkspaceCapabilities = { BOARD: false, RELEASES: false, known: false }

const previewMock = vi.fn()
const updateMock = vi.fn()

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiPermissionCatalog: vi.fn(async () => CATALOG),
  rolesApi: {
    preview: (...a: unknown[]) => previewMock(...a),
    update: (...a: unknown[]) => updateMock(...a),
  },
}))

function renderEditor(r: Role, capabilities: WorkspaceCapabilities, onSaved = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <RoleEditorModal wsId={WS_ID} role={r} capabilities={capabilities}
                       onClose={vi.fn()} onSaved={onSaved} />
    </QueryClientProvider>,
  )
  return onSaved
}

beforeEach(() => {
  previewMock.mockReset()
  updateMock.mockReset()
  previewMock.mockResolvedValue({
    assignment: { managesMembers: false, canAssign: [], cannotAssign: [], warnings: [] },
  })
})

describe('RoleEditorModal — the catalog, grouped and gated', () => {
  it('offers only the permissions of the role’s own scope', async () => {
    renderEditor(role([]), ALL_USED)
    await screen.findByText('Create issues')
    // `workspace.edit` is a WORKSPACE key; a PROJECT role may not hold it, and a
    // body carrying it is a 422 — so it is never drawn here.
    expect(screen.queryByText('Edit the workspace')).toBeNull()
  })

  it('hides a capability permission no project in the workspace uses', async () => {
    renderEditor(role([]), NOTHING_USED)
    await screen.findByText('Create issues')

    expect(screen.queryByText('Manage sprints')).toBeNull()
    expect(screen.queryByText('Manage releases')).toBeNull()
    expect(await screen.findByText(/2 permissions are hidden/)).toBeInTheDocument()
    expect(screen.getByText(/sprint planning or releases/)).toBeInTheDocument()
  })

  it('offers them once any project uses the capability', async () => {
    renderEditor(role([]), ALL_USED)
    expect(await screen.findByText('Manage sprints')).toBeInTheDocument()
    expect(screen.getByText('Manage releases')).toBeInTheDocument()
    expect(screen.queryByText(/permissions? (is|are) hidden/)).toBeNull()
  })

  it('hides nothing until the answer is known — "not yet" is never "off"', async () => {
    renderEditor(role([]), NOT_KNOWN_YET)
    expect(await screen.findByText('Manage sprints')).toBeInTheDocument()
  })

  it('never hides a permission the role already holds', async () => {
    renderEditor(role([{ key: 'version.manage', ownOnly: false }]), NOTHING_USED)
    // Held, so it is shown even though no project uses releases — hiding a grant
    // would invite an admin to save a role they cannot see all of.
    expect(await screen.findByText('Manage releases')).toBeInTheDocument()
    expect(screen.getByText(/1 permission is hidden/)).toBeInTheDocument()
  })

  it('reveals the hidden ones on request', async () => {
    renderEditor(role([]), NOTHING_USED)
    await userEvent.click(await screen.findByText(/2 permissions are hidden/))
    expect(await screen.findByText('Manage sprints')).toBeInTheDocument()
  })
})

describe('RoleEditorModal — compose-time ceiling feedback', () => {
  it('previews the set as it is composed and names the blocking permission', async () => {
    previewMock.mockResolvedValue({
      assignment: {
        managesMembers: true,
        canAssign: [{ roleId: 'r-view', name: 'Viewer' }],
        cannotAssign: [{ roleId: 'r-con', name: 'Contributor', missing: 'issue.rank' }],
        warnings: [],
      },
    })
    renderEditor(role([{ key: 'issue.create', ownOnly: false }]), ALL_USED)

    // "On its own" — the block is a lower bound, computed from the role alone.
    expect(await screen.findByText(/On its own, this role can assign/)).toBeInTheDocument()
    expect(screen.getByText('Viewer')).toBeInTheDocument()
    expect(screen.getByText('Contributor')).toBeInTheDocument()
    // The key, verbatim: the same string the runtime 403 quotes.
    expect(screen.getByText('issue.rank')).toBeInTheDocument()

    await waitFor(() => expect(previewMock).toHaveBeenCalled())
    expect(previewMock.mock.calls[0][1]).toEqual({
      scope: 'PROJECT',
      permissions: [{ key: 'issue.create', ownOnly: false }],
    })
  })

  it('renders the manages-members-but-assigns-nothing warning without blocking a save', async () => {
    previewMock.mockResolvedValue({
      assignment: {
        managesMembers: true, canAssign: [], cannotAssign: [],
        warnings: ['MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING'],
      },
    })
    renderEditor(role([]), ALL_USED)

    expect(await screen.findByText(/cannot give any of them the ability to do anything/))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save role' })).toBeEnabled()
  })

  it('surfaces a preview 422 rather than pretending the set is fine', async () => {
    const { ApiResponseError } = await import('../api')
    previewMock.mockRejectedValue(new ApiResponseError(422, 'Unknown permission: issue.teleport'))
    renderEditor(role([]), ALL_USED)
    expect(await screen.findByText('Unknown permission: issue.teleport')).toBeInTheDocument()
  })
})

describe('RoleEditorModal — saving', () => {
  it('sends the full set plus the version, and hidden grants survive untouched', async () => {
    updateMock.mockResolvedValue(role([]))
    const onSaved = renderEditor(
      role([{ key: 'version.manage', ownOnly: false }, { key: 'issue.create', ownOnly: false }]),
      // `version.manage` belongs to a capability nothing uses — but the role
      // holds it, so it is neither hidden nor dropped from the payload. Hiding
      // must never reach a request: enforcement does not consult a capability.
      NOTHING_USED,
    )
    await screen.findByText('Create issues')
    await userEvent.click(screen.getByRole('button', { name: 'Save role' }))

    await waitFor(() => expect(updateMock).toHaveBeenCalled())
    const [, roleId, payload] = updateMock.mock.calls[0] as [string, string, {
      permissions: RolePermissionEntry[]; version: number
    }]
    expect(roleId).toBe('r-qa')
    expect(payload.version).toBe(7)
    expect([...payload.permissions].map(p => p.key).sort())
      .toEqual(['issue.create', 'version.manage'])
    expect(onSaved).toHaveBeenCalled()
  })

  it('stores comment.edit own-only however it is ticked, and locks the toggle', async () => {
    updateMock.mockResolvedValue(role([]))
    renderEditor(role([]), ALL_USED)

    await userEvent.click(await screen.findByRole('checkbox', { name: /Edit comments/ }))
    const ownToggle = await screen.findByRole('checkbox', { name: /Own only \(always\)/ })
    expect(ownToggle).toBeChecked()
    expect(ownToggle).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Save role' }))
    await waitFor(() => expect(updateMock).toHaveBeenCalled())
    const payload = updateMock.mock.calls[0][2] as { permissions: RolePermissionEntry[] }
    expect(payload.permissions).toEqual([{ key: 'comment.edit', ownOnly: true }])
  })

  it('renders a stale-version 409 and says to reload rather than retrying blindly', async () => {
    const { ApiResponseError } = await import('../api')
    updateMock.mockRejectedValue(new ApiResponseError(409,
      'Role was modified by someone else — refresh and retry'))
    renderEditor(role([]), ALL_USED)

    await screen.findByText('Create issues')
    await userEvent.click(screen.getByRole('button', { name: 'Save role' }))
    expect(await screen.findByText(/modified by someone else/)).toBeInTheDocument()
    expect(screen.getByText(/Close and reopen the role/)).toBeInTheDocument()
  })
})
