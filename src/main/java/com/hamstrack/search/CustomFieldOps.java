package com.hamstrack.search;

import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.search.parser.ast.ComparisonOp;

import java.util.List;
import java.util.Set;

/**
 * Allowed HQL operators for each custom-field {@link FieldType} (HD-52 §3), the
 * single source of truth for both {@link HqlValidator} (rejects an illegal op) and
 * the {@code /schema} operator list. Kept separate from {@link FieldRegistry} because
 * custom fields are per-request, not statically registered.
 *
 * <p>Per-type matrix:
 * <ul>
 *   <li>TEXT/TEXTAREA/URL — {@code = != ~} + IS [NOT] EMPTY</li>
 *   <li>NUMBER/DATE — {@code = != > < >= <=} + IS [NOT] EMPTY</li>
 *   <li>SELECT — {@code = !=} + IN + IS [NOT] EMPTY</li>
 *   <li>MULTI_SELECT — {@code =} (contains) + IN (any-of) + IS [NOT] EMPTY</li>
 *   <li>USER — {@code = !=} + IN + IS [NOT] EMPTY (+ {@code currentUser()})</li>
 *   <li>CHECKBOX — {@code =}</li>
 * </ul>
 * Custom fields are non-sortable in MVP (never appear in ORDER BY).
 */
public final class CustomFieldOps {

    private CustomFieldOps() {}

    /** The comparison operators (excluding IN / IS EMPTY) legal on a custom field. */
    public static Set<ComparisonOp> comparisonOps(FieldType type) {
        return switch (type) {
            case TEXT, TEXTAREA, URL ->
                    Set.of(ComparisonOp.EQ, ComparisonOp.NEQ, ComparisonOp.MATCH);
            case NUMBER, DATE ->
                    Set.of(ComparisonOp.EQ, ComparisonOp.NEQ,
                            ComparisonOp.GT, ComparisonOp.LT, ComparisonOp.GTE, ComparisonOp.LTE);
            case SELECT, USER -> Set.of(ComparisonOp.EQ, ComparisonOp.NEQ);
            case MULTI_SELECT -> Set.of(ComparisonOp.EQ);   // = means "contains"
            case CHECKBOX -> Set.of(ComparisonOp.EQ);
        };
    }

    /** Whether {@code field IN (…)} is legal for the type. */
    public static boolean supportsIn(FieldType type) {
        return switch (type) {
            case SELECT, MULTI_SELECT, USER -> true;
            default -> false;
        };
    }

    /**
     * Whether {@code field IS [NOT] EMPTY} is legal. Every custom field is
     * absence-bearing (no row = empty), so all but CHECKBOX support it. CHECKBOX
     * is boolean-only in MVP (a present false is distinct from empty; we keep it
     * simple and disallow EMPTY, matching the {@code =}-only matrix).
     */
    public static boolean nullable(FieldType type) {
        return type != FieldType.CHECKBOX;
    }

    /** Zero-arg functions offered for the type (USER → {@code currentUser()}). */
    public static List<String> functions(FieldType type) {
        return type == FieldType.USER ? List.of("currentUser()") : List.of();
    }
}
