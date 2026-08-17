import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { apiGetProject, apiGetWorkspace } from '../api'

/**
 * **"May I?" — the SINGLE place the SPA is allowed to answer it**
 * (roles-permissions-proposal §14.1, HD-123 S5).
 *
 * Before this hook every surface re-derived its own predicate from a role string
 * (`project.myRole === 'MANAGER'`, `workspace.myRole !== 'MEMBER'`), and the two
 * bugs that produced are the reason this slice exists: HD-98 (the rail gated
 * project Settings on `myRole === 'MANAGER'` while the settings area and the
 * server admitted the wider curator predicate, so a workspace admin held the
 * permission and had no way in) and HD-116 (the identical bug, still live on a
 * second surface — the command palette). Both are the same failure: a client-side
 * copy of a server predicate drifting from it.
 *
 * The fix is structural, not disciplinary. The server sends its OWN answer —
 * `myPermissions`, a flat array of grant keys, on the workspace and project
 * responses the SPA already fetches — and a UI gate compares against the literal
 * string the server checks. **No component may read `myRole` for a decision**
 * (§5.3): `myRole === 'MANAGER'` cannot express a custom role, so a component
 * that reads it is wrong by construction the moment custom roles exist.
 *
 * Three rules every consumer inherits:
 *
 *  • **A hidden control is never a permission.** The API is the enforcement
 *    boundary; everything here is presentation. A gate that is wrong shows a
 *    button that 403s — never a door that silently opens.
 *  • **A missing answer is never a yes.** An unloaded, failed or empty
 *    permission set denies. `isLoading` distinguishes "not yet" from "no" so a
 *    control can render *disabled* instead of vanishing — see below.
 *  • **`:own` is not a prefix.** See {@link Permissions}.
 */

// ── The catalog (mirrors `common.security.Permission`) ───────────────────────
// Declared as `as const` arrays so each is BOTH a runtime list (fixtures, the
// S6 role editor's grouping) and the literal union below. The server enum stays
// the source of truth — `GET /api/permissions` publishes it — and a key this
// build does not know about is simply never asked about, which denies.

/** The 9 workspace-scoped keys — answered by the workspace response. */
export const WORKSPACE_PERMISSIONS = [
  'workspace.edit',
  'workspace.member.manage',
  'workspace.role.manage',
  'workspace.taxonomy.manage',
  'project.create',
  'project.curate.all',
  'project.administer.all',
  'label.create',
  'label.manage',
] as const

/** The 20 project-scoped keys — answered by the project response. */
export const PROJECT_PERMISSIONS = [
  'project.edit',
  'project.archive',
  'project.member.manage',
  'project.taxonomy.manage',
  'issue.create',
  'issue.edit',
  'issue.transition',
  'issue.assign',
  'issue.assignable',
  'issue.rank',
  'issue.delete',
  'comment.create',
  'comment.edit',
  'comment.delete',
  'attachment.create',
  'attachment.delete',
  'sprint.manage',
  'sprint.assign',
  'version.manage',
  'component.manage',
] as const

/**
 * The 6 keys a grant may narrow to "objects you own" (§6.4) — the issue's
 * reporter, the comment's author, the attachment's uploader, the label's
 * creator. These are the ONLY keys {@link Permissions.canOwn} accepts.
 */
export const OWNABLE_PERMISSIONS = [
  'issue.edit',
  'issue.delete',
  'comment.edit',
  'comment.delete',
  'attachment.delete',
  'label.manage',
] as const

/**
 * Keys that may ONLY ever be granted own-only (§17.3: editing another person's
 * words is not a permission this product ships at any role). Asking
 * {@link Permissions.can} about one could only ever return false, so the type
 * refuses the question instead of answering it misleadingly.
 */
export const OWN_REQUIRED_PERMISSIONS = ['comment.edit'] as const

export type WorkspacePermission = (typeof WORKSPACE_PERMISSIONS)[number]
export type ProjectPermission = (typeof PROJECT_PERMISSIONS)[number]
export type PermissionKey = WorkspacePermission | ProjectPermission
export type OwnablePermission = (typeof OWNABLE_PERMISSIONS)[number]
export type OwnRequiredPermission = (typeof OWN_REQUIRED_PERMISSIONS)[number]

/**
 * The own-only marker in the wire form: `"issue.edit:own"`
 * (`PermissionSet.OWN_SUFFIX` server-side). A permission key can never itself
 * contain a `':'` — the server's `Permission` enum refuses such a key at
 * class-init precisely so this encoding stays unambiguous.
 */
