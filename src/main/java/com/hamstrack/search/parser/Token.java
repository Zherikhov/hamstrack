package com.hamstrack.search.parser;

/**
 * A single lexeme produced by the {@link Lexer}.
 *
 * @param type   the token category
 * @param text   the token's text. For {@link TokenType#STRING} this is the <em>unescaped</em>
 *               string content (without the surrounding quotes); for every other type it is the
 *               raw source slice.
 * @param start  0-based character offset of the token's first character in the source query
 * @param length span length in source characters (for {@code STRING} this is the full quoted span
 *               including quotes and escape backslashes, so error highlights cover the real text)
 */
public record Token(TokenType type, String text, int start, int length) {

    /** Convenience: offset one past the last source character of this token. */
    public int end() {
        return start + length;
    }
}
