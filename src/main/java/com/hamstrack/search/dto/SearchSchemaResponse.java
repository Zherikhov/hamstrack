package com.hamstrack.search.dto;

import java.util.List;
import java.util.Map;

/**
 * Response of {@code GET /api/workspaces/{ws}/search/schema} (Advanced Search
 * proposal §8.2) — drives the SPA's autocomplete. Static field/operator schema plus
 * the small, embeddable value picklists (statuses/types/priorities reachable by the
 * caller's visible projects). The member list is deliberately NOT embedded; user
 * value autocomplete uses the bounded {@code /search/suggest} typeahead (§16.4).
 *
 * @param fields   one entry per live queryable field
 * @param keywords the HQL keywords (for keyword autocomplete)
 * @param values   value picklists keyed by the field's {@code valueSuggest} token
 *                 (STATUS/TYPE/PRIORITY); USER is intentionally absent (see /suggest)
 * @param insights what the Insights panel on this page may be asked for (HD-140 R6) —
 *                 published here rather than hardcoded in the SPA because it is
 *                 CAPABILITY-NARROWED, on exactly the same terms as {@code fields}
 */
public record SearchSchemaResponse(
        List<Field> fields,
        List<String> keywords,
        Map<String, List<ValueOption>> values,
        Insights insights
) {
    /**
     * The Insights panel's vocabulary: the measures and grouping dimensions worth OFFERING to
     * this caller (reports-proposal §2.6).
     *
     * <p><strong>Suggestions, not a contract — the same sentence that governs {@code fields},
     * and it means the same thing here.</strong> {@code SPRINT} is dropped when no visible
     * project plans in sprints and {@code POINTS} when none estimates, mirroring how the
     * {@code sprint} and {@code storyPoints} FIELDS are dropped from the list above. What is
     * omitted still resolves: {@code POST …/search/insights} answers 200 for a dimension that is
     * not in this list, because a capability is a presentation preference and may never change a
     * status code (Rule A). A saved panel state must not break because a curator flipped a
     * project toggle.
     *
     * @param measures   {@code COUNT} | {@code POINTS} | {@code NONE}, narrowed as above
     * @param dimensions the grouping dimensions, narrowed as above. One list serves both the x
     *                   axis ({@code slice}) and the colour ({@code segment}) — grouping is
     *                   symmetric — with the single rule that the two must differ
     */
    public record Insights(List<String> measures, List<String> dimensions) {}

    /**
     * A queryable field's public schema.
     *
     * @param name      HQL field name
     * @param type      data-type family (ENUM_REF/USER_REF/DATE/…)
     * @param operators the operator symbols legal on this field (incl. IN / IS EMPTY
     *                  rendered as tokens)
     * @param nullable  whether {@code IS [NOT] EMPTY} is allowed
     * @param sortable  whether the field may appear in ORDER BY
     * @param valueSuggest the value-source token (or null when there is no picklist)
     * @param functions zero-arg functions offered for the field
     */
    public record Field(
            String name,
            String type,
            List<String> operators,
            boolean nullable,
            boolean sortable,
            String valueSuggest,
            List<String> functions
    ) {}

    /** A picklist entry: a display label and an optional resolvable value (email for USER). */
    public record ValueOption(String label, String value) {
        public static ValueOption label(String label) {
            return new ValueOption(label, null);
        }
    }
}
