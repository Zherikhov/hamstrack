/**
 * **PNG export for report charts** (HD-141, R7 — reports-proposal §4.4, §1.6 #3).
 *
 * Nobody asks for a PNG button; they screenshot. The research is explicit about
 * that, and equally explicit about why the screenshots are worth so little:
 * competitors *"switch the elapsed-time axis scale depending on timeframe and
 * outliers"*, so two pictures of the same report are not comparable and a reader
 * learns to trust neither. Our answer is two rules, and this module implements
 * the second half of both:
 *
 *  1. **the axis is fixed** — `scale.ts`, applied by every chart, so the grid a
 *     picture is drawn against is the same grid next month's picture uses; and
 *  2. **the picture says what it is a picture of** — every image carries the
 *     project, the window and `computedAt` in its footer, because a chart pasted
 *     into a chat is separated from its URL the instant it is pasted.
 *
 * **No rendering library, in this chunk or any other.** The pipeline is native:
 * serialise the chart's own `<svg>`, rasterise it through an `Image`, and
 * compose it onto a `<canvas>` with a header and a footer band. That is also why
 * the export cannot drift from the chart — it *is* the chart, not a second
 * renderer drawing the same numbers a second way.
 *
 * Three traps this file exists to handle, each of which silently produces a
 * broken or a lying image rather than an error:
 *
 *  • **CSS custom properties do not cross the boundary.** An `<svg>` rasterised
 *    through an `Image` is an isolated document: it cannot see the page's
 *    `:root`, so `stroke="var(--color-chart-1)"` — which is exactly what every
 *    chart in this feature writes — resolves to nothing and the series is simply
 *    not painted. Every `var()` is therefore resolved to a literal before
 *    serialisation, and anything still unresolved falls back to a VISIBLE grey
 *    rather than to `none`: a wrongly-coloured line can be argued with, a
 *    missing one cannot.
 *  • **The background is opaque, deliberately.** A transparent PNG of dark text
 *    and thin dark lines is unreadable the moment it is pasted onto a dark
 *    surface — which is where these images go. The image gets the same white
 *    card the chart sits on in the app.
 *  • **The canvas may not exist.** Clipboard image writes are unavailable in
 *    some browsers and in every insecure context. Both degrade to a download
 *    with a sentence, never to a dead button.
 *
 * Everything above the canvas call is pure and tested; `renderChartImage` is the
 * one function that needs a real browser.
 */

/** Device-pixel multiplier — a report screenshot is read, and often zoomed. */
export const IMAGE_SCALE = 2

/** The exported surface. Opaque on purpose — see the header. */
export const IMAGE_BACKGROUND = '#FFFFFF'

/**
 * What an unresolvable `var()` becomes.
 *
 * The context grey from the data-visualisation palette: visible on white, and
 * never mistaken for a series colour. The alternative — dropping the attribute —
 * removes a line from a chart without removing it from the legend or the table,
 * which is the one failure this module may not produce.
 */
export const IMAGE_FALLBACK_COLOR = '#8B97A8'

const FONT_SANS = '"Inter", system-ui, -apple-system, "Segoe UI", sans-serif'
const FONT_MONO = '"IBM Plex Mono", ui-monospace, SFMono-Regular, monospace'
const INK = '#101828'
const MUTED = '#667085'
const RULE = '#E8EBF1'
const PAD = 20

/** Thrown when this browser cannot produce an image at all. */
export class ImageExportUnsupportedError extends Error {
  constructor(message = 'Image export isn’t supported in this browser.') {
    super(message)
    this.name = 'ImageExportUnsupportedError'
  }
}

/**
 * What the footer has to say — the three fields §4.4 names, plus the two
 * disclosures the report itself would be wrong to omit.
 *
 * `window` is a sentence rather than a pair of dates because not every report
 * has a date window: the aging half is a current state, velocity is a count of
 * sprints, a burn-up is one named sprint. Each page words its own, and the field
 * is required — an image whose window is a guess is exactly the artefact this
 * feature is trying to replace.
 */
