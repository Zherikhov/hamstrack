import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Badge, ParentChip, PriorityBadge, StatusBadge } from './ui'
import { INK_MIN, contrastRatio, parseColour } from '../colour'
import type { Priority } from '../types'

/**
 * The rendering half of HD-176, asserted **from the DOM rather than from the
 * props** — a component can call the primitive and then be overridden by a later
 * style, and only reading the element back catches that.
 *
 * The surface a badge's label is measured against is the badge's own **computed
 * tint**, not the card: that is the surface the glyph is literally painted on,
 * and it is the reason the tint stopped being an alpha overlay. An overlay
 * composites over whatever is behind the element, so a chip's background over a
 * hovered row was never the colour anybody measured; an opaque tint is, and the
 * ink derived against it then holds in every row state.
 *
 * Like `colour.test.ts`, this runs on no automated path yet — CI runs
 * `./mvnw -B verify`, which never invokes `npm test` (HD-242).
 */

/** The inline style the element actually carries, parsed back out of the DOM. */
function styleOf(el: HTMLElement) {
  return { color: el.style.color, background: el.style.backgroundColor || el.style.background }
}

/** jsdom serialises an inline colour as `rgb(r, g, b)`; bring it back to hex. */
function toHexFromCss(value: string): string {
  const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(value)
  if (m) {
    return `#${[1, 2, 3].map(i => Number(m[i]).toString(16).padStart(2, '0')).join('').toUpperCase()}`
  }
  return value.trim().toUpperCase()
}

const priority = (color: string): Priority => ({
  id: 'p1', name: 'Medium', color, icon: 'equal', position: 1,
} as Priority)

describe('StatusBadge', () => {
  it('paints an unreadable hue as a readable one against its own tint', () => {
    render(<StatusBadge name="In Testing" category="IN_PROGRESS" color="#EAB308" />)
    const el = screen.getByText('In Testing')
    const { color, background } = styleOf(el)

    // Both are opaque values the browser can measure — no alpha overlay left.
    expect(parseColour(toHexFromCss(background))).not.toBeNull()
    expect(contrastRatio(toHexFromCss(color), toHexFromCss(background)))
      .toBeGreaterThanOrEqual(INK_MIN)
    // The raw stored hue is what FAILED, so the label cannot still be it.
    expect(toHexFromCss(color)).not.toBe('#EAB308')
  })

  it('leaves a hue that already reads exactly as stored — the tuned-workspace guarantee', () => {
    render(<StatusBadge name="Done" category="DONE" color="#16202E" />)
    const el = screen.getByText('Done')
    expect(toHexFromCss(styleOf(el).color)).toBe('#16202E')
  })

  it('gives a colourless status the safety-state hue as a real, measurable colour', () => {
    // As a bare `var(--color-sandbox)` this used to produce `var(--color-sandbox)20`
    // for the tint and the hairline — not a colour any browser parses — so a
    // status with no stored colour silently lost both.
    render(<StatusBadge name="To Do" category="TODO" />)
    const el = screen.getByText('To Do')
    const { color, background } = styleOf(el)
    expect(parseColour(toHexFromCss(background))).not.toBeNull()
    expect(contrastRatio(toHexFromCss(color), toHexFromCss(background)))
      .toBeGreaterThanOrEqual(INK_MIN)
  })

  it('adds no element: the badge is the one span it always was', () => {
    const { container } = render(<StatusBadge name="To Do" category="TODO" color="#EAB308" />)
    expect(container.querySelectorAll('*')).toHaveLength(1)
    expect(container.firstElementChild?.tagName).toBe('SPAN')
  })
})

describe('Badge', () => {
  it('falls back to neutral tokens for a colour it cannot parse, and does not throw', () => {
    render(<Badge label="Broken" color="not-a-colour" />)
    const el = screen.getByText('Broken')
    // jsdom drops an unparseable declaration, so what matters is that nothing
    // threw and no raw junk reached the style.
    expect(el.style.color).not.toContain('not-a-colour')
  })
})

describe('PriorityBadge', () => {
  it('derives its label and its glyph against the same surface', () => {
    const { container } = render(<PriorityBadge priority={priority('#EAB308')} />)
    const label = screen.getByText('Medium')
    const glyph = container.querySelector('svg')!
    expect(toHexFromCss(label.style.color)).not.toBe('#EAB308')
    expect(toHexFromCss(glyph.style.color)).toBe(toHexFromCss(label.style.color))
  })

  it('is inline text with no box — no padding, no radius, no background', () => {
    const { container } = render(<PriorityBadge priority={priority('#EAB308')} />)
    const el = container.firstElementChild as HTMLElement
    expect(el.style.padding).toBe('')
    expect(el.style.borderRadius).toBe('')
    expect(el.style.background).toBe('')
  })
})

describe('ParentChip', () => {
  it('reads its key against the pill tint it sits on', () => {
    render(<ParentChip parentKey="PAY-131" color="#EAB308" />)
    const key = screen.getByText('PAY-131')
    const pill = key.closest('button') as HTMLElement
    expect(contrastRatio(toHexFromCss(key.style.color), toHexFromCss(styleOf(pill).background)))
      .toBeGreaterThanOrEqual(INK_MIN)
  })
})
