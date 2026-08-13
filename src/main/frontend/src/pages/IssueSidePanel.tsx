import ResizeHandle from '../components/ResizeHandle'
import IssueDetail from './IssueDetail'
import type { IssueType, Status, PriorityOption, ProjectField, TransitionRule } from '../types'

interface Props {
  wsId: string
  projectId: string
  issueNumber: number
  issueTypes: IssueType[]
  statuses: Status[]
  transitions: TransitionRule[]  // workflow rules — gate the inline status editor
  priorities: PriorityOption[]   // the project's offered priorities (from config)
  fields: ProjectField[]         // the project's custom fields (from config)
  onClose: () => void
  /** Open a different issue in the same panel (parent/child navigation). */
  onOpenIssue?: (number: number) => void
  /** Controlled panel width in px (default 440). Owned by the parent so it
   *  survives the key-based remount when switching issues (HD-54). */
  width?: number
  /** Resize bounds + callbacks. When onResize is provided the left-edge drag
   *  handle is rendered (Board); omitted on Backlog → fixed-width, no handle. */
  minWidth?: number
  maxWidth?: () => number
  onResize?: (next: number) => void
  onResizeDragChange?: (dragging: boolean) => void
}

/**
 * Drawer chrome around {@link IssueDetail} (HD-60). Owns only the fixed panel
 * width and the left-edge resize handle; all issue content lives in the shared
 * IssueDetail body so the same view can back a full-page route later (HD-67).
 */
export default function IssueSidePanel({
  wsId, projectId, issueNumber, issueTypes, statuses, transitions, priorities, fields,
  onClose, onOpenIssue, width, minWidth, maxWidth, onResize, onResizeDragChange,
}: Props) {
  const panelWidth = width ?? 440
  const panelStyle: React.CSSProperties = {
    position: 'relative',
    width: panelWidth,
    minWidth: panelWidth,
    height: '100%',
    borderLeft: '1px solid var(--color-border)',
    overflow: 'hidden',
  }

  // Left-edge drag handle — only on the Board (parent supplies onResize + bounds)
  const resizeHandle = onResize && maxWidth && minWidth != null && (
    <ResizeHandle
      side="left"
      size={panelWidth}
      min={minWidth}
      max={maxWidth}
      onResize={onResize}
      onDragChange={onResizeDragChange}
      ariaLabel="Resize issue panel"
    />
  )

  return (
    <div style={panelStyle}>
      {resizeHandle}
      <IssueDetail
        wsId={wsId}
        projectId={projectId}
        issueNumber={issueNumber}
        issueTypes={issueTypes}
        statuses={statuses}
        transitions={transitions}
        priorities={priorities}
        fields={fields}
        onClose={onClose}
        onOpenIssue={onOpenIssue}
        enableExpand
      />
    </div>
  )
}
