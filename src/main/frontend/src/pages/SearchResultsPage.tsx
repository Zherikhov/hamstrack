import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Bookmark, ChevronDown, Save, SlidersHorizontal, X } from 'lucide-react'
import {
  apiGetProjectConfig, apiSearch, apiSearchSchema,
  ApiResponseError, savedFilters, type HqlError,
} from '../api'
import { parseOrderBy, setOrderBy, nextSortDir } from '../hql'
import { Avatar, Button, PriorityBadge, StatusBadge } from '../components/ui'
import { Pager } from '../components/Pager'
import HqlInput from '../components/HqlInput'
import SavedFiltersPanel from '../components/SavedFiltersPanel'
import SaveFilterDialog from '../components/SaveFilterDialog'
import IssueSidePanel from './IssueSidePanel'
import type { ProjectConfig, SearchResultRow } from '../types'

// The result columns the chooser offers. `sort` is the HQL field the header
// rewrites ORDER BY with (null = not sortable). `key` doubles as the column id.
interface ColumnDef {
  key: string
  label: string
  sort: string | null
  default: boolean
}

const COLUMNS: ColumnDef[] = [
  { key: 'key', label: 'Key', sort: null, default: true },
  { key: 'type', label: 'Type', sort: 'type', default: true },
  { key: 'status', label: 'Status', sort: 'status', default: true },
  { key: 'priority', label: 'Priority', sort: 'priority', default: true },
  { key: 'assignee', label: 'Assignee', sort: 'assignee', default: true },
  { key: 'summary', label: 'Summary', sort: null, default: true },
  { key: 'project', label: 'Project', sort: null, default: true },
  { key: 'reporter', label: 'Reporter', sort: 'reporter', default: false },
  { key: 'due', label: 'Due', sort: 'due', default: false },
  { key: 'created', label: 'Created', sort: 'created', default: false },
  { key: 'updated', label: 'Updated', sort: 'updated', default: false },
]

const EXAMPLES = [
  'status = "In Progress" AND assignee = currentUser() ORDER BY priority DESC',
  'type = "Bug" AND priority >= "High" AND due IS NOT EMPTY',
  'reporter = currentUser() AND updated >= startOfWeek()',
  'text ~ "login" AND status != "Done"',
]

/**
 * Advanced search (HQL) results view. Workspace-scoped route
 * (/w/:wsId/search?q=…). The query lives in the URL so links/back work; sorting is
 * driven by the query's ORDER BY (clicking a header rewrites it and re-runs); rows
 * open the shared IssueSidePanel drawer, respecting each row's own project.
 */
