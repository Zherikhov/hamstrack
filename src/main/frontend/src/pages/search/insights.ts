/**
 * The pure half of the Insights panel (HD-140, slice R6 of the reports epic) —
 * **the dashboard replacement** (reports-proposal §1.5, §2.6).
 *
 * The refused thing was a grid of configurable gadgets. The best-documented
 * complaint about it is not that it is ugly but that its numbers *disagree with
 * each other*: every gadget carries its own filter, so two widgets on one screen
 * count overlapping sets and nobody can say which is right. This panel has **one
 * dataset — the HQL query already in the box** — so that failure is not
 * configured away, it is unrepresentable.
 *
 * The other half of the idea is that the panel is a **navigation device**, not a
 * picture: clicking a bar narrows the query. The clause that does it is
 * `bucket.hql`, and it is **the server's, never this module's** — see
 * {@link narrowClause}. Getting it wrong is the worst bug this feature can have,
 * because a fragment that returns a *different* set than the bar is valid HQL
 * and looks exactly like success.
 *
 * The response is a **cross tab, not a tree**: `slices` are the bars, `segments`
 * the legend, `cells` their intersections — three flat lists, capped
 * separately, which this module folds into the row shape the chart and the table
 * both read. Both are fed from ONE fold, so the picture and the numbers under it
 * cannot disagree about what was grouped into "Other".
 */

import type {
  InsightsBucket, InsightsDimension, InsightsMeasure, InsightsResponse, ReportMeta, SearchSchema,
} from '../../types'

// ── The two axes ─────────────────────────────────────────────────────────────

export interface MeasureDef {
  key: InsightsMeasure
  label: string
  /** Unit for the table header and the tooltip. Empty under `NONE`. */
  unit: string
}

export const INSIGHTS_MEASURES: MeasureDef[] = [
  { key: 'COUNT', label: 'Issue count', unit: 'issues' },
  { key: 'POINTS', label: 'Story points', unit: 'points' },
  { key: 'NONE', label: 'None — just the breakdown', unit: '' },
]

export interface DimensionDef {
  key: InsightsDimension
  label: string
  /**
   * The plural, spelled out rather than derived. Appending an `s` to the label
   * gives "statuss" and "prioritys", and a panel that cannot spell its own axis
   * is not one a sceptical reader trusts with a number.
   */
  plural: string
}

export const INSIGHTS_DIMENSIONS: DimensionDef[] = [
  { key: 'STATUS', label: 'Status', plural: 'statuses' },
  { key: 'TYPE', label: 'Type', plural: 'types' },
  { key: 'PRIORITY', label: 'Priority', plural: 'priorities' },
  { key: 'ASSIGNEE', label: 'Assignee', plural: 'assignees' },
  { key: 'COMPONENT', label: 'Component', plural: 'components' },
  { key: 'LABEL', label: 'Label', plural: 'labels' },
  { key: 'SPRINT', label: 'Sprint', plural: 'sprints' },
  { key: 'PROJECT', label: 'Project', plural: 'projects' },
]

export function dimensionDef(key: InsightsDimension): DimensionDef {
  return INSIGHTS_DIMENSIONS.find(d => d.key === key) ?? INSIGHTS_DIMENSIONS[0]
}

export function measureDef(key: InsightsMeasure): MeasureDef {
  return INSIGHTS_MEASURES.find(m => m.key === key) ?? INSIGHTS_MEASURES[0]
}

/**
 * The measures this workspace OFFERS, from `/search/schema`'s own `insights`
 * block — **never inferred here**.
 *
 * The server narrows that list on exactly the terms it narrows `fields`:
 * `POINTS` goes when no visible project estimates, `SPRINT` when none plans in
 * sprints. Reusing its answer is what keeps the panel and the query box agreeing
 * about what this workspace does, instead of two capability rules drifting apart
 * in two languages.
 *
 * **An absent block means "offer everything"**, not "offer nothing": a server
 * that predates the panel, or a schema request that failed, must not silently
 * cost the workspace its sprint slice. Withholding a control on missing
 * information is the same class of mistake as inferring a capability from data —
 * it makes an unrelated failure look like a deliberate configuration.
 *
 * And whatever is omitted is still **sent and still answered** if a shared URL
 * names it (Rule A): this list decides the toolbar, never the request.
 */
