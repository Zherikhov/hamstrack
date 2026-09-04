/**
 * **A stored colour is an identity hue, not ink (HD-176).**
 *
 * Statuses, priorities, issue types, labels and custom-field select options each
 * carry a `color` a human picked and the database stored. This module is the one
 * place that decides what to *paint* from one, and it decides it from two inputs:
 * the stored hue, and the surface the pixel actually lands on.
 *
 * The rule, stated as a category so it survives the sixth colour-carrying entity:
 *
 * > A stored colour is painted at full strength wherever it is a **fill** (dot,
 * > tile, tint, bar, chart segment) and is **dimmed until it clears
 * > {@link INK_MIN} against its surface** wherever it is **ink**. A fill carries a
 * > ring derived from the same hue at {@link FILL_MIN}. Text drawn over a solid
 * > fill of that hue is black or white, whichever measures higher.
 *
 * **Every clause of that rule has a function, including the one that does
 * nothing.** A fill is unchanged for any colour that is one, which is exactly why
 * it had no primitive for five slices and why every fill site in the app was
 * written without an import — and a site that needs no import is a site no
 * reviewer of this module ever sees. {@link fillOf} is the fill's
 * primitive: it returns the hue untouched and is worth having only because the
 * value it declines is not a hue.
 *
 * ## The derivation is closed-form, not a search
 *
 * {@link inkOn} scales all three **linear** RGB channels by one factor
 * `k = L_target / L_colour`. Scaling every channel by the same factor multiplies
 * the luminance by exactly `k` and leaves chromaticity untouched — it is the same
 * hue with less light, not a different colour. So there is no colour library, no
 * iteration over a hue wheel, and no case that fails to converge: on a light
 * surface the factor walks toward black (21:1) and on a dark one toward white.
 *
 * **Above the threshold the transform is the identity, and that is the property
 * that matters most.** A workspace that already tuned its colours to read as ink
 * gets a byte-identical render — nothing is repainted, no screenshot moves, and
 * no admin is told their choice was wrong. It is asserted in `colour.test.ts`
 * rather than described, because it is the property a future refactor breaks
 * silently: a rewrite that always recomputes still clears every ratio assertion.
 *
 * ## Derived at render time, never stored
 *
 * A stored foreground is a second source of truth that goes stale on every theme,
 * every surface and every threshold change, and correcting it would need a
 * migration. This costs a few floating-point operations and a bounded memo
 * instead, and it keeps the backend out of the visual decision entirely: no
 * column, no API field, no cache invalidation.
 *
 * ## What the module deliberately does not do
 *
 * It never *refuses* a colour. Every hue an admin can pick is still stored and
 * still painted as a fill at full strength; only the glyph moves, and only when
 * the faithful glyph could not be read. What {@link fillOf} declines is not a
 * colour at all — a value no colour picker can produce and no writing door was
 * bound to reject before HD-176 — and it declines it to a token rather than to
 * nothing. It also cannot rescue a colour used as
 * the only encoding of meaning — two admins may pick two indistinguishable hues,
 * and the name beside the swatch is what carries the meaning, exactly as
 * `DESIGN.md` already requires of charts.
 */

// ── Thresholds ────────────────────────────────────────────────────────────────

/** WCAG 1.4.3 minimum for text. Not a tuning parameter; deliberately not a configuration property. */
export const INK_MIN = 4.5

/** WCAG 1.4.11 minimum for a non-text graphic (a dot, a bar, a ring). */
export const FILL_MIN = 3

/**
 * Badge tint weight. `0x20/255` is the alpha `DESIGN.md`'s "soft tints for badges
 * use color + 18–20 alpha on light" produced when the tint was an overlay, kept
 * exactly so the change is invisible — the difference is that it is now
 * *composited by us* against a named surface instead of over whatever happened to
 * be behind the element.
 */
export const TINT_WEIGHT = 0x20 / 255

/** Badge hairline weight — the former `color + 40` border, composited the same way. */
export const EDGE_WEIGHT = 0x40 / 255

/**
 * What to paint when a colour cannot be parsed. Tokens, never literals — the
 * Beacon strategy remaps values behind stable names, so anything reading a
 * `var(--color-*)` re-skins for free.
 */
export const NEUTRAL_INK = 'var(--color-text-secondary)'
export const NEUTRAL_FILL = 'var(--color-surface-2)'
export const NEUTRAL_EDGE = 'var(--color-border)'

