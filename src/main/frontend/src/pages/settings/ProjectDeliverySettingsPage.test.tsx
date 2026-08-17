import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import ProjectDeliverySettingsPage from './ProjectDeliverySettingsPage'
import { ApiResponseError, apiSearch, apiUpdateProject } from '../../api'
import type { Project, ProjectDelivery, Sprint, Version } from '../../types'

/**
 * HD-106 (S5) — Project settings → **Delivery**.
 *
 * Two halves, and the second one is what this slice adds:
 *
 *  • **The write contract** (inherited from the Board tab's tests, because the
 *    endpoint did not change): `delivery` is PARTIAL, so switching one capability
 *    must not restate — and so must not be able to silently reset — the other
 *    two; and `preset` is DERIVED server-side and REJECTED on write (400), so the
 *    object read from a GET may never be echoed back.
 *  • **The OFF direction** (§13). Nothing before this slice could turn a
 *    capability off, so nothing tested that switching is *non-destructive and
 *    legible*: the confirmations have to name the live data a user would fear
 *    losing (the sprint that is running, the versions that have not shipped) and
 *    say what happens to it, and the page has to keep saying it afterwards.
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

const BASE: Project = {
  id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
  archived: false, myRole: 'MANAGER', createdAt: '2026-01-01T00:00:00Z',
}

function withDelivery(delivery: Partial<ProjectDelivery>): Project {
  return {
    ...BASE,
    delivery: { board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN', ...delivery },
  }
}

function sprint(name: string, state: Sprint['state'], sequence: number): Sprint {
  return {
    id: `s-${sequence}`, name, state, sequence,
    issueCount: 4, doneIssueCount: 1, points: 8, donePoints: 2, unestimatedCount: 0,
    createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
  }
}

function version(name: string, released: boolean): Version {
  return {
    id: `v-${name}`, name, released, archived: false,
    issueCount: 3, doneIssueCount: 1, affectsIssueCount: 0,
    createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
  }
}

// Swapped per test — what each endpoint answers with.
let project: Project = withDelivery({})
let sprints: Sprint[] = []
let versions: Version[] = []
/** The caller's WORKSPACE role — the other half of the curator predicate. */
let workspaceRole: 'OWNER' | 'ADMIN' | 'MEMBER' = 'OWNER'

/**
 * Every WRITE the two data modules can perform. They are mocked as spies rather
 * than omitted so the §13 invariant — *switching never completes, moves or
 * deletes anything* — can be asserted directly instead of inferred from the one
 * PATCH we do see.
 */
// `vi.hoisted`: the mock factory below spreads these, and it runs before any
// ordinary top-level binding is initialised.
const sprintWrites = vi.hoisted(() => ({
  create: vi.fn(), update: vi.fn(), start: vi.fn(), complete: vi.fn(),
  addIssues: vi.fn(), removeIssue: vi.fn(), remove: vi.fn(),
}))
const versionWrites = vi.hoisted(() => ({
  create: vi.fn(), update: vi.fn(), release: vi.fn(), unrelease: vi.fn(),
  archive: vi.fn(), unarchive: vi.fn(), remove: vi.fn(),
}))

