import type { ReactNode } from 'react'
import { ApiResponseError } from '../api'
import type {
  PermissionCatalogEntry, ProjectRef, Role, RoleAssignmentView, RoleScope, RoleUsage,
  SettableRolesView,
} from '../types'
import { MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING } from '../types'
import { Select } from './ui'

/**
 * Shared vocabulary for the People & Roles screens (HD-123 S6).
 *
 * Three things live here because more than one screen needs them to agree:
 *
 *  • **How a conflict is read.** `classifyConflict` is the ONE implementation of
 *    the documented order — `Retry-After` first, `errorType` second, everything
 *    else "render `detail`, offer no retry". Getting that order wrong is not a
 *    cosmetic bug: an absent discriminator on a retryable body reads as "no
 *    recovery I know about", which is the single wrong answer for a request that
 *    is only asking to be re-sent.
 *  • **What a permission key means in prose.** The catalog is fetched from
 *    `GET /api/permissions` and is authoritative; this table only adds the
 *    human sentence beside each key. A key with no entry here still renders — it
 *    shows its own key and stays fully checkable — because the catalog grows
 *    between releases and a role editor that silently omitted an unknown grant
 *    would quietly narrow every role saved through it.
 *  • **The derived `assignment` block, rendered.** Never re-derived: the ceiling
 *    is set containment with per-grant width, and a second implementation of a
 *    server predicate in the SPA is the HD-98 / HD-116 bug class by construction.
 */

// ── Conflict classification ─────────────────────────────────────────────────

export type ConflictKind =
  /**
   * **Any refusal carrying `Retry-After`**, whatever its status — a 409 from a
   * lost row-lock race, or a 429 from a budget (the invite ceilings, HD-190).
   * They share the one property a caller acts on: nothing was changed, and the
   * IDENTICAL request re-sent after the wait is the whole remedy. None carries
   * an `errorType`, and which ceiling or which lock refused is legible only from
   * `detail` — so render that and never branch on its wording.
   *
   * `seconds` is the raw header value. A screen whose `detail` already names the
   * wait in prose should print the sentence and leave `seconds` alone rather
   * than round a second countdown that can disagree with it.
   */
  | { kind: 'retry'; seconds: number; detail: string }
  /** Removing this member would leave `projects` with no administrator. Adoption clears it. */
  | { kind: 'stranded'; projects: ProjectRef[]; detail: string }
  /**
   * The listed projects have administrators **only by inheritance**, and this
   * change takes that inheritance away (HD-130 S7, doors 6–9: a workspace
   * removal, the OPEN→STRICT flip, or either default-role picker).
   *
   * **Deliberately not `stranded`, and there is no adoption retry.** Adoption
   * writes the caller a Team lead row, and here the caller has no row at all —
   * they inherit a default at least as wide as `project.member.manage` and
   * possibly much wider, so "adopting" would *narrow the adopter* in the very
   * project they were rescuing. The remedy is an action the refused person can
   * perform right now, because the default that is about to matter still grants
   * it to them today: add an explicit administrator to each project first.
   *
   * It is re-derived inside the write's transaction, so it can arrive **after a
   * clean-looking preview** — which is why it must render as itself rather than
   * as a generic error.
   */
  | { kind: 'strandedByInheritance'; projects: ProjectRef[]; detail: string }
  /** The adoption itself is refused — the retry fails identically, so offer none. */
  | { kind: 'adoptionBlocked'; projects: ProjectRef[]; detail: string }
  /** The adoption stopped on a membership row whose stored role cannot be read. Needs an operator. */
  | { kind: 'adoptionUnreadable'; projects: ProjectRef[]; detail: string }
  /** The role still has holders; the refusal carries the whole usage object. */
  | { kind: 'roleInUse'; usage?: RoleUsage; detail: string }
  /** You may not widen — or delete — a role you hold yourself. No flag clears it. */
  | { kind: 'selfHeld'; detail: string }
  /** The change would demote every administrator of `projects` at once. No adoption here. */
  | { kind: 'lastProjectAdminBulk'; projects: ProjectRef[]; detail: string }
  /** The workspace is at its custom-role cap. Do not retry. */
  | { kind: 'roleLimit'; detail: string }
  /**
   * **An unaccepted invitation to this address already stands in this
   * workspace** (HD-133) — one standing offer per address per workspace, so a
   * second one is refused rather than queued or silently replacing the first.
   *
   * The identical request will fail identically, so there is no retry: the
   * remedy `detail` names is to withdraw the blocking invitation on Workspace
   * settings → People and invite again. Two wordings arrive under this one code
   * — the blocking row is still live, or it has lapsed and is merely on file
   * (an expired invitation occupies the slot, because the constraint cannot
   * read a clock). That difference is the useful part and lives ONLY in
   * `detail`, so render the sentence and never branch on its wording.
   *
   * Named separately from `plain` for one reason: the screen that shows this
   * refusal also lists the blocking invitation, and it must refresh that list —
   * a stale copy is how an administrator concludes the refusal is wrong. The
   * "already a member" 409 carries **no** `errorType` on purpose and wins when
   * both apply, so it lands on `plain` and moves no list.
   */
  | { kind: 'duplicateInvite'; detail: string }
  /**
   * A plain conflict — the last Owner, the last project administrator, a built-in
   * role, a stale `version`, a taken name — **or an `errorType` this build does
   * not recognise**, which is deliberately treated the same way: render `detail`,
   * offer no retry.
   */
  | { kind: 'plain'; detail: string }

