import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { X, Trash2, Paperclip, Plus, CornerDownRight, MoreHorizontal } from 'lucide-react'
import {
  ApiResponseError,
  apiGetIssue, apiUpdateIssue, apiDeleteIssue, apiListIssues,
  apiListComments, apiCreateComment, apiDeleteComment,
  apiListAttachments, apiUploadAttachment, apiDownloadAttachment, apiDeleteAttachment,
  apiGetIssueHistory, apiListWorkspaceMembers, apiGetIssueChildren,
} from '../api'
import { useAuthStore } from '../auth'
import { useUiStore } from '../uiStore'
import { Button, Input, Select, Textarea, StatusBadge, PriorityBadge, Avatar, ChildrenProgress } from '../components/ui'
import { FieldInput, FieldValueDisplay } from '../components/fields'
import { Markdown, MarkdownToolbar } from '../components/markdown'
import { isMoveAllowed } from '../lib/transitions'
import type {
  Issue, IssueType, Status, PriorityOption, ProjectField, FieldValue, FieldType, TransitionRule,
  Comment, Attachment, IssueHistoryEntry, WorkspaceMember,
} from '../types'

function fieldValuesOf(issue: Issue): Record<string, FieldValue> {
  return Object.fromEntries(issue.fields.map(f => [f.fieldId, f.value]))
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

// A parent key is "<projectKey>-<number>"; the trailing segment is the number.
function numberFromKey(key: string): number | undefined {
  const n = Number(key.slice(key.lastIndexOf('-') + 1))
  return Number.isFinite(n) ? n : undefined
}

/**
 * The single source of truth for Activity timestamps (HD-69) — comment rows and
 * history rows must never drift apart again. `label` is the compact, locale-aware
 * date + hh:mm (no seconds) shown in the feed; `title` is the full precise value
 * surfaced on hover.
 */
function formatActivityTime(iso: string): { label: string; title: string } {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return { label: iso, title: iso }
  return {
    label: d.toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' }),
    title: d.toLocaleString(undefined, { dateStyle: 'full', timeStyle: 'long' }),
  }
}

/**
 * Right-aligned timestamp cell for an activity row. `ml-auto` pushes it to the
 * row's right edge and `flex-shrink-0` keeps it whole while the text before it
 * truncates (the drawer can be as narrow as 360px).
 */
function ActivityTime({ at }: { at: string }) {
  const { label, title } = formatActivityTime(at)
  return (
    <span
      className="mono text-xs ml-auto flex-shrink-0 whitespace-nowrap"
      style={{ color: 'var(--color-text-muted)' }}
      title={title}
    >
      {label}
    </span>
  )
}

/** Width reserved on every activity row for the hover delete button, so the
 *  timestamps of comment and history rows land on the same right edge. */
const ACTIVITY_ACTION_W = 13

function historyLabel(field: string) {
  const labels: Record<string, string> = {
    title: 'Title', description: 'Description', status: 'Status',
    priority: 'Priority', type: 'Type', assignee: 'Assignee', dueDate: 'Due date',
    parent: 'Parent',
  }
  return labels[field] ?? field
}

// Small uppercase section heading — the future jump-nav anchors (HD-65) target these.
function SectionHeading({ children }: { children: React.ReactNode }) {
  return (
    <span className="text-xs font-semibold uppercase tracking-wider" style={{ color: 'var(--color-text-secondary)' }}>
      {children}
    </span>
  )
}

export interface IssueDetailProps {
  wsId: string
  projectId: string
  issueNumber: number
  issueTypes: IssueType[]
  statuses: Status[]             // workflow statuses (board-column order)
  transitions: TransitionRule[]  // gate the inline status editor to legal moves
  priorities: PriorityOption[]   // the project's offered priorities (from config)
  fields: ProjectField[]         // the project's custom fields (from config)
  /** Drawer supplies a close handler; the full-page view (HD-67) omits it. */
  onClose?: () => void
  /** Open a different issue in the same surface (parent/child navigation). */
  onOpenIssue?: (number: number) => void
  /** Drawer shows an "open full page" button; the page itself omits it (HD-67). */
  enableExpand?: boolean
}

/**
 * The issue detail body: a single continuous scroll (no tabs, no separate edit
 * mode — HD-60) shared by the board/backlog drawer (`IssueSidePanel`) and, later,
 * a full-page route (HD-67). Owns data loading and the per-field save pipeline;
 * the drawer chrome (width, resize handle) lives in the wrapper.
 */
export default function IssueDetail({
  wsId, projectId, issueNumber, issueTypes, statuses, transitions, priorities, fields, onClose, onOpenIssue, enableExpand,
}: IssueDetailProps) {
  const qc = useQueryClient()
  const { user } = useAuthStore()
  const openCreateIssue = useUiStore(s => s.openCreateIssue)

  const [issue, setIssue] = useState<Issue | null>(null)
  const [children, setChildren] = useState<Issue[]>([])
  const [comments, setComments] = useState<Comment[]>([])
  const [attachments, setAttachments] = useState<Attachment[]>([])
  const [history, setHistory] = useState<IssueHistoryEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  // Optimistic-lock: a 409 means someone else saved first — we reload (server
  // wins) and show a non-destructive notice rather than clobbering their change.
  const [conflict, setConflict] = useState(false)
  // Inline metadata edit error (e.g. a type change that strands the parent).
  const [metaError, setMetaError] = useState('')

  // Header overflow (⋯) menu
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  // Responsive: the metadata grid drops to one column in a narrow drawer.
  const rootRef = useRef<HTMLDivElement>(null)
  const [narrow, setNarrow] = useState(false)

  // Candidate parents for the inline Parent editor — loaded lazily on first use.
  const [candidateParents, setCandidateParents] = useState<Issue[]>([])
  const parentsLoaded = useRef(false)

  // Inline description edit (HD-62) + Markdown Write/Preview (HD-66)
  const [descEditing, setDescEditing] = useState(false)
  const [descDraft, setDescDraft] = useState('')
  const [descPreview, setDescPreview] = useState(false)
  const descRef = useRef<HTMLTextAreaElement>(null)

  // Inline custom-field edit (HD-62): one field at a time, with a working draft.
  const [editingFieldId, setEditingFieldId] = useState<string | null>(null)
  const [fieldDraft, setFieldDraft] = useState<FieldValue | undefined>(undefined)
  const fieldEditorRef = useRef<HTMLDivElement>(null)
  const [addFieldOpen, setAddFieldOpen] = useState(false)
  const addMenuRef = useRef<HTMLDivElement>(null)

  // Inline title editing (HD-60 — the first inline field; the pattern the rest follow)
  const [titleEditing, setTitleEditing] = useState(false)
  const [titleDraft, setTitleDraft] = useState('')
  const titleRef = useRef<HTMLTextAreaElement>(null)

  // Comment composer + mention autocomplete
  const [commentBody, setCommentBody] = useState('')
  const [postingComment, setPostingComment] = useState(false)
  const [members, setMembers] = useState<WorkspaceMember[]>([])
  const [mentionQuery, setMentionQuery] = useState<string | null>(null)
  const [mentionStart, setMentionStart] = useState(0)
  const commentRef = useRef<HTMLTextAreaElement>(null)

  // Merged activity feed (comments + history) — newest first (HD-64)
  // Defaults to Comments (HD-69) — the discussion is what people open an issue for.
  const [activityFilter, setActivityFilter] = useState<'all' | 'comments' | 'history'>('comments')
  const [commentPreview, setCommentPreview] = useState(false)
  const activityHeadingRef = useRef<HTMLDivElement>(null)

  // Jump navigation for tall issues (HD-65)
  const scrollRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const descSectionRef = useRef<HTMLDivElement>(null)
  const subSectionRef = useRef<HTMLDivElement>(null)
  const filesSectionRef = useRef<HTMLDivElement>(null)
  const activitySectionRef = useRef<HTMLDivElement>(null)
  const [showNav, setShowNav] = useState(false)
  const [activeSection, setActiveSection] = useState('description')

  // Attachments
  const [uploading, setUploading] = useState(false)
  const [fileError, setFileError] = useState('')
  const [dragOver, setDragOver] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    apiListWorkspaceMembers(wsId).then(setMembers).catch(() => {})
  }, [wsId])

  // Watch the panel width so the metadata grid can collapse to one column.
  // Re-attach once content renders (the root element only exists past loading).
  useEffect(() => {
    const el = rootRef.current
    if (!el) return
    const ro = new ResizeObserver(entries => {
      for (const e of entries) setNarrow(e.contentRect.width < 380)
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [loading])

  const loadIssue = useCallback(async () => {
    setLoading(true)
    try {
      const [iss, cmts, atts, hist, kids] = await Promise.all([
        apiGetIssue(wsId, projectId, issueNumber),
        apiListComments(wsId, projectId, issueNumber, { size: 100 }),
        apiListAttachments(wsId, projectId, issueNumber),
        apiGetIssueHistory(wsId, projectId, issueNumber, { size: 100 }),
        apiGetIssueChildren(wsId, projectId, issueNumber),
      ])
      setIssue(iss)
      setComments(cmts.content)
      setAttachments(atts)
      setHistory(hist.content)
      setChildren(kids)
    } finally {
      setLoading(false)
    }
  }, [wsId, projectId, issueNumber])

  useEffect(() => { loadIssue() }, [loadIssue])

  // Close the overflow menu on outside click.
  useEffect(() => {
    if (!menuOpen) return
    function onDoc(e: MouseEvent) {
      if (!menuRef.current?.contains(e.target as Node)) setMenuOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [menuOpen])

  // ── Per-field save (HD-60) ──────────────────────────────────────────────────
  // Every inline editor commits its own diffed PATCH through here, always with
  // the current optimistic-lock `version`. Reused by the metadata/description/
  // field editors added in HD-61/HD-62. Returns the updated issue, or undefined
  // on a handled 409 (reloaded); rethrows other errors so callers can react.
  const savePatch = useCallback(async (
    patch: Parameters<typeof apiUpdateIssue>[3],
  ): Promise<Issue | undefined> => {
    if (!issue) return undefined
    setError('')
    try {
      const updated = await apiUpdateIssue(wsId, projectId, issueNumber, { ...patch, version: issue.version })
      setIssue(updated)
      setConflict(false)
      // Any field change writes a history entry; keep the feed fresh.
      apiGetIssueHistory(wsId, projectId, issueNumber, { size: 100 })
        .then(h => setHistory(h.content)).catch(() => {})
      await qc.invalidateQueries({ queryKey: ['issues', wsId, projectId] })
      await qc.invalidateQueries({ queryKey: ['issue', wsId, projectId] })
      return updated
    } catch (err: unknown) {
      if (err instanceof ApiResponseError && err.status === 409) {
        setConflict(true)
        await loadIssue()   // server wins — discard the local edit
        return undefined
      }
      setError(err instanceof Error ? err.message : 'Failed to save')
      throw err
    }
  }, [issue, wsId, projectId, issueNumber, qc, loadIssue])

  // ── Inline title ──
  function startTitleEdit() {
    if (!issue) return
    setTitleDraft(issue.title)
    setTitleEditing(true)
  }
  useEffect(() => {
    if (titleEditing) {
      const el = titleRef.current
      if (el) {
        el.style.height = 'auto'
        el.style.height = `${el.scrollHeight}px`
        el.focus()
        el.select()
      }
    }
  }, [titleEditing])
  async function commitTitle() {
    setTitleEditing(false)
    const next = titleDraft.trim()
    if (!issue || !next || next === issue.title) return  // empty rejected / unchanged
    await savePatch({ title: next }).catch(() => {})
  }

  // ── Delete ──
  async function handleDelete() {
    if (!issue) return
    setMenuOpen(false)
    if (!window.confirm(`Delete ${issue.key}? This cannot be undone.`)) return
    await apiDeleteIssue(wsId, projectId, issueNumber)
    await qc.invalidateQueries({ queryKey: ['issues', wsId, projectId] })
    onClose?.()
  }

  // ── Comments ──
  const filteredMembers = mentionQuery !== null
    ? members.filter(m => m.displayName.toLowerCase().startsWith(mentionQuery.toLowerCase())).slice(0, 6)
    : []

  function handleCommentChange(e: React.ChangeEvent<HTMLTextAreaElement>) {
    const val = e.target.value
    setCommentBody(val)
    const cursor = e.target.selectionStart ?? val.length
    const slice = val.slice(0, cursor)
    const atIdx = slice.lastIndexOf('@')
    if (atIdx !== -1 && (atIdx === 0 || /\s/.test(slice[atIdx - 1]))) {
      const query = slice.slice(atIdx + 1)
      if (!/\s/.test(query)) {
        setMentionQuery(query)
        setMentionStart(atIdx)
        return
      }
    }
    setMentionQuery(null)
  }

  function insertMention(member: WorkspaceMember) {
    const before = commentBody.slice(0, mentionStart)
    const after = commentBody.slice(commentRef.current?.selectionStart ?? commentBody.length)
    setCommentBody(before + '@' + member.displayName + ' ' + after)
    setMentionQuery(null)
    commentRef.current?.focus()
  }

  async function handlePostComment() {
    if (!commentBody.trim()) return
    setPostingComment(true)
    try {
      const c = await apiCreateComment(wsId, projectId, issueNumber, commentBody.trim())
      setComments(prev => [c, ...prev])   // newest-first feed
      setCommentBody('')
      setCommentPreview(false)
      // Show the just-posted comment (it lands at the top of the feed).
      activityHeadingRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    } finally {
      setPostingComment(false)
    }
  }

  async function handleDeleteComment(commentId: string) {
    await apiDeleteComment(wsId, projectId, issueNumber, commentId)
    setComments(prev => prev.filter(c => c.id !== commentId))
  }

  // ── Attachments ──
  // Upload one or many files sequentially; each is appended as it lands, and
  // per-file failures are collected so one bad file doesn't sink the rest.
  async function uploadFiles(files: File[]) {
    if (files.length === 0) return
    setFileError('')
    setUploading(true)
    const errors: string[] = []
    for (const file of files) {
      try {
        const att = await apiUploadAttachment(wsId, projectId, issueNumber, file)
        setAttachments(prev => [...prev, att])
      } catch (err: unknown) {
        errors.push(`${file.name}: ${err instanceof Error ? err.message : 'upload failed'}`)
      }
    }
    setFileError(errors.join(' · '))
    setUploading(false)
  }

  async function handleUploadFile(e: React.ChangeEvent<HTMLInputElement>) {
    const files = e.target.files ? Array.from(e.target.files) : []
    e.target.value = '' // allow re-selecting the same file
    await uploadFiles(files)
  }

  // Whole-panel drag-and-drop — only reacts to OS file drags (not issue-card DnD).
  function isFileDrag(e: React.DragEvent) {
    return e.dataTransfer.types.includes('Files')
  }
  function handleDragOver(e: React.DragEvent) {
    if (!isFileDrag(e)) return
    e.preventDefault()
    e.dataTransfer.dropEffect = 'copy'
    if (!dragOver) setDragOver(true)
  }
  function handleDragLeave(e: React.DragEvent) {
    if (!e.currentTarget.contains(e.relatedTarget as Node)) setDragOver(false)
  }
  function handleDropFiles(e: React.DragEvent) {
    if (!isFileDrag(e)) return
    e.preventDefault()
    setDragOver(false)
    uploadFiles(Array.from(e.dataTransfer.files))
  }

  async function handleDownloadAttachment(att: Attachment) {
    try {
      await apiDownloadAttachment(wsId, projectId, issueNumber, att)
    } catch {
      setFileError('Download failed')
    }
  }

  async function handleDeleteAttachment(attachmentId: string) {
    await apiDeleteAttachment(wsId, projectId, issueNumber, attachmentId)
    setAttachments(prev => prev.filter(a => a.id !== attachmentId))
  }

  // An issue "can have children" iff the project's type set offers a type
  // exactly one level below it.
  const issueLevel = issue ? issue.type.hierarchyLevel : undefined
  const canHaveChildren = issueLevel !== undefined
    && issueTypes.some(t => t.hierarchyLevel === issueLevel - 1)

  const fieldValues = issue ? fieldValuesOf(issue) : {}

  // ── Inline metadata editing (HD-61) ─────────────────────────────────────────
  // Each cell commits its own field through the shared savePatch (version + 409).
  const commitField = useCallback((patch: Parameters<typeof apiUpdateIssue>[3]) => {
    savePatch(patch).catch(() => {})
  }, [savePatch])

  // Parent adjacency (strict, config-driven): a legal parent is EXACTLY one
  // hierarchy level above this issue's type.
  const typeLevel = issue?.type.hierarchyLevel
  const eligibleParentTypeIds = useMemo(() => {
    if (typeLevel === undefined) return new Set<string>()
    return new Set(issueTypes.filter(t => t.hierarchyLevel === typeLevel + 1).map(t => t.id))
  }, [issueTypes, typeLevel])
  const canHaveParent = eligibleParentTypeIds.size > 0
  const eligibleParents = candidateParents.filter(i => i.id !== issue?.id && eligibleParentTypeIds.has(i.type.id))

  // Load candidate parents once, when the Parent editor is first opened.
  function ensureParents() {
    if (parentsLoaded.current || !canHaveParent) return
    parentsLoaded.current = true
    // HD-79: board list endpoint returns a capped wrapper — take the issue array.
    apiListIssues(wsId, projectId).then(r => setCandidateParents(r.issues)).catch(() => {})
  }

  // Changing type can strand the current parent (adjacency breaks) — mirror the
  // backend 422 client-side and block the change with an inline message.
  function changeType(nextTypeId: string) {
    if (!issue || nextTypeId === issue.type.id) return
    const nextLevel = issueTypes.find(t => t.id === nextTypeId)?.hierarchyLevel
    if (issue.parentId && issue.parentTypeId) {
      const parentLevel = issueTypes.find(t => t.id === issue.parentTypeId)?.hierarchyLevel
      if (parentLevel === undefined || nextLevel === undefined || parentLevel !== nextLevel + 1) {
        setMetaError("This type can't be a child of the current parent — detach the parent first, or pick a type one level below it.")
        return
      }
    }
    setMetaError('')
    commitField({ typeId: nextTypeId })
  }

  const gridCols = narrow ? 'grid-cols-1' : 'grid-cols-2'

  // ── Inline description (HD-62) ──
  function startDescEdit() {
    setDescDraft(issue?.description ?? '')
    setDescPreview(false)
    setDescEditing(true)
  }
  useEffect(() => {
    if (descEditing && !descPreview) {
      const el = descRef.current
      if (el) { el.style.height = 'auto'; el.style.height = `${el.scrollHeight}px`; el.focus() }
    }
  }, [descEditing, descPreview])
  function cancelDescEdit() {
    setDescEditing(false)
    setDescPreview(false)
  }
  async function commitDesc() {
    setDescEditing(false)
    setDescPreview(false)
    if (!issue || descDraft === (issue.description ?? '')) return  // unchanged (empty string clears)
    await savePatch({ description: descDraft }).catch(() => {})
  }

  // ── Inline custom fields (HD-62) ──
  // Discrete types commit on pick (a single choice = done); text-like and
  // multi-select edit a draft committed on blur/Enter/Save. This split avoids the
  // custom Select popover's blur firing before the choice registers.
  const isDiscreteField = (t: FieldType) => t === 'SELECT' || t === 'USER' || t === 'DATE' || t === 'CHECKBOX'

  function startEditField(f: ProjectField) {
    setAddFieldOpen(false)
    setFieldDraft(fieldValues[f.id])
    setEditingFieldId(f.id)
  }
  function cancelEditField() {
    setEditingFieldId(null)
    setFieldDraft(undefined)
  }
  function commitFieldDraft(f: ProjectField, draft: FieldValue | undefined) {
    const original = fieldValues[f.id]
    // A required field's value can never be cleared (backend enforces the same).
    if (f.required && draft === undefined && original !== undefined) { cancelEditField(); return }
    setEditingFieldId(null)
    if (JSON.stringify(draft) === JSON.stringify(original)) return   // no change
    commitField({ fields: { [f.id]: draft === undefined ? null : draft } })
  }
  // Focus the editor's first control when it opens (so blur-commit works).
  useEffect(() => {
    if (!editingFieldId) return
    const el = fieldEditorRef.current?.querySelector('input,textarea,button') as HTMLElement | null
    el?.focus()
  }, [editingFieldId])
  // Close the "+ Add field" menu on outside click.
  useEffect(() => {
    if (!addFieldOpen) return
    function onDoc(e: MouseEvent) {
      if (!addMenuRef.current?.contains(e.target as Node)) setAddFieldOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [addFieldOpen])

  // Merged, newest-first activity feed under the active filter (HD-64).
  type ActivityItem =
    | { kind: 'comment'; at: string; comment: Comment }
    | { kind: 'history'; at: string; entry: IssueHistoryEntry }
  const activity = useMemo<ActivityItem[]>(() => {
    const items: ActivityItem[] = []
    if (activityFilter !== 'history') comments.forEach(c => items.push({ kind: 'comment', at: c.createdAt, comment: c }))
    if (activityFilter !== 'comments') history.forEach(h => items.push({ kind: 'history', at: h.createdAt, entry: h }))
    items.sort((a, b) => b.at.localeCompare(a.at))   // newest first
    return items
  }, [comments, history, activityFilter])

  // Show the jump nav only when the body is tall enough to warrant it, with
  // hysteresis (show at 1.5×, hide at 1.3×) so editing near the boundary doesn't
  // flicker. Re-measures on drawer resize and content growth.
  useEffect(() => {
    const el = scrollRef.current
    if (!el) return
    let raf = 0
    const recompute = () => {
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => {
        const ratio = el.scrollHeight / Math.max(1, el.clientHeight)
        setShowNav(prev => (prev ? ratio >= 1.3 : ratio >= 1.5))
      })
    }
    const ro = new ResizeObserver(recompute)
    ro.observe(el)
    if (contentRef.current) ro.observe(contentRef.current)
    recompute()
    return () => { cancelAnimationFrame(raf); ro.disconnect() }
  }, [loading])

  // Highlight the section currently at the top of the scroll viewport.
  useEffect(() => {
    if (!showNav) return
    const el = scrollRef.current
    if (!el) return
    const secs: [string, React.RefObject<HTMLDivElement | null>][] = [
      ['description', descSectionRef], ['subissues', subSectionRef],
      ['files', filesSectionRef], ['activity', activitySectionRef],
    ]
    let raf = 0
    const onScroll = () => {
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => {
        const top = el.getBoundingClientRect().top
        let current = 'description'
        for (const [id, ref] of secs) {
          const r = ref.current?.getBoundingClientRect()
          if (r && r.top - top <= 8) current = id   // top scrolled to/above the viewport top
        }
        setActiveSection(current)
      })
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    onScroll()
    return () => { el.removeEventListener('scroll', onScroll); cancelAnimationFrame(raf) }
  }, [showNav, children.length, attachments.length])

  const rootStyle: React.CSSProperties = {
    height: '100%',
    width: '100%',
    background: 'white',
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden',
    position: 'relative',
  }
  const headerStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '10px 16px',
    borderBottom: '1px solid var(--color-border)',
    flexShrink: 0,
    position: 'sticky',
    top: 0,
    background: 'white',
    zIndex: 5,
  }

  if (loading) {
    return (
      <div style={rootStyle}>
        <div style={headerStyle}>
          <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
          {onClose && (
            <button onClick={onClose} className="cursor-pointer hover:opacity-60"><X size={16} /></button>
          )}
        </div>
      </div>
    )
  }

  return (
    <div
      ref={rootRef}
      style={rootStyle}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDropFiles}
    >
      {/* File drag-and-drop overlay (HD-63) — appears while dragging OS files */}
      {dragOver && (
        <div
          className="absolute inset-0 flex items-center justify-center rounded"
          style={{
            zIndex: 40, pointerEvents: 'none',
            background: 'color-mix(in srgb, var(--color-brand) 8%, white)',
            border: '2px dashed var(--color-brand)',
          }}
        >
          <span className="text-sm font-medium flex items-center gap-2" style={{ color: 'var(--color-brand)' }}>
            <Paperclip size={16} /> Drop to attach
          </span>
        </div>
      )}
      {/* ── Sticky header: key (links to the full page from the drawer) · ⋯ · close ── */}
      <div style={headerStyle}>
        {issue && (enableExpand ? (
          <Link
            to={`/w/${wsId}/p/${projectId}/issues/${issueNumber}`}
            className="mono text-xs flex-shrink-0 hover:underline"
            style={{ color: 'var(--color-text-muted)' }}
            title="Open full page"
          >
            {issue.key}
          </Link>
        ) : (
          <span className="mono text-xs flex-shrink-0" style={{ color: 'var(--color-text-muted)' }}>
            {issue.key}
          </span>
        ))}
        <div className="flex items-center gap-1">
          <div className="relative" ref={menuRef}>
            <button
              onClick={() => setMenuOpen(o => !o)}
              aria-label="More actions"
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              className="cursor-pointer p-1 rounded hover:bg-[var(--color-surface-2)] transition-colors"
            >
              <MoreHorizontal size={16} style={{ color: 'var(--color-text-muted)' }} />
            </button>
            {menuOpen && (
              <div
                role="menu"
                className="absolute right-0 mt-1 border rounded-md py-1 z-30"
                style={{
                  minWidth: 160, background: 'var(--color-card)',
                  borderColor: 'var(--color-border-2)', boxShadow: 'var(--shadow-lg)',
                }}
              >
                <button
                  role="menuitem"
                  onClick={handleDelete}
                  className="w-full flex items-center gap-2 px-3 py-1.5 text-sm text-left cursor-pointer hover:bg-[var(--color-surface-2)] transition-colors"
                  style={{ color: 'var(--color-error)' }}
                >
                  <Trash2 size={13} /> Delete issue
                </button>
              </div>
            )}
          </div>
          {onClose && (
            <button onClick={onClose} className="cursor-pointer p-1 hover:opacity-60 transition-opacity">
              <X size={16} style={{ color: 'var(--color-text-muted)' }} />
            </button>
          )}
        </div>
      </div>

      {/* ── Jump nav (HD-65) — appears only for a tall issue ── */}
      {showNav && issue && (
        <div
          className="flex items-center gap-1 px-4 py-1.5 border-b flex-shrink-0 overflow-x-auto"
          style={{ background: 'white', borderColor: 'var(--color-border)' }}
        >
          {[
            { id: 'description', label: 'Description', ref: descSectionRef, show: true, count: undefined as number | undefined },
            { id: 'subissues', label: 'Sub-issues', ref: subSectionRef, show: canHaveChildren || children.length > 0, count: children.length },
            { id: 'files', label: 'Files', ref: filesSectionRef, show: true, count: attachments.length },
            { id: 'activity', label: 'Activity', ref: activitySectionRef, show: true, count: comments.length + history.length },
          ].filter(n => n.show).map(n => (
            <button
              key={n.id}
              onClick={() => n.ref.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
              className="text-xs px-2 py-0.5 rounded whitespace-nowrap cursor-pointer transition-colors"
              style={{
                color: activeSection === n.id ? 'var(--color-brand)' : 'var(--color-text-muted)',
                fontWeight: activeSection === n.id ? 600 : 400,
                background: activeSection === n.id ? 'var(--color-surface-2)' : 'transparent',
              }}
            >
              {n.label}{n.count !== undefined ? ` ${n.count}` : ''}
            </button>
          ))}
        </div>
      )}

      {/* ── Single continuous scroll ── */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto">
        <div ref={contentRef} className="p-4 flex flex-col gap-4">

        {/* Optimistic-lock notice — non-destructive, dismissible */}
        {conflict && (
          <div
            className="flex items-center justify-between gap-2 text-xs rounded px-3 py-2"
            style={{ background: '#fef3c7', color: '#92400e', border: '1px solid #fcd34d' }}
          >
            <span>This issue changed elsewhere — it was reloaded with the latest version.</span>
            <button onClick={() => setConflict(false)} className="cursor-pointer flex-shrink-0 hover:opacity-70">
              <X size={13} />
            </button>
          </div>
        )}

        {/* Parent breadcrumb — navigates to the parent issue */}
        {issue?.parentId && issue.parentKey && (() => {
          const parentColor = issueTypes.find(t => t.id === issue.parentTypeId)?.color
            ?? 'var(--color-text-muted)'
          const parentNumber = numberFromKey(issue.parentKey)
          return (
            <button
              type="button"
              disabled={parentNumber === undefined || !onOpenIssue}
              onClick={() => parentNumber !== undefined && onOpenIssue?.(parentNumber)}
              className="flex items-center gap-1.5 text-xs text-left rounded px-2 py-1 -mx-1 transition-colors disabled:cursor-default enabled:cursor-pointer enabled:hover:bg-[var(--color-surface-2)]"
              style={{ color: 'var(--color-text-secondary)', width: 'fit-content', maxWidth: '100%' }}
            >
              <CornerDownRight size={12} style={{ color: parentColor, flexShrink: 0 }} />
              <span style={{ color: 'var(--color-text-muted)' }}>under</span>
              <span className="mono" style={{ color: parentColor }}>{issue.parentKey}</span>
              {issue.parentTitle && (
                <span className="truncate" style={{ color: 'var(--color-text-muted)' }}>
                  · {issue.parentTitle}
                </span>
              )}
            </button>
          )
        })()}

        {/* Title — inline edit (click / Enter to edit, Enter/blur commit, Esc cancel) */}
        {titleEditing ? (
          <textarea
            ref={titleRef}
            value={titleDraft}
            onChange={e => {
              setTitleDraft(e.target.value)
              const el = e.target
              el.style.height = 'auto'
              el.style.height = `${el.scrollHeight}px`
            }}
            onBlur={commitTitle}
            onKeyDown={e => {
              if (e.key === 'Enter') { e.preventDefault(); commitTitle() }
              else if (e.key === 'Escape') { e.preventDefault(); setTitleEditing(false) }
            }}
            rows={1}
            className="w-full text-base font-semibold leading-snug rounded-md border px-2 py-1 outline-none resize-none"
            style={{ color: 'var(--color-text)', borderColor: 'var(--color-brand)', background: 'white' }}
          />
        ) : (
          <h2
            tabIndex={0}
            onClick={startTitleEdit}
            onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); startTitleEdit() } }}
            className="text-base font-semibold leading-snug rounded-md px-2 py-1 -mx-2 cursor-text outline-none transition-colors hover:bg-[var(--color-surface-2)] focus:bg-[var(--color-surface-2)]"
            style={{ color: 'var(--color-text)' }}
            title="Click to edit"
          >
            {issue?.title}
          </h2>
        )}

        {/* Metadata grid — inline click-to-edit (HD-61). Each control commits its
            own field; Reporter is read-only. */}
        {issue && (
          <>
            <div className={`grid ${gridCols} gap-3`}>
              {/* Status — only legal workflow transitions are offered */}
              <div>
                <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Status</div>
                <Select value={issue.status.id} onChange={e => commitField({ statusId: e.target.value })}>
                  <option value={issue.status.id}>
                    <StatusBadge name={issue.status.name} category={issue.status.category} color={issue.status.color} />
                  </option>
                  {statuses
                    .filter(s => s.id !== issue.status.id && isMoveAllowed(issue.status, s.id, transitions))
                    .map(s => (
                      <option key={s.id} value={s.id}>
                        <StatusBadge name={s.name} category={s.category} color={s.color} />
                      </option>
                    ))}
                </Select>
              </div>

              {/* Priority */}
              <div>
                <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Priority</div>
                <Select value={issue.priority.id} onChange={e => commitField({ priorityId: e.target.value })}>
                  {priorities.map(p => (
                    <option key={p.id} value={p.id}><PriorityBadge priority={p} /></option>
                  ))}
                  {/* Keep the current priority selectable even if no longer offered */}
                  {!priorities.some(p => p.id === issue.priority.id) && (
                    <option value={issue.priority.id}><PriorityBadge priority={issue.priority} /></option>
                  )}
                </Select>
              </div>

              {/* Type — guarded against stranding the current parent */}
              <div>
                <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Type</div>
                <Select value={issue.type.id} onChange={e => changeType(e.target.value)}>
                  {issueTypes.map(t => (
                    <option key={t.id} value={t.id}>
                      <span style={{ color: t.color }}>{t.name}</span>
                    </option>
                  ))}
                </Select>
              </div>

              {/* Assignee — blank clears (clearAssignee) */}
              <div>
                <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Assignee</div>
                <Select
                  value={issue.assignee?.id ?? ''}
                  onChange={e => commitField(e.target.value ? { assigneeId: e.target.value } : { clearAssignee: true })}
                >
                  <option value="">Unassigned</option>
                  {members.map(m => <option key={m.userId} value={m.userId}>{m.displayName}</option>)}
                  {/* Current assignee may have left the workspace — keep selectable */}
                  {issue.assignee && !members.some(m => m.userId === issue.assignee!.id) && (
                    <option value={issue.assignee.id}>{issue.assignee.displayName}</option>
                  )}
                </Select>
              </div>

              {/* Due date — clearing the field clears the due date */}
              <div>
                <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Due date</div>
                <Input
                  type="date"
                  value={issue.dueDate ?? ''}
                  onChange={e => commitField(e.target.value ? { dueDate: e.target.value } : { clearDueDate: true })}
                />
              </div>

              {/* Parent — adjacency-filtered; blank detaches (clearParent) */}
              {canHaveParent && (
                <div onMouseDown={ensureParents}>
                  <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Parent</div>
                  <Select
                    value={issue.parentId ?? ''}
                    onChange={e => { setMetaError(''); commitField(e.target.value ? { parentId: e.target.value } : { clearParent: true }) }}
                  >
                    <option value="">No parent</option>
                    {eligibleParents.map(i => (
                      <option key={i.id} value={i.id}>{i.key} — {i.title}</option>
                    ))}
                    {/* Keep the current parent selectable while candidates load */}
                    {issue.parentId && !eligibleParents.some(i => i.id === issue.parentId) && issue.parentKey && (
                      <option value={issue.parentId}>{issue.parentKey} — {issue.parentTitle ?? ''}</option>
                    )}
                  </Select>
                </div>
              )}

              {/* Reporter — read-only */}
              <div>
                <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Reporter</div>
                <div className="flex items-center gap-1.5 py-2">
                  <Avatar name={issue.reporter.displayName} avatarUrl={issue.reporter.avatarUrl} size={18} />
                  <span className="text-sm truncate">{issue.reporter.displayName}</span>
                </div>
              </div>
            </div>
            {metaError && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{metaError}</p>}
          </>
        )}

        {/* Description — inline edit (HD-62). Plain text for now; Markdown is HD-66. */}
        {issue && (
          <div ref={descSectionRef} style={{ scrollMarginTop: 8 }}>
            <div className="mb-1.5"><SectionHeading>Description</SectionHeading></div>
            {descEditing ? (
              <div>
                <div className="flex items-center gap-2 mb-1">
                  {!descPreview && <MarkdownToolbar textareaRef={descRef} value={descDraft} onChange={setDescDraft} />}
                  <button
                    type="button"
                    onMouseDown={e => e.preventDefault()}
                    onClick={() => setDescPreview(p => !p)}
                    className="text-xs cursor-pointer ml-auto hover:underline"
                    style={{ color: 'var(--color-text-muted)' }}
                  >
                    {descPreview ? 'Write' : 'Preview'}
                  </button>
                </div>
                {descPreview ? (
                  <div className="border rounded-md px-3 py-2" style={{ borderColor: 'var(--color-border-2)', minHeight: 96 }}>
                    {descDraft.trim()
                      ? <Markdown>{descDraft}</Markdown>
                      : <span className="text-sm" style={{ color: 'var(--color-text-muted)' }}>Nothing to preview</span>}
                  </div>
                ) : (
                  <textarea
                    ref={descRef}
                    value={descDraft}
                    onChange={e => {
                      setDescDraft(e.target.value)
                      const el = e.target; el.style.height = 'auto'; el.style.height = `${el.scrollHeight}px`
                    }}
                    onKeyDown={e => {
                      if (e.key === 'Escape') { e.preventDefault(); cancelDescEdit() }
                      else if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); commitDesc() }
                    }}
                    rows={4}
                    placeholder="Add a description… (Markdown supported)"
                    className="w-full text-sm rounded-md border px-3 py-2 outline-none resize-none"
                    style={{ color: 'var(--color-text)', borderColor: 'var(--color-brand)', background: 'white' }}
                  />
                )}
                <div className="flex gap-3 mt-1.5">
                  <button onClick={commitDesc} className="text-xs cursor-pointer" style={{ color: 'var(--color-brand)' }}>Save</button>
                  <button onClick={cancelDescEdit} className="text-xs cursor-pointer" style={{ color: 'var(--color-text-muted)' }}>Cancel</button>
                </div>
              </div>
            ) : issue.description ? (
              <div
                onClick={startDescEdit}
                className="rounded-md px-2 py-1 -mx-2 cursor-text transition-colors hover:bg-[var(--color-surface-2)]"
                title="Click to edit"
              >
                <Markdown>{issue.description}</Markdown>
              </div>
            ) : (
              <button
                onClick={startDescEdit}
                className="text-sm text-left cursor-text rounded-md px-2 py-1 -mx-2 transition-colors hover:bg-[var(--color-surface-2)]"
                style={{ color: 'var(--color-text-muted)' }}
              >
                Add a description…
              </button>
            )}
          </div>
        )}

        {/* Custom fields — inline click-to-edit (HD-62). Filled fields show by
            default; "+ Add field" reveals an unfilled one. */}
        {issue && fields.length > 0 && (
          <div className="flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <SectionHeading>Fields</SectionHeading>
              {fields.some(f => fieldValues[f.id] === undefined) && (
                <div className="relative" ref={addMenuRef}>
                  <button
                    onClick={() => setAddFieldOpen(o => !o)}
                    className="inline-flex items-center gap-1 text-xs cursor-pointer hover:underline"
                    style={{ color: 'var(--color-brand)' }}
                  >
                    <Plus size={12} /> Add field
                  </button>
                  {addFieldOpen && (
                    <div
                      className="absolute right-0 mt-1 border rounded-md py-1 z-30"
                      style={{ minWidth: 180, background: 'var(--color-card)', borderColor: 'var(--color-border-2)', boxShadow: 'var(--shadow-lg)' }}
                    >
                      {fields.filter(f => fieldValues[f.id] === undefined).map(f => (
                        <button
                          key={f.id}
                          onClick={() => startEditField(f)}
                          className="w-full text-left px-3 py-1.5 text-sm cursor-pointer hover:bg-[var(--color-surface-2)] transition-colors"
                          style={{ color: 'var(--color-text)' }}
                        >
                          {f.name}{f.required ? ' *' : ''}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>

            {fields.some(f => fieldValues[f.id] !== undefined || editingFieldId === f.id) ? (
              <div className={`grid ${gridCols} gap-3`}>
                {fields.filter(f => fieldValues[f.id] !== undefined || editingFieldId === f.id).map(f => {
                  const fullWidth = f.type === 'TEXTAREA' || f.type === 'MULTI_SELECT'
                  return (
                    <div key={f.id} className={fullWidth ? 'col-span-2' : ''}>
                      {editingFieldId === f.id ? (
                        <div
                          ref={fieldEditorRef}
                          onBlur={isDiscreteField(f.type) ? undefined : (e => {
                            if (!e.currentTarget.contains(e.relatedTarget as Node)) commitFieldDraft(f, fieldDraft)
                          })}
                          onKeyDown={e => {
                            if (e.key === 'Escape') { e.preventDefault(); cancelEditField() }
                            else if (e.key === 'Enter' && (f.type === 'TEXT' || f.type === 'URL' || f.type === 'NUMBER')) { e.preventDefault(); commitFieldDraft(f, fieldDraft) }
                            else if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && f.type === 'TEXTAREA') { e.preventDefault(); commitFieldDraft(f, fieldDraft) }
                          }}
                        >
                          <FieldInput
                            field={f}
                            value={fieldDraft}
                            members={members}
                            onChange={v => { setFieldDraft(v); if (isDiscreteField(f.type)) commitFieldDraft(f, v) }}
                          />
                          <div className="flex gap-3 mt-1.5">
                            {!isDiscreteField(f.type) && (
                              <button onClick={() => commitFieldDraft(f, fieldDraft)} className="text-xs cursor-pointer" style={{ color: 'var(--color-brand)' }}>Save</button>
                            )}
                            <button onMouseDown={e => { e.preventDefault(); cancelEditField() }} className="text-xs cursor-pointer" style={{ color: 'var(--color-text-muted)' }}>Cancel</button>
                          </div>
                        </div>
                      ) : (
                        <div
                          onClick={() => startEditField(f)}
                          className="cursor-pointer rounded-md px-2 py-1 -mx-2 transition-colors hover:bg-[var(--color-surface-2)]"
                          title="Click to edit"
                        >
                          <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>{f.name}</div>
                          <FieldValueDisplay field={f} value={fieldValues[f.id]!} members={members} />
                        </div>
                      )}
                    </div>
                  )
                })}
              </div>
            ) : (
              <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>No field values set.</p>
            )}
          </div>
        )}

        {/* Sub-issues — roll-up progress + list + create sub-task */}
        {issue && (canHaveChildren || children.length > 0) && (
          <div ref={subSectionRef} className="flex flex-col gap-2 pt-1 border-t" style={{ borderColor: 'var(--color-border)', scrollMarginTop: 8 }}>
            <div className="flex items-center justify-between pt-2">
              <SectionHeading>Sub-issues</SectionHeading>
              {canHaveChildren && (
                <button
                  type="button"
                  onClick={() => openCreateIssue({
                    parentId: issue.id,
                    projectId,
                    parentLevel: issue.type.hierarchyLevel,
                  })}
                  className="inline-flex items-center gap-1 text-xs cursor-pointer hover:underline"
                  style={{ color: 'var(--color-brand)' }}
                >
                  <Plus size={12} /> Create sub-task
                </button>
              )}
            </div>

            {children.length > 0 && (
              <ChildrenProgress
                done={children.filter(c => c.status.category === 'DONE').length}
                total={children.length}
              />
            )}

            <div className="flex flex-col gap-1">
              {children.map(c => (
                <button
                  key={c.id}
                  type="button"
                  onClick={() => onOpenIssue?.(c.number)}
                  disabled={!onOpenIssue}
                  className="flex items-center gap-2 rounded border px-2 py-1.5 text-left transition-colors disabled:cursor-default enabled:cursor-pointer enabled:hover:border-[var(--color-border-2)]"
                  style={{ background: 'white', borderColor: 'var(--color-border)' }}
                >
                  <span
                    className="rounded-full flex-shrink-0"
                    style={{ width: 7, height: 7, background: c.type.color }}
                    title={c.type.name}
                  />
                  <span className="mono text-xs flex-shrink-0" style={{ color: 'var(--color-text-muted)' }}>{c.key}</span>
                  <span className="text-xs truncate flex-1" style={{ color: 'var(--color-text)' }}>{c.title}</span>
                  <StatusBadge name={c.status.name} category={c.status.category} color={c.status.color} />
                </button>
              ))}
              {children.length === 0 && (
                <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>No sub-issues yet.</p>
              )}
            </div>
          </div>
        )}

        {/* Files */}
        <div ref={filesSectionRef} className="flex flex-col gap-3 pt-1 border-t" style={{ borderColor: 'var(--color-border)', scrollMarginTop: 8 }}>
          <div className="pt-2"><SectionHeading>Files ({attachments.length})</SectionHeading></div>
          {attachments.length === 0 && (
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>No files attached yet.</p>
          )}
          {attachments.map(a => (
            <div key={a.id} className="flex gap-2.5 items-center group">
              <Paperclip size={14} className="flex-shrink-0" style={{ color: 'var(--color-text-muted)' }} />
              <div className="flex-1 min-w-0">
                <button
                  onClick={() => handleDownloadAttachment(a)}
                  className="text-sm font-medium truncate block max-w-full text-left cursor-pointer hover:underline"
                  style={{ color: 'var(--color-brand)' }}
                  title={`Download ${a.filename}`}
                >
                  {a.filename}
                </button>
                <div className="flex items-baseline gap-2">
                  <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    {formatBytes(a.sizeBytes)}
                  </span>
                  <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    {a.uploadedByName} · {new Date(a.createdAt).toLocaleDateString()}
                  </span>
                </div>
              </div>
              {user?.id === a.uploadedById && (
                <button
                  onClick={() => handleDeleteAttachment(a.id)}
                  className="opacity-0 group-hover:opacity-100 transition-opacity cursor-pointer flex-shrink-0"
                  style={{ color: 'var(--color-text-muted)' }}
                >
                  <Trash2 size={13} />
                </button>
              )}
            </div>
          ))}

          {fileError && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{fileError}</p>}

          <div>
            <input ref={fileInputRef} type="file" multiple className="hidden" onChange={handleUploadFile} />
            <Button variant="ghost" size="sm" onClick={() => fileInputRef.current?.click()} loading={uploading}>
              <Paperclip size={13} /> Attach file
            </Button>
          </div>
        </div>

        {/* Activity — merged comments + history, newest-first, filterable (HD-64) */}
        <div ref={activitySectionRef} className="flex flex-col gap-3 pt-1 border-t" style={{ borderColor: 'var(--color-border)', scrollMarginTop: 8 }}>
          <div ref={activityHeadingRef} className="flex items-center justify-between pt-2 gap-2 flex-wrap">
            <SectionHeading>Activity</SectionHeading>
            <div className="flex items-center gap-0.5 rounded-md p-0.5" style={{ background: 'var(--color-surface-2)' }}>
              {(['all', 'comments', 'history'] as const).map(f => {
                const count = f === 'all' ? comments.length + history.length : f === 'comments' ? comments.length : history.length
                const active = activityFilter === f
                return (
                  <button
                    key={f}
                    onClick={() => setActivityFilter(f)}
                    className="text-xs px-2 py-0.5 rounded capitalize cursor-pointer transition-colors"
                    style={{
                      background: active ? 'white' : 'transparent',
                      color: active ? 'var(--color-brand)' : 'var(--color-text-muted)',
                      fontWeight: active ? 600 : 400,
                      boxShadow: active ? 'var(--shadow-card)' : 'none',
                    }}
                  >
                    {f} {count}
                  </button>
                )
              })}
            </div>
          </div>

          {activity.length === 0 ? (
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>No activity yet.</p>
          ) : (
            <div className="flex flex-col gap-3">
              {activity.map(item => item.kind === 'comment' ? (
                <div key={`c-${item.comment.id}`} className="flex gap-2.5 group">
                  <Avatar name={item.comment.authorName} size={22} />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-baseline gap-2 mb-0.5">
                      <span className="text-xs font-medium truncate min-w-0">{item.comment.authorName}</span>
                      <ActivityTime at={item.comment.createdAt} />
                    </div>
                    <Markdown>{item.comment.body}</Markdown>
                  </div>
                  {user?.id === item.comment.authorId ? (
                    <button
                      onClick={() => handleDeleteComment(item.comment.id)}
                      className="opacity-0 group-hover:opacity-100 transition-opacity cursor-pointer flex-shrink-0"
                      style={{ color: 'var(--color-text-muted)' }}
                      title="Delete comment"
                    >
                      <Trash2 size={13} />
                    </button>
                  ) : (
                    <span className="flex-shrink-0" style={{ width: ACTIVITY_ACTION_W }} aria-hidden />
                  )}
                </div>
              ) : (
                <div key={`h-${item.entry.id}`} className="flex gap-2.5 items-start">
                  <div
                    className="flex-shrink-0 rounded-full mt-1.5"
                    style={{ width: 6, height: 6, background: 'var(--color-brand)' }}
                  />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-baseline gap-2">
                      <span className="text-xs truncate min-w-0" style={{ color: 'var(--color-text-muted)' }}>
                        <span className="font-medium" style={{ color: 'var(--color-text)' }}>{item.entry.changedByName}</span>
                        {' '}changed <strong>{historyLabel(item.entry.field)}</strong>
                      </span>
                      <ActivityTime at={item.entry.createdAt} />
                    </div>
                    {(item.entry.oldValue !== undefined || item.entry.newValue !== undefined) && item.entry.field !== 'description' && (
                      <div className="text-xs mt-0.5 flex items-center gap-1" style={{ color: 'var(--color-text-secondary)' }}>
                        {item.entry.oldValue && (
                          <span
                            className="px-1.5 py-0.5 rounded"
                            style={{ background: '#fee2e2', color: '#991b1b', textDecoration: 'line-through' }}
                          >
                            {item.entry.oldValue}
                          </span>
                        )}
                        {item.entry.oldValue && item.entry.newValue && <span>→</span>}
                        {item.entry.newValue && (
                          <span className="px-1.5 py-0.5 rounded" style={{ background: '#dcfce7', color: '#166534' }}>
                            {item.entry.newValue}
                          </span>
                        )}
                      </div>
                    )}
                  </div>
                  {/* Mirrors the comment row's delete-button gutter so both kinds
                      of timestamp share one right edge. */}
                  <span className="flex-shrink-0" style={{ width: ACTIVITY_ACTION_W }} aria-hidden />
                </div>
              ))}
            </div>
          )}
        </div>

        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        </div>
      </div>

      {/* ── Sticky comment composer (HD-64) — always reachable at the bottom ── */}
      {issue && (
        <div className="flex-shrink-0 border-t p-3" style={{ borderColor: 'var(--color-border)', background: 'white' }}>
          <div className="flex items-center gap-2 mb-1">
            {!commentPreview && <MarkdownToolbar textareaRef={commentRef} value={commentBody} onChange={setCommentBody} />}
            <button
              type="button"
              onMouseDown={e => e.preventDefault()}
              onClick={() => setCommentPreview(p => !p)}
              className="text-xs cursor-pointer ml-auto hover:underline"
              style={{ color: 'var(--color-text-muted)' }}
            >
              {commentPreview ? 'Write' : 'Preview'}
            </button>
          </div>
          <div className="relative flex gap-2 items-end">
            <div className="flex-1 relative">
              {commentPreview ? (
                <div className="border rounded-md px-3 py-2 text-sm" style={{ borderColor: 'var(--color-border-2)', minHeight: 52 }}>
                  {commentBody.trim()
                    ? <Markdown>{commentBody}</Markdown>
                    : <span style={{ color: 'var(--color-text-muted)' }}>Nothing to preview</span>}
                </div>
              ) : (
                <Textarea
                  ref={commentRef}
                  value={commentBody}
                  onChange={handleCommentChange}
                  placeholder="Add a comment… Use @Name to mention"
                  rows={2}
                />
              )}
              {!commentPreview && filteredMembers.length > 0 && (
                <div
                  className="absolute z-10 border rounded shadow-md"
                  style={{
                    bottom: '100%', left: 0, right: 0, marginBottom: 4,
                    background: 'white', borderColor: 'var(--color-border)',
                  }}
                >
                  {filteredMembers.map(m => (
                    <button
                      key={m.userId}
                      onMouseDown={e => { e.preventDefault(); insertMention(m) }}
                      className="w-full flex items-center gap-2 px-3 py-1.5 text-sm text-left cursor-pointer hover:bg-gray-50"
                      style={{ color: 'var(--color-text)' }}
                    >
                      <Avatar name={m.displayName} size={18} />
                      <span>{m.displayName}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <Button variant="primary" size="sm" onClick={handlePostComment} loading={postingComment} disabled={!commentBody.trim()}>
              Post
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
