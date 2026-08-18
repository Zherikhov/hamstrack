import { useEffect, useState } from 'react'
import { useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Lock, Unlock } from 'lucide-react'
import { apiGetWorkspace, apiUpdateWorkspace } from '../../api'
import { usePermissions } from '../../hooks/usePermissions'
import { sortRoles, useProjectAccess, useRoleInvalidation, useRoles } from '../../hooks/useRoles'
import { Button, Input } from '../../components/ui'
import {
  CeilingSummary, ConflictNotice, DefaultRoleSelect, Notice, SettingsPageHeader, classifyConflict,
} from '../../components/roles'
import type { ConflictKind } from '../../components/roles'
import ProjectAccessConfirmDialog from '../../components/ProjectAccessConfirmDialog'
import type { ProjectAccessMode, SettableRolesView } from '../../types'

/**
 * Workspace settings → **General** (HD-123 S7 · HD-130 §9.1).
 *
 * Three cards, one read (`GET /project-access`), one gate (`workspace.edit`).
 *
 * ## What the access mode actually is, since the obvious reading is wrong
 *
 * It decides **one thing**: whether a workspace member with no explicit project
 * membership inherits that project's default role. It is not an on/off switch for
 * permissions, and there is deliberately **no "enforcement off" mode** — a
 * permission always means what it says. `OPEN` is the identity: it is exactly how
 * the product behaved before this release, so a workspace that never opens this
 * page is byte-identically the workspace it was.
 *
 * `STRICT` narrows **writes only**. Everyone still sees every project; narrowing
 * reads would be private projects by the back door, which this model explicitly
 * refuses.
 *
 * ## Why restricting goes through a dialog and opening does not
 *
 * The guard on all of this is one-way by construction: it compares the fallback
 * before against the fallback after, so a change that *widens* — flipping back to
 * Open, or naming a more capable default — can never leave a project without an
 * administrator. Restricting can, and it can also take away everybody's ability
 * to file an issue in a project nobody was explicitly added to. So restricting
 * gets the preview, and opening is one click.
 *
 * ## The declared default lives in both modes, and that is not a bug
 *
 * In `STRICT` it is inert but stored, and it goes live the moment somebody flips
 * back — which is exactly why the picker below is bounded by the grant ceiling in
 * both modes. A bound that only bit while Open would be one you step over by
 * flipping twice.
 */
export default function WorkspaceGeneralPage() {
  const { wsId } = useParams<{ wsId: string }>()
  const permissions = usePermissions(wsId)
  const canEdit = permissions.can('workspace.edit')
  const invalidate = useRoleInvalidation(wsId)

  const { data: workspace } = useQuery({
    queryKey: ['workspace', wsId],
    queryFn: () => apiGetWorkspace(wsId!),
    enabled: !!wsId,
  })
  // Held until the permission answer is in: the endpoint is gated on
  // `workspace.edit`, so firing it for anyone else is a guaranteed 403.
  const { data: access, isLoading } = useProjectAccess(wsId, canEdit)
  const { data: roleList = [] } = useRoles(wsId, { scope: 'PROJECT' })
  const roles = sortRoles(roleList)

  if (!permissions.isLoading && !canEdit) {
    return (
      <div>
        <SettingsPageHeader
          title="General"
          subtitle="How this workspace is named, and how people get access to the projects inside it."
        />
        <Notice tone="info">
          <span className="text-sm">
            Changing these needs the <span className="mono">workspace.edit</span> permission, which
            your role here does not grant. Everything on this page is a workspace-wide setting, so
            it is deliberately narrow.
          </span>
        </Notice>
      </div>
    )
  }

  return (
    <div>
      <SettingsPageHeader
        title="General"
        subtitle="How this workspace is named, and how people get access to the projects inside it. Both settings apply to every project at once."
      />

      <div className="flex flex-col gap-5">
        <NameCard wsId={wsId!} name={workspace?.name} onSaved={invalidate} />
        <ProjectAccessCard
          wsId={wsId!}
          mode={access?.mode}
          loading={isLoading}
          projectsWithNoExplicitMembers={access?.impact.projectsWithNoExplicitMembers ?? 0}
          projects={access?.impact.projects ?? 0}
          onChanged={invalidate}
        />
        <DefaultRoleCard
          wsId={wsId!}
          roles={roles}
          mode={access?.mode}
          currentRoleId={access?.defaultProjectRoleId ?? null}
          settable={access?.settable}
          loading={isLoading}
          onChanged={invalidate}
        />
      </div>
    </div>
  )
}

