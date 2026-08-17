import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CAPABILITY, useEnableCapability, useSetCapability, type DeliveryCapability } from './delivery'

/**
 * HD-104 (S3) — what the SERVER actually receives when a capability is enabled.
 *
 * Every other test in this slice asserts the argument handed to a mocked
 * `apiUpdateProject`, which is one serialization short of the claim being made.
 * The claim is about the wire: S1 answers **400** to a body carrying the derived
 * `preset`, and an explicit `preset: undefined` is invisible to `toEqual` yet
 * would be dropped — or not — depending on how the body is built. So this file
 * mocks `fetch` instead of the API client and reads the request body as a
 * STRING, exactly as the backend parses it.
 *
 * It is also the table-driven half of Rule C's write contract: it iterates
 * `CAPABILITY` rather than naming capabilities, so a fourth row whose `patch`
 * echoes the read object (or restates a capability it should not touch) fails
 * here without anyone remembering to add a test for it.
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

/** The response shape `apiUpdateProject` parses — content is irrelevant here. */
const PROJECT_JSON = JSON.stringify({
  id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
  archived: false, myRole: 'MANAGER', createdAt: '2026-01-01T00:00:00Z',
  delivery: { board: 'SCRUM', releases: true, estimation: true, preset: 'CUSTOM' },
})

const fetchMock = vi.fn(async () => new Response(PROJECT_JSON, {
  status: 200, headers: { 'Content-Type': 'application/json' },
}))

/** The single writer, exercised through the real `api.ts` — nothing is stubbed. */
function Harness({ capability }: { capability: DeliveryCapability }) {
  const { enable, error } = useEnableCapability(WS_ID, PROJECT_ID)
  return (
    <>
      <button onClick={() => { enable(capability).catch(() => {}) }}>{CAPABILITY[capability].enable}</button>
      <span data-testid="err">{error}</span>
    </>
  )
}

