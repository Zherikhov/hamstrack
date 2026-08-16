import { describe, it, expect, afterEach } from 'vitest'
import { isEditableTarget, shouldHandleGlobalKey, anyOverlayOpen } from './keyboard'

// HD-39 §17.1 / §16: `isEditableTarget` is the single point of failure for the
// whole feature — one missed editable surface and typing `c` starts opening
// dialogs and eating characters. Each §8.3 clause gets its own assertion.

const NO_OVERLAYS = { createIssueOpen: false, paletteOpen: false, helpOpen: false }

function mount(html: string): HTMLElement {
  const host = document.createElement('div')
  host.innerHTML = html
  document.body.appendChild(host)
  return host
}

afterEach(() => {
  document.body.innerHTML = ''
})

/** A KeyboardEvent whose composedPath()/target resolve to `el`. */
function keyEvent(key: string, el: Element | null, init: Partial<KeyboardEventInit> & { keyCode?: number } = {}) {
  const e = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init })
  if (init.keyCode != null) Object.defineProperty(e, 'keyCode', { value: init.keyCode })
  Object.defineProperty(e, 'target', { value: el, configurable: true })
  Object.defineProperty(e, 'composedPath', { value: () => (el ? [el] : []), configurable: true })
  return e
}

describe('isEditableTarget', () => {
  it('is true for every text-ish input variant, including a type-less input', () => {
    const host = mount(`
      <input id="bare" />
      <input id="text" type="text" />
      <input id="search" type="search" />
      <input id="email" type="email" />
      <input id="password" type="password" />
      <input id="number" type="number" />
      <input id="date" type="date" />
      <input id="url" type="url" />
    `)
    for (const id of ['bare', 'text', 'search', 'email', 'password', 'number', 'date', 'url']) {
      expect(isEditableTarget(host.querySelector('#' + id)), id).toBe(true)
    }
  })

  it('is true for textarea and select', () => {
    const host = mount('<textarea></textarea><select><option>a</option></select>')
    expect(isEditableTarget(host.querySelector('textarea'))).toBe(true)
    expect(isEditableTarget(host.querySelector('select'))).toBe(true)
  })

  it('is true for contenteditable and for an element INSIDE a contenteditable', () => {
    const host = mount('<div contenteditable="true"><span id="inner">x</span></div>')
    expect(isEditableTarget(host.querySelector('[contenteditable]'))).toBe(true)
    expect(isEditableTarget(host.querySelector('#inner'))).toBe(true)
  })

  it('is true for the ARIA text roles (textbox / combobox / searchbox)', () => {
    const host = mount(`
      <div role="textbox"></div><div role="combobox"></div><div role="searchbox"></div>
    `)
    expect(isEditableTarget(host.querySelector('[role="textbox"]'))).toBe(true)
    expect(isEditableTarget(host.querySelector('[role="combobox"]'))).toBe(true)
    expect(isEditableTarget(host.querySelector('[role="searchbox"]'))).toBe(true)
  })

  it('is true for the [data-no-shortcuts] opt-out and anything inside it', () => {
    const host = mount('<div data-no-shortcuts><button id="b">x</button></div>')
    expect(isEditableTarget(host.querySelector('[data-no-shortcuts]'))).toBe(true)
    expect(isEditableTarget(host.querySelector('#b'))).toBe(true)
  })

  it('is false for buttons, plain divs, checkboxes/radios and contenteditable="false"', () => {
    const host = mount(`
      <button id="btn">x</button>
      <div id="div"></div>
      <input id="cb" type="checkbox" />
      <input id="radio" type="radio" />
      <input id="file" type="file" />
      <div id="ce" contenteditable="false"></div>
    `)
    for (const id of ['btn', 'div', 'cb', 'radio', 'file', 'ce']) {
      expect(isEditableTarget(host.querySelector('#' + id)), id).toBe(false)
    }
  })

  it('is false for the button-ish and picker input types that are not text surfaces', () => {
    const host = mount(`
      <input id="button" type="button" />
      <input id="submit" type="submit" />
      <input id="reset" type="reset" />
      <input id="range" type="range" />
      <input id="color" type="color" />
      <input id="image" type="image" />
    `)
    for (const id of ['button', 'submit', 'reset', 'range', 'color', 'image']) {
      expect(isEditableTarget(host.querySelector('#' + id)), id).toBe(false)
    }
  })

  it('is true for a keystroke landing INSIDE an editable wrapper (the closest() rationale)', () => {
    // HqlInput: an input[role=combobox] whose dropdown rows are plain divs — a
    // keystroke can be retargeted onto any of them while the user is typing.
    const host = mount(`
      <div role="combobox"><div id="opt">status</div></div>
      <div data-no-shortcuts><span id="deep"><em id="deeper">x</em></span></div>
      <label><span id="inlabel">Title</span><input id="titled" /></label>
    `)
    expect(isEditableTarget(host.querySelector('#opt'))).toBe(true)
    expect(isEditableTarget(host.querySelector('#deep'))).toBe(true)
    expect(isEditableTarget(host.querySelector('#deeper'))).toBe(true)
    expect(isEditableTarget(host.querySelector('#titled'))).toBe(true)
    // A <label>'s text is NOT editable — the suppression must not over-reach.
    expect(isEditableTarget(host.querySelector('#inlabel'))).toBe(false)
  })

  it('is true for the empty-string contenteditable form the browser normalises to', () => {
    const host = mount('<div id="ce" contenteditable=""></div>')
    expect(isEditableTarget(host.querySelector('#ce'))).toBe(true)
  })

  it('is false for a null/undefined or non-Element target', () => {
    expect(isEditableTarget(null)).toBe(false)
    expect(isEditableTarget(undefined)).toBe(false)
    expect(isEditableTarget(document as unknown as Element)).toBe(false)
  })
})

