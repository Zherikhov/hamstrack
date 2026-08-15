import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import IssueDetail from './IssueDetail'
import type { Issue, IssueType, PriorityOption, ProjectField, Status } from '../types'

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

vi.mock('../api', () => ({
  ApiResponseError: class ApiResponseError extends Error { status = 0 },
  apiGetIssue: vi.fn(async () => issueResponse),
  apiUpdateIssue: vi.fn(),
  apiDeleteIssue: vi.fn(),
  apiListIssues: vi.fn(async () => ({ issues: [], truncated: false })),
  apiListComments: vi.fn(async () => ({
    content: [{
      id: 'c1', authorId: 'u1', authorName: AUTHOR, body: COMMENT_BODY,
      createdAt: '2026-08-11T13:45:00Z', updatedAt: '2026-08-11T13:45:00Z',
    }],
  })),
  apiCreateComment: vi.fn(),
  apiDeleteComment: vi.fn(),
  apiListAttachments: vi.fn(async () => []),
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
function renderDetail(fields: ProjectField[] = []) {
  render(
    <IssueDetail
      wsId="w1"
      projectId="p1"
      issueNumber={7}
      issueTypes={[TYPE_TASK]}
      statuses={[STATUS_TODO, STATUS_DOING]}
      transitions={[]}
      priorities={[PRIORITY]}
      fields={fields}
    />,
    { wrapper },
  )
}

afterEach(() => { issueResponse = ISSUE })

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
