import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { useGlobalShortcuts, CHORD_TIMEOUT_MS } from './useGlobalShortcuts'
import { useUiStore } from '../uiStore'
import { useAuthStore } from '../auth'
import type { User } from '../types'

// HD-39 §17.1 (12–24). The map itself is trivial; what these pin down is §8.3 —
// when a global shortcut must NOT fire. That predicate is the feature's single
// point of failure (§16): miss one editable surface and typing `c` starts
// opening dialogs and swallowing characters.

const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me', systemRole: 'USER' }
const ADMIN: User = { ...ME, systemRole: 'ADMIN' }

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

function seedRecentProject(userId: string) {
  localStorage.setItem(
    `hamstrack.recent-projects.${userId}`,
    JSON.stringify([{ wsId: WS_ID, projectId: PROJECT_ID, key: 'PR', name: 'Proj', visitedAt: Date.now() }]),
  )
}

function Harness() {
  const { chordArmed } = useGlobalShortcuts()
  const loc = useLocation()
  return (
    <>
      <div data-testid="path">{loc.pathname}</div>
      <div data-testid="chord">{chordArmed ? 'armed' : 'idle'}</div>
      <textarea data-testid="ta" />
      <input data-testid="txt" />
      <button data-testid="btn">b</button>
      {/* The rest of the editable surfaces §8.3 clause 5 must suppress. */}
      <select data-testid="sel"><option>a</option></select>
      <div data-testid="ce" contentEditable suppressContentEditableWarning>
        <span data-testid="ce-inner">rich text</span>
      </div>
      {/* HqlInput is an input[role=combobox]; its dropdown rows are plain divs. */}
      <div data-testid="hql" role="combobox">
        <div data-testid="hql-option">status</div>
      </div>
      <div data-testid="optout" data-no-shortcuts>
        <span data-testid="optout-inner">widget</span>
      </div>
    </>
  )
}

function renderHook() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/home']}>
        <Routes><Route path="*" element={<Harness />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/**
 * Dispatch a real bubbling keydown from the currently focused element, so the
 * window-level listener sees exactly what a browser would give it.
 */
function press(key: string, init: KeyboardEventInit & { keyCode?: number } = {}, from?: Element) {
  const target = from ?? (document.activeElement as Element | null) ?? document.body
  const e = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init })
  if (init.keyCode != null) Object.defineProperty(e, 'keyCode', { value: init.keyCode })
  act(() => { target.dispatchEvent(e) })
  return e
}

const path = () => screen.getByTestId('path').textContent

beforeEach(() => {
  localStorage.clear()
  useAuthStore.setState({ user: ME })
  useUiStore.setState({
    createIssueOpen: false, createIssuePreset: undefined,
    paletteOpen: false, paletteRestoreFocus: true,
    helpOpen: false, helpFocusReturn: null, searchFocusNonce: 0,
  })
})

afterEach(() => {
  vi.useRealTimers()
  document.body.innerHTML = ''
})

describe('useGlobalShortcuts — the map', () => {
  it('`c` opens the create-issue modal', () => {
    renderHook()
    press('c')
    expect(useUiStore.getState().createIssueOpen).toBe(true)
  })

  it('`?` toggles the help overlay', () => {
    renderHook()
    press('?', { shiftKey: true })
    expect(useUiStore.getState().helpOpen).toBe(true)
  })

  it('`/` requests search focus and preventDefaults, when a workspace is resolvable', () => {
    seedRecentProject(ME.id)
    renderHook()
    const e = press('/')
    expect(useUiStore.getState().searchFocusNonce).toBe(1)
    expect(e.defaultPrevented).toBe(true)
  })

  it('`/` is an inert no-op (and is NOT preventDefaulted) with no workspace at all', () => {
    renderHook()
    const e = press('/')
    expect(useUiStore.getState().searchFocusNonce).toBe(0)
    expect(e.defaultPrevented).toBe(false)
  })

  it('`g` then `h`/`m`/`w` navigates', () => {
    renderHook()
    press('g'); press('h')
    expect(path()).toBe('/home')
    press('g'); press('m')
    expect(path()).toBe('/my-work')
    press('g'); press('w')
    expect(path()).toBe('/workspaces')
  })

  it('`g b` / `g l` navigate to the current project when there is one', () => {
    seedRecentProject(ME.id)
    renderHook()
    press('g'); press('b')
    expect(path()).toBe(`/w/${WS_ID}/p/${PROJECT_ID}`)
    press('g'); press('l')
    expect(path()).toBe(`/w/${WS_ID}/p/${PROJECT_ID}/backlog`)
  })

  it('`g b` is a no-op with no current project', () => {
    renderHook()
    press('g')
    const e = press('b')
    expect(path()).toBe('/home')
    expect(e.defaultPrevented).toBe(false)
  })

  it('`g a` navigates only for a system ADMIN', () => {
    renderHook()
    press('g'); press('a')
    expect(path()).toBe('/home')

    act(() => { useAuthStore.setState({ user: ADMIN }) })
    press('g'); press('a')
    expect(path()).toBe('/admin')
  })

  it('`g` then an unmapped key cancels the chord silently', () => {
    seedRecentProject(ME.id)
    renderHook()
    press('g')
    expect(screen.getByTestId('chord')).toHaveTextContent('armed')
    press('x')
    expect(screen.getByTestId('chord')).toHaveTextContent('idle')
    // …and the now-disarmed `b` does nothing.
    press('b')
    expect(path()).toBe('/home')
  })

  it('an expired chord (>1200 ms) does not navigate', () => {
    vi.useFakeTimers()
    seedRecentProject(ME.id)
    renderHook()
    press('g')
    act(() => { vi.advanceTimersByTime(CHORD_TIMEOUT_MS + 1) })
    expect(screen.getByTestId('chord')).toHaveTextContent('idle')
    press('b')
    expect(path()).toBe('/home')
  })

  it('cancels an armed chord when the window loses focus', () => {
    renderHook()
    press('g')
    expect(screen.getByTestId('chord')).toHaveTextContent('armed')
    act(() => { window.dispatchEvent(new Event('blur')) })
    expect(screen.getByTestId('chord')).toHaveTextContent('idle')
  })
})