/**
 * Read a refusal in the one documented order. It is keyed on what the response
 * CARRIES, not on its status: a body with `Retry-After` is `retry` whether it is
 * a 409 lock race or a 429 budget refusal (the invite ceilings, HD-190).
 * Anything with no discriminator at all (403 ceiling refusals, 422 unusable role
 * ids) falls through to `plain`, whose `detail` is exactly the server's sentence
 * — which for a ceiling refusal *names the offending permission*, so it must be
 * rendered rather than replaced with "Forbidden".
 */
export function classifyConflict(err: unknown): ConflictKind {
  const detail = err instanceof Error ? err.message : 'Something went wrong.'
  if (!(err instanceof ApiResponseError)) return { kind: 'plain', detail }
  // 1. Retry-After FIRST, on ANY status, before any look at `errorType`.
  if (err.retryAfter !== undefined) {
    return { kind: 'retry', seconds: err.retryAfter, detail }
  }
  const projects = err.projects ?? []
  switch (err.errorType) {
    case 'STRANDED_PROJECTS': return { kind: 'stranded', projects, detail }
    case 'STRANDED_BY_INHERITANCE': return { kind: 'strandedByInheritance', projects, detail }
    case 'ADOPTION_BLOCKED': return { kind: 'adoptionBlocked', projects, detail }
    case 'ADOPTION_ROLE_UNREADABLE': return { kind: 'adoptionUnreadable', projects, detail }
    case 'LAST_PROJECT_ADMIN_BULK': return { kind: 'lastProjectAdminBulk', projects, detail }
    case 'ROLE_IN_USE': return { kind: 'roleInUse', usage: err.usage, detail }
    case 'SELF_HELD_ROLE': return { kind: 'selfHeld', detail }
    case 'ROLE_LIMIT_REACHED': return { kind: 'roleLimit', detail }
    case 'DUPLICATE_INVITE': return { kind: 'duplicateInvite', detail }
    // An unrecognised (or absent) errorType is "no retry available".
    default: return { kind: 'plain', detail }
  }
}

// ── Permission prose ────────────────────────────────────────────────────────

interface PermissionCopy {
  /** A short imperative title for the checkbox row. */
  title: string
  /** One line of prose under it. */
  blurb: string
}

/**
 * The one-line description beside each catalog key. Deliberately a lookup rather
 * than a union: an unknown key is rendered from the key itself (see
 * {@link permissionCopy}), never dropped.
 */
