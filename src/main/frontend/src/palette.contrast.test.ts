/// <reference types="vite/client" />
import { describe, it, expect } from 'vitest'
// The stylesheet as TEXT, never as a stylesheet — the same trick `colour.test.ts`
// uses, and the reason `vitest.config.ts` excuses exactly this one file from
// `css: false`. There is deliberately not a second mechanism for reading it.
import INDEX_CSS from './index.css?raw'
import { contrastRatio, parseColour, relativeLuminance } from './colour'

/**
 * **The seal on HD-175's declared palette.**
 *
 * This file asserts one thing and refuses a second.
 *
 * What it asserts: **no declared token value is illegal on a surface it is
 * declared for.** Every `--color-*` in `index.css` is classified here as ink, fill,
 * surface or non-text, an ink names the surfaces it may land on, and the ratio is
 * measured with the same arithmetic the product renders with.
 *
 * What it refuses: **it never calls `inkOn` on a token** (ADR-0029). Deriving the
 * palette at runtime is the tempting one-rule-everywhere answer, and it would hide
 * a palette bug behind a computation, make every screenshot depend on an algorithm
 * and put the design system beyond the reach of a diff. A colour that comes from
 * the database is derived at render time because nobody can review it in advance; a
 * colour that comes from the stylesheet is decided at design time and asserted
 * here, because everybody can. `colour.test.ts` owns the other side of that seam
 * and must not start asserting a token's design value.
 *
 * **What it cannot prove, and why the browser harness exists anyway:** which token
 * any given *element* uses, alpha composited from ancestors, gradients, per-element
 * size and weight, and any hardcoded hex in a component. Those need a DOM.
 * `npm run audit:contrast` (see `audit/`) covers them. Both are required; neither
 * replaces the other, and a green run here is not a clean audit.
 *
 * **This suite runs on no automated path.** CI executes exactly one command,
 * `./mvnw -B verify`, whose frontend executions are `npm ci` and `npm run build` —
 * nothing invokes `npm test` (HD-242, already recorded in `colour.test.ts`). So
 * what is written here protects a reviewer and a local run, not a merge, and
 * "asserted" means "the assertion exists", not "the assertion ran".
 */

// ── The declared values, read from the stylesheet ──────────────────────────────

