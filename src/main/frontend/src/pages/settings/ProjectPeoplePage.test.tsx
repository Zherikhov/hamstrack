import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import ProjectPeoplePage from './ProjectPeoplePage'
import { ApiResponseError } from '../../api'
import { useAuthStore } from '../../auth'
import { PROJECT_ADMIN_PERMISSIONS, PROJECT_CONTRIBUTOR_PERMISSIONS } from '../../test/permissions'
import type { ProjectMember, Role, User, WorkspaceMember } from '../../types'

/**
 * **HD-123 S6 — Project People, and the default-access card.**
 *
 * This screen is the first consumer the project-members endpoint has ever had,
 * and that is exactly why the card comes first: almost nobody has a
 * `project_members` row, so the **default role** — not explicit membership — is
 * how nearly everyone gets access. The card is therefore asserted as a primary
 * control with a live count, not as a footnote, and the member table is asserted
 * to describe itself as the exception rather than as an empty list to fill.
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'
const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

function role(id: string, key: string, name: string): Role {
  return {
    id, key, name, scope: 'PROJECT', builtIn: true, position: 0, version: 0,
    permissions: [], assignment: { managesMembers: false, canAssign: [], cannotAssign: [], warnings: [] },
    usage: null,
  }
}
const MANAGER = role('r-manager', 'MANAGER', 'Project admin')
const CONTRIBUTOR = role('r-member', 'MEMBER', 'Contributor')
const VIEWER = role('r-viewer', 'VIEWER', 'Viewer')

/** Mutable so one case can drop the built-in the add form defaults to. */
let roleCatalog: Role[] = [MANAGER, CONTRIBUTOR, VIEWER]

const WS_MEMBERS: WorkspaceMember[] = [
  { userId: ME.id, email: ME.email, displayName: 'Me Myself', roleId: 'r-ws-admin', role: 'ADMIN' },
  { userId: 'u-mia', email: 'mia@example.com', displayName: 'Mia', roleId: 'r-ws-member', role: 'MEMBER' },
  { userId: 'u-sam', email: 'sam@example.com', displayName: 'Sam', roleId: 'r-ws-member', role: 'MEMBER' },
  { userId: 'u-ada', email: 'ada@example.com', displayName: 'Ada', roleId: 'r-ws-member', role: 'MEMBER' },
]

/**
 * Mia holds the PROJECT role keyed `MANAGER`, while three workspace members hold
 * the WORKSPACE role keyed `MEMBER` — and the project contributor role is keyed
 * `MEMBER` too. That collision is the reason a member is resolved by **id**: a
 * key resolved against a catalog spanning both scopes names the wrong privilege,
 * with no custom role anywhere in sight.
 */
const MIA: ProjectMember = {
  userId: 'u-mia', email: 'mia@example.com', displayName: 'Mia',
  roleId: MANAGER.id, role: 'MANAGER',
}

let projectMembers: ProjectMember[] = [MIA]
let projectPermissions: string[] = PROJECT_ADMIN_PERMISSIONS
/** Both links of the default-access chain, as the project response carries them. */
let defaultRole: { projectRoleId: string | null; workspaceRoleId: string | null } =
  { projectRoleId: null, workspaceRoleId: null }
/**
 * The workspace's access mode (HD-130 S7). It is published on the WORKSPACE
 * response and nowhere else, so this is the only place the card can read it from —
 * and reading it wrong is what makes the card's headline sentence a lie.
 */
let projectAccessMode: 'OPEN' | 'STRICT' = 'OPEN'

const listMock = vi.fn(async () => projectMembers)
const addMock = vi.fn()
const updateRoleMock = vi.fn()
const removeMock = vi.fn()
const defaultRoleGetMock = vi.fn()
const defaultRoleSetMock = vi.fn()

