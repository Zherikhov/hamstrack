import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import AdminFieldsPage from './AdminFieldsPage'
import { AdminApiProvider, type AdminScopeKind } from './AdminApiContext'
import type { AdminApi, UpsertFieldPayload } from '../../api'
import type { AdminField } from '../../types'

/**
 * HD-275 — the SPA half: end the silence around a custom field whose key a
 * built-in HQL name has taken, and offer the rename that is the only exit.
 *
 * The compatibility rule (AC-14/AC-30) is the reason this file exists at all.
 * The form used to initialise its key state from `field.key` and send it on
 * **every** update; the server now reads a `key` on update as a rename request
 * and refuses one with 422 unless the field is shadowed. Had the client kept
 * echoing it, every existing custom-field edit — a typo fixed in a display
 * name — would have started failing, on a path no other test looks at. So the
 * assertion here is about the *payload*, not about the dialog.
 */

/**
 * `TEXT` rather than the `MULTI_SELECT` a real `labels` field would be: the
 * editor disables Save on a select carrying no options, and every assertion
 * here is about the key, not about option editing.
 */
const BASE: AdminField = {
  id: 'f1', key: 'labels', name: 'Team labels', type: 'TEXT',
  config: null, archived: false, scope: 'WORKSPACE', usage: null,
}

function field(patch: Partial<AdminField>): AdminField {
  return { ...BASE, ...patch }
}

const update = vi.fn<(id: string, p: UpsertFieldPayload) => Promise<AdminField>>()
const create = vi.fn<(p: UpsertFieldPayload) => Promise<AdminField>>()

function fakeApi(fields: AdminField[]): AdminApi {
  return {
    fields: {
      list: vi.fn(async () => fields),
      create,
      update,
      archive: vi.fn(),
      unarchive: vi.fn(),
      remove: vi.fn(),
      usage: vi.fn(),
    },
    fieldSets: { list: vi.fn(async () => []), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
  } as unknown as AdminApi
}

function renderPage(fields: AdminField[], scope: AdminScopeKind = 'workspace') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <AdminApiProvider value={{
        api: fakeApi(fields), scope, eyebrow: 'Workspace', keyPrefix: ['admin', 'ws'],
      }}>
        <AdminFieldsPage />
      </AdminApiProvider>
    </QueryClientProvider>,
  )
}

/** Open the edit dialog for the (single) editable row on screen. */
async function openEditor(user: ReturnType<typeof userEvent.setup>, archived = false) {
  // An archived row is filtered out of the table until the toggle is on, and
  // the toggle itself only exists once something archived has loaded.
  if (archived) await user.click(await screen.findByRole('checkbox', { name: /Show archived/ }))
  await user.click(await screen.findByRole('button', { name: 'Edit' }))
  return screen.getByRole('dialog')
}

beforeEach(() => {
  vi.clearAllMocks()
  update.mockResolvedValue(BASE)
  create.mockResolvedValue(BASE)
})

// ── The row warning (AC-28) ──────────────────────────────────────────────────

