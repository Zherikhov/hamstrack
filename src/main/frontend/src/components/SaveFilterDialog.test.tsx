import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import SaveFilterDialog from './SaveFilterDialog'

/**
 * HD-100 — "Save as filter" was a hand-rolled overlay: a bare `<div>` with no
 * `role`, no `aria-modal` and no accessible name. To a screen reader it was
 * indistinguishable from page content, and to a test it was unfindable — every
 * other dialog in the app (`CommandPalette`, `ShortcutsHelp`, the admin `Modal`)
 * is located via `[role="dialog"]`, and this one silently was not.
 *
 * The fix routes it through the shared `Modal`, so what is worth pinning is the
 * CONTRACT that shell provides — role, modality, name, the keyboard-ownership
 * flag — rather than the fact that a particular component is imported. Written
 * against the same expectations as `components/ShortcutsHelp.test.tsx`, so all
 * dialogs stay assertable the same way.
 */

const noop = () => {}

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<typeof import('../api')>()),
  // The dialog only touches these on submit; nothing here submits.
  savedFilters: { create: vi.fn(), update: vi.fn() },
}))

beforeEach(() => {
  vi.clearAllMocks()
})

function renderDialog(editing?: { id: string; name: string } | null) {
  return render(
    <SaveFilterDialog
      wsId="w1"
      hql='status = "To Do"'
      editing={editing ?? null}
      onClose={noop}
      onSaved={noop}
    />,
  )
}

describe('SaveFilterDialog — dialog semantics (HD-100)', () => {
  it('is findable via [role="dialog"], is modal, and carries an accessible name', () => {
    renderDialog()

    // The query every other dialog test in this codebase uses.
    const dialog = screen.getByRole('dialog', { name: 'Save as filter' })
    expect(dialog).toBeInTheDocument()
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    // Named, not merely present: an unnamed dialog is announced as "dialog".
    expect(dialog.getAttribute('aria-label')).toBe('Save as filter')

    // Raw-selector form too — this is the exact query that returned nothing
    // before the fix, so it is asserted literally rather than only through the
    // accessible-role helper.
    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1)
  })

  it('names itself after the action it is actually performing', () => {
    renderDialog({ id: 'f1', name: 'My open bugs' })

    // Editing an existing filter is a different action, and the accessible name
    // is the only thing that says so to a screen-reader user.
    expect(screen.getByRole('dialog', { name: 'Update filter' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Save as filter' })).toBeNull()
  })

  it('declares that it owns the keyboard, so global single-key shortcuts stay inert', () => {
    renderDialog()

    // HD-39 §8.3: `useGlobalShortcuts` stands down while this flag is in the DOM.
    // The hand-rolled overlay set it on itself; moving to the shared shell must
    // not have dropped it.
    expect(document.querySelector('[data-modal-open="true"]')).not.toBeNull()
  })

  it('still renders its own content — the shell replaced the chrome, not the form', async () => {
    const user = userEvent.setup()
    renderDialog()

    const dialog = screen.getByRole('dialog', { name: 'Save as filter' })
    expect(dialog).toContainElement(screen.getByRole('textbox', { name: /name/i }))
    // The query being saved is shown as PLAIN TEXT (never HTML) — the reason the
    // old overlay rendered it in a <div> and the reason it must keep doing so.
    expect(screen.getByText('status = "To Do"')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save filter' })).toBeInTheDocument()

    await user.type(screen.getByRole('textbox', { name: /name/i }), 'Open bugs')
    expect(screen.getByRole('textbox', { name: /name/i })).toHaveValue('Open bugs')
  })

  it('closes from its own controls', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(
      <SaveFilterDialog wsId="w1" hql="" editing={null} onClose={onClose} onSaved={noop} />,
    )

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onClose).toHaveBeenCalled()

    // …and from the backdrop, which is what a click OUTSIDE the panel means.
    onClose.mockClear()
    await user.click(screen.getByRole('dialog').parentElement!)
    expect(onClose).toHaveBeenCalled()
  })
})