describe('useGlobalShortcuts — §8.3 suppression', () => {
  it('does not fire while focus is in a textarea or an input', () => {
    renderHook()
    press('c', {}, screen.getByTestId('ta'))
    expect(useUiStore.getState().createIssueOpen).toBe(false)
    press('c', {}, screen.getByTestId('txt'))
    expect(useUiStore.getState().createIssueOpen).toBe(false)
    press('?', { shiftKey: true }, screen.getByTestId('txt'))
    expect(useUiStore.getState().helpOpen).toBe(false)
  })

  it('still fires from a non-editable element such as a button', () => {
    renderHook()
    press('c', {}, screen.getByTestId('btn'))
    expect(useUiStore.getState().createIssueOpen).toBe(true)
  })

  it('does not fire with Ctrl / Alt / Meta held', () => {
    renderHook()
    press('c', { ctrlKey: true })
    press('c', { altKey: true })
    press('c', { metaKey: true })
    expect(useUiStore.getState().createIssueOpen).toBe(false)
  })

  it('does not fire on key auto-repeat', () => {
    renderHook()
    press('c', { repeat: true })
    expect(useUiStore.getState().createIssueOpen).toBe(false)
  })

  it('does not fire during IME composition', () => {
    renderHook()
    press('c', { isComposing: true })
    press('c', { keyCode: 229 })
    expect(useUiStore.getState().createIssueOpen).toBe(false)
  })

  it('does not fire while the palette or the help overlay is open', () => {
    renderHook()
    act(() => { useUiStore.setState({ paletteOpen: true }) })
    press('c')
    expect(useUiStore.getState().createIssueOpen).toBe(false)

    act(() => { useUiStore.setState({ paletteOpen: false, helpOpen: true }) })
    press('c')
    expect(useUiStore.getState().createIssueOpen).toBe(false)
  })

  it('does not fire while a locally-owned modal marks itself data-modal-open', () => {
    renderHook()
    const modal = document.createElement('div')
    modal.setAttribute('data-modal-open', 'true')
    document.body.appendChild(modal)
    press('c')
    expect(useUiStore.getState().createIssueOpen).toBe(false)
  })

  it('does not fire when the event was already handled downstream', () => {
    renderHook()
    const e = new KeyboardEvent('keydown', { key: 'c', bubbles: true, cancelable: true })
    e.preventDefault()
    act(() => { document.body.dispatchEvent(e) })
    expect(useUiStore.getState().createIssueOpen).toBe(false)
  })
})