// ── 1. Name ─────────────────────────────────────────────────────────────────

/** The first rename affordance this product has ever had. */
function NameCard({ wsId, name, onSaved }: {
  wsId: string
  name: string | undefined
  onSaved: () => Promise<void>
}) {
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [refusal, setRefusal] = useState<ConflictKind | null>(null)

  // Seed from the server value once it lands, and re-seed whenever it changes
  // underneath us — a draft that silently diverged from the stored name would be
  // saved over somebody else's rename.
  useEffect(() => { setDraft(name ?? '') }, [name])

  const trimmed = draft.trim()
  const dirty = trimmed.length > 0 && trimmed !== (name ?? '')

  async function save() {
    setBusy(true)
    setRefusal(null)
    try {
      await apiUpdateWorkspace(wsId, { name: trimmed })
      await onSaved()
    } catch (err) {
      setRefusal(classifyConflict(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title="Workspace name" blurb="Shown in the switcher, on invitations, and at the top of every settings screen.">
      <div className="flex items-end gap-3">
        <div className="flex-1" style={{ maxWidth: 360 }}>
          <Input label="Name" value={draft} maxLength={255}
                 onChange={e => setDraft(e.target.value)} />
        </div>
        <Button variant="primary" loading={busy} disabled={!dirty} onClick={save}>Save</Button>
      </div>
      {refusal && <div className="mt-3"><ConflictNotice refusal={refusal} /></div>}
    </Card>
  )
}

// ── 2. Project access ───────────────────────────────────────────────────────

const MODE_COPY: Record<ProjectAccessMode, { label: string; blurb: string }> = {
  OPEN: {
    label: 'Open',
    blurb: 'Everyone in the workspace can work in every project, using each project’s default role. '
      + 'Add someone to a project only to give them a different role.',
  },
  STRICT: {
    label: 'Restricted',
    blurb: 'Only people added to a project can change anything in it. Everyone can still see every project.',
  },
}

function ProjectAccessCard({ wsId, mode, loading, projects, projectsWithNoExplicitMembers, onChanged }: {
  wsId: string
  mode: ProjectAccessMode | undefined
  loading: boolean
  projects: number
  projectsWithNoExplicitMembers: number
  onChanged: () => Promise<void>
}) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [refusal, setRefusal] = useState<ConflictKind | null>(null)

  async function choose(next: ProjectAccessMode) {
    if (next === mode) return
    setRefusal(null)
    // Restricting removes inheritance and needs the preview. Opening only ever
    // widens, so the stranding guard cannot fire and a dialog would be theatre.
    if (next === 'STRICT') { setConfirming(true); return }
    setBusy(true)
    try {
      await apiUpdateWorkspace(wsId, { projectAccessMode: 'OPEN' })
      await onChanged()
    } catch (err) {
      setRefusal(classifyConflict(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title="Project access"
          blurb="Whether people who were never added to a project can work in it. This is the only thing the setting changes — permissions themselves always mean what they say, in both modes.">
      <div className="flex flex-col gap-2">
        {(['OPEN', 'STRICT'] as const).map(value => (
          <label key={value}
                 className="flex items-start gap-3 rounded-lg border p-3 cursor-pointer"
                 style={{
                   borderColor: mode === value ? 'var(--color-brand)' : 'var(--color-border)',
                   background: mode === value
                     ? 'color-mix(in srgb, var(--color-brand) 6%, white)'
                     : 'var(--color-card)',
                 }}>
            <input type="radio" name="project-access-mode" value={value}
                   checked={mode === value}
                   // A permanent slot: disabled until the answer lands, never absent.
                   disabled={loading || busy || mode === undefined}
                   onChange={() => choose(value)}
                   style={{ marginTop: 3, accentColor: 'var(--color-brand)' }} />
            <span className="flex-1 min-w-0">
              <span className="text-sm font-semibold flex items-center gap-2"
                    style={{ color: 'var(--color-text)' }}>
                {value === 'OPEN' ? <Unlock size={14} /> : <Lock size={14} />}
                {MODE_COPY[value].label}
              </span>
              <span className="text-sm block mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
                {MODE_COPY[value].blurb}
              </span>
            </span>
          </label>
        ))}
      </div>

      {mode === 'OPEN' && projectsWithNoExplicitMembers > 0 && (
        <p className="text-xs mt-3" style={{ color: 'var(--color-text-muted)' }}>
          {projectsWithNoExplicitMembers} of {projects} project
          {projects !== 1 ? 's' : ''} here {projectsWithNoExplicitMembers === 1 ? 'has' : 'have'} nobody
          added explicitly, so everyone works in {projectsWithNoExplicitMembers === 1 ? 'it' : 'them'}{' '}
          through the default. Restricting would show you exactly what that costs before it applies.
        </p>
      )}
      {mode === 'STRICT' && (
        <p className="text-xs mt-3" style={{ color: 'var(--color-text-muted)' }}>
          Reads are untouched: every member still sees every project, its board and its issues. Only
          changing things needs a membership.
        </p>
      )}

      {refusal && <div className="mt-3"><ConflictNotice refusal={refusal} /></div>}

      {confirming && (
        <ProjectAccessConfirmDialog
          wsId={wsId}
          payload={{ projectAccessMode: 'STRICT' }}
          onClose={() => setConfirming(false)}
          onApplied={onChanged}
        />
      )}
    </Card>
  )
}

// ── 3. Default project role ─────────────────────────────────────────────────

function DefaultRoleCard({ wsId, roles, mode, currentRoleId, settable, loading, onChanged }: {
  wsId: string
  roles: ReturnType<typeof sortRoles>
  mode: ProjectAccessMode | undefined
  currentRoleId: string | null
  settable: SettableRolesView | undefined
  loading: boolean
  onChanged: () => Promise<void>
}) {
  const [draft, setDraft] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [refusal, setRefusal] = useState<ConflictKind | null>(null)

  const selected = draft ?? currentRoleId ?? ''
  const dirty = draft !== null && draft !== (currentRoleId ?? '')

  async function save() {
    setBusy(true)
    setRefusal(null)
    try {
      // Two fields that mean different things, so exactly one is ever sent —
      // sending both is a 422 rather than a precedence rule.
      await apiUpdateWorkspace(wsId, selected
        ? { defaultProjectRoleId: selected }
        : { clearDefaultProjectRole: true })
      await onChanged()
      setDraft(null)
    } catch (err) {
      setRefusal(classifyConflict(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title="Default project role"
          blurb="What a workspace member gets in a project they were never added to. A project can override it for itself; this is the value the rest of them fall back to.">
      <div style={{ maxWidth: 360 }}>
        {/* An explicit placeholder for the null value is mandatory here: the
            shared Select falls back to its FIRST option when nothing matches, so
            without it "inherited" would be silently relabelled as whichever role
            sorts first — a confidently wrong label on a privilege. */}
        <DefaultRoleSelect
          label="Role" roles={roles} settable={settable} value={selected}
          placeholder="Contributor (built-in default)"
          disabled={loading || busy}
          onChange={value => setDraft(value)} />
      </div>

      <CeilingSummary settable={settable} />

      {mode === 'STRICT' && (
        <p className="text-xs mt-3" style={{ color: 'var(--color-text-muted)' }}>
          Project access is Restricted right now, so nobody is inheriting this today. It is still
          stored, and it applies again the moment access is switched back to Open — which is why it
          is bounded by the same rule either way.
        </p>
      )}

      {refusal && <div className="mt-3"><ConflictNotice refusal={refusal} /></div>}

      <div className="flex items-center gap-3 mt-3">
        <Button variant="primary" loading={busy} disabled={!dirty} onClick={save}>Save</Button>
        {dirty && (
          <Button variant="ghost" onClick={() => { setDraft(null); setRefusal(null) }}>Cancel</Button>
        )}
      </div>
      <p className="text-xs mt-2" style={{ color: 'var(--color-text-muted)' }}>
        This hands its role to everybody at once, so you can only choose something you already hold
        yourself. Anything wider needs an owner.
      </p>
    </Card>
  )
}

// ── Shell ───────────────────────────────────────────────────────────────────

function Card({ title, blurb, children }: {
  title: string
  blurb: string
  children: React.ReactNode
}) {
  return (
    // Named landmarks, not anonymous divs: three cards on one page each carry a
    // "Save", and a control that cannot be told apart by name is one a keyboard
    // or screen-reader user cannot aim.
    <section aria-label={title} className="rounded-lg border p-4"
             style={{ background: 'white', borderColor: 'var(--color-border)',
                      boxShadow: 'var(--shadow-card)' }}>
      <h2 className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>{title}</h2>
      {/* Inline maxWidth: our @theme --spacing-* scale shadows Tailwind's
          max-w-{xs..3xl} sizes (max-w-xl would be 32px) — see CLAUDE.md */}
      <p className="text-sm mt-1 mb-3" style={{ color: 'var(--color-text-secondary)', maxWidth: 620 }}>
        {blurb}
      </p>
      {children}
    </section>
  )
}