export default function SearchResultsPage() {
  const { wsId } = useParams<{ wsId: string }>()
  const qc = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()

  // The committed (submitted) query drives the fetch; the input holds the draft.
  // `q` absent entirely (=== null) is a fresh landing → show the start view and
  // DON'T auto-run (an empty query returns all issues). An explicit empty string
  // (from hitting Search with a blank box) still runs, matching prior behavior.
  const rawQ = searchParams.get('q')
  const hasQueryParam = rawQ !== null
  const committed = rawQ ?? ''
  const [draft, setDraft] = useState(committed)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(50)   // search caps at 100 server-side
  const [hqlError, setHqlError] = useState<HqlError | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  // Column chooser + save dialog + panels
  const [visibleCols, setVisibleCols] = useState<Set<string>>(
    () => new Set(COLUMNS.filter(c => c.default).map(c => c.key))
  )
  const [colChooserOpen, setColChooserOpen] = useState(false)
  const [saveOpen, setSaveOpen] = useState(false)
  const [filtersOpen, setFiltersOpen] = useState(false)

  // When set, the Save flow offers "Update '<name>'" (edit the loaded filter's body)
  // in addition to "Save as new". Cleared on ✕, on a successful update, and whenever
  // a different saved filter is loaded.
  const [editingFilter, setEditingFilter] = useState<{ id: string; name: string } | null>(null)

  // Drawer: an opened row (cross-project — carries its own project).
  const [openRow, setOpenRow] = useState<SearchResultRow | null>(null)

  useEffect(() => { setDraft(committed) }, [committed])
  useEffect(() => { setPage(0) }, [committed, size])

  const { data: schema } = useQuery({
    queryKey: ['searchSchema', wsId],
    queryFn: () => apiSearchSchema(wsId!),
    enabled: !!wsId,
    staleTime: 5 * 60 * 1000,
  })

  const { data, isLoading, isFetching, isError, error } = useQuery({
    queryKey: ['search', wsId, committed, page, size],
    queryFn: () => apiSearch(wsId!, { query: committed, page, size }),
    enabled: !!wsId && hasQueryParam,
    placeholderData: prev => prev,
    retry: false,
  })

  // Surface a 422 (bad HQL) as an inline underline + message; keep other errors
  // as a plain banner. TanStack surfaces the thrown ApiResponseError.
  useEffect(() => {
    if (isError && error instanceof ApiResponseError) {
      setErrorMsg(error.detail)
      setHqlError(error.hql ?? null)
    } else if (!isError) {
      setErrorMsg(null)
      setHqlError(null)
    }
  }, [isError, error])

  function runQuery(next: string) {
    setHqlError(null)
    setErrorMsg(null)
    const params = new URLSearchParams(searchParams)
    if (next) params.set('q', next); else params.delete('q')
    setSearchParams(params)
  }

  // Loading a saved filter runs it and drops any in-progress "edit query" session
  // (the loaded filter is a fresh, un-edited query — not the one being edited).
  function loadFilter(hql: string) {
    setEditingFilter(null)
    setDraft(hql)
    runQuery(hql)
  }

  // "Edit query": load the filter's body into the search WITHOUT running it, and
  // enter edit mode so Save offers "Update '<name>'".
  function editFilterQuery(f: { id: string; name: string; hql: string }) {
    setDraft(f.hql)
    setEditingFilter({ id: f.id, name: f.name })
  }

  const order = useMemo(() => parseOrderBy(committed), [committed])

  function onSortHeader(col: ColumnDef) {
    if (!col.sort) return
    const dir = nextSortDir(order, col.sort)
    const next = setOrderBy(draft || committed, col.sort, dir)
    setDraft(next)
    runQuery(next)
  }

  const rows = data?.content ?? []
  const total = data?.totalElements ?? 0

  const cols = COLUMNS.filter(c => visibleCols.has(c.key))

  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden' }}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        {/* Query bar */}
        <div
          className="flex items-center gap-2 px-5 py-3 border-b flex-shrink-0"
          style={{ background: 'white', borderColor: 'var(--color-border)' }}
        >
          {editingFilter && (
            <div
              className="flex items-center gap-1.5 flex-shrink-0"
              style={{
                padding: '5px 8px 5px 10px', borderRadius: 'var(--radius-md)',
                background: 'color-mix(in srgb, var(--color-brand) 12%, white)',
                border: '1px solid var(--color-brand)',
              }}
            >
              <span className="text-xs" style={{ color: 'var(--color-brand)' }}>
                Editing: <b>{editingFilter.name}</b>
              </span>
              <button
                onClick={() => setEditingFilter(null)}
                className="cursor-pointer flex-shrink-0"
                title="Stop editing this filter"
                style={{ color: 'var(--color-brand)' }}
              >
                <X size={13} />
              </button>
            </div>
          )}
          <HqlInput
            wsId={wsId!}
            value={draft}
            onChange={setDraft}
            onSubmit={() => runQuery(draft)}
            schema={schema}
            error={hqlError}
            autoFocus
          />
          <Button variant="primary" size="md" onClick={() => runQuery(draft)}>Search</Button>
          <div style={{ position: 'relative' }}>
            <Button variant="secondary" size="md" onClick={() => setFiltersOpen(o => !o)}>
              <Bookmark size={14} />
              Saved filters
              <ChevronDown size={13} />
            </Button>
            {filtersOpen && wsId && (
              <SavedFiltersPanel
                wsId={wsId}
                onClose={() => setFiltersOpen(false)}
                onLoad={(hql) => { loadFilter(hql); setFiltersOpen(false) }}
                onEditQuery={(f) => { editFilterQuery(f); setFiltersOpen(false) }}
              />
            )}
          </div>
          <div style={{ position: 'relative' }}>
            <Button variant="secondary" size="md" onClick={() => setColChooserOpen(o => !o)}>
              <SlidersHorizontal size={14} />
              Columns
            </Button>
            {colChooserOpen && (
              <ColumnChooser
                visible={visibleCols}
                onToggle={key => setVisibleCols(prev => {
                  const n = new Set(prev)
                  if (n.has(key)) { if (n.size > 1) n.delete(key) } else n.add(key)
                  return n
                })}
                onClose={() => setColChooserOpen(false)}
              />
            )}
          </div>
          <Button variant="secondary" size="md" onClick={() => setSaveOpen(true)}>
            <Save size={14} />
            Save
          </Button>
        </div>

        {/* Error banner (non-422 errors; a 422 also underlines the input) */}
        {errorMsg && (
          <div
            className="flex items-start gap-2 px-5 py-2 border-b flex-shrink-0"
            style={{ background: 'color-mix(in srgb, var(--color-error) 8%, white)', borderColor: 'var(--color-border)' }}
          >
            <X size={14} style={{ color: 'var(--color-error)', marginTop: 2, flexShrink: 0 }} />
            <span className="text-xs" style={{ color: 'var(--color-error)' }}>{errorMsg}</span>
          </div>
        )}

        {/* Results */}
        <div className="flex-1 overflow-auto" style={{ background: 'var(--color-surface)' }}>
          {!hasQueryParam ? (
            <StartView
              wsId={wsId}
              onExample={ex => { setEditingFilter(null); setDraft(ex); runQuery(ex) }}
              onLoadFilter={loadFilter}
            />
          ) : isLoading ? (
            <div className="flex items-center justify-center py-16">
              <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>searching…</span>
            </div>
          ) : isError && !hqlError ? (
            <div className="flex flex-col items-center justify-center py-16 gap-2">
              <span className="text-sm" style={{ color: 'var(--color-text-muted)' }}>Search failed.</span>
            </div>
          ) : rows.length === 0 ? (
            <NoResults
              onExample={ex => { setEditingFilter(null); setDraft(ex); runQuery(ex) }}
            />
          ) : (
            <table className="w-full border-collapse">
              <thead>
                <tr>
                  {cols.map(c => {
                    const active = c.sort && order.field?.toLowerCase() === c.sort.toLowerCase()
                    return (
                      <th
                        key={c.key}
                        onClick={() => onSortHeader(c)}
                        className="text-left px-4 py-2 text-xs font-medium"
                        style={{
                          color: 'var(--color-text-muted)', background: 'white',
                          position: 'sticky', top: 0, zIndex: 1,
                          borderBottom: '1px solid var(--color-border)',
                          cursor: c.sort ? 'pointer' : 'default',
                          userSelect: 'none',
                        }}
                      >
                        <span className="inline-flex items-center gap-1">
                          {c.label}
                          {active && (order.dir === 'DESC'
                            ? <ArrowDown size={12} style={{ color: 'var(--color-brand)' }} />
                            : <ArrowUp size={12} style={{ color: 'var(--color-brand)' }} />)}
                        </span>
                      </th>
                    )
                  })}
                </tr>
              </thead>
              <tbody>
                {rows.map(row => (
                  <ResultRow
                    key={row.issue.id}
                    row={row}
                    cols={cols}
                    active={openRow?.issue.id === row.issue.id}
                    onClick={() => setOpenRow(prev => prev?.issue.id === row.issue.id ? null : row)}
                  />
                ))}
              </tbody>
            </table>
          )}
        </div>

        {data && total > 0 && (
          <Pager
            page={page}
            size={size}
            totalPages={data.totalPages}
            totalElements={total}
            onPage={setPage}
            onSize={setSize}
          />
        )}
        {isFetching && !isLoading && (
          <div className="px-5 py-1 text-xs mono flex-shrink-0" style={{ color: 'var(--color-text-muted)', background: 'white' }}>
            updating…
          </div>
        )}
      </div>

      {/* Cross-project drawer — fetches the row's project config on demand */}
      {openRow && wsId && (
        <ResultDrawer
          key={openRow.issue.id}
          wsId={wsId}
          row={openRow}
          onClose={() => setOpenRow(null)}
        />
      )}

      {saveOpen && wsId && (
        <SaveFilterDialog
          wsId={wsId}
          hql={draft}
          editing={editingFilter}
          onClose={() => setSaveOpen(false)}
          onSaved={() => { setSaveOpen(false); qc.invalidateQueries({ queryKey: ['savedFilters', wsId] }) }}
          onUpdated={() => {
            setSaveOpen(false)
            setEditingFilter(null)
            qc.invalidateQueries({ queryKey: ['savedFilters', wsId] })
          }}
        />
      )}
    </div>
  )
}