export interface ImageProvenance {
  /**
   * What this image is **about** — `Payments (PAY)` for a project report, and the
   * panel plus its dataset for the ad-hoc insights chart, which has no project of
   * its own. Never a bare id: this field is the whole reason a picture separated
   * from its URL still means something.
   */
  project: string
  /** `2026-05-22 → 2026-08-20 (UTC)`, `Sprint 12 · 14–28 Aug`, `as of now`. */
  window: string
  /** `meta.computedAt`, verbatim off the response. */
  computedAt?: string | null
  /** `meta.basedOnIssues`. */
  basedOnIssues?: number | null
  /** `meta.truncated` — a truncated report says so IN THE IMAGE, not only on the page. */
  truncated?: boolean
  /** `meta.cap`, printed only when the cap actually bit. */
  cap?: number | null
}

/**
 * The footer band, one string per line.
 *
 * Line 1 is the identity: project and window. Line 2 is provenance plus the
 * axis claim — *"axes zero-based, fixed ticks"* is in the image on purpose,
 * because the whole value of the rule is that a second image can be held up
 * beside this one, and a reader who does not know the rule holds has no reason
 * to try. Line 3 exists only when the row cap bit, and it is a sentence rather
 * than a flag: an image that quietly left rows out is the "these numbers don't
 * match what I expected" failure in its most portable form.
 */
export function imageFooterLines(p: ImageProvenance): string[] {
  const lines: string[] = [`${p.project} · ${p.window}`]

  const parts: string[] = []
  if (p.computedAt) parts.push(`computed ${formatComputedAt(p.computedAt)}`)
  if (typeof p.basedOnIssues === 'number' && Number.isFinite(p.basedOnIssues)) {
    parts.push(`based on ${p.basedOnIssues.toLocaleString()} issue${p.basedOnIssues === 1 ? '' : 's'}`)
  }
  parts.push('axes zero-based, fixed ticks')
  parts.push('Hamstrack')
  lines.push(parts.join(' · '))

  if (p.truncated) {
    lines.push(
      typeof p.cap === 'number' && Number.isFinite(p.cap)
        ? `TRUNCATED — computed from the most recent ${p.cap.toLocaleString()} issues, not from every match`
        : 'TRUNCATED — the row cap bit, so this is a partial view',
    )
  }
  return lines
}

/**
 * `computedAt` as a readable UTC instant.
 *
 * UTC, not the reader's locale, and the label says so: every date in this
 * feature is a UTC date, and an image travels to readers in other timezones by
 * definition. An unparseable value is printed verbatim rather than swallowed.
 */
export function formatComputedAt(iso: string): string {
  const ms = Date.parse(iso)
  if (Number.isNaN(ms)) return iso
  return `${new Date(ms).toISOString().slice(0, 16).replace('T', ' ')} UTC`
}

/**
 * A download filename: `hamstrack-flow-PAY-2026-08-20.png`.
 *
 * Dated, because the point of exporting is comparing two of them later, and a
 * folder of `chart.png`, `chart (1).png` defeats that on its own. Anything that
 * is not a safe filename character is folded to `-`.
 */
export function imageFileName(reportSlug: string, projectKey: string | null | undefined, at: Date = new Date()): string {
  const parts = ['hamstrack', reportSlug, projectKey ?? '', at.toISOString().slice(0, 10)]
  return `${parts.filter(Boolean).map(slug).join('-')}.png`
}

/** Same rule, for the CSV the series endpoints hand back without a filename. */
export function csvFileName(reportSlug: string, projectKey: string | null | undefined, at: Date = new Date()): string {
  const parts = ['hamstrack', reportSlug, projectKey ?? '', at.toISOString().slice(0, 10)]
  return `${parts.filter(Boolean).map(slug).join('-')}.csv`
}

function slug(s: string): string {
  return s.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
}

/**
 * Resolves a CSS custom property against the live document.
 *
 * Returns `''` for anything the document does not define — including in a test
 * environment that never loaded the stylesheet, which is precisely the path
 * `resolveCssVars`' fallback exists to cover.
 */
