import { useCallback } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { apiGetProjectAccess, apiPermissionCatalog, projectDefaultRoleApi, rolesApi } from '../api'
import type { PermissionCatalogEntry, Role, RoleScope } from '../types'

/**
 * Roles as **server state** (HD-123 S6) — the queries the People and Roles
 * screens share, and the one invalidation every mutation on them runs.
 *
 * ## Why the invalidation is centralised
 *
 * `myPermissions` rides responses the SPA fetched for another reason — the
 * workspace entry the rail caches, the project entry every board render reads.
 * The SPA therefore renders its controls from an **already-fetched** answer, so
 * after a role edit a user can keep *seeing* a control the API will refuse. The
 * API is the enforcement boundary and nothing unsafe happens, but a button that
 * 403s is a bug in the UI's contract with the user, and the fix is that every
 * write which can change a permission set invalidates every cached carrier of
 * one — not just the list it visibly changed.
 *
 * `useRoleInvalidation` is that list, in one place, because the failure
 * mode of getting it wrong is invisible: the screen you are looking at updates,
 * and a different screen keeps a stale answer until its own `staleTime` lapses.
 *
 * **A role's *contents* are additionally cached server-side for up to 10 s per
 * node**, so on a multi-instance deployment a revocation can still be honoured
 * elsewhere for that long. Refetching cannot close that window — it is a
 * property of the server — which is exactly why the SPA must not pretend
 * otherwise by, say, optimistically rewriting `myPermissions` locally.
 */

/** Every roles query key starts here, so one prefix invalidates them all. */
export const ROLES_KEY_ROOT = 'roles' as const

export interface RolesQueryOptions {
  /** Both scopes when absent. */
  scope?: RoleScope
  /**
   * Adds the workspace-scoped `usage` block — and **requires
   * `workspace.role.manage`**, turning an otherwise-open endpoint into a 403 for
   * anyone else. Only ever pass `true` behind that permission.
   */
  includeUsage?: boolean
  /**
   * Hold the request until the CALLER's own permissions are known. Without it a
   * screen that derives `includeUsage` from a permission fires once with it off
   * and again with it on, and the first answer carries `usage: null` — which is
   * not "unused", it is "not asked for".
   */
  enabled?: boolean
}

/**
 * Distinct entries per (scope, includeUsage): the two differ in *shape*
 * (`usage` is `null` when it was not asked for, which says nothing about whether
 * the role is in use), and sharing one entry would let a cheap list overwrite an
 * expensive one with nulls.
 */
export function rolesKey(wsId: string | undefined, opts: RolesQueryOptions = {}) {
  return [ROLES_KEY_ROOT, wsId, opts.scope ?? 'ALL', opts.includeUsage ? 'usage' : 'plain'] as const
}

export function useRoles(wsId: string | undefined, opts: RolesQueryOptions = {}) {
  return useQuery({
    queryKey: rolesKey(wsId, opts),
    queryFn: () => rolesApi.list(wsId!, { scope: opts.scope, includeUsage: opts.includeUsage }),
    enabled: !!wsId && opts.enabled !== false,
  })
}

/**
 * The permission catalog. Static product metadata — no tenancy, no workspace,
 * identical for every caller on a version — so it is cached for the session
 * rather than per workspace.
 */
export function usePermissionCatalog() {
  return useQuery({
    queryKey: ['permission-catalog'],
    queryFn: apiPermissionCatalog,
    staleTime: Infinity,
    gcTime: Infinity,
  })
}

/**
 * Refresh **everything a permission set rides on** after a role or membership
 * write. Awaited by every mutation on the People/Roles screens.
 *
 * The list, and why each entry is on it:
 *
 *  • `['roles', wsId]` — the roles themselves, their derived `assignment` block
 *    (which is computed against every *other* role, so editing one changes the
 *    block of several) and their usage counts.
 *  • `['workspace', wsId]` and `['workspaces']` — `myPermissions` on the
 *    workspace, which gates the settings doors, the invite control and this very
 *    screen.
 *  • `['projects', wsId]` and `['project', wsId]` (a prefix, so every project
 *    entry) — `myPermissions` on each project. A workspace-scoped role change
 *    reaches these through `project.curate.all` / `project.administer.all`, and a
 *    project-scoped one obviously does.
 *  • `['workspace-members', wsId]` / `['project-members', wsId]` — the rendered
 *    role names, and the membership rows a delete-with-reassign rewrites in bulk.
 *  • `['project-access', wsId]` and `['project-default-role', wsId]` (a prefix,
 *    so every project's entry) — the S7 settings reads. Both carry a
 *    server-derived `settable` block computed against the caller's own permission
 *    set, so a role edit moves what the pickers may offer even when neither the
 *    mode nor a default changed.
 *
 * **This is also the invalidation every S7 write runs** (HD-130 §9.4). The access
 * mode and either default role change what `myPermissions` contains on the very
 * next request, on every replica — so a stale project list is a stale gate, and
 * "the screen I am looking at updated" is precisely the wrong test.
 */
