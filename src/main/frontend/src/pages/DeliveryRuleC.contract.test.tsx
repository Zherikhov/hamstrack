import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { PROJECT_ADMIN_PERMISSIONS, PROJECT_CONTRIBUTOR_PERMISSIONS } from '../test/permissions'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import BacklogPage from './BacklogPage'
import ReleasesPage from './ReleasesPage'
import ProjectSettingsArea from './settings/ProjectSettingsArea'
import { CAPABILITY, type DeliveryCapability } from '../components/delivery'
import { useAuthStore } from '../auth'
import type {
  Issue, IssueType, PriorityOption, ProjectDelivery, Status, User, Version,
} from '../types'

/**
 * HD-104 (S3) — **Rule C as a property of the product, not as four buttons.**
 *
 * The other S3 tests each pin one screen. That is necessary but it is not the
 * invariant: §5.3 says *every* capability has an enabling affordance visible
 * while it is off, placed where a user would look for it. Stated per screen, the
 * rule is satisfied by four hand-written cases and broken silently by the fifth —
 * which is exactly how the production dead end happened (`releases` would have
 * had no reachable affordance at all once its rail item was hidden).
 *
 * So this file iterates `CAPABILITY` instead of naming capabilities. `SURFACE`
 * below is typed `Record<DeliveryCapability, …>`, so a new row in the capability
 * table **fails to compile** until someone says which screen owns it, and the
 * generated cases then fail at runtime unless that screen really renders the
 * affordance while the capability is off — and really withholds it from a plain
 * member.
 *
 * What it deliberately does NOT re-test: the write payload (`delivery.wire.test`
 * covers the bytes, table-driven too) and each surface's own behaviour.
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }
const TODO: Status = { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 }
const TASK: IssueType = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const PRIORITY: PriorityOption = { id: 'pr1', name: 'Medium', color: '#888', isDefault: true }

const ISSUE: Issue = {
  id: 'i1', number: 1, key: 'PR-1', title: 'ranked one',
  type: TASK, status: TODO, priority: PRIORITY,
  reporter: { id: 'u-other', displayName: 'Other' },
  childCount: 0, doneChildCount: 0, sprint: null, storyPoints: null,
  fields: [], version: 1,
  createdAt: '2026-08-10T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z',
}

const VERSION: Version = {
  id: 'v1', name: '2.4.0', released: false, archived: false,
  issueCount: 24, doneIssueCount: 18, affectsIssueCount: 0,
  createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
}

/** Everything off — each generated case only cares about its own member. */
const ALL_OFF: ProjectDelivery = {
  board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN',
}

const state = vi.hoisted(() => ({
  delivery: { board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN' } as ProjectDelivery,
  curator: true,
}))

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<Record<string, unknown>>()),
  apiGetProject: vi.fn(async () => ({
    id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
    archived: false, myRole: 'MEMBER', delivery: state.delivery,
    myPermissions: state.curator ? PROJECT_ADMIN_PERMISSIONS : PROJECT_CONTRIBUTOR_PERMISSIONS,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiGetWorkspace: vi.fn(async () => ({
    id: WS_ID, name: 'WS', slug: 'ws', myRole: 'MEMBER', myPermissions: [],
    createdAt: '2026-01-01T00:00:00Z',
  })),
  apiUpdateProject: vi.fn(),
  apiGetProjectConfig: vi.fn(async () => ({
    statuses: [TODO], transitions: [], priorities: [PRIORITY], issueTypes: [TASK], fields: [],
  })),
  apiGetBacklogView: vi.fn(async () => ({
    sprints: [],
    backlog: {
      issues: [ISSUE], truncated: false, totalAvailable: 1,
      stats: { issueCount: 1, doneIssueCount: 0, points: 0, donePoints: 0, unestimatedCount: 1 },
    },
    sectionCap: 300,
  })),
  apiListIssues: vi.fn(async () => ({ issues: [ISSUE], truncated: false, totalAvailable: 1, cap: 100 })),
  // HD-96: refreshing one section is ONE request that answers with the WHOLE
  // section — rows, header, `truncated`, `totalAvailable`, whole-section stats
  // and both caps — and carries no page size at all. It replaced a pair of calls
  // (`GET …/issues?size=sectionCap` + `GET /sprints/{id}`) whose size the server
  // silently narrowed to 100 while answering 200.
  apiGetBacklogSection: vi.fn(async () => ({
    // This project has no sprints, so the backlog is its only refreshable section.
    sprint: null,
    issues: [ISSUE], truncated: false, totalAvailable: 1,
    stats: { issueCount: 1, doneIssueCount: 0, points: 0, donePoints: 0, unestimatedCount: 1 },
    sectionCap: 300,
  })),
  apiRankIssue: vi.fn(),
  sprintsApi: {
    list: vi.fn(async () => ({
      content: [], page: 0, size: 200, totalElements: 0, totalPages: 1, hasNext: false,
    })),
    create: vi.fn(), start: vi.fn(), get: vi.fn(),
  },
  versionsApi: {
    list: vi.fn(async () => [VERSION]),
    usage: vi.fn(async () => ({ fixIssueCount: 24, affectsIssueCount: 0, unresolvedFixIssueCount: 6 })),
    create: vi.fn(), update: vi.fn(), release: vi.fn(), unrelease: vi.fn(),
    archive: vi.fn(), unarchive: vi.fn(), remove: vi.fn(),
  },
  labelsApi: { list: vi.fn(async () => []), create: vi.fn() },
  componentsApi: { list: vi.fn(async () => []), update: vi.fn() },
}))