// ── Parsing ───────────────────────────────────────────────────────────────────

export interface Rgba {
  /** 0–255 */ r: number
  /** 0–255 */ g: number
  /** 0–255 */ b: number
  /** 0–1 */ a: number
}

const HEX = /^#(?:[0-9a-f]{6}|[0-9a-f]{8})$/i

/**
 * `#RRGGBB` / `#RRGGBBAA` → channels, anything else → `null`.
 *
 * Deliberately strict. A CSS colour name, a three-digit shorthand, a five-digit
 * typo, an empty string and a megabyte of prose are all *not colours*, and the
 * renderer's contract is that it falls back to a neutral token rather than
 * guessing what was meant. The length check comes before the pattern so a huge
 * string costs one comparison.
 */
export function parseColour(value: unknown): Rgba | null {
  if (typeof value !== 'string') return null
  const v = value.trim()
  if (v.length !== 7 && v.length !== 9) return null
  if (!HEX.test(v)) return null
  return {
    r: parseInt(v.slice(1, 3), 16),
    g: parseInt(v.slice(3, 5), 16),
    b: parseInt(v.slice(5, 7), 16),
    a: v.length === 9 ? parseInt(v.slice(7, 9), 16) / 255 : 1,
  }
}

function hex2(n: number): string {
  return Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0').toUpperCase()
}

/** Channels → `#RRGGBB`. Alpha is never emitted: everything this module returns is opaque. */
export function toHex(c: { r: number; g: number; b: number }): string {
  return `#${hex2(c.r)}${hex2(c.g)}${hex2(c.b)}`
}

/**
 * Source-over composite in **gamma (sRGB) space**, which is what a browser does
 * for an 8-digit hex background. Measuring in linear space and painting in gamma
 * space would make the number and the pixel disagree.
 */
export function compositeOver(fg: Rgba, bg: { r: number; g: number; b: number }): Rgba {
  const a = Math.max(0, Math.min(1, fg.a))
  return {
    r: fg.r * a + bg.r * (1 - a),
    g: fg.g * a + bg.g * (1 - a),
    b: fg.b * a + bg.b * (1 - a),
    a: 1,
  }
}

// ── Luminance & contrast ──────────────────────────────────────────────────────

function toLinear(channel8: number): number {
  const c = channel8 / 255
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
}

function toChannel8(linear: number): number {
  const c = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055
  return Math.max(0, Math.min(255, Math.round(c * 255)))
}

/** WCAG relative luminance, 0 (black) → 1 (white). */
export function relativeLuminance(c: { r: number; g: number; b: number }): number {
  return 0.2126 * toLinear(c.r) + 0.7152 * toLinear(c.g) + 0.0722 * toLinear(c.b)
}

function ratioOfLuminance(a: number, b: number): number {
  const hi = Math.max(a, b)
  const lo = Math.min(a, b)
  return (hi + 0.05) / (lo + 0.05)
}

/**
 * WCAG contrast ratio, 1 → 21. The first argument is composited over the second
 * when it carries alpha. An unparseable input measures `1`, never a throw: this
 * runs on a render path and on every keystroke in the colour picker.
 */
export function contrastRatio(colour: unknown, against: unknown): number {
  const bg = parseColour(against)
  const fgRaw = parseColour(colour)
  if (!bg || !fgRaw) return 1
  const fg = fgRaw.a < 1 ? compositeOver(fgRaw, bg) : fgRaw
  return ratioOfLuminance(relativeLuminance(fg), relativeLuminance(bg))
}

// ── Surfaces ──────────────────────────────────────────────────────────────────

/**
 * The design tokens this module resolves to real channels, and the values
 * `index.css` gives them.
 *
 * This map is **not** a second palette: {@link token} reads the live custom
 * property first and only falls back here when no stylesheet is attached to the
 * document — the unit-test environment, where the CSS pipeline is deliberately
 * switched off (`vitest.config.ts`, `css: false`). `colour.test.ts` parses
 * `index.css` and asserts every entry below still agrees with it, so the fallback
 * cannot drift into a palette of its own.
 *
 * Exported **only** so that test can iterate it. Nothing in the app reads it: a
 * component asks {@link token}, which prefers the live value, so a theme that
 * remaps `index.css` re-skins the app without touching this file.
 */
