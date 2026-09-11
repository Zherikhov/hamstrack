import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import AboutModal from './AboutModal'
import { useCloseOnEscape } from '../hooks/useCloseOnEscape'

/**
 * **The keyboard half of HD-300's dialog claim.**
 *
 * HD-300 put `role="dialog"` + `aria-modal="true"` on three hand-rolled overlays.
 * The ui_qa gate then measured, in the signed system Chrome, that none of them
 * closed on Escape: the attributes asserted "this region owns the interaction"
 * and nothing behind them did. That is the same defect shape as a lint rule that
 * passes while checking nothing, so the behaviour ships with the claim.
 *
 * **What this file holds, stated so the gap is a decision and not an omission:**
 * Escape, on the shared `useCloseOnEscape`, for the three overlays this ticket
 * declared modal. It does **not** hold a focus trap or focus restoration —
 * neither exists anywhere in this repo, Tab leaves every dialog including the
 * ones HD-300 never touched, and the fix belongs to the whole dialog category
 * (six hand-rolled overlays plus the shared `Modal`) rather than to three of
 * them. That is a follow-up filed off this gate, and it is why this file is
 * named for Escape rather than for "dialog semantics".
 *
 * The other two members are asserted where their fixtures already live —
 * `CreateIssueModal.test.tsx` and `CreateProjectModal.test.tsx` — because a
 * second copy of either mock block is the duplication this repo calls a defect.
 * `CreateIssueModal`'s case is the interesting one: it presses Escape twice,
 * once with a listbox open, which is the `defaultPrevented` clause below
 * measured through a real nested widget instead of a fixture.
 */

describe('AboutModal closes on Escape (HD-300)', () => {
  it('closes on Escape, and stays open for any other key', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(
      <MemoryRouter>
        <AboutModal onClose={onClose} />
      </MemoryRouter>,
    )

    // The claim under test is the one the attributes make.
    expect(screen.getByRole('dialog', { name: 'About' })).toBeInTheDocument()

    await user.keyboard('{Enter}')
    await user.keyboard('a')
    expect(onClose).not.toHaveBeenCalled()

    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

/**
 * The hook's own contract, on a fixture rather than on a modal: these three
 * properties are why the three call sites are one line each, and each of them
 * is a bug that would otherwise be found by hand in a browser.
 */
describe('useCloseOnEscape', () => {
  function Probe({ onClose, enabled }: { onClose: () => void; enabled?: boolean }) {
    useCloseOnEscape(onClose, enabled)
    return <button>probe</button>
  }

  it('leaves an Escape another widget already handled alone', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    // A nested widget that owns Escape while its popup is open — exactly what
    // `Select`, `LabelPicker` and every inline editor in `IssueDetail` do.
    function Nested() {
      useCloseOnEscape(onClose)
      return (
        <input
          aria-label="inner"
          onKeyDown={e => { if (e.key === 'Escape') e.preventDefault() }}
        />
      )
    }
    render(<Nested />)

    await user.click(screen.getByLabelText('inner'))
    await user.keyboard('{Escape}')
    expect(
      onClose,
      'the dialog closed on an Escape a nested popup had already consumed, so the first ' +
      'press now closes two things at once',
    ).not.toHaveBeenCalled()
  })

  it('stops listening when the dialog unmounts', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    function Host() {
      const [open, setOpen] = useState(true)
      return (
        <>
          <button onClick={() => setOpen(false)}>unmount</button>
          {open && <Probe onClose={onClose} />}
        </>
      )
    }
    render(<Host />)

    await user.click(screen.getByRole('button', { name: 'unmount' }))
    await user.keyboard('{Escape}')
    expect(
      onClose,
      'the window listener outlived the component, so every closed dialog keeps calling ' +
      'back on every Escape for the rest of the session',
    ).not.toHaveBeenCalled()
  })

  it('detaches when disabled and never breaks hook order', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<Probe onClose={onClose} enabled={false} />)
    await user.keyboard('{Escape}')
    expect(onClose, 'enabled=false still listened').not.toHaveBeenCalled()
  })

  it('calls the CURRENT callback, not the one captured on mount', async () => {
    const user = userEvent.setup()
    const first = vi.fn()
    const second = vi.fn()
    function Host() {
      const [swapped, setSwapped] = useState(false)
      return (
        <>
          <button onClick={() => setSwapped(true)}>swap</button>
          <Probe onClose={swapped ? second : first} />
        </>
      )
    }
    render(<Host />)

    await user.click(screen.getByRole('button', { name: 'swap' }))
    await user.keyboard('{Escape}')
    expect(first, 'a stale closure from the first render answered the keystroke').not.toHaveBeenCalled()
    expect(second).toHaveBeenCalledTimes(1)
  })
})
