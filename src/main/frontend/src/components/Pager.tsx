import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Select } from './ui'

export const PAGE_SIZES = [10, 20, 50, 100]

/** Page navigation + per-page size selector for paginated list/table views. */
export function Pager({ page, size, totalPages, totalElements, onPage, onSize }: {
  page: number
  size: number
  totalPages: number
  totalElements: number
  onPage: (p: number) => void
  onSize: (s: number) => void
}) {
  const from = totalElements === 0 ? 0 : page * size + 1
  const to = Math.min((page + 1) * size, totalElements)
  return (
    <div
      className="flex items-center gap-3 px-5 py-2 border-t flex-shrink-0"
      style={{ background: 'white', borderColor: 'var(--color-border)' }}
    >
      <label className="text-xs flex items-center gap-1.5" style={{ color: 'var(--color-text-muted)' }}>
        Per page
        <Select compact value={size} onChange={e => onSize(Number(e.target.value))}>
          {PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
        </Select>
      </label>
      <span className="ml-auto mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
        {from}–{to} of {totalElements}
      </span>
      <div className="flex items-center gap-1">
        <button
          disabled={page <= 0}
          onClick={() => onPage(page - 1)}
          className="p-1 rounded disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
          style={{ color: 'var(--color-text-secondary)' }}
          aria-label="Previous page"
        >
          <ChevronLeft size={16} />
        </button>
        <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
          {totalPages === 0 ? 0 : page + 1} / {totalPages}
        </span>
        <button
          disabled={page + 1 >= totalPages}
          onClick={() => onPage(page + 1)}
          className="p-1 rounded disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
          style={{ color: 'var(--color-text-secondary)' }}
          aria-label="Next page"
        >
          <ChevronRight size={16} />
        </button>
      </div>
    </div>
  )
}
