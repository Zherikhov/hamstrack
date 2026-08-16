import { describe, it, expect } from 'vitest'
import {
  boardIssuesKey,
  backlogIssuesKeyPrefix,
  backlogViewKey,
  boardIssuesKeyPrefix,
  projectIssuesKeyPrefix,
  serializeIssueFilters,
  sprintsKey,
} from './queryKeys'

/**
 * The board cache key is a *contract*, not an implementation detail: `BoardPage`
 * writes the `BoardIssues` wrapper under it and `CreateIssueModal` reads that same
 * entry for its parent picker. Disagreeing about the key (or its arity) is exactly
 * how the HD-86/HD-87 white screen happened — twice — so the compatibility rules get
 * a direct test rather than being implied by the component suites.
 *
 * The load-bearing rule: an EMPTY filter selection must serialize to the same key as
 * the 2-argument call. `BoardPage` always passes an object
 * (`{ priorityId: undefined, labelIds: [], labelMatch: 'any' }`); `CreateIssueModal`
 * passes nothing at all. If those two ever diverge, the dialog reads an empty cache
 * entry (or worse, a differently-shaped one) over a freshly-rendered board.
 */
describe('boardIssuesKey — backwards-compatibility contract', () => {
  const unfiltered = boardIssuesKey('w1', 'p1')

  it('still works with two arguments and keeps the documented shape', () => {
    expect(unfiltered).toEqual(['issues', 'w1', 'p1', 'board', ''])
  })

  it('treats every flavour of "no filters" as the SAME entry as the 2-arg call', () => {
    expect(boardIssuesKey('w1', 'p1', '')).toEqual(unfiltered)
    expect(boardIssuesKey('w1', 'p1', {})).toEqual(unfiltered)
    // …and the exact object BoardPage builds when nothing is selected
    expect(boardIssuesKey('w1', 'p1', {
      priorityId: undefined,
      labelIds: [],
      labelMatch: 'any',
    })).toEqual(unfiltered)
    // an "all" toggle with no labels selected must not split the entry either
    expect(boardIssuesKey('w1', 'p1', { labelIds: [], labelMatch: 'all' })).toEqual(unfiltered)
  })

  it('keeps the legacy priority-id string and the object form on one entry', () => {
    expect(boardIssuesKey('w1', 'p1', 'prio-1'))
      .toEqual(boardIssuesKey('w1', 'p1', { priorityId: 'prio-1' }))
    expect(boardIssuesKey('w1', 'p1', 'prio-1')).not.toEqual(unfiltered)
  })

  it('sorts labelIds so the same selection in a different order is one entry', () => {
    expect(boardIssuesKey('w1', 'p1', { labelIds: ['b', 'a'] }))
      .toEqual(boardIssuesKey('w1', 'p1', { labelIds: ['a', 'b'] }))
  })

  it('separates genuinely different selections', () => {
    const a = boardIssuesKey('w1', 'p1', { labelIds: ['a'] })
    const ab = boardIssuesKey('w1', 'p1', { labelIds: ['a', 'b'] })
    expect(a).not.toEqual(unfiltered)
    expect(a).not.toEqual(ab)
    // different workspace / project never share an entry
    expect(boardIssuesKey('w2', 'p1')).not.toEqual(unfiltered)
    expect(boardIssuesKey('w1', 'p2')).not.toEqual(unfiltered)
  })

  it('only distinguishes labelMatch once two labels are selected', () => {
    // one label: "any" and "all" are the same question
    expect(boardIssuesKey('w1', 'p1', { labelIds: ['a'], labelMatch: 'all' }))
      .toEqual(boardIssuesKey('w1', 'p1', { labelIds: ['a'], labelMatch: 'any' }))
    // two labels: they are different questions and must not share a cache entry
    expect(boardIssuesKey('w1', 'p1', { labelIds: ['a', 'b'], labelMatch: 'all' }))
      .not.toEqual(boardIssuesKey('w1', 'p1', { labelIds: ['a', 'b'], labelMatch: 'any' }))
  })

  // HD-32: the fix-version filter went live. It was reserved in the interface
  // from HD-30 onwards, so activating it must not move ANY existing key — the
  // arity is unchanged and "no version selected" still resolves to the 2-arg
  // entry the create dialog reads.
  describe('fix-version filter (HD-32)', () => {
    it('keeps "no fix version selected" on the unfiltered entry', () => {
      expect(boardIssuesKey('w1', 'p1', { fixVersionId: undefined })).toEqual(unfiltered)
      expect(boardIssuesKey('w1', 'p1', { fixVersionId: '' })).toEqual(unfiltered)
      // …the exact object BoardPage now builds with nothing selected at all
      expect(boardIssuesKey('w1', 'p1', {
        priorityId: undefined,
        labelIds: [],
        labelMatch: 'any',
        componentId: undefined,
        fixVersionId: undefined,
      })).toEqual(unfiltered)
    })

    it('gives each version its own entry', () => {
      const v1 = boardIssuesKey('w1', 'p1', { fixVersionId: 'v1' })
      expect(v1).not.toEqual(unfiltered)
      expect(v1).not.toEqual(boardIssuesKey('w1', 'p1', { fixVersionId: 'v2' }))
      // stable regardless of how the object was built
      expect(v1).toEqual(boardIssuesKey('w1', 'p1', { labelIds: [], fixVersionId: 'v1' }))
    })

    it('composes with the other dimensions instead of replacing them', () => {
      const all = boardIssuesKey('w1', 'p1', {
        priorityId: 'pr1', componentId: 'c1', labelIds: ['a'], fixVersionId: 'v1',
      })
      expect(all).not.toEqual(boardIssuesKey('w1', 'p1', { priorityId: 'pr1', componentId: 'c1', labelIds: ['a'] }))
      expect(all).not.toEqual(boardIssuesKey('w1', 'p1', { fixVersionId: 'v1' }))
      // a component id and a version id with the same value can never collide
      expect(boardIssuesKey('w1', 'p1', { componentId: 'x' }))
        .not.toEqual(boardIssuesKey('w1', 'p1', { fixVersionId: 'x' }))
    })

    it('serializes backlog fix-version filters exactly like board ones', () => {
      expect(serializeIssueFilters({ fixVersionId: 'v1' }))
        .toBe(boardIssuesKey('w1', 'p1', { fixVersionId: 'v1' })[4])
    })
  })

  // HD-27: the Scrum board scopes itself by adding `?sprintId=` to the SAME
  // request the Kanban board makes. The id therefore has to join the key (or the
  // two boards would overwrite each other's cache entry) WITHOUT moving any
  // pre-0.13.0 key — the create dialog still reads `boardIssuesKey(ws, p)`.
  describe('sprint scoping (HD-27)', () => {
    it('keeps "no sprint scope" on the unfiltered entry', () => {
      expect(boardIssuesKey('w1', 'p1', { sprintId: undefined })).toEqual(unfiltered)
      expect(boardIssuesKey('w1', 'p1', { sprintId: '' })).toEqual(unfiltered)
      // …the exact object BoardPage now builds in KANBAN mode
      expect(boardIssuesKey('w1', 'p1', {
        priorityId: undefined,
        labelIds: [],
        labelMatch: 'any',
        componentId: undefined,
        fixVersionId: undefined,
        sprintId: undefined,
      })).toEqual(unfiltered)
    })

    it('never lets a Scrum board share the Kanban entry', () => {
      const scrum = boardIssuesKey('w1', 'p1', { sprintId: 'sp1' })
      expect(scrum).not.toEqual(unfiltered)
      expect(scrum).not.toEqual(boardIssuesKey('w1', 'p1', { sprintId: 'sp2' }))
      // a component/version/sprint id with the same value can never collide
      expect(boardIssuesKey('w1', 'p1', { sprintId: 'x' }))
        .not.toEqual(boardIssuesKey('w1', 'p1', { componentId: 'x' }))
    })

    it('composes with the other dimensions instead of replacing them', () => {
      const all = boardIssuesKey('w1', 'p1', { priorityId: 'pr1', labelIds: ['a'], sprintId: 'sp1' })
      expect(all).not.toEqual(boardIssuesKey('w1', 'p1', { priorityId: 'pr1', labelIds: ['a'] }))
      expect(all).not.toEqual(boardIssuesKey('w1', 'p1', { sprintId: 'sp1' }))
    })
  })

  // HD-31: the component filter joined the object. It must add a dimension
  // WITHOUT changing the arity of the call or the "no filters" serialization —
  // the create dialog still reads `boardIssuesKey(ws, project)` for its parent
  // picker, and an unselected component must resolve to that same entry.
  describe('component filter (HD-31)', () => {
    it('keeps "no component selected" on the unfiltered entry', () => {
      expect(boardIssuesKey('w1', 'p1', { componentId: undefined })).toEqual(unfiltered)
      expect(boardIssuesKey('w1', 'p1', { componentId: '' })).toEqual(unfiltered)
      // …the exact object BoardPage builds with nothing selected at all
      expect(boardIssuesKey('w1', 'p1', {
        priorityId: undefined,
        labelIds: [],
        labelMatch: 'any',
        componentId: undefined,
      })).toEqual(unfiltered)
    })

    it('gives each component its own entry', () => {
      const c1 = boardIssuesKey('w1', 'p1', { componentId: 'c1' })
      expect(c1).not.toEqual(unfiltered)
      expect(c1).not.toEqual(boardIssuesKey('w1', 'p1', { componentId: 'c2' }))
      // stable regardless of how the object was built
      expect(c1).toEqual(boardIssuesKey('w1', 'p1', { labelIds: [], componentId: 'c1' }))
    })

    it('composes with the other dimensions instead of replacing them', () => {
      const both = boardIssuesKey('w1', 'p1', { priorityId: 'pr1', componentId: 'c1', labelIds: ['a'] })
      expect(both).not.toEqual(boardIssuesKey('w1', 'p1', { priorityId: 'pr1', labelIds: ['a'] }))
      expect(both).not.toEqual(boardIssuesKey('w1', 'p1', { componentId: 'c1' }))
      // the legacy priority-string form carries no component, so it can't collide
      expect(boardIssuesKey('w1', 'p1', 'pr1')).not.toEqual(both)
    })

    it('serializes backlog component filters exactly like board ones', () => {
      expect(serializeIssueFilters({ componentId: 'c1' }))
        .toBe(boardIssuesKey('w1', 'p1', { componentId: 'c1' })[4])
    })
  })
})