export function offeredMeasures(schema: SearchSchema | undefined): MeasureDef[] {
  const allowed = schema?.insights?.measures
  if (!allowed || allowed.length === 0) return INSIGHTS_MEASURES
  return INSIGHTS_MEASURES.filter(m => allowed.includes(m.key))
}

/** The slices/segments this workspace OFFERS. Same rule, same caveat. */
export function offeredDimensions(schema: SearchSchema | undefined): DimensionDef[] {
  const allowed = schema?.insights?.dimensions
  if (!allowed || allowed.length === 0) return INSIGHTS_DIMENSIONS
  return INSIGHTS_DIMENSIONS.filter(d => allowed.includes(d.key))
}

// ── URL state (§4.4 — a panel that cannot be pasted into Slack is half a feature) ──

export interface InsightsState {
  open: boolean
  measure: InsightsMeasure
  slice: InsightsDimension
  /** Null = no colour dimension. */
  segment: InsightsDimension | null
}

export const INSIGHTS_DEFAULTS: InsightsState = {
  open: false,
  measure: 'COUNT',
  slice: 'STATUS',
  segment: null,
}

const MEASURE_KEYS = new Set(INSIGHTS_MEASURES.map(m => m.key))
const DIMENSION_KEYS = new Set(INSIGHTS_DIMENSIONS.map(d => d.key))

/**
 * Read the panel out of the URL.
 *
 * Unrecognised values degrade to the default **silently and deliberately**: a
 * value that is not a measure at all is a typo or a client from another release,
 * and announcing a fallback for it teaches nothing. A value that IS a measure
 * this workspace does not currently offer is the opposite case — a real request
 * — and is honoured, not clamped; the panel says so in a sentence (see
 * {@link hiddenChoices}).
 *
 * A `segment` equal to the `slice` is dropped rather than sent: the endpoint
 * refuses that pair with a 422, and a diagonal is not a breakdown anyway.
 */
export function readInsightsState(params: URLSearchParams): InsightsState {
  const rawMeasure = (params.get('measure') ?? '').toUpperCase()
  const rawSlice = (params.get('slice') ?? '').toUpperCase()
  const rawSegment = (params.get('segment') ?? '').toUpperCase()

  const measure = MEASURE_KEYS.has(rawMeasure as InsightsMeasure)
    ? rawMeasure as InsightsMeasure
    : INSIGHTS_DEFAULTS.measure
  const slice = DIMENSION_KEYS.has(rawSlice as InsightsDimension)
    ? rawSlice as InsightsDimension
    : INSIGHTS_DEFAULTS.slice
  let segment = DIMENSION_KEYS.has(rawSegment as InsightsDimension)
    ? rawSegment as InsightsDimension
    : null
  if (segment === slice) segment = null
  // A breakdown with no measure has no heights to stack, so a colour dimension
  // would be a legend over nothing.
  if (measure === 'NONE') segment = null

  return { open: isOpen(params.get('insights')), measure, slice, segment }
}

function isOpen(raw: string | null): boolean {
  if (raw === null) return false
  const v = raw.trim().toLowerCase()
  return v === '' || v === '1' || v === 'true' || v === 'open' || v === 'yes'
}

/**
 * Write the panel INTO an existing `URLSearchParams`, preserving everything else
 * on it — `q` above all, which is the dataset. Returns a new object; the input
 * is not mutated.
 *
 * A closed panel removes all four keys rather than writing `insights=0`: the URL
 * of a search with no panel open should be the URL it was before anybody opened
 * one, or every link grows four parameters that mean "nothing to see".
 */
export function writeInsightsParams(
  current: URLSearchParams, state: InsightsState,
): URLSearchParams {
  const next = new URLSearchParams(current)
  if (!state.open) {
    for (const key of ['insights', 'measure', 'slice', 'segment']) next.delete(key)
    return next
  }
  next.set('insights', '1')
  next.set('measure', state.measure)
  next.set('slice', state.slice)
  if (state.segment) next.set('segment', state.segment)
  else next.delete('segment')
  return next
}