/** `--color-x: #RRGGBB;` / `#RRGGBBAA` declarations inside the `@theme` block. */
function declaredTokens(css: string): Record<string, string> {
  const out: Record<string, string> = {}
  const re = /(--color-[a-z0-9-]+)\s*:\s*(#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?)\s*;/g
  let m = re.exec(css)
  while (m !== null) {
    out[m[1]] = m[2].toUpperCase()
    m = re.exec(css)
  }
  return out
}

const TOKENS = declaredTokens(INDEX_CSS)

// ── The classification ────────────────────────────────────────────────────────

/**
 * A surface an ink is allowed to land on: either another token, or a **tint of a
 * token** — `color-mix(in srgb, <hue> N%, white)`, which is what a badge paints
 * under its own label.
 *
 * The tints are not decoration in this map; they are the reason four of the values
 * in `index.css` are what they are. A semantic hue's 14% tint is **darker** than
 * `--color-surface-2`, so an ink measured only against the raised surface passes
 * here and fails inside the badge it was introduced for.
 */
type Surface = string | { tintOf: string; pct: number }

type Role =
  /** Painted as text or as a meaningful glyph. Must clear 4.5:1 on every surface listed. */
  | { role: 'ink'; on: Surface[] }
  /** Painted as a background/fill. Never text. */
  | { role: 'fill' }
  /** A background that text lands on. */
  | { role: 'surface' }
  /** Neither text nor a surface text lands on: a hairline, a grid line, a chart series. */
  | { role: 'non-text'; why: string }

const LIGHT: Surface[] = ['--color-card', '--color-surface', '--color-surface-2']
const DARK: Surface[] = ['--color-ink', '--color-ink-menu']

/**
 * The 14% mix is the darkest tint any token hue is painted at anywhere in the
 * app — a bound on every site, deliberately not a list of them, because a named
 * site goes stale one call site before the claim does. `ToneBadge` paints here
 * too, which is the point: a semantic badge's label sits on this surface, so this
 * is where its pair has to hold.
 */
const ownTint = (hue: string): Surface => ({ tintOf: hue, pct: 14 })

const CLASSIFICATION: Record<string, Role> = {
  // ── surfaces ────────────────────────────────────────────────────────────────
  '--color-card': { role: 'surface' },
  '--color-surface': { role: 'surface' },
  '--color-surface-2': { role: 'surface' },
  '--color-ink': { role: 'surface' },
  '--color-ink-menu': { role: 'surface' },
  '--color-ink-blue-deep': { role: 'surface' },

  // ── neutral inks ────────────────────────────────────────────────────────────
  '--color-text': { role: 'ink', on: LIGHT },
  '--color-text-secondary': { role: 'ink', on: LIGHT },
  '--color-text-muted': { role: 'ink', on: LIGHT },
  '--color-rail-text': { role: 'ink', on: DARK },
  // The rail's binding surface is the LIGHTER of the two dark ones. Listing both
  // is the whole point: measuring rail ink against `--color-ink` alone is exactly
  // how #6B7688 (3.86 on the rail, 3.34 in the menu) shipped.
  '--color-rail-muted': { role: 'ink', on: DARK },
  '--color-rail-active': { role: 'ink', on: DARK },

  // ── semantic inks ───────────────────────────────────────────────────────────
  '--color-brand-ink': { role: 'ink', on: [...LIGHT, ownTint('--color-brand')] },
  '--color-error-ink': { role: 'ink', on: [...LIGHT, ownTint('--color-error')] },
  '--color-warning-ink': { role: 'ink', on: [...LIGHT, ownTint('--color-warning')] },
  '--color-success-ink': { role: 'ink', on: [...LIGHT, ownTint('--color-success')] },
  // Dual role, and deliberately NOT split: the ink form of this slate is a 1.06:1
  // move from its fill form — invisible — so the token was nudged in place instead
  // of gaining a sibling nobody could tell apart. A sibling is worth declaring
  // only when the two values are visibly different.
  '--color-sandbox': { role: 'ink', on: [...LIGHT, ownTint('--color-sandbox')] },
  '--color-info': { role: 'ink', on: [...LIGHT, ownTint('--color-info')] },
  // Ink for text drawn ON a solid brand fill — both ends of the brand gradient.
  '--color-on-brand': { role: 'ink', on: ['--color-brand', '--color-accent-2'] },
  // Axis ticks and labels are read, and a chart is drawn on a white card.
  '--color-chart-axis': { role: 'ink', on: ['--color-card'] },

  // ── fills ───────────────────────────────────────────────────────────────────
  '--color-brand': { role: 'fill' },
  '--color-brand-hover': { role: 'fill' },
  '--color-accent-2': { role: 'fill' },
  '--color-pending': { role: 'fill' },
  '--color-success': { role: 'fill' },
  '--color-warning': { role: 'fill' },
  '--color-error': { role: 'fill' },

  // ── neither ─────────────────────────────────────────────────────────────────
  '--color-border': { role: 'non-text', why: 'hairline; WCAG 1.4.11 on borders is a separate follow-up ticket' },
  '--color-border-2': { role: 'non-text', why: 'hairline; same follow-up' },
  '--color-ink-blue': { role: 'non-text', why: 'legacy translucent rail-depth overlay, never text' },
  '--color-chart-1': { role: 'non-text', why: 'series hue: aria-hidden chart over a real table' },
  '--color-chart-2': { role: 'non-text', why: 'series hue' },
  '--color-chart-3': { role: 'non-text', why: 'series hue' },
  '--color-chart-4': { role: 'non-text', why: 'series hue' },
  '--color-chart-5': { role: 'non-text', why: 'series hue; 2.31 on white and deliberately left alone' },
  '--color-chart-context': { role: 'non-text', why: 'series hue; 2.96 on white and deliberately left alone' },
  '--color-chart-grid': { role: 'non-text', why: 'grid line (= --color-border)' },
}

/**
 * Solid fills that carry text, and the ink each one carries. Every pair here is a
 * real component: a `Button` variant, the rail's New-issue button, the landing
 * page's primary CTA.
 */
const SOLID_PAIRS: { fill: string; ink: string; where: string }[] = [
  { fill: '--color-brand', ink: '--color-on-brand', where: 'Button primary · Avatar · NavRail New issue · .lp-btn-primary' },
  { fill: '--color-accent-2', ink: '--color-on-brand', where: 'the light end of every brand gradient' },
  // The one deliberate override of black-or-white (OQ4): the danger button paints
  // its fill from the hue's INK value and keeps white text, because a red button
  // with black text reads as a hazard sign rather than a destructive action.
  { fill: '--color-error-ink', ink: 'literal:#FFFFFF', where: 'Button danger' },
]

/** `-ink` siblings, which must still be their fill's hue. */
const SIBLINGS: [fill: string, ink: string][] = [
  ['--color-brand', '--color-brand-ink'],
  ['--color-error', '--color-error-ink'],
  ['--color-warning', '--color-warning-ink'],
  ['--color-success', '--color-success-ink'],
]

const INK_MIN = 4.5

// ── Helpers ───────────────────────────────────────────────────────────────────

function hex(name: string): string {
  if (name.startsWith('literal:')) return name.slice('literal:'.length)
  const v = TOKENS[name]
  if (!v) throw new Error(`${name} is not declared in index.css`)
  return v
}

/** `color-mix(in srgb, <hue> pct%, white)` — CSS mixes `srgb` gamma-encoded. */
function tint(hue: string, pct: number): string {
  const c = parseColour(hex(hue))!
  const t = pct / 100
  const ch = (v: number) => Math.round(v * t + 255 * (1 - t)).toString(16).padStart(2, '0').toUpperCase()
  return `#${ch(c.r)}${ch(c.g)}${ch(c.b)}`
}

function surfaceHex(s: Surface): string {
  return typeof s === 'string' ? hex(s) : tint(s.tintOf, s.pct)
}

function surfaceName(s: Surface): string {
  return typeof s === 'string' ? s : `${s.pct}% tint of ${s.tintOf}`
}

/** Linear-light chromaticity coordinates — invariant under a one-factor dim. */
function chromaticity(colour: string): [number, number, number] {
  const c = parseColour(colour)!
  const lin = (v: number) => {
    const x = v / 255
    return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4)
  }
  const r = lin(c.r), g = lin(c.g), b = lin(c.b)
  const sum = r + g + b
  return sum === 0 ? [0, 0, 0] : [r / sum, g / sum, b / sum]
}

