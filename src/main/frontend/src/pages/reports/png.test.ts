import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  IMAGE_FALLBACK_COLOR, copyImageToClipboard, copyTextToClipboard, csvFileName, formatComputedAt,
  imageFileName, imageFooterLines, prepareSvg, resolveCssVars, svgDataUrl,
} from './png'

/**
 * HD-141 (R7) — the PNG pipeline, minus the one call that needs a real browser.
 *
 * Two things are being protected here, and neither is "the image looks nice":
 *
 *  1. **The picture says what it is a picture of.** Project, window and
 *     `computedAt` are in the footer of every image, because a chart pasted into
 *     a chat is separated from its URL the instant it is pasted — and a
 *     truncated report stays truncated in the picture, since an image is the
 *     most portable form of "these numbers don't match what I expected".
 *  2. **A `var()` never survives serialisation.** An `<svg>` rasterised through
 *     an `Image` cannot see the page's `:root`, so `stroke="var(--color-chart-1)"`
 *     — which is what every chart in this feature writes — resolves to nothing
 *     and the series is simply not painted. A wrongly-coloured line can be
 *     argued with; a missing one silently changes the chart. jsdom loads no
 *     stylesheet, so these tests exercise exactly that unresolved path.
 */

afterEach(() => vi.unstubAllGlobals())

describe('imageFooterLines — what travels with the picture', () => {
  const base = {
    project: 'Payments (PAY)',
    window: '2026-05-22 → 2026-08-20 (UTC), weekly buckets',
    computedAt: '2026-08-20T09:14:22Z',
    basedOnIssues: 1842,
  }

  it('carries project, window and computedAt — the three §4.4 names', () => {
    const [identity, provenance] = imageFooterLines(base)
    expect(identity).toContain('Payments (PAY)')
    expect(identity).toContain('2026-05-22 → 2026-08-20 (UTC)')
    expect(provenance).toContain('computed 2026-08-20 09:14 UTC')
    // Grouping is the reader's locale, so the assertion is not: what matters is
    // that the count is there, not which separator this runtime chose.
    expect(provenance).toMatch(/based on 1.842 issues/)
  })

  it('states the axis rule in the image, because that is where the claim is used', () => {
    // The rule's whole value is that a second export can be held beside this
    // one; a reader who does not know it holds has no reason to try.
    expect(imageFooterLines(base)[1]).toContain('axes zero-based, fixed ticks')
  })

  it('says TRUNCATED, with the cap, when the row cap bit', () => {
    const lines = imageFooterLines({ ...base, truncated: true, cap: 20000 })
    expect(lines).toHaveLength(3)
    expect(lines[2]).toContain('TRUNCATED')
    expect(lines[2]).toMatch(/20.000/)
  })

  it('still says truncated when no cap came with it', () => {
    const lines = imageFooterLines({ ...base, truncated: true, cap: null })
    expect(lines[2]).toContain('TRUNCATED')
    expect(lines[2]).not.toContain('null')
  })

  it('adds no truncation line to an untruncated report', () => {
    expect(imageFooterLines(base)).toHaveLength(2)
  })

  it('omits provenance it was not given rather than printing a blank or a zero', () => {
    const [, provenance] = imageFooterLines({ project: 'P', window: 'as of now' })
    expect(provenance).not.toContain('computed')
    expect(provenance).not.toContain('based on')
    expect(provenance).toContain('axes zero-based')
  })

  it('singularises one issue', () => {
    expect(imageFooterLines({ ...base, basedOnIssues: 1 })[1]).toContain('based on 1 issue ')
  })
})

describe('formatComputedAt', () => {
  it('is UTC and says so — an image travels to other timezones by definition', () => {
    expect(formatComputedAt('2026-08-20T09:14:22Z')).toBe('2026-08-20 09:14 UTC')
  })

  it('prints an unparseable value verbatim instead of swallowing it', () => {
    expect(formatComputedAt('not-a-date')).toBe('not-a-date')
  })
})

describe('file names', () => {
  it('are dated, because the point of exporting is comparing two of them later', () => {
    const at = new Date('2026-08-20T00:00:00Z')
    expect(imageFileName('flow', 'PAY', at)).toBe('hamstrack-flow-pay-2026-08-20.png')
    expect(csvFileName('sprint-burnup', 'PAY', at)).toBe('hamstrack-sprint-burnup-pay-2026-08-20.csv')
  })

  it('survive a project with no key and an awkward one', () => {
    const at = new Date('2026-08-20T00:00:00Z')
    expect(imageFileName('velocity', null, at)).toBe('hamstrack-velocity-2026-08-20.png')
    expect(imageFileName('velocity', 'A B/C', at)).toBe('hamstrack-velocity-a-b-c-2026-08-20.png')
  })
})