// ── Result row ────────────────────────────────────────────────────────────────

function ResultRow({ row, cols, active, onClick }: {
  row: SearchResultRow; cols: ColumnDef[]; active: boolean; onClick: () => void
}) {
  return (
    <tr
      onClick={onClick}
      className="cursor-pointer border-b transition-colors"
      style={{ borderColor: 'var(--color-border)', background: active ? 'var(--color-surface-2)' : 'white' }}
      onMouseEnter={e => { if (!active) e.currentTarget.style.background = 'var(--color-surface)' }}
      onMouseLeave={e => { if (!active) e.currentTarget.style.background = 'white' }}
    >
      {cols.map(c => (
        <td key={c.key} className="px-4 py-2.5" style={{ maxWidth: c.key === 'summary' ? 360 : undefined }}>
          <Cell col={c.key} row={row} />
        </td>
      ))}
    </tr>
  )
}

function Cell({ col, row }: { col: string; row: SearchResultRow }) {
  const i = row.issue
  const muted = { color: 'var(--color-text-muted)' }
  switch (col) {
    case 'key':
      return <span className="mono text-xs" style={muted}>{i.key}</span>
    case 'type':
      return <span className="text-xs" style={{ color: i.type.color }}>{i.type.name}</span>
    case 'status':
      return <StatusBadge name={i.status.name} category={i.status.category} color={i.status.color} />
    case 'priority':
      return <PriorityBadge priority={i.priority} />
    case 'assignee':
      return i.assignee ? (
        <div className="flex items-center gap-1.5">
          <Avatar name={i.assignee.displayName} avatarUrl={i.assignee.avatarUrl} size={20} />
          <span className="text-xs truncate max-w-24" style={{ color: 'var(--color-text-secondary)' }}>{i.assignee.displayName}</span>
        </div>
      ) : <span className="text-xs" style={muted}>—</span>
    case 'reporter':
      return (
        <div className="flex items-center gap-1.5">
          <Avatar name={i.reporter.displayName} avatarUrl={i.reporter.avatarUrl} size={20} />
          <span className="text-xs truncate max-w-24" style={{ color: 'var(--color-text-secondary)' }}>{i.reporter.displayName}</span>
        </div>
      )
    case 'summary':
      return <span className="text-sm truncate block" style={{ color: 'var(--color-text)' }}>{i.title}</span>
    case 'project':
      return <span className="text-xs truncate block" style={{ color: 'var(--color-text-secondary)' }}>{row.projectName}</span>
    case 'due':
      return <span className="mono text-xs" style={muted}>{i.dueDate ?? '—'}</span>
    case 'created':
      return <span className="mono text-xs" style={muted}>{i.createdAt.slice(0, 10)}</span>
    case 'updated':
      return <span className="mono text-xs" style={muted}>{i.updatedAt.slice(0, 10)}</span>
    default:
      return null
  }
}