// ── 1. The classification covers the stylesheet, and vice versa ───────────────

describe('the token classification', () => {
  it('names every --color-* declared in index.css', () => {
    expect(INDEX_CSS, 'index.css did not resolve — this assertion is running on an empty string')
      .toBeTruthy()
    const undeclared = Object.keys(TOKENS).filter((t) => !(t in CLASSIFICATION))
    expect(
      undeclared,
      'a new token was added to index.css without being classified here, so nothing measured it',
    ).toEqual([])
  })

  it('names nothing that index.css does not declare', () => {
    const stale = Object.keys(CLASSIFICATION).filter((t) => !(t in TOKENS))
    expect(stale, 'this map still classifies a token that no longer exists').toEqual([])
  })

  it('classifies enough tokens that an emptied map cannot pass everything', () => {
    // The tripwire, in the shape `colour.test.ts` and `RequestFieldLengthBoundTest`
    // both use: the assertions above are all "for each", and "each of none" is true.
    expect(Object.keys(CLASSIFICATION).length).toBeGreaterThan(30)
    expect(Object.values(CLASSIFICATION).filter((c) => c.role === 'ink').length).toBeGreaterThan(12)
  })
})

// ── 2. Every ink clears 4.5:1 on every surface it is declared for ─────────────

describe('ink tokens', () => {
  const inks = Object.entries(CLASSIFICATION)
    .filter((e): e is [string, Extract<Role, { role: 'ink' }>] => e[1].role === 'ink')

  for (const [name, spec] of inks) {
    for (const surface of spec.on) {
      it(`${name} clears ${INK_MIN}:1 on ${surfaceName(surface)}`, () => {
        const ratio = contrastRatio(hex(name), surfaceHex(surface))
        expect(
          ratio,
          `${name} ${hex(name)} on ${surfaceName(surface)} ${surfaceHex(surface)} measures ${ratio.toFixed(2)}`,
        ).toBeGreaterThanOrEqual(INK_MIN)
      })
    }
  }
})