export function cssVarResolver(root: Element | null = typeof document === 'undefined' ? null : document.documentElement) {
  const styles = root && typeof getComputedStyle === 'function' ? getComputedStyle(root) : null
  return (name: string): string => (styles?.getPropertyValue(name) ?? '').trim()
}

const VAR_RE = /var\(\s*(--[A-Za-z0-9_-]+)\s*(?:,\s*([^()]*?))?\s*\)/g

/**
 * Replace every `var(--token)` in serialised SVG with a literal colour.
 *
 * Runs to a fixed point (bounded) so a nested `var(--a, var(--b, #fff))` is
 * resolved rather than half-resolved. The precedence is: what the document says
 * → the fallback written in the markup → {@link IMAGE_FALLBACK_COLOR}. Never
 * "leave it as it is": a `var()` that survives into the rasterised document is
 * an invalid attribute value, and an invalid stroke is an unpainted series.
 */
export function resolveCssVars(markup: string, resolve: (name: string) => string): string {
  let out = markup
  for (let pass = 0; pass < 4 && VAR_RE.test(out); pass++) {
    VAR_RE.lastIndex = 0
    out = out.replace(VAR_RE, (_all, name: string, fallback?: string) => {
      const value = resolve(name)
      if (value) return value
      const written = (fallback ?? '').trim()
      if (written && !written.includes('var(')) return written
      return written || IMAGE_FALLBACK_COLOR
    })
    VAR_RE.lastIndex = 0
  }
  // A nested fallback that survived four passes still must not reach the raster.
  VAR_RE.lastIndex = 0
  return out.replace(VAR_RE, IMAGE_FALLBACK_COLOR)
}

export interface PreparedSvg {
  markup: string
  width: number
  height: number
}

/**
 * A chart's `<svg>`, ready to be rasterised: cloned, sized, var-free, and with a
 * font stack the isolated document can actually use.
 *
 * The clone is deliberate — the live chart is not touched, so an export cannot
 * leave the on-screen report subtly different from the one it exported. Size
 * comes from the laid-out element and falls back to its own attributes, which is
 * what makes this function testable without a layout engine.
 */
export function prepareSvg(svg: SVGSVGElement, resolve = cssVarResolver()): PreparedSvg {
  const rect = typeof svg.getBoundingClientRect === 'function' ? svg.getBoundingClientRect() : null
  const width = Math.round(rect?.width || Number(svg.getAttribute('width')) || 720)
  const height = Math.round(rect?.height || Number(svg.getAttribute('height')) || 320)

  const clone = svg.cloneNode(true) as SVGSVGElement
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  clone.setAttribute('xmlns:xlink', 'http://www.w3.org/1999/xlink')
  clone.setAttribute('width', String(width))
  clone.setAttribute('height', String(height))
  if (!clone.getAttribute('viewBox')) clone.setAttribute('viewBox', `0 0 ${width} ${height}`)

  // The rasterised document has no stylesheet, so the font stack has to travel
  // with it or every label falls back to a serif face nothing else in the
  // product uses.
  const style = clone.ownerDocument.createElementNS('http://www.w3.org/2000/svg', 'style')
  style.textContent = `text{font-family:${FONT_SANS};}`
  clone.insertBefore(style, clone.firstChild)

  const markup = resolveCssVars(new XMLSerializer().serializeToString(clone), resolve)
  return { markup, width, height }
}