describe('resolveCssVars — the trap that silently unpaints a series', () => {
  it('replaces a token with the document’s value', () => {
    const markup = '<path stroke="var(--color-chart-1)" />'
    expect(resolveCssVars(markup, name => (name === '--color-chart-1' ? '#0072B2' : '')))
      .toBe('<path stroke="#0072B2" />')
  })

  it('uses the fallback written in the markup when the document has no value', () => {
    expect(resolveCssVars('<path stroke="var(--nope, #123456)" />', () => ''))
      .toBe('<path stroke="#123456" />')
  })

  it('falls back to a VISIBLE colour, never to nothing', () => {
    // The alternative — leaving `var()` in place, or emptying the attribute —
    // removes a line from the chart while leaving it in the legend and the
    // table. That is the one failure this module may not produce.
    const out = resolveCssVars('<path stroke="var(--gone)" />', () => '')
    expect(out).toContain(IMAGE_FALLBACK_COLOR)
    expect(out).not.toContain('var(')
  })

  it('resolves a nested fallback', () => {
    const out = resolveCssVars(
      '<path stroke="var(--a, var(--b, #ABCDEF))" />',
      () => '',
    )
    expect(out).toBe('<path stroke="#ABCDEF" />')
  })

  it('leaves no var() anywhere, whatever it was handed', () => {
    const markup = '<g fill="var(--x)"><text style="fill:var(--y,var(--z))">hi</text></g>'
    expect(resolveCssVars(markup, () => '')).not.toContain('var(')
  })
})

describe('prepareSvg', () => {
  function chart(): SVGSVGElement {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    svg.setAttribute('width', '640')
    svg.setAttribute('height', '300')
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path')
    path.setAttribute('stroke', 'var(--color-chart-1)')
    path.setAttribute('d', 'M0 0 L10 10')
    svg.appendChild(path)
    return svg
  }

  it('produces standalone, var-free markup at the chart’s own size', () => {
    const { markup, width, height } = prepareSvg(chart(), () => '')
    expect(width).toBe(640)
    expect(height).toBe(300)
    expect(markup).toContain('xmlns="http://www.w3.org/2000/svg"')
    expect(markup).not.toContain('var(')
    expect(markup).toContain(IMAGE_FALLBACK_COLOR)
  })

  it('carries a font stack, since the rasterised document has no stylesheet', () => {
    expect(prepareSvg(chart(), () => '').markup).toContain('font-family')
  })

  it('does not touch the live chart — an export may not alter what it exported', () => {
    const svg = chart()
    prepareSvg(svg, () => '#0072B2')
    expect(svg.querySelector('path')?.getAttribute('stroke')).toBe('var(--color-chart-1)')
    expect(svg.querySelector('style')).toBeNull()
  })

  it('adds a viewBox when the chart has none, so the raster is not empty', () => {
    expect(prepareSvg(chart(), () => '').markup).toContain('viewBox="0 0 640 300"')
  })

  it('encodes to a data URL rather than a blob URL — same-origin, never tainting', () => {
    expect(svgDataUrl('<svg/>')).toBe('data:image/svg+xml;charset=utf-8,%3Csvg%2F%3E')
  })
})

describe('clipboard — a fallback is reported, never claimed as success', () => {
  it('returns false when the browser has no image clipboard', async () => {
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn() } })
    expect(await copyImageToClipboard(new Blob(['x']))).toBe(false)
  })

  it('returns false when the write is refused (insecure context, denied permission)', async () => {
    vi.stubGlobal('ClipboardItem', class { constructor(_: unknown) { /* shim */ } })
    vi.stubGlobal('navigator', { clipboard: { write: vi.fn(async () => { throw new Error('denied') }) } })
    expect(await copyImageToClipboard(new Blob(['x']))).toBe(false)
  })

  it('returns true when it worked', async () => {
    const write = vi.fn(async () => undefined)
    vi.stubGlobal('ClipboardItem', class { constructor(_: unknown) { /* shim */ } })
    vi.stubGlobal('navigator', { clipboard: { write } })
    expect(await copyImageToClipboard(new Blob(['x']))).toBe(true)
    expect(write).toHaveBeenCalled()
  })

  it('reports a text copy the same way', async () => {
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn(async () => undefined) } })
    expect(await copyTextToClipboard('https://example.test/x')).toBe(true)
    vi.stubGlobal('navigator', {})
    expect(await copyTextToClipboard('https://example.test/x')).toBe(false)
  })
})