// ── Cross-project drawer wrapper ──────────────────────────────────────────────
// The shared IssueSidePanel renders per-project taxonomy (statuses/types/
// priorities/fields), so we fetch the opened row's project config on demand.

function ResultDrawer({ wsId, row, onClose }: { wsId: string; row: SearchResultRow; onClose: () => void }) {
  const { data: config } = useQuery({
    queryKey: ['projectConfig', wsId, row.projectId],
    queryFn: () => apiGetProjectConfig(wsId, row.projectId),
  })
  const [openNumber, setOpenNumber] = useState(row.issue.number)
  const cfg: ProjectConfig | undefined = config
  if (!cfg) {
    return (
      <div
        className="flex-shrink-0 border-l flex items-center justify-center"
        style={{ width: 452, background: 'white', borderColor: 'var(--color-border)' }}
      >
        <span className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
      </div>
    )
  }
  return (
    <IssueSidePanel
      wsId={wsId}
      projectId={row.projectId}
      issueNumber={openNumber}
      issueTypes={cfg.issueTypes}
      statuses={cfg.statuses}
      transitions={cfg.transitions}
      priorities={cfg.priorities}
      fields={cfg.fields}
      onOpenIssue={setOpenNumber}
      onClose={onClose}
    />
  )
}

// ── Column chooser popover ────────────────────────────────────────────────────

