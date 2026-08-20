import { AlertTriangle } from 'lucide-react'
import type { AgingColumn, AgingItem } from '../../types'
import { CHART_AXIS, CHART_CONTEXT, CHART_GRID, CHART_SERIES } from './common'
import { formatDays } from './cycle'
import { niceAxis } from './scale'

/**
 * Aging work in progress (§2.2) — **the half that names the rotting item**.
 *
 * One column per non-DONE status of the project's effective workflow; inside a
 * column every open issue is a dot placed at its age, oldest at the top, and
 * every dot carries its **issue key and assignee** in plain text. The p50/p85
 * lines from the finished half are drawn across it, so an item sitting above p85
 * is visibly older than 85% of everything this team has ever finished.
 *
 * That is the whole reason this epic refuses the cumulative flow diagram: a CFD
 * shades an area and leaves the reader to guess which item is stuck. This says
 * *DEMO-31, Alex, 19 days* — and if a design decision has to give, the naming is
 * the part that stays.
 *
 * **Hand-drawn, not Recharts.** The library owns the finished-work scatter (and
 * this file imports none of it), but no chart library places a hundred text
 * labels without overlapping them, and here the labels ARE the report. So the
 * dot sits at its true age — that position is the measurement — and the label is
 * nudged down until it clears the label above it, with a hairline leader back to
 * its dot when the two part company. Nothing is ever moved to make the picture
 * tidier: only the text moves, never the dot.
 *
 * `aria-hidden`, like every chart here. The accessible reading, the keyboard
 * path and the links are the table underneath.
 */
