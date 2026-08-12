package com.hamstrack.search.parser.ast;

import java.util.Optional;

/**
 * The parsed top level of an HQL query: an optional filter expression and an optional
 * {@code ORDER BY} (Advanced Search proposal §4.2).
 *
 * <p>An empty / whitespace-only query string parses to a {@code Query} with neither part present
 * ({@link #isEmpty()} == true), which pass 2 interprets as "all visible issues, default sort".
 *
 * @param filter  the filter expression, or empty for "no predicate"
 * @param orderBy the sort clause, or empty for "default sort"
 */
public record Query(Optional<Expr> filter, Optional<OrderBy> orderBy) {

    public Query {
        filter = filter == null ? Optional.empty() : filter;
        orderBy = orderBy == null ? Optional.empty() : orderBy;
    }

    /** The "all visible issues, default sort" query. */
    public static Query all() {
        return new Query(Optional.empty(), Optional.empty());
    }

    /** True when there is neither a filter nor an order-by (means "all"). */
    public boolean isEmpty() {
        return filter.isEmpty() && orderBy.isEmpty();
    }
}