describe('anyOverlayOpen (clause 6)', () => {
  it('is false with no overlays and no [data-modal-open] node', () => {
    expect(anyOverlayOpen(NO_OVERLAYS)).toBe(false)
  })

  it('is true for each uiStore overlay flag', () => {
    expect(anyOverlayOpen({ ...NO_OVERLAYS, createIssueOpen: true })).toBe(true)
    expect(anyOverlayOpen({ ...NO_OVERLAYS, paletteOpen: true })).toBe(true)
    expect(anyOverlayOpen({ ...NO_OVERLAYS, helpOpen: true })).toBe(true)
  })

  it('is true when a locally-owned modal marks itself with data-modal-open', () => {
    mount('<div data-modal-open="true"></div>')
    expect(anyOverlayOpen(NO_OVERLAYS)).toBe(true)
  })
})

describe('shouldHandleGlobalKey (§8.3, clause by clause)', () => {
  it('allows a plain key on a non-editable target', () => {
    const host = mount('<div id="d"></div>')
    expect(shouldHandleGlobalKey(keyEvent('c', host.querySelector('#d')), NO_OVERLAYS)).toBe(true)
  })

  it('clause 1 — refuses an already-handled event', () => {
    const e = keyEvent('c', null)
    e.preventDefault()
    expect(shouldHandleGlobalKey(e, NO_OVERLAYS)).toBe(false)
  })

  it('clause 2 — refuses Ctrl/Meta/Alt, ignores Shift', () => {
    expect(shouldHandleGlobalKey(keyEvent('c', null, { ctrlKey: true }), NO_OVERLAYS)).toBe(false)
    expect(shouldHandleGlobalKey(keyEvent('c', null, { metaKey: true }), NO_OVERLAYS)).toBe(false)
    expect(shouldHandleGlobalKey(keyEvent('c', null, { altKey: true }), NO_OVERLAYS)).toBe(false)
    expect(shouldHandleGlobalKey(keyEvent('?', null, { shiftKey: true }), NO_OVERLAYS)).toBe(true)
  })

  it('clause 3 — refuses while an IME composition is in progress', () => {
    expect(shouldHandleGlobalKey(keyEvent('c', null, { isComposing: true }), NO_OVERLAYS)).toBe(false)
    expect(shouldHandleGlobalKey(keyEvent('c', null, { keyCode: 229 }), NO_OVERLAYS)).toBe(false)
  })

  it('clause 4 — refuses key auto-repeat', () => {
    expect(shouldHandleGlobalKey(keyEvent('c', null, { repeat: true }), NO_OVERLAYS)).toBe(false)
  })

  it('clause 5 — refuses when the target is editable', () => {
    const host = mount('<textarea></textarea>')
    expect(shouldHandleGlobalKey(keyEvent('c', host.querySelector('textarea')), NO_OVERLAYS)).toBe(false)
  })

  it('clause 6 — refuses while any overlay owns the keyboard', () => {
    expect(shouldHandleGlobalKey(keyEvent('c', null), { ...NO_OVERLAYS, paletteOpen: true })).toBe(false)
    mount('<div data-modal-open="true"></div>')
    expect(shouldHandleGlobalKey(keyEvent('c', null), NO_OVERLAYS)).toBe(false)
  })

  it('clause 5 — refuses for EVERY editable surface the app has today', () => {
    const host = mount(`
      <textarea id="ta"></textarea>
      <select id="sel"><option>a</option></select>
      <input id="txt" type="text" />
      <input id="bare" />
      <div id="ce" contenteditable="true"><span id="inner">x</span></div>
      <div id="hql" role="combobox"></div>
      <div id="tb" role="textbox"></div>
      <div id="sb" role="searchbox"></div>
      <div id="opt" data-no-shortcuts></div>
    `)
    for (const id of ['ta', 'sel', 'txt', 'bare', 'ce', 'inner', 'hql', 'tb', 'sb', 'opt']) {
      expect(shouldHandleGlobalKey(keyEvent('c', host.querySelector('#' + id)), NO_OVERLAYS), id).toBe(false)
    }
  })

  it('clause 5 — prefers composedPath()[0] over e.target (retargeted events)', () => {
    const host = mount('<textarea id="ta"></textarea><button id="btn">x</button>')
    const ta = host.querySelector('#ta')!
    const btn = host.querySelector('#btn')!
    // target says "button", the composed path says the keystroke really started
    // in the textarea — the deepest element wins, so the shortcut is suppressed.
    const e = new KeyboardEvent('keydown', { key: 'c', bubbles: true, cancelable: true })
    Object.defineProperty(e, 'target', { value: btn, configurable: true })
    Object.defineProperty(e, 'composedPath', { value: () => [ta, btn], configurable: true })
    expect(shouldHandleGlobalKey(e, NO_OVERLAYS)).toBe(false)
  })

  it('clause 6 — refuses for EACH overlay flag independently, and for the DOM probe', () => {
    for (const flag of ['createIssueOpen', 'paletteOpen', 'helpOpen'] as const) {
      expect(shouldHandleGlobalKey(keyEvent('c', null), { ...NO_OVERLAYS, [flag]: true }), flag).toBe(false)
    }
    // The probe is exact-match on "true": a modal that is merely mounted-but-shut
    // (data-modal-open="false") must NOT block the shortcut.
    mount('<div data-modal-open="false"></div>')
    expect(shouldHandleGlobalKey(keyEvent('c', null), NO_OVERLAYS)).toBe(true)
    mount('<div data-modal-open="true"></div>')
    expect(shouldHandleGlobalKey(keyEvent('c', null), NO_OVERLAYS)).toBe(false)
  })

  it('applies every clause to a chord second key as well (it is a single-key shortcut too)', () => {
    const host = mount('<textarea></textarea>')
    for (const key of ['b', 'l', 'h', 'm', 's', 'w', 'a']) {
      expect(shouldHandleGlobalKey(keyEvent(key, host.querySelector('textarea')), NO_OVERLAYS), key).toBe(false)
      expect(shouldHandleGlobalKey(keyEvent(key, null, { repeat: true }), NO_OVERLAYS), key).toBe(false)
      expect(shouldHandleGlobalKey(keyEvent(key, null, { isComposing: true }), NO_OVERLAYS), key).toBe(false)
    }
  })

  it('Cmd/Ctrl+K exemptions — modifiers and editable targets are allowed, the rest are not', () => {
    const host = mount('<input type="text" />')
    const opts = { allowModifiers: true, allowEditableTarget: true }
    const inInput = () => keyEvent('k', host.querySelector('input'), { metaKey: true })
    expect(shouldHandleGlobalKey(inInput(), NO_OVERLAYS, opts)).toBe(true)
    // …but an open overlay (clause 6) and auto-repeat (clause 4) still block it.
    expect(shouldHandleGlobalKey(inInput(), { ...NO_OVERLAYS, createIssueOpen: true }, opts)).toBe(false)
    expect(
      shouldHandleGlobalKey(
        keyEvent('k', host.querySelector('input'), { metaKey: true, repeat: true }),
        NO_OVERLAYS,
        opts,
      ),
    ).toBe(false)
  })
})
