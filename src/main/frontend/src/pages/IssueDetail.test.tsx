import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { PROJECT_ADMIN_PERMISSIONS, WORKSPACE_ADMIN_PERMISSIONS } from '../test/permissions'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import IssueDetail from './IssueDetail'
import { apiUpdateIssue, sprintsApi, versionsApi } from '../api'
import { useAuthStore } from '../auth'
import { INK_MIN, SURFACE, contrastRatio } from '../colour'
import type {
  Attachment, Comment, Issue, IssueType, PriorityOption, ProjectDelivery, ProjectField,
  Sprint, Status, VersionRef,
} from '../types'

// HD-69: refinements to the merged Activity feed (HD-64).
//  1. the filter defaults to Comments — history entries stay hidden until the
//     user switches to All/History;
//  2. comment rows and history rows render their timestamp through the SAME
//     helper, so both show a date *and* a time (the old history rows showed a
//     bare date and drifted from the comment rows).
// These are behavioural assertions only — the gutter/truncation work is pixels.

const STATUS_TODO: Status = { id: 's1', name: 'To Do', color: '#999', category: 'TODO', position: 0 }
const STATUS_DOING: Status = { id: 's2', name: 'In Progress', color: '#09f', category: 'IN_PROGRESS', position: 1 }
const TYPE_TASK: IssueType = { id: 't1', name: 'Task', color: '#555', position: 0, hierarchyLevel: 1 }
const PRIORITY: PriorityOption = { id: 'pr1', name: 'Medium', color: '#888', isDefault: true }

const COMMENT_BODY = 'Looks good to me'
const AUTHOR = 'Ada Lovelace'
const HISTORIAN = 'Grace Hopper'

const ISSUE: Issue = {
  id: 'i1', number: 7, key: 'PR-7', title: 'Something to fix',
  description: 'A description',
  type: TYPE_TASK, status: STATUS_TODO, priority: PRIORITY,
  reporter: { id: 'u1', displayName: AUTHOR },
  childCount: 0, doneChildCount: 0,
  fields: [], version: 1,
  createdAt: '2026-08-10T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z',
}

// HD-68 fixtures: two project custom fields; the issue below fills only the
// first, so the "+ Add field" affordance and a filled value both render.
const FIELD_TEAM: ProjectField = {
  id: 'f1', key: 'team', name: 'Team', type: 'SELECT',
  config: { options: [{ id: 'o1', label: 'Platform' }, { id: 'o2', label: 'Growth' }] },
  required: false, showOnCreate: false,
}
const FIELD_NOTES: ProjectField = {
  id: 'f2', key: 'release_notes', name: 'Release notes', type: 'TEXT',
  required: false, showOnCreate: false,
}
const PROJECT_FIELDS = [FIELD_TEAM, FIELD_NOTES]

const ISSUE_WITH_FIELDS: Issue = { ...ISSUE, fields: [{ fieldId: FIELD_TEAM.id, value: 'o1' }] }

/** What the mocked `apiGetIssue` resolves with — swappable per test. */
let issueResponse: Issue = ISSUE

// HD-22: the project's OPEN sprints (ACTIVE + FUTURE), i.e. everything the Sprint
// picker may legally offer. Swappable per test, like `issueResponse`.
const SPRINT_ACTIVE: Sprint = {
  id: 'sp-active', name: 'Sprint 7', state: 'ACTIVE', sequence: 7,
  goal: null, startAt: '2026-08-01T00:00:00Z', endAt: '2026-08-15T00:00:00Z',
  completedAt: null, daysRemaining: 3,
  issueCount: 1, doneIssueCount: 0, points: 0, donePoints: 0, unestimatedCount: 1,
  createdAt: '2026-07-30T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
}
const SPRINT_FUTURE: Sprint = {
  ...SPRINT_ACTIVE, id: 'sp-future', name: 'Sprint 8', state: 'FUTURE', sequence: 8,
  startAt: null, endAt: null, daysRemaining: null, issueCount: 0, unestimatedCount: 0,
}
let openSprints: Sprint[] = []

// HD-102: what the project DECLARES it does. Every path-specific cell below asks
// this and nothing else — never "does this project have sprints/versions yet?".
// Swappable per test; the default is a project that does all three, so the cells
// render in their editable form.
const ALL_ON: ProjectDelivery = { board: 'SCRUM', releases: true, estimation: true, preset: 'CUSTOM' }
let delivery: ProjectDelivery = ALL_ON
/** The project's `myPermissions` — every inline editor's gate (HD-123 S5). */
let projectPermissions: string[] = PROJECT_ADMIN_PERMISSIONS

