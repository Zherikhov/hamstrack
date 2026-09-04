/**
 * **The in-page half of the contrast audit (HD-175).**
 *
 * Everything exported here is serialised into the browser by `page.evaluate`, so it
 * may not import anything and may not close over anything. It deliberately does
 * **no colour arithmetic**: it reports what the DOM says — a foreground hex, an
 * ordered stack of background layers, a size, a weight, an opacity, a DOM path —
 * and the Node half measures it with `src/colour.ts`. Two contrast implementations
 * is two answers, and the one living in a harness is the one nobody tests.
 *
 * The one judgement it does make is *which elements exist to be measured*, because
 * that needs a live layout box and cannot be decided anywhere else.
 */

/**
 * Returns `{ text, graphics, counts }` for the current document.
 *
 * `text` — one entry per element that owns a visible, non-whitespace text node.
 * `graphics` — every visible `<svg>`, with its `aria-hidden` state recorded rather
 * than used as a filter, for the separate, non-failing WCAG 1.4.11 section (spec
 * section 6.8).
 */
export function collectInPage(maxElements) {
  const LIMIT = maxElements || 8000

  // ── colour plumbing ───────────────────────────────────────────────────────
  // getComputedStyle answers in `rgb()` / `rgba()`, and — for anything that went
  // through `color-mix()` — in `color(srgb r g b / a)` with 0–1 channels. Both are
  // read. Anything else (`lab()`, `oklch()`, a system colour) is reported unparsed
  // rather than guessed at, and the Node half turns that into an `indeterminate`,
  // which fails the run.
  //
  // The srgb form is not a nicety. `ui.tsx` paints a brand tint with
  // `color-mix(in srgb, var(--color-brand) 12%, var(--color-card))`, so without it
  // every element sitting on one of those tints is a hole in the audit — and a
  // hole is exactly what this harness exists to stop being invisible. The first
  // run of it reported seven.
  function toHex8(css) {
    if (!css) return null
    const s = String(css).trim()
    if (s === 'transparent') return '#00000000'
    const hx = (n) => n.toString(16).padStart(2, '0').toUpperCase()
    const clamp255 = (x) => Math.max(0, Math.min(255, Math.round(x)))
    const alphaOf = (v) => Math.max(0, Math.min(1, v.endsWith('%') ? parseFloat(v) / 100 : parseFloat(v)))
    if (/^color\(\s*srgb\b/i.test(s)) {
      const parts = s.slice(s.indexOf('(') + 1).match(/-?[\d.]+%?/g)
      if (!parts || parts.length < 3) return null
      const c = (v) => clamp255((v.endsWith('%') ? parseFloat(v) / 100 : parseFloat(v)) * 255)
      const al = parts.length > 3 ? alphaOf(parts[3]) : 1
      return '#' + hx(c(parts[0])) + hx(c(parts[1])) + hx(c(parts[2])) + hx(Math.round(al * 255))
    }
    if (!/^rgba?\(/i.test(s)) return null
    const nums = s.match(/-?[\d.]+%?/g)
    if (!nums || nums.length < 3) return null
    const ch = (v) => clamp255(v.endsWith('%') ? (parseFloat(v) / 100) * 255 : parseFloat(v))
    const a = nums.length > 3 ? alphaOf(nums[3]) : 1
    return '#' + hx(ch(nums[0])) + hx(ch(nums[1])) + hx(ch(nums[2])) + hx(Math.round(a * 255))
  }

  // Colour stops out of a `linear-gradient(...)` / `radial-gradient(...)`. Chrome
  // normalises every named colour, hex literal and `var()` in a gradient to `rgb()`
  // by the time it reaches `getComputedStyle`, so a gradient that yields no stop
  // here is genuinely unusual — and is reported, not skipped. A harness that
  // silently skips gradient backgrounds reports a clean button that is not clean.
  function gradientStops(bgImage) {
    const out = []
    const re = /(?:rgba?|color)\([^)]*\)/gi
    let m = re.exec(bgImage)
    while (m !== null) {
      const hex = toHex8(m[0])
      if (hex) out.push(hex)
      m = re.exec(bgImage)
    }
    return out
  }

  function domPath(el) {
    const parts = []
    let node = el
    while (node && node.nodeType === 1 && parts.length < 8) {
      let part = node.tagName.toLowerCase()
      if (node.id) { parts.unshift(part + '#' + node.id); break }
      const cls = typeof node.className === 'string' ? node.className.trim() : ''
      if (cls) part += '.' + cls.split(/\s+/).slice(0, 3).join('.')
      parts.unshift(part)
      node = node.parentElement
    }
    return parts.join(' > ')
  }

  /**
   * The effective background under `el`, as an ordered stack of layers, topmost
   * first. The walk stops at the first fully opaque layer; if it reaches the root
   * without one, the element is `indeterminate` rather than assumed white — an
   * unmeasurable element is a hole, not a success (spec section 6.5).
   */
  function backgroundStack(el) {
    const layers = []
    let node = el
    let guard = 0
    while (node && node.nodeType === 1 && guard < 64) {
      guard += 1
      const cs = getComputedStyle(node)
      const image = cs.backgroundImage
      if (image && image !== 'none') {
        if (/gradient\(/i.test(image)) {
          const stops = gradientStops(image)
          if (stops.length === 0) return { layers, indeterminate: 'unreadable-gradient' }
          layers.push({ kind: 'gradient', stops })
          if (stops.every((s) => s.slice(7, 9) === 'FF')) return { layers, indeterminate: null }
        } else if (/url\(/i.test(image)) {
          return { layers, indeterminate: 'background-image' }
        }
      }
      const bg = toHex8(cs.backgroundColor)
      if (bg === null) return { layers, indeterminate: 'unparseable-background' }
      if (bg.slice(7, 9) !== '00') {
        layers.push({ kind: 'colour', hex: bg })
        if (bg.slice(7, 9) === 'FF') return { layers, indeterminate: null }
      }
      node = node.parentElement
    }
    return { layers, indeterminate: 'no-opaque-ancestor' }
  }

  function cumulativeOpacity(el) {
    let o = 1
    let node = el
    let guard = 0
    while (node && node.nodeType === 1 && guard < 64) {
      guard += 1
      const v = parseFloat(getComputedStyle(node).opacity)
      if (!Number.isNaN(v)) o *= v
      node = node.parentElement
    }
    return o
  }

  function isInert(el) {
    // WCAG 1.4.3 exempts an inactive control. `ui.tsx` renders those as a passing
    // ink under an `opacity`, so they must be *named* rather than measured —
    // otherwise a documented exemption reads as a failure and the failure list
    // stops being a work list.
    return !!el.closest('[disabled],[aria-disabled="true"],fieldset[disabled]')
  }

  function visible(el) {
    const cs = getComputedStyle(el)
    if (cs.visibility === 'hidden' || cs.display === 'none') return false
    if (parseFloat(cs.opacity) === 0) return false
    const r = el.getBoundingClientRect()
    return r.width > 0 && r.height > 0
  }

  function ownText(el) {
    let s = ''
    for (const n of el.childNodes) if (n.nodeType === 3) s += n.nodeValue
    return s.replace(/\s+/g, ' ').trim()
  }

  const text = []
  const graphics = []
  let skippedInert = 0
  let skippedInvisible = 0

  const all = document.querySelectorAll('body *')
  for (const el of all) {
    if (text.length + graphics.length >= LIMIT) break
    const tag = el.tagName.toLowerCase()
    if (tag === 'script' || tag === 'style' || tag === 'noscript') continue

    const inSvg = !!el.ownerSVGElement
    if (tag === 'svg') {
      // 1.4.11 candidates. A lucide icon is `stroke="currentColor"`, so its ink is
      // the computed `color` of the <svg> element itself.
      //
      // `aria-hidden` is **recorded, not filtered**. lucide-react marks every icon
      // aria-hidden, so filtering on it collects nothing at all — which is how a
      // first run of this harness reported zero 1.4.11 candidates on six pages that
      // are full of icons. The flag matters to the reader of the follow-up ticket
      // (a chart body is aria-hidden over a real table and is decorative; a grip
      // handle is aria-hidden and identifies a control), so it travels with the
      // finding instead of deciding it here.
      if (!visible(el)) continue
      const cs = getComputedStyle(el)
      const bgs = backgroundStack(el.parentElement || el)
      graphics.push({
        path: domPath(el),
        html: el.outerHTML.slice(0, 120),
        colour: toHex8(cs.color),
        layers: bgs.layers,
        indeterminate: bgs.indeterminate,
        opacity: cumulativeOpacity(el),
        inert: isInert(el),
        ariaHidden: !!el.closest('[aria-hidden="true"]'),
      })
      continue
    }

    const content = ownText(el)
    if (!content) continue
    if (!visible(el)) { skippedInvisible += 1; continue }
    if (isInert(el)) { skippedInert += 1; continue }

    const cs = getComputedStyle(el)
    // SVG <text> paints with `fill`, not `color`.
    const fg = (inSvg ? toHex8(cs.fill) : null) ?? toHex8(cs.color)
    // A fully transparent glyph is not on screen and has no ratio to hold. The HQL
    // editor stacks a `color: transparent` syntax-highlight underlay under the real
    // <textarea>; measured naively it reads as white-on-white at 1.00 and is the
    // single most convincing wrong failure this harness can produce.
    if (fg !== null && fg.slice(7, 9) === '00') { skippedInvisible += 1; continue }
    const bgs = backgroundStack(el)
    const size = parseFloat(cs.fontSize) || 0
    const weightRaw = cs.fontWeight
    const weight = weightRaw === 'bold' ? 700 : weightRaw === 'normal' ? 400 : parseInt(weightRaw, 10) || 400

    text.push({
      path: domPath(el),
      html: el.outerHTML.slice(0, 160),
      sample: content.slice(0, 60),
      colour: fg,
      layers: bgs.layers,
      indeterminate: fg === null ? 'unparseable-colour' : bgs.indeterminate,
      sizePx: size,
      weight,
      opacity: cumulativeOpacity(el),
      ariaHidden: !!el.closest('[aria-hidden="true"]'),
    })
  }

  return { text, graphics, counts: { skippedInert, skippedInvisible, scanned: all.length } }
}
