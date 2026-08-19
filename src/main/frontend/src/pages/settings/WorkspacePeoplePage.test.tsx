import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import WorkspacePeoplePage from './WorkspacePeoplePage'
import { ApiResponseError } from '../../api'
import { useAuthStore } from '../../auth'
import { WORKSPACE_ADMIN_PERMISSIONS } from '../../test/permissions'
import type { Role, User, WorkspaceMember } from '../../types'

/**
 * **HD-123 S6 — Workspace People, and the removal flow.**
 *
 * The removal is the most demanding interaction in the slice, because one status
 * code carries four different situations with four different remedies, and the
 * client has to tell them apart in a fixed order:
 *
 *  1. `Retry-After` present → transient lock contention. **Checked first**,
 *     because it carries no `errorType` and an absent discriminator otherwise
 *     reads as "no recovery I know about".
 *  2. `STRANDED_PROJECTS` → the adoption retry works. Offer it, and name the
 *     projects, because the second call is *consent* to take them over.
 *  3. `ADOPTION_BLOCKED` → the retry fails identically. **No adopt button.**
 *  4. `ADOPTION_ROLE_UNREADABLE` → nobody party to the request can clear it.
 *
 * And on success, 200 and 204 are different answers: a removal that granted the
 * caller a role answers 200 with `adoptedProjects`, and that body exists
 * precisely so the grant is visible to the person who caused it. A client that
 * assumed 204 would silently accumulate project roles for its user.
 */

const WS_ID = 'w1'
const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

function role(id: string, key: string, name: string): Role {
  return {
    id, key, name, scope: 'WORKSPACE', builtIn: true, position: 0, version: 0,
    permissions: [], assignment: { managesMembers: false, canAssign: [], cannotAssign: [], warnings: [] },
    usage: null,
  }
}

const OWNER = role('r-owner', 'OWNER', 'Owner')
const ADMIN = role('r-admin', 'ADMIN', 'Admin')
const MEMBER = role('r-member', 'MEMBER', 'Member')

/**
 * The catalog the roles endpoint answers with — mutable, because one case has to
 * drop the built-in the invite form defaults to. That is the only way to reach
 * the fallback at all: the endpoint always returns the shared built-ins today.
 */
let roleCatalog: Role[] = [OWNER, ADMIN, MEMBER]

const MIA: WorkspaceMember = {
  userId: 'u-mia', email: 'mia@example.com', displayName: 'Mia',
  roleId: ADMIN.id, role: 'ADMIN',
}
const ME_MEMBER: WorkspaceMember = {
  userId: ME.id, email: ME.email, displayName: ME.displayName,
  roleId: OWNER.id, role: 'OWNER',
}
/**
 * A row the server refused to describe. `roleId` degrades **with** `role`, never
 * past it: emitting the id alone would hand the withheld name straight back,
 * because a client would look it up in the catalog and print it.
 */
const CORRUPT: WorkspaceMember = {
  userId: 'u-ghost', email: 'ghost@example.com', displayName: 'Ghost',
  roleId: null, role: null,
}

let members: WorkspaceMember[] = [ME_MEMBER, MIA]
let workspacePermissions: string[] = WORKSPACE_ADMIN_PERMISSIONS

const removeMock = vi.fn()
const patchRoleMock = vi.fn()

vi.mock('../../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiListWorkspaceMembers: vi.fn(async () => members),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'Acme', slug: 'acme', myRole: 'OWNER',
    myPermissions: workspacePermissions, createdAt: '2026-01-01T00:00:00Z',
  })),
  apiUpdateWorkspaceMemberRole: (...a: unknown[]) => patchRoleMock(...a),
  apiRemoveWorkspaceMember: (...a: unknown[]) => removeMock(...a),
  apiInviteWorkspaceMember: vi.fn(async () => ({ message: 'ok' })),
  rolesApi: { list: vi.fn(async () => roleCatalog) },
}))

