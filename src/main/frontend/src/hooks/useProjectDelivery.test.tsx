import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { deliveryOf, useProjectDelivery } from './useProjectDelivery'
import type { Project, ProjectDelivery } from '../types'

/**
 * HD-102 §12 — `deliveryOf` is the ONE place the SPA answers "does this project
 * do X?", so its three input shapes are the whole contract of the slice:
 *
 *  1. a project that carries `delivery` — believed verbatim;
 *  2. a project that does NOT (a pre-HD-102 server, or a hand-built fixture) —
 *     the §7 upgrade rule, *an upgrade never takes away a capability a project
 *     already has*: releases + estimation on, board from the deprecated
 *     `boardMode` mirror;
 *  3. no project at all (still loading, or the query failed) — every capability
 *     OFF, so a control is never flashed in and then yanked away.
 *
 * (2) and (3) are invisible in the UI — nothing renders differently *because*
 * they are fallbacks — which is exactly why they need pinning here rather than
 * through a page. Both are load-bearing: (2) keeps every project that existed
 * before the migration as capable as it was, and (3) decides what a page shows
 * during its first paint.
 */

const BASE: Omit<Project, 'delivery' | 'boardMode'> = {
  id: 'p1',
  workspaceId: 'w1',
  name: 'Proj',
  key: 'PR',
  archived: false,
  myRole: 'MANAGER',
  createdAt: '2026-01-01T00:00:00Z',
}

describe('deliveryOf — a project that declares its capabilities', () => {
  it('returns the declared set verbatim', () => {
    const delivery: ProjectDelivery = {
      board: 'SCRUM', releases: false, estimation: true, preset: 'CUSTOM',
    }
    expect(deliveryOf({ ...BASE, delivery })).toEqual(delivery)
  })

  it('ignores the deprecated top-level `boardMode` mirror when the two disagree', () => {
    // "No surface reads both" — a stale mirror on the wire must never be able to
    // put the board and the settings page into different modes.
    const delivery: ProjectDelivery = {
      board: 'SCRUM', releases: true, estimation: true, preset: 'SCRUM',
    }
    expect(deliveryOf({ ...BASE, boardMode: 'KANBAN', delivery }).board).toBe('SCRUM')
  })
})

describe('deliveryOf — a project WITHOUT `delivery` (the §7 upgrade rule)', () => {
  it('keeps releases and estimation ON, and takes the board from `boardMode`', () => {
    // Every pre-HD-102 project could reach the Releases page and the points
    // input; an upgrade must not silently remove either.
    expect(deliveryOf({ ...BASE, boardMode: 'SCRUM' })).toEqual({
      board: 'SCRUM', releases: true, estimation: true, preset: 'CUSTOM',
    })
  })

  it('falls back to KANBAN when the mirror is absent too', () => {
    expect(deliveryOf({ ...BASE })).toEqual({
      board: 'KANBAN', releases: true, estimation: true, preset: 'CUSTOM',
    })
  })

  it('derives no preset for itself — the preset lives server-side only', () => {
    // A KANBAN + releases + estimation project would be labelled CUSTOM by the
    // server anyway, but the point is that this client never computes a label:
    // one derivation, one place (risk 5, §17).
    expect(deliveryOf({ ...BASE, boardMode: 'SCRUM' }).preset).toBe('CUSTOM')
  })
})

describe('deliveryOf — no project (loading, or the query failed)', () => {
  it.each([['undefined', undefined], ['null', null]] as const)(
    'reports every capability OFF for %s',
    (_label, project) => {
      expect(deliveryOf(project)).toEqual({
        board: 'KANBAN', releases: false, estimation: false, preset: 'CUSTOM',
      })
    },
  )

  it('never guesses a capability ON — a control is never drawn and then withdrawn', () => {
    const unknown = deliveryOf(undefined)
    expect(unknown.board).not.toBe('SCRUM')
    expect(unknown.releases).toBe(false)
    expect(unknown.estimation).toBe(false)
  })
})

// ── The hook half ─────────────────────────────────────────────────────────────

const projectResponse = vi.hoisted(() => ({ current: null as Project | null }))

vi.mock('../api', () => ({
  apiGetProject: vi.fn(async () => {
    if (!projectResponse.current) throw new Error('boom')
    return projectResponse.current
  }),
  apiGetWorkspace: vi.fn(async () => ({
    id: 'w1', name: 'WS', slug: 'ws', myRole: 'MEMBER', createdAt: '2026-01-01T00:00:00Z',
  })),
  ApiResponseError: class ApiResponseError extends Error { status = 0 },
  apiGetBacklogView: vi.fn(),
  sprintsApi: { list: vi.fn(async () => ({ content: [] })) },
}))

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

beforeEach(() => { projectResponse.current = null })

describe('useProjectDelivery', () => {
  it('reports every capability off while the project entry is still loading', () => {
    projectResponse.current = {
      ...BASE, delivery: { board: 'SCRUM', releases: true, estimation: true, preset: 'SCRUM' },
    }
    const { result } = renderHook(() => useProjectDelivery('w1', 'p1'), { wrapper })

    // First paint, before the response lands: nothing path-specific is offered.
    expect(result.current.iterations).toBe(false)
    expect(result.current.releases).toBe(false)
    expect(result.current.estimation).toBe(false)
  })

  it('derives `iterations` from the board once the project resolves', async () => {
    projectResponse.current = {
      ...BASE, delivery: { board: 'SCRUM', releases: false, estimation: true, preset: 'CUSTOM' },
    }
    const { result } = renderHook(() => useProjectDelivery('w1', 'p1'), { wrapper })

    await waitFor(() => expect(result.current.iterations).toBe(true))
    expect(result.current.releases).toBe(false)
    expect(result.current.estimation).toBe(true)
    expect(result.current.project?.id).toBe('p1')
  })

  it('stays all-off when the project query FAILS — never a half-drawn surface', async () => {
    projectResponse.current = null   // the mocked endpoint rejects
    const { result } = renderHook(() => useProjectDelivery('w1', 'p1'), { wrapper })

    await waitFor(() => expect(result.current.project).toBeUndefined())
    expect(result.current.iterations).toBe(false)
    expect(result.current.releases).toBe(false)
    expect(result.current.estimation).toBe(false)
  })

  it('is inert without ids (no project route) rather than assuming a capability', () => {
    const { result } = renderHook(() => useProjectDelivery(undefined, undefined), { wrapper })
    expect(result.current.iterations).toBe(false)
    expect(result.current.releases).toBe(false)
    expect(result.current.estimation).toBe(false)
  })
})
