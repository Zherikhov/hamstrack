import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { MoreHorizontal, Plus, Rocket } from 'lucide-react'
import {
  ApiResponseError, versionsApi,
} from '../api'
import { hqlQuote, sanitizeForHql } from '../hql'
import { ISSUES_KEY_ROOT } from '../lib/queryKeys'
import { Button, Checkbox, Input, Select, Textarea } from '../components/ui'
import { CapabilityOffState, ReversibleNotice, useEnableCapability } from '../components/delivery'
import { useProjectDelivery } from '../hooks/useProjectDelivery'
import { Modal } from './admin/common'
import {
  ReleasedPill, VersionProgress, moveTargets, remapTargets, versionsKey,
} from '../components/versions'
import type { Version, VersionUsage } from '../types'

/**
 * Releases (HD-32) — `/w/:wsId/p/:projectId/releases`, wrapped in `ParamKeyed`
 * like every other project page so its dialogs/filters can't leak across
 * projects.
 *
 * Versions are managed HERE, not in project settings: they are working data with
 * a lifecycle (unreleased ⇄ released, plus an orthogonal archived), not
 * configuration. Project settings → Components links across to this page.
 *
 * One card per version: mono name (DESIGN.md — a version is inspectable data,
 * like an issue key), date, released/unreleased pill, a `doneIssueCount /
 * issueCount` progress bar, and a kebab (Edit · Release…/Un-release · Archive ·
 * Delete). The progress counts ride along on the list response, so the page
 * costs exactly one request.
 *
 * The issue count is a link into the HQL search results page, pre-filled with
 * `project = "KEY" AND fixVersion = "2.4.0"` — deliberately reusing that view
 * instead of growing a second list surface here.
 *
 * Reading needs project membership only; every write needs project MANAGER *or*
 * workspace OWNER/ADMIN (the server's `requireProjectCurator`). Non-curators get
 * the read-only page — the server independently enforces the role, so this is a
 * UX guard, not a security boundary.
 */