// HD-123 S5: the two LISTS whose rows are gated one by one, because ownership is
// a property of the row and not of the page. Both default to what the rest of
// this file has always assumed (no files; one comment, by the issue's reporter),
// and the own-gate tests swap in a pair of rows with different owners.
const MY_ID = 'u1'      // ISSUE.reporter.id, and the default comment's author
const OTHER_ID = 'u2'
const DEFAULT_COMMENTS: Comment[] = [{
  id: 'c1', authorId: MY_ID, authorName: AUTHOR, body: COMMENT_BODY,
  createdAt: '2026-08-11T13:45:00Z', updatedAt: '2026-08-11T13:45:00Z',
}]
let comments: Comment[] = DEFAULT_COMMENTS
let attachments: Attachment[] = []

vi.mock('../api', () => ({
  ApiResponseError: class ApiResponseError extends Error { status = 0 },
  // HD-191: the Files section reads the workspace's storage summary. Quota off
  // here, so no storage chrome is drawn and these cases stay about their own
  // subject — the branch itself is pinned in `IssueDetail.storage.test.tsx`.
  // The discriminator is stubbed with its real value on purpose: left
  // `undefined`, `err.errorType === STORAGE_QUOTA_EXCEEDED` would be true for
  // every error that carries no discriminator at all.
  STORAGE_QUOTA_EXCEEDED: 'STORAGE_QUOTA_EXCEEDED',
  apiGetWorkspaceStorage: vi.fn(async () => ({
    quotaEnabled: false, quotaBytes: null, usedBytes: 0, availableBytes: null,
    attachmentCount: 0, percentUsed: null, warnAtPercent: 80,
    maxFileBytes: 25 * 1024 * 1024, asOf: '2026-09-03T10:00:00Z',
  })),
  apiGetWorkspaceStorageByProject: vi.fn(),
  apiGetIssue: vi.fn(async () => issueResponse),
  apiUpdateIssue: vi.fn(),
  apiDeleteIssue: vi.fn(),
  apiListIssues: vi.fn(async () => ({ issues: [], truncated: false })),
  apiListComments: vi.fn(async () => ({ content: comments })),
  apiCreateComment: vi.fn(),
  apiDeleteComment: vi.fn(),
  apiListAttachments: vi.fn(async () => attachments),
  apiUploadAttachment: vi.fn(),
  apiDownloadAttachment: vi.fn(),
  apiDeleteAttachment: vi.fn(),
  apiGetIssueHistory: vi.fn(async () => ({
    content: [{
      id: 'h1', field: 'status', oldValue: 'To Do', newValue: 'In Progress',
      changedById: 'u2', changedByName: HISTORIAN,
      createdAt: '2026-08-12T08:05:00Z',
    }],
  })),
  apiListWorkspaceMembers: vi.fn(async () => []),
  apiGetIssueChildren: vi.fn(async () => []),
  // HD-30: the details grid's Labels cell picks from the workspace's labels.
  labelsApi: { list: vi.fn(async () => []), create: vi.fn() },
  // HD-31: …and its Component cell from the project's components.
  componentsApi: { list: vi.fn(async () => []) },
  // HD-32: …and its Fix/Affects versions cells from the project's versions.
  versionsApi: { list: vi.fn(async () => []) },
  // HD-102: the delivery capabilities ride the ProjectResponse the nav rail
  // already caches — `useProjectDelivery` reads that same entry.
  apiGetProject: vi.fn(async () => ({
    id: 'p1', workspaceId: 'w1', name: 'Proj', key: 'PR',
    archived: false, myRole: 'MANAGER', myPermissions: projectPermissions, delivery,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  // Only fetched when a caller asks for the curator role (this surface doesn't),
  // but a mocked module must still expose every imported binding.
  apiGetWorkspace: vi.fn(async () => ({
    id: 'w1', name: 'WS', slug: 'ws', myRole: 'OWNER', myPermissions: WORKSPACE_ADMIN_PERMISSIONS,
    createdAt: '2026-01-01T00:00:00Z',
  })),
  // HD-22: …and its Sprint cell offers the project's open sprints.
  sprintsApi: {
    list: vi.fn(async () => ({
      content: openSprints, page: 0, size: 200,
      totalElements: openSprints.length, totalPages: 1, hasNext: false,
    })),
  },
}))

beforeAll(() => {
  // jsdom ships no ResizeObserver; IssueDetail observes the panel + scroll area.
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

/** `enableExpand` is omitted on purpose: the key would then render a <Link>,
 *  which needs a router the rest of this component doesn't. */
function renderDetail(fields: ProjectField[] = [], types: IssueType[] = [TYPE_TASK]) {
  render(
    <IssueDetail
      wsId="w1"
      projectId="p1"
      issueNumber={7}
      issueTypes={types}
      statuses={[STATUS_TODO, STATUS_DOING]}
      transitions={[]}
      priorities={[PRIORITY]}
      fields={fields}
    />,
    { wrapper },
  )
}

afterEach(() => {
  issueResponse = ISSUE
  openSprints = []
  delivery = ALL_ON
  projectPermissions = PROJECT_ADMIN_PERMISSIONS
  comments = DEFAULT_COMMENTS
  attachments = []
  vi.mocked(apiUpdateIssue).mockReset()
})

/** The activity filter is a segmented control of buttons labelled "<filter> <count>". */
function filterButton(name: 'all' | 'comments' | 'history') {
  return screen.getByRole('button', { name: new RegExp(`^${name}\\b`, 'i') })
}

const HAS_TIME = /\d{1,2}:\d{2}/

describe('IssueDetail activity feed (HD-69)', () => {
  it('defaults the filter to Comments and hides history until All is picked', async () => {
    renderDetail()

    // Comments are shown by default…
    expect(await screen.findByText(COMMENT_BODY)).toBeInTheDocument()
    expect(filterButton('comments')).toHaveStyle({ fontWeight: '600' })

    // …and the history entry is not rendered at all under that filter.
    expect(screen.queryByText(HISTORIAN)).not.toBeInTheDocument()
    expect(screen.queryByText(/changed/i)).not.toBeInTheDocument()

    await userEvent.click(filterButton('all'))

    await waitFor(() => expect(screen.getByText(HISTORIAN)).toBeInTheDocument())
    expect(screen.getByText(/changed/i)).toBeInTheDocument()
    expect(screen.getByText(COMMENT_BODY)).toBeInTheDocument()   // still there
  })

  it('shows history only (no comments) under the History filter', async () => {
    renderDetail()
    await screen.findByText(COMMENT_BODY)

    await userEvent.click(filterButton('history'))

    await waitFor(() => expect(screen.queryByText(COMMENT_BODY)).not.toBeInTheDocument())
    expect(screen.getByText(HISTORIAN)).toBeInTheDocument()
  })

  it('renders comment AND history timestamps with a time component, not a bare date', async () => {
    // Sanity: the regex really does discriminate — a bare locale date has no
    // "hh:mm" in it, whatever the runner's locale is.
    expect(new Date('2026-08-12T08:05:00Z').toLocaleDateString()).not.toMatch(HAS_TIME)

    renderDetail()

    const commentRow = (await screen.findByText(COMMENT_BODY)).closest('.group')
    expect(commentRow?.textContent).toMatch(HAS_TIME)

    await userEvent.click(filterButton('all'))

    const historyRow = await waitFor(() => {
      const row = screen.getByText(HISTORIAN).closest('div.flex.gap-2\\.5')
      expect(row).toBeTruthy()
      return row!
    })
    // The regression this guards: history rows used to print a bare date.
    expect(historyRow.textContent).toMatch(HAS_TIME)
  })
})

// HD-68: the custom-fields ("Fields") block moved out of the description flow
// and up into the details area, directly under the built-in metadata grid.
// Guarded here as DOM order, not pixels.
describe('IssueDetail custom fields placement (HD-68)', () => {
  /** The section headings are <span>s; the jump nav renders the same labels as
   *  <button>s, so filter by tag to always get the heading. */
  function heading(label: string) {
    const el = screen.getAllByText(label).find(e => e.tagName === 'SPAN')
    expect(el, `no <span> section heading "${label}"`).toBeTruthy()
    return el!
  }

  it('renders the custom field label and value', async () => {
    issueResponse = ISSUE_WITH_FIELDS
    renderDetail(PROJECT_FIELDS)

    expect(await screen.findByText('Fields')).toBeInTheDocument()
    expect(screen.getByText(FIELD_TEAM.name)).toBeInTheDocument()
    // SELECT values render as the option label, not the stored option id.
    expect(screen.getByText('Platform')).toBeInTheDocument()
    expect(screen.queryByText('o1')).not.toBeInTheDocument()
    // The unfilled field is hidden behind "+ Add field" rather than shown empty.
    expect(screen.queryByText(FIELD_NOTES.name)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /add field/i })).toBeInTheDocument()
  })

  it('places the Fields block BEFORE the Description section in the DOM', async () => {
    issueResponse = ISSUE_WITH_FIELDS
    renderDetail(PROJECT_FIELDS)

    await screen.findByText('Fields')
    const fieldsHeading = heading('Fields')
    const descHeading = heading('Description')

    // Under the old layout Description came first — this is the regression.
    expect(fieldsHeading.compareDocumentPosition(descHeading) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy()

    // …and Fields still sits inside the details area, i.e. after the built-in
    // metadata grid (Status is the first built-in label).
    const statusLabel = screen.getByText('Status')
    expect(statusLabel.compareDocumentPosition(fieldsHeading) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy()
  })

  it('keeps the empty state when the project has fields but the issue has no values', async () => {
    renderDetail(PROJECT_FIELDS)   // issueResponse = ISSUE, whose fields are []

    expect(await screen.findByText('Fields')).toBeInTheDocument()
    expect(screen.getByText('No field values set.')).toBeInTheDocument()
    expect(heading('Fields').compareDocumentPosition(heading('Description')) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy()
  })
})

// HD-22 + the 0.13.0 review: the Sprint cell.
//
// A COMPLETED sprint's membership is a delivered fact — the server now refuses to
// take an issue OUT of one (422) as firmly as it refuses to put one IN. The picker
// must therefore stop OFFERING the move rather than let the user discover it as a
// 422 that silently reverts their edit.
describe('IssueDetail sprint cell (HD-22 / 0.13.0 freeze)', () => {
  /**
   * The design system's `Select` is a custom listbox, not a native `<select>`: the
   * trigger is a button carrying the aria-label, and the options only exist in the
   * DOM while the popup is open.
   */
  function sprintTrigger() {
    return screen.queryByRole('button', { name: 'Sprint' })
  }

  it('hides the cell when the project does not plan iterations and the issue has none', async () => {
    // HD-102 §6, `I ∨ value`: off and empty ⇒ absent. Note it is the CAPABILITY
    // that decides, not the (empty) sprint list.
    delivery = { ...ALL_ON, board: 'KANBAN' }
    renderDetail()
    await screen.findByText('Something to fix')
    expect(sprintTrigger()).toBeNull()
  })

  it('renders the cell on an iterations project that has no sprints YET', async () => {
    // The removed presence heuristic (`sprintOptions.length > 0`) hid the cell
    // here — the same class of check that made the Backlog's first sprint
    // uncreatable in production. A declared capability answers on its own.
    openSprints = []
    renderDetail()

    await screen.findByText('Something to fix')
    const trigger = await waitFor(() => {
      const el = sprintTrigger()
      expect(el).not.toBeNull()
      return el!
    })
    expect(trigger).not.toBeDisabled()
  })

  it('offers the open sprints and clears with `clearSprint` — never both keys', async () => {
    openSprints = [SPRINT_ACTIVE, SPRINT_FUTURE]
    issueResponse = {
      ...ISSUE,
      sprint: { id: SPRINT_ACTIVE.id, name: SPRINT_ACTIVE.name, state: 'ACTIVE' },
    }
    vi.mocked(apiUpdateIssue).mockResolvedValue({ ...ISSUE, version: 2 })
    renderDetail()

    await screen.findByText('Something to fix')
    const trigger = await waitFor(() => {
      const el = sprintTrigger()
      expect(el).not.toBeNull()
      return el!
    })
    expect(trigger).not.toBeDisabled()
    expect(trigger).toHaveTextContent('Sprint 7 (active)')

    // Both OPEN sprints are on offer, the running one flagged as such.
    await userEvent.click(trigger)
    expect(await screen.findByRole('option', { name: 'Sprint 8' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Sprint 7 (active)' })).toBeInTheDocument()

    // "No sprint (backlog)" is the clear — and it must go out as `clearSprint`
    // ALONE: `sprintId` + `clearSprint` is a 400 (and now a type error too).
    await userEvent.click(screen.getByRole('option', { name: 'No sprint (backlog)' }))
    await waitFor(() => expect(apiUpdateIssue).toHaveBeenCalled())
    const payload = vi.mocked(apiUpdateIssue).mock.calls[0][3] as Record<string, unknown>
    expect(payload.clearSprint).toBe(true)
    expect(payload).not.toHaveProperty('sprintId')

    // …and moving it to another open sprint sends the id alone.
    await userEvent.click(sprintTrigger()!)
    await userEvent.click(await screen.findByRole('option', { name: 'Sprint 8' }))
    await waitFor(() => expect(apiUpdateIssue).toHaveBeenCalledTimes(2))
    const moved = vi.mocked(apiUpdateIssue).mock.calls[1][3] as Record<string, unknown>
    expect(moved.sprintId).toBe(SPRINT_FUTURE.id)
    expect(moved).not.toHaveProperty('clearSprint')
  })

  it('goes read-only, with the reason, once the issue’s sprint has completed', async () => {
    // A completed sprint has left the OPEN list; the issue's own sprint keeps it
    // displayable, exactly like an archived component.
    openSprints = [SPRINT_FUTURE]
    issueResponse = {
      ...ISSUE,
      sprint: { id: 'sp-closed', name: 'Sprint 6', state: 'COMPLETED' },
    }
    renderDetail()

    await screen.findByText('Something to fix')
    const trigger = await waitFor(() => {
      const el = sprintTrigger()
      expect(el).not.toBeNull()
      return el!
    })
    // Read-only rather than absent: the issue must still show what it was delivered in.
    expect(trigger).toBeDisabled()
    expect(trigger).toHaveTextContent('Sprint 6')
    // …and the control explains itself instead of looking broken.
    expect(screen.getByText(/no longer be moved/i)).toBeInTheDocument()

    // The picker cannot even be opened, so no move can be attempted from here.
    await userEvent.click(trigger)
    expect(screen.queryByRole('option', { name: 'Sprint 8' })).toBeNull()
    expect(apiUpdateIssue).not.toHaveBeenCalled()
  })
})

// HD-102 Rule B (§5.2): CONTROLS are gated by a delivery capability, VALUES never
// are. Turning a capability off must be provably non-destructive *and legible* —
// an issue that belongs to a sprint in a project switched back to Kanban still
// SHOWS that sprint, read-only, with the reason attached. Risk 4 in the proposal
// calls this the easiest rule to skip, so each `∨ value` row of §6 is guarded.
describe('IssueDetail delivery capabilities — Rule B (HD-102)', () => {
  const V_240: VersionRef = { id: 'v1', name: '2.4.0', released: false, archived: false }
  const V_231: VersionRef = { id: 'v2', name: '2.3.1', released: true, archived: false }

  it('keeps a sprint visible, read-only, when the project stopped planning iterations', async () => {
    delivery = { ...ALL_ON, board: 'KANBAN' }
    issueResponse = {
      ...ISSUE,
      sprint: { id: SPRINT_ACTIVE.id, name: SPRINT_ACTIVE.name, state: 'ACTIVE' },
    }
    renderDetail()

    await screen.findByText('Something to fix')
    // The value is still on screen…
    expect(await screen.findByText('Sprint 7')).toBeInTheDocument()
    // …the reason is stated, not merely hover-only…
    expect(screen.getByText(/planning off/i)).toBeInTheDocument()
    // …and the control is gone, so nothing can be changed from here.
    expect(screen.queryByRole('button', { name: 'Sprint' })).toBeNull()
    expect(apiUpdateIssue).not.toHaveBeenCalled()
  })

  it('hides the story-points input when estimation is off, but keeps an existing estimate', async () => {
    delivery = { ...ALL_ON, estimation: false }
    issueResponse = { ...ISSUE, storyPoints: 5 }
    renderDetail()

    await screen.findByText('Something to fix')
    expect(await screen.findByText('Story points')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText(/estimation off/i)).toBeInTheDocument()
    expect(screen.queryByLabelText('Story points')).toBeNull()   // the input itself
  })

  it('drops the story-points cell entirely when estimation is off and the issue has none', async () => {
    delivery = { ...ALL_ON, estimation: false }
    renderDetail()

    await screen.findByText('Something to fix')
    expect(screen.queryByText('Story points')).toBeNull()
  })

  it('keeps fix versions visible, read-only, when releases are off', async () => {
    delivery = { ...ALL_ON, releases: false }
    issueResponse = { ...ISSUE, fixVersions: [V_240] }
    renderDetail()

    await screen.findByText('Something to fix')
    expect(await screen.findByText('Fix versions')).toBeInTheDocument()
    expect(screen.getByText('2.4.0')).toBeInTheDocument()
    expect(screen.getByText(/releases off/i)).toBeInTheDocument()
    // The role the issue carries NO value in is absent altogether.
    expect(screen.queryByText('Affects versions')).toBeNull()

    // Clicking the value opens no editor — it is a value, not a control.
    await userEvent.click(screen.getByText('2.4.0'))
    expect(screen.queryByRole('button', { name: /save/i })).toBeNull()
    expect(apiUpdateIssue).not.toHaveBeenCalled()
  })

  it('offers the version cells on a releases project that has curated none yet', async () => {
    // Again the anti-heuristic guard: `versionOptions.length > 0` used to decide
    // this, so a brand-new releases project had nowhere to record a fix version.
    renderDetail()

    await screen.findByText('Something to fix')
    expect(await screen.findByText('Add fix versions…')).toBeInTheDocument()
  })

  it('applies the same rule to AFFECTS versions — the other half of the `R` row', async () => {
    // The two version roles share one component and one gate, so a regression in
    // either direction would show up in exactly one of them.
    delivery = { ...ALL_ON, releases: false }
    issueResponse = { ...ISSUE, affectsVersions: [V_231] }
    renderDetail()

    await screen.findByText('Something to fix')
    expect(await screen.findByText('Affects versions')).toBeInTheDocument()
    expect(screen.getByText('2.3.1')).toBeInTheDocument()
    expect(screen.getByText(/releases off/i)).toBeInTheDocument()
    // …and the empty role stays hidden, so "off + no value ⇒ absent" holds per
    // ROLE, not per project.
    expect(screen.queryByText('Fix versions')).toBeNull()

    await userEvent.click(screen.getByText('2.3.1'))
    expect(screen.queryByRole('button', { name: /save/i })).toBeNull()
    expect(apiUpdateIssue).not.toHaveBeenCalled()
  })

  it('keeps a COMPLETED sprint readable when iterations are off — two reasons, one value', async () => {
    // Both read-only reasons apply at once (the capability is off AND the sprint
    // has completed). The value must survive the overlap: this is the state an
    // issue delivered by a team that has since switched to Kanban sits in
    // forever.
    delivery = { ...ALL_ON, board: 'KANBAN' }
    issueResponse = {
      ...ISSUE, sprint: { id: 'sp-done', name: 'Sprint 6', state: 'COMPLETED' },
    }
    renderDetail()

    await screen.findByText('Something to fix')
    expect(await screen.findByText('Sprint 6')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sprint' })).toBeNull()
    expect(screen.queryByRole('combobox', { name: 'Sprint' })).toBeNull()
  })
})

/**
 * HD-102 §12 — the eager fetches this slice DELETED (`useOpenSprints` and
 * `useProjectVersions`, which IssueDetail ran unconditionally on every open) are
 * gone because the capability answers the question the data used to. Their cost
 * was real: two extra project-scoped requests per issue opened, on a surface the
 * board opens on every card click.
 *
 * These assertions are the guard rail — a refactor that reintroduces "fetch it
 * so we can decide whether to show it" fails here rather than quietly doubling
 * the request count again.
 */
describe('IssueDetail spends no request to decide what to show (HD-102)', () => {
  beforeEach(() => {
    vi.mocked(sprintsApi.list).mockClear()
    vi.mocked(versionsApi.list).mockClear()
  })

  it('fetches neither sprints nor versions on a project that does neither', async () => {
    delivery = { board: 'KANBAN', releases: false, estimation: false, preset: 'KANBAN' }
    renderDetail()

    await screen.findByText('Something to fix')
    await waitFor(() => expect(screen.queryByText('Sprint')).toBeNull())
    expect(sprintsApi.list).not.toHaveBeenCalled()
    expect(versionsApi.list).not.toHaveBeenCalled()
  })

  it('fetches no versions on a releases project until a version cell is opened', async () => {
    // The picker fetches its own options; the page no longer prefetches them to
    // decide whether the cell exists.
    renderDetail()

    await screen.findByText('Something to fix')
    expect(await screen.findByText('Add fix versions…')).toBeInTheDocument()
    expect(versionsApi.list).not.toHaveBeenCalled()

    await userEvent.click(screen.getByText('Add fix versions…'))
    await waitFor(() => expect(versionsApi.list).toHaveBeenCalled())
  })

  it('fetches no sprints for an issue whose sprint is shown read-only', async () => {
    // Rule B renders the badge off the issue's OWN `sprint` ref — there is
    // nothing to choose from, so there is nothing to fetch.
    delivery = { ...ALL_ON, board: 'KANBAN' }
    issueResponse = {
      ...ISSUE, sprint: { id: SPRINT_ACTIVE.id, name: SPRINT_ACTIVE.name, state: 'ACTIVE' },
    }
    renderDetail()

    await screen.findByText('Something to fix')
    expect(await screen.findByText('Sprint 7')).toBeInTheDocument()
    expect(sprintsApi.list).not.toHaveBeenCalled()
  })
})

/**
 * HD-123 S5 §14.3 — the issue surface is where the catalog's field-group split
 * earns its keep, and where the **ownership modifier** is actually felt.
 *
 * `:own` is not a prefix of the unrestricted key (`"issue.edit:own"` ≠
 * `"issue.edit"`), and the failure direction that matters is *widening*: a
 * helper that read the own-only grant as unrestricted would hand every reporter
 * an "edit anyone's issue" surface. So each own-gated control is asserted from
 * both sides of the same grant — the actor who owns the object, and the one who
 * does not — with nothing else changed between the two.
 */
describe('IssueDetail permission gates (HD-123 S5)', () => {
  const REPORTER_ID = 'u1'   // ISSUE.reporter.id

  /** Sign in as somebody — `null` for "not the reporter of this issue". */
  function signIn(id: string) {
    useAuthStore.setState({
      user: { id, email: `${id}@example.com`, displayName: 'Someone' },
      accessToken: 'test-token',
      initialized: true,
    })
  }

  afterEach(() => {
    useAuthStore.setState({ user: null, accessToken: null, initialized: true })
  })

  it('withdraws the comment composer without `comment.create`, keeping the comments', async () => {
    projectPermissions = PROJECT_ADMIN_PERMISSIONS.filter(p => p !== 'comment.create')
    renderDetail()

    await screen.findByText('Something to fix')
    expect(screen.queryByRole('button', { name: 'Post' })).not.toBeInTheDocument()
    expect(screen.queryByPlaceholderText(/Add a comment/)).not.toBeInTheDocument()
    // Rule B: a VALUE is never withdrawn — the discussion is still readable.
    expect(await screen.findByText(COMMENT_BODY)).toBeInTheDocument()
  })

  it('disables "Attach file" without `attachment.create`, rather than removing it', async () => {
    projectPermissions = PROJECT_ADMIN_PERMISSIONS.filter(p => p !== 'attachment.create')
    renderDetail()

    await screen.findByText('Something to fix')
    const attach = screen.getByRole('button', { name: /Attach file/ })
    expect(attach).toBeDisabled()
    expect(attach).toHaveAttribute(
      'title', 'You don’t have permission to attach files to this issue',
    )
  })

  it('gives an own-only `issue.delete` grant to the reporter and to nobody else', async () => {
    // The whole trap in one pair of assertions: the SAME grant string, and the
    // answer differs only by who is looking. A `startsWith` test would have
    // opened the menu in both.
    projectPermissions = ['issue.delete:own']

    signIn('u-somebody-else')
    const other = render(
      <IssueDetail wsId="w1" projectId="p1" issueNumber={7} issueTypes={[TYPE_TASK]}
                   statuses={[STATUS_TODO]} transitions={[]} priorities={[PRIORITY]} fields={[]} />,
      { wrapper },
    )
    await screen.findByText('Something to fix')
    expect(screen.queryByRole('button', { name: 'More actions' })).not.toBeInTheDocument()
    other.unmount()

    signIn(REPORTER_ID)
    renderDetail()
    await screen.findByText('Something to fix')
    expect(await screen.findByRole('button', { name: 'More actions' })).toBeInTheDocument()
  })

  it('does not treat an own-only `issue.edit` grant as permission to edit anyone’s issue', async () => {
    projectPermissions = ['issue.edit:own']
    signIn('u-somebody-else')
    renderDetail()

    const title = await screen.findByText('Something to fix')
    await userEvent.click(title)
    // No inline editor opened — the read view is still a heading, not an input.
    expect(screen.queryByDisplayValue('Something to fix')).not.toBeInTheDocument()
    expect(apiUpdateIssue).not.toHaveBeenCalled()
  })

  it('honours the same own-only grant for the reporter', async () => {
    projectPermissions = ['issue.edit:own']
    signIn(REPORTER_ID)
    renderDetail()

    await userEvent.click(await screen.findByText('Something to fix'))
    expect(await screen.findByDisplayValue('Something to fix')).toBeInTheDocument()
  })

  // ── the per-ROW half of the same modifier ─────────────────────────────────
  // Files and comments are the only surfaces where ownership varies *within one
  // render*: two rows, same viewer, same grant, different owner. A gate written
  // once per page (`canDelete` hoisted out of the map) or widened to a bare
  // `startsWith` passes every assertion above and still hands a contributor a
  // delete control on their colleague's file. So both lists are asserted with a
  // mixed pair in a single render.

  const MINE: Attachment = {
    id: 'a-mine', filename: 'mine.txt', sizeBytes: 12, contentType: 'text/plain',
    uploadedById: REPORTER_ID, uploadedByName: AUTHOR, createdAt: '2026-08-11T10:00:00Z',
  }
  const THEIRS: Attachment = {
    ...MINE, id: 'a-theirs', filename: 'theirs.txt',
    uploadedById: OTHER_ID, uploadedByName: HISTORIAN,
  }

  /** The action buttons of the file row whose filename is {@code name}. */
  function fileRowButtons(name: string) {
    const row = screen.getByText(name).closest('div.group')
    expect(row).not.toBeNull()
    // The filename itself is a button (it downloads), so a row with no delete
    // affordance has exactly one.
    return [...row!.querySelectorAll('button')]
  }

  it('offers `attachment.delete:own` on the uploader’s own file and on no other', async () => {
    projectPermissions = ['attachment.delete:own']
    attachments = [MINE, THEIRS]
    signIn(REPORTER_ID)
    renderDetail()

    await screen.findByText('mine.txt')
    expect(fileRowButtons('mine.txt')).toHaveLength(2)
    expect(fileRowButtons('theirs.txt')).toHaveLength(1)
  })

  it('offers an UNRESTRICTED `attachment.delete` on both — the other direction', async () => {
    // Without this the pair above would also pass on a build that read every
    // grant as own-only: a narrowing, and just as wrong.
    projectPermissions = ['attachment.delete']
    attachments = [MINE, THEIRS]
    signIn(REPORTER_ID)
    renderDetail()

    await screen.findByText('mine.txt')
    expect(fileRowButtons('mine.txt')).toHaveLength(2)
    expect(fileRowButtons('theirs.txt')).toHaveLength(2)
  })

  it('offers `comment.delete:own` on the author’s own comment and on no other', async () => {
    projectPermissions = ['comment.delete:own']
    comments = [
      { ...DEFAULT_COMMENTS[0], id: 'c-mine', body: 'My own words' },
      {
        id: 'c-theirs', authorId: OTHER_ID, authorName: HISTORIAN, body: 'Somebody else’s words',
        createdAt: '2026-08-11T14:00:00Z', updatedAt: '2026-08-11T14:00:00Z',
      },
    ]
    signIn(MY_ID)
    renderDetail()

    await screen.findByText('My own words')
    // One row is mine, one is not, and exactly one delete control is rendered.
    expect(screen.getAllByRole('button', { name: 'Delete comment' })).toHaveLength(1)
  })

  it('offers a moderator the delete control on every comment', async () => {
    projectPermissions = ['comment.delete']
    comments = [
      { ...DEFAULT_COMMENTS[0], id: 'c-mine', body: 'My own words' },
      {
        id: 'c-theirs', authorId: OTHER_ID, authorName: HISTORIAN, body: 'Somebody else’s words',
        createdAt: '2026-08-11T14:00:00Z', updatedAt: '2026-08-11T14:00:00Z',
      },
    ]
    signIn(MY_ID)
    renderDetail()

    await screen.findByText('My own words')
    expect(screen.getAllByRole('button', { name: 'Delete comment' })).toHaveLength(2)
  })

  it('offers no delete control at all without the grant, on either list', async () => {
    projectPermissions = []
    attachments = [MINE, THEIRS]
    comments = DEFAULT_COMMENTS
    signIn(REPORTER_ID)
    renderDetail()

    await screen.findByText('mine.txt')
    expect(fileRowButtons('mine.txt')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Delete comment' })).not.toBeInTheDocument()
  })
})

// ── The parent breadcrumb's ink (HD-176 follow-up) ────────────────────────────
// The breadcrumb paints the PARENT ISSUE TYPE's stored hue as text. That hue is
// an identity a human picked, not ink, so it is derived against the surface the
// row lands on before it reaches a `color`.
//
// This is asserted from the DOM and phrased about the *entity* — a parent whose
// type carries an unreadable hue — rather than about the variable that holds the
// value on the way. The escape it pins existed precisely because that variable
// was local: `colour.test.ts` scans source text for member chains, so a hue
// copied into a bare local is outside what it can see, and renaming or
// re-inlining the local must not decide whether this guarantee is checked.
describe('IssueDetail parent breadcrumb (HD-176)', () => {
  /** jsdom serialises an inline colour as `rgb(r, g, b)`; bring it back to hex. */
  function toHexFromCss(value: string): string {
    const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(value)
    return m
      ? `#${[1, 2, 3].map(i => Number(m[i]).toString(16).padStart(2, '0')).join('').toUpperCase()}`
      : value.trim().toUpperCase()
  }

  const PARENT_KEY = 'PR-1'
  /** The seeded Medium yellow — 1.92:1 as text, which is what shipped here. */
  const TYPE_EPIC_UNREADABLE: IssueType = {
    id: 't9', name: 'Epic', color: '#EAB308', position: 1, hierarchyLevel: 2,
  }
  /** A hue that already reads: the tuned-workspace half of the guarantee. */
  const TYPE_EPIC_READABLE: IssueType = { ...TYPE_EPIC_UNREADABLE, color: '#16202E' }

  const childOf = (parentType: IssueType): Issue => ({
    ...ISSUE,
    parentId: 'i0', parentKey: PARENT_KEY, parentTitle: 'The epic', parentTypeId: parentType.id,
  })

  /** The parent key, and the surface it is painted on — the row's darkest state. */
  async function parentKeyInk(parentType: IssueType) {
    issueResponse = childOf(parentType)
    renderDetail([], [TYPE_TASK, parentType])
    const key = await screen.findByText(PARENT_KEY)
    return { key, ink: toHexFromCss(key.style.color) }
  }

  it('renders derived ink when the parent TYPE hue fails 4.5:1 on this row', async () => {
    // Sanity: the fixture really is a hue that cannot be read here, so a green
    // run below is the derivation working and not a threshold that never bites.
    expect(contrastRatio(TYPE_EPIC_UNREADABLE.color, SURFACE.row)).toBeLessThan(INK_MIN)

    const { ink } = await parentKeyInk(TYPE_EPIC_UNREADABLE)

    expect(ink).not.toBe(TYPE_EPIC_UNREADABLE.color)
    expect(contrastRatio(ink, SURFACE.row)).toBeGreaterThanOrEqual(INK_MIN)
  })

  it('paints the arrow and the key the same derived ink', async () => {
    const { key, ink } = await parentKeyInk(TYPE_EPIC_UNREADABLE)
    const glyph = key.closest('button')!.querySelector('svg')!
    expect(toHexFromCss(glyph.style.color)).toBe(ink)
  })

  it('leaves a hue that already reads exactly as stored', async () => {
    const { ink } = await parentKeyInk(TYPE_EPIC_READABLE)
    expect(ink).toBe(TYPE_EPIC_READABLE.color)
  })

  it('keeps a parent whose type the config does not carry on its muted token', async () => {
    // The config is the only source of a type's hue, so a parent typed by
    // something this project's config does not list has no hue at all.
    issueResponse = { ...childOf(TYPE_EPIC_UNREADABLE), parentTypeId: 'not-in-the-config' }
    renderDetail([], [TYPE_TASK])
    const key = await screen.findByText(PARENT_KEY)
    // A token, not a derived hex and not a raw junk value: the neutral is passed
    // into the derivation rather than defaulted in front of it.
    expect(key.style.color).toContain('--color-text-muted')
  })

  it('adds no box to the breadcrumb: it is still the bare button it was', async () => {
    const { key } = await parentKeyInk(TYPE_EPIC_UNREADABLE)
    const chip = key.closest('button') as HTMLElement
    expect(chip.style.background).toBe('')
    expect(chip.style.backgroundColor).toBe('')
    expect(chip.style.border).toBe('')
  })
})