export const TOKEN_FALLBACK: Record<string, string> = {
  '--color-card': '#FFFFFF',
  '--color-surface': '#F3F5F9',
  '--color-surface-2': '#EEF1F6',
  '--color-ink': '#101828',
  '--color-brand': '#0EA5A4',
  '--color-pending': '#F79009',
  '--color-sandbox': '#667085',
  '--color-success': '#12B981',
}

const tokenCache = new Map<string, string>()

/**
 * `--color-card` → `#FFFFFF`, read from the document when there is one.
 *
 * Resolution is cached because a custom property does not change while the app
 * runs; {@link resetColourCache} exists for the test that proves the fallback and
 * the live read agree, and for a future theme switch.
 */
export function token(name: string): string {
  const cached = tokenCache.get(name)
  if (cached !== undefined) return cached
  let value = ''
  if (typeof document !== 'undefined' && typeof getComputedStyle === 'function') {
    try {
      value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
    } catch {
      value = ''
    }
  }
  const resolved = parseColour(value) ? value.toUpperCase() : (TOKEN_FALLBACK[name] ?? '#FFFFFF')
  tokenCache.set(name, resolved)
  return resolved
}

/**
 * The surfaces a glyph in this app can land on. **The surface is an argument,
 * never an assumption** — the same hue needs a different factor on a white card,
 * on a tinted row and on the dark rail, which is also what makes a dark theme a
 * new argument here rather than a re-litigation of this decision.
 */
export const SURFACE = {
  /** White card / panel — the default reading surface. */
  get card() { return token('--color-card') },
  /** The slate app canvas. */
  get canvas() { return token('--color-surface') },
  /** Subtle raised / hover fill. */
  get raised() { return token('--color-surface-2') },
  /** The dark navigation rail. */
  get rail() { return token('--color-ink') },
  /**
   * A row that tints on hover: the darkest state it can take, so the ratio holds
   * in every state rather than only in the one that was screenshotted.
   */
  get row() { return darkest(token('--color-card'), token('--color-surface'), token('--color-surface-2')) },
}

/** The lowest-luminance of the given surfaces — what a hoverable element measures against. */
export function darkest(...surfaces: string[]): string {
  let best = surfaces[0] ?? SURFACE.card
  let bestL = Number.POSITIVE_INFINITY
  for (const s of surfaces) {
    const parsed = parseColour(s)
    if (!parsed) continue
    const l = relativeLuminance(parsed)
    if (l < bestL) { bestL = l; best = s }
  }
  return best
}

/**
 * The stored hue exactly as it will be written into a declaration, or `null`.
 *
 * Two jobs, and each one is a bug this module used to have:
 *
 * 1. **It is the gate every value passes before it reaches CSS.** `parseColour`
 *    returns *channels*, which is what a measurement needs and not what a
 *    declaration takes, so every caller that wanted to paint the hue itself
 *    reached past the module for the original string. That is the whole of
 *    {@link fillOf}'s reason to exist.
 * 2. **It returns the value that was actually matched — trimmed.** `parseColour`
 *    trims before testing, so `" #FF0000"` parses while the untrimmed original
 *    does not necessarily *serialise*: `String.prototype.trim` also strips NBSP
 *    and the BOM, neither of which a CSS tokenizer treats as whitespace, so a
 *    legacy value carrying one would round-trip out of here and invalidate the
 *    declaration it landed in. Handing back the matched form closes that.
 *
 * A fully transparent hue is **absent, not a colour**, the same reading
 * {@link derive} and {@link tintOf} already take: an invisible fill under a
 * neutral ring is a worse answer than a neutral fill under one.
 */
function storedHue(value: unknown): string | null {
  const parsed = parseColour(value)
  if (!parsed || parsed.a === 0) return null
  return (value as string).trim()
}

