import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import ShadowedFieldsNotice from './ShadowedFieldsNotice'
import type { ShadowedSearchField } from '../../types'

/**
 * HD-275 AC-31 — the search surface's notice.
 *
 * Three inputs that are NOT the same input: a populated list, an empty list,
 * and no list at all. The third is a server that predates `shadowedFields`, and
 * the defect worth guarding is a component that reads `.length` off it and
 * takes the whole search page down over a field the server was never asked for.
 */

const LABELS: ShadowedSearchField = { key: 'labels', name: 'Team labels', shadowedBy: 'label' }
const COMPONENTS: ShadowedSearchField = { key: 'components', name: 'Subsystems', shadowedBy: 'component' }

describe('ShadowedFieldsNotice', () => {
  it('renders nothing when the server sent no list at all (older server)', () => {
    const { container } = render(<ShadowedFieldsNotice />)
    // Absent means "nothing to warn about" — never "warn about everything",
    // and never a crash.
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing for an empty list', () => {
    const { container } = render(<ShadowedFieldsNotice fields={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('names the field, the key, the built-in that took it, and a remedy', () => {
    render(<ShadowedFieldsNotice fields={[LABELS]} />)

    const line = screen.getByText(/is a custom field in this workspace/)
    // The tenant's own field, by display name…
    expect(line).toHaveTextContent('“Team labels” is a custom field in this workspace')
    // …the key that was taken, and the built-in that answers instead. Naming
    // only one of the two leaves the reader unable to tell what they are seeing
    // when the query returns rows.
    expect(line).toHaveTextContent('“labels” is a built-in search name')
    expect(line).toHaveTextContent('returns built-in label data, not this field\'s values')
    // A remedy the reader can route to. No count of affected saved filters —
    // it cannot be computed honestly and, in this population, is zero.
    expect(line).toHaveTextContent('A workspace admin can rename the field\'s key in Settings → Fields')
    expect(line.textContent).not.toMatch(/saved filter/i)
  })

  it('renders one line per field up to three', () => {
    render(<ShadowedFieldsNotice fields={[LABELS, COMPONENTS]} />)
    expect(screen.getAllByText(/is a custom field in this workspace/)).toHaveLength(2)
  })

  it('collapses past three, and still names every key', () => {
    const many: ShadowedSearchField[] = [
      LABELS, COMPONENTS,
      { key: 'sprint', name: 'Iteration', shadowedBy: 'sprint' },
      { key: 'points', name: 'Effort', shadowedBy: 'storyPoints' },
    ]
    render(<ShadowedFieldsNotice fields={many} />)

    // The per-field lines are gone…
    expect(screen.queryByText(/is a custom field in this workspace/)).toBeNull()
    const summary = screen.getByText(/4 custom fields in this workspace/)
    // …but the keys are not: a bare count says something is wrong without
    // saying which query is the one lying.
    for (const key of ['labels', 'components', 'sprint', 'points']) {
      expect(summary).toHaveTextContent(`“${key}”`)
    }
    expect(summary).toHaveTextContent('A workspace admin can rename their keys in Settings → Fields')
  })
})