export default function ReleasesPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const [showArchived, setShowArchived] = useState(false)
  const [editing, setEditing] = useState<Version | 'new' | null>(null)
  const [releasing, setReleasing] = useState<Version | null>(null)
  const [deleting, setDeleting] = useState<Version | null>(null)
  const [error, setError] = useState('')

  // HD-102: the route still resolves with releases OFF (Rule C, §5.3) — the rail
  // item is gone, but a link, a bookmark or "turn this back on" must all land
  // somewhere. `isCurator` mirrors ScopeResolver.requireProjectCurator: a
  // workspace OWNER/ADMIN curates a project's releases without being a member.
  const { releases, isCurator, project } =
    useProjectDelivery(wsId, projectId, { needsRole: true })
  const enabling = useEnableCapability(wsId, projectId)

  // Its OWN key (archived rows included) so the pickers' lean
  // `versionsKey(wsId, projectId)` entry stays lean; both live under the same
  // prefix, so one invalidation refreshes both.
  const manageKey = [...versionsKey(wsId, projectId), 'manage'] as const
  const { data: versions = [], isLoading, isError } = useQuery({
    queryKey: manageKey,
    queryFn: () => versionsApi.list(wsId!, projectId!, { includeArchived: true }),
    enabled: !!wsId && !!projectId,
  })

  /** Versions changed → the pickers' list AND every issue payload that embeds one. */
  function invalidate() {
    qc.invalidateQueries({ queryKey: versionsKey(wsId, projectId) })
    qc.invalidateQueries({ queryKey: [ISSUES_KEY_ROOT] })
    qc.invalidateQueries({ queryKey: ['issue'] })
  }

  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      archived ? versionsApi.unarchive(wsId!, projectId!, id) : versionsApi.archive(wsId!, projectId!, id),
    onSuccess: () => { setDeleting(null); setError(''); invalidate() },
    onError: e => setError(errorText(e, 'Failed')),
  })

  // Un-release keeps `releaseDate` (it stays the plan) and only clears the
  // released state — reversible with no data loss.
  const unrelease = useMutation({
    mutationFn: (id: string) => versionsApi.unrelease(wsId!, projectId!, id),
    onSuccess: () => { setError(''); invalidate() },
    onError: e => setError(errorText(e, 'Failed to un-release')),
  })

  const visible = showArchived ? versions : versions.filter(v => !v.archived)
  const archivedCount = versions.filter(v => v.archived).length

  /** Jump to the HQL search results, pre-filled for this version's fix links. */
  function openIssues(v: Version) {
    if (!project) return
    const q = `project = ${hqlQuote(sanitizeForHql(project.key))} `
      + `AND fixVersion = ${hqlQuote(sanitizeForHql(v.name))}`
    navigate(`/w/${wsId}/search?q=${encodeURIComponent(q)}`)
  }

  if (isError) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-3">
        <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
          Project not found — it may have been deleted, or your access was removed.
        </p>
        <Button variant="secondary" size="sm" onClick={() => navigate(`/w/${wsId}`, { state: { showAll: true } })}>
          Go to projects
        </Button>
      </div>
    )
  }

  return (
    <div style={{ flex: 1, overflowY: 'auto', background: 'var(--color-surface)' }}>
      {/* 1180 matches HomePage — the other nav-rail work surface built out of
          cards. The old 900 left roughly a third of the content area empty on a
          normal laptop, because this column does NOT centre itself (no
          `margin: 0 auto`), so the slack all piled up on the right.
          Inline maxWidth rather than a Tailwind class: our @theme --spacing-*
          scale shadows max-w-{xs..3xl} (max-w-xl would resolve to 32px) — see
          CLAUDE.md. */}
      <div style={{ maxWidth: 1180, padding: '20px 26px 40px' }}>
        <div className="flex items-start justify-between gap-4 mb-4">
          <div>
            <h1 style={{ fontSize: 22, fontWeight: 800, letterSpacing: '-0.01em' }}>Releases</h1>
            <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 560 }}>
              The versions this project ships. Link issues to one as a <b>fix version</b> (“this
              change ships here”) or an <b>affects version</b> (“this defect exists there”).
              Releasing is reversible, and archiving keeps a version on the issues that carry it.
            </p>
          </div>
          {/* Rule C: "New version" is the capability's own entry point, so it is
              NOT gated by the capability — with releases off it turns them on in
              the same gesture that creates the first version. */}
          {isCurator && (
            <Button variant="primary" onClick={() => { setError(''); setEditing('new') }}>
              <Plus size={14} /> New version
            </Button>
          )}
        </div>

        {/* The off-state, where the page's own controls are (§5.3). Versions and
            their issue links are untouched below — Rule B keeps every value
            visible; only the write controls went. */}
        {/* `project` guards the loading window: an unloaded project answers "off"
            for every capability by design (§ `UNKNOWN_DELIVERY`), and this panel
            must not flash onto a project whose releases are on. */}
        {!!project && !releases && (
          <div className="mb-4">
            <CapabilityOffState
              capability="releases"
              isCurator={isCurator}
              pending={enabling.isPending}
              error={enabling.error}
              onEnable={() => { setError(''); enabling.enable('releases').catch(() => {}) }}
            />
          </div>
        )}

        {archivedCount > 0 && (
          <div className="flex justify-end mb-2">
            <Checkbox
              checked={showArchived}
              onChange={e => setShowArchived(e.target.checked)}
              label={<span className="text-xs">Show archived ({archivedCount})</span>}
            />
          </div>
        )}

        {error && !editing && !deleting && !releasing && (
          <p className="text-xs mb-2" style={{ color: 'var(--color-error)' }}>{error}</p>
        )}

        {isLoading ? (
          <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</p>
        ) : visible.length === 0 ? (
          <div
            className="flex flex-col items-center justify-center gap-3 rounded-lg border"
            style={{ padding: '48px 24px', background: 'white', borderColor: 'var(--color-border)' }}
          >
            <Rocket size={22} style={{ color: 'var(--color-text-muted)' }} />
            <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
              No versions yet — add one to start tracking what ships when.
            </p>
            {isCurator && (
              <Button variant="secondary" size="sm" onClick={() => { setError(''); setEditing('new') }}>
                <Plus size={13} /> New version
              </Button>
            )}
          </div>
        ) : (
          <div className="flex flex-col gap-2.5">
            {visible.map(v => (
              <VersionCard
                key={v.id}
                version={v}
                // Rule B (§5.2): the card, its progress and its issue links stay
                // exactly as they are; only the lifecycle CONTROLS follow the
                // capability. Nothing here was deleted by turning releases off.
                canCurate={isCurator && releases}
                onOpenIssues={() => openIssues(v)}
                onEdit={() => { setError(''); setEditing(v) }}
                onRelease={() => { setError(''); setReleasing(v) }}
                onUnrelease={() => unrelease.mutate(v.id)}
                onArchive={() => archive.mutate({ id: v.id, archived: v.archived })}
                onDelete={() => { setError(''); setDeleting(v) }}
              />
            ))}
          </div>
        )}
      </div>

      {editing && wsId && projectId && (
        <VersionForm
          wsId={wsId}
          projectId={projectId}
          version={editing === 'new' ? null : editing}
          // Rule C: creating the first version on a releases-off project turns
          // releases on in the same submit, with the reversible notice in the
          // dialog's copy. Editing an existing one never flips anything.
          enableReleases={!releases}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); invalidate() }}
        />
      )}

      {releasing && wsId && projectId && (
        <ReleaseDialog
          wsId={wsId}
          projectId={projectId}
          version={releasing}
          versions={versions}
          onClose={() => setReleasing(null)}
          onReleased={() => { setReleasing(null); invalidate() }}
        />
      )}

      {deleting && wsId && projectId && (
        <DeleteVersionDialog
          wsId={wsId}
          projectId={projectId}
          version={deleting}
          versions={versions}
          onClose={() => setDeleting(null)}
          onArchiveInstead={() => archive.mutate({ id: deleting.id, archived: false })}
          onDeleted={() => { setDeleting(null); invalidate() }}
        />
      )}
    </div>
  )
}

