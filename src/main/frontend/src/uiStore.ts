import { create } from 'zustand'

interface UiState {
  /** Create-issue dialog visibility — rendered by TopBar, triggerable from any page. */
  createIssueOpen: boolean
  openCreateIssue: () => void
  closeCreateIssue: () => void
}

export const useUiStore = create<UiState>(set => ({
  createIssueOpen: false,
  openCreateIssue: () => set({ createIssueOpen: true }),
  closeCreateIssue: () => set({ createIssueOpen: false }),
}))