/** Serialised SVG as a data URL — same-origin by construction, so it never taints the canvas. */
export function svgDataUrl(markup: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(markup)}`
}

export interface ChartImageOptions {
  /** The report's name — `Flow — created vs resolved`. */
  title: string
  /** One line under it, usually the measure or the sprint. Optional. */
  subtitle?: string
  /** The footer band, from {@link imageFooterLines}. */
  footerLines: string[]
  scale?: number
}

/**
 * Rasterise a chart into a PNG blob with its title above and its provenance
 * below.
 *
 * Throws {@link ImageExportUnsupportedError} rather than returning a blank blob
 * when the browser gives us no 2D context or no `toBlob` — a silent empty PNG
 * pasted into a chat is worse than a sentence saying it did not work.
 */
export async function renderChartImage(
  svg: SVGSVGElement,
  opts: ChartImageOptions,
): Promise<Blob> {
  const scale = opts.scale ?? IMAGE_SCALE
  const { markup, width, height } = prepareSvg(svg)

  const headerH = 24 + (opts.subtitle ? 18 : 0)
  const footerH = opts.footerLines.length * 15 + 16
  const totalW = width + PAD * 2
  const totalH = PAD + headerH + height + footerH + PAD

  const canvas = document.createElement('canvas')
  canvas.width = Math.round(totalW * scale)
  canvas.height = Math.round(totalH * scale)
  const ctx = canvas.getContext ? canvas.getContext('2d') : null
  if (!ctx) throw new ImageExportUnsupportedError()
  ctx.scale(scale, scale)

  ctx.fillStyle = IMAGE_BACKGROUND
  ctx.fillRect(0, 0, totalW, totalH)

  ctx.textBaseline = 'alphabetic'
  ctx.fillStyle = INK
  ctx.font = `700 15px ${FONT_SANS}`
  ctx.fillText(opts.title, PAD, PAD + 14)
  if (opts.subtitle) {
    ctx.fillStyle = MUTED
    ctx.font = `12px ${FONT_SANS}`
    ctx.fillText(opts.subtitle, PAD, PAD + 32)
  }

  const image = await loadImage(svgDataUrl(markup))
  ctx.drawImage(image, PAD, PAD + headerH, width, height)

  const ruleY = PAD + headerH + height + 8
  ctx.strokeStyle = RULE
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(PAD, ruleY + 0.5)
  ctx.lineTo(totalW - PAD, ruleY + 0.5)
  ctx.stroke()

  ctx.font = `11px ${FONT_MONO}`
  opts.footerLines.forEach((line, i) => {
    ctx.fillStyle = i === 0 ? INK : MUTED
    ctx.fillText(clipText(ctx, line, totalW - PAD * 2), PAD, ruleY + 20 + i * 15)
  })

  return toBlob(canvas)
}

/**
 * Ellipsise a line that does not fit.
 *
 * Canvas text does not wrap and does not clip — it simply draws past the edge,
 * which on an exported image means a footer that runs off into nothing. A long
 * HQL query or a long project name is ordinary, so the line is measured and cut.
 * The first footer line is the one that identifies the image, so what survives
 * is its beginning.
 */
function clipText(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string {
  if (ctx.measureText(text).width <= maxWidth) return text
  let cut = text
  while (cut.length > 1 && ctx.measureText(`${cut}…`).width > maxWidth) {
    cut = cut.slice(0, -1)
  }
  return `${cut}…`
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new ImageExportUnsupportedError('The chart could not be rasterised.'))
    img.src = src
  })
}

function toBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    if (typeof canvas.toBlob !== 'function') {
      reject(new ImageExportUnsupportedError())
      return
    }
    canvas.toBlob(blob => {
      if (blob) resolve(blob)
      else reject(new ImageExportUnsupportedError())
    }, 'image/png')
  })
}

/**
 * Copy an image to the clipboard, reporting honestly whether it worked.
 *
 * `false` is not an error — Firefox has no image clipboard write, and no browser
 * has one outside a secure context. The caller downloads instead and says so;
 * what it must never do is claim a copy that did not happen.
 */
export async function copyImageToClipboard(blob: Blob): Promise<boolean> {
  try {
    const clipboard = typeof navigator === 'undefined' ? null : navigator.clipboard
    if (!clipboard?.write || typeof ClipboardItem === 'undefined') return false
    await clipboard.write([new ClipboardItem({ 'image/png': blob })])
    return true
  } catch {
    return false
  }
}

/** Same contract, for the shareable URL. */
export async function copyTextToClipboard(text: string): Promise<boolean> {
  try {
    const clipboard = typeof navigator === 'undefined' ? null : navigator.clipboard
    if (!clipboard?.writeText) return false
    await clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

/** Save a blob under a name. The one place this feature touches the filesystem. */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
