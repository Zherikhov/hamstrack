package com.hamstrack.search.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hand-written HQL lexer (Advanced Search proposal §4.1). Turns a query string into a flat
 * {@link List} of {@link Token}s terminated by an {@link TokenType#EOF} token.
 *
 * <p>Recognizes: identifiers, case-insensitive keywords, single/double-quoted strings with
 * {@code \"}, {@code \'}, {@code \\} escapes, signed/decimal numbers, the operators
 * {@code = != ~ > < >= <=}, parentheses and commas. Any other input character, an unterminated
 * string, or an illegal escape raises an {@link HqlParseException} with the exact offset.
 *
 * <p>The lexer is field-name-agnostic: {@code IDENT} tokens carry the raw (case-preserving) text;
 * the parser/registry normalize field names later.
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("AND", TokenType.AND),
            Map.entry("OR", TokenType.OR),
            Map.entry("NOT", TokenType.NOT),
            Map.entry("IN", TokenType.IN),
            Map.entry("IS", TokenType.IS),
            Map.entry("EMPTY", TokenType.EMPTY),
            Map.entry("ORDER", TokenType.ORDER),
            Map.entry("BY", TokenType.BY),
            Map.entry("ASC", TokenType.ASC),
            Map.entry("DESC", TokenType.DESC)
    );

    private final String src;
    private int pos;

    public Lexer(String src) {
        this.src = src == null ? "" : src;
    }

    /** Tokenize the whole input. Convenience over constructing a {@code Lexer} directly. */
    public static List<Token> tokenize(String src) {
        return new Lexer(src).lex();
    }

    public List<Token> lex() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (pos >= src.length()) {
                tokens.add(new Token(TokenType.EOF, "", src.length(), 0));
                return tokens;
            }
            tokens.add(nextToken());
        }
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private Token nextToken() {
        char c = src.charAt(pos);

        switch (c) {
            case '(' -> { return single(TokenType.LPAREN); }
            case ')' -> { return single(TokenType.RPAREN); }
            case ',' -> { return single(TokenType.COMMA); }
            case '~' -> { return single(TokenType.OP); }
            case '=' -> { return single(TokenType.OP); }
            case '"', '\'' -> { return string(c); }
            case '!' -> {
                if (peek(1) == '=') {
                    return op2();
                }
                throw new HqlParseException(HqlParseException.Kind.BAD_CHARACTER,
                        "Unexpected character '!' at position " + pos + " (did you mean '!='?)",
                        pos, 1, "!");
            }
            case '>', '<' -> {
                if (peek(1) == '=') {
                    return op2();
                }
                return single(TokenType.OP);
            }
            default -> {
                // NOTE: '-' only begins a NUMBER here (unary minus on a numeric literal). It is
                // never a standalone operator in the grammar, so a lone '-' falls through to the
                // bad-character path below.
                if (c == '-' && Character.isDigit(peek(1))) {
                    return number();
                }
                if (Character.isDigit(c)) {
                    return number();
                }
                if (isIdentStart(c)) {
                    return identifierOrKeyword();
                }
                throw new HqlParseException(HqlParseException.Kind.BAD_CHARACTER,
                        "Unexpected character '" + c + "' at position " + pos,
                        pos, 1, String.valueOf(c));
            }
        }
    }

    private Token single(TokenType type) {
        Token t = new Token(type, String.valueOf(src.charAt(pos)), pos, 1);
        pos++;
        return t;
    }

    /** Two-character operator ({@code != >= <=}). */
    private Token op2() {
        Token t = new Token(TokenType.OP, src.substring(pos, pos + 2), pos, 2);
        pos += 2;
        return t;
    }

    private Token number() {
        int start = pos;
        if (src.charAt(pos) == '-') {
            pos++;
        }
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            pos++;
        }
        if (pos < src.length() && src.charAt(pos) == '.') {
            // only a fractional part with at least one digit is valid: digit+ '.' digit+
            if (Character.isDigit(peek(1))) {
                pos++; // consume '.'
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            // NOTE: a trailing '.' with no following digit (e.g. "5.") is left unconsumed; the '.'
            // then surfaces as a BAD_CHARACTER at its own offset, which is the clearest message.
        }
        return new Token(TokenType.NUMBER, src.substring(start, pos), start, pos - start);
    }

    private Token identifierOrKeyword() {
        int start = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        String text = src.substring(start, pos);
        TokenType kw = KEYWORDS.get(text.toUpperCase(Locale.ROOT));
        TokenType type = kw != null ? kw : TokenType.IDENT;
        return new Token(type, text, start, pos - start);
    }

    private Token string(char quote) {
        int start = pos;
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\\') {
                char next = peek(1);
                // NOTE: only \" \' \\ are legal escapes (§4.2). Any other \x is a parse error.
                if (next == '"' || next == '\'' || next == '\\') {
                    sb.append(next);
                    pos += 2;
                    continue;
                }
                throw new HqlParseException(HqlParseException.Kind.ILLEGAL_ESCAPE,
                        "Illegal escape sequence '\\" + (next == '\0' ? "" : next)
                                + "' at position " + pos + " (only \\\" \\' \\\\ are allowed)",
                        pos, 2, "\\" + (next == '\0' ? "" : next));
            }
            if (c == quote) {
                pos++; // closing quote
                int length = pos - start;
                return new Token(TokenType.STRING, sb.toString(), start, length);
            }
            sb.append(c);
            pos++;
        }
        throw new HqlParseException(HqlParseException.Kind.UNTERMINATED_STRING,
                "Unterminated string starting at position " + start,
                start, src.length() - start, null);
    }

    private char peek(int ahead) {
        int i = pos + ahead;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