vi.mock('../../api', () => ({
  // Faithful to the real class (status, detail) — `errorText` reads `detail`, the
  // ProblemDetail field the server's own wording arrives in.
  ApiResponseError: class ApiResponseError extends Error {
    status: number
    detail: string
    constructor(status: number, detail: string) {
      super(detail)
      this.status = status
      this.detail = detail
    }
  },
  apiGetProject: vi.fn(async () => project),
  apiUpdateProject: vi.fn(async () => project),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'WS', slug: 'ws', myRole: workspaceRole, createdAt: '2026-01-01T00:00:00Z',
  })),
  // Mocked but expected to stay UNCALLED — see "asks search nothing, ever".
  apiSearch: vi.fn(async () => (
    { content: [], page: 0, size: 1, totalElements: 0, totalPages: 1, hasNext: false }
  )),
  sprintsApi: {
    list: vi.fn(async () => ({
      content: sprints, page: 0, size: 200, totalElements: sprints.length,
      totalPages: 1, hasNext: false,
    })),
    get: vi.fn(),
    completionPreview: vi.fn(),
    ...sprintWrites,
  },
  versionsApi: { list: vi.fn(async () => versions), get: vi.fn(), ...versionWrites },
  // Imported by `components/sprints`, unused here — a mocked module must still
  // expose every binding its importers destructure.
  apiGetBacklogView: vi.fn(),
  apiListIssuesPaged: vi.fn(),
}))

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/p/${PROJECT_ID}/settings/delivery`]}>
        <Routes>
          <Route
            path="/w/:wsId/p/:projectId/settings/delivery"
            element={<ProjectDeliverySettingsPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** The exact bytes a PATCH would carry — `toEqual` cannot see an explicit undefined. */
function sentBody(call = 0): Record<string, Record<string, unknown>> {
  return JSON.parse(JSON.stringify(vi.mocked(apiUpdateProject).mock.calls[call][2]))
}

/** Every sprint / version WRITE the page could conceivably have triggered. */
function dataWrites() {
  return [...Object.values(sprintWrites), ...Object.values(versionWrites)]
}

beforeEach(() => {
  project = withDelivery({})
  sprints = []
  versions = []
  workspaceRole = 'OWNER'
  vi.mocked(apiUpdateProject).mockClear()
  vi.mocked(apiSearch).mockClear()
  for (const spy of dataWrites()) spy.mockReset()
})

describe('Delivery tab — the write payload (HD-102 / HD-106)', () => {
  it('sends a PARTIAL `delivery` carrying only the board, and never `preset`', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /scrum/i }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    const [wsId, projectId] = vi.mocked(apiUpdateProject).mock.calls[0]
    expect(wsId).toBe(WS_ID)
    expect(projectId).toBe(PROJECT_ID)

    const sent = sentBody()
    expect(Object.keys(sent)).toEqual(['delivery'])
    expect(Object.keys(sent.delivery)).toEqual(['board'])
    expect(sent.delivery).toEqual({ board: 'SCRUM' })
    // The other two capabilities are untouched, not restated at their read value.
    expect(sent.delivery).not.toHaveProperty('preset')
    expect(sent.delivery).not.toHaveProperty('releases')
    expect(sent.delivery).not.toHaveProperty('estimation')
  })

  it('does not send the deprecated top-level `boardMode` alongside it', async () => {
    // Sending both with DIFFERENT values is a 400 on the S1 backend; new code
    // sends `delivery` only, so the ambiguity can never arise.
    renderPage()
    await userEvent.click(await screen.findByRole('radio', { name: /scrum/i }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(vi.mocked(apiUpdateProject).mock.calls[0][2]).not.toHaveProperty('boardMode')
  })

  it('reads the current mode from `delivery`, not from the deprecated mirror', async () => {
    project = { ...withDelivery({ board: 'SCRUM', estimation: true, preset: 'SCRUM' }), boardMode: 'KANBAN' }
    renderPage()

    await waitFor(() => expect(screen.getByRole('radio', { name: /scrum/i })).toBeChecked())
    expect(screen.getByRole('radio', { name: /kanban/i })).not.toBeChecked()
  })

  it('falls back to the mirror for a project that predates `delivery`', async () => {
    // §7 upgrade rule via `deliveryOf`: no `delivery` on the wire ⇒ the board
    // still comes from `boardMode`, so an un-migrated response reads correctly.
    project = { ...BASE, boardMode: 'SCRUM' }
    renderPage()

    await waitFor(() => expect(screen.getByRole('radio', { name: /scrum/i })).toBeChecked())
  })

  it('shows the derived preset as a label and never sends it back', async () => {
    project = withDelivery({ board: 'SCRUM', releases: true, estimation: true, preset: 'CUSTOM' })
    renderPage()

    // The pill carries its own explanation — the label is derived, not a setting.
    const pill = await screen.findByTitle(/Derived from the three capabilities/i)
    expect(pill).toHaveTextContent('Custom')
    await userEvent.click(screen.getByRole('radio', { name: /kanban/i }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(sentBody().delivery).not.toHaveProperty('preset')
  })
})

/**
 * Rule C's half (§5.3): an OFF capability is LISTED with a way back, never
 * merely absent — the property that keeps a releases-off project reachable at
 * all, since its rail item disappears with the capability.
 */
describe('Delivery tab — off capabilities stay reachable (HD-104)', () => {
  it('lists the off capabilities with an enabling action, and only the off ones', async () => {
    project = withDelivery({ estimation: true })
    renderPage()

    expect(await screen.findByRole('button', { name: /turn on releases/i })).toBeInTheDocument()
    // Estimation is already on — it is listed with the way OUT, not the way in.
    expect(screen.queryByRole('button', { name: /turn on story points/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Turn off story points' })).toBeInTheDocument()
    // The reversibility of the switch is copy on the page, not a tooltip.
    expect(screen.getByText(/turn them off again in Project settings/i)).toBeInTheDocument()
  })

  it('enables one with the same partial, preset-free PATCH as the board switch', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /turn on story points/i }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(Object.keys(sentBody().delivery)).toEqual(['estimation'])
    expect(sentBody().delivery).toEqual({ estimation: true })
    expect(sentBody().delivery).not.toHaveProperty('preset')
  })

  it('never confirms turning a capability ON', async () => {
    // §13's first row: nothing to lose, so nothing to ask about.
    versions = [version('2.4.0', false)]
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /turn on releases/i }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

/**
 * §13 — switching a capability OFF. The direction S1–S4 never implemented, and
 * the one where a user's fear ("did I just delete my sprint?") has to be
 * answered *before* the click, with this project's own data in the sentence.
 */
describe('Delivery tab — switching away from Scrum (§13)', () => {
  beforeEach(() => {
    project = withDelivery({ board: 'SCRUM', estimation: true, preset: 'SCRUM' })
  })

  it('confirms with the running sprint named, and states what happens to it', async () => {
    sprints = [sprint('Sprint 7', 'ACTIVE', 7)]
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /kanban/i }))

    const dialog = await screen.findByRole('dialog', { name: /switch to kanban\?/i })
    // The sprint keeps running and stays visible — never auto-completed, never
    // hidden, and Complete sprint is still offered (§6 / the Backlog's behaviour).
    expect(dialog).toHaveTextContent(/Sprint 7 is running/i)
    expect(dialog).toHaveTextContent(/read-only section/i)
    expect(dialog).toHaveTextContent(/Complete sprint/i)
    expect(dialog).toHaveTextContent(/until you complete it/i)
    // …and the ranked backlog is explicitly untouched (rank is shared with the
    // board and was never sprint-specific).
    expect(dialog).toHaveTextContent(/ranked backlog is untouched/i)
    expect(dialog).toHaveTextContent(/keeps its exact position/i)
    // Nothing is written until the user confirms.
    expect(apiUpdateProject).not.toHaveBeenCalled()
  })

  it('names the planned sprints too, and calls them kept', async () => {
    sprints = [sprint('Sprint 7', 'ACTIVE', 7), sprint('Sprint 8', 'FUTURE', 8), sprint('Sprint 9', 'FUTURE', 9)]
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /kanban/i }))

    const dialog = await screen.findByRole('dialog', { name: /switch to kanban\?/i })
    expect(dialog).toHaveTextContent(/Sprint 8 and Sprint 9/)
    expect(dialog).toHaveTextContent(/kept/i)
  })

  it('writes only `delivery.board` once confirmed', async () => {
    sprints = [sprint('Sprint 7', 'ACTIVE', 7)]
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /kanban/i }))
    const dialog = await screen.findByRole('dialog', { name: /switch to kanban\?/i })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Switch to Kanban' }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(Object.keys(sentBody().delivery)).toEqual(['board'])
    expect(sentBody().delivery).toEqual({ board: 'KANBAN' })
  })

  it('writes nothing when the confirmation is cancelled', async () => {
    sprints = [sprint('Sprint 7', 'ACTIVE', 7)]
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /kanban/i }))
    const dialog = await screen.findByRole('dialog', { name: /switch to kanban\?/i })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(apiUpdateProject).not.toHaveBeenCalled()
  })

  it('does not confirm when the project has no open sprint', async () => {
    // §13 "empty / last-of-kind": there is nothing to warn about.
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /kanban/i }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('Delivery tab — turning releases and estimation off (§13)', () => {
  it('confirms releases-off with the unreleased count, then writes only `releases`', async () => {
    project = withDelivery({ releases: true, preset: 'RELEASES' })
    versions = [version('2.4.0', false), version('2.5.0', false), version('2.3.0', true)]
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Turn off releases' }))

    const dialog = await screen.findByRole('dialog', { name: /turn off releases\?/i })
    expect(dialog).toHaveTextContent(/2 unreleased versions/i)
    expect(dialog).toHaveTextContent(/of 3 in this project/i)
    expect(dialog).toHaveTextContent(/every issue linked to one/i)
    expect(dialog).toHaveTextContent(/nothing is deleted, released or unlinked/i)

    await userEvent.click(within(dialog).getByRole('button', { name: 'Turn off releases' }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(Object.keys(sentBody().delivery)).toEqual(['releases'])
    expect(sentBody().delivery).toEqual({ releases: false })
  })

  it('does not confirm releases-off when nothing is unreleased', async () => {
    project = withDelivery({ releases: true, preset: 'RELEASES' })
    versions = [version('2.3.0', true)]
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Turn off releases' }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  /**
   * Estimation is the one capability with NO live fact behind its confirmation,
   * and that is now permanent until HD-101 / HD-113. The count used to come from
   * `apiSearch('project = "PR" AND storyPoints >= 0')`, but `project` is not a
   * registered HQL field — the query is a 422 against the real app, so the number
   * was unreachable in production and only ever appeared against this mock. What
   * the dialog must do instead is say the true thing WITHOUT a number, and in
   * particular never claim there is nothing at stake.
   */
  it('confirms estimation-off with §13’s sentence, and invents no count', async () => {
    project = withDelivery({ estimation: true })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Turn off story points' }))

    const dialog = await screen.findByRole('dialog', { name: /turn off story points\?/i })
    expect(dialog).toHaveTextContent(/Every story point already recorded is kept/i)
    // The spec pins this wording — the values are kept, only the input goes.
    expect(dialog).toHaveTextContent(
      /Existing story points are kept and still shown on issues; only the input is hidden\./i)
    // A made-up reassurance is worse than none (§13): no invented total, and no
    // "nothing to keep" on a project that may be full of estimates.
    expect(dialog).not.toHaveTextContent(/No issue carries an estimate yet/i)
    expect(dialog).not.toHaveTextContent(/\d+ issues? (carry|carries) an estimate/i)

    await userEvent.click(within(dialog).getByRole('button', { name: 'Turn off story points' }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(sentBody().delivery).toEqual({ estimation: false })
  })
})

/**
 * Carried over from the retired Board tab (deleted with `ProjectBoardSettingsPage`),
 * whose own tests covered the save's two outcomes. The page changed shape and the
 * write changed from the deprecated `{ boardMode }` to `{ delivery: { board } }`,
 * but the outcomes a user sees must not have been lost in the move — a settings
 * switch that fails silently is indistinguishable from one that did nothing.
 */
describe('Delivery tab — the outcome of a save (ex-Board tab)', () => {
  /** The mocked `ApiResponseError` shape: a ProblemDetail `detail` is the server's own wording. */
  function refusal(detail: string): ApiResponseError {
    return new ApiResponseError(409, detail)
  }

  it('confirms a successful switch, and says every surface already follows it', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /scrum/i }))

    expect(await screen.findByText(/Saved — every surface of this project follows/i))
      .toBeInTheDocument()
  })

  it('shows the server’s own wording when the board switch is refused', async () => {
    // 409 (archived under us) / 403 (role changed under us). The old Board tab's
    // `apiErrorText` fallback is now `switcher.error`; the guarantee is the same.
    vi.mocked(apiUpdateProject).mockRejectedValueOnce(refusal('This project is archived'))
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /scrum/i }))

    expect(await screen.findByText('This project is archived')).toBeInTheDocument()
    expect(screen.queryByText(/Saved — every surface/i)).not.toBeInTheDocument()
    // The radio still shows the project's real, unchanged state.
    expect(screen.getByRole('radio', { name: /kanban/i })).toBeChecked()
  })

  it('keeps the confirmation open on a refusal, so the reason is readable', async () => {
    project = withDelivery({ board: 'SCRUM', estimation: true, preset: 'SCRUM' })
    sprints = [sprint('Sprint 7', 'ACTIVE', 7)]
    vi.mocked(apiUpdateProject).mockRejectedValueOnce(refusal('Project is archived'))
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /kanban/i }))
    const dialog = await screen.findByRole('dialog', { name: /switch to kanban\?/i })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Switch to Kanban' }))

    // Closing the dialog would take the only copy of the reason with it.
    await waitFor(() => expect(within(dialog).getByText('Project is archived')).toBeInTheDocument())
    expect(screen.getByRole('dialog', { name: /switch to kanban\?/i })).toBeInTheDocument()

    // …and a retry from the still-open dialog goes through.
    await userEvent.click(within(dialog).getByRole('button', { name: 'Switch to Kanban' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(apiUpdateProject).toHaveBeenCalledTimes(2)
    expect(sentBody(1).delivery).toEqual({ board: 'KANBAN' })
  })

  it('points a Scrum project at the Backlog, where sprints are actually planned', async () => {
    // The old Board tab's one navigational affordance — the absolute path matters
    // (a relative one resolves AFTER the /settings/* splat).
    project = withDelivery({ board: 'SCRUM', estimation: true, preset: 'SCRUM' })
    renderPage()

    const link = await screen.findByRole('link', { name: 'Open Backlog' })
    expect(link.getAttribute('href')).toBe(`/w/${WS_ID}/p/${PROJECT_ID}/backlog`)
    expect(screen.getByText(/Plan and start sprints from the project/i)).toBeInTheDocument()
  })

  it('offers no such link while the board is Kanban', async () => {
    renderPage()

    await screen.findByRole('radio', { name: /kanban/i })
    expect(screen.queryByText(/Plan and start sprints from the project/i)).not.toBeInTheDocument()
  })
})

/**
 * The switch is only half the promise; the other half is that the page keeps
 * saying what survived it (Rule B). A count read from the project's own data —
 * never a generic "your data is safe".
 */
describe('Delivery tab — live "kept data" notices', () => {
  it('reports the open sprints kept by a Kanban project', async () => {
    sprints = [sprint('Sprint 7', 'ACTIVE', 7), sprint('Sprint 8', 'FUTURE', 8)]
    renderPage()

    const notice = await screen.findByText(/open sprints kept/i)
    expect(notice).toHaveTextContent(/2 open sprints kept/i)
    expect(notice).toHaveTextContent(/Sprint 7 is still running/i)
    expect(notice).toHaveTextContent(/read-only sections/i)
    // …and it links to where they are still visible.
    expect(screen.getByRole('link', { name: 'Open Backlog' }).getAttribute('href'))
      .toBe(`/w/${WS_ID}/p/${PROJECT_ID}/backlog`)
  })

  it('reports the versions kept by a project with releases off', async () => {
    versions = [version('2.4.0', false), version('2.5.0', false), version('2.6.0', false), version('2.3.0', true)]
    renderPage()

    const notice = await screen.findByText(/unreleased/i, { selector: 'div' })
    expect(notice).toHaveTextContent(/3 unreleased versions kept/i)
    expect(notice).toHaveTextContent(/4 in total/i)
    expect(screen.getByRole('link', { name: 'Open Releases' }).getAttribute('href'))
      .toBe(`/w/${WS_ID}/p/${PROJECT_ID}/releases`)
  })

  it('says nothing about versions when the project has none', async () => {
    renderPage()

    await screen.findByRole('button', { name: /turn on releases/i })
    expect(screen.queryByText(/versions kept/i)).not.toBeInTheDocument()
  })

  it('keeps saying that recorded estimates survive with estimation off', async () => {
    renderPage()

    expect(await screen.findByText(/Story points already recorded are kept/i)).toBeInTheDocument()
  })
})

describe('Delivery tab — an archived project is frozen (§4)', () => {
  it('disables every switch and says why', async () => {
    project = { ...withDelivery({ releases: true, preset: 'RELEASES' }), archived: true }
    renderPage()

    expect(await screen.findByText(/unarchive it to change how it delivers/i)).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /scrum/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Turn off releases' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /turn on story points/i })).toBeDisabled()
  })
})

/**
 * §13's invariant, asserted rather than implied: **no capability change completes,
 * moves or deletes anything.** Every other test proves what the page *did* send
 * (one PATCH); these prove what it did NOT — the sprint and version write
 * endpoints are spied and must stay untouched through the whole switch, in both
 * the cancelled and the confirmed direction.
 */
describe('Delivery tab — switching mutates nothing but the project (§13)', () => {
  beforeEach(() => {
    project = withDelivery({ board: 'SCRUM', estimation: true, preset: 'SCRUM' })
    sprints = [sprint('Sprint 7', 'ACTIVE', 7), sprint('Sprint 8', 'FUTURE', 8)]
  })

  it('completes, moves or deletes no sprint when Scrum is switched off', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /kanban/i }))
    const dialog = await screen.findByRole('dialog', { name: /switch to kanban\?/i })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Switch to Kanban' }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    // The running sprint is still running: nothing was completed, nothing was
    // re-planned, no issue was moved out of it, nothing was deleted.
    for (const write of dataWrites()) expect(write).not.toHaveBeenCalled()
  })

  it('writes nothing at all when the confirmation is cancelled', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /kanban/i }))
    const dialog = await screen.findByRole('dialog', { name: /switch to kanban\?/i })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(apiUpdateProject).not.toHaveBeenCalled()
    for (const write of dataWrites()) expect(write).not.toHaveBeenCalled()
    // …and the radio still reads the project's real state, unchanged.
    expect(screen.getByRole('radio', { name: /scrum/i })).toBeChecked()
  })

  it('touches no version when releases are switched off', async () => {
    project = withDelivery({ releases: true, preset: 'RELEASES' })
    versions = [version('2.4.0', false), version('2.5.0', false)]
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Turn off releases' }))
    const dialog = await screen.findByRole('dialog', { name: /turn off releases\?/i })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Turn off releases' }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    // Nothing released, archived, unlinked or deleted — the sentence the dialog
    // just made, checked against the endpoints that could break it.
    for (const write of dataWrites()) expect(write).not.toHaveBeenCalled()
  })
})

/**
 * §13's top two rows: a switch that cannot surprise anybody is applied on the
 * spot. A confirmation nobody needs is not harmless — it teaches users to click
 * through the ones that matter.
 */
describe('Delivery tab — no confirmation where none is due (§13)', () => {
  it('never confirms Kanban → Scrum, even with an ACTIVE sprint already there', async () => {
    // Kanban → Scrum has nothing to lose in any state (a Kanban project may
    // already have sprints — that is exactly the dead end HD-102 fixed).
    sprints = [sprint('Sprint 7', 'ACTIVE', 7), sprint('Sprint 8', 'FUTURE', 8)]
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /scrum/i }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(sentBody().delivery).toEqual({ board: 'SCRUM' })
  })

  it('never confirms Scrum → Kanban with only COMPLETED sprints behind it', async () => {
    // `useOpenSprints` asks for ACTIVE + FUTURE only, so a project with years of
    // completed sprints and nothing open switches without ceremony.
    project = withDelivery({ board: 'SCRUM', estimation: true, preset: 'SCRUM' })
    sprints = []
    renderPage()

    await userEvent.click(await screen.findByRole('radio', { name: /kanban/i }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(sentBody().delivery).toEqual({ board: 'KANBAN' })
  })
})

/**
 * The page fetches **no** facts of its own: every number it shows comes from a
 * list the SPA already caches (sprints, versions).
 *
 * This guards the one that used to be different. The estimation dialog asked
 * search for `project = "PR" AND storyPoints >= 0`, which cannot work — `project`
 * is not a registered HQL field (the registry knows status, type, priority,
 * assignee, reporter, parent, text, created, updated, due, label, component,
 * fixVersion, affectsVersion, sprint, storyPoints), so the real app answers 422
 * *Unknown field* and the count was unreachable in production; and a workspace
 * owning a CUSTOM field keyed `project` would resolve the term to that field and
 * answer a silently wrong number. Dropping the term would count the whole
 * workspace. It comes back via HD-101 (register the field) or HD-113 (a
 * `delivery-usage` endpoint, which also answers for an archived project).
 */
describe('Delivery tab — the page asks search nothing, ever (HD-101 / HD-113)', () => {
  it('runs no search on load, on a confirmation, or on a switch', async () => {
    project = withDelivery({ estimation: true })
    renderPage()

    expect(await screen.findByRole('button', { name: 'Turn off story points' })).toBeInTheDocument()
    expect(apiSearch).not.toHaveBeenCalled()

    // The dialog is where the count used to be fetched — the one moment a search
    // could creep back in.
    await userEvent.click(screen.getByRole('button', { name: 'Turn off story points' }))
    const dialog = await screen.findByRole('dialog', { name: /turn off story points\?/i })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Turn off story points' }))

    await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
    expect(apiSearch).not.toHaveBeenCalled()
  })
})

/**
 * The preset is DERIVED server-side (§2.3) and exists here as a label only. Two
 * failure modes, both invisible without a test: a label this client invented for
 * a response that carried none, and a label that finds its way into a write (a
 * 400 the user meets as "the switch does nothing").
 */
describe('Delivery tab — the preset is display-only', () => {
  it('shows no pill for a response that carried no `delivery`', async () => {
    // `deliveryOf` answers CUSTOM for a pre-HD-102 project — rendering that as a
    // pill would show a label the server never derived.
    project = { ...BASE, boardMode: 'SCRUM' }
    renderPage()

    await waitFor(() => expect(screen.getByRole('radio', { name: /scrum/i })).toBeChecked())
    expect(screen.queryByTitle(/Derived from the three capabilities/i)).not.toBeInTheDocument()
  })

  it.each(['KANBAN', 'SCRUM', 'RELEASES', 'CUSTOM'] as const)(
    'renders %s as a label and never sends it, in either direction',
    async preset => {
      project = withDelivery({ board: 'SCRUM', releases: true, estimation: true, preset })
      versions = [version('2.4.0', false)]
      renderPage()

      expect(await screen.findByTitle(/Derived from the three capabilities/i)).toBeInTheDocument()

      // The OFF direction too — the one S3 never exercised.
      await userEvent.click(screen.getByRole('button', { name: 'Turn off releases' }))
      const dialog = await screen.findByRole('dialog', { name: /turn off releases\?/i })
      await userEvent.click(within(dialog).getByRole('button', { name: 'Turn off releases' }))

      await waitFor(() => expect(apiUpdateProject).toHaveBeenCalledTimes(1))
      expect(JSON.stringify(vi.mocked(apiUpdateProject).mock.calls[0][2])).not.toContain('preset')
    },
  )
})

/**
 * The role is now the REAL one (`needsRole: true`), so `CapabilityOffState`'s
 * member branch is reachable from this page for the first time. The settings area
 * redirects a non-curator, so this is defence in depth — but the component is
 * shared with the Backlog and the Releases page, where a member reaches it every
 * day, and it must say the same thing in all three.
 */
describe('Delivery tab — a plain member (§6)', () => {
  beforeEach(() => {
    workspaceRole = 'MEMBER'
    project = { ...withDelivery({}), myRole: 'MEMBER' }
  })

  it('is told the project’s way of working, with no vocabulary and no verb', async () => {
    renderPage()

    // The off capabilities state the fact; they do not teach a member a word for
    // something their project does not do, or offer an action they cannot take.
    expect(await screen.findByText(/Releases are off for this project\./i)).toBeInTheDocument()
    expect(screen.getByText(/Story-point estimation is off for this project\./i)).toBeInTheDocument()
    expect(screen.getByText(/a project admin can turn releases back on/i)).toBeInTheDocument()
  })

  it('is offered no enabling control for an off capability', async () => {
    renderPage()

    await screen.findByText(/Releases are off for this project\./i)
    expect(screen.queryByRole('button', { name: /turn on releases/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /turn on story points/i })).not.toBeInTheDocument()
    // …and the curator's own framing is gone with it.
    expect(screen.queryByText(/This project does not estimate/i)).not.toBeInTheDocument()
  })

  /**
   * The gap the S5 test gate found, now closed: only `CapabilityOffState` used to
   * consult `isCurator`, so the same member who is told "Story-point estimation is
   * off for this project" (no verb, no offer) was simultaneously handed a live
   * Scrum radio — an enabling control for iterations, and the exact vocabulary
   * §5.3 says to withhold — plus a working "Turn off releases".
   *
   * Never a hole (Rule A: the endpoint was never open to them, the click 403s,
   * and the settings area redirects non-curators before they arrive), but the
   * page's contract is that it "must behave correctly wherever it is mounted, not
   * only behind that guard".
   */
  it('is offered no board switch and no turn-off control either', async () => {
    project = { ...withDelivery({ releases: true, preset: 'RELEASES' }), myRole: 'MEMBER' }
    renderPage()

    await screen.findByText(/Story-point estimation is off/i)
    expect(screen.queryByRole('radio', { name: /scrum/i })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Turn off releases' })).toBeNull()
    // …and it does not pass by rendering nothing: the member is still TOLD how
    // this project delivers, in statements with no verb and no offer.
    expect(screen.getByText(/This project plans as one ranked list\./i)).toBeInTheDocument()
    expect(screen.getByText(/This project groups issues into versions/i)).toBeInTheDocument()
  })

  it('still sees a curator’s controls once the role qualifies', async () => {
    // The mirror image, so the test above cannot pass by rendering nothing at all.
    workspaceRole = 'ADMIN'
    renderPage()

    expect(await screen.findByRole('button', { name: /turn on releases/i })).toBeInTheDocument()
    expect(screen.queryByText(/Releases are off for this project\./i)).not.toBeInTheDocument()
  })
})