export function useRoleInvalidation(wsId: string | undefined) {
  const qc = useQueryClient()
  return useCallback(async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: [ROLES_KEY_ROOT, wsId] }),
      qc.invalidateQueries({ queryKey: ['workspace', wsId] }),
      qc.invalidateQueries({ queryKey: ['workspaces'] }),
      qc.invalidateQueries({ queryKey: ['projects', wsId] }),
      qc.invalidateQueries({ queryKey: ['project', wsId] }),
      qc.invalidateQueries({ queryKey: ['workspace-members', wsId] }),
      qc.invalidateQueries({ queryKey: ['project-members', wsId] }),
      qc.invalidateQueries({ queryKey: [PROJECT_ACCESS_KEY_ROOT, wsId] }),
      qc.invalidateQueries({ queryKey: [PROJECT_DEFAULT_ROLE_KEY_ROOT, wsId] }),
    ])
  }, [qc, wsId])
}

/** `GET /project-access` — the workspace General page's single read (HD-130 S7 W1). */
export const PROJECT_ACCESS_KEY_ROOT = 'project-access' as const

export function projectAccessKey(wsId: string | undefined) {
  return [PROJECT_ACCESS_KEY_ROOT, wsId] as const
}

/** `GET /projects/{p}/default-role` — what the project picker dialog opens against. */
export const PROJECT_DEFAULT_ROLE_KEY_ROOT = 'project-default-role' as const

export function projectDefaultRoleKey(wsId: string | undefined, projectId: string | undefined) {
  return [PROJECT_DEFAULT_ROLE_KEY_ROOT, wsId, projectId] as const
}

/**
 * The workspace's project-access settings — mode, declared default, the ceiling
 * rendered, and the impact of the workspace **as it stands**.
 *
 * Gated on `workspace.edit` server-side, so `enabled` must be held until that
 * answer is known: firing it for a member without the permission is a guaranteed
 * 403 and a retry storm on a screen that should simply not have been reachable.
 *
 * **The `impact` here is the current state, never a proposal.** The confirm dialog
 * re-fetches `POST …/preview` for what is actually being asked; reusing this block
 * would render a snapshot of the wrong question.
 */
export function useProjectAccess(wsId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: projectAccessKey(wsId),
    queryFn: () => apiGetProjectAccess(wsId!),
    enabled: !!wsId && enabled,
  })
}

/**
 * One project's default-access settings. Gated on `project.member.manage` in
 * **that project** — which no workspace-wide role grants — so, as above, `enabled`
 * waits for the project's own permission answer.
 */
export function useProjectDefaultRole(
  wsId: string | undefined, projectId: string | undefined, enabled = true,
) {
  return useQuery({
    queryKey: projectDefaultRoleKey(wsId, projectId),
    queryFn: () => projectDefaultRoleApi.get(wsId!, projectId!),
    enabled: !!wsId && !!projectId && enabled,
  })
}

/** Members of one project — the list the SPA never called before this slice. */
export function projectMembersKey(wsId: string | undefined, projectId: string | undefined) {
  return ['project-members', wsId, projectId] as const
}

/**
 * Roles of one scope, ordered the way the screens render them: built-ins first
 * (in their seeded `position`), then the workspace's own alphabetically. A
 * custom role may legally reuse a built-in's *key*, so nothing here branches on
 * one — `builtIn` is the flag.
 */
export function sortRoles(roles: Role[]): Role[] {
  return [...roles].sort((a, b) => {
    if (a.builtIn !== b.builtIn) return a.builtIn ? -1 : 1
    if (a.builtIn) return a.position - b.position
    return a.name.localeCompare(b.name)
  })
}

/** Catalog entries of one scope, in catalog order. */
export function catalogFor(
  catalog: PermissionCatalogEntry[] | undefined, scope: RoleScope,
): PermissionCatalogEntry[] {
  return (catalog ?? []).filter(e => e.scope === scope)
}
