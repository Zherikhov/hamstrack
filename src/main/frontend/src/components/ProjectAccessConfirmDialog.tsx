import { useEffect, useState } from 'react'
import { ShieldAlert } from 'lucide-react'
import { apiPreviewProjectAccess, apiUpdateWorkspace } from '../api'
import type { UpdateWorkspacePayload } from '../api'
import { Modal } from '../pages/admin/common'
import { Button } from './ui'
import { ConflictNotice, Notice, ProjectRefList, classifyConflict } from './roles'
import type { ConflictKind } from './roles'
import type { ProjectAccessImpact } from '../types'

/**
 * **"What would this actually do?"** — the confirm step in front of restricting a
 * live workspace's project access (HD-130 S7 §9.1).
 *
 * Flipping the switch on a live workspace and silently removing access from
 * people who had it is the failure this whole epic exists to prevent, so this
 * dialog is the slice's most important surface. Two properties it exists to
 * honour, and both are structural rather than a matter of care:
 *
 * ## 1. The preview is a snapshot and is re-fetched on open
 *
 * The counts describe a population — `workspace_members` × `project_members` —
 * that is **not the row being written**, so no optimistic check could make them
 * exact and the server deliberately ships no token, echo or `expectedCount`
 * (inventing one would be ceremony that *looks* like a guarantee). What it ships
 * instead is `computedAt`, and the client's half of the bargain is to fetch on
 * open and **never cache a preview across a dialog close**.
 *
 * That is why the fetch here is a plain effect into local state rather than a
 * `useQuery`: a cache entry is exactly the thing that must not exist. A dialog
 * closed and reopened a minute later asks again, always.
 *
 * ## 2. The refusal can still arrive after a clean preview
 *
 * `strandedProjects` — the one number that must be exact — is re-derived **inside
 * the write's transaction** and enforced there, whether or not the caller ever
 * previewed. So a preview showing none is not a promise: the write can still
 * answer **409 `STRANDED_BY_INHERITANCE`**, and it is rendered as itself (named
 * projects, the real remedy, no adoption button) rather than as a generic error.
 *
 * Counts are advisory; refusals are authoritative.
 *
 * ## What the headline says, and why it is not the obvious sentence
 *
 * Not *"only workspace admins will be able to change anything there"* — that is
 * false. A workspace Owner/Admin holds `project.curate.all`, which is
 * `project.edit` + `component.manage` + `version.manage` + `sprint.manage` and
 * **no issue or comment permission at all**. In a restricted project with no
 * explicit members nobody can file or edit an issue, *including the Owner*. The
 * honest headline is `projectsWithNoWriters`, and it is drawn as a warning rather
 * than a refusal because a workspace may legitimately restrict a project nobody
 * is currently working in.
 */
export default function ProjectAccessConfirmDialog({ wsId, payload, onClose, onApplied }: {
  wsId: string
  /** The body being confirmed — previewed with the identical payload the write sends. */
  payload: UpdateWorkspacePayload
  onClose: () => void
  /** Called after the write lands, for cache invalidation. */
  onApplied: () => void | Promise<void>
}) {
  const [impact, setImpact] = useState<ProjectAccessImpact | null>(null)
  const [previewError, setPreviewError] = useState<ConflictKind | null>(null)
  const [refusal, setRefusal] = useState<ConflictKind | null>(null)
  const [busy, setBusy] = useState(false)

  // Re-fetched on open, into local state, deliberately outside the query cache —
  // see the class note. `cancelled` guards the unmount race, so a dialog closed
  // mid-flight never writes into a dead tree.
  useEffect(() => {
    let cancelled = false
    setImpact(null)
    setPreviewError(null)
    apiPreviewProjectAccess(wsId, payload)
      .then(result => { if (!cancelled) setImpact(result) })
      .catch(err => { if (!cancelled) setPreviewError(classifyConflict(err)) })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wsId, JSON.stringify(payload)])

  async function apply() {
    setBusy(true)
    setRefusal(null)
    try {
      await apiUpdateWorkspace(wsId, payload)
      await onApplied()
      onClose()
    } catch (err) {
      setRefusal(classifyConflict(err))
      setBusy(false)
    }
  }

  const stranded = impact?.strandedProjects ?? []
  // A stranding list is a block, not a warning: there is no adoption retry for
  // this door, so offering the button would only produce the 409 the preview
  // already showed.
  const blocked = stranded.length > 0
  const noWriters = impact?.projectsWithNoWriters ?? 0

  return (
    <Modal title="Restrict project access?" onClose={onClose} width={640}>
      <div className="flex flex-col gap-4">
        <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          After this, only people who have been added to a project can change anything in it.
          Everyone in the workspace can still <b>see</b> every project — nothing is hidden, no data
          is changed, and switching back restores exactly what people had.
        </p>

        {!impact && !previewError && (
          <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>
            working out what this would do…
          </p>
        )}

        {previewError && <ConflictNotice refusal={previewError} />}

        {impact && (
          <>
            {noWriters > 0 && (
              <Notice tone="warning">
                <span className="flex gap-2 text-sm">
                  <ShieldAlert size={16} style={{ flexShrink: 0, marginTop: 1, color: 'var(--color-warning)' }} />
                  <span>
                    In <b>{noWriters}</b> of your <b>{impact.projects}</b>{' '}
                    project{impact.projects !== 1 ? 's' : ''} nobody has been added explicitly. After
                    this, <b>nobody — including you —</b> will be able to file or edit an issue there.
                  </span>
                </span>
                <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  Being a workspace administrator does not help: that role can rename a project and
                  cut a release, and grants no issue or comment permission at all. Add people to
                  those projects, or leave access open.
                </span>
              </Notice>
            )}

            <ImpactTable impact={impact} />

            {blocked && (
              <Notice tone="danger">
                <span className="text-sm">
                  <b>This cannot be applied yet.</b>{' '}
                  {stranded.length === 1 ? 'One project is' : `${stranded.length} projects are`}{' '}
                  administered only through the default access, so restricting it would leave
                  nobody able to manage who works {stranded.length === 1 ? 'there' : 'in them'}:
                </span>
                <ProjectRefList projects={stranded} />
                <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  Add an explicit administrator to each first — the current default still lets you.
                </span>
              </Notice>
            )}

            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
              These counts were taken at{' '}
              <span className="mono">{formatComputedAt(impact.computedAt)}</span> and can move while
              this dialog is open — they are a guide, not a promise. The one thing that is checked
              again as the change is written is whether a project would be left with no
              administrator, and that check can still refuse.
            </p>
          </>
        )}

        {refusal && <ConflictNotice refusal={refusal} />}

        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" loading={busy} disabled={!impact || blocked} onClick={apply}>
            Restrict project access
          </Button>
        </div>
      </div>
    </Modal>
  )
}

