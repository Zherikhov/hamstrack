import { Link, useParams } from 'react-router'
import { usePermissions } from '../../hooks/usePermissions'
import { useWorkspaceStorage, useWorkspaceStorageByProject } from '../../hooks/useWorkspaceStorage'
import { Notice, SettingsPageHeader } from '../../components/roles'
import { StorageFillBar } from '../../components/storage'
import { formatBytes, formatPercent } from '../../lib/bytes'
import type { WorkspaceStorageByProject } from '../../types'

/**
 * Workspace settings → **Storage** (HD-191 §12.2).
 *
 * The ticket's own sentence is that *a quota nobody can see is a trap rather
 * than a limit*, and this page is the half of the answer that arrives **before**
 * anybody is refused. The other half is the line beside the upload control on an
 * issue, which appears only near the threshold; this one is always here.
 *
 * ## Two reads, two gates, on purpose
 *
 * The **summary** needs nothing but membership: it is the same aggregate the
 * refusal already hands whoever hit it, so withholding it would only stop a
 * member telling "I am blocked" from "the server is broken". The **breakdown**
 * names projects and their volumes, which is real disclosure across a project
 * boundary, so it needs `workspace.edit` — and it is the only thing on this page
 * that a member without that grant does not see. That is why the page renders at
 * all for them rather than bouncing: the figure they may read, they read.
 *
 * ## Rule C
 *
 * With `quotaEnabled` false there is no ceiling and the page still renders
 * everything else, saying so in a sentence. Usage is counted, reported and
 * reconciled either way (§6.9), and this is the number an operator reads *while
 * deciding what to set* — a page that appeared only once a limit existed would
 * be unreachable for exactly that person.
 */
export default function WorkspaceStoragePage() {
  const { wsId } = useParams<{ wsId: string }>()
  const permissions = usePermissions(wsId)
  const canEdit = permissions.can('workspace.edit')

  const { data: summary, isLoading, isError, error } = useWorkspaceStorage(wsId)
  const breakdown = useWorkspaceStorageByProject(wsId, canEdit)

  return (
    <div>
      <SettingsPageHeader
        title="Storage"
        subtitle="How much of this workspace’s attachment storage is in use, and which projects it is in. Reads and downloads are never affected by any of this — a full workspace stays fully readable."
      />

      <div className="flex flex-col gap-5">
        <Card title="Usage"
              blurb="Counted from the attachment rows this workspace holds, so it follows a deleted issue or project down immediately.">
          {isLoading && (
            <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>Loading…</p>
          )}
          {isError && (
            <Notice tone="info">
              <span className="text-sm">
                This figure could not be read just now
                {error instanceof Error && error.message ? ` — ${error.message}` : '.'}
              </span>
            </Notice>
          )}
          {summary && (
            <div className="flex flex-col gap-3">
              <StorageFillBar summary={summary} />
              <dl className="grid gap-x-6 gap-y-1 text-xs"
                  style={{ gridTemplateColumns: 'auto 1fr', maxWidth: 420 }}>
                <dt style={{ color: 'var(--color-text-secondary)' }}>Attachments</dt>
                <dd className="mono" style={{ color: 'var(--color-text)' }}>
                  {summary.attachmentCount.toLocaleString()}
                </dd>
                <dt style={{ color: 'var(--color-text-secondary)' }}>Largest single file</dt>
                <dd className="mono" style={{ color: 'var(--color-text)' }}>
                  {formatBytes(summary.maxFileBytes)}
                </dd>
                <dt style={{ color: 'var(--color-text-secondary)' }}>Counted as of</dt>
                <dd className="mono" style={{ color: 'var(--color-text)' }}>
                  {formatMoment(summary.asOf)}
                </dd>
              </dl>
              {/* The per-file limit and the workspace quota are different bounds
                  with different remedies, and a page that showed only one of
                  them would send a reader to fix the wrong thing. */}
              <p className="text-xs" style={{ color: 'var(--color-text-muted)', maxWidth: 620 }}>
                The single-file limit is a separate bound from the workspace total: a file over it is
                refused whatever the workspace has left, and a file under it is refused when the
                workspace has no room. Both are set by whoever runs this instance.
              </p>
            </div>
          )}
        </Card>

        <Card title="By project"
              blurb="Where the space went, largest first.">
          {!canEdit && !permissions.isLoading && (
            <Notice tone="info">
              <span className="text-sm">
                Seeing which projects hold the space needs the{' '}
                <span className="mono">workspace.edit</span> permission, which your role here does
                not grant. The workspace total above is readable by every member.
              </span>
            </Notice>
          )}
          {canEdit && breakdown.isLoading && (
            <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>Loading…</p>
          )}
          {canEdit && breakdown.isError && (
            <Notice tone="info">
              <span className="text-sm">
                {breakdown.error instanceof Error && breakdown.error.message
                  ? breakdown.error.message
                  : 'This breakdown could not be read just now.'}
              </span>
            </Notice>
          )}
          {canEdit && breakdown.data && (
            <BreakdownTable wsId={wsId!} data={breakdown.data} />
          )}
        </Card>
      </div>
    </div>
  )
}

