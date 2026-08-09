import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { apiCreateIssue, apiGetProjectConfig, apiListIssues, apiListProjects, apiListWorkspaceMembers, apiListWorkspaces } from '../api'
import type { CreateIssuePreset } from '../uiStore'
import type { FieldValue } from '../types'
import { FieldInput } from './fields'
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

  const active = projects.filter(p => !p.archived)

  // A preset (create sub-task) pins the project to the parent's project.
  const [projectId, setProjectId] = useState(preset?.projectId ?? defaultProjectId ?? '')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [typeId, setTypeId] = useState('')
  const [statusId, setStatusId] = useState('')
  const [priorityId, setPriorityId] = useState('')
  const [parentId, setParentId] = useState(preset?.parentId ?? '')
  const [assigneeId, setAssigneeId] = useState('')
  const [dueDate, setDueDate] = useState('')
  const [fieldValues, setFieldValues] = useState<Record<string, FieldValue>>({})
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
    queryKey: ['issues', effectiveWsId, effectiveProjectId, 'board', ''],
    queryFn: () => apiListIssues(effectiveWsId, effectiveProjectId),
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
  const effectiveStatusId = statusId || statuses[0]?.id || ''
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
        fields: Object.keys(fieldValues).length > 0 ? fieldValues : undefined,
      })
      await qc.invalidateQueries({ queryKey: ['issues', effectiveWsId, effectiveProjectId] })
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
    <div style={overlayStyle} onClick={onClose}>
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
                No projects in this workspace yet.
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