/**
 * The choices the URL asked for that this workspace does not currently offer.
 *
 * The panel honours them — a capability gates the UI and never the answer — and
 * prints this list, because a control missing from the toolbar while the chart
 * plainly uses it is the shape of a bug report.
 */
export function hiddenChoices(
  state: InsightsState, schema: SearchSchema | undefined,
): string[] {
  const measures = new Set(offeredMeasures(schema).map(m => m.key))
  const dimensions = new Set(offeredDimensions(schema).map(d => d.key))
  const out: string[] = []
  if (!measures.has(state.measure)) out.push(measureDef(state.measure).label.toLowerCase())
  for (const dim of [state.slice, state.segment]) {
    if (dim && !dimensions.has(dim)) out.push(dimensionDef(dim).label.toLowerCase())
  }
  return [...new Set(out)]
}

// ── The response, defensively ────────────────────────────────────────────────

/** What a missing `meta` degrades to — printed as-is, never invented. */
const UNKNOWN_META: ReportMeta = {
  computedAt: '',
  basedOnIssues: 0,
  truncated: false,
  cap: 0,
  firstIssueAt: null,
  unmatchedFilters: [],
}

/**
 * Normalise a response into something the panel can render without a hole.
 *
 * Not paranoia about the network — it is about **two ends of one contract that
 * ship separately**. Each missing piece here has an obvious correct fallback (no
 * cells, no legend, an unclickable bucket), and taking it keeps a thin response
 * readable instead of turning an added-later field into a blank panel.
 *
 * The one thing it does NOT invent is `meta`: a report that cannot say when it
 * was computed prints an empty provenance line rather than today's date.
 */
export function normalizeInsights(raw: InsightsResponse | undefined): InsightsResponse | undefined {
  if (!raw) return undefined
  return {
    measure: raw.measure ?? 'COUNT',
    slice: raw.slice ?? 'STATUS',
    segment: raw.segment ?? null,
    slices: (raw.slices ?? []).map(s => ({
      bucket: normalizeBucket(s.bucket),
      count: num(s.count),
      points: numOrNull(s.points),
      unestimatedCount: Math.max(0, Math.round(num(s.unestimatedCount))),
    })),
    segments: (raw.segments ?? []).map(normalizeBucket),
    cells: (raw.cells ?? []).map(c => ({
      sliceId: c.sliceId ?? null,
      segmentId: c.segmentId ?? null,
      count: num(c.count),
      points: numOrNull(c.points),
      unestimatedCount: Math.max(0, Math.round(num(c.unestimatedCount))),
    })),
    sliceMultiValued: !!raw.sliceMultiValued,
    segmentMultiValued: !!raw.segmentMultiValued,
    slicesTruncated: !!raw.slicesTruncated,
    cellsTruncated: !!raw.cellsTruncated,
    sliceCap: num(raw.sliceCap),
    cellCap: num(raw.cellCap),
    meta: raw.meta ? { ...UNKNOWN_META, ...raw.meta } : UNKNOWN_META,
  }
}

function normalizeBucket(b: InsightsBucket | undefined): InsightsBucket {
  return {
    id: b?.id ?? null,
    // "None" is the only label this file invents, and only when the server sent
    // none for the empty bucket. It is a bucket a reader has to be able to name
    // in order to click it.
    label: b?.label && b.label.trim() ? b.label : (b?.id ? b.id : 'None'),
    hql: b?.hql ?? null,
  }
}

