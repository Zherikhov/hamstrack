import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  NO_PERMISSIONS, OWNABLE_PERMISSIONS, OWN_REQUIRED_PERMISSIONS,
  PROJECT_PERMISSIONS, WORKSPACE_PERMISSIONS,
  canOpenProjectSettings, canOpenWorkspaceSettings, permissionsFrom,
  useProjectPermissions, usePermissions,
} from './usePermissions'
import { PROJECT_ADMIN_PERMISSIONS, WORKSPACE_ADMIN_PERMISSIONS } from '../test/permissions'
import type { Project, Workspace } from '../types'

/**
 * HD-123 S5 — `usePermissions` is the SPA's only authorization primitive, so its
 * contract is the whole slice.
 *
 * **The trap this file exists to nail down.** `"issue.edit:own"` and
 * `"issue.edit"` are two DISTINCT wire values, not a prefix relationship. A
 * helper written as `perms.some(p => p.startsWith('issue.edit'))` — the obvious
 * first draft — resolves the ambiguity in the **widening** direction: it reads an
 * own-only grant as unrestricted and hands a reporter an "edit anyone's issue"
 * button. Widening is the worse of the two failure directions, and it is silent,
 * so it gets its own describe block below and is asserted from both sides.
 *
 * The type system carries most of the weight (`can` takes the catalog union, so
 * `can('issue.edit:own')` does not compile, and `canOwn` demands the ownership
 * fact as a second argument), but the runtime semantics have to match the
 * server's `PermissionSet.has(p)` / `has(p, isOwn)` exactly, and only a test can
 * say that.
 */

describe('the :own modifier is not a prefix (the widening trap)', () => {
  it('does NOT let an own-only grant answer "may I do this to anything?"', () => {
    const perms = permissionsFrom([{ myPermissions: ['issue.edit:own'] }])

    // The whole point. `startsWith('issue.edit')` would answer true here.
    expect(perms.can('issue.edit')).toBe(false)
    expect(perms.can('issue.delete')).toBe(false)
  })

  it('answers the OWN question true only for an object the actor owns', () => {
    const perms = permissionsFrom([{ myPermissions: ['issue.edit:own'] }])

    expect(perms.canOwn('issue.edit', true)).toBe(true)
    expect(perms.canOwn('issue.edit', false)).toBe(false)
  })

  it('lets an unrestricted grant answer BOTH questions, for any object', () => {
    const perms = permissionsFrom([{ myPermissions: ['issue.edit'] }])

    expect(perms.can('issue.edit')).toBe(true)
    expect(perms.canOwn('issue.edit', true)).toBe(true)
    // The asymmetry: unrestricted covers "not mine" too.
    expect(perms.canOwn('issue.edit', false)).toBe(true)
  })

  it('never leaks one key into another that shares its prefix', () => {
    // `comment.delete` vs `comment.deleted`-style confusion is what a substring
    // test invites; these are real neighbours in the catalog.
    const perms = permissionsFrom([{ myPermissions: ['comment.create'] }])

    expect(perms.can('comment.create')).toBe(true)
    expect(perms.can('comment.delete')).toBe(false)
    expect(perms.canOwn('comment.delete', true)).toBe(false)
  })

  it('takes the WIDER grant when two carriers disagree — never the narrower', () => {
    // Mirrors `PermissionSet.union`: an unrestricted grant always beats an
    // own-only one, in either argument order.
    const forward = permissionsFrom([
      { myPermissions: ['issue.edit:own'] },
      { myPermissions: ['issue.edit'] },
    ])
    const backward = permissionsFrom([
      { myPermissions: ['issue.edit'] },
      { myPermissions: ['issue.edit:own'] },
    ])

    for (const perms of [forward, backward]) {
      expect(perms.can('issue.edit')).toBe(true)
      expect(perms.canOwn('issue.edit', false)).toBe(true)
    }
  })

  it('keeps the two questions separate for every ownable key in the catalog', () => {
    for (const key of OWNABLE_PERMISSIONS) {
      const perms = permissionsFrom([{ myPermissions: [`${key}:own`] }])
      expect([key, perms.can(key as never)]).toEqual([key, false])
      expect([key, perms.canOwn(key, true)]).toEqual([key, true])
    }
  })

  it('agrees with the server about which key is own-REQUIRED', () => {
    // `comment.edit` is the one key that is never granted unrestricted (§17.3),
    // which is why the type refuses `can('comment.edit')` outright. If the server
    // ever widened it, this list is the thing to change first.
    expect([...OWN_REQUIRED_PERMISSIONS]).toEqual(['comment.edit'])
    expect(OWNABLE_PERMISSIONS).toContain('comment.edit')
  })
})