const PERMISSION_COPY: Record<string, PermissionCopy> = {
  // workspace scope
  'workspace.edit': {
    title: 'Edit the workspace',
    blurb: 'Rename or describe it, and set how projects are accessed by default.',
  },
  'workspace.member.manage': {
    title: 'Manage people',
    blurb: 'Invite people, change a member’s workspace role, remove a member.',
  },
  'workspace.role.manage': {
    title: 'Manage roles',
    blurb: 'Create, edit and delete this workspace’s own roles. Deliberately separate from managing people.',
  },
  'workspace.taxonomy.manage': {
    title: 'Manage workspace taxonomy',
    blurb: 'Workspace statuses, workflows, issue types, priorities and fields, and the project-binding matrix.',
  },
  'project.create': {
    title: 'Create projects',
    blurb: 'Open a new project in this workspace.',
  },
  'project.curate.all': {
    title: 'Curate every project',
    blurb: 'Edit any project and manage its components, versions and sprints without being a member of it.',
  },
  'project.administer.all': {
    title: 'Administer every project',
    blurb: 'Hold every project permission in every project, without being a member. No built-in role grants this — only an Owner can hand it out.',
  },
  'label.create': { title: 'Create labels', blurb: 'Add a new workspace label.' },
  'label.manage': {
    title: 'Manage labels',
    blurb: 'Rename, recolour and describe labels; archiving, merging and deleting need it unrestricted.',
  },
  // project scope
  'project.edit': {
    title: 'Edit the project',
    blurb: 'Rename or describe it, and change how the team delivers.',
  },
  'project.archive': { title: 'Archive the project', blurb: 'Archive it, and restore it afterwards.' },
  'project.member.manage': {
    title: 'Manage project members',
    blurb: 'Add and remove members, change their project role, set the project’s default role.',
  },
  'project.taxonomy.manage': {
    title: 'Manage project taxonomy',
    blurb: 'This project’s private statuses, workflows, types, priorities and fields, and its bindings.',
  },
  'issue.create': { title: 'Create issues', blurb: 'File new work in this project.' },
  'issue.edit': {
    title: 'Edit issues',
    blurb: 'Title, description, type, priority, labels, component, versions, parent, due date, points, custom fields.',
  },
  'issue.transition': { title: 'Change status', blurb: 'Move an issue through the workflow — board drag included.' },
  'issue.assign': { title: 'Assign issues', blurb: 'Set or clear an assignee, including assigning yourself.' },
  'issue.assignable': {
    title: 'Can be assigned work',
    blurb: 'Checked against the person being assigned, not the one assigning. Withhold it and this role never appears in an assignee picker.',
  },
  'issue.rank': { title: 'Reorder the backlog', blurb: 'Change the rank issues sit in on the backlog and the board.' },
  'issue.delete': { title: 'Delete issues', blurb: 'Permanently remove an issue.' },
  'comment.create': { title: 'Comment', blurb: 'Post a comment on an issue.' },
  'comment.edit': {
    title: 'Edit comments',
    blurb: 'Always own-only: editing someone else’s words is not something this product grants at any role.',
  },
  'comment.delete': { title: 'Delete comments', blurb: 'Own-only is your own; unrestricted is moderation.' },
  'attachment.create': { title: 'Upload files', blurb: 'Attach a file to an issue.' },
  'attachment.delete': { title: 'Delete files', blurb: 'Own-only is the files you uploaded.' },
  'sprint.manage': { title: 'Manage sprints', blurb: 'Create, rename, start, complete and delete sprints.' },
  'sprint.assign': { title: 'Put issues in sprints', blurb: 'Move issues into and out of a sprint.' },
  'version.manage': { title: 'Manage releases', blurb: 'Create, edit, release, archive and delete versions.' },
  'component.manage': { title: 'Manage components', blurb: 'Create, edit, archive and delete components.' },
}

export function permissionCopy(key: string): PermissionCopy {
  return PERMISSION_COPY[key] ?? { title: key, blurb: 'This build has no description for this permission yet.' }
}

/** How the editor names a capability, for the muted tag and the "hidden" notice. */
export const CAPABILITY_TAG: Record<string, string> = {
  BOARD: 'sprint planning',
  RELEASES: 'releases',
}

// ── Grouping ────────────────────────────────────────────────────────────────

export interface PermissionGroup {
  id: string
  label: string
  entries: PermissionCatalogEntry[]
}

/**
 * Group labels keyed by `scope:area`, where the area is the key's first segment.
 * `project.*` genuinely means two different things in the two scopes — "create a
 * project here" versus "edit this project" — so the scope is part of the key.
 */
const GROUP_LABEL: Record<string, string> = {
  'WORKSPACE:workspace': 'Workspace',
  'WORKSPACE:project': 'Projects',
  'WORKSPACE:label': 'Labels',
  'PROJECT:project': 'Project',
  'PROJECT:issue': 'Issues',
  'PROJECT:comment': 'Comments',
  'PROJECT:attachment': 'Files',
  'PROJECT:sprint': 'Sprints',
  'PROJECT:version': 'Releases',
  'PROJECT:component': 'Components',
}

