package com.hamstrack.search.parser.ast;

import java.util.List;

/**
 * A trailing {@code ORDER BY k1 [ASC|DESC], k2 …} clause (Advanced Search proposal §4.2). The key
 * order is significant (primary sort first). Field names are raw identifiers; sortability is a pass 2
 * concern.
 */
public record OrderBy(List<SortKey> keys) {

    public OrderBy {
        keys = List.copyOf(keys);
    }

    /** Sort direction. Default (unspecified) is {@link #ASC}. */
    public enum Direction { ASC, DESC }

    /** One {@code field [ASC|DESC]} sort key. */
    public record SortKey(String field, Direction direction) {}
}
