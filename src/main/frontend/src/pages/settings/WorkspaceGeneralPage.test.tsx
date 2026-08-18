import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import WorkspaceGeneralPage from './WorkspaceGeneralPage'
import { ApiResponseError } from '../../api'
import { WORKSPACE_ADMIN_PERMISSIONS, WORKSPACE_MEMBER_PERMISSIONS } from '../../test/permissions'
import type { ProjectAccessImpact, ProjectAccessSettings, Role } from '../../types'

/**
 * **HD-123 S7 — the workspace project-access switch and its impact preview.**
 *
 * The whole point of the slice is that flipping this on a live workspace must not
 * silently take access away from people who had it, so the tests here are about
 * exactly three properties and not about layout:
 *
 *  1. the switch says what it changes — and what it does **not**: the mode decides
 *     only whether non-members inherit a default, there is no "enforcement off",
 *     and `OPEN` is the pre-S7 behaviour;
 *  2. the preview is **re-fetched every time the dialog opens** and never reused
 *     across a close, because the counts are advisory and carry a timestamp;
 *  3. the one authoritative refusal — the write's own re-derived stranding check —
 *     renders as itself, including when it lands *after* a clean-looking preview.
 */

const WS_ID = 'w1'

function role(id: string, key: string, name: string, position: number): Role {
  return {
    id, key, name, scope: 'PROJECT', builtIn: true, position, version: 0,
    permissions: [], assignment: { managesMembers: false, canAssign: [], cannotAssign: [], warnings: [] },
    usage: null,
  }
}
const MANAGER = role('r-manager', 'MANAGER', 'Project admin', 0)
const CONTRIBUTOR = role('r-member', 'MEMBER', 'Contributor', 2)
const VIEWER = role('r-viewer', 'VIEWER', 'Viewer', 3)

const EMPTY_IMPACT: ProjectAccessImpact = {
  computedAt: '2026-08-18T09:14:00Z',
  from: { mode: 'OPEN', defaultProjectRoleId: null },
  to: { mode: 'STRICT', defaultProjectRoleId: null },
  activeMembers: 24,
  projects: 2,
  projectsWithNoExplicitMembers: 1,
  projectsWithNoWriters: 1,
  perProject: [
    { id: 'p1', key: 'PAY', name: 'Payments', membersOnDefault: 21, explicitMembers: 3, membersLosingEverything: 21, noWritersAfter: false },
    { id: 'p2', key: 'OPS', name: 'Ops', membersOnDefault: 24, explicitMembers: 0, membersLosingEverything: 22, noWritersAfter: true },
  ],
  strandedProjects: [],
}

let workspacePermissions: string[] = WORKSPACE_ADMIN_PERMISSIONS
let access: ProjectAccessSettings = {
  mode: 'OPEN',
  defaultProjectRoleId: null,
  settable: { canSet: [{ roleId: VIEWER.id, name: 'Viewer' }], cannotSet: [] },
  impact: { ...EMPTY_IMPACT, to: { mode: 'OPEN', defaultProjectRoleId: null } },
}

const accessMock = vi.fn()
const previewMock = vi.fn()
const updateMock = vi.fn()

