import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import ShortcutsHelp from './ShortcutsHelp'
import { useUiStore } from '../uiStore'
import { useAuthStore } from '../auth'
import type { User } from '../types'

// HD-39 §17.1 (43–44). The `?` sheet is the only place the keyboard map is
// written down, so what matters is that it lists the real map, hides rows the
// current user can't use, and can always be dismissed.

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me', systemRole: 'USER' }
const ADMIN: User = { ...ME, systemRole: 'ADMIN' }

function renderHelp() {
  return render(<MemoryRouter initialEntries={['/home']}><ShortcutsHelp /></MemoryRouter>)
}

const open = () => act(() => { useUiStore.setState({ helpOpen: true }) })

beforeAll(() => {
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
  vi.clearAllMocks()
  useAuthStore.setState({ user: ME })
  useUiStore.setState({ helpOpen: false, helpFocusReturn: null, paletteOpen: false, createIssueOpen: false })
})

describe('ShortcutsHelp', () => {
  it('renders nothing until helpOpen flips', () => {
    renderHelp()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    open()
    expect(screen.getByRole('dialog', { name: 'Keyboard shortcuts' })).toBeInTheDocument()
  })

  it('lists the documented groups and rows', () => {
    renderHelp()
    open()
    for (const group of ['General', 'Go to', 'In the palette', 'Notes']) {
      expect(screen.getByText(group)).toBeInTheDocument()
    }
    expect(screen.getByText('Command palette')).toBeInTheDocument()
    expect(screen.getByText('Create issue')).toBeInTheDocument()
    expect(screen.getByText('Home')).toBeInTheDocument()
    expect(screen.getByText('Type an issue key to jump straight to it')).toBeInTheDocument()
    // The honest statement of today's search limits.
    expect(screen.getByText(/matches issue titles and descriptions/)).toBeInTheDocument()
  })

  it('hides the admin row from a non-admin and shows it to an ADMIN', () => {
    const { unmount } = renderHelp()
    open()
    expect(screen.queryByText('System administration')).not.toBeInTheDocument()
    unmount()

    useAuthStore.setState({ user: ADMIN })
    renderHelp()
    open()
    expect(screen.getByText('System administration')).toBeInTheDocument()
  })

  it('marks Board/Backlog as needing a project when there is none', () => {
    renderHelp()
    open()
    expect(screen.getAllByText('— needs a project')).toHaveLength(2)
  })

  it('closes on Escape, on `?` again, on a backdrop click and on the ✕ button', async () => {
    const user = userEvent.setup()
    renderHelp()

    open()
    await user.keyboard('{Escape}')
    expect(useUiStore.getState().helpOpen).toBe(false)

    open()
    await user.keyboard('?')
    expect(useUiStore.getState().helpOpen).toBe(false)

    open()
    await user.click(screen.getByRole('dialog').parentElement!)
    expect(useUiStore.getState().helpOpen).toBe(false)

    open()
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(useUiStore.getState().helpOpen).toBe(false)
  })

  it('ignores `?` that starts in a text surface — never closes, never swallows', () => {
    render(
      <MemoryRouter initialEntries={['/home']}>
        <input data-testid="bg" defaultValue="" />
        <ShortcutsHelp />
      </MemoryRouter>,
    )
    open()
    const bg = screen.getByTestId('bg')

    // §8.3 clause 5 / §16: a keystroke that started in an input belongs to the
    // input, even while the sheet is up.
    const typed = new KeyboardEvent('keydown', { key: '?', bubbles: true, cancelable: true })
    act(() => { bg.dispatchEvent(typed) })
    expect(useUiStore.getState().helpOpen).toBe(true)
    expect(typed.defaultPrevented).toBe(false)

    // Clause 1: an event something else already consumed is left alone.
    const handled = new KeyboardEvent('keydown', { key: '?', bubbles: true, cancelable: true })
    handled.preventDefault()
    act(() => { document.body.dispatchEvent(handled) })
    expect(useUiStore.getState().helpOpen).toBe(true)

    // Esc stays unconditional — it is the universal dismissal.
    const esc = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    act(() => { bg.dispatchEvent(esc) })
    expect(useUiStore.getState().helpOpen).toBe(false)
  })

  it('keeps focus on its own dismiss control while it is open', async () => {
    render(
      <MemoryRouter initialEntries={['/home']}>
        <input data-testid="bg" defaultValue="" />
        <ShortcutsHelp />
      </MemoryRouter>,
    )
    open()
    const closeBtn = screen.getByRole('button', { name: 'Close' })
    await waitFor(() => expect(closeBtn).toHaveFocus())

    // A background editor cannot steal the keyboard from under the sheet.
    act(() => { screen.getByTestId('bg').focus() })
    await waitFor(() => expect(closeBtn).toHaveFocus())
  })

  it('takes focus on open and hands it back on close', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/home']}>
        <button data-testid="opener">opener</button>
        <ShortcutsHelp />
      </MemoryRouter>,
    )
    const opener = screen.getByTestId('opener')
    opener.focus()

    open()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus())

    await user.keyboard('{Escape}')
    await waitFor(() => expect(opener).toHaveFocus())
  })

  // Two single-element focus traps up at once would re-focus each other through
  // requestAnimationFrame forever — the tab pegs and only a reload gets out. The
  // store refuses the second one, exactly like `openPalette` does (§3.3).
  it('refuses to open while the palette or the create dialog owns the keyboard', () => {
    renderHelp()

    useUiStore.setState({ paletteOpen: true })
    act(() => { useUiStore.getState().openHelp() })
    expect(useUiStore.getState().helpOpen).toBe(false)
    // `?` goes through toggleHelp — it inherits the same guard.
    act(() => { useUiStore.getState().toggleHelp() })
    expect(useUiStore.getState().helpOpen).toBe(false)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    useUiStore.setState({ paletteOpen: false })

    useUiStore.setState({ createIssueOpen: true })
    act(() => { useUiStore.getState().openHelp() })
    expect(useUiStore.getState().helpOpen).toBe(false)
    act(() => { useUiStore.getState().toggleHelp() })
    expect(useUiStore.getState().helpOpen).toBe(false)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    useUiStore.setState({ createIssueOpen: false })

    // With nothing else up it opens normally.
    act(() => { useUiStore.getState().openHelp() })
    expect(useUiStore.getState().helpOpen).toBe(true)
    expect(screen.getByRole('dialog', { name: 'Keyboard shortcuts' })).toBeInTheDocument()
  })

  it('marks itself as a modal so global single-key shortcuts stay inert', () => {
    renderHelp()
    open()
    expect(document.querySelector('[data-modal-open="true"]')).not.toBeNull()
  })
})