/**
 * One row per live project. `membersOnDefault` is the population the mode is
 * about; `membersLosingEverything` is the subset of those left holding nothing at
 * all — the two differ exactly by the people whose *workspace* role carries a
 * cross-project grant, which is a distinction a single "affected" number would
 * erase.
 */
function ImpactTable({ impact }: { impact: ProjectAccessImpact }) {
  if (impact.perProject.length === 0) {
    return (
      <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
        This workspace has no live projects, so nothing changes for anybody today.
      </p>
    )
  }
  return (
    <div className="rounded-lg border overflow-hidden"
         style={{ borderColor: 'var(--color-border)', background: 'white' }}>
      <table className="w-full" style={{ borderCollapse: 'collapse' }}>
        <thead>
          <tr style={{ background: 'var(--color-surface)' }}>
            <Th align="left">Project</Th>
            <Th>Added explicitly</Th>
            <Th>On the default</Th>
            <Th>Left with nothing</Th>
          </tr>
        </thead>
        <tbody>
          {impact.perProject.map(row => (
            <tr key={row.id} style={{ borderTop: '1px solid var(--color-border)' }}>
              <td className="px-3 py-2 text-sm">
                <span className="flex items-center gap-2">
                  <span className="mono text-xs px-1.5 py-0.5 rounded"
                        style={{ background: 'var(--color-surface-2)', color: 'var(--color-text-secondary)' }}>
                    {row.key}
                  </span>
                  <bdi className="truncate">{row.name}</bdi>
                  {row.noWritersAfter && (
                    <span className="text-xs whitespace-nowrap" style={{ color: 'var(--color-warning)' }}>
                      nobody could file an issue
                    </span>
                  )}
                </span>
              </td>
              <Td>{row.explicitMembers}</Td>
              <Td>{row.membersOnDefault}</Td>
              <Td strong={row.membersLosingEverything > 0}>{row.membersLosingEverything}</Td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="px-3 py-2 text-xs border-t"
           style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-muted)' }}>
        {impact.activeMembers} active member{impact.activeMembers !== 1 ? 's' : ''} in this
        workspace. Deactivated accounts are not counted — they cannot sign in, so they are not
        people who lose access.
      </div>
    </div>
  )
}

function Th({ children, align = 'right' }: { children: React.ReactNode; align?: 'left' | 'right' }) {
  return (
    <th className="px-3 py-2 text-xs font-semibold uppercase tracking-wider"
        style={{ color: 'var(--color-text-muted)', textAlign: align }}>
      {children}
    </th>
  )
}

function Td({ children, strong }: { children: React.ReactNode; strong?: boolean }) {
  return (
    <td className="px-3 py-2 mono text-sm" style={{
      textAlign: 'right',
      color: strong ? 'var(--color-text)' : 'var(--color-text-secondary)',
      fontWeight: strong ? 600 : 400,
    }}>
      {children}
    </td>
  )
}

/** Local time, seconds included — the point is "how old is this", not the date. */
function formatComputedAt(iso: string): string {
  const at = new Date(iso)
  return Number.isNaN(at.getTime()) ? iso : at.toLocaleTimeString()
}