describe('a missing answer is never a yes', () => {
  it('denies everything for an absent carrier', () => {
    const perms = permissionsFrom([undefined, null])
    expect(perms.can('issue.create')).toBe(false)
    expect(perms.canOwn('issue.edit', true)).toBe(false)
    expect(perms.isLoading).toBe(false)
  })

  it('denies everything for an empty grant list — a real, resolved "no"', () => {
    const perms = permissionsFrom([{ myPermissions: [] }])
    expect(perms.can('issue.create')).toBe(false)
    expect(perms.isLoading).toBe(false)
  })

  it('distinguishes "not yet" from "no" through isLoading, and denies in both', () => {
    const pending = permissionsFrom([undefined], true)
    expect(pending.can('issue.create')).toBe(false)
    expect(pending.isLoading).toBe(true)

    expect(NO_PERMISSIONS.can('issue.create')).toBe(false)
    expect(NO_PERMISSIONS.isLoading).toBe(false)
  })

  it('ignores a key this build does not know, rather than guessing at it', () => {
    const perms = permissionsFrom([{ myPermissions: ['some.future.permission'] }])
    expect(perms.can('issue.create')).toBe(false)
  })
})

describe('the shared door predicates', () => {
  it('opens project settings on ANY of the three project-admin grants', () => {
    for (const key of ['project.edit', 'project.taxonomy.manage', 'project.member.manage']) {
      expect([key, canOpenProjectSettings(permissionsFrom([{ myPermissions: [key] }]))])
        .toEqual([key, true])
    }
    // …and not on a grant that is merely adjacent.
    expect(canOpenProjectSettings(permissionsFrom([{ myPermissions: ['issue.edit'] }]))).toBe(false)
    expect(canOpenProjectSettings(permissionsFrom([]))).toBe(false)
  })

  it('opens workspace settings on any of its four grants', () => {
    const keys = [
      'workspace.edit', 'workspace.taxonomy.manage',
      'workspace.member.manage', 'workspace.role.manage',
    ]
    for (const key of keys) {
      expect([key, canOpenWorkspaceSettings(permissionsFrom([{ myPermissions: [key] }]))])
        .toEqual([key, true])
    }
    // A plain workspace member holds label grants and nothing else.
    const member = permissionsFrom([{ myPermissions: ['label.create', 'label.manage:own'] }])
    expect(canOpenWorkspaceSettings(member)).toBe(false)
  })
})

describe('the catalog mirrors the server enum', () => {
  it('carries 9 workspace-scoped and 20 project-scoped keys, all distinct', () => {
    expect(WORKSPACE_PERMISSIONS).toHaveLength(9)
    expect(PROJECT_PERMISSIONS).toHaveLength(20)
    const all = [...WORKSPACE_PERMISSIONS, ...PROJECT_PERMISSIONS]
    expect(new Set(all).size).toBe(29)
  })

  it('holds no key containing a colon — that would make the :own suffix ambiguous', () => {
    // The server's `Permission` enum refuses such a key at class-init for exactly
    // this reason; the client half of that invariant is asserted here.
    for (const key of [...WORKSPACE_PERMISSIONS, ...PROJECT_PERMISSIONS]) {
      expect([key, key.includes(':')]).toEqual([key, false])
    }
  })
})

