package com.hamstrack.search.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/workspaces/{ws}/search} (Advanced Search proposal §8.1).
 *
 * @param query the HQL query string; required but may be empty ("" → all visible
 *              issues, default sort). Null is treated as empty.
 * @param page  0-based page index; null/negative → 0.
 * @param size  page size; null → the default (50), clamped to [1, 100] server-side.
 *
 * <p>All fields are boxed (never primitive int) so a body omitting {@code page}/
 * {@code size} does not 400 under Jackson 3's {@code FAIL_ON_NULL_FOR_PRIMITIVES}
 * (CLAUDE.md gotcha). There is no separate sort param — sorting is expressed in the
 * HQL {@code ORDER BY} (§8.1).
 */
public record SearchRequest(
        // Reject an oversized body at binding (clean 400) rather than materializing a
        // multi-MB string; matches HqlParser.MAX_QUERY_LENGTH (the parser's own cap is
        // kept as defense-in-depth — the numbers must stay in sync).
        @Size(max = 2000) String query,
        Integer page,
        Integer size) {
    public String queryOrEmpty() {
        return query == null ? "" : query;
    }
}