/**
 * **The fill primitive: what to paint as a `background` from a stored colour.**
 *
 * The identity hue at full strength when it is one, and `neutral` when it is not.
 * Nothing about the pixel changes for any colour an admin could have picked —
 * this is not a dimming step, and a fill is deliberately never dimmed.
 *
 * It exists because a fill was the one thing this module had no primitive for,
 * and "paint the stored string" needs no import: every fill site in the app
 * therefore bypassed the module entirely, and the values it declines are exactly
 * the ones no writing door has ever had to refuse. A custom field's
 * `config.options[].color` is JSONB with no column width and no bean validation
 * before HD-176, and the migration that added the 422 deliberately leaves
 * pre-existing rows as they are — so on an upgraded instance a stored
 * `url(https://…)` still arrives, `background` still accepts it as a
 * `background-image`, and every project member's browser fetches it. The colour
 * that survives that is the one this function returns; everything else becomes a
 * token.
 *
 * The neutral is a **token**, so a re-skin carries it, and the caller may name a
 * different one when the surface demands it (a chart's context grey, a category's
 * own hue) — it is a fallback, never a second palette.
 */
export function fillOf(colour: unknown, neutral: string = NEUTRAL_FILL): string {
  return storedHue(colour) ?? neutral
}

// ── The memo ──────────────────────────────────────────────────────────────────

const MEMO_CAP = 512
const memo = new Map<string, string>()

function memoised(key: string, compute: () => string): string {
  const hit = memo.get(key)
  if (hit !== undefined) return hit
  const value = compute()
  // Overflow drops the whole map rather than evicting one entry: colour sets are
  // tiny in practice, and a workspace with thousands of labels must not grow a
  // cache without a ceiling.
  if (memo.size >= MEMO_CAP) memo.clear()
  memo.set(key, value)
  return value
}

/** Drops the memo and the token resolution. For tests, and for a future theme switch. */
export function resetColourCache(): void {
  memo.clear()
  tokenCache.clear()
}

// ── The derivation ────────────────────────────────────────────────────────────

/** Black or white against a known surface luminance — the guaranteed floor, ≥ 4.58:1. */
function solidFallback(surfaceLuminance: number): string {
  const black = ratioOfLuminance(0, surfaceLuminance)
  const white = ratioOfLuminance(1, surfaceLuminance)
  return black >= white ? '#000000' : '#FFFFFF'
}

/**
 * The whole of the derivation: return `raw` untouched if it already clears
 * `minRatio` against `surface`, otherwise the same hue with its linear channels
 * scaled by one factor until it does.
 *
 * Two details are load-bearing:
 *
 * 1. **Direction is decided by the surface, not by the colour.** Darkening is
 *    reachable exactly when `(L_surface + 0.05) / ratio − 0.05 > 0`; below that
 *    the surface is dark enough that only lightening can reach the target, and
 *    lightening is refused when it would clip a channel — clipping changes the
 *    chromaticity, which is the one thing this function promises not to do.
 * 2. **Rounding is checked, not assumed.** The factor is computed in continuous
 *    linear space and then quantised to 8 bits, and the quantised value can land
 *    just under the threshold — `#7B7B00` measures 4.49 on white where `#7A7A00`
 *    measures 4.55. So the result is re-measured after encoding and stepped one
 *    further notch until it clears. A guarantee that depends on which side of a
 *    rounding boundary a value falls is a flaky test with a delayed fuse.
 */
function derive(raw: unknown, surface: string, minRatio: number, neutral: string): string {
  const parsed = parseColour(raw)
  const surf = parseColour(surface)
  if (!parsed || !surf) return neutral
  if (parsed.a === 0) return neutral // fully transparent is absent, not a colour

  const effective = parsed.a < 1 ? compositeOver(parsed, surf) : parsed
  const surfaceL = relativeLuminance(surf)
  const colourL = relativeLuminance(effective)

  if (ratioOfLuminance(colourL, surfaceL) >= minRatio) {
    // Identity. Byte-identical for an opaque input — an acceptance criterion,
    // not a nicety. `trim()` because the match was made on the trimmed form: the
    // untrimmed original can carry an NBSP or a BOM, which JS strips and a CSS
    // tokenizer does not, so returning it would invalidate the declaration.
    return parsed.a < 1 ? toHex(effective) : (raw as string).trim()
  }

  const lin = {
    r: toLinear(effective.r),
    g: toLinear(effective.g),
    b: toLinear(effective.b),
  }
  const maxLin = Math.max(lin.r, lin.g, lin.b)
  const darkTarget = (surfaceL + 0.05) / minRatio - 0.05
  const lightTarget = minRatio * (surfaceL + 0.05) - 0.05

  let k: number
  let step: number
  if (darkTarget > 0 && colourL > 0) {
    k = darkTarget / colourL
    step = 0.98
  } else if (colourL > 0 && maxLin > 0 && lightTarget <= 1 && (lightTarget / colourL) * maxLin <= 1) {
    k = lightTarget / colourL
    step = 1.02
  } else {
    return solidFallback(surfaceL)
  }

  for (let i = 0; i < 64; i++) {
    if (step > 1 && k * maxLin > 1) break
    const candidate = {
      r: toChannel8(lin.r * k),
      g: toChannel8(lin.g * k),
      b: toChannel8(lin.b * k),
    }
    if (ratioOfLuminance(relativeLuminance(candidate), surfaceL) >= minRatio) return toHex(candidate)
    k *= step
  }
  return solidFallback(surfaceL)
}

