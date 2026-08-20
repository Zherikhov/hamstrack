package com.hamstrack.report.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/workspaces/{wsId}/search/insights} (reports-proposal §2.6, §4.3).
 *
 * <p><strong>The dataset is the query, not a report parameter.</strong> {@code query} is the same
 * HQL string {@code POST …/search} takes and is parsed, validated, resolved and compiled by the
 * same machinery — which is the entire argument for this feature over a widget dashboard: the
 * panel cannot disagree with the result list underneath it, because there is one dataset and one
 * predicate. Empty means "everything visible", exactly as it does on search.
 *
 * <p><strong>Every field is a boxed reference type</strong>, and the three enum-ish ones are
 * {@link String} rather than enums. Both are deliberate:
 * <ul>
 *   <li>boxed, because Jackson 3 enables {@code FAIL_ON_NULL_FOR_PRIMITIVES} and a body that
 *       omits a primitive field 400s with {@code "Failed to read request"} (CLAUDE.md gotcha);</li>
 *   <li>strings, because binding an enum directly makes an unknown value a <em>deserialization</em>
 *       failure — a 400 whose detail is "Failed to read request" and which names neither the
 *       offending field nor the accepted values. On this path that would also be the odd one out:
 *       every other refusal under {@code /search} is a <strong>422 naming the field</strong>, which
 *       is what the SPA's error surface is built to render. So the tokens are resolved in the
 *       service and an unknown one is a 422 that says which parameter and what it accepts. (This is
 *       a considered divergence from {@code ReportController}'s GET endpoints, where
 *       {@code ?measure=BOGUS} is a 400 raised by Spring's argument binding before any handler
 *       runs — same idea, different mechanism, because a query parameter is bound and a JSON body
 *       is read.)</li>
 * </ul>
 *
 * @param query   HQL; {@code null} or empty means every issue the caller can see
 * @param measure {@code COUNT} | {@code POINTS} | {@code NONE}, case-insensitive; null → {@code COUNT}
 * @param slice   the x axis, one of {@link InsightsDimension}, case-insensitive; null → {@code STATUS}
 * @param segment the optional colour dimension; null/blank → not segmented
 */
public record InsightsRequest(
        // Same cap, for the same reason, as SearchRequest#query — reject an oversized body at
        // binding rather than materialising a multi-MB string. The two numbers must stay in sync
        // with each other and with HqlParser.MAX_QUERY_LENGTH.
        @Size(max = 2000) String query,
        // 32, and the reason is not "an enum name is short" (the longest is 9): these three are the
        // fields ECHOED BACK inside the 422 that refuses them. Unbounded, a 20 MB token binds
        // (Jackson 3 allows 20 000 000 chars), trim().toUpperCase() copies it twice, the message
        // builds a third and a ~20 MB problem+json body carries it out — tens of MB of transient
        // heap per request, on a path whose budget is per minute and whose body nothing upstream
        // bounds. Rejected at binding as a 400, before any of that is allocated. The service
        // truncates what it echoes as well (InsightsService#echo): two halves, because a cap here
        // protects only the fields it is written on, while the truncation protects the message
        // whatever reaches it.
        @Size(max = 32) String measure,
        @Size(max = 32) String slice,
        @Size(max = 32) String segment) {

    /** The HQL to run; never null. */
    public String queryOrEmpty() {
        return query == null ? "" : query;
    }
}
