package com.hamstrack.search.dto;

import java.util.List;

/**
 * Response of {@code GET /api/workspaces/{ws}/search/suggest?field=&q=} (Advanced
 * Search proposal §8.2, §16.4) — a bounded server-side prefix typeahead for
 * user-valued fields ({@code assignee}/{@code reporter}), so large workspaces never
 * ship thousands of members per session.
 *
 * @param field       the queried field (echoed back)
 * @param suggestions matching workspace members (capped), most relevant first
 */
public record SuggestResponse(String field, List<Suggestion> suggestions) {

    /**
     * One typeahead entry.
     *
     * @param label the display name shown in the dropdown
     * @param value the resolvable value the SPA inserts (the member's email)
     */
    public record Suggestion(String label, String value) {}
}
