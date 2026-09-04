import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { componentsApi } from '../api'
import { Select } from './ui'
import type { Component, ComponentRef } from '../types'

/**
 * Component rendering + picking (HD-31) — the components counterpart of
 * `components/labels.tsx`: every surface that shows or picks a component (create
 * dialog, issue detail, board cards, backlog rows, board/backlog filter bars)
 * goes through this module so one component reads the same everywhere.
 *
 * Components are PROJECT-scoped content, not project taxonomy: they are fetched
 * from their own endpoint under `componentsKey(wsId, projectId)` and never travel
 * in `ProjectConfig` (so renaming one doesn't invalidate every board's config).
 * One per issue — hence a `Select`, not the multi-select typeahead labels use.
 */

/** Query key for one project's component list (pickers/filters: non-archived only). */
export function componentsKey(wsId: string | undefined, projectId: string | undefined) {
  return ['components', wsId, projectId] as const
}

/** The project's non-archived components — the source for every picker/filter. */
export function useProjectComponents(
  wsId: string | undefined,
  projectId: string | undefined,
  enabled = true,
) {
  return useQuery({
    queryKey: componentsKey(wsId, projectId),
    queryFn: () => componentsApi.list(wsId!, projectId!),
    enabled: !!wsId && !!projectId && enabled,
  })
}

/**
 * The component of an issue as a quiet, muted line — board card and backlog cell.
 * Archived components are dimmed rather than hidden, so an issue's classification
 * stays readable after the component is retired.
 */
export function ComponentName({ component, compact }: { component: ComponentRef; compact?: boolean }) {
  return (
    <span
      className="truncate inline-block max-w-full"
      title={component.archived ? `${component.name} (archived)` : component.name}
      style={{
        fontSize: compact ? 11 : 12,
        color: 'var(--color-text-muted)',
        opacity: component.archived ? 0.6 : 1,
      }}
    >
      {component.name}
    </span>
  )
}

/**
 * The board/backlog component filter. Server-side by design (HD-31 §3.6, same
 * rule as the label filter): a classification filter must search the whole
 * project, not just the issues the capped board already loaded — so the
 * selection becomes a `?componentId=` query param and part of the query key,
 * unlike the HD-43 client-side chips.
 *
 * Renders nothing while the project has no components at all (and none is
 * selected) — an empty "All components" select is pure noise in the filter bar.
 */
export function ComponentFilter({ wsId, projectId, value, onChange }: {
  wsId: string | undefined
  projectId: string | undefined
  value: string
  onChange: (id: string) => void
}) {
  const { data: components, isSuccess } = useProjectComponents(wsId, projectId)

  // A component archived or deleted since the selection was made would filter the
  // board down to nothing forever — drop the id once the list has loaded.
  useEffect(() => {
    if (!isSuccess || !components || !value) return
    if (!components.some(c => c.id === value)) onChange('')
  }, [isSuccess, components, value, onChange])

  const all = components ?? []
  if (all.length === 0 && !value) return null

  const active = !!value
  return (
    <Select
      compact
      aria-label="Filter by component"
      value={value}
      onChange={e => onChange(e.target.value)}
      style={active
        ? {
            background: 'color-mix(in srgb, var(--color-brand) 12%, white)',
            borderColor: 'var(--color-brand)',
            color: 'var(--color-brand-ink)',
          }
        : undefined}
    >
      <option value="">All components</option>
      {all.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
    </Select>
  )
}

/**
 * Single-select component picker for the create dialog and the issue-detail
 * details grid. Blank = "No component" (the caller turns that into
 * `clearComponent: true` on an update).
 *
 * `current` keeps the issue's CURRENT component selectable even when it has been
 * archived (and is therefore absent from the picker list) — the same handling as
 * an assignee who left the workspace. Attaching an archived component is a 422
 * server-side, so it is offered only when it is already the value.
 */
export function ComponentSelect({ label, ariaLabel, components, value, current, onChange, disabled }: {
  label?: string
  ariaLabel?: string
  components: Component[]
  value: string
  /** The issue's current component, when it isn't in `components` (archived). */
  current?: ComponentRef | null
  onChange: (id: string) => void
  disabled?: boolean
}) {
  const stale = current && !components.some(c => c.id === current.id) ? current : null
  return (
    <Select
      label={label}
      aria-label={ariaLabel}
      value={value}
      disabled={disabled}
      onChange={e => onChange(e.target.value)}
    >
      <option value="">No component</option>
      {components.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
      {stale && <option value={stale.id}>{stale.name} (archived)</option>}
    </Select>
  )
}
