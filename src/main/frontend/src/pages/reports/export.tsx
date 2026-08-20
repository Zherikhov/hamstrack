import { useRef, useState, type RefObject } from 'react'
import { useLocation, useSearchParams } from 'react-router'
import { Copy, Download, Image as ImageIcon, Link2 } from 'lucide-react'
import { ApiResponseError, apiDownloadReportCsv, type ReportCsvKind } from '../../api'
import { Button } from '../../components/ui'
import { useProjectEntry } from '../../components/sprints'
import {
  copyImageToClipboard, copyTextToClipboard, csvFileName, downloadBlob, imageFileName,
  imageFooterLines, renderChartImage, type ImageProvenance,
} from './png'

/**
 * **Export** (HD-141, R7 — reports-proposal §4.4): the three ways a report
 * leaves the page, and the one distinction the research says everybody gets
 * wrong.
 *
 * Two components, because the two artefacts belong to different things. A
 * **report** has a shareable URL and a CSV; a **chart** has a picture. The
 * cycle-time page has one of the first and two of the second, and a design that
 * bolted all four buttons onto every card would have printed the same CSV link
 * twice on one page.
 *
 * ── The separation that is the whole point ──────────────────────────────────
 *
 * *"There is no native Jira sprint report export button"*, so people fall back
 * to a JQL CSV export producing **"flat issue lists without burndown or velocity
 * context"**. That is the documented disappointment: the user asks to export the
 * chart and is handed a different artefact answering a different question. So
 * this bar ships **two links with two labels** —
 *
 *  • **Chart series (CSV)** — the numbers plotted above, one row per data point,
 *    which is the export nobody else ships; and
 *  • **Matching issues (CSV)** — the issue list behind them.
 *
 * — and it prints the difference between them in a sentence rather than leaving
 * a reader to discover it by opening the wrong file. The second is **listed and
 * disabled today**: it hands off to a search export that does not exist yet
 * (there is no `…/search/export`, and HQL has no `project` field to scope one
 * with client-side — see the report on this slice). Listing it disabled is the
 * same Rule C reflex the reports list uses for a capability that is off: a
 * feature nobody can see is a feature nobody asks for, and hiding it would leave
 * the *series* CSV as the only export on screen — which is exactly how a reader
 * ends up believing it contains the issue list.
 *
 * ── Sharing ────────────────────────────────────────────────────────────────
 *
 * **Copy link pins the state before it copies.** Report pages deliberately leave
 * their URL incomplete until a control is touched — no window means "whatever
 * this server defaults to", no sprint means "whichever is active" — which is the
 * right default for browsing and the wrong thing to paste into a chat, where it
 * silently means a different report to the reader. So the button writes the
 * resolved state into the address bar first and copies that. The value of a
 * shareable URL is entirely in it reproducing what the sender saw.
 */

export interface CsvExport {
  kind: ReportCsvKind
  /** What this file contains, in the reader's words — never "Export". */
  label: string
  /** The report's own request parameters, resolved. */
  params: Record<string, string>
  /** Filename slug when the server sends no `Content-Disposition`. */
  slug: string
}