describe('AdminFieldsPage — the shadowed-key warning on a row', () => {
  it('warns on a live shadowed field, naming the key, the built-in and the remedy', async () => {
    renderPage([field({ shadowedBy: 'label' })])

    const warning = await screen.findByText(/Not searchable/)
    expect(warning).toHaveTextContent('“labels” is a built-in search name')
    expect(warning).toHaveTextContent('answers from the built-in “label” field, not from this one')
    // This console owns the row, so the remedy is one the reader performs here.
    expect(warning).toHaveTextContent('Edit the field to rename its key.')
  })

  it('says nothing about an ordinary field', async () => {
    renderPage([field({ key: 'severity', name: 'Severity', shadowedBy: null })])

    expect(await screen.findByText('Severity')).toBeInTheDocument()
    expect(screen.queryByText(/Not searchable/)).toBeNull()
  })

  it('says nothing about an ARCHIVED shadowed field', async () => {
    // The predicate that matters: an archived definition is out of resolution,
    // so it shadows nothing. Every Hamstrack instance ships archived system
    // placeholders keyed `labels`/`sprint`/`components` (V3), and warning about
    // those would put a permanent false alarm on a clean install.
    const user = userEvent.setup()
    renderPage([field({ archived: true, shadowedBy: 'label' })])
    await user.click(await screen.findByRole('checkbox', { name: /Show archived/ }))

    expect(await screen.findByText('Team labels')).toBeInTheDocument()
    expect(screen.queryByText(/Not searchable/)).toBeNull()
  })

  it('points an inherited row at an administrator who can act, and names no workspace', async () => {
    // A project console cannot rename a field it inherits — the server answers
    // 404, as it does for every other inherited edit — so the copy must not
    // tell the reader to do it here.
    renderPage([field({ scope: 'WORKSPACE', shadowedBy: 'label' })], 'project')

    const warning = await screen.findByText(/Not searchable/)
    expect(warning).toHaveTextContent('A workspace administrator can rename its key.')
    expect(warning).not.toHaveTextContent('Edit the field')
    // The workspace is never identified: a project admin was not shown it.
    expect(warning.textContent).not.toMatch(/workspace “|workspace '/)
  })

  it('points an inherited GLOBAL row at an instance administrator', async () => {
    // A global definition is one row shadowed for every workspace at once; only
    // an instance admin can move it, and telling a workspace admin to would be
    // a refusal dressed as an instruction.
    renderPage([field({ scope: 'GLOBAL', shadowedBy: 'label' })], 'workspace')

    expect(await screen.findByText(/Not searchable/))
      .toHaveTextContent('An instance administrator can rename its key.')
  })
})

// ── The editor's Key input (AC-29) ───────────────────────────────────────────

describe('AdminFieldsPage — the rename affordance in the editor', () => {
  it('offers an editable Key on a shadowed field this console owns', async () => {
    const user = userEvent.setup()
    renderPage([field({ shadowedBy: 'label' })])
    const dialog = await openEditor(user)

    expect(screen.getByLabelText('Key')).toHaveValue('labels')
    // The type is still fixed — only the key moved.
    expect(dialog).toHaveTextContent('the type is fixed once created')
    expect(dialog).not.toHaveTextContent('key and type are fixed once created')
  })

  it('explains what the rename does and does not do, without counting filters', async () => {
    const user = userEvent.setup()
    renderPage([field({ shadowedBy: 'label' })])
    const dialog = await openEditor(user)

    expect(dialog).toHaveTextContent(
      '“labels” is a built-in search name, so searching for it answers from the built-in “label” field and not from this one.')
    expect(dialog).toHaveTextContent('Issue values are not affected.')
    expect(dialog).toHaveTextContent(
      'Saved filters are not rewritten — anyone using the old key will need to update their filter to the new one.')
    // No number: for a global definition the candidate set spans workspaces the
    // admin cannot see, and here the count of filters that actually break is
    // zero. A figure would invite the reader to believe otherwise.
    expect(dialog.textContent).not.toMatch(/\d+ (saved )?filter/i)
  })

  it('keeps the key fixed on a field that is NOT shadowed', async () => {
    const user = userEvent.setup()
    renderPage([field({ key: 'severity', name: 'Severity', shadowedBy: null })])
    const dialog = await openEditor(user)

    expect(screen.queryByLabelText('Key')).toBeNull()
    expect(dialog).toHaveTextContent('severity · Text — key and type are fixed once created')
  })

  it('keeps the key fixed on a shadowed SYSTEM field', async () => {
    // Refused 409 server-side: `DemoDataService` resolves system defs by key.
    const user = userEvent.setup()
    renderPage([field({ isSystem: true, shadowedBy: 'label' })])
    await openEditor(user)

    expect(screen.queryByLabelText('Key')).toBeNull()
  })

  it('offers the rename on an ARCHIVED shadowed field', async () => {
    // The row shows no warning (it is out of resolution) but the exit is still
    // offered — archiving a field is exactly what a curator does when it stops
    // working, and rename-then-unarchive is the recovery path.
    const user = userEvent.setup()
    renderPage([field({ archived: true, shadowedBy: 'label' })])
    await openEditor(user, true)

    expect(screen.getByLabelText('Key')).toHaveValue('labels')
  })
})

// ── The payload rule (AC-30, and the trap AC-14 exists for) ──────────────────

describe('AdminFieldsPage — `key` is sent only when it changed', () => {
  it('omits `key` from a name-only edit of an ordinary field', async () => {
    const user = userEvent.setup()
    renderPage([field({ key: 'severity', name: 'Severity', shadowedBy: null })])
    await openEditor(user)

    await user.clear(screen.getByLabelText('Name'))
    await user.type(screen.getByLabelText('Name'), 'Severity level')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    const [, payload] = update.mock.calls[0]
    expect(payload.name).toBe('Severity level')
    // The whole ticket's compatibility risk, in one assertion: a `key` here is
    // read as a rename request and refused 422 on an unshadowed field.
    expect(payload.key).toBeUndefined()
    expect('key' in payload && payload.key !== undefined).toBe(false)
  })

  it('omits `key` when the shadowed field is edited without touching the key', async () => {
    const user = userEvent.setup()
    renderPage([field({ shadowedBy: 'label' })])
    await openEditor(user)

    // The input is prefilled with the current key; leaving it alone is not a
    // rename, which is why the trigger is difference rather than presence.
    expect(screen.getByLabelText('Key')).toHaveValue('labels')
    await user.clear(screen.getByLabelText('Name'))
    await user.type(screen.getByLabelText('Name'), 'Squad labels')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][1].key).toBeUndefined()
  })

  it('omits `key` when only its casing changed', async () => {
    const user = userEvent.setup()
    renderPage([field({ shadowedBy: 'label' })])
    await openEditor(user)

    await user.clear(screen.getByLabelText('Key'))
    await user.type(screen.getByLabelText('Key'), 'LABELS')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    // The server lower-cases and compares the same way — `LABELS` over `labels`
    // is not a rename, and sending it would be a no-op the client had to guess.
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][1].key).toBeUndefined()
  })

  it('omits `key` when the input is cleared entirely', async () => {
    const user = userEvent.setup()
    renderPage([field({ shadowedBy: 'label' })])
    await openEditor(user)

    await user.clear(screen.getByLabelText('Key'))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    // Blank never means "re-derive the key from the name": that would rename a
    // field every time somebody fixed its display name.
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][1].key).toBeUndefined()
  })

  it('sends the new `key` when it actually changed', async () => {
    const user = userEvent.setup()
    renderPage([field({ shadowedBy: 'label' })])
    await openEditor(user)

    await user.clear(screen.getByLabelText('Key'))
    await user.type(screen.getByLabelText('Key'), 'team_labels')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][1].key).toBe('team_labels')
  })

  it('still sends the key a new field was given', async () => {
    const user = userEvent.setup()
    renderPage([])

    await user.click(await screen.findByRole('button', { name: '+ New field' }))
    await user.type(screen.getByLabelText('Name'), 'Team labels')
    await user.type(screen.getByLabelText(/^Key \(optional/), 'team_labels')
    await user.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][0].key).toBe('team_labels')
  })
})
