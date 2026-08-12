package com.hamstrack.search.parser;

/**
 * Lexical token categories for the HQL query language (see the Advanced Search proposal §4.1).
 *
 * <p>Keywords ({@code AND OR NOT IN IS EMPTY ORDER BY ASC DESC}) are recognized case-insensitively
 * by the {@link Lexer} and emitted as their dedicated token types rather than as {@link #IDENT}.
 * Operators are folded into a single {@link #OP} type; the concrete operator is carried in the
 * token's text.
 */
public enum TokenType {

    /** Field or function name: {@code letter (letter | digit | '_')*}. */
    IDENT,

    /** Quoted string literal (double- or single-quoted), text is the already-unescaped value. */
    STRING,

    /** Numeric literal: {@code '-'? digit+ ('.' digit+)?}. */
    NUMBER,

    /** Comparison / text-match operator: one of {@code = != ~ > < >= <=}. Text carries which. */
    OP,

    /** {@code (} */
    LPAREN,

    /** {@code )} */
    RPAREN,

    /** {@code ,} */
    COMMA,

    // --- keywords (case-insensitive) ---
    AND,
    OR,
    NOT,
    IN,
    IS,
    EMPTY,
    ORDER,
    BY,
    ASC,
    DESC,

    /** Synthetic end-of-input marker; its start offset is the length of the input. */
    EOF
}
