import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import CreateProjectModal from './CreateProjectModal'
import { CREATION_REVERSIBLE_NOTICE } from './delivery'
import { useAuthStore } from '../auth'
import type { User } from '../types'

/**
 * HD-105 (S4) — what the SERVER actually receives when a project is created.
 *
 * `CreateProjectModal.test.tsx` asserts the ARGUMENT handed to a mocked
 * `apiCreateProject`, which is one serialization short of the claim being made.
 * The claim is about the wire, and the gap is not theoretical:
 *
 *  • S1 answers **400** to a create body carrying the derived `preset`
 *    (`DeliveryCapabilitiesApiTest.presetIsRejectedInRequestsRatherThanIgnored`),
 *    so an extra key is a broken feature, not a cosmetic wart.
 *  • `toEqual` ignores keys whose value is `undefined`, and `JSON.stringify`
 *    drops them too — so an object assertion and the bytes can agree for the
 *    wrong reason, and disagree the moment someone writes `preset: null`.
 *
 * So this file mocks `fetch` rather than the API client and reads the request
 * body as a STRING, exactly as the backend parses it. It is the create-side twin
 * of `delivery.wire.test.tsx`, which does the same for the enable-a-capability
 * PATCH.
 */

const WS_ID = 'w1'
const OTHER_WS = 'w2'
const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }
const COLLEAGUE: User = { id: 'u-you', email: 'you@example.com', displayName: 'You Yourself' }

const PROJECT_JSON = JSON.stringify({
  id: 'p-new', workspaceId: WS_ID, name: 'Payments', key: 'PAY',
  archived: false, myRole: 'MANAGER', createdAt: '2026-08-17T00:00:00Z',
  delivery: { board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN' },
})

const fetchMock = vi.fn(async () => new Response(PROJECT_JSON, {
  status: 201, headers: { 'Content-Type': 'application/json' },
}))

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

/** Submit, then hand back the request body as the STRING the server would parse. */
async function submitAndReadBody(
  user: ReturnType<typeof userEvent.setup>,
  wsId = WS_ID,
): Promise<string> {
  await user.type(screen.getByLabelText('Project name'), 'Payments')
  await user.click(screen.getByRole('button', { name: 'Create project' }))
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

  const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
  // The create is workspace-scoped in the PATH — the tenant is never a body field.
  expect(url).toBe(`/api/workspaces/${wsId}/projects`)
  expect(init.method).toBe('POST')
  expect(typeof init.body).toBe('string')
  return init.body as string
}

beforeEach(() => {
  fetchMock.mockClear()
  localStorage.clear()
  vi.stubGlobal('fetch', fetchMock)
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
})
afterEach(() => vi.unstubAllGlobals())

describe('CreateProjectModal — the bytes a create actually sends (HD-105)', () => {
  it('sends the LEAN default verbatim when the picker is never touched', async () => {
    const user = userEvent.setup()
    renderModal()

    const body = await submitAndReadBody(user)

    // D4 on the wire — §17 calls this the highest-risk assumption in the design,
    // so the assertion is on the bytes, not on which card looks selected.
    expect(JSON.parse(body).delivery).toEqual({
      board: 'KANBAN', releases: false, estimation: false,
    })
    // The whole body, byte for byte: nothing rides along that `toEqual` on the
    // argument object would have forgiven.
    expect(body).toBe(JSON.stringify({
      // `PAYMEN` — the key auto-derived from the name, capped at 6 characters.
      name: 'Payments', key: 'PAYMEN',
      delivery: { board: 'KANBAN', releases: false, estimation: false },
    }))
  })

  it('expresses Scrum + Releases in ONE request, with estimation derived (D1, §18 q7)', async () => {
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByRole('radio', { name: /scrum/i }))
    await user.click(screen.getByRole('checkbox', { name: /releases/i }))
    const body = await submitAndReadBody(user)

    // The combination a three-way enum would make unrepresentable — and
    // `estimation: true`, which the picker deliberately never asks about.
    expect(JSON.parse(body).delivery).toEqual({
      board: 'SCRUM', releases: true, estimation: true,
    })
  })

  it('never puts `preset` or `boardMode` in the bytes — S1 answers 400 to either', async () => {
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByRole('radio', { name: /scrum/i }))
    const body = await submitAndReadBody(user)

    // A substring check on the SERIALIZED body, so an `undefined`-valued extra
    // key cannot pass by being invisible to both the stringifier and `toEqual`.
    expect(body).not.toContain('preset')
    expect(body).not.toContain('boardMode')
    // …and the exhaustive form: exactly the three capabilities, nothing else.
    expect(Object.keys(JSON.parse(body).delivery).sort())
      .toEqual(['board', 'estimation', 'releases'])
    expect(Object.keys(JSON.parse(body)).sort()).toEqual(['delivery', 'key', 'name'])
  })

  it('sends `delivery` explicitly even when it equals the server default', async () => {
    const user = userEvent.setup()
    renderModal()

    const body = await submitAndReadBody(user)

    // S1 would apply the same lean defaults for an omitted `delivery`, but the
    // picker exists to make the choice a STATEMENT — and an explicit body is what
    // makes the localStorage nudge auditable in the network tab.
    expect(JSON.parse(body)).toHaveProperty('delivery')
  })
})