export const OWN_SUFFIX = ':own'

/** Anything the server sends `myPermissions` on: a workspace or a project. */
export interface PermissionCarrier {
  myPermissions?: readonly string[] | null
}

/**
 * The answer to "may I", split the same way the server splits it
 * (`PermissionSet.has(p)` vs `has(p, isOwn)`).
 *
 * **The `:own` trap this shape exists to make unwritable.** `"issue.edit:own"`
 * and `"issue.edit"` are two DISTINCT values, not a prefix relationship. A
 * helper written as `perms.some(p => p.startsWith('issue.edit'))` resolves the
 * ambiguity in the *widening* direction — it reads an own-only grant as
 * unrestricted and hands every reporter an "edit anyone's issue" button. So:
 *
 *  • there is no single string test — {@link can} ("may I do this to
 *    ANYTHING?") and {@link canOwn} ("may I do this to THIS object, which I
 *    own?") are separate methods with different arities;
 *  • `can` is typed to the catalog union, so `can('issue.edit:own')` is a
 *    **compile error**, not a silently-false runtime check;
 *  • `canOwn` requires the ownership fact as an explicit second argument —
 *    computed by the call site, never guessed here — so it cannot be called by
 *    accident where `can` was meant;
 *  • `canOwn` only accepts the 6 keys that actually support the modifier, and
 *    `can` refuses the one key that is own-only by law.
 *
 * Nothing in this module ever calls `startsWith` on a grant: the wire strings
 * are split ONCE, on exact suffix, into two disjoint sets.
 */
export interface Permissions<K extends PermissionKey = PermissionKey> {
  /**
   * May I do this to **any** object of this kind? Mirrors
   * `PermissionSet.has(p)` — an own-only grant answers **false** here, always.
   */
  can(permission: Exclude<K, OwnRequiredPermission>): boolean
  /**
   * May I do this to **this one** object? Mirrors
   * `PermissionSet.has(p, isOwn)`: true when the grant is unrestricted, or when
   * it is own-only *and* `isOwn`.
   *
   * @param isOwn whether the actor owns that object — `issue.reporter.id === me`,
   *              `comment.authorId === me`, `attachment.uploadedById === me`.
   */
  canOwn(permission: K & OwnablePermission, isOwn: boolean): boolean
  /**
   * The answer is not known yet (the backing query is in flight).
   *
   * **The loading rule, decided once, here** (§14.1: *"render controls disabled,
   * never hidden-then-popped-in"*): `can`/`canOwn` return **false** while this is
   * true — a gate never widens on missing data — and a surface picks its
   * rendering from the pair:
   *
   *  • a control that occupies a **permanent slot** (a select, an input, a
   *    button that is always in the layout) stays mounted and renders
   *    `disabled={!can(x)}`. It can only ever go disabled → enabled, so nothing
   *    reflows and nothing is yanked out from under a pointer.
   *  • a control that is **conditionally mounted** (a nav item, a menu entry, a
   *    palette row) is not mounted while loading either. It can only ever pop
   *    in — it can never flash in and then vanish, which is the failure mode
   *    that actually loses a user's click.
   *
   * In practice the window is invisible on a project page: every gate reads a
   * cache entry the nav rail has already populated.
   */
  isLoading: boolean
}

/** Denies everything and is not loading — a resolved "no". */
export const NO_PERMISSIONS: Permissions = build(new Set(), new Set(), false)

function build(
  unrestricted: ReadonlySet<string>,
  ownOnly: ReadonlySet<string>,
  isLoading: boolean,
): Permissions {
  return {
    can: permission => unrestricted.has(permission),
    canOwn: (permission, isOwn) =>
      unrestricted.has(permission) || (isOwn && ownOnly.has(permission)),
    isLoading,
  }
}

/**
 * Build from carriers the caller ALREADY holds — the pure half, for the surfaces
 * that cannot call a hook: the command-palette registry (a plain builder,
 * §HD-116), and any per-row gate over a list (`projects.map(p => …)`), where one
 * hook per row would be both wrong and impossible.
 *
 * Carriers of different scopes compose: the workspace response answers the 9
 * workspace keys and the project response the 20 project ones, and the two key
 * spaces are disjoint, so a union can never be ambiguous. Where both carry the
 * same key the **wider** grant wins, exactly as `PermissionSet.union` does.
 *
 * A `null`/`undefined` carrier contributes nothing — it denies. Pass
 * `isLoading` when that absence is "not yet" rather than "no".
 */