function BreakdownTable({ wsId, data }: { wsId: string; data: WorkspaceStorageByProject }) {
  const total = data.totalBytes
  const share = (bytes: number) => (total > 0 ? (bytes / total) * 100 : 0)

  if (data.projects.length === 0 && data.unattributedBytes === 0) {
    return (
      <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
        No files are attached anywhere in this workspace yet.
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      <table className="w-full text-sm">
        <thead>
          <tr style={{ color: 'var(--color-text-muted)' }}>
            <th className="text-left font-medium text-xs pb-1">Project</th>
            <th className="text-right font-medium text-xs pb-1">Files</th>
            <th className="text-right font-medium text-xs pb-1">Size</th>
            <th className="text-right font-medium text-xs pb-1">Share</th>
          </tr>
        </thead>
        <tbody>
          {/* Order is the server's (bytes descending) and is not re-sorted here:
              two orderings of one list is how two surfaces start disagreeing. */}
          {data.projects.map(row => (
            <tr key={row.projectId} className="border-t" style={{ borderColor: 'var(--color-border)' }}>
              <td className="py-1.5 pr-3">
                {/* Absolute path: inside the settings splat a relative link would
                    resolve after the splat segment. */}
                <Link to={`/w/${wsId}/p/${row.projectId}`} className="hover:underline"
                      style={{ color: 'var(--color-text)' }}>
                  <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    {row.key}
                  </span>{' '}
                  {row.name}
                </Link>
              </td>
              <td className="py-1.5 text-right mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {row.attachmentCount.toLocaleString()}
              </td>
              <td className="py-1.5 text-right mono text-xs" style={{ color: 'var(--color-text)' }}>
                {formatBytes(row.bytes)}
              </td>
              <td className="py-1.5 text-right mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {formatPercent(share(row.bytes))}
              </td>
            </tr>
          ))}
          {/* Its own row, never folded into the total: a non-zero value here is
              the gap between the counter the quota enforces and the rows it is
              supposed to be a sum of, which is the one state this page exists to
              show. */}
          {data.unattributedBytes !== 0 && (
            <tr className="border-t" style={{ borderColor: 'var(--color-border)' }}>
              <td className="py-1.5 pr-3" style={{ color: 'var(--color-text-secondary)' }}>
                Not attributed to a project
              </td>
              <td className="py-1.5 text-right mono text-xs" style={{ color: 'var(--color-text-muted)' }}>—</td>
              <td className="py-1.5 text-right mono text-xs" style={{ color: 'var(--color-text)' }}>
                {formatBytes(data.unattributedBytes)}
              </td>
              <td className="py-1.5 text-right mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {formatPercent(share(data.unattributedBytes))}
              </td>
            </tr>
          )}
        </tbody>
      </table>

      {data.unattributedBytes !== 0 && (
        <p className="text-xs" style={{ color: 'var(--color-text-muted)', maxWidth: 620 }}>
          “Not attributed to a project” is the difference between the workspace counter and the sum
          of the rows above. It is normally zero, and a nightly pass recomputes the counter from the
          rows; a value that persists is worth showing to whoever runs this instance.
        </p>
      )}
      <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
        Total {formatBytes(total)} · counted as of {formatMoment(data.asOf)}.
      </p>
    </div>
  )
}

function formatMoment(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

function Card({ title, blurb, children }: {
  title: string
  blurb: string
  children: React.ReactNode
}) {
  return (
    <section aria-label={title} className="rounded-lg border p-4"
             style={{ background: 'var(--color-card)', borderColor: 'var(--color-border)',
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