describe('CreateProjectModal — the nudge is keyed per user AND per workspace (§18 q1)', () => {
  /** Create once as `who` in `wsId`, picking Scrum, then unmount. */
  async function createAsScrum(who: User, wsId: string) {
    useAuthStore.setState({ user: who, accessToken: 'test-token', initialized: true })
    const user = userEvent.setup()
    renderModal(wsId)
    await user.click(screen.getByRole('radio', { name: /scrum/i }))
    await user.type(screen.getByLabelText('Project name'), 'Payments')
    await user.click(screen.getByRole('button', { name: 'Create project' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    cleanup()
    fetchMock.mockClear()
  }

  it('does not leak one user’s choice to another sharing the browser', async () => {
    await createAsScrum(ME, WS_ID)
    // The remembered choice is real — this is the control for the assertion below.
    expect(localStorage.getItem(`hamstrack.delivery-choice.${ME.id}.${WS_ID}`)).toBeTruthy()

    // Same browser, same workspace, different account. Accounts sharing a device
    // must never inherit each other's preferences.
    useAuthStore.setState({ user: COLLEAGUE, accessToken: 'test-token', initialized: true })
    const user = userEvent.setup()
    renderModal(WS_ID)

    expect(screen.getByRole('radio', { name: /kanban/i })).toBeChecked()
    const body = await submitAndReadBody(user)
    expect(JSON.parse(body).delivery).toEqual({
      board: 'KANBAN', releases: false, estimation: false,
    })
  })

  it('does not leak one workspace’s choice to another for the same user', async () => {
    await createAsScrum(ME, WS_ID)

    // The shop running ten Scrum projects is the case the nudge exists for; their
    // consulting workspace is not it.
    const user = userEvent.setup()
    renderModal(OTHER_WS)

    expect(screen.getByRole('radio', { name: /kanban/i })).toBeChecked()
    const body = await submitAndReadBody(user, OTHER_WS)
    expect(JSON.parse(body).delivery).toEqual({
      board: 'KANBAN', releases: false, estimation: false,
    })
  })

  it('keeps the two keys independent rather than merely last-write-wins', async () => {
    await createAsScrum(ME, WS_ID)
    await createAsScrum(COLLEAGUE, OTHER_WS)

    // Four distinct (user, workspace) pairs, two of them written: a single shared
    // key would have collapsed these into one entry and the isolation tests above
    // would pass for the wrong reason.
    expect(localStorage.getItem(`hamstrack.delivery-choice.${ME.id}.${WS_ID}`)).toBeTruthy()
    expect(localStorage.getItem(`hamstrack.delivery-choice.${COLLEAGUE.id}.${OTHER_WS}`)).toBeTruthy()
    expect(localStorage.getItem(`hamstrack.delivery-choice.${ME.id}.${OTHER_WS}`)).toBeNull()
    expect(localStorage.getItem(`hamstrack.delivery-choice.${COLLEAGUE.id}.${WS_ID}`)).toBeNull()
  })
})

describe('CreateProjectModal — the reassurance line is body copy (§12)', () => {
  it('is a real text node, not a `title` attribute on something you must hover', () => {
    renderModal()

    const notice = screen.getByText(CREATION_REVERSIBLE_NOTICE)
    // `getByText` never matches `title`, but the negative half is worth stating
    // outright: nothing in the modal demotes this sentence to a hover hint. It is
    // invisible to exactly the person deciding whether the choice is expensive.
    expect(notice).toBeInTheDocument()
    expect(notice.textContent).toBe(CREATION_REVERSIBLE_NOTICE)
    const hoverOnly = Array.from(document.querySelectorAll('[title]'))
      .map(el => el.getAttribute('title'))
    expect(hoverOnly).not.toContain(CREATION_REVERSIBLE_NOTICE)
  })

  it('precedes the first card, so it is read while deciding and not after', () => {
    renderModal()

    const notice = screen.getByText(CREATION_REVERSIBLE_NOTICE)
    const firstCard = screen.getAllByRole('radio')[0]
    // DOCUMENT_POSITION_FOLLOWING: the card comes AFTER the notice.
    expect(notice.compareDocumentPosition(firstCard) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy()
    // And the converse, so a symmetric bug (both flags set on an ancestor
    // relationship) cannot satisfy the assertion above.
    expect(notice.compareDocumentPosition(firstCard) & Node.DOCUMENT_POSITION_PRECEDING)
      .toBeFalsy()
  })
})
