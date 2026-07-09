import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { X, Trash2, Paperclip } from 'lucide-react'
import {
  apiGetIssue, apiCreateIssue, apiUpdateIssue, apiDeleteIssue,
  apiListComments, apiCreateComment, apiDeleteComment,
  apiListAttachments, apiUploadAttachment, apiDownloadAttachment, apiDeleteAttachment,
  apiGetIssueHistory, apiListWorkspaceMembers,
} from '../api'
import { useAuthStore } from '../auth'
import { Button, Input, Textarea, Select, StatusBadge, PriorityBadge, Avatar } from '../components/ui'
import type { Issue, IssueType, Status, Comment, Attachment, IssueHistoryEntry, WorkspaceMember } from '../types'

const PRIORITIES = ['URGENT', 'HIGH', 'MEDIUM', 'LOW', 'NONE']

type Tab = 'details' | 'comments' | 'files' | 'history'

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

interface Props {
  wsId: string
  projectId: string
  issueNumber: number | null  // null = create mode
  issueTypes: IssueType[]
  statuses: Status[]
  onClose: () => void
}

export default function IssueSidePanel({ wsId, projectId, issueNumber, issueTypes, statuses, onClose }: Props) {
  const qc = useQueryClient()
  const { user } = useAuthStore()
  const isCreate = issueNumber === null

  const [issue, setIssue] = useState<Issue | null>(null)
  const [comments, setComments] = useState<Comment[]>([])
  const [attachments, setAttachments] = useState<Attachment[]>([])
  const [history, setHistory] = useState<IssueHistoryEntry[]>([])
  const [loading, setLoading] = useState(!isCreate)
  const [tab, setTab] = useState<Tab>('details')

  // Form state
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [typeId, setTypeId] = useState(issueTypes[0]?.id ?? '')
  const [statusId, setStatusId] = useState(statuses[0]?.id ?? '')
  const [priority, setPriority] = useState('MEDIUM')
  const [saving, setSaving] = useState(false)
  const [editing, setEditing] = useState(isCreate)
  const [commentBody, setCommentBody] = useState('')
  const [postingComment, setPostingComment] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [fileError, setFileError] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [error, setError] = useState('')

  // Mention autocomplete
  const [members, setMembers] = useState<WorkspaceMember[]>([])
  const [mentionQuery, setMentionQuery] = useState<string | null>(null)
  const [mentionStart, setMentionStart] = useState(0)
  const commentRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    apiListWorkspaceMembers(wsId).then(setMembers).catch(() => {})
  }, [wsId])

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
    const newVal = before + '@' + member.displayName + ' ' + after
    setCommentBody(newVal)
    setMentionQuery(null)
    commentRef.current?.focus()
  }

  const loadIssue = useCallback(async () => {
    if (isCreate) return
    setLoading(true)
    try {
      const [iss, cmts, atts, hist] = await Promise.all([
        apiGetIssue(wsId, projectId, issueNumber!),
        apiListComments(wsId, projectId, issueNumber!),
        apiListAttachments(wsId, projectId, issueNumber!),
        apiGetIssueHistory(wsId, projectId, issueNumber!),
      ])
      setIssue(iss)
      setTitle(iss.title)
      setDescription(iss.description ?? '')
      setTypeId(iss.type.id)
      setStatusId(iss.status.id)
      setPriority(iss.priority)
      setComments(cmts)
      setAttachments(atts)
      setHistory(hist)
    } finally {
      setLoading(false)
    }
  }, [wsId, projectId, issueNumber, isCreate])

  useEffect(() => { loadIssue() }, [loadIssue])

  async function handleSave() {
    setError('')
    setSaving(true)
    try {
      if (isCreate) {
        await apiCreateIssue(wsId, projectId, { title: title.trim(), typeId, statusId, priority, description: description || undefined })
        await qc.invalidateQueries({ queryKey: ['issues', wsId, projectId] })
        onClose()
      } else {
        const updated = await apiUpdateIssue(wsId, projectId, issueNumber!,
          { title, description, typeId, statusId, priority, version: issue?.version })
        setIssue(updated)
        // Reload history after update
        apiGetIssueHistory(wsId, projectId, issueNumber!).then(setHistory).catch(() => {})
        await qc.invalidateQueries({ queryKey: ['issues', wsId, projectId] })
        setEditing(false)
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete() {
    if (!issue) return
    if (!window.confirm(`Delete ${issue.key}? This cannot be undone.`)) return
    await apiDeleteIssue(wsId, projectId, issueNumber!)
    await qc.invalidateQueries({ queryKey: ['issues', wsId, projectId] })
    onClose()
  }

  async function handlePostComment() {
    if (!commentBody.trim()) return
    setPostingComment(true)
    try {
      const c = await apiCreateComment(wsId, projectId, issueNumber!, commentBody.trim())
      setComments(prev => [...prev, c])
      setCommentBody('')
    } finally {
      setPostingComment(false)
    }
  }

  async function handleDeleteComment(commentId: string) {
    await apiDeleteComment(wsId, projectId, issueNumber!, commentId)
    setComments(prev => prev.filter(c => c.id !== commentId))
  }

  async function handleUploadFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = '' // allow re-selecting the same file
    if (!file) return
    setFileError('')
    setUploading(true)
    try {
      const att = await apiUploadAttachment(wsId, projectId, issueNumber!, file)
      setAttachments(prev => [...prev, att])
    } catch (err: unknown) {
      setFileError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setUploading(false)
    }
  }

  async function handleDownloadAttachment(att: Attachment) {
    try {
      await apiDownloadAttachment(wsId, projectId, issueNumber!, att)
    } catch {
      setFileError('Download failed')
    }
  }

  async function handleDeleteAttachment(attachmentId: string) {
    await apiDeleteAttachment(wsId, projectId, issueNumber!, attachmentId)
    setAttachments(prev => prev.filter(a => a.id !== attachmentId))
  }

  const panelStyle: React.CSSProperties = {
    width: 440,
    minWidth: 440,
    height: '100%',
    background: 'white',
    borderLeft: '1px solid var(--color-border)',
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden',
  }

  const headerStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '10px 16px',
    borderBottom: '1px solid var(--color-border)',
    flexShrink: 0,
  }

  if (loading) {
    return (
      <div style={panelStyle}>
        <div style={headerStyle}>
          <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>loading…</span>
          <button onClick={onClose} className="cursor-pointer hover:opacity-60"><X size={16} /></button>
        </div>
      </div>
    )
  }

  function historyLabel(field: string) {
    const labels: Record<string, string> = {
      title: 'Title', description: 'Description', status: 'Status',
      priority: 'Priority', type: 'Type', assignee: 'Assignee', dueDate: 'Due date',
    }
    return labels[field] ?? field
  }

  return (
    <div style={panelStyle}>
      {/* Header */}
      <div style={headerStyle}>
        <div className="flex items-center gap-2 min-w-0">
          {issue && (
            <span className="mono text-xs flex-shrink-0" style={{ color: 'var(--color-text-muted)' }}>
              {issue.key}
            </span>
          )}
          {isCreate && (
            <span className="text-sm font-medium" style={{ color: 'var(--color-text-muted)' }}>New Issue</span>
          )}
        </div>
        <div className="flex items-center gap-1">
          {!isCreate && !editing && (
            <>
              <Button variant="ghost" size="sm" onClick={() => setEditing(true)}>Edit</Button>
              <Button variant="ghost" size="sm" onClick={handleDelete}>
                <Trash2 size={13} style={{ color: 'var(--color-error)' }} />
              </Button>
            </>
          )}
          <button onClick={onClose} className="cursor-pointer p-1 hover:opacity-60 transition-opacity ml-1">
            <X size={16} style={{ color: 'var(--color-text-muted)' }} />
          </button>
        </div>
      </div>

      {/* Tabs — only in view mode */}
      {!isCreate && !editing && (
        <div
          className="flex border-b flex-shrink-0"
          style={{ borderColor: 'var(--color-border)' }}
        >
          {(['details', 'comments', 'files', 'history'] as Tab[]).map(t => (
            <button
              key={t}
              onClick={() => setTab(t)}
              className="px-4 py-2 text-xs font-medium capitalize cursor-pointer transition-colors"
              style={{
                borderBottom: tab === t ? '2px solid var(--color-brand)' : '2px solid transparent',
                color: tab === t ? 'var(--color-brand)' : 'var(--color-text-muted)',
                background: 'transparent',
              }}
            >
              {t}{t === 'comments' ? ` (${comments.length})` : ''}
              {t === 'files' ? ` (${attachments.length})` : ''}
              {t === 'history' ? ` (${history.length})` : ''}
            </button>
          ))}
        </div>
      )}

      {/* Body */}
      <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-4">

        {/* ── Details tab / create form ── */}
        {(tab === 'details' || isCreate || editing) && (
          <>
            {/* Title */}
            {editing ? (
              <Input
                label="Title"
                value={title}
                onChange={e => setTitle(e.target.value)}
                placeholder="Issue title"
                autoFocus={isCreate}
              />
            ) : (
              <h2 className="text-base font-semibold leading-snug" style={{ color: 'var(--color-text)' }}>
                {issue?.title}
              </h2>
            )}

            {/* Metadata grid */}
            <div className="grid grid-cols-2 gap-3">
              {editing ? (
                <>
                  <Select label="Type" value={typeId} onChange={e => setTypeId(e.target.value)}>
                    {issueTypes.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                  </Select>
                  <Select label="Status" value={statusId} onChange={e => setStatusId(e.target.value)}>
                    {statuses.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                  </Select>
                  <Select label="Priority" value={priority} onChange={e => setPriority(e.target.value)}>
                    {PRIORITIES.map(p => <option key={p} value={p}>{p}</option>)}
                  </Select>
                </>
              ) : issue ? (
                <>
                  <div>
                    <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Status</div>
                    <StatusBadge name={issue.status.name} category={issue.status.category} color={issue.status.color} />
                  </div>
                  <div>
                    <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Priority</div>
                    <PriorityBadge priority={issue.priority} />
                  </div>
                  <div>
                    <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Type</div>
                    <span className="text-sm" style={{ color: issue.type.color }}>{issue.type.name}</span>
                  </div>
                  <div>
                    <div className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>Reporter</div>
                    <div className="flex items-center gap-1.5">
                      <Avatar name={issue.reporter.displayName} avatarUrl={issue.reporter.avatarUrl} size={18} />
                      <span className="text-sm truncate">{issue.reporter.displayName}</span>
                    </div>
                  </div>
                </>
              ) : null}
            </div>

            {/* Description */}
            {editing ? (
              <Textarea
                label="Description"
                value={description}
                onChange={e => setDescription(e.target.value)}
                placeholder="Add a description…"
                rows={4}
              />
            ) : issue?.description ? (
              <div>
                <div className="text-xs mb-1.5" style={{ color: 'var(--color-text-muted)' }}>Description</div>
                <p className="text-sm whitespace-pre-wrap" style={{ color: 'var(--color-text)' }}>
                  {issue.description}
                </p>
              </div>
            ) : null}

            {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}

            {/* Save / Cancel */}
            {editing && (
              <div className="flex gap-2">
                <Button variant="primary" onClick={handleSave} loading={saving} disabled={!title.trim()}>
                  {isCreate ? 'Create issue' : 'Save changes'}
                </Button>
                {!isCreate && (
                  <Button variant="ghost" onClick={() => { setEditing(false); setError('') }}>Cancel</Button>
                )}
              </div>
            )}
          </>
        )}

        {/* ── Comments tab ── */}
        {tab === 'comments' && !isCreate && !editing && (
          <div className="flex flex-col gap-3">
            {comments.map(c => (
              <div key={c.id} className="flex gap-2.5 group">
                <Avatar name={c.authorName} size={22} />
                <div className="flex-1 min-w-0">
                  <div className="flex items-baseline gap-2 mb-0.5">
                    <span className="text-xs font-medium">{c.authorName}</span>
                    <span className="mono text-xs" style={{ color: 'var(--color-text-muted)' }}>
                      {new Date(c.createdAt).toLocaleDateString()}
                    </span>
                  </div>
                  <p className="text-sm whitespace-pre-wrap" style={{ color: 'var(--color-text)' }}>
                    {c.body}
                  </p>
                </div>
                {user?.id === c.authorId && (
                  <button
                    onClick={() => handleDeleteComment(c.id)}
                    className="opacity-0 group-hover:opacity-100 transition-opacity cursor-pointer flex-shrink-0"
                    style={{ color: 'var(--color-text-muted)' }}
                  >
                    <Trash2 size={13} />
                  </button>
                )}
              </div>
            ))}

            {/* Comment input with mention autocomplete */}
            <div className="relative flex gap-2 items-end pt-2 border-t" style={{ borderColor: 'var(--color-border)' }}>
              <div className="flex-1 relative">
                <Textarea
                  ref={commentRef}
                  value={commentBody}
                  onChange={handleCommentChange}
                  placeholder="Add a comment… Use @Name to mention"
                  rows={2}
                />
                {filteredMembers.length > 0 && (
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
              <Button
                variant="primary"
                size="sm"
                onClick={handlePostComment}
                loading={postingComment}
                disabled={!commentBody.trim()}
              >
                Post
              </Button>
            </div>
          </div>
        )}

        {/* ── Files tab ── */}
        {tab === 'files' && !isCreate && !editing && (
          <div className="flex flex-col gap-3">
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

            <div className="pt-2 border-t" style={{ borderColor: 'var(--color-border)' }}>
              <input ref={fileInputRef} type="file" className="hidden" onChange={handleUploadFile} />
              <Button
                variant="ghost"
                size="sm"
                onClick={() => fileInputRef.current?.click()}
                loading={uploading}
              >
                <Paperclip size={13} /> Attach file
              </Button>
            </div>
          </div>
        )}

        {/* ── History tab ── */}
        {tab === 'history' && !isCreate && !editing && (
          <div className="flex flex-col gap-2">
            {history.length === 0 ? (
              <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>No changes recorded yet.</p>
            ) : (
              history.map(h => (
                <div key={h.id} className="flex gap-2.5 items-start">
                  <div
                    className="flex-shrink-0 rounded-full mt-1.5"
                    style={{ width: 6, height: 6, background: 'var(--color-brand)' }}
                  />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-baseline gap-1.5 flex-wrap">
                      <span className="text-xs font-medium">{h.changedByName}</span>
                      <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        changed <strong>{historyLabel(h.field)}</strong>
                      </span>
                      <span className="mono text-xs ml-auto" style={{ color: 'var(--color-text-muted)' }}>
                        {new Date(h.createdAt).toLocaleDateString()}
                      </span>
                    </div>
                    {(h.oldValue !== undefined || h.newValue !== undefined) && h.field !== 'description' && (
                      <div className="text-xs mt-0.5 flex items-center gap-1" style={{ color: 'var(--color-text-secondary)' }}>
                        {h.oldValue && (
                          <span
                            className="px-1.5 py-0.5 rounded"
                            style={{ background: '#fee2e2', color: '#991b1b', textDecoration: 'line-through' }}
                          >
                            {h.oldValue}
                          </span>
                        )}
                        {h.oldValue && h.newValue && <span>→</span>}
                        {h.newValue && (
                          <span className="px-1.5 py-0.5 rounded" style={{ background: '#dcfce7', color: '#166534' }}>
                            {h.newValue}
                          </span>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              ))
            )}
          </div>
        )}
      </div>
    </div>
  )
}
