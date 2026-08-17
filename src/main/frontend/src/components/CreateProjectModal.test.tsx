import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import CreateProjectModal from './CreateProjectModal'
import { apiCreateProject } from '../api'
import {
  CAPABILITY,
  CREATION_REVERSIBLE_NOTICE,
  LEAN_DELIVERY_CHOICE,
  creationDelivery,
  type DeliveryCapability,
} from './delivery'
import { useAuthStore } from '../auth'
import type { CreateProjectPayload } from '../api'
import type { User } from '../types'

/**
 * HD-105 (S4) — the delivery picker on project creation.
 *
 * Three claims are worth a test here, and they are different in kind:
 *
 *  • **The default is lean (D4).** §17 names this the highest-risk assumption in
 *    the design, so the assertion is on the BYTES a create sends when the user
 *    ignores the picker entirely — not on which card looks selected.
 *  • **"Scrum + Releases" is expressible.** This is divergence D1's whole
 *    justification: a three-way radio would make the ordinary case
 *    (two-week sprints that also ship a tagged 2.4.0) unrepresentable. If this
 *    test ever fails, the picker has quietly become the enum we refused.
 *  • **The picker and the capability table cannot drift.** The cases iterate
 *    `CAPABILITY` rather than naming capabilities, so a fourth row appears here
 *    the moment it exists — with its own copy, or with its stated reason for not
 *    being asked at creation (§18 q7 keeps `estimation` off the picker).
 */

const WS_ID = 'w1'
const OTHER_WS = 'w2'
const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

// Only the create call is stubbed: `delivery.tsx` and the auth store import
// other bindings from this module, and a factory that dropped them would fail on
// access rather than on anything this file is testing.
vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiCreateProject: vi.fn(async () => ({
    id: 'p-new', workspaceId: WS_ID, name: 'Payments', key: 'PAY',
    archived: false, myRole: 'MANAGER', createdAt: '2026-08-17T00:00:00Z',
  })),
}))

const createMock = vi.mocked(apiCreateProject)

