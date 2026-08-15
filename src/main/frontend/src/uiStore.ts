import { create } from 'zustand'

/**
 * Pre-fill for the create-issue dialog — e.g. "create sub-task" from a parent, or the
 * board's per-column quick add. Every field is a default the user can still change.
 */
export interface CreateIssuePreset {
  /** Pre-select this issue as the new issue's parent. */
  parentId?: string
  /** The parent's project — pins the modal's project selector so eligibility matches. */
  projectId?: string
  /** The parent's type hierarchy level — the child type defaults to the highest legal level below it. */
  parentLevel?: number
  /**
   * Pre-select this status (board column quick-add). Only meaningful together with
   * `projectId` — statuses are per-project taxonomy, so the modal drops it as soon
   * as the user switches project.
   */
  statusId?: string
}

interface UiState {
  /** Create-issue dialog visibility — rendered by AppShell, triggerable from any page. */
  createIssueOpen: boolean
  /** Optional pre-fill (e.g. "create sub-task"); undefined = a blank create. */
  createIssuePreset?: CreateIssuePreset
  openCreateIssue: (preset?: CreateIssuePreset) => void
  closeCreateIssue: () => void
}

export const useUiStore = create<UiState>(set => ({
  createIssueOpen: false,
  createIssuePreset: undefined,
  openCreateIssue: preset => set({ createIssueOpen: true, createIssuePreset: preset }),
  closeCreateIssue: () => set({ createIssueOpen: false, createIssuePreset: undefined }),
}))