/**
 * The colour to paint a **glyph** in: the stored hue if it can be read on
 * `surface`, otherwise the same hue dimmed until it clears {@link INK_MIN}.
 *
 * Yellow `#FFFF00` on a white card comes back `#7A7A00` — beside a `#FFFF00`
 * dot, which is the point: the identity is still on screen at full strength, and
 * only the text moved.
 */
export function inkOn(colour: unknown, surface: string = SURFACE.card, neutral: string = NEUTRAL_INK): string {
  // The guard comes BEFORE the memo, so the key is a hue and never an arbitrary
  // string: keying on the raw input let an unparseable 20 KB option colour become
  // a 20 KB `Map` key, and the cap bounds the entry count, not the bytes.
  const hue = storedHue(colour)
  if (hue === null) return neutral
  return memoised(`ink|${hue}|${surface}|${neutral}`, () => derive(hue, surface, INK_MIN, neutral))
}

/**
 * The hairline for a **fill**: the same hue at {@link FILL_MIN}, so a pale dot
 * keeps a visible edge against the surface it sits on (WCAG 1.4.11). The fill
 * itself is never dimmed — it is the identity, at full strength.
 */
export function ringOn(colour: unknown, surface: string = SURFACE.card, neutral: string = NEUTRAL_EDGE): string {
  const hue = storedHue(colour)
  if (hue === null) return neutral
  return memoised(`ring|${hue}|${surface}|${neutral}`, () => derive(hue, surface, FILL_MIN, neutral))
}

/**
 * A soft opaque tint of `colour` over `surface` — the badge background, and the
 * badge hairline at {@link EDGE_WEIGHT}.
 *
 * Opaque on purpose. An alpha overlay composites over *whatever happens to be
 * behind the element*, so the tint over a hovered row was never the background
 * anybody measured; the composited hex is what we measured, so it is what we
 * paint, and the ink derived against it then holds in every row state.
 */
export function tintOf(colour: unknown, surface: string = SURFACE.card, weight: number = TINT_WEIGHT): string {
  const hue = storedHue(colour)
  if (hue === null) return NEUTRAL_FILL
  return memoised(`tint|${hue}|${surface}|${weight}`, () => {
    const parsed = parseColour(hue)
    const surf = parseColour(surface)
    if (!parsed || !surf) return NEUTRAL_FILL
    const effective = parsed.a < 1 ? compositeOver(parsed, surf) : parsed
    return toHex(compositeOver({ ...effective, a: weight }, surf))
  })
}

/**
 * Ink for text drawn **on top of a solid fill of the hue itself** — Home's issue
 * type tile, the one place the design deliberately uses a solid form.
 *
 * Black or white, whichever measures higher. Every possible fill luminance
 * satisfies at least one of them (black clears 4.5:1 above L = 0.175, white below
 * L = 0.183, and the intervals overlap), so the floor is 4.58:1 for any colour
 * that exists — which is why this is the one place a solid chip is safe without
 * asking anything of the picker. The tile shipped white ink unconditionally and
 * measured 1.92 on the seeded Medium yellow, which is what this replaces.
 */
export function onSolid(colour: unknown, neutral: string = NEUTRAL_INK): string {
  const hue = storedHue(colour)
  if (hue === null) return neutral
  return memoised(`solid|${hue}|${neutral}`, () => {
    const parsed = parseColour(hue)
    if (!parsed) return neutral
    const card = parseColour(SURFACE.card)
    const effective = parsed.a < 1 && card ? compositeOver(parsed, card) : parsed
    return solidFallback(relativeLuminance(effective))
  })
}