function renderModal(wsId = WS_ID) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${wsId}`]}>
        <CreateProjectModal wsId={wsId} onClose={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Fill the two required fields and submit — the picker is deliberately untouched. */
async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Project name'), 'Payments')
  await user.click(screen.getByRole('button', { name: 'Create project' }))
  await waitFor(() => expect(createMock).toHaveBeenCalledTimes(1))
  return createMock.mock.calls[0][1] as CreateProjectPayload
}

beforeEach(() => {
  createMock.mockClear()
  localStorage.clear()
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
})

describe('CreateProjectModal — the delivery picker (HD-105)', () => {
  it('sends the LEAN default when the picker is never touched', async () => {
    const user = userEvent.setup()
    renderModal()

    const payload = await fillAndSubmit(user)

    // D4, on the wire: Kanban, releases off, estimation off.
    expect(payload.delivery).toEqual({ board: 'KANBAN', releases: false, estimation: false })
    // …and that it is exactly what the one named constant says, so flipping the
    // constant flips the product rather than only this expectation.
    expect(payload.delivery).toEqual(creationDelivery(LEAN_DELIVERY_CHOICE))
  })

  it('expresses Scrum + Releases together — the combination D1 exists to keep legal', async () => {
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByRole('radio', { name: /scrum/i }))
    await user.click(screen.getByRole('checkbox', { name: /releases/i }))
    const payload = await fillAndSubmit(user)

    // Scrum also turns estimation on in the SAME request (§14.2, §18 q7) — the
    // picker asks two questions and answers three capabilities.
    expect(payload.delivery).toEqual({ board: 'SCRUM', releases: true, estimation: true })
  })

  it('turns Releases on without leaving Kanban — the toggle is not a third answer', async () => {
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByRole('checkbox', { name: /releases/i }))
    const payload = await fillAndSubmit(user)

    expect(payload.delivery).toEqual({ board: 'KANBAN', releases: true, estimation: false })
    expect(screen.getByRole('radio', { name: /kanban/i })).toBeChecked()
  })

  it('never sends `preset` — it is derived server-side and answered with a 400', async () => {
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByRole('radio', { name: /scrum/i }))
    const payload = await fillAndSubmit(user)

    expect(JSON.stringify(payload)).not.toContain('preset')
    // `boardMode` is the deprecated mirror and has no field on create at all.
    expect(JSON.stringify(payload)).not.toContain('boardMode')
    expect(Object.keys(payload.delivery ?? {}).sort()).toEqual(['board', 'estimation', 'releases'])
  })

  it('places the "you can change this later" line ABOVE the cards', async () => {
    renderModal()

    const notice = screen.getByText(CREATION_REVERSIBLE_NOTICE)
    // Reversibility is copy, never a tooltip (`getByText` never matches `title`)…
    expect(notice).toBeInTheDocument()
    // …and it is read while deciding, not after: it precedes the first card in
    // document order. An up-front question is only cheap if "this is not
    // permanent" arrives before the commitment.
    const firstCard = screen.getByRole('radio', { name: /kanban/i })
    expect(notice.compareDocumentPosition(firstCard) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy()
  })

  it('never blocks creation on the question', async () => {
    const user = userEvent.setup()
    renderModal()

    // A card is always selected and Create is enabled as soon as name/key are —
    // there is no state in which the user is stuck on the picker.
    expect(screen.getByRole('radio', { name: /kanban/i })).toBeChecked()
    await user.type(screen.getByLabelText('Project name'), 'Payments')
    expect(screen.getByRole('button', { name: 'Create project' })).toBeEnabled()
  })
})

describe('CreateProjectModal — the picker follows the capability table', () => {
  const capabilities = Object.keys(CAPABILITY) as DeliveryCapability[]

  it.each(capabilities.filter(c => CAPABILITY[c].creation.atCreation))(
    'renders %s with the table’s own copy',
    capability => {
      const creation = CAPABILITY[capability].creation
      if (!creation.atCreation) throw new Error('filtered above')
      renderModal()

      // The card's name and blurb come from the table, so new copy lands here
      // without a second hand-written list to update.
      expect(screen.getByText(creation.blurb)).toBeInTheDocument()
      if (creation.offCard) {
        // A capability whose OFF state is a named way of working is a radio pair.
        expect(screen.getByRole('radio', { name: new RegExp(creation.title, 'i') })).toBeInTheDocument()
        expect(screen.getByRole('radio', { name: new RegExp(creation.offCard.title, 'i') })).toBeInTheDocument()
        expect(screen.getByText(creation.offCard.blurb)).toBeInTheDocument()
      } else {
        // An orthogonal capability is a toggle, and says so in words — without
        // that sentence the three named cards would imply an exclusivity the
        // data does not have (D1).
        expect(screen.getByRole('checkbox', { name: new RegExp(creation.title, 'i') })).toBeInTheDocument()
        expect(creation.independentNote).toBeTruthy()
        expect(screen.getByText(creation.independentNote!)).toBeInTheDocument()
      }
    },
  )

  it.each(capabilities.filter(c => !CAPABILITY[c].creation.atCreation))(
    'keeps %s off the picker, for a stated reason',
    capability => {
      const creation = CAPABILITY[capability].creation
      if (creation.atCreation) throw new Error('filtered above')
      // "We decided" must not be able to look like "we forgot" — the row carries
      // the reason (§18 q7) and the screen carries no control for it.
      expect(creation.why).toBeTruthy()
      renderModal()
      expect(screen.queryByRole('checkbox', { name: new RegExp(CAPABILITY[capability].enable, 'i') }))
        .toBeNull()
      expect(screen.queryByText(CAPABILITY[capability].offBlurb)).toBeNull()
    },
  )

  it('asks exactly the capabilities the table says are asked', () => {
    renderModal()
    const asked = capabilities.filter(c => CAPABILITY[c].creation.atCreation)
    // Radios = the board pair (2 cards for 1 capability); checkboxes = add-ons.
    const addOns = asked.filter(c => {
      const creation = CAPABILITY[c].creation
      return creation.atCreation && !creation.offCard
    })
    expect(screen.getAllByRole('checkbox')).toHaveLength(addOns.length)
    expect(screen.getAllByRole('radio')).toHaveLength((asked.length - addOns.length) * 2)
  })
})

describe('CreateProjectModal — the last-preset nudge (§18 q1)', () => {
  async function createWith(
    user: ReturnType<typeof userEvent.setup>,
    pick: () => Promise<void>,
    wsId = WS_ID,
  ) {
    const view = renderModal(wsId)
    await pick()
    await fillAndSubmit(user)
    view.unmount()
  }

  it('pre-selects the last choice made in THIS workspace, after a successful create', async () => {
    const user = userEvent.setup()
    await createWith(user, async () => {
      await user.click(screen.getByRole('radio', { name: /scrum/i }))
      await user.click(screen.getByRole('checkbox', { name: /releases/i }))
    })
    createMock.mockClear()

    renderModal()

    expect(screen.getByRole('radio', { name: /scrum/i })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /releases/i })).toBeChecked()
  })

  it('never overrides an explicit choice — the nudge is read once and then has no say', async () => {
    const user = userEvent.setup()
    await createWith(user, () => user.click(screen.getByRole('radio', { name: /scrum/i })))
    createMock.mockClear()

    renderModal()
    // The user disagrees with their own history: back to Kanban.
    await user.click(screen.getByRole('radio', { name: /kanban/i }))
    const payload = await fillAndSubmit(user)

    expect(payload.delivery).toEqual({ board: 'KANBAN', releases: false, estimation: false })
  })

  it('is scoped to the workspace, and is never sent as a server-side default', async () => {
    const user = userEvent.setup()
    await createWith(user, () => user.click(screen.getByRole('radio', { name: /scrum/i })))
    createMock.mockClear()

    // Another workspace starts lean: the nudge is a per-workspace convenience,
    // not an account-wide preference and not a stored default (§2.4).
    renderModal(OTHER_WS)
    expect(screen.getByRole('radio', { name: /kanban/i })).toBeChecked()
    const payload = await fillAndSubmit(user)
    expect(payload.delivery).toEqual(creationDelivery(LEAN_DELIVERY_CHOICE))
  })

  it('remembers nothing when the create failed', async () => {
    const user = userEvent.setup()
    createMock.mockRejectedValueOnce(new Error('Key already in use'))
    renderModal()

    await user.click(screen.getByRole('radio', { name: /scrum/i }))
    await fillAndSubmit(user)
    await screen.findByText('Key already in use')
    screen.getByRole('button', { name: 'Cancel' }) // still open
    createMock.mockClear()

    // A picker the user fiddled with and did not get a project out of expressed
    // no preference.
    expect(localStorage.getItem(`hamstrack.delivery-choice.${ME.id}.${WS_ID}`)).toBeNull()
  })

  it('falls back to the lean default when the stored value is unreadable or stale', async () => {
    // Written by an older build / hand-edited / carrying a capability that is not
    // asked at creation — discarded wholesale rather than repaired, so nothing
    // can be replayed into a create request as an invisible choice.
    localStorage.setItem(
      `hamstrack.delivery-choice.${ME.id}.${WS_ID}`,
      JSON.stringify({ board: 'WATERFALL', addOns: ['estimation'] }),
    )
    const user = userEvent.setup()
    renderModal()

    expect(screen.getByRole('radio', { name: /kanban/i })).toBeChecked()
    const payload = await fillAndSubmit(user)
    expect(payload.delivery).toEqual(creationDelivery(LEAN_DELIVERY_CHOICE))
  })
})
