import { create } from 'zustand'

interface UiState {
  /** Monotonic counter — BoardPage opens the create panel when it changes. */
  createIssueSignal: number
  requestCreateIssue: () => void
}

export const useUiStore = create<UiState>(set => ({
  createIssueSignal: 0,
  requestCreateIssue: () => set(s => ({ createIssueSignal: s.createIssueSignal + 1 })),
}))
