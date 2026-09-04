import { formatBytes, formatPercent } from '../lib/bytes'
import type { StorageQuotaRefusal } from '../apiError'
import type { WorkspaceStorageSummary } from '../types'

/**
 * The storage-quota surfaces, and the rules that decide when each one appears
 * (HD-191 §12).
 *
 * ## The one thing this module refuses to do
 *
 * **Nothing here prescribes an action to its reader.** The refusal describes a
 * situation and stops. That is a decision taken in the spec (§5.5) and it is not
 * a tone preference: the reader of a quota refusal may hold no delete grant at
 * all (`attachment.delete` is own-only for most roles), the space is very often
 * in a project they cannot see, and "ask your administrator" is a dead end in
 * **both** deployment models — on Cloud a workspace owner cannot raise an
 * instance property, and on a self-hosted install the reader may not know who
 * holds the `.env`. A refusal may only prescribe an action its reader can
 * perform, so this one prescribes none.
 *
 * For the same reason nothing here says "try again shortly". The quota refusal
 * is a **409 with no `Retry-After`** — waiting frees no bytes — and rendering it
 * as a rate limit would be an instruction that cannot work. The two upload 429s
 * are the ones that pass, and they carry the header that says so.
 *
 * ## Rule C — a surface that survives its capability being off
 *
 * `quotaEnabled` false is a real deployment, not an absence: usage is still
 * counted, still reported and still reconciled (§6.9). So the settings page
 * still renders every figure and says plainly that nothing is enforced, rather
 * than vanishing — a page that only exists once a limit exists is unreachable
 * for exactly the operator deciding whether to set one. The *warn line* is the
 * opposite case and is absent by the same logic: with no quota there is no
 * threshold to be above, so there is no true sentence to put beside an upload
 * button.
 */

/**
 * How space comes back. One sentence, in the passive, naming a mechanism and no
 * person — see the module note on why it names no person.
 */
export const STORAGE_FREED_BY =
  'Storage is freed by deleting attachments that are no longer needed.'

/** Is there a quota to be a fraction of? Narrows the three nullable fields together. */
export function hasQuota(summary: WorkspaceStorageSummary | undefined): summary is
  WorkspaceStorageSummary & { quotaBytes: number; availableBytes: number; percentUsed: number } {
  return !!summary && summary.quotaEnabled
    && summary.quotaBytes !== null && summary.availableBytes !== null
    && summary.percentUsed !== null
}

/**
 * The quiet line beside the upload control — **`null` below the threshold, and
 * that silence is the design**: a storage figure on every issue page is noise,
 * so the chrome appears only at or above `warnAtPercent`, which is the operator's
 * own number and never a constant in this bundle.
 */
export function storageWarning(summary: WorkspaceStorageSummary | undefined): string | null {
  if (!hasQuota(summary)) return null
  if (summary.percentUsed < summary.warnAtPercent) return null
  if (summary.availableBytes <= 0) {
    return `This workspace has used all of its attachment storage `
      + `(${formatBytes(summary.usedBytes)} of ${formatBytes(summary.quotaBytes)}). `
      + STORAGE_FREED_BY
  }
  return `${formatBytes(summary.availableBytes)} of ${formatBytes(summary.quotaBytes)} remaining.`
}

/**
 * Why this file will not be sent, or `null` when it fits — the client-side half
 * of the door, checked before the request rather than after it.
 *
 * It names **both** numbers, because the point of checking early is to be
 * legible, and "too large" without the two figures is the silent no-op §12.1
 * forbids. It is only ever a courtesy: the server holds the guarantee and
 * refuses the same upload on its own parsed size, so a stale cache here costs a
 * round trip and never a wrong outcome.
 */
export function quotaBlockFor(
  summary: WorkspaceStorageSummary | undefined,
  file: { name: string; size: number },
): string | null {
  if (!hasQuota(summary)) return null
  if (file.size <= summary.availableBytes) return null
  const remaining = summary.availableBytes > 0
    ? `${formatBytes(summary.availableBytes)} of ${formatBytes(summary.quotaBytes)} remaining`
    : `no remaining storage (${formatBytes(summary.usedBytes)} of ${formatBytes(summary.quotaBytes)} used)`
  return `${file.name} is ${formatBytes(file.size)} and this workspace has ${remaining}.`
}

