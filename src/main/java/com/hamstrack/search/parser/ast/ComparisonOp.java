package com.hamstrack.search.parser.ast;

import java.util.Map;

/**
 * The scalar comparison / text-match operators of HQL (Advanced Search proposal §4.2).
 * {@code IN} and {@code IS [NOT] EMPTY} are modeled as their own predicate nodes rather than as
 * operators here.
 */
public enum ComparisonOp {
    EQ("="),
    NEQ("!="),
    GT(">"),
    LT("<"),
    GTE(">="),
    LTE("<="),
    /** Text contains ({@code ~}) — compiles to ILIKE in pass 2. */
    MATCH("~");

    private final String symbol;

    ComparisonOp(String symbol) {
        this.symbol = symbol;
    }

    /** The source symbol, e.g. {@code ">="}. */
    public String symbol() {
        return symbol;
    }

    private static final Map<String, ComparisonOp> BY_SYMBOL = Map.of(
            "=", EQ,
            "!=", NEQ,
            ">", GT,
            "<", LT,
            ">=", GTE,
            "<=", LTE,
            "~", MATCH
    );

    /** Look up an operator by its source symbol, or {@code null} if unknown. */
    public static ComparisonOp fromSymbol(String symbol) {
        return BY_SYMBOL.get(symbol);
    }
}