describe('useGlobalShortcuts — §8.3 clause 5, every editable surface (§16)', () => {
  const SURFACES = ['ta', 'txt', 'sel', 'ce', 'ce-inner', 'hql', 'hql-option', 'optout', 'optout-inner'] as const

  it('never opens the create dialog from any editable surface (nor from inside one)', () => {
    renderHook()
    for (const id of SURFACES) {
      press('c', {}, screen.getByTestId(id))
      expect(useUiStore.getState().createIssueOpen, id).toBe(false)
    }
  })

  it('never opens help and never steals `/` from any editable surface', () => {
    seedRecentProject(ME.id)
    renderHook()
    for (const id of SURFACES) {
      const el = screen.getByTestId(id)
      press('?', { shiftKey: true }, el)
      expect(useUiStore.getState().helpOpen, id).toBe(false)
      // `/` must be typed literally: no focus request AND no preventDefault
      // (swallowing it would delete the character the user meant to type).
      const e = press('/', {}, el)
      expect(useUiStore.getState().searchFocusNonce, id).toBe(0)
      expect(e.defaultPrevented, id).toBe(false)
    }
  })

  it('does not arm a `g` chord from a text surface, and a stray `b` afterwards is inert', () => {
    seedRecentProject(ME.id)
    renderHook()
    const e = press('g', {}, screen.getByTestId('txt'))
    expect(screen.getByTestId('chord')).toHaveTextContent('idle')
    expect(e.defaultPrevented).toBe(false)
    press('b')
    expect(path()).toBe('/home')
  })

  it('suppresses (and cancels) a chord whose SECOND key lands in a text field', () => {
    seedRecentProject(ME.id)
    renderHook()
    press('g')
    expect(screen.getByTestId('chord')).toHaveTextContent('armed')
    // Focus moved into an editor between the two presses — `g` must not swallow
    // the keystroke, and the chord must not survive to eat the next one either.
    press('b', {}, screen.getByTestId('ta'))
    expect(path()).toBe('/home')
    expect(screen.getByTestId('chord')).toHaveTextContent('idle')
    press('b')
    expect(path()).toBe('/home')
  })

  it('suppresses (and cancels) a chord when a modal opens mid-chord', () => {
    seedRecentProject(ME.id)
    renderHook()
    press('g')
    act(() => { useUiStore.setState({ createIssueOpen: true }) })
    press('b')
    expect(path()).toBe('/home')
    expect(screen.getByTestId('chord')).toHaveTextContent('idle')
  })

  it('does not fire while the create-issue modal owns the keyboard', () => {
    renderHook()
    act(() => { useUiStore.setState({ createIssueOpen: true }) })
    press('?', { shiftKey: true })
    expect(useUiStore.getState().helpOpen).toBe(false)
    press('g'); press('m')
    expect(path()).toBe('/home')
  })

  it('does not arm a chord on key auto-repeat or during IME composition', () => {
    renderHook()
    press('g', { repeat: true })
    expect(screen.getByTestId('chord')).toHaveTextContent('idle')
    press('g', { isComposing: true })
    expect(screen.getByTestId('chord')).toHaveTextContent('idle')
    press('g', { keyCode: 229 })
    expect(screen.getByTestId('chord')).toHaveTextContent('idle')
  })

  it('matches `e.key` case-sensitively, so Caps Lock does not trigger anything (§8.1)', () => {
    seedRecentProject(ME.id)
    renderHook()
    press('C')
    expect(useUiStore.getState().createIssueOpen).toBe(false)
    press('G')
    expect(screen.getByTestId('chord')).toHaveTextContent('idle')
    press('g'); press('B')
    expect(path()).toBe('/home')
  })

  it('`g s` goes to Search when a workspace is resolvable, and is inert otherwise', () => {
    const { unmount } = renderHook()
    press('g'); press('s')
    expect(path()).toBe('/home') // no workspace anywhere → no-op
    unmount()

    seedRecentProject(ME.id)
    renderHook()
    press('g'); press('s')
    expect(path()).toBe(`/w/${WS_ID}/search`)
  })
})

describe('useGlobalShortcuts — Cmd/Ctrl+K', () => {
  it('opens the palette even while typing in an input (the documented exemption)', () => {
    renderHook()
    press('k', { metaKey: true }, screen.getByTestId('txt'))
    expect(useUiStore.getState().paletteOpen).toBe(true)
  })

  it('accepts Ctrl as well as Meta', () => {
    renderHook()
    press('k', { ctrlKey: true })
    expect(useUiStore.getState().paletteOpen).toBe(true)
  })

  it('does NOT open while the create-issue modal is up', () => {
    renderHook()
    act(() => { useUiStore.setState({ createIssueOpen: true }) })
    press('k', { metaKey: true })
    expect(useUiStore.getState().paletteOpen).toBe(false)
  })
})

describe('useGlobalShortcuts — lifecycle', () => {
  it('removes its listener on unmount so a route change cannot double-fire', () => {
    const { unmount } = renderHook()
    unmount()
    press('c')
    expect(useUiStore.getState().createIssueOpen).toBe(false)
  })
})