/**
 * Group catalog entries by area, **preserving catalog order** — for the groups
 * and inside them. An area this build has no label for still renders, under its
 * own capitalised area name: a permission the server enforces must never be
 * invisible in the editor that composes roles.
 */
export function groupPermissions(
  entries: PermissionCatalogEntry[], scope: RoleScope,
): PermissionGroup[] {
  const groups: PermissionGroup[] = []
  for (const entry of entries) {
    const area = entry.key.split('.')[0]
    const id = `${scope}:${area}`
    let group = groups.find(g => g.id === id)
    if (!group) {
      group = { id, label: GROUP_LABEL[id] ?? area.charAt(0).toUpperCase() + area.slice(1), entries: [] }
      groups.push(group)
    }
    group.entries.push(entry)
  }
  return groups
}

// ── Small presentational bits ───────────────────────────────────────────────

/**
 * Header for a settings screen that is NOT part of an admin console.
 *
 * Deliberately not `admin/common`'s `PageHeader`: that one reads its eyebrow
 * from `AdminApiContext`, so mounting it outside an `AdminApiProvider` throws. The
 * People and Roles screens are ordinary settings pages, not scoped catalog
 * consoles, and must not have to fake a provider to render a title.
 */
export function SettingsPageHeader({ title, subtitle, action }: {
  title: string
  subtitle: string
  action?: ReactNode
}) {
  return (
    <div className="flex items-start justify-between mb-5">
      <div>
        <h1 className="font-display font-bold" style={{ fontSize: 24, letterSpacing: '-0.4px' }}>{title}</h1>
        {/* Inline maxWidth: our @theme --spacing-* scale shadows Tailwind's
            max-w-{xs..3xl} sizes (max-w-xl would be 32px) — see CLAUDE.md */}
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 620 }}>{subtitle}</p>
      </div>
      {action}
    </div>
  )
}

/**
 * A soft tint over a semantic token — DESIGN.md's "color + 18–20 alpha on
 * light", expressed as a `color-mix` over the token rather than as a new hex.
 * The palette is remapped by value in `index.css`, so anything written as a
 * literal silently stops following the theme.
 */
function tint(token: string, percent: number): string {
  return `color-mix(in srgb, var(${token}) ${percent}%, white)`
}

/**
 * A banner carrying a refusal, a warning or a consequence. One component so the
 * six dialogs in this feature cannot each invent their own amber.
 */
export function Notice({ tone, children }: { tone: 'warning' | 'danger' | 'info'; children: ReactNode }) {
  const token = tone === 'danger' ? '--color-error' : tone === 'warning' ? '--color-warning' : '--color-sandbox'
  return (
    <div className="text-sm rounded-lg px-3 py-2.5 flex flex-col gap-2"
         style={{ background: tint(token, 10), border: `1px solid ${tint(token, 34)}`,
                  color: 'var(--color-text)' }}>
      {children}
    </div>
  )
}

export function Chip({ children, tone = 'neutral', title }: {
  children: ReactNode
  tone?: 'neutral' | 'brand' | 'warning' | 'danger'
  title?: string
}) {
  const tones: Record<string, { color: string; background: string }> = {
    neutral: { color: 'var(--color-text-muted)', background: 'var(--color-surface-2)' },
    brand: { color: 'var(--color-brand)', background: tint('--color-brand', 12) },
    warning: { color: 'var(--color-warning)', background: tint('--color-warning', 14) },
    danger: { color: 'var(--color-error)', background: tint('--color-error', 12) },
  }
  return (
    <span className="text-xs px-2 py-0.5 rounded-full whitespace-nowrap" title={title} style={tones[tone]}>
      {children}
    </span>
  )
}

/**
 * A member's role, as a label. Never a decision — `myRole` and a member's `role`
 * are open strings that a workspace may define, so nothing branches on the value.
 * A `null` role is the server refusing to describe a corrupt row; it renders as
 * "unknown role" and never guesses a substitute.
 */
export function RoleLabel({ role }: { role: string | null | undefined }) {
  if (!role) {
    return (
      <span className="text-xs italic" title="The stored role for this member could not be read — ask an administrator to check it."
            style={{ color: 'var(--color-text-muted)' }}>
        unknown role
      </span>
    )
  }
  return <span className="text-sm">{role}</span>
}