export default function AgingChart({
  columns, p50, p85, assigneeName, columnColor, onSelect, height = 340,
}: {
  columns: AgingColumn[]
  p50: number | null
  p85: number | null
  assigneeName: (id: string | null) => string
  /** The status's own configured colour — taxonomy series never use the ramp. */
  columnColor: (column: AgingColumn) => string | null
  onSelect: (item: AgingItem) => void
  height?: number
}) {
  const oldest = Math.max(0, ...columns.flatMap(c => c.items.map(i => i.ageDays)))
  // The axis clears p85 as well as the data: a reference line drawn above the
  // top of the plot is a line the reader never sees and silently stops checking.
  const axis = niceAxis(Math.max(oldest, p85 ?? 0, p50 ?? 0))
  const y = (days: number) => height - (Math.max(0, Math.min(days, axis.max)) / axis.max) * height

  return (
    <div aria-hidden="true">
      <div className="flex" style={{ width: '100%' }}>
        {/* The age axis. Zero-based with fixed round ticks, like every axis in
            this feature, so two screenshots a week apart are comparable. */}
        <div style={{ width: 44, position: 'relative', height, flexShrink: 0 }}>
          {axis.ticks.map(t => (
            <span
              key={t}
              className="mono"
              style={{
                position: 'absolute', right: 6, top: y(t) - 7,
                fontSize: 11, color: CHART_AXIS, whiteSpace: 'nowrap',
              }}
            >
              {t}
            </span>
          ))}
        </div>

        <div style={{ flex: 1, minWidth: 0, position: 'relative', height, borderLeft: `1px solid ${CHART_GRID}` }}>
          {axis.ticks.map(t => (
            <div
              key={t}
              style={{
                position: 'absolute', left: 0, right: 0, top: y(t),
                borderTop: `1px solid ${CHART_GRID}`,
              }}
            />
          ))}

          {/* p50 / p85 — derived, secondary, dashed, in the context colour. */}
          {p50 != null && <Rule label={`p50 ${formatDays(p50)}d`} top={y(p50)} dash="5 4" />}
          {p85 != null && <Rule label={`p85 ${formatDays(p85)}d`} top={y(p85)} dash="2 3" />}

          <div className="flex" style={{ position: 'absolute', inset: 0 }}>
            {columns.map((column, ci) => (
              <div
                key={column.statusId ?? `stranded-${ci}`}
                style={{
                  flex: 1, minWidth: 0, position: 'relative',
                  borderRight: ci === columns.length - 1 ? 'none' : `1px solid ${CHART_GRID}`,
                }}
              >
                {column.items.length === 0 && (
                  <span
                    style={{
                      position: 'absolute', left: 0, right: 0, top: height / 2 - 8,
                      textAlign: 'center', fontSize: 11.5, color: 'var(--color-text-muted)',
                    }}
                  >
                    nothing here
                  </span>
                )}
                {layout(column.items, y, height).map(placed => {
                  const over85 = p85 != null && placed.item.ageDays > p85
                  const color = over85 ? CHART_SERIES[1] : CHART_SERIES[0]
                  const drift = Math.abs(placed.labelTop - (placed.dotTop - 3))
                  return (
                    <div key={placed.item.issueId}>
                      {drift > 3 && (
                        <span
                          style={{
                            position: 'absolute',
                            left: 9,
                            top: Math.min(placed.dotTop, placed.labelTop + 7),
                            height: drift,
                            borderLeft: `1px solid ${CHART_GRID}`,
                          }}
                        />
                      )}
                      <span
                        style={{
                          position: 'absolute', left: 6, top: placed.dotTop - 3,
                          width: 7, height: 7, borderRadius: 999, background: color,
                        }}
                      />
                      <span
                        onClick={() => onSelect(placed.item)}
                        title={`${placed.item.key} — ${placed.item.title} · ${formatDays(placed.item.ageDays)} days · ${assigneeName(placed.item.assigneeId)}`}
                        className="flex items-center gap-1 truncate cursor-pointer"
                        style={{
                          position: 'absolute', left: 16, top: placed.labelTop,
                          maxWidth: 'calc(100% - 20px)', fontSize: 11, lineHeight: '14px',
                          color: over85 ? color : 'var(--color-text-secondary)',
                          fontWeight: over85 ? 700 : 500,
                        }}
                      >
                        {/* Colour is never the only encoding: an item past p85
                            also carries a mark and, in the table below, a word. */}
                        {over85 && <AlertTriangle size={10} style={{ flexShrink: 0 }} />}
                        <span className="mono" style={{ flexShrink: 0 }}>{placed.item.key}</span>
                        <span className="truncate">{assigneeName(placed.item.assigneeId)}</span>
                      </span>
                    </div>
                  )
                })}
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Column feet: the status, its own colour, and how many sit in it. */}
      <div className="flex" style={{ width: '100%' }}>
        <div style={{ width: 44, flexShrink: 0 }} />
        <div className="flex" style={{ flex: 1, minWidth: 0 }}>
          {columns.map((column, ci) => (
            <div
              key={column.statusId ?? `stranded-foot-${ci}`}
              className="min-w-0"
              style={{ flex: 1, padding: '8px 6px 0' }}
            >
              <div className="flex items-center gap-1.5 min-w-0">
                <span
                  style={{
                    width: 8, height: 8, borderRadius: 999, flexShrink: 0,
                    background: columnColor(column) ?? CHART_CONTEXT,
                  }}
                />
                <span className="truncate" style={{ fontSize: 12, fontWeight: 600 }}>{column.name}</span>
              </div>
              <div className="mono" style={{ fontSize: 11, color: 'var(--color-text-muted)', marginTop: 2 }}>
                {column.items.length}
              </div>
            </div>
          ))}
        </div>
      </div>

      <p style={{ fontSize: 11, color: CHART_AXIS, margin: '10px 0 0 44px' }}>
        Age in days, since work started (or since creation for anything never started).
      </p>
    </div>
  )
}

/** One percentile rule across every column, with its value at the right edge. */
function Rule({ label, top, dash }: { label: string; top: number; dash: string }) {
  return (
    <>
      <svg
        style={{ position: 'absolute', left: 0, right: 0, top, width: '100%', height: 1, overflow: 'visible' }}
        preserveAspectRatio="none"
      >
        <line x1="0" y1="0" x2="100%" y2="0" stroke={CHART_CONTEXT} strokeWidth={2} strokeDasharray={dash} />
      </svg>
      <span
        className="mono"
        style={{
          position: 'absolute', right: 2, top: top - 14, fontSize: 11, color: CHART_AXIS,
          background: 'var(--color-card)', padding: '0 3px',
        }}
      >
        {label}
      </span>
    </>
  )
}

export interface PlacedItem {
  item: AgingItem
  /** Where the dot IS — the measurement, never adjusted. */
  dotTop: number
  /** Where its label fits — nudged down to clear the label above it. */
  labelTop: number
}

/**
 * Oldest first, dots at their true age, labels pushed down only as far as they
 * must be to stay legible.
 *
 * Exported for its own test: this is the one piece of geometry in the aging half
 * that can silently start lying (a label that drifts far from its dot reads as a
 * different age), and the invariant worth pinning is that `dotTop` is never
 * touched.
 */
export function layout(items: AgingItem[], y: (days: number) => number, height: number): PlacedItem[] {
  const LABEL_STEP = 15
  const sorted = [...items].sort((a, b) => b.ageDays - a.ageDays)
  const out: PlacedItem[] = []
  let floor = 0
  for (const item of sorted) {
    const dotTop = y(item.ageDays)
    const labelTop = Math.min(Math.max(dotTop - 7, floor), Math.max(0, height - 14))
    floor = labelTop + LABEL_STEP
    out.push({ item, dotTop, labelTop })
  }
  return out
}