/**
 * The refusal, rendered from the **409's own figures** — never from the cached
 * summary, which is a minute old by design and would be describing a different
 * moment than the one the server refused in.
 *
 * `detail` is the server's sentence and is rendered as it stands; the figures
 * line under it is this build's arithmetic over the four numbers the body
 * carried. When the body carried none (an older server, a proxy that ate the
 * extensions), the sentence alone is still the whole refusal — which is why the
 * parser refuses a partial read rather than filling a zero in.
 */
export function StorageQuotaRefusalNotice({ detail, refusal }: {
  detail: string
  refusal?: StorageQuotaRefusal
}) {
  return (
    <div className="text-xs rounded-md px-2.5 py-2 flex flex-col gap-1"
         style={{
           background: 'color-mix(in srgb, var(--color-warning) 10%, white)',
           border: '1px solid color-mix(in srgb, var(--color-warning) 34%, white)',
           color: 'var(--color-text)',
         }}>
      <span>{detail}</span>
      {refusal && (
        <span className="mono" style={{ color: 'var(--color-text-secondary)' }}>
          {formatBytes(refusal.usedBytes)} of {formatBytes(refusal.quotaBytes)} used ·
          {' '}this file needed {formatBytes(refusal.fileBytes)}
        </span>
      )}
    </div>
  )
}

/**
 * The fill bar, with the warn threshold marked.
 *
 * **The percentage is never clamped.** A workspace can be past its quota — the
 * operator lowered it, or it grew past one that was raised — and §6.9 keeps that
 * visible on purpose: the over-100% figure is the only thing on the page that
 * explains why uploads are being refused, and hiding it behind a full-looking
 * bar would leave the refusals unexplained. The track can only be full, so the
 * *number* carries the overflow and the bar changes colour to say the track ran
 * out.
 */
export function StorageFillBar({ summary }: { summary: WorkspaceStorageSummary }) {
  const quota = hasQuota(summary) ? summary : null
  const percent = quota?.percentUsed ?? null
  const over = percent !== null && percent > 100
  const warn = percent !== null && percent >= summary.warnAtPercent

  const fill = over ? 'var(--color-error)' : warn ? 'var(--color-warning)' : 'var(--color-brand)'

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between gap-3 flex-wrap">
        <span className="mono text-sm" style={{ color: 'var(--color-text)' }}>
          {formatBytes(summary.usedBytes)}
          {quota && <> of {formatBytes(quota.quotaBytes)}</>}
        </span>
        {percent !== null && (
          <span className="text-sm font-semibold"
                style={{ color: over ? 'var(--color-error-ink)' : 'var(--color-text-secondary)' }}>
            {formatPercent(percent)} of the quota
          </span>
        )}
      </div>

      {/* Decoration over figures that are all in text above and below it. */}
      <div aria-hidden className="relative rounded-full overflow-hidden"
           style={{ height: 10, background: 'var(--color-surface-2)' }}>
        <div className="h-full"
             style={{
               width: percent === null ? '100%' : `${Math.max(0, Math.min(100, percent))}%`,
               background: percent === null ? 'var(--color-border-2)' : fill,
               transition: 'width 200ms ease-out',
             }} />
        {quota && summary.warnAtPercent < 100 && (
          <div style={{
            position: 'absolute', top: 0, bottom: 0, left: `${summary.warnAtPercent}%`,
            width: 2, background: 'var(--color-card)', opacity: 0.9,
          }} />
        )}
      </div>

      {quota
        ? (
          <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            {over
              ? <>Past the quota by {formatBytes(quota.usedBytes - quota.quotaBytes)}. Existing
                  files stay readable and downloadable; new uploads are refused. {STORAGE_FREED_BY}</>
              : <>{formatBytes(quota.availableBytes)} remaining. The mark on the bar is{' '}
                  {summary.warnAtPercent}%, where this workspace starts saying so on its issue pages.</>}
          </p>
        )
        : (
          <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            This instance does not enforce a storage quota. Usage is still counted and reported
            here, so this is the figure to read before setting one.
          </p>
        )}
    </div>
  )
}