// ── 3. Every solid fill carries its declared ink ──────────────────────────────

describe('solid fills', () => {
  for (const pair of SOLID_PAIRS) {
    it(`${pair.fill} carries ${pair.ink} (${pair.where})`, () => {
      const ratio = contrastRatio(hex(pair.ink), hex(pair.fill))
      expect(ratio, `measures ${ratio.toFixed(2)}`).toBeGreaterThanOrEqual(INK_MIN)
    })
  }

  /**
   * **A trap, asserted as one.** `--color-brand-hover` is declared and used
   * nowhere, so nothing carries text on it today — and neither ink that could
   * clears: white measures 3.87 and `--color-on-brand` 4.36. Whoever adds the
   * hover state this token exists for will reach for one of them. This test says
   * so out loud, and turns green into a signal rather than into silence: if the
   * value is ever changed so that an ink does clear, this assertion fails and its
   * message is the instruction.
   */
  it('--color-brand-hover still carries NO legible ink, so a hover state cannot be built from it as declared', () => {
    const white = contrastRatio('#FFFFFF', hex('--color-brand-hover'))
    const onBrand = contrastRatio(hex('--color-on-brand'), hex('--color-brand-hover'))
    expect(Math.max(white, onBrand)).toBeLessThan(INK_MIN)
  })
})

// ── 4. An -ink sibling is its fill's hue, dimmed — not a different colour ─────

describe('the -ink siblings', () => {
  for (const [fill, ink] of SIBLINGS) {
    it(`${ink} is ${fill} with less light, not another colour`, () => {
      const a = chromaticity(hex(fill))
      const b = chromaticity(hex(ink))
      // Linear-light chromaticity is exactly invariant under the one-factor scale
      // `colour.ts` applies; the tolerance is 8-bit quantisation, nothing else.
      for (let i = 0; i < 3; i++) expect(Math.abs(a[i] - b[i])).toBeLessThan(0.02)
    })

    it(`${ink} is darker than ${fill}`, () => {
      expect(relativeLuminance(parseColour(hex(ink))!))
        .toBeLessThan(relativeLuminance(parseColour(hex(fill))!))
    })
  }
})

// ── 5. The two collapses, asserted so restoring a third level has to argue ────

describe('two ink levels below primary, not three (ADR-0028)', () => {
  it('--color-text-muted holds --color-text-secondary’s value', () => {
    // Not a tidy-up: the entire legal band between them is 1.13:1 wide. A third
    // value here is a distinction no reader can see and every future contributor
    // will try to preserve. If this ever needs to change, it needs the arithmetic
    // in ADR-0028 to have changed with it.
    expect(hex('--color-text-muted')).toBe(hex('--color-text-secondary'))
  })

  it('--color-rail-muted holds --color-rail-text’s value', () => {
    expect(hex('--color-rail-muted')).toBe(hex('--color-rail-text'))
  })

  it('the chart ramp is untouched, including the two values that would fail if they were text', () => {
    // Recorded by value because the temptation is to "fix" them. They are never
    // text: a series hue appears only inside an aria-hidden chart and on its
    // aria-hidden legend swatch, over a real <table> carrying the same numbers.
    expect(hex('--color-chart-5')).toBe('#56B4E9')
    expect(hex('--color-chart-context')).toBe('#8B97A8')
    expect(contrastRatio(hex('--color-chart-5'), hex('--color-card'))).toBeLessThan(3)
  })
})

