import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import IssueFullPage from './IssueFullPage'
import { ApiResponseError } from '../api'
import { useAuthStore } from '../auth'
import type { User } from '../types'

/**
 * HD-97, second variant — the route exists but the RESOURCE behind it does not.
 *
 * The two failures below look identical from a component's point of view (the
 * query rejected) and must not look identical to the user:
 *
 *  - **404** — "gone" and "not yours" are deliberately the same answer from the
 *    server (the tenancy rule: never reveal existence via a 403), so one screen
 *    covers both, and it must not claim anything exists.
 *  - **anything else** — an outage. Telling somebody "we couldn't find that
 *    issue" while the backend is down sends them hunting for a deletion that
 *    never happened, and it is the reason the two states are pinned apart here
 *    rather than folded into one "error" branch.
 *
 * `IssueDetail` is stubbed: it is a large component with its own network needs
 * and none of it is reachable on either path under test.
 */

const WS_ID = 'w1'
const PROJECT_ID = 'p1'
const ME: User = { id: 'u-me', email: 'me@example.com', displayName: 'Me Myself' }

const apiGetProjectConfigMock = vi.fn()

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal<typeof import('../api')>()),
  // ApiResponseError is deliberately NOT stubbed: the page branches on
  // `error instanceof ApiResponseError`, so a look-alike class here would make
  // the 404 branch unreachable and the test would pass for the wrong reason.
  apiGetProjectConfig: () => apiGetProjectConfigMock(),
}))

vi.mock('./IssueDetail', () => ({ default: () => <div data-testid="issue-detail" /> }))

beforeEach(() => {
  localStorage.clear()
  apiGetProjectConfigMock.mockReset()
  useAuthStore.setState({ user: ME, accessToken: 'test-token', initialized: true })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/w/${WS_ID}/p/${PROJECT_ID}/issues/7`]}>
        <Routes>
          <Route path="/w/:wsId/p/:projectId/issues/:number" element={<IssueFullPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('IssueFullPage — a 404 renders the not-found screen (HD-97)', () => {
  it('shows the resource variant, worded without confirming the issue exists', async () => {
    apiGetProjectConfigMock.mockRejectedValue(new ApiResponseError(404, 'Not found'))
    renderPage()

    expect(await screen.findByText(/couldn.t find that issue/i)).toBeInTheDocument()
    expect(screen.getByText('404')).toBeInTheDocument()
    // Covers deleted AND not-shared without stating which.
    expect(screen.getByText(/isn.t shared with your account/i)).toBeInTheDocument()
    // The page contributes its own way back, and it is a real link.
    expect(screen.getByRole('link', { name: 'Go to board' }))
      .toHaveAttribute('href', `/w/${WS_ID}/p/${PROJECT_ID}`)
    expect(screen.queryByTestId('issue-detail')).toBeNull()
    // The screen is never empty — the symptom this ticket was filed for.
    expect(document.body.textContent?.trim()).not.toBe('')
  })

  it('keeps a NON-404 failure visibly different — an outage is not a not-found', async () => {
    apiGetProjectConfigMock.mockRejectedValue(new ApiResponseError(500, 'Internal Server Error'))
    renderPage()

    expect(await screen.findByText(/couldn.t be loaded/i)).toBeInTheDocument()
    expect(screen.queryByText(/couldn.t find that issue/i)).toBeNull()
    expect(screen.queryByText('404')).toBeNull()
  })

  it('treats a transport failure (no status at all) as an outage too', async () => {
    // `fetch` rejecting outright is a plain Error, not an ApiResponseError — the
    // `instanceof` half of the guard, which a `error.status === 404` test alone
    // would not exercise.
    apiGetProjectConfigMock.mockRejectedValue(new TypeError('Failed to fetch'))
    renderPage()

    expect(await screen.findByText(/couldn.t be loaded/i)).toBeInTheDocument()
    expect(screen.queryByText(/couldn.t find that issue/i)).toBeNull()
  })
})