function ColumnChooser({ visible, onToggle, onClose }: {
  visible: Set<string>; onToggle: (key: string) => void; onClose: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    function onDoc(e: MouseEvent) { if (!ref.current?.contains(e.target as Node)) onClose() }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [onClose])
  return (
    <div
      ref={ref}
      style={{
        position: 'absolute', right: 0, top: '100%', marginTop: 4, zIndex: 90, width: 190,
        background: 'var(--color-card)', border: '1px solid var(--color-border-2)',
        borderRadius: 'var(--radius-md)', boxShadow: 'var(--shadow-lg)', padding: 4,
      }}
    >
      {COLUMNS.map(c => (
        <label
          key={c.key}
          className="flex items-center gap-2 cursor-pointer"
          style={{ padding: '7px 10px', borderRadius: 'var(--radius-sm)', fontSize: 13 }}
        >
          <input
            type="checkbox"
            checked={visible.has(c.key)}
            onChange={() => onToggle(c.key)}
            style={{ accentColor: 'var(--color-brand)', width: 14, height: 14 }}
          />
          <span style={{ color: 'var(--color-text)' }}>{c.label}</span>
        </label>
      ))}
    </div>
  )
}

// ── Start view (fresh landing, no `q` param) ──────────────────────────────────
// Shown when the user opens Search with no query in the URL. Surfaces their
// saved filters (own + shared) up top — the whole point of this view, since the
// empty query would otherwise silently return every issue and hide them — with
// the example queries below. Does NOT run a search.

function StartView({ wsId, onExample, onLoadFilter }: {
  wsId?: string
  onExample: (ex: string) => void
  onLoadFilter: (hql: string) => void
}) {
  const { data: filters = [] } = useQuery({
    queryKey: ['savedFilters', wsId],
    queryFn: () => savedFilters.list(wsId!),
    enabled: !!wsId,
  })

  return (
    <div className="flex flex-col items-center justify-center py-16 gap-4" style={{ maxWidth: 560, margin: '0 auto' }}>
      <span className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
        Search across every project in this workspace with HQL.
      </span>

      {/* Saved filters (own + shared) — name/hql are plain text only (XSS guard). */}
      {filters.length > 0 && (
        <div className="w-full flex flex-col gap-1.5">
          <span className="text-xs font-semibold" style={{ color: 'var(--color-text-secondary)' }}>Your saved filters:</span>
          {filters.map(f => (
            <button
              key={f.id}
              onClick={() => onLoadFilter(f.hql)}
              className="text-left cursor-pointer transition-colors"
              style={{
                padding: '8px 12px', borderRadius: 'var(--radius-md)',
                background: 'white', border: '1px solid var(--color-border)',
              }}
              onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--color-brand)' }}
              onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--color-border)' }}
            >
              <div className="text-sm truncate" style={{ color: 'var(--color-text)' }}>
                {f.name}{!f.mine && ` · ${f.ownerName}`}
              </div>
              <div className="mono truncate" style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
                {f.hql || 'all issues'}
              </div>
            </button>
          ))}
        </div>
      )}

      <ExamplesList onExample={onExample} />
    </div>
  )
}

// ── No-results state (a query ran but matched nothing) ────────────────────────

function NoResults({ onExample }: { onExample: (ex: string) => void }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 gap-4" style={{ maxWidth: 560, margin: '0 auto' }}>
      <span className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
        No issues match this query.
      </span>
      <ExamplesList onExample={onExample} />
    </div>
  )
}

function ExamplesList({ onExample }: { onExample: (ex: string) => void }) {
  return (
    <div className="w-full flex flex-col gap-1.5">
      <span className="text-xs font-semibold" style={{ color: 'var(--color-text-secondary)' }}>Try:</span>
      {EXAMPLES.map(ex => (
        <button
          key={ex}
          onClick={() => onExample(ex)}
          className="mono text-left cursor-pointer transition-colors"
          style={{
            fontSize: 12, padding: '8px 12px', borderRadius: 'var(--radius-md)',
            background: 'white', border: '1px solid var(--color-border)', color: 'var(--color-text-secondary)',
          }}
          onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--color-brand)' }}
          onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--color-border)' }}
        >
          {ex}
        </button>
      ))}
    </div>
  )
}