vi.mock('../../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetProject: vi.fn(async () => ({
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Payments', key: 'PAY',
    archived: false, myRole: 'MANAGER', myPermissions: projectPermissions,
    defaultRole, createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'Acme', slug: 'acme', myRole: 'ADMIN', myPermissions: [],
    projectAccessMode, defaultProjectRoleId: defaultRole.workspaceRoleId,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiListWorkspaceMembers: vi.fn(async () => WS_MEMBERS),
  rolesApi: { list: vi.fn(async () => roleCatalog) },
  projectMembersApi: {
    list: (...a: unknown[]) => listMock(...(a as [])),
    add: (...a: unknown[]) => addMock(...a),
    updateRole: (...a: unknown[]) => updateRoleMock(...a),
    remove: (...a: unknown[]) => removeMock(...a),
  },
  projectDefaultRoleApi: {
    get: (...a: unknown[]) => defaultRoleGetMock(...a),
    set: (...a: unknown[]) => defaultRoleSetMock(...a),
  },
}))

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/p/${PROJECT_ID}/settings/people`]}>
        <Routes>
          <Route path="/w/:wsId/p/:projectId/settings/people" element={<ProjectPeoplePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  projectMembers = [MIA]
  roleCatalog = [MANAGER, CONTRIBUTOR, VIEWER]
  projectPermissions = PROJECT_ADMIN_PERMISSIONS
  defaultRole = { projectRoleId: null, workspaceRoleId: null }
  projectAccessMode = 'OPEN'
  listMock.mockClear()
  addMock.mockReset()
  updateRoleMock.mockReset()
  removeMock.mockReset()
  defaultRoleGetMock.mockReset()
  defaultRoleGetMock.mockResolvedValue({
    projectRoleId: null, workspaceRoleId: null, mode: projectAccessMode,
    settable: { canSet: [], cannotSet: [] },
  })
  defaultRoleSetMock.mockReset()
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
})

describe('ProjectPeoplePage — default access', () => {
  it('leads with the default-access card and counts who is actually covered by it', async () => {
    renderPage()
    expect(await screen.findByText('Default access')).toBeInTheDocument()
    // 4 workspace members, 1 with an explicit row → 3 on the default.
    expect(await screen.findByText('3')).toBeInTheDocument()
    expect(screen.getByText(/members work.* here through that/)).toBeInTheDocument()
  })

  it('names the built-in fallback when neither link of the chain is set', async () => {
    renderPage()
    expect(await screen.findByText(/They get/)).toHaveTextContent(
      'They get Contributor — the built-in default, since neither this project nor the workspace sets one.')
  })

  it('names the workspace default, and says it is inherited', async () => {
    defaultRole = { projectRoleId: null, workspaceRoleId: VIEWER.id }
    renderPage()
    expect(await screen.findByText(/They get/)).toHaveTextContent(
      'They get Viewer — the workspace’s default, which this project inherits.')
  })

  it('the project’s own override wins over the workspace default, and says so', async () => {
    defaultRole = { projectRoleId: MANAGER.id, workspaceRoleId: VIEWER.id }
    renderPage()
    // Both links ride the response precisely so the card can distinguish these
    // two: the same name means something different when a project chose it.
    expect(await screen.findByText(/They get/)).toHaveTextContent(
      'They get Project admin — a default this project sets for itself.')
  })

  it('renders a placeholder when the chain names a role this workspace cannot describe', async () => {
    defaultRole = { projectRoleId: 'r-from-another-workspace', workspaceRoleId: VIEWER.id }
    renderPage()

    expect(await screen.findByText(/cannot describe/)).toBeInTheDocument()
    // Never falls through to the next link, and never guesses a name: the id
    // that is set is the one that decides access.
    expect(screen.queryByText(/They get/)).toBeNull()
  })

  it('opens the real picker (HD-130 S7) on the choice this project actually made', async () => {
    // Rule C: the mechanism is findable from the page it applies to. S6 drew this
    // disabled with a "coming soon" chip because the write had no grant ceiling
    // behind it yet; S7 ships both, so it must actually open.
    defaultRole = { projectRoleId: MANAGER.id, workspaceRoleId: VIEWER.id }
    defaultRoleGetMock.mockResolvedValue({
      projectRoleId: MANAGER.id, workspaceRoleId: VIEWER.id, mode: 'OPEN',
      settable: { canSet: [{ roleId: VIEWER.id, name: 'Viewer' }], cannotSet: [] },
    })
    renderPage()

    const button = await screen.findByRole('button', { name: 'Change default access' })
    // A permanent slot: it is mounted immediately and only ever goes disabled →
    // enabled as the permission answer lands, so this waits rather than asserting
    // on the loading frame.
    await waitFor(() => expect(button).toBeEnabled())
    expect(screen.queryByText('coming soon')).toBeNull()
    await userEvent.click(button)

    const dialog = await screen.findByRole('dialog')
    // Two DISTINCT choices, not one dropdown in which `null` is an unlabelled
    // option: "deliberately follows the workspace" and "happens to name the same
    // role" diverge the moment the workspace default moves.
    expect(within(dialog).getByRole('radio', { name: /Inherit the workspace default \(Viewer\)/ })).toBeInTheDocument()
    // The project sets its own override, so that is the radio it opens on.
    expect(within(dialog).getByRole('radio', { name: /Use a different role in this project/ })).toBeChecked()
  })

  it('writes {inherit:true} — a real choice, never an absent field', async () => {
    defaultRole = { projectRoleId: MANAGER.id, workspaceRoleId: null }
    defaultRoleGetMock.mockResolvedValue({
      projectRoleId: MANAGER.id, workspaceRoleId: null, mode: 'OPEN',
      settable: { canSet: [], cannotSet: [] },
    })
    defaultRoleSetMock.mockResolvedValue({})
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Change default access' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('radio', { name: /Inherit the workspace default/ }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(defaultRoleSetMock).toHaveBeenCalled())
    expect(defaultRoleSetMock.mock.calls[0][2]).toEqual({ inherit: true })
  })

  it('renders the ceiling from `settable` rather than discovering it on save', async () => {
    defaultRoleGetMock.mockResolvedValue({
      projectRoleId: null, workspaceRoleId: null, mode: 'OPEN',
      settable: {
        canSet: [{ roleId: VIEWER.id, name: 'Viewer' }],
        // The §4 membership escape does NOT reach the picker: an actor who may
        // promote a colleague to Project admin is still refused when they aim the
        // same role at the default, and the refusal names the permission.
        cannotSet: [{ roleId: MANAGER.id, name: 'Project admin', missing: 'issue.delete' }],
      },
    })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Change default access' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('issue.delete')).toBeInTheDocument()

    await userEvent.click(within(dialog).getByRole('radio', { name: /Use a different role/ }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Default role for this project' }))
    const blocked = await screen.findByRole('option', { name: /Project admin — requires issue.delete/ })
    expect(blocked).toHaveAttribute('aria-disabled', 'true')
    // Selecting it is refused client-side too, so nothing is sent that the
    // server has already said it would reject.
    await userEvent.click(blocked)
    expect(defaultRoleSetMock).not.toHaveBeenCalled()
  })

  it('disables the picker, but never hides the card, without project.member.manage', async () => {
    projectPermissions = PROJECT_CONTRIBUTOR_PERMISSIONS
    renderPage()
    const button = await screen.findByRole('button', { name: 'Change default access' })
    expect(button).toBeDisabled()
    // The card is the Rule C affordance — a card nobody can see is a mechanism
    // nobody can find, so it stays even when the button is refused.
    expect(screen.getByText('Default access')).toBeInTheDocument()
  })

  it('tells the truth in a RESTRICTED workspace: nobody works here through the default', async () => {
    projectAccessMode = 'STRICT'
    renderPage()
    expect(await screen.findByText(/nobody works in/)).toBeInTheDocument()
    // The count stops describing today and starts describing a hypothetical.
    expect(await screen.findByText(/would work here through it if project access were Open/))
      .toBeInTheDocument()
    expect(screen.queryByText(/can contribute to/)).toBeNull()
  })

  it('keeps the OPEN copy when the workspace is open', async () => {
    renderPage()
    expect(await screen.findByText(/can contribute to/)).toBeInTheDocument()
    expect(await screen.findByText(/work here through that default today/)).toBeInTheDocument()
    expect(screen.queryByText(/nobody works in/)).toBeNull()
  })

  it('describes an empty member table as the normal state, not a gap', async () => {
    projectMembers = []
    renderPage()
    expect(await screen.findByText(/everyone works under the default above/i)).toBeInTheDocument()
  })
})

describe('ProjectPeoplePage — explicit membership', () => {
  it('changes a member’s project role in ONE call, by role id', async () => {
    updateRoleMock.mockResolvedValue({ ...MIA, role: 'MEMBER' })
    renderPage()

    await screen.findByText('Mia')
    await userEvent.click(screen.getByRole('button', { name: 'Project role for Mia' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Contributor' }))

    await waitFor(() => expect(updateRoleMock).toHaveBeenCalled())
    // One call — not remove-then-add, which would drop them onto the default in
    // between and leave the project momentarily without an administrator.
    expect(updateRoleMock.mock.calls[0]).toEqual([WS_ID, PROJECT_ID, 'u-mia', CONTRIBUTOR.id])
    expect(removeMock).not.toHaveBeenCalled()
  })

  it('renders the last-administrator 409 on the row that caused it', async () => {
    updateRoleMock.mockRejectedValue(new ApiResponseError(409,
      "This is the project's last administrator — add another one first"))
    renderPage()

    await screen.findByText('Mia')
    await userEvent.click(screen.getByRole('button', { name: 'Project role for Mia' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Viewer' }))

    expect(await screen.findByText(/^This is the project's last administrator/)).toBeInTheDocument()
  })

  it('adds only people who do not already have a role of their own', async () => {
    addMock.mockResolvedValue(MIA)
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /Add someone from the workspace/i }))
    // Mia already has an explicit row, so she is not on offer.
    expect(screen.queryByRole('option', { name: /mia@example.com/ })).toBeNull()
    await userEvent.click(await screen.findByRole('option', { name: /sam@example.com/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    await waitFor(() => expect(addMock).toHaveBeenCalled())
    expect(addMock.mock.calls[0][2]).toEqual({ userId: 'u-sam', roleId: CONTRIBUTOR.id })
  })

  it('offers no write control at all without project.member.manage', async () => {
    projectPermissions = PROJECT_CONTRIBUTOR_PERMISSIONS
    renderPage()

    await screen.findByText('Mia')
    expect(screen.queryByRole('button', { name: 'Add' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Project role for Mia' })).toBeDisabled()
    // Reading the roster stays open to any workspace member — the endpoint is.
    expect(screen.getByText('mia@example.com')).toBeInTheDocument()
  })

  it('selects NOTHING when the contributor built-in is missing — never roles[0]', async () => {
    // `roles[0]` after `sortRoles` is the built-in at position 0: **Project
    // admin**, full control of the project. A fallback for "the role I expected
    // is not in this list" that resolves to the widest role on the list is the
    // one outcome worse than an empty picker, so this asserts the empty picker.
    roleCatalog = [MANAGER, VIEWER]
    renderPage()

    await screen.findByText('Mia')
    await userEvent.click(await screen.findByRole('button', { name: /Add someone from the workspace/i }))
    await userEvent.click(await screen.findByRole('option', { name: /sam@example.com/ }))

    const form = screen.getByRole('button', { name: 'Add' }).closest('form')!
    expect(within(form).getByText('Choose a role…')).toBeInTheDocument()
    expect(within(form).queryByText('Project admin')).toBeNull()
    // A person is chosen and the button still refuses: the missing half is the
    // role, and nothing here guesses it.
    expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled()
    expect(addMock).not.toHaveBeenCalled()
  })
})
