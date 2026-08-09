import { create } from 'zustand'

/** Pre-fill for the create-issue dialog when opened as "create sub-task" from a parent. */
export interface CreateIssuePreset {
  /** Pre-select this issue as the new issue's parent. */
  parentId?: string
  /** The parent's project — pins the modal's project selector so eligibility matches. */
  projectId?: string
  /** The parent's type hierarchy level — the child type defaults to the highest legal level below it. */
  parentLevel?: number
}

interface UiState {
  /** Create-issue dialog visibility — rendered by TopBar, triggerable from any page. */
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
