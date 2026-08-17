import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, X } from 'lucide-react'
import { apiCreateIssue, apiGetProjectConfig, apiListIssues, apiListProjects, apiListWorkspaceMembers, apiListWorkspaces } from '../api'
import type { CreateIssuePreset } from '../uiStore'
import type { BoardIssues, FieldValue } from '../types'
import { boardIssuesKey, projectIssuesKeyPrefix } from '../lib/queryKeys'
import { FieldInput } from './fields'
import { LabelPicker } from './labels'
import { ComponentSelect, useProjectComponents } from './projectComponents'
import { VersionPicker } from './versions'
import { SprintPicker } from './sprints'
import { deliveryOf } from '../hooks/useProjectDelivery'
import { permissionsFrom } from '../hooks/usePermissions'
import { Button, Input, Select, Textarea } from './ui'

interface Props {
  /** Absent when opened outside a workspace (/workspaces) — a Workspace select appears instead. */
  wsId?: string
  /** Pre-selected project — the one currently open, if any. */
  defaultProjectId?: string
  /** "Create sub-task" pre-fill: pins the project + parent and defaults a legal child type. */
  preset?: CreateIssuePreset
  onClose: () => void
}

export default function CreateIssueModal({ wsId, defaultProjectId, preset, onClose }: Props) {
  const qc = useQueryClient()

  const [wsSelection, setWsSelection] = useState('')

  const { data: workspaces = [] } = useQuery({
    queryKey: ['workspaces'],
    queryFn: apiListWorkspaces,
    enabled: !wsId,
  })

  // '' while the lists load; the effective value falls back to the first option
  const effectiveWsId = wsId ?? (wsSelection || workspaces[0]?.id || '')

  const { data: projects = [], isSuccess: projectsLoaded } = useQuery({
    queryKey: ['projects', effectiveWsId],
    queryFn: () => apiListProjects(effectiveWsId),
    enabled: !!effectiveWsId,
  })

  // HD-123 §14.3: the picker offers only projects that actually grant
  // `issue.create`. Each row carries its own permission set, so this is a pure
  // per-row question — and answering it HERE rather than at the rail's "New
  // issue" button is deliberate: the button stays a permanent affordance and
  // the reason is stated where the choice is made, instead of a control
  // disappearing from the shell for reasons a user cannot see.
  const active = projects.filter(p => !p.archived && permissionsFrom([p]).can('issue.create'))

  // A preset (create sub-task) pins the project to the parent's project.
  const [projectId, setProjectId] = useState(preset?.projectId ?? defaultProjectId ?? '')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [typeId, setTypeId] = useState('')
  // Board column quick-add pre-selects a status. It's only a default (the Status
  // select stays editable) and only valid for the preset's project — switching
  // project runs resetTaxonomySelections() and drops it, since statuses are
  // per-project taxonomy.
  const [statusId, setStatusId] = useState(preset?.projectId ? preset.statusId ?? '' : '')
  const [priorityId, setPriorityId] = useState('')
  const [parentId, setParentId] = useState(preset?.parentId ?? '')
  const [assigneeId, setAssigneeId] = useState('')
  const [dueDate, setDueDate] = useState('')
  const [fieldValues, setFieldValues] = useState<Record<string, FieldValue>>({})
  // Components are project-scoped (HD-31), so the selection is dropped whenever
  // the project changes — see resetTaxonomySelections().
  const [componentId, setComponentId] = useState('')
  // Versions are project-scoped too (HD-32) — dropped on a project change. Two
  // independent roles: "fix" ships the change, "affects" records where the defect
  // lives. Only the fix picker is on the short path; affects hides behind
  // "More fields" so the common create stays two selects long.
  const [fixVersionIds, setFixVersionIds] = useState<string[]>([])
  const [affectsVersionIds, setAffectsVersionIds] = useState<string[]>([])
  // Sprints are project-scoped too (HD-22) — dropped on a project change. Omitted
  // means the ranked backlog, where a new issue lands at the BOTTOM: filing an
  // issue is not a priority statement. Story points are a plain string draft so
  // "unestimated" (blank) stays representable.
  const [sprintId, setSprintId] = useState('')
  const [storyPoints, setStoryPoints] = useState('')
  const [moreOpen, setMoreOpen] = useState(false)
  // Labels are workspace-scoped (HD-30), so unlike the taxonomy selections they
  // survive a project change — only switching workspace clears them.
  const [labelIds, setLabelIds] = useState<string[]>([])
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  const effectiveProjectId = projectId || active[0]?.id || ''

  // Types, statuses and priorities depend on the selected project since M1
  // (its workflow / priority set) — not on the workspace
  const { data: config } = useQuery({
    queryKey: ['projectConfig', effectiveWsId, effectiveProjectId],
    queryFn: () => apiGetProjectConfig(effectiveWsId, effectiveProjectId),
    enabled: !!effectiveWsId && !!effectiveProjectId,
  })
  const issueTypes = config?.issueTypes ?? []
  const statuses = config?.statuses ?? []
  const priorities = config?.priorities ?? []
  const createFields = (config?.fields ?? []).filter(f => f.showOnCreate)

  // Project components (HD-31) — non-archived only; own endpoint/key, never part
  // of the project config. NOT a delivery capability (§5.3): a component is a
  // slice of one project's own work, not a way of working, so this control keeps
  // its "nothing curated ⇒ no empty select" rule.
  const { data: componentOptions = [] } = useProjectComponents(effectiveWsId, effectiveProjectId)

  // HD-102 §6: which of the path-specific inputs this form offers is answered by
  // the SELECTED project's declared capabilities — never by whether it happens to
  // have sprints or versions yet. Read straight off the project list this dialog
  // already fetched, so it costs no request and follows the project select
  // instantly. Nothing here is a value (a create form has none), so this is pure
  // control gating; Rule B has nothing to preserve.
  const delivery = deliveryOf(projects.find(p => p.id === effectiveProjectId))
  const iterations = delivery.board === 'SCRUM'

  // Option list for the Assignee picker and for USER-type custom fields
  const { data: members = [] } = useQuery({
    queryKey: ['wsMembers', effectiveWsId],
    queryFn: () => apiListWorkspaceMembers(effectiveWsId),
    enabled: !!effectiveWsId,
  })

  // Strict adjacency (Revision 2): a parent→child edge is legal iff the parent's
  // type level is EXACTLY one above the child's (parentLevel - childLevel == 1).
  // adjChild(parentLevel) = offered types exactly one level below the parent.
  const adjChildTypes = (parentLevel: number) =>
    issueTypes.filter(t => t.hierarchyLevel === parentLevel - 1)

  // A parent may be fixed by the preset ("create sub-task") — then the project is
  // pinned and the Type picker is constrained to adjChild(parentLevel).
  const presetParentLevel = preset?.parentId !== undefined ? preset?.parentLevel : undefined

  // Preset default type: the first (by position) type in adjChild(parentLevel).
  const presetDefaultTypeId = useMemo(() => {
    if (presetParentLevel === undefined || issueTypes.length === 0) return ''
    const below = adjChildTypes(presetParentLevel)
    if (below.length === 0) return ''
    return below.reduce((best, t) => (t.position < best.position ? t : best)).id
  }, [presetParentLevel, issueTypes])

  // Candidate parents: all issues in the project (filtered per adjacency below).
  // Loaded whenever the project offers a parent-capable relationship at all.
  const anyParentableEdge = useMemo(
    () => issueTypes.some(t => issueTypes.some(p => p.hierarchyLevel === t.hierarchyLevel + 1)),
    [issueTypes],
  )
  const { data: projectIssues = [] } = useQuery({
    // Same cache entry as the board's unfiltered list (`boardIssuesKey`) — so the
    // dialog reuses whatever the board already fetched. HD-79: the endpoint
    // returns a capped wrapper; the cached value under a board key is ALWAYS that
    // wrapper and the parent picker projects the array out with `select` rather
    // than caching a different shape under the same key (a mismatch here used to
    // crash the SPA with "projectIssues.filter is not a function" whenever the
    // dialog was opened over a board).
    queryKey: boardIssuesKey(effectiveWsId, effectiveProjectId),
    queryFn: () => apiListIssues(effectiveWsId, effectiveProjectId),
    select: (board: BoardIssues) => board.issues,
    enabled: !!effectiveWsId && !!effectiveProjectId && (anyParentableEdge || preset?.parentId !== undefined),
  })

  // The currently-selected parent's type level (from the preset or the picker).
  const selectedParentLevel = useMemo(() => {
    if (presetParentLevel !== undefined) return presetParentLevel
    if (!parentId) return undefined
    const parent = projectIssues.find(i => i.id === parentId)
    return parent ? issueTypes.find(t => t.id === parent.type.id)?.hierarchyLevel : undefined
  }, [presetParentLevel, parentId, projectIssues, issueTypes])

  // F1 — Type options: when a parent is fixed/selected, restrict to
  // adjChild(parentLevel) (illegal types are NOT rendered). Otherwise the full set.
  const typeOptions = useMemo(() => {
    if (selectedParentLevel === undefined) return issueTypes
    return adjChildTypes(selectedParentLevel)
  }, [issueTypes, selectedParentLevel])

  // F1 — if the current typeId is not in the restricted set, fall back to its first option.
  const effectiveTypeId =
    (typeId && typeOptions.some(t => t.id === typeId) ? typeId : '')
    || (presetParentLevel !== undefined ? presetDefaultTypeId : '')
    || typeOptions[0]?.id || ''
  // Guard against a status that isn't offered by the selected project's workflow
  // (e.g. a stale preset) — fall back to the workflow's first column.
  const effectiveStatusId =
    (statusId && statuses.some(s => s.id === statusId) ? statusId : '')
    || statuses[0]?.id || ''
  const effectivePriorityId = priorityId
    || priorities.find(p => p.isDefault)?.id || priorities[0]?.id || ''

  // F2 — Parent eligibility is strict adjacency: a parent's type level must be
  // EXACTLY one above the selected type's level (config-driven, no hardcoded names).
  const selectedTypeLevel = issueTypes.find(t => t.id === effectiveTypeId)?.hierarchyLevel
  const eligibleParentTypeIds = useMemo(() => {
    if (selectedTypeLevel === undefined) return new Set<string>()
    return new Set(issueTypes.filter(t => t.hierarchyLevel === selectedTypeLevel + 1).map(t => t.id))
  }, [issueTypes, selectedTypeLevel])

  // A parent picker only makes sense when a type exactly one level above exists.
  // The preset always shows a (fixed) parent.
  const canHaveParent = eligibleParentTypeIds.size > 0 || preset?.parentId !== undefined

  const eligibleParents = projectIssues.filter(i => eligibleParentTypeIds.has(i.type.id))

  // If the selected type changed such that the chosen parent is no longer adjacent, drop it.
  const parentStillLegal = !parentId || eligibleParents.some(i => i.id === parentId)
  const effectiveParentId = preset?.parentId ?? (parentStillLegal ? parentId : '')

  function handleWorkspaceChange(id: string) {
    setWsSelection(id)
    setProjectId('')
    resetTaxonomySelections()
    setLabelIds([])   // labels belong to the workspace we just left
  }

  function handleProjectChange(id: string) {
    setProjectId(id)
    // Taxonomy is per-project — selections don't carry over
    resetTaxonomySelections()
  }

  function resetTaxonomySelections() {
    setTypeId('')
    setStatusId('')
    setPriorityId('')
    setParentId('')
    setAssigneeId('')  // members are workspace-scoped
    setComponentId('') // components belong to the project we just left
    setFixVersionIds([])     // …and so do versions (HD-32)
    setAffectsVersionIds([])
    setSprintId('')          // …and sprints (HD-22)
    setStoryPoints('')
    setFieldValues({})
  }

  function handleTypeChange(id: string) {
    setTypeId(id)
    // F4 — changing the Type so the current parent is no longer adjacent
    // (parentLevel != newLevel + 1) clears the parent. Preset parents are fixed.
    const newLevel = issueTypes.find(t => t.id === id)?.hierarchyLevel
    if (parentId && preset?.parentId === undefined && newLevel !== undefined) {
      const parent = projectIssues.find(i => i.id === parentId)
      const parentLevel = parent && issueTypes.find(t => t.id === parent.type.id)?.hierarchyLevel
      if (parentLevel === undefined || parentLevel !== newLevel + 1) setParentId('')
    }
  }

  function handleParentChange(id: string) {
    setParentId(id)
    // F4 — changing the Parent so the current type is no longer adjacent resets
    // the Type to the first option of adjChild(newParentLevel) (F1 re-derives the
    // options; clearing typeId lets the effective type fall back to that first option).
    if (!id) return
    const parent = projectIssues.find(i => i.id === id)
    const parentLevel = parent && issueTypes.find(t => t.id === parent.type.id)?.hierarchyLevel
    const curLevel = typeId ? issueTypes.find(t => t.id === typeId)?.hierarchyLevel : undefined
    if (parentLevel === undefined || curLevel === undefined || parentLevel !== curLevel + 1) {
      setTypeId('')
    }
  }

  function setFieldValue(fieldId: string, value: FieldValue | undefined) {
    setFieldValues(prev => {
      const next = { ...prev }
      if (value === undefined) delete next[fieldId]
      else next[fieldId] = value
      return next
    })
  }

  const missingRequired = createFields.some(f => f.required && fieldValues[f.id] === undefined)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    // Re-entry guard: ignore a second submit while a create is already in flight
    // (belt-and-suspenders against a double-click double-creating an issue).
    if (saving) return
    setError('')
    setSaving(true)
    try {
      await apiCreateIssue(effectiveWsId, effectiveProjectId, {
        title: title.trim(),
        typeId: effectiveTypeId,
        statusId: effectiveStatusId,
        priorityId: effectivePriorityId || undefined,
        description: description.trim() || undefined,
        assigneeId: assigneeId || undefined,
        dueDate: dueDate || undefined,
        parentId: effectiveParentId || undefined,
        labelIds: labelIds.length > 0 ? labelIds : undefined,
        componentId: componentId || undefined,
        fixVersionIds: fixVersionIds.length > 0 ? fixVersionIds : undefined,
        affectsVersionIds: affectsVersionIds.length > 0 ? affectsVersionIds : undefined,
        sprintId: sprintId || undefined,
        // Blank stays UNESTIMATED — never coerced to 0.
        storyPoints: storyPoints.trim() && Number.isFinite(Number(storyPoints))
          ? Number(storyPoints) : undefined,
        fields: Object.keys(fieldValues).length > 0 ? fieldValues : undefined,
      })
      await qc.invalidateQueries({ queryKey: projectIssuesKeyPrefix(effectiveWsId, effectiveProjectId) })
      onClose()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to create issue')
    } finally {
      setSaving(false)
    }
  }

  const overlayStyle: React.CSSProperties = {
    position: 'fixed', inset: 0, zIndex: 50,
    background: 'rgba(28,27,25,0.55)',
    // Padding keeps the panel off the screen edges; the panel's max-height:100%
    // then resolves against this padded area, so it never overflows off-screen
    display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 12,
    backdropFilter: 'blur(2px)',
  }

  const panelStyle: React.CSSProperties = {
    background: 'white',
    borderRadius: 'var(--radius-lg)',
    border: '1px solid var(--color-border)',
    width: 480,
    // Cap to the (padded) viewport so short screens scroll the body instead of
    // pushing the form off-screen; header and footer stay pinned
    maxWidth: '100%',
    maxHeight: '100%',
    display: 'flex',
    flexDirection: 'column',
    boxShadow: '0 20px 60px rgba(0,0,0,0.18)',
  }

  return (
    <div data-modal-open="true" style={overlayStyle} onClick={onClose}>
      <div style={panelStyle} onClick={e => e.stopPropagation()}>
        <div
          className="flex items-center justify-between px-5 py-4 border-b flex-shrink-0"
          style={{ borderColor: 'var(--color-border)' }}
        >
          <span className="font-semibold text-sm">New Issue</span>
          <button onClick={onClose} className="cursor-pointer hover:opacity-60 transition-opacity">
            <X size={16} style={{ color: 'var(--color-text-muted)' }} />
          </button>
        </div>

        <form onSubmit={submit} className="flex flex-col min-h-0 flex-1">
          <div className="flex-1 min-h-0 overflow-y-auto p-5 flex flex-col gap-4">
          {!wsId && (
            <Select label="Workspace" value={effectiveWsId} onChange={e => handleWorkspaceChange(e.target.value)}>
              {workspaces.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
            </Select>
          )}

          <div>
            <Select label="Project" value={effectiveProjectId} onChange={e => handleProjectChange(e.target.value)}>
              {active.map(p => <option key={p.id} value={p.id}>{p.key} — {p.name}</option>)}
            </Select>
            {projectsLoaded && active.length === 0 && (
              <p className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                {projects.some(p => !p.archived)
                  ? 'You don’t have permission to create issues in any project in this workspace.'
                  : 'No projects in this workspace yet.'}
              </p>
            )}
          </div>

          <Input
            label="Title"
            value={title}
            onChange={e => setTitle(e.target.value)}
            placeholder="Issue title"
            autoFocus
            required
          />

          <div className="grid grid-cols-3 gap-3">
            <Select label="Type" value={effectiveTypeId} onChange={e => handleTypeChange(e.target.value)}>
              {typeOptions.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
            </Select>
            <Select label="Status" value={effectiveStatusId} onChange={e => setStatusId(e.target.value)}>
              {statuses.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
            </Select>
            <Select label="Priority" value={effectivePriorityId} onChange={e => setPriorityId(e.target.value)}>
              {priorities.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
            </Select>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <Select label="Assignee" value={assigneeId} onChange={e => setAssigneeId(e.target.value)}>
              <option value="">Unassigned</option>
              {members.map(m => <option key={m.userId} value={m.userId}>{m.displayName}</option>)}
            </Select>
            <Input label="Due date" type="date" value={dueDate} onChange={e => setDueDate(e.target.value)} />
          </div>

          {/* Component (HD-31) — one per issue, project-scoped. Hidden entirely
              when the project curates none, so the form doesn't grow an empty
              control. A component with auto-assign on fills the assignee with
              its lead server-side, but only when none was picked here. */}
          {componentOptions.length > 0 && (
            <div className="flex flex-col gap-1">
              <ComponentSelect
                label="Component"
                components={componentOptions}
                value={componentId}
                onChange={setComponentId}
              />
              {!assigneeId && (() => {
                const c = componentOptions.find(o => o.id === componentId)
                return c?.autoAssign && c.leadName ? (
                  <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    Will be assigned to {c.leadName} (component lead).
                  </span>
                ) : null
              })()}
            </div>
          )}

          {/* Sprint + story points (HD-22 / HD-102 §6). The Sprint select rides
              the `board = SCRUM` capability and the points input the `estimation`
              one — each hidden when its capability is off, so a Kanban project's
              form carries no Scrum vocabulary and no permanently-empty number
              field. They share a row when both are on. Leaving Sprint blank files
              the issue at the BOTTOM of the ranked backlog; leaving points blank
              means "unestimated", which is deliberately not 0. */}
          {(iterations || delivery.estimation) && (
            <div className={iterations && delivery.estimation ? 'grid grid-cols-2 gap-3' : undefined}>
              {iterations && (
                <SprintPicker
                  label="Sprint"
                  wsId={effectiveWsId}
                  projectId={effectiveProjectId}
                  value={sprintId}
                  onChange={setSprintId}
                  emptyLabel="Backlog"
                />
              )}
              {delivery.estimation && (
                <Input
                  label="Story points"
                  type="number"
                  min={0}
                  max={999}
                  step={0.5}
                  placeholder="Unestimated"
                  value={storyPoints}
                  onChange={e => setStoryPoints(e.target.value)}
                />
              )}
            </div>
          )}

          {/* Parent picker — only for types with a tier exactly one level above.
              When opened as "create sub-task" the parent is fixed (disabled). */}
          {canHaveParent && (
            <Select
              label="Parent"
              value={effectiveParentId}
              onChange={e => handleParentChange(e.target.value)}
              disabled={preset?.parentId !== undefined}
            >
              {preset?.parentId !== undefined ? (
                // Fixed preset parent — the only option; the current issue list may
                // not contain it, so render a self-standing option from the list.
                (() => {
                  const p = projectIssues.find(i => i.id === preset.parentId)
                  return <option value={preset.parentId}>{p ? `${p.key} — ${p.title}` : 'Parent'}</option>
                })()
              ) : (
                <>
                  <option value="">No parent</option>
                  {eligibleParents.map(i => (
                    <option key={i.id} value={i.id}>{i.key} — {i.title}</option>
                  ))}
                </>
              )}
            </Select>
          )}

          <Textarea
            label="Description"
            value={description}
            onChange={e => setDescription(e.target.value)}
            placeholder="Add a description…"
            rows={4}
          />

          {createFields.map(f => (
            <FieldInput key={f.id} field={f} value={fieldValues[f.id]}
                        onChange={v => setFieldValue(f.id, v)} members={members} />
          ))}

          {/* Fix version(s) (HD-32) — on the short path: "this ships in 2.4.0"
              is a normal thing to say while filing. Gated on the declared
              `releases` capability (HD-102 §6), NOT on "does this project have
              versions yet?" — a releases project that hasn't curated any yet
              still shows the picker, whose empty state points at the Releases
              page. */}
          {delivery.releases && effectiveWsId && effectiveProjectId && (
            <VersionPicker
              label="Fix version(s)"
              wsId={effectiveWsId}
              projectId={effectiveProjectId}
              value={fixVersionIds}
              onChange={setFixVersionIds}
              placeholder="Where will this ship?"
            />
          )}

          {/* Labels (HD-30) — below the custom fields; workspace-scoped, and any
              member may create one on the fly from here. */}
          {effectiveWsId && (
            <div className="flex flex-col gap-1">
              <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Labels</span>
              <LabelPicker wsId={effectiveWsId} value={labelIds} onChange={setLabelIds} />
            </div>
          )}

          {/* "More fields" (HD-32) — the rarely-needed classification lives here
              so the create path stays short. Affects versions is a triage detail
              ("this defect exists in 2.3.1"), not something most filings need.
              Same `releases` gate as the fix picker above. */}
          {delivery.releases && effectiveWsId && effectiveProjectId && (
            <div className="flex flex-col gap-2">
              <button
                type="button"
                onClick={() => setMoreOpen(o => !o)}
                aria-expanded={moreOpen}
                className="inline-flex items-center gap-1 text-xs cursor-pointer self-start hover:underline"
                style={{ color: 'var(--color-text-secondary)' }}
              >
                {moreOpen ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                More fields
                {!moreOpen && affectsVersionIds.length > 0 && (
                  <span className="mono" style={{ color: 'var(--color-brand)' }}>
                    ({affectsVersionIds.length})
                  </span>
                )}
              </button>
              {moreOpen && (
                <VersionPicker
                  label="Affects version(s)"
                  wsId={effectiveWsId}
                  projectId={effectiveProjectId}
                  value={affectsVersionIds}
                  onChange={setAffectsVersionIds}
                  placeholder="Where does the defect exist?"
                />
              )}
            </div>
          )}

          {error && (
            <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>
          )}
          </div>

          <div className="flex justify-end gap-2 px-5 py-4 border-t flex-shrink-0"
               style={{ borderColor: 'var(--color-border)' }}>
            <Button variant="ghost" type="button" onClick={onClose}>Cancel</Button>
            <Button
              variant="primary"
              type="submit"
              loading={saving}
              disabled={!title.trim() || !effectiveProjectId || !effectiveTypeId || !effectiveStatusId || missingRequired}
            >
              Create issue
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
