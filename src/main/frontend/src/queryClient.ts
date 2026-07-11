import { QueryClient } from '@tanstack/react-query'

// Module-level so auth code can wipe the cache on identity changes —
// cached data must never survive a user switch
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
    },
  },
})