// ── The hook half ─────────────────────────────────────────────────────────────

const WS_ID = 'w1'
const PROJECT_ID = 'p1'

const responses = vi.hoisted(() => ({
  project: null as Project | null,
  workspace: null as Workspace | null,
  calls: [] as string[],
}))

vi.mock('../api', () => ({
  apiGetProject: vi.fn(async () => {
    responses.calls.push('project')
    if (!responses.project) throw new Error('boom')
    return responses.project
  }),
  apiGetWorkspace: vi.fn(async () => {
    responses.calls.push('workspace')
    if (!responses.workspace) throw new Error('boom')
    return responses.workspace
  }),
}))

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

const PROJECT: Project = {
  id: PROJECT_ID, workspaceId: WS_ID, name: 'Proj', key: 'PR',
  archived: false, myRole: 'MEMBER', myPermissions: PROJECT_ADMIN_PERMISSIONS,
  createdAt: '2026-01-01T00:00:00Z',
}

const WORKSPACE: Workspace = {
  id: WS_ID, name: 'Acme', slug: 'acme', myRole: 'ADMIN',
  myPermissions: WORKSPACE_ADMIN_PERMISSIONS, createdAt: '2026-01-01T00:00:00Z',
}

beforeEach(() => {
  responses.project = PROJECT
  responses.workspace = WORKSPACE
  responses.calls = []
})

describe('useProjectPermissions', () => {
  it('denies on first paint and reports isLoading, then answers', async () => {
    const { result } = renderHook(() => useProjectPermissions(WS_ID, PROJECT_ID), { wrapper })

    // Before the response lands: no gate opens, and the surface can tell that
    // this is "not yet" rather than "no".
    expect(result.current.can('issue.create')).toBe(false)
    expect(result.current.isLoading).toBe(true)

    await waitFor(() => expect(result.current.can('issue.create')).toBe(true))
    expect(result.current.isLoading).toBe(false)
  })

  it('asks the network for the PROJECT only — never the workspace', async () => {
    const { result } = renderHook(() => useProjectPermissions(WS_ID, PROJECT_ID), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    // The HD-98 fix used to cost a `GET /workspaces/{id}` per board to
    // reconstruct the curator bypass by hand. The server folds that bypass into
    // the project's own permission set, so the extra round trip is gone.
    await new Promise(r => setTimeout(r, 20))
    expect(responses.calls).toEqual(['project'])
  })

  it('denies everything when the project query fails', async () => {
    responses.project = null
    const { result } = renderHook(() => useProjectPermissions(WS_ID, PROJECT_ID), { wrapper })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.can('issue.create')).toBe(false)
  })

  it('denies, and asks nothing, without a project id', async () => {
    const { result } = renderHook(() => useProjectPermissions(WS_ID, undefined), { wrapper })

    await new Promise(r => setTimeout(r, 20))
    expect(result.current.can('issue.create')).toBe(false)
    expect(result.current.isLoading).toBe(false)
    expect(responses.calls).toEqual([])
  })
})

describe('usePermissions', () => {
  it('answers both scopes from their own responses', async () => {
    const { result } = renderHook(() => usePermissions(WS_ID, PROJECT_ID), { wrapper })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    // Workspace scope from the workspace response…
    expect(result.current.can('project.create')).toBe(true)
    // …project scope from the project one. The two key spaces are disjoint, so
    // one object can carry both without ambiguity.
    expect(result.current.can('issue.transition')).toBe(true)
  })

  it('answers workspace scope alone when no project is named', async () => {
    const { result } = renderHook(() => usePermissions(WS_ID), { wrapper })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.can('workspace.taxonomy.manage')).toBe(true)
    expect(result.current.can('issue.transition')).toBe(false)
    expect(responses.calls).toEqual(['workspace'])
  })
})