/** Where a role is in play, as one sentence. `null` usage means "not asked for". */
export function usageSummary(usage: RoleUsage | null | undefined): string | null {
  if (!usage) return null
  const parts: string[] = []
  if (usage.members > 0) parts.push(`${usage.members} member${usage.members !== 1 ? 's' : ''}`)
  if (usage.invites > 0) parts.push(`${usage.invites} pending invite${usage.invites !== 1 ? 's' : ''}`)
  if (usage.projectMembers > 0) {
    parts.push(`${usage.projectMembers} project member${usage.projectMembers !== 1 ? 's' : ''}`
      + (usage.projects > 0 ? ` in ${usage.projects} project${usage.projects !== 1 ? 's' : ''}` : ''))
  }
  if (usage.defaultForProjects > 0) parts.push(`the default in ${usage.defaultForProjects} project${usage.defaultForProjects !== 1 ? 's' : ''}`)
  if (usage.defaultForWorkspace) parts.push('the workspace default')
  return parts.length > 0 ? parts.join(' · ') : 'not in use'
}

/**
 * The server-derived answer to *"who could a holder of this role hand out?"*,
 * rendered while the role is being composed.
 *
 * The phrasing is "on its own" on purpose: the block is computed from the role
 * alone and ignores what a real holder additionally gets from their workspace
 * role, so it is a **lower bound** — the conservative direction, and the one the
 * warning is about.
 */
export function AssignmentPanel({ assignment, pending }: {
  assignment: RoleAssignmentView | null
  pending?: boolean
}) {
  const warns = assignment?.warnings.includes(MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING)
  return (
    <div className="rounded-lg border p-3 flex flex-col gap-2"
         style={{ borderColor: 'var(--color-border)', background: 'var(--color-surface)',
                  opacity: pending ? 0.6 : 1 }}>
      <div className="text-xs font-semibold uppercase tracking-wider"
           style={{ color: 'var(--color-text-muted)' }}>
        What this role can hand out
      </div>
      {!assignment && (
        <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>checking…</span>
      )}
      {assignment && (
        <>
          <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            {assignment.managesMembers
              ? 'It can manage a roster.'
              : 'It cannot manage a roster, so it hands out nothing today.'}
          </p>
          {assignment.canAssign.length > 0 && (
            <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              On its own, this role can assign:{' '}
              <b>{assignment.canAssign.map(r => r.name).join(', ')}</b>
            </p>
          )}
          {assignment.cannotAssign.length > 0 && (
            <div className="flex flex-col gap-1">
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>It cannot assign:</span>
              {assignment.cannotAssign.map(blocker => (
                <span key={blocker.roleId} className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  · <b>{blocker.name}</b> — missing{' '}
                  <span className="mono" style={{ color: 'var(--color-text)' }}>{blocker.missing}</span>
                </span>
              ))}
            </div>
          )}
          {warns && (
            <Notice tone="warning">
              <span className="text-xs">
                This role can add people to a roster but cannot give any of them the ability to do
                anything. That is allowed — but it is rarely what was meant. Tick the permissions the
                roles it should hand out already grant.
              </span>
            </Notice>
          )}
          <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
            A lower bound: a real holder may also hold workspace-wide project permissions, which
            this does not count.
          </p>
        </>
      )}
    </div>
  )
}

/** A list of projects in the way — the `projects` extension, rendered. */
export function ProjectRefList({ projects }: { projects: ProjectRef[] }) {
  if (projects.length === 0) return null
  return (
    <ul className="flex flex-col gap-1 m-0 p-0" style={{ listStyle: 'none' }}>
      {projects.map(p => (
        <li key={p.id} className="text-sm flex items-center gap-2">
          <span className="mono text-xs px-1.5 py-0.5 rounded"
                style={{ background: 'var(--color-surface-2)', color: 'var(--color-text-secondary)' }}>
            {p.key}
          </span>
          {/* `<bdi>`, not `<span>`: this list sits inside a sentence that discloses
              a durable privilege grant, and a bidi control at the head of a
              project name reorders whatever follows it. Isolation costs nothing
              and does not depend on what the backend happens to validate. */}
          <bdi style={{ color: 'var(--color-text)' }}>{p.name}</bdi>
        </li>
      ))}
    </ul>
  )
}