vi.mock('../../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'Acme', slug: 'acme', myRole: 'ADMIN',
    myPermissions: workspacePermissions,
    projectAccessMode: access.mode, defaultProjectRoleId: access.defaultProjectRoleId,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetProjectAccess: (...a: unknown[]) => accessMock(...a),
  apiPreviewProjectAccess: (...a: unknown[]) => previewMock(...a),
  apiUpdateWorkspace: (...a: unknown[]) => updateMock(...a),
  rolesApi: { list: vi.fn(async () => [MANAGER, CONTRIBUTOR, VIEWER]) },
}))

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/settings/general`]}>
        <Routes>
          <Route path="/w/:wsId/settings/general" element={<WorkspaceGeneralPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  workspacePermissions = WORKSPACE_ADMIN_PERMISSIONS
  access = {
    mode: 'OPEN',
    defaultProjectRoleId: null,
    settable: { canSet: [{ roleId: VIEWER.id, name: 'Viewer' }], cannotSet: [] },
    impact: { ...EMPTY_IMPACT, to: { mode: 'OPEN', defaultProjectRoleId: null } },
  }
  accessMock.mockReset()
  accessMock.mockImplementation(async () => access)
  previewMock.mockReset()
  previewMock.mockResolvedValue(EMPTY_IMPACT)
  updateMock.mockReset()
  updateMock.mockResolvedValue({})
})

describe('WorkspaceGeneralPage — the access-mode switch', () => {
  it('renders both modes, with the stored one selected', async () => {
    renderPage()
    const open = await screen.findByRole('radio', { name: /Open/ })
    const restricted = screen.getByRole('radio', { name: /Restricted/ })
    await waitFor(() => expect(open).toBeChecked())
    expect(restricted).not.toBeChecked()
  })

  it('explains what the mode changes — and does not offer an “enforcement off”', async () => {
    renderPage()
    // The mode decides ONE thing: whether people who were never added to a
    // project inherit its default role.
    expect(await screen.findByText(/using each project’s default role/)).toBeInTheDocument()
    expect(screen.getByText(/Everyone can still see every project/)).toBeInTheDocument()
    // Exactly two choices, ever.
    expect(screen.getAllByRole('radio', { name: /Open|Restricted/ })).toHaveLength(2)
  })

  it('flips back to Open in one call — widening cannot strand, so no dialog', async () => {
    access = { ...access, mode: 'STRICT' }
    renderPage()

    const open = await screen.findByRole('radio', { name: /Open/ })
    await waitFor(() => expect(open).toBeEnabled())
    await userEvent.click(open)

    await waitFor(() => expect(updateMock).toHaveBeenCalled())
    expect(updateMock.mock.calls[0][1]).toEqual({ projectAccessMode: 'OPEN' })
    expect(previewMock).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('WorkspaceGeneralPage — the impact preview', () => {
  async function openConfirm() {
    const restricted = await screen.findByRole('radio', { name: /Restricted/ })
    await waitFor(() => expect(restricted).toBeEnabled())
    await userEvent.click(restricted)
    return screen.findByRole('dialog')
  }

  it('re-fetches the preview when the dialog opens, and again on every reopen', async () => {
    renderPage()

    const dialog = await openConfirm()
    await waitFor(() => expect(previewMock).toHaveBeenCalledTimes(1))
    // Previewed with the IDENTICAL body the write will send — a second, dialog-
    // local description of "what would happen" is the bug class this avoids.
    expect(previewMock.mock.calls[0][1]).toEqual({ projectAccessMode: 'STRICT' })

    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await openConfirm()
    // Never a cached snapshot: the counts describe a population that is not the
    // row being written, so a preview from a previous interaction is not an
    // answer to this one.
    await waitFor(() => expect(previewMock).toHaveBeenCalledTimes(2))
  })

  it('leads with the honest headline — nobody, INCLUDING the owner, could file an issue', async () => {
    renderPage()
    const dialog = await openConfirm()

    expect(await within(dialog).findByText(/nobody — including you —/)).toBeInTheDocument()
    // Not "only workspace admins can change things there": a workspace admin
    // holds project.curate.all, which contains no issue or comment permission.
    expect(within(dialog).getByText(/rename a project and\s+cut a release/)).toBeInTheDocument()
    expect(within(dialog).getByText('OPS')).toBeInTheDocument()
    expect(within(dialog).getByText('nobody could file an issue')).toBeInTheDocument()
  })

  it('labels the counts as a snapshot rather than a promise', async () => {
    renderPage()
    const dialog = await openConfirm()
    expect(await within(dialog).findByText(/a guide, not a promise/)).toBeInTheDocument()
  })

  it('refuses to offer the confirm at all when the preview names stranded projects', async () => {
    previewMock.mockResolvedValue({
      ...EMPTY_IMPACT,
      strandedProjects: [{ id: 'p2', key: 'OPS', name: 'Ops' }],
    })
    renderPage()
    const dialog = await openConfirm()

    expect(await within(dialog).findByText(/This cannot be applied yet./)).toBeInTheDocument()
    // A stranding list is a block, not a warning: there is no adoption retry for
    // this door, so a confirm button could only ever produce the same 409.
    expect(within(dialog).getByRole('button', { name: 'Restrict project access' })).toBeDisabled()
    expect(within(dialog).getByText(/Add an explicit administrator to each first/)).toBeInTheDocument()
  })

  it('renders a 409 STRANDED_BY_INHERITANCE that arrives AFTER a clean preview', async () => {
    // The preview shows nothing in the way; the write re-derives the same check
    // inside its transaction and refuses anyway. That is the documented contract,
    // not an edge case, so it must not render as a generic error.
    const refusal = new ApiResponseError(409,
      'Restricting project access would leave Ops with nobody able to manage their membership',
      undefined, undefined,
      { errorType: 'STRANDED_BY_INHERITANCE', projects: [{ id: 'p2', key: 'OPS', name: 'Ops' }] })
    updateMock.mockRejectedValue(refusal)
    renderPage()
    const dialog = await openConfirm()

    await within(dialog).findByRole('button', { name: 'Restrict project access' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Restrict project access' }))

    expect(await within(dialog).findByText(/nobody able to manage their membership/)).toBeInTheDocument()
    // No "take them over" button: adopting would NARROW the adopter here.
    expect(within(dialog).getByText(/no “take them over” option here on purpose/)).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: /Take over/ })).toBeNull()
  })

  it('checks Retry-After before the discriminator', async () => {
    // A retryable 409 carries NO errorType, so a client that branches on the
    // discriminator first reads its absence as "no recovery I know about".
    updateMock.mockRejectedValue(
      new ApiResponseError(409, 'Somebody else was changing this', undefined, undefined, { retryAfter: 2 }))
    renderPage()
    const dialog = await openConfirm()

    await within(dialog).findByRole('button', { name: 'Restrict project access' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Restrict project access' }))

    expect(await within(dialog).findByText(/Try again in 2 seconds/)).toBeInTheDocument()
  })
})

describe('WorkspaceGeneralPage — the default project role', () => {
  it('labels the null value rather than letting the first role stand in for it', async () => {
    renderPage()
    // The shared Select falls back to its FIRST option when nothing matches —
    // which after sorting is Project admin. Without the placeholder, "inherited"
    // would be silently relabelled as full control of every project.
    expect(await screen.findByText('Contributor (built-in default)')).toBeInTheDocument()
    expect(screen.queryByText('Project admin')).toBeNull()
  })

  it('renders the ceiling as a reason, not as a 403 six weeks later', async () => {
    access = {
      ...access,
      settable: {
        canSet: [{ roleId: VIEWER.id, name: 'Viewer' }, { roleId: CONTRIBUTOR.id, name: 'Contributor' }],
        cannotSet: [{ roleId: MANAGER.id, name: 'Project admin', missing: 'project.archive' }],
      },
    }
    renderPage()

    // The same string the runtime 403 would carry, quoted before anything is sent.
    expect(await screen.findByText('project.archive')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Role' }))
    const blocked = await screen.findByRole('option', { name: /Project admin — requires project.archive/ })
    expect(blocked).toHaveAttribute('aria-disabled', 'true')
    await userEvent.click(blocked)
    expect(updateMock).not.toHaveBeenCalled()
  })

  it('sends exactly one of the two fields that name the same column', async () => {
    access = { ...access, defaultProjectRoleId: VIEWER.id }
    renderPage()

    // Three cards, three Saves — scoped by the card's own landmark name.
    const card = await screen.findByRole('region', { name: 'Default project role' })
    await userEvent.click(within(card).getByRole('button', { name: 'Role' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Contributor (built-in default)' }))
    await userEvent.click(within(card).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(updateMock).toHaveBeenCalled())
    // Sending both is a 422, not a precedence rule.
    expect(updateMock.mock.calls[0][1]).toEqual({ clearDefaultProjectRole: true })
  })

  it('says the default is stored but inert while access is Restricted', async () => {
    access = { ...access, mode: 'STRICT' }
    renderPage()
    expect(await screen.findByText(/applies again the moment access is switched back to Open/))
      .toBeInTheDocument()
  })
})

describe('WorkspaceGeneralPage — the gate', () => {
  it('asks for nothing and offers nothing without workspace.edit', async () => {
    workspacePermissions = WORKSPACE_MEMBER_PERMISSIONS
    renderPage()

    expect(await screen.findByText(/does not grant/)).toBeInTheDocument()
    expect(screen.queryByRole('radio')).toBeNull()
    // The read itself is gated on the same permission, so firing it would be a
    // guaranteed 403 rather than a useful request.
    expect(accessMock).not.toHaveBeenCalled()
  })
})