export function ReportExportBar({
  wsId, projectId, projectKey, shareParams, csv, disabled,
}: {
  wsId: string | undefined
  projectId: string | undefined
  projectKey: string | null | undefined
  /** The FULLY RESOLVED report state — what is on screen, not what the URL happens to say. */
  shareParams: Record<string, string>
  csv: CsvExport[]
  /** True while there is nothing to export (the report failed, or has not arrived). */
  disabled?: boolean
}) {
  const [, setSearchParams] = useSearchParams()
  const location = useLocation()
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState<string | null>(null)

  async function copyLink() {
    // Pin first: the address bar and the copied link must agree, and both must
    // describe the report on screen rather than a default that moves.
    setSearchParams(shareParams, { replace: true })
    const qs = new URLSearchParams(shareParams).toString()
    const origin = typeof window === 'undefined' ? '' : window.location.origin
    const url = `${origin}${location.pathname}${qs ? `?${qs}` : ''}`
    const ok = await copyTextToClipboard(url)
    setStatus(ok
      ? 'Link copied — it opens this exact report, window and filters.'
      : 'Couldn’t reach the clipboard. The address bar now holds this exact report — copy it from there.')
  }

  async function downloadCsv(item: CsvExport) {
    if (!wsId || !projectId) return
    setBusy(item.kind)
    setStatus('')
    try {
      await apiDownloadReportCsv(
        wsId, projectId, item.kind, item.params, csvFileName(item.slug, projectKey),
      )
      setStatus(`${item.label} downloaded as CSV.`)
    } catch (e) {
      const err = e instanceof ApiResponseError ? e : null
      setStatus(err
        // The server's own sentence, verbatim: a CSV inherits every refusal of
        // the report it serialises, and those refusals name the cap or the wait
        // they measured against. A generic banner would throw that away.
        ? `Couldn’t download that CSV: ${err.detail}`
        : 'Couldn’t download that CSV — the request failed. Try again.')
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" onClick={() => void copyLink()}>
          <Link2 size={13} aria-hidden="true" /> Copy link
        </Button>

        {csv.map(item => (
          <Button
            key={item.kind}
            size="sm"
            disabled={disabled || busy !== null}
            loading={busy === item.kind}
            onClick={() => void downloadCsv(item)}
          >
            <Download size={13} aria-hidden="true" /> {item.label} (CSV)
          </Button>
        ))}

        {/* Listed and disabled, never hidden — see the header. The title carries
            the reason, and the sentence below carries the distinction. */}
        <Button
          size="sm"
          disabled
          aria-disabled="true"
          title={ISSUES_CSV_HINT}
        >
          <Download size={13} aria-hidden="true" /> Matching issues (CSV)
          <span
            className="mono"
            style={{
              fontSize: 9, letterSpacing: '0.05em', marginLeft: 4,
              border: '1px solid var(--color-border-2)', borderRadius: 5, padding: '1px 4px',
            }}
          >
            SOON
          </span>
        </Button>
      </div>

      {/* The two labels are only unambiguous if somebody says what the other one
          holds. This sentence is the feature, not the chrome around it. */}
      <p className="text-xs" style={{ color: 'var(--color-text-muted)', margin: 0, maxWidth: 640 }}>
        <b>{csv.map(c => c.label).join(' / ')}</b> gives you the numbers plotted on this page, one row
        per point. <b>Matching issues</b> would give you the issue list behind them — a different
        file, and not the chart.
      </p>

      <ExportStatus status={status} />
    </div>
  )
}

/** Why the issue-list export is not clickable, in the one place a hover reaches it. */
export const ISSUES_CSV_HINT =
  'The issue-list export isn’t built yet — it hands off to the search export, which doesn’t exist. '
  + 'The CSV beside it holds this chart’s own numbers.'

/**
 * The picture of one chart: copy to clipboard, or download.
 *
 * Reads the chart's live `<svg>` through a ref rather than re-drawing anything,
 * so the exported image cannot disagree with the one on screen — and so the
 * fixed axes (`scale.ts`) are in the export by construction rather than by a
 * second implementation remembering to be fixed.
 *
 * A chart that has not arrived yet (the Recharts chunk is lazy) has no `<svg>`
 * to serialise, and the honest answer is to say so; a button that produces an
 * empty PNG is worse than one that says "not yet".
 */
export function ChartExport({
  chartRef, title, subtitle, provenance, slug, projectKey,
}: {
  chartRef: RefObject<HTMLDivElement | null>
  /** The report's name, drawn at the top of the image. */
  title: string
  subtitle?: string
  provenance: ImageProvenance
  slug: string
  projectKey: string | null | undefined
}) {
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  const nameAt = useRef(new Date())

  async function build(): Promise<Blob | null> {
    const svg = chartRef.current?.querySelector('svg')
    if (!svg) {
      setStatus('The chart hasn’t finished loading yet — try again in a moment.')
      return null
    }
    return renderChartImage(svg as SVGSVGElement, {
      title,
      subtitle,
      footerLines: imageFooterLines(provenance),
    })
  }

  async function copy() {
    setBusy(true)
    setStatus('')
    try {
      const blob = await build()
      if (!blob) return
      if (await copyImageToClipboard(blob)) {
        setStatus('Image copied — it carries the project, the window and when it was computed.')
        return
      }
      // Not an error: no browser copies images outside a secure context, and one
      // copies none at all. Downloading and saying so beats a dead button.
      downloadBlob(blob, imageFileName(slug, projectKey, nameAt.current))
      setStatus('Your browser can’t copy images, so it was downloaded instead.')
    } catch (e) {
      setStatus(messageFor(e))
    } finally {
      setBusy(false)
    }
  }

  async function download() {
    setBusy(true)
    setStatus('')
    try {
      const blob = await build()
      if (!blob) return
      downloadBlob(blob, imageFileName(slug, projectKey, nameAt.current))
      setStatus('Image downloaded.')
    } catch (e) {
      setStatus(messageFor(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col items-end gap-1" style={{ marginLeft: 'auto' }}>
      <div className="flex items-center gap-2">
        <Button size="sm" disabled={busy} onClick={() => void copy()} title={IMAGE_HINT}>
          <Copy size={13} aria-hidden="true" /> Copy image
        </Button>
        <Button size="sm" disabled={busy} onClick={() => void download()} title={IMAGE_HINT}>
          <ImageIcon size={13} aria-hidden="true" /> PNG
        </Button>
      </div>
      <ExportStatus status={status} align="right" />
    </div>
  )
}

/** What the picture promises, said before it is taken. */
export const IMAGE_HINT =
  'The image carries the project, the window and when it was computed in its footer, '
  + 'and its axes are zero-based with fixed ticks — so two exports taken weeks apart are comparable.'

/**
 * The one feedback surface for both components.
 *
 * `role="status"` rather than a toast: an export either happened or it did not,
 * and the sentence explaining a fallback ("your browser can't copy images, so it
 * was downloaded") has to be readable for longer than a toast lasts — and has to
 * reach a screen reader at all.
 */
function ExportStatus({ status, align }: { status: string; align?: 'right' }) {
  return (
    <p
      role="status"
      aria-live="polite"
      className="text-xs"
      style={{
        color: 'var(--color-text-secondary)', margin: 0, minHeight: status ? undefined : 0,
        textAlign: align === 'right' ? 'right' : undefined,
        maxWidth: 460,
      }}
    >
      {status}
    </p>
  )
}

function messageFor(e: unknown): string {
  const detail = e instanceof Error ? e.message : ''
  return detail
    ? `Couldn’t make the image: ${detail}`
    : 'Couldn’t make the image — try again, or use the CSV.'
}

/**
 * The project's identity, for the footer of an image that will be read far from
 * this page.
 *
 * Reads the already-cached `['project', wsId, projectId]` entry — the nav rail
 * and the reports area both populate it — so a report page pays nothing for it.
 * Before it arrives the id is used rather than a placeholder: an image whose
 * footer says "…" is an image that fails at the one job the footer has.
 */
export function useReportProject(wsId: string | undefined, projectId: string | undefined) {
  const { project } = useProjectEntry(wsId, projectId)
  const projectKey = project?.key ?? null
  const projectLabel = project
    ? (project.key ? `${project.name} (${project.key})` : project.name)
    : (projectId ?? 'Unknown project')
  return { project, projectKey, projectLabel }
}