/** Server messages reach the SPA as ProblemDetail `detail` — prefer it over a generic string. */
function errorText(e: unknown, fallback: string) {
  if (e instanceof ApiResponseError) return e.detail
  return e instanceof Error ? e.message : fallback
}

/** `YYYY-MM-DD` → the viewer's locale, without dragging in a timezone shift. */
function formatDate(iso: string | undefined) {
  if (!iso) return null
  const d = new Date(`${iso}T00:00:00`)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

/** Today as `YYYY-MM-DD` in the viewer's own timezone (the date input's format). */
function todayInputValue() {
  const now = new Date()
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000)
  return local.toISOString().slice(0, 10)
}

// ── Card ──────────────────────────────────────────────────────────────────────

function VersionCard({
  version, canCurate, onOpenIssues, onEdit, onRelease, onUnrelease, onArchive, onDelete,
}: {
  version: Version
  canCurate: boolean
  onOpenIssues: () => void
  onEdit: () => void
  onRelease: () => void
  onUnrelease: () => void
  onArchive: () => void
  onDelete: () => void
}) {
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!menuOpen) return
    function onDoc(e: MouseEvent) {
      if (!menuRef.current?.contains(e.target as Node)) setMenuOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [menuOpen])

  const date = formatDate(version.releaseDate)

  return (
    <div
      className="rounded-lg border"
      style={{
        background: 'white',
        borderColor: 'var(--color-border)',
        boxShadow: 'var(--shadow-card)',
        padding: '14px 16px',
        opacity: version.archived ? 0.7 : 1,
      }}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            {/* DESIGN.md: version names are inspectable data → IBM Plex Mono */}
            <span className="mono truncate" style={{ fontSize: 14, fontWeight: 600, color: 'var(--color-text)' }}>
              {version.name}
            </span>
            <ReleasedPill released={version.released} />
            {version.archived && (
              <span className="text-xs px-2 py-0.5 rounded-full"
                    style={{ color: 'var(--color-warning)', background: '#FBF3E8' }}>
                archived
              </span>
            )}
          </div>
          <div className="flex items-center gap-2 mt-1 flex-wrap">
            <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
              {date
                ? (version.released ? `Released ${date}` : `Planned for ${date}`)
                : 'No release date'}
            </span>
            {version.affectsIssueCount > 0 && (
              <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                · affects <span className="mono">{version.affectsIssueCount}</span> issue
                {version.affectsIssueCount === 1 ? '' : 's'}
              </span>
            )}
          </div>
          {version.description && (
            <p className="text-sm mt-1.5" style={{ color: 'var(--color-text-secondary)', maxWidth: 520 }}>
              {version.description}
            </p>
          )}
        </div>

        <div className="flex items-center gap-3 flex-shrink-0">
          {/* Progress — the counts ride along on the list response, so this costs
              no extra request. Zero issues renders "No issues yet", not a 0% bar. */}
          {version.issueCount > 0 ? (
            <button
              type="button"
              onClick={onOpenIssues}
              title={`Show the ${version.issueCount} issue${version.issueCount === 1 ? '' : 's'} with this fix version`}
              className="cursor-pointer text-left rounded-md px-2 py-1 -mx-2 transition-colors hover:bg-[var(--color-surface-2)]"
            >
              <VersionProgress done={version.doneIssueCount} total={version.issueCount} />
            </button>
          ) : (
            <VersionProgress done={0} total={0} />
          )}

          {canCurate && (
            <div className="relative" ref={menuRef}>
              <button
                type="button"
                onClick={() => setMenuOpen(o => !o)}
                aria-label={`Actions for ${version.name}`}
                aria-haspopup="menu"
                aria-expanded={menuOpen}
                className="cursor-pointer p-1 rounded hover:bg-[var(--color-surface-2)] transition-colors"
              >
                <MoreHorizontal size={16} style={{ color: 'var(--color-text-muted)' }} />
              </button>
              {menuOpen && (
                <div
                  role="menu"
                  className="absolute right-0 mt-1 border rounded-md py-1 z-30"
                  style={{
                    minWidth: 176, background: 'var(--color-card)',
                    borderColor: 'var(--color-border-2)', boxShadow: 'var(--shadow-lg)',
                  }}
                >
                  <MenuItem label="Edit" onClick={() => { setMenuOpen(false); onEdit() }} />
                  {version.released ? (
                    <MenuItem label="Un-release" onClick={() => { setMenuOpen(false); onUnrelease() }} />
                  ) : (
                    <MenuItem label="Release…" onClick={() => { setMenuOpen(false); onRelease() }} />
                  )}
                  <MenuItem
                    label={version.archived ? 'Unarchive' : 'Archive'}
                    onClick={() => { setMenuOpen(false); onArchive() }}
                  />
                  <MenuItem
                    label="Delete"
                    danger
                    onClick={() => { setMenuOpen(false); onDelete() }}
                  />
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function MenuItem({ label, danger, onClick }: { label: string; danger?: boolean; onClick: () => void }) {
  return (
    <button
      role="menuitem"
      onClick={onClick}
      className="w-full px-3 py-1.5 text-sm text-left cursor-pointer hover:bg-[var(--color-surface-2)] transition-colors"
      style={{ color: danger ? 'var(--color-error)' : 'var(--color-text)' }}
    >
      {label}
    </button>
  )
}

// ── Create / edit ─────────────────────────────────────────────────────────────

function VersionForm({ wsId, projectId, version, enableReleases, onClose, onSaved }: {
  wsId: string
  projectId: string
  version: Version | null
  /**
   * The project has releases OFF and this submit is the gesture that turns them
   * on (Rule C, §5.3). Only meaningful on create — an existing version is proof
   * the capability was on once, and Rule B keeps it visible either way.
   */
  enableReleases?: boolean
  onClose: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState(version?.name ?? '')
  const [description, setDescription] = useState(version?.description ?? '')
  const [releaseDate, setReleaseDate] = useState(version?.releaseDate ?? '')
  const [error, setError] = useState('')
  const enabling = useEnableCapability(wsId, projectId)
  const bootstrapping = !version && !!enableReleases

  const save = useMutation({
    mutationFn: async () => {
      const base = { name: name.trim(), description: description.trim() }
      if (version) {
        // Partial PATCH: `clearReleaseDate` is the only way to unset a nullable
        // scalar (a plain null can't be told apart from "not sent" server-side).
        return versionsApi.update(wsId, projectId, version.id,
          releaseDate ? { ...base, releaseDate } : { ...base, clearReleaseDate: true })
      }
      // Capability first, so a failure can never leave a version stranded on a
      // project that still says it doesn't do releases.
      if (bootstrapping) await enabling.enable('releases')
      return versionsApi.create(wsId, projectId, releaseDate ? { ...base, releaseDate } : base)
    },
    onSuccess: onSaved,
    // 409 duplicate name (case-insensitive, ARCHIVED names included) and any
    // write while the project is archived — the server message says which.
    onError: e => setError(errorText(e, 'Save failed')),
  })

  return (
    <Modal title={version ? `Edit version “${version.name}”` : 'New version'} onClose={onClose}>
      <div className="flex flex-col gap-3">
        {/* Rule C: what this submit will change, and that it can be changed back,
            as copy — not as a tooltip on the button. */}
        {bootstrapping && <ReversibleNotice capability="releases" />}

        <Input label="Name" value={name} onChange={e => setName(e.target.value)}
               maxLength={60} placeholder="2.4.0" autoFocus />

        <Textarea label="Description" rows={2} value={description} maxLength={500}
                  onChange={e => setDescription(e.target.value)} />

        <div className="flex flex-col gap-1">
          <Input label="Release date" type="date" value={releaseDate}
                 onChange={e => setReleaseDate(e.target.value)} />
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
            Optional — the plan. Releasing without one dates the version today.
          </span>
        </div>

        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!name.trim()} loading={save.isPending} onClick={() => save.mutate()}>
            {version ? 'Save' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

// ── Release ───────────────────────────────────────────────────────────────────

/**
 * "Release…" — the date, plus the "move N unresolved issues to →" select, which
 * appears ONLY when the version actually has unresolved (non-DONE) fix-linked
 * issues. Releasing with open work is allowed by design (§6.1): blocking it
 * would push teams to falsify the tracker.
 */
function ReleaseDialog({ wsId, projectId, version, versions, onClose, onReleased }: {
  wsId: string
  projectId: string
  version: Version
  versions: Version[]
  onClose: () => void
  onReleased: () => void
}) {
  const [releaseDate, setReleaseDate] = useState(version.releaseDate ?? todayInputValue())
  const [moveToId, setMoveToId] = useState('')
  const [error, setError] = useState('')

  // The unresolved count is the one thing the list response doesn't carry.
  const { data: usage, isLoading: usageLoading } = useQuery<VersionUsage>({
    queryKey: [...versionsKey(wsId, projectId), version.id, 'usage'],
    queryFn: () => versionsApi.usage(wsId, projectId, version.id),
  })
  const unresolved = usage?.unresolvedFixIssueCount ?? 0
  // Only another UNRELEASED, non-archived version of this project is a legal
  // target — anything else is a 422.
  const targets = moveTargets(versions, version.id)

  const release = useMutation({
    mutationFn: () => versionsApi.release(wsId, projectId, version.id, {
      releaseDate: releaseDate || undefined,
      moveUnresolvedToVersionId: moveToId || undefined,
    }),
    onSuccess: onReleased,
    // 409 already released (a double-click must not silently re-run the move) /
    // project archived · 422 bad move target.
    onError: e => setError(errorText(e, 'Failed to release')),
  })

  return (
    <Modal title={`Release “${version.name}”?`} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <Input label="Release date" type="date" value={releaseDate}
               onChange={e => setReleaseDate(e.target.value)} />

        {usageLoading ? (
          <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>checking open issues…</span>
        ) : unresolved > 0 ? (
          <div className="flex flex-col gap-2">
            <div className="text-sm rounded-lg px-3 py-2.5"
                 style={{ background: '#FBF3E8', border: '1px solid #EFD9BC', color: '#7C4A0B' }}>
              <span className="mono">{unresolved}</span> issue{unresolved === 1 ? '' : 's'} still
              open with this fix version. Releasing anyway is fine — they keep it unless you move them.
            </div>
            {targets.length > 0 ? (
              <Select
                label={`Move ${unresolved} unresolved issue${unresolved === 1 ? '' : 's'} to`}
                value={moveToId}
                onChange={e => setMoveToId(e.target.value)}
              >
                <option value="">Leave them on {version.name}</option>
                {targets.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
              </Select>
            ) : (
              <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                No other unreleased version to move them to — create one first if you want to.
              </span>
            )}
          </div>
        ) : (
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
            Everything with this fix version is done. 🎉
          </span>
        )}

        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={release.isPending} onClick={() => release.mutate()}>
            Release
          </Button>
        </div>
      </div>
    </Modal>
  )
}

// ── Delete ────────────────────────────────────────────────────────────────────

/**
 * Delete a version. In use it is a 409 unless the caller opts in explicitly:
 * `remapToId` re-points BOTH roles' links at another version ("the work moved to
 * the next release" — the normal reason to delete one), `force` just drops them.
 * Archiving is offered as the non-destructive path.
 */
function DeleteVersionDialog({ wsId, projectId, version, versions, onClose, onArchiveInstead, onDeleted }: {
  wsId: string
  projectId: string
  version: Version
  versions: Version[]
  onClose: () => void
  onArchiveInstead: () => void
  onDeleted: () => void
}) {
  const [remapToId, setRemapToId] = useState('')
  const [error, setError] = useState('')

  const { data: usage } = useQuery<VersionUsage>({
    queryKey: [...versionsKey(wsId, projectId), version.id, 'usage'],
    queryFn: () => versionsApi.usage(wsId, projectId, version.id),
  })
  const linked = (usage?.fixIssueCount ?? version.issueCount)
    + (usage?.affectsIssueCount ?? version.affectsIssueCount)
  const targets = remapTargets(versions, version.id)

  const remove = useMutation({
    mutationFn: () => versionsApi.remove(wsId, projectId, version.id,
      remapToId ? { remapToId } : { force: linked > 0 }),
    onSuccess: onDeleted,
    onError: e => setError(errorText(e, 'Delete failed')),
  })

  return (
    <Modal title={`Delete version “${version.name}”?`} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <div className="text-sm rounded-lg px-3 py-2.5"
             style={{ background: '#FBF3E8', border: '1px solid #EFD9BC', color: '#7C4A0B' }}>
          {linked > 0 ? (
            <>
              Linked to <span className="mono">{usage?.fixIssueCount ?? version.issueCount}</span> issue
              {(usage?.fixIssueCount ?? version.issueCount) === 1 ? '' : 's'} as a fix version and{' '}
              <span className="mono">{usage?.affectsIssueCount ?? version.affectsIssueCount}</span> as an
              affects version. Deleting drops those links — move them to another version instead, or
              archive to keep them.
            </>
          ) : (
            <>Not linked to any issue — safe to delete.</>
          )}
        </div>

        {linked > 0 && targets.length > 0 && (
          <Select label="Move the links to" value={remapToId} onChange={e => setRemapToId(e.target.value)}>
            <option value="">Don’t move — drop the links</option>
            {targets.map(t => (
              <option key={t.id} value={t.id}>{t.released ? `${t.name} (released)` : t.name}</option>
            ))}
          </Select>
        )}

        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          {!version.archived && (
            <Button variant="secondary" onClick={onArchiveInstead}>Archive instead</Button>
          )}
          <Button variant="danger" loading={remove.isPending} onClick={() => remove.mutate()}>
            {remapToId ? 'Delete & move' : 'Delete'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