/**
 * Resolve a role from an **id** — the only identifier that answers the question.
 *
 * A key does not: `MEMBER` is a built-in key in *both* scopes (workspace Member
 * and project Contributor, two ids, two different permission sets), so resolving
 * a member's role by key against a catalog spanning both scopes can name the
 * wrong privilege today, with no custom role involved. Membership responses
 * therefore carry `roleId`, and every picker binds to it.
 *
 * **Absent is a real answer and must not be papered over.** `roleId` is `null`
 * exactly when `role` is — the server refusing to describe a wrong-scope or
 * foreign row, having withheld the id for the same reason it withheld the name.
 * An id that IS present but matches nothing here is the other case (a plain FK
 * the database cannot constrain to this workspace's roles of this scope). Both
 * return `null`, and the caller renders a placeholder: the one thing worse than
 * an unlabelled role is a confidently wrong label on a privilege.
 */
export function resolveRoleById(roles: Role[], roleId: string | null | undefined): Role | null {
  if (!roleId) return null
  return roles.find(r => r.id === roleId) ?? null
}

/** Which link of the §5.2 chain supplied a project's default access. */
export type DefaultRoleOrigin = 'PROJECT' | 'WORKSPACE' | 'BUILT_IN'

export interface ResolvedDefaultRole {
  origin: DefaultRoleOrigin
  /** The role itself, or `null` when the chain names an id this workspace cannot describe. */
  role: Role | null
  /** True when a link named an id that resolves to nothing — render a placeholder, not a guess. */
  unresolvable: boolean
}

/**
 * Walk the default-access chain — **project override → workspace default →
 * built-in Contributor** — and report both the role and the link it came from.
 *
 * Both links ship on the response precisely because a card showing one value
 * cannot say where the value came from, and "this project overrides the
 * workspace" and "this project inherits" are different facts about the same
 * name. The fallback is identified by `builtIn && key === 'MEMBER'` **within the
 * PROJECT-scoped list** — the one place a key is safe to read, because the scope
 * has already been fixed by the caller and a custom role can never take a
 * built-in's key (they are server-generated and suffixed on collision).
 */
export function resolveDefaultRole(
  projectRoles: Role[],
  defaultRole: { projectRoleId: string | null; workspaceRoleId: string | null } | undefined,
): ResolvedDefaultRole {
  for (const [origin, id] of [
    ['PROJECT', defaultRole?.projectRoleId],
    ['WORKSPACE', defaultRole?.workspaceRoleId],
  ] as const) {
    if (!id) continue
    const role = resolveRoleById(projectRoles, id)
    return { origin, role, unresolvable: role === null }
  }
  const contributor = projectRoles.find(r => r.builtIn && r.key === 'MEMBER') ?? null
  return { origin: 'BUILT_IN', role: contributor, unresolvable: false }
}

/**
 * A **refusal**, rendered — the one place the S7 pickers and the access switch
 * turn a `ConflictKind` into prose, so no two of them invent a different sentence
 * for the same 409.
 *
 * Three cases get their own treatment and everything else renders `detail`
 * verbatim (a ceiling 403's `detail` *names the permission the actor lacks*,
 * which is the only actionable part of it — replacing it with "Forbidden" throws
 * away the answer):
 *
 *  • **`retry`** — `Retry-After` was present, so nothing happened and the
 *    identical request is the fix. Read first, before any look at `errorType`.
 *  • **`strandedByInheritance`** — the change would leave the listed projects with
 *    nobody able to manage their membership. No adoption retry exists for it, so
 *    none is offered; the remedy names something the refused person can do now.
 *  • **`stranded`** — the workspace-removal shape, which *does* have an adoption
 *    path. It is not reachable from these screens, but classifying it as its
 *    cousin would silently offer the wrong remedy, so it is named separately.
 */
export function ConflictNotice({ refusal, children }: { refusal: ConflictKind; children?: ReactNode }) {
  const stranding = refusal.kind === 'strandedByInheritance'
  return (
    <Notice tone={refusal.kind === 'retry' ? 'warning' : 'danger'}>
      <span className="text-sm">{refusal.detail}</span>
      {'projects' in refusal && refusal.projects.length > 0 && (
        <ProjectRefList projects={refusal.projects} />
      )}
      {refusal.kind === 'retry' && (
        <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          Somebody else was changing this at the same moment, so nothing was changed at all. Try
          again in {refusal.seconds} second{refusal.seconds !== 1 ? 's' : ''}.
        </span>
      )}
      {stranding && (
        <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          Nothing was changed. There is no “take them over” option here on purpose: whoever
          administers {refusal.projects.length === 1 ? 'that project' : 'those projects'} does it
          through the default that is about to go away, so taking it over would give them
          <i> less </i>than they have now. Add an explicit administrator to each first — the current
          default still lets you.
        </span>
      )}
      {children}
    </Notice>
  )
}

