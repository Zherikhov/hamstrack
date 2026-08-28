package com.hamstrack.search.dto;

import com.hamstrack.common.dto.Paging;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/workspaces/{ws}/search} (Advanced Search proposal §8.1).
 *
 * @param query the HQL query string; required but may be empty ("" → all visible
 *              issues, default sort). Null is treated as empty.
 * @param page  0-based page index; null/negative → 0, refused above
 *              {@link Paging#MAX_PAGE}.
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
        // The index is refused here rather than clamped in the service, because the
        // service computes the offset AFTER the count query has been paid for: the
        // annotation fires during argument resolution, so an out-of-range page costs
        // zero statements. That is what this bound buys — the cheapness of the refusal,
        // not the refusal itself: the offset multiplication is belted by Paging.offsetOf,
        // which answers the same 400 a statement later (HD-163). Before either existed the
        // overflow reached Hibernate as a negative firstResult and 500'd. No @Min: a
        // negative page is coerced to 0 in the service and has always answered 200.
        @Max(Paging.MAX_PAGE) Integer page,
        Integer size) {
    public String queryOrEmpty() {
        return query == null ? "" : query;
    }
}