export function permissionsFrom<K extends PermissionKey = PermissionKey>(
  carriers: ReadonlyArray<PermissionCarrier | null | undefined>,
  isLoading = false,
): Permissions<K> {
  const unrestricted = new Set<string>()
  const ownOnly = new Set<string>()
  for (const carrier of carriers) {
    for (const grant of carrier?.myPermissions ?? []) {
      // Exact suffix, never a prefix match: `"issue.edit:own"` yields the key
      // `issue.edit` in the OWN set and nothing at all in the unrestricted one.
      if (grant.endsWith(OWN_SUFFIX)) ownOnly.add(grant.slice(0, -OWN_SUFFIX.length))
      else unrestricted.add(grant)
    }
  }
  // An unrestricted grant always beats an own-only one, never the reverse.
  for (const key of unrestricted) ownOnly.delete(key)
  return build(unrestricted, ownOnly, isLoading) as Permissions<K>
}

/**
 * Project-scoped permissions, from the `['project', wsId, projectId]` entry the
 * nav rail and both settings areas already cache — **zero extra requests** on
 * every board, backlog, issue and releases render.
 *
 * It needs no workspace lookup, and that is a property of the server contract,
 * not an optimisation: `ProjectResponse.myPermissions` already folds in the
 * workspace-level curator bypass (`project.curate.all` →
 * `{project.edit, component.manage, version.manage, sprint.manage}`). The old
 * `useIsProjectCurator(wsId, projectId, needsRole)` had to fetch the workspace
 * to reconstruct that half of the predicate by hand — which is precisely how
 * HD-98 happened.
 *
 * Typed to {@link ProjectPermission}, so asking a workspace-scoped question here
 * (`can('project.create')`) is a compile error rather than a silent false.
 */
export function useProjectPermissions(
  wsId: string | undefined,
  projectId: string | undefined,
): Permissions<ProjectPermission> {
  const { data, isLoading } = useQuery({
    queryKey: ['project', wsId, projectId],
    queryFn: () => apiGetProject(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })
  return useMemo(
    () => permissionsFrom<ProjectPermission>([data], isLoading),
    [data, isLoading],
  )
}

/**
 * Workspace-scoped permissions, plus the project's when a `projectId` is given —
 * the §14.1 entry point, for a surface that gates on both scopes at once.
 *
 * Prefer {@link useProjectPermissions} on a project page that only asks
 * project-scoped questions: this one additionally subscribes to
 * `['workspace', wsId]`, which a plain board does not otherwise need.
 */
export function usePermissions(
  wsId: string | undefined,
  projectId?: string,
): Permissions {
  const workspace = useQuery({
    queryKey: ['workspace', wsId],
    queryFn: () => apiGetWorkspace(wsId!),
    enabled: !!wsId,
  })
  const project = useQuery({
    queryKey: ['project', wsId, projectId],
    queryFn: () => apiGetProject(wsId!, projectId!),
    enabled: !!wsId && !!projectId,
  })
  const isLoading = workspace.isLoading || project.isLoading
  return useMemo(
    () => permissionsFrom([workspace.data, project.data], isLoading),
    [workspace.data, project.data, isLoading],
  )
}

// ── Named composite predicates ──────────────────────────────────────────────
// A gate that more than one surface applies lives HERE, once. Two surfaces
// spelling out the same disjunction is exactly how the rail and the settings
// area drifted apart in HD-98, and how the palette still disagreed with both in
// HD-116 — so "the rail and the palette and the area agree" is a property of
// there being one function, not of three authors being careful.

/**
 * May this actor open **project settings**? The area is a container for three
 * separately-granted things (details/delivery, taxonomy, members), so any one of
 * them earns the door. Applied by `NavRail`, `ProjectSettingsArea` and the
 * command palette — the three surfaces of HD-98 + HD-116.
 */
export function canOpenProjectSettings(permissions: Permissions<ProjectPermission>): boolean {
  return permissions.can('project.edit')
    || permissions.can('project.taxonomy.manage')
    || permissions.can('project.member.manage')
}

/**
 * May this actor open **workspace settings**? Same shape as
 * {@link canOpenProjectSettings}: the area holds workspace taxonomy, the project
 * binding matrix and labels, and any one grant earns the door. Applied by
 * `WorkspaceSettingsArea`, `WorkspaceHomePage` and the command palette.
 */
export function canOpenWorkspaceSettings(permissions: Permissions<WorkspacePermission>): boolean {
  return permissions.can('workspace.edit')
    || permissions.can('workspace.taxonomy.manage')
    || permissions.can('workspace.member.manage')
    || permissions.can('workspace.role.manage')
}