// ── 6. The per-site override tripwire ─────────────────────────────────────────

/**
 * Component source, read through Vite rather than through `node:fs` so the test
 * has no opinion about the working directory.
 */
const COMPONENT_SOURCES = import.meta.glob('./{components,pages}/**/*.tsx', {
  query: '?raw', eager: true, import: 'default',
}) as Record<string, string>

describe('per-site overrides', () => {
  /**
   * **The number this ticket ships with.** A per-site override — a literal hex,
   * where a token could have carried the decision — is the escape hatch HD-175's
   * AC allows as a last resort, and an escape hatch nobody counts is a doorway.
   * The count may go DOWN freely; going up means someone painted a colour the
   * palette cannot re-skin, and they should have to say so here rather than
   * append silently.
   *
   * Most of the 68 predate this ticket (an admin banner's amber-on-cream pair, the
   * colour-picker's own swatch palette, the landing page's mock catalogue). HD-175
   * removed six: five `#04211f` inks, now `--color-on-brand`, and a bespoke
   * drop-target tint on the board.
   */
  const DECLARED_OVERRIDES = 68

  function countHexLiterals(source: string): number {
    const code = source
      .split('\n')
      .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l)) // a documented ratio is not a paint
      .join('\n')
    return (code.match(/#[0-9a-fA-F]{6}\b/g) ?? []).length
  }

  it('does not grow past the number declared here', () => {
    const files = Object.entries(COMPONENT_SOURCES).filter(([p]) => !p.endsWith('.test.tsx'))
    expect(files.length, 'the glob matched nothing — this assertion would pass on zero files')
      .toBeGreaterThan(60)
    const total = files.reduce((sum, [, src]) => sum + countHexLiterals(src), 0)
    expect(
      total,
      'a new raw hex appeared in a component. If a token exists, use it; if the ' +
      'override is deliberate, justify it in a comment at the site and raise this number.',
    ).toBeLessThanOrEqual(DECLARED_OVERRIDES)
  })

  it('has no #04211f left anywhere: the on-fill ink decision is one literal', () => {
    // Both surfaces (the product button and the landing page) now read the same
    // token, which is the whole of AC#8.
    const offenders = Object.entries(COMPONENT_SOURCES)
      .filter(([, src]) => /#04211f/i.test(src))
      .map(([p]) => p)
    expect(offenders).toEqual([])
    expect((INDEX_CSS.match(/#04211f/gi) ?? []).length, 'declared once, in @theme').toBe(1)
  })
})

// ── 7. The seam, scanned from this side ───────────────────────────────────────

/**
 * **`colour.test.ts` guards the seam in one direction; this guards the other.**
 *
 * There, `RAW_INK` / `RAW_FILL` catch a *stored* hue that skipped the derivation.
 * Here: a *stylesheet token* handed to a parameter that means "a hue that came
 * from the database". Both are the same mistake — a colour on the wrong side of
 * ADR-0029 — and only one of the two directions had a scan.
 *
 * The direction this one catches is the quieter, because it fails **downward into
 * something that still renders**. `AdminUsersPage` passed `'var(--color-success)'`
 * to `Badge`: the tint and the hairline were built as `var(--color-success)20` and
 * `…40`, which no browser parses, so both were thrown away and what was left
 * looked deliberate. It stayed that way until HD-176 derived the label too and the
 * last third went grey. A wrong mechanism whose output is plausible is not found
 * by looking at it.
 *
 * **What it cannot see — and nothing else sees it either.** The same indirection
 * blind spot `RAW_INK` records: a token parked in a `const` or in a lookup table
 * (`color={STATUS_COLOR[u.status]}`) reaches the prop under a name, and that is
 * how three of the four badges in the historical bug were actually written. So the
 * shape that caused it is the shape this regex misses. That was checked rather than
 * assumed — the lookup-table version was reinstated verbatim and the whole suite
 * stayed green — every test in every file, nothing to report. Nor do the DOM tests
 * catch it: `ui.contrast.test.tsx` pins the *component's behaviour*, never a *call
 * site*, and no assertion anywhere reads the arguments a page passes. **This half is
 * not detected, and it is not "pinned behaviourally instead".**
 *
 * What the DOM tests do buy is narrower, and real. A wrong call site now fails
 * **uniformly**: `Badge` rejects a token in all three of tint, hairline and label,
 * so the badge goes plainly neutral instead of the two-thirds-broken render that
 * still looked deliberate — being partly right is what let the original live in
 * `AdminUsersPage` until HD-176 turned the last third grey. And the correct
 * mechanism now exists: `ToneBadge` takes a declared fill/ink pair, so the fix at a
 * call site is a component swap and not a hex. Uniform failure plus an available
 * remedy — not detection.
 *
 * Widening the regex to bare identifiers would fire on every legitimate
 * `color={c}` and would still not follow a table into another module. A scan that
 * cannot honestly do that says so where it is defined, rather than implying it away.
 */
const TOKEN_INTO_DERIVED: { what: string; re: RegExp }[] = [
  {
    what: 'a `color` prop whose contract is "a hue from the database"',
    re: /<(?:Badge|StatusBadge|ParentChip)\b[^>]*?\bcolor=(?:\{[^}]*|["'])var\(--/,
  },
  {
    what: 'a derivation primitive called ON a token (first argument)',
    // First argument only: the LAST argument of inkOn/ringOn/fillOf is the
    // neutral, which is a token on purpose so it re-skins with the palette.
    re: /\b(?:inkOn|tintOf|fillOf|ringOn|onSolid)\(\s*["'`]var\(--/,
  },
]

describe('a stylesheet token never enters the render-time path', () => {
  it('fires on the shape it exists to catch — otherwise it proves nothing', () => {
    // The two historical offences, verbatim in shape. A tripwire nobody has ever
    // seen trip is a tripwire nobody knows is connected.
    const admin = `<Badge label={ROLE_LABEL[u.systemRole]}
                         color={u.systemRole === 'ADMIN' ? 'var(--color-brand)' : undefined} />`
    expect(TOKEN_INTO_DERIVED.some(({ re }) => re.test(admin))).toBe(true)
    expect(TOKEN_INTO_DERIVED.some(({ re }) => re.test("inkOn('var(--color-success)', SURFACE.card)"))).toBe(true)

    // …and stays quiet on the legitimate neighbours, or its green is worth
    // nothing: a token as the NEUTRAL argument, and a token in plain CSS.
    expect(TOKEN_INTO_DERIVED.some(({ re }) => re.test("ringOn(o.color, SURFACE.card, 'var(--color-brand)')"))).toBe(false)
    expect(TOKEN_INTO_DERIVED.some(({ re }) => re.test('<StateChip color="var(--color-pending)" />'))).toBe(false)
  })

  it('finds none in the components', () => {
    const offenders: string[] = []
    for (const [id, source] of Object.entries(COMPONENT_SOURCES)) {
      if (id.endsWith('.test.tsx')) continue
      for (const { what, re } of TOKEN_INTO_DERIVED) {
        const hit = re.exec(source)
        if (hit) offenders.push(`${id}: ${what} — ${hit[0].replace(/\s+/g, ' ')}`)
      }
    }
    expect(
      offenders,
      'a var(--color-*) token reached a parameter that parses and MEASURES its argument. ' +
      'It cannot be measured, so it is rejected and the element renders neutral — silently. ' +
      'A semantic state is a stylesheet colour: use ToneBadge, which takes a declared ' +
      'fill/ink pair. A stored hue is a database colour: pass the hex. ADR-0029 is the line.',
    ).toEqual([])
  })
})