function num(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

function numOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** A stable identity for a bucket across the three lists — and the series key. */
export function bucketKey(bucket: Pick<InsightsBucket, 'id'>): string {
  return bucket.id ?? ' none'
}

// ── The click-through — the thing that makes this a feature ──────────────────

/**
 * The HQL clause that narrows the query to one bucket, or **null when this
 * bucket cannot be expressed as a query**.
 *
 * A one-line function on purpose. It is `bucket.hql` and nothing else: the
 * server emits a fragment only when the fragment returns *precisely* the issues
 * that bucket counted, and null in the three cases where it would not —
 * `PROJECT` (HQL has no vocabulary for it), a name owned by two visible projects
 * (the clause would be WIDER than the bar), and a name outside HQL name
 * resolution such as a completed sprint or an archived label (the clause would
 * 422 on click).
 *
 * **Never reconstruct it here.** Telling those cases apart needs the workspace's
 * whole name→id map, and a client that guesses ships a click which quietly
 * answers a different question than the bar it was on — valid HQL, plausible
 * numbers, wrong set. That is strictly worse than a bar that says it cannot be
 * clicked, which is why the null is carried all the way into the UI instead of
 * being smoothed over.
 */
export function narrowClause(bucket: InsightsBucket): string | null {
  const hql = bucket.hql
  return hql && hql.trim() ? hql : null
}

// ── The bars ─────────────────────────────────────────────────────────────────

/**
 * How many bars the CHART draws. The table underneath draws every one the
 * server shipped (up to its own `sliceCap`, which the panel reports separately).
 *
 * A bar chart of two hundred assignees is not a smaller version of a readable
 * chart, it is an unreadable one — but folding the tail into an "Other" bar
 * would invent an aggregate nobody asked for and make it unclickable. So the
 * chart truncates, the table does not, and the panel prints the difference.
 */
export const MAX_BARS = 24

/**
 * How many colours a segmented chart may use before the tail becomes "Other".
 *
 * DESIGN.md, verbatim: *"Five is the limit. A slice with more than five
 * categories groups the tail into Other rather than inventing a sixth hue:
 * colours 6–10 of any ramp are not reliably distinguishable for colour-blind
 * readers, and a 12-slice legend is unreadable for everyone."*
 */
export const MAX_SERIES = 5

/** The reserved series key for the folded tail — never a real entity id. */
export const OTHER_KEY = ' other'

export interface SegmentSeries {
  key: string
  label: string
  total: number
  /**
   * True for the folded tail. An "Other" band is **not clickable**: there is no
   * single value to narrow to, and a click that narrowed to the largest member
   * of the fold would be a different question wearing the same colour.
   */
  other: boolean
  /** The bucket to narrow by, absent on the fold. */
  bucket?: InsightsBucket
}

/** The value of a slice/cell under the selected measure. `NONE` has none. */
export function measureValue(
  row: { count: number; points: number | null }, measure: InsightsMeasure,
): number {
  if (measure === 'NONE') return 0
  if (measure === 'POINTS') return row.points ?? 0
  return row.count
}

export interface ChartRow {
  key: string
  label: string
  bucket: InsightsBucket
  /** The bar's height under the selected measure — the server's exact total. */
  total: number
  unestimatedCount: number
  /** Segment totals by series key; empty when unsegmented. */
  values: Record<string, number>
  /** The bucket behind each series key, for the click-through. */
  buckets: Record<string, InsightsBucket>
}

export interface InsightsFold {
  rows: ChartRow[]
  series: SegmentSeries[]
  /**
   * Sum of the bars under the selected measure.
   *
   * **Not the issue count, and the panel may never label it as one.** On a
   * many-valued slice an issue lands in several bars, and on a truncated one
   * some bars are missing — so this agrees with `meta.basedOnIssues` only by
   * coincidence. `meta.basedOnIssues` is the dataset; this is the picture.
   */
  barSum: number
  /** Issues in the shipped bars carrying no estimate. */
  unestimated: number
}

/**
 * Fold the cross tab into the rows the chart and the table both read.
 *
 * ONE fold feeding both is the point: the table under a chart is only the
 * accessibility answer if it is the *same* numbers, and a second computation of
 * "which segments were grouped into Other" is a second chance to disagree.
 *
 * Series are ranked by their summed measure rather than by the server's order,
 * because the five that get a colour should be the five worth telling apart —
 * a taxonomy order would hand the hues to whichever statuses sort first.
 */
export function foldInsights(
  data: InsightsResponse | undefined, measure: InsightsMeasure,
): InsightsFold {
  if (!data) return { rows: [], series: [], barSum: 0, unestimated: 0 }

  const segmentsById = new Map(data.segments.map(b => [bucketKey(b), b]))

  // Rank the legend by its share of the whole picture, then fold the tail.
  const totals = new Map<string, number>()
  for (const cell of data.cells) {
    const key = cell.segmentId ?? ' none'
    totals.set(key, (totals.get(key) ?? 0) + measureValue(cell, measure))
  }
  let series: SegmentSeries[] = []
  if (data.segment) {
    const all = [...segmentsById.values()]
      .map(b => ({
        key: bucketKey(b),
        label: b.label,
        total: totals.get(bucketKey(b)) ?? 0,
        other: false,
        bucket: b,
      }))
      .sort((a, b) => b.total - a.total || a.label.localeCompare(b.label))
    if (all.length > MAX_SERIES) {
      const tail = all.slice(MAX_SERIES)
      series = all.slice(0, MAX_SERIES)
      series.push({
        key: OTHER_KEY,
        label: `Other (${tail.length.toLocaleString()})`,
        total: tail.reduce((n, s) => n + s.total, 0),
        other: true,
      })
    } else {
      series = all
    }
  }
  const known = new Set(series.filter(s => !s.other).map(s => s.key))
  const hasFold = series.some(s => s.other)

  const rows: ChartRow[] = data.slices.map(slice => ({
    key: bucketKey(slice.bucket),
    label: slice.bucket.label,
    bucket: slice.bucket,
    total: measureValue(slice, measure),
    unestimatedCount: slice.unestimatedCount,
    values: Object.fromEntries(series.map(s => [s.key, 0])),
    buckets: {},
  }))
  const byKey = new Map(rows.map(r => [r.key, r]))

  for (const cell of data.cells) {
    const row = byKey.get(cell.sliceId ?? ' none')
    if (!row) continue
    const segKey = cell.segmentId ?? ' none'
    const target = known.has(segKey) ? segKey : (hasFold ? OTHER_KEY : segKey)
    if (!(target in row.values)) continue
    row.values[target] += measureValue(cell, measure)
    const bucket = segmentsById.get(segKey)
    if (bucket && target === segKey) row.buckets[segKey] = bucket
  }

  return {
    rows,
    series,
    barSum: rows.reduce((n, r) => n + r.total, 0),
    unestimated: data.slices.reduce((n, s) => n + s.unestimatedCount, 0),
  }
}

// ── Formatting ───────────────────────────────────────────────────────────────

/**
 * A measure, printed. `NONE` prints an em dash rather than `0`: there is no
 * number, and a zero is a claim.
 */
export function formatInsightsValue(value: number, measure: InsightsMeasure): string {
  if (measure === 'NONE') return '—'
  if (!Number.isFinite(value)) return '—'
  if (measure === 'POINTS') {
    return Number.isInteger(value) ? value.toLocaleString() : value.toFixed(1)
  }
  return Math.round(value).toLocaleString()
}

/** `issues` / `points` / nothing under `NONE`. */
export function measureUnit(measure: InsightsMeasure): string {
  return measureDef(measure).unit
}

/**
 * The one-line summary above the chart — what was aggregated, over what, sliced
 * how. It is the sentence a reader checks the picture against.
 *
 * It counts **`meta.basedOnIssues`**, never the bar sum: those two differ on a
 * many-valued slice and on a truncated one, and the caption is the place a
 * reader is most likely to mistake one for the other.
 */
export function insightsCaption(
  data: Pick<InsightsResponse, 'meta' | 'slices'>,
  slice: InsightsDimension,
  segment: InsightsDimension | null,
): string {
  const def = dimensionDef(slice)
  const n = data.slices.length
  const dim = n === 1 ? def.label.toLowerCase() : def.plural
  const issues = data.meta.basedOnIssues
  const head = `${issues.toLocaleString()} issue${issues === 1 ? '' : 's'}`
    + ` across ${n.toLocaleString()} ${dim}`
  return segment
    ? `${head}, coloured by ${dimensionDef(segment).label.toLowerCase()}`
    : head
}