beforeEach(() => {
  fetchMock.mockClear()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

describe('useEnableCapability — the bytes on the wire (HD-104)', () => {
  // Iterating the table is the point: a capability added to `CAPABILITY` is
  // covered here the moment it exists.
  it.each(Object.keys(CAPABILITY) as DeliveryCapability[])(
    'PATCHes only its own member of `delivery` for %s, and never `preset`',
    async capability => {
      const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      render(
        <QueryClientProvider client={qc}><Harness capability={capability} /></QueryClientProvider>,
      )

      await userEvent.click(screen.getByRole('button'))
      await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

      const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
      expect(url).toBe(`/api/workspaces/${WS_ID}/projects/${PROJECT_ID}`)
      expect(init.method).toBe('PATCH')

      const body = init.body as string
      expect(typeof body).toBe('string')
      // The serialized body, not an object comparison: exactly one member of
      // `delivery`, and no `preset` anywhere in the bytes.
      expect(body).toBe(JSON.stringify({ delivery: CAPABILITY[capability].patch }))
      expect(body).not.toContain('preset')
      expect(body).not.toContain('boardMode')
      expect(Object.keys(JSON.parse(body))).toEqual(['delivery'])
      expect(Object.keys(JSON.parse(body).delivery)).toHaveLength(1)
    },
  )

  it('never lets the enable-only wrapper choose a direction', () => {
    // `useEnableCapability` is a THIN wrapper over the two-way writer, and its
    // whole reason to exist is that a "Plan in sprints" button can only ever mean
    // ON. Structurally: it exposes `enable`, and nothing that takes a boolean.
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    let api: ReturnType<typeof useEnableCapability> | null = null
    function Probe() { api = useEnableCapability(WS_ID, PROJECT_ID); return null }
    render(<QueryClientProvider client={qc}><Probe /></QueryClientProvider>)

    expect(Object.keys(api!).sort()).toEqual(['enable', 'error', 'isPending', 'reset'])
    expect(api!.enable).toHaveLength(1)   // (capability) — no `on` argument to get wrong
  })

  it('surfaces the server’s own wording when the switch is refused', async () => {
    // A refused enable must say WHY (403 after a role change, 409 on an archived
    // project) — the affordance is useless if it fails silently.
    fetchMock.mockResolvedValueOnce(new Response(
      JSON.stringify({ detail: 'This project is archived' }),
      { status: 409, headers: { 'Content-Type': 'application/json' } },
    ))
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={qc}><Harness capability="releases" /></QueryClientProvider>,
    )

    await userEvent.click(screen.getByRole('button'))

    await waitFor(() => expect(screen.getByTestId('err')).toHaveTextContent('This project is archived'))
  })
})

/**
 * HD-106 (S5) — the OFF direction on the wire.
 *
 * S3 only ever built enable-on-first-use, so every wire assertion so far has been
 * about a body that turns something ON. The off direction is the new half, it goes
 * through the SAME writer, and it is the one where a mistake is expensive: a body
 * that restated a second member would silently reset a capability the user did not
 * touch, and a body carrying `preset` is a 400 the user meets as "nothing happens".
 *
 * The expected bytes are written out LONGHAND rather than derived from
 * `capabilityPatch`, which is the function under test — a test that asks the
 * implementation what it should send can only ever agree with it. `board` is the
 * interesting row: its off value is a named mode (`KANBAN`), not `false`.
 */
const OFF_BODY: Record<DeliveryCapability, string> = {
  iterations: '{"delivery":{"board":"KANBAN"}}',
  releases: '{"delivery":{"releases":false}}',
  estimation: '{"delivery":{"estimation":false}}',
}

/** The two-way writer, exercised through the real `api.ts`. */
function OffHarness({ capability }: { capability: DeliveryCapability }) {
  const { set } = useSetCapability(WS_ID, PROJECT_ID)
  return (
    <button onClick={() => { set({ capability, on: false }).catch(() => {}) }}>
      {CAPABILITY[capability].turnOff.action}
    </button>
  )
}

describe('useSetCapability — turning a capability OFF (HD-106 §13)', () => {
  it('covers every capability in the table', () => {
    // A fourth row added to CAPABILITY without an expected off body fails HERE,
    // rather than shipping an untested off switch.
    expect(Object.keys(OFF_BODY).sort()).toEqual(Object.keys(CAPABILITY).sort())
  })

  it.each(Object.keys(CAPABILITY) as DeliveryCapability[])(
    'PATCHes exactly one member of `delivery` to turn %s off',
    async capability => {
      const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      render(
        <QueryClientProvider client={qc}><OffHarness capability={capability} /></QueryClientProvider>,
      )

      await userEvent.click(screen.getByRole('button'))
      await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

      const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
      expect(url).toBe(`/api/workspaces/${WS_ID}/projects/${PROJECT_ID}`)
      expect(init.method).toBe('PATCH')

      const body = init.body as string
      // The serialized bytes, exactly as the backend parses them — an explicit
      // `preset: undefined` or a restated sibling is invisible to `toEqual`.
      expect(body).toBe(OFF_BODY[capability])
      expect(body).not.toContain('preset')
      expect(body).not.toContain('boardMode')
      expect(Object.keys(JSON.parse(body))).toEqual(['delivery'])
      expect(Object.keys(JSON.parse(body).delivery)).toHaveLength(1)
    },
  )

  it('is the exact inverse of the enable body, member for member', async () => {
    // On and off must address the SAME member of `delivery` — the reason
    // `capabilityPatch` derives the off value from the on one instead of storing
    // a second, independently-editable patch.
    for (const capability of Object.keys(CAPABILITY) as DeliveryCapability[]) {
      const onMember = Object.keys(CAPABILITY[capability].patch)
      const offMember = Object.keys(JSON.parse(OFF_BODY[capability]).delivery)
      expect(offMember).toEqual(onMember)
    }
  })

  it('turns iterations off as KANBAN — a named mode, never `false`', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={qc}><OffHarness capability="iterations" /></QueryClientProvider>,
    )

    await userEvent.click(screen.getByRole('button'))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    const body = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].body as string
    // `board: false` would be a 400 (the column is a two-value enum) and "Kanban
    // is the absence of Scrum" is the framing §2 exists to reject.
    expect(JSON.parse(body).delivery.board).toBe('KANBAN')
    expect(body).not.toContain('false')
  })
})