/**
 * A role picker over the roles of one scope. Renders the CURRENT value even when
 * it is not in the list (a role the caller cannot assign, or one that was just
 * deleted), so a select never silently reports a role the member does not hold.
 */
export function RoleSelect({ roles, value, onChange, disabled, label, ariaLabel, title, compact, placeholder }: {
  roles: Role[]
  value: string
  onChange: (roleId: string) => void
  disabled?: boolean
  label?: string
  ariaLabel?: string
  title?: string
  compact?: boolean
  /**
   * Rendered as the empty-valued first option. **Required whenever `value` may be
   * empty:** the shared `Select` falls back to its FIRST option when no option
   * matches, so an unresolved current role would otherwise be drawn as whichever
   * role happens to sort first — a confidently wrong label on a privilege.
   */
  placeholder?: string
}) {
  return (
    <Select label={label} aria-label={ariaLabel} title={title} compact={compact}
            value={value} disabled={disabled}
            onChange={e => onChange(e.target.value)}>
      <>
        {placeholder !== undefined && <option value="">{placeholder}</option>}
        {roles.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
      </>
    </Select>
  )
}

/**
 * The **default-role picker**, bounded by the server's own ceiling (HD-130 S7).
 *
 * The difference from {@link RoleSelect} is the whole point of the slice: a role
 * the actor may not make a default is rendered **disabled with the reason** —
 * the first permission they lack, the identical string the runtime 403 would
 * quote — rather than being offered and refused at save. That is the same
 * discipline the role editor applies with `canAssign`/`cannotAssign`.
 *
 * `settable` is **server-derived and never recomputed here**, and the two scopes
 * are asymmetric: the workspace comparand is a constant (built-in Contributor ∪
 * the actor's workspace-wide project grants), the project one is the actor's real
 * effective set in that project. So a role settable at one scope may be refused
 * at the other, and a block from one picker must never be reused for the other.
 *
 * A role in neither list is still rendered and still selectable: `settable` is
 * guidance, the API is the boundary, and silently dropping a role the server
 * would have accepted is the failure mode that hides a legitimate action.
 */
export function DefaultRoleSelect({ roles, settable, value, onChange, disabled, label, ariaLabel, placeholder }: {
  roles: Role[]
  /** `undefined` while the read is in flight — nothing is marked blocked yet. */
  settable: SettableRolesView | undefined
  value: string
  onChange: (roleId: string) => void
  disabled?: boolean
  label?: string
  ariaLabel?: string
  placeholder?: string
}) {
  const blocked = new Map((settable?.cannotSet ?? []).map(b => [b.roleId, b.missing]))
  return (
    <Select label={label} aria-label={ariaLabel} value={value} disabled={disabled}
            onChange={e => onChange(e.target.value)}>
      <>
        {placeholder !== undefined && <option value="">{placeholder}</option>}
        {roles.map(r => {
          const missing = blocked.get(r.id)
          return (
            <option key={r.id} value={r.id} disabled={missing !== undefined}
                    title={missing !== undefined ? `Requires ${missing}` : undefined}>
              {missing !== undefined ? `${r.name} — requires ${missing}` : r.name}
            </option>
          )
        })}
      </>
    </Select>
  )
}

/**
 * The refusals from `settable`, listed under a picker — because a disabled option
 * inside a closed dropdown is invisible until somebody goes looking, and "why
 * can't I choose that?" is the question this block exists to answer before it is
 * asked.
 */
export function CeilingSummary({ settable }: { settable: SettableRolesView | undefined }) {
  if (!settable || settable.cannotSet.length === 0) return null
  return (
    <div className="flex flex-col gap-1 mt-2">
      <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
        You cannot make {settable.cannotSet.length === 1 ? 'this role' : 'these roles'} the default,
        because {settable.cannotSet.length === 1 ? 'it grants' : 'they grant'} more than you hold
        yourself:
      </span>
      {settable.cannotSet.map(blocker => (
        <span key={blocker.roleId} className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          · <b>{blocker.name}</b> — you are missing{' '}
          <span className="mono" style={{ color: 'var(--color-text)' }}>{blocker.missing}</span>
        </span>
      ))}
    </div>
  )
}