describe('key namespaces', () => {
  it('never lets the backlog (Page<Issue>) collide with the board (BoardIssues)', () => {
    const backlog = [...backlogIssuesKeyPrefix('w1', 'p1'), serializeIssueFilters({ labelIds: [] })]
    expect(backlog).not.toEqual(boardIssuesKey('w1', 'p1'))
    expect(backlog[3]).toBe('backlog')
  })

  it('gives both list kinds a common invalidation prefix', () => {
    const prefix = projectIssuesKeyPrefix('w1', 'p1')
    expect(boardIssuesKey('w1', 'p1').slice(0, 3)).toEqual([...prefix])
    expect(backlogIssuesKeyPrefix('w1', 'p1').slice(0, 3)).toEqual([...prefix])
  })

  it('serializes backlog filters exactly like board filters', () => {
    expect(serializeIssueFilters({ labelIds: ['b', 'a'], labelMatch: 'all' }))
      .toBe(boardIssuesKey('w1', 'p1', { labelIds: ['a', 'b'], labelMatch: 'all' })[4])
  })

  // HD-23: the planning aggregate (`BacklogView`) is a THIRD value shape. It
  // must not collide with the board wrapper or with the legacy paginated
  // backlog, and it must live under the shared invalidation prefix so creating
  // an issue (which lands at the bottom of the ranked backlog) refreshes it.
  it('gives the planning view its own namespace under the shared prefix', () => {
    const view = backlogViewKey('w1', 'p1')
    expect(view).not.toEqual(boardIssuesKey('w1', 'p1'))
    expect(view.slice(0, 4)).not.toEqual([...backlogIssuesKeyPrefix('w1', 'p1')])
    expect(view.slice(0, 3)).toEqual([...projectIssuesKeyPrefix('w1', 'p1')])
    expect(view[3]).toBe('backlogView')
  })

  it('lets the board be invalidated without touching the planning view', () => {
    const boardPrefix = boardIssuesKeyPrefix('w1', 'p1')
    expect(boardIssuesKey('w1', 'p1').slice(0, 4)).toEqual([...boardPrefix])
    expect(backlogViewKey('w1', 'p1').slice(0, 4)).not.toEqual([...boardPrefix])
  })

  it('keys the planning view by its own two extra dimensions', () => {
    const plain = backlogViewKey('w1', 'p1')
    expect(backlogViewKey('w1', 'p1', {})).toEqual(plain)
    expect(backlogViewKey('w1', 'p1', { includeDone: false })).toEqual(plain)
    expect(backlogViewKey('w1', 'p1', { includeDone: true })).not.toEqual(plain)
    expect(backlogViewKey('w1', 'p1', { statusId: 's1' })).not.toEqual(plain)
    expect(backlogViewKey('w1', 'p1', { statusId: 's1' }))
      .not.toEqual(backlogViewKey('w1', 'p1', { statusId: 's2' }))
    // …and still composes with every board dimension
    expect(backlogViewKey('w1', 'p1', { statusId: 's1', priorityId: 'pr1' }))
      .not.toEqual(backlogViewKey('w1', 'p1', { statusId: 's1' }))
  })

  it('separates the sprint-list slices so a picker and the board never share one', () => {
    expect(sprintsKey('w1', 'p1', 'ACTIVE')).not.toEqual(sprintsKey('w1', 'p1'))
    expect(sprintsKey('w1', 'p1', 'ACTIVE')).not.toEqual(sprintsKey('w1', 'p1', ['ACTIVE', 'FUTURE']))
    // order-insensitive: the same selection is always one entry
    expect(sprintsKey('w1', 'p1', ['FUTURE', 'ACTIVE']))
      .toEqual(sprintsKey('w1', 'p1', ['ACTIVE', 'FUTURE']))
    // sprints are content, not taxonomy — never under the issue-list root
    expect(sprintsKey('w1', 'p1')[0]).toBe('sprints')
  })
})