function conflict(detail: string, extras: Partial<ApiResponseError>): ApiResponseError {
  const err = new ApiResponseError(409, detail)
  Object.assign(err, extras)
  return err
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/settings/people`]}>
        <Routes>
          <Route path="/w/:wsId/settings/people" element={<WorkspacePeoplePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  members = [ME_MEMBER, MIA]
  roleCatalog = [OWNER, ADMIN, MEMBER]
  workspacePermissions = WORKSPACE_ADMIN_PERMISSIONS
  removeMock.mockReset()
  patchRoleMock.mockReset()
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
})

async function openRemoveDialog() {
  await screen.findByText('Mia')
  await userEvent.click(screen.getByRole('button', { name: 'Remove' }))
  await screen.findByRole('button', { name: 'Remove from workspace' })
}

describe('WorkspacePeoplePage — the roster', () => {
  it('renders each member with the role they hold, and changes one by id', async () => {
    patchRoleMock.mockResolvedValue({ ...MIA, role: 'MEMBER' })
    renderPage()

    await screen.findByText('Mia')
    await userEvent.click(await screen.findByRole('button', { name: 'Role for Mia' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Member' }))

    await waitFor(() => expect(patchRoleMock).toHaveBeenCalled())
    // A role is named by ID, not by key: a key cannot address a custom role, and
    // a workspace may define one that reuses a built-in's key.
    expect(patchRoleMock.mock.calls[0]).toEqual([WS_ID, MIA.userId, MEMBER.id])
  })

  it('renders a ceiling refusal verbatim, because it names the missing permission', async () => {
    patchRoleMock.mockRejectedValue(new ApiResponseError(403,
      'You cannot assign or administer the role "Admin", which includes issue.rank — a permission you do not hold in this workspace'))
    renderPage()

    await screen.findByText('Mia')
    await userEvent.click(await screen.findByRole('button', { name: 'Role for Mia' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Member' }))

    expect(await screen.findByText(/which includes issue\.rank/)).toBeInTheDocument()
  })

  it('renders a refused role as a placeholder, and still offers a way to set one', async () => {
    members = [ME_MEMBER, CORRUPT]
    renderPage()

    await screen.findByText('Ghost')
    // Never a guess, and never the first role in the list either.
    expect(screen.getByText('unknown role')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Set a role for Ghost' }))
      .toHaveTextContent('Choose a role…')
  })

  it('offers no Remove for your own row — leaving is a different feature', async () => {
    renderPage()
    await screen.findByText('Mia')
    expect(screen.getAllByRole('button', { name: 'Remove' })).toHaveLength(1)
  })

  it('hides the invite form and disables the role selects without workspace.member.manage', async () => {
    workspacePermissions = []
    renderPage()

    await screen.findByText('Mia')
    expect(screen.queryByRole('button', { name: /Send invite/ })).toBeNull()
    expect(screen.getByRole('button', { name: 'Role for Mia' })).toBeDisabled()
  })

  it('selects NOTHING when the built-in the invite defaults to is missing', async () => {
    // Unreachable through the API today, and that is exactly why it is asserted:
    // a fallback only ever runs in the situation nobody tested. An invite is a
    // grant, and sorted order carries no promise about privilege — so the answer
    // to "the role I expected is not in this list" has to be an empty picker with
    // Send disabled, never whichever row happens to sit at either end.
    roleCatalog = [OWNER, ADMIN]
    renderPage()

    await screen.findByText('Mia')
    await userEvent.type(screen.getByLabelText('Invite by email'), 'new@example.com')

    const invite = screen.getByRole('button', { name: /Send invite/ }).closest('form')!
    expect(within(invite).getByText('Choose a role…')).toBeInTheDocument()
    // Not silently pre-loaded with a privilege the caller never chose.
    expect(within(invite).queryByText('Owner')).toBeNull()
    expect(within(invite).queryByText('Admin')).toBeNull()
    // And the email alone cannot send it: a grant with no named role is refused
    // here rather than resolved to one.
    expect(screen.getByRole('button', { name: /Send invite/ })).toBeDisabled()
  })
})

describe('WorkspacePeoplePage — removal, and the three refusals', () => {
  it('removes with the flag OFF first, and closes on a 204', async () => {
    removeMock.mockResolvedValue(null)
    renderPage()
    await openRemoveDialog()

    await userEvent.click(screen.getByRole('button', { name: 'Remove from workspace' }))

    await waitFor(() => expect(removeMock).toHaveBeenCalledWith(WS_ID, MIA.userId, false))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('STRANDED_PROJECTS: names the projects, then adopts them on an explicit consent', async () => {
    removeMock
      .mockRejectedValueOnce(conflict(
        'Removing this member would leave 2 projects without an administrator: Alpha (P17), Bravo (P42)',
        {
          errorType: 'STRANDED_PROJECTS',
          projects: [
            { id: 'p-17', key: 'P17', name: 'Alpha' },
            { id: 'p-42', key: 'P42', name: 'Bravo' },
          ],
        }))
      .mockResolvedValueOnce({
        adoptedProjects: [
          { id: 'p-17', key: 'P17', name: 'Alpha' },
          { id: 'p-42', key: 'P42', name: 'Bravo' },
        ],
      })

    renderPage()
    await openRemoveDialog()
    await userEvent.click(screen.getByRole('button', { name: 'Remove from workspace' }))

    // The consent step NAMES the projects rather than asking "OK to continue?".
    await screen.findByText(/Projects you would take over/)
    expect(screen.getByText('Alpha')).toBeInTheDocument()
    expect(screen.getByText('Bravo')).toBeInTheDocument()
    expect(screen.getByText('P17')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Take over 2 projects and remove Mia/ }))

    // Only the SECOND call carries the flag — consent is never pre-set.
    await waitFor(() => expect(removeMock).toHaveBeenCalledTimes(2))
    expect(removeMock.mock.calls[0]).toEqual([WS_ID, MIA.userId, false])
    expect(removeMock.mock.calls[1]).toEqual([WS_ID, MIA.userId, true])

    // The 200 body is surfaced: the grant is visible to the person who caused it.
    expect(await screen.findByText(/you are now Team lead/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Done' })).toBeInTheDocument()
  })

  it('a flagged removal that stranded nothing (204) closes without claiming a grant', async () => {
    removeMock
      .mockRejectedValueOnce(conflict('would strand', {
        errorType: 'STRANDED_PROJECTS',
        projects: [{ id: 'p-17', key: 'P17', name: 'Alpha' }],
      }))
      .mockResolvedValueOnce(null)

    renderPage()
    await openRemoveDialog()
    await userEvent.click(screen.getByRole('button', { name: 'Remove from workspace' }))
    await screen.findByText(/Projects you would take over/)
    await userEvent.click(screen.getByRole('button', { name: /Take over 1 project and remove Mia/ }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.queryByText(/you are now Team lead/i)).toBeNull()
  })

  it('ADOPTION_BLOCKED: renders the reason and offers NO adopt button', async () => {
    removeMock.mockRejectedValue(conflict(
      'Your own role in Alpha (P17) holds more than “Team lead” does, so taking it over would take that away from you',
      { errorType: 'ADOPTION_BLOCKED', projects: [{ id: 'p-17', key: 'P17', name: 'Alpha' }] }))

    renderPage()
    await openRemoveDialog()
    await userEvent.click(screen.getByRole('button', { name: 'Remove from workspace' }))

    expect(await screen.findByText(/would take that away from you/)).toBeInTheDocument()
    expect(screen.getByText(/Projects in the way/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Take over/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
  })

  it('STRANDED_BY_INHERITANCE: the same shape as STRANDED_PROJECTS, the opposite remedy', async () => {
    // HD-130 S7, door 6. Those projects have administrators only through the
    // project's DEFAULT access, so "take them over" cannot apply — adoption
    // writes a Team lead row, and whoever inherits holds at least that much
    // already, so adopting would NARROW the rescuer in the project they rescued.
    // Classifying it as its cousin would offer a button that makes things worse.
    removeMock.mockRejectedValue(conflict(
      'Removing this member would leave Alpha (P17) with nobody able to manage their membership',
      { errorType: 'STRANDED_BY_INHERITANCE', projects: [{ id: 'p-17', key: 'P17', name: 'Alpha' }] }))

    renderPage()
    await openRemoveDialog()
    await userEvent.click(screen.getByRole('button', { name: 'Remove from workspace' }))

    expect(await screen.findByText(/nobody able to manage their membership/)).toBeInTheDocument()
    expect(screen.getByText('Alpha')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Take over/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
    // The remedy names something the refused person can do right now — the
    // default that is about to matter still grants it to them today.
    expect(screen.getByText(/the current default still lets you/)).toBeInTheDocument()
  })

  it('ADOPTION_ROLE_UNREADABLE: no retry, and says it needs an operator', async () => {
    removeMock.mockRejectedValue(conflict(
      'Your membership in Alpha (P17) refers to a role that cannot be read',
      { errorType: 'ADOPTION_ROLE_UNREADABLE', projects: [{ id: 'p-17', key: 'P17', name: 'Alpha' }] }))

    renderPage()
    await openRemoveDialog()
    await userEvent.click(screen.getByRole('button', { name: 'Remove from workspace' }))

    expect(await screen.findByText(/refers to a role that cannot be read/)).toBeInTheDocument()
    expect(screen.getByText(/system administrator/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Take over/ })).toBeNull()
  })

  it('Retry-After wins over a missing errorType: the identical request is offered again', async () => {
    const contention = new ApiResponseError(409, 'The workspace is busy — try again')
    Object.assign(contention, { retryAfter: 2 })
    removeMock.mockRejectedValueOnce(contention).mockResolvedValueOnce(null)

    renderPage()
    await openRemoveDialog()
    await userEvent.click(screen.getByRole('button', { name: 'Remove from workspace' }))

    await screen.findByText(/try again in 2 seconds/i)
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

    await waitFor(() => expect(removeMock).toHaveBeenCalledTimes(2))
    // IDENTICAL — the flag is not quietly turned on by a retry.
    expect(removeMock.mock.calls[1]).toEqual([WS_ID, MIA.userId, false])
  })

  it('an unrecognised errorType is treated as "no retry available"', async () => {
    removeMock.mockRejectedValue(conflict('A workspace must always have an owner',
      { errorType: 'SOMETHING_THIS_BUILD_HAS_NEVER_SEEN' }))

    renderPage()
    await openRemoveDialog()
    await userEvent.click(screen.getByRole('button', { name: 'Remove from workspace' }))

    expect(await screen.findByText('A workspace must always have an owner')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Take over/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
  })

  it('the last-Owner conflict (no errorType at all) renders its own sentence', async () => {
    removeMock.mockRejectedValue(new ApiResponseError(409,
      'A workspace must always have an owner — promote another member first'))

    renderPage()
    await openRemoveDialog()
    await userEvent.click(screen.getByRole('button', { name: 'Remove from workspace' }))

    expect(await screen.findByText(/promote another member first/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Take over/ })).toBeNull()
  })
})