// The settings area's other tabs are heavy and irrelevant — only the tab that
// carries the off-capability list is rendered for real.
vi.mock('./admin/AdminStatusesPage', () => ({ default: () => <div /> }))
vi.mock('./admin/AdminPrioritiesPage', () => ({ default: () => <div /> }))
vi.mock('./admin/AdminIssueTypesPage', () => ({ default: () => <div /> }))
vi.mock('./admin/AdminFieldsPage', () => ({ default: () => <div /> }))
vi.mock('./admin/AdminWorkflowsPage', () => ({ default: () => <div /> }))
vi.mock('./settings/ProjectBindingsPage', () => ({ default: () => <div /> }))
vi.mock('./settings/ProjectComponentsPage', () => ({ default: () => <div /> }))

beforeAll(() => {
  if (!('ResizeObserver' in globalThis)) {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
  if (typeof window.matchMedia !== 'function') {
    window.matchMedia = ((query: string) => ({
      matches: false, media: query, onchange: null,
      addEventListener: () => {}, removeEventListener: () => {},
      addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia
  }
})

beforeEach(() => {
  localStorage.clear()
  state.delivery = ALL_OFF
  state.curator = true
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
})

/**
 * `routePath` stays PARAMETERISED (`:wsId`/`:projectId`) — every one of these
 * pages reads its ids from `useParams` and disables its queries without them, so
 * a literal path renders a permanently-loading shell that would pass a
 * "no affordance for a member" assertion for entirely the wrong reason.
 */
function renderAt(entry: string, routePath: string, element: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          {/* The redirect target of the settings guard — a plain member lands here. */}
          <Route path="/w/:wsId/p/:projectId" element={<div>the board</div>} />
          <Route path={routePath} element={element} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const SETTINGS_ENTRY = `/w/${WS_ID}/p/${PROJECT_ID}/settings/delivery`
const SETTINGS_ROUTE = '/w/:wsId/p/:projectId/settings/*'

interface Surface {
  /** Where a user looks for this capability — the §5.3 placement rule. */
  where: string
  /** Renders that surface with the capability off; resolves once it has loaded. */
  open: () => Promise<unknown>
  /**
   * The same surface for a plain member. Some are route-gated rather than
   * control-gated, so what "loaded" means differs — the settings area redirects.
   */
  openAsMember?: () => Promise<unknown>
}

/**
 * The registry. Typed against `DeliveryCapability`, so adding a capability to
 * `CAPABILITY` without saying which screen offers to turn it on is a TYPE error,
 * and pointing it at a screen that does not offer it is a test failure.
 */
const SURFACE: Record<DeliveryCapability, Surface> = {
  iterations: {
    where: 'the Backlog, where sprint sections would be',
    open: () => {
      renderAt(`/w/${WS_ID}/p/${PROJECT_ID}/backlog`, '/w/:wsId/p/:projectId/backlog', <BacklogPage />)
      return screen.findByText(ISSUE.title)
    },
  },
  releases: {
    where: 'the Releases page, which still resolves with the capability off',
    open: () => {
      renderAt(`/w/${WS_ID}/p/${PROJECT_ID}/releases`, '/w/:wsId/p/:projectId/releases', <ReleasesPage />)
      return screen.findByText(VERSION.name)
    },
  },
  estimation: {
    where: 'Project settings → Delivery, having no page of its own to seed it from',
    open: () => {
      renderAt(SETTINGS_ENTRY, SETTINGS_ROUTE, <ProjectSettingsArea />)
      return screen.findByRole('radio', { name: /scrum/i })
    },
    // Route-gated: the whole settings area bounces a non-curator, so the
    // affordance is unreachable rather than merely unrendered.
    openAsMember: () => {
      renderAt(SETTINGS_ENTRY, SETTINGS_ROUTE, <ProjectSettingsArea />)
      return screen.findByText('the board')
    },
  },
}

describe('Rule C — the capability table and the product agree (HD-104 §5.3)', () => {
  it('has a surface for EVERY declared capability, and no orphan surfaces', () => {
    // The compile-time half is `Record<DeliveryCapability, Surface>`; this is the
    // runtime half, which also catches a surface left behind by a removed row.
    expect(Object.keys(SURFACE).sort()).toEqual(Object.keys(CAPABILITY).sort())
  })

  it('gives every capability copy that names the way back, not just a way in', () => {
    // Rule C's third clause. Asserted over the table so it cannot be true of
    // three rows and forgotten on the fourth.
    for (const [name, copy] of Object.entries(CAPABILITY)) {
      expect(copy.enable, name).toBeTruthy()
      expect(copy.offTitle, name).toBeTruthy()
      expect(copy.memberNote, name).toBeTruthy()
      // …and what a member is told while it is ON, so a settings row can be
      // rendered read-only for them instead of as a control they can only 403 on.
      expect(copy.memberNoteOn, name).toBeTruthy()
      // "…turn it/them off again in Project settings → <tab>" — the reversal is
      // named with its location, not implied.
      expect(copy.reversible, name).toMatch(/turn (it|them) off again in Project settings → \w+/i)
      // The patch is one member of `delivery`, and never the derived preset.
      expect(Object.keys(copy.patch), name).toHaveLength(1)
      expect(copy.patch, name).not.toHaveProperty('preset')
    }
  })

  describe.each(Object.keys(CAPABILITY) as DeliveryCapability[])('%s', capability => {
    const copy = CAPABILITY[capability]
    // Undefined only for a capability nobody gave a home to — reported as a
    // failing assertion inside the case rather than as a collection-time crash,
    // so the message names the capability that has no way in.
    const surface = SURFACE[capability] as Surface | undefined

    it(`offers a curator the affordance while it is off — in ${surface?.where ?? 'NO SURFACE — Rule C violated'}`, async () => {
      expect(surface, `no surface declares "${capability}" — Rule C requires one`).toBeDefined()
      await surface!.open()

      // `All`, because a surface may legitimately offer it twice (the Backlog
      // puts one in the header and one in the slot the sprint sections occupy) —
      // what Rule C requires is *at least one*.
      expect((await screen.findAllByRole('button', { name: new RegExp(copy.enable, 'i') })).length)
        .toBeGreaterThan(0)
      // The reversibility sentence is COPY. `getByText` never matches a `title`
      // attribute, so this also pins that it was not demoted to a tooltip.
      expect(screen.getAllByText(copy.reversible).length).toBeGreaterThan(0)
    })

    it('offers a plain member no enabling control at all', async () => {
      state.curator = false
      expect(surface, `no surface declares "${capability}" — Rule C requires one`).toBeDefined()
      await (surface!.openAsMember ?? surface!.open)()

      expect(screen.queryAllByRole('button', { name: new RegExp(copy.enable, 'i') })).toHaveLength(0)
      expect(screen.queryAllByText(copy.reversible)).toHaveLength(0)
    })
  })
})
