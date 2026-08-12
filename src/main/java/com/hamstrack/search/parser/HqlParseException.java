package com.hamstrack.search.parser;

/**
 * A lexer/parser failure in the HQL front-end. Carries everything pass 2 needs to render the
 * {@code ProblemDetail} described in the Advanced Search proposal §7.1 (422 {@code PARSE_ERROR}
 * with a highlight span):
 *
 * <ul>
 *   <li>{@link #getMessage()} — human-readable detail (the {@code detail} field),</li>
 *   <li>{@link #getPosition()} — 0-based char offset of the error into the query string,</li>
 *   <li>{@link #getLength()} — highlight span length (best-effort; may be 0),</li>
 *   <li>{@link #getToken()} — the offending lexeme when available (may be {@code null}),</li>
 *   <li>{@link #getErrorType()} — a stable discriminator for the specific failure.</li>
 * </ul>
 *
 * <p>This type is intentionally free of any Spring dependency and is NOT wired into
 * {@code GlobalExceptionHandler} in this pass — pass 2 (the search endpoint) maps it to a
 * {@code ProblemDetail}. Its {@link #errorType} constant is always {@code PARSE_ERROR} at the
 * {@code errorType} JSON level; {@link #getErrorType()} additionally exposes the finer-grained
 * {@link Kind} so the endpoint (or tests) can distinguish causes without string-matching messages.
 */
public class HqlParseException extends RuntimeException {

    /** Fine-grained cause discriminator. All map to {@code errorType:"PARSE_ERROR"} in the API. */
    public enum Kind {
        BAD_CHARACTER,
        UNTERMINATED_STRING,
        ILLEGAL_ESCAPE,
        UNEXPECTED_TOKEN,
        UNQUOTED_VALUE,
        UNBALANCED_PARENS,
        EMPTY_IN_LIST,
        TRAILING_INPUT,
        ORDER_BY_NOT_LAST,
        QUERY_TOO_LONG,
        DEPTH_LIMIT_EXCEEDED,
        IN_LIST_TOO_LARGE,
        TOO_MANY_SORT_KEYS,
        TOO_MANY_PREDICATES
    }

    private final Kind kind;
    private final int position;
    private final int length;
    private final String token;

    public HqlParseException(Kind kind, String message, int position, int length, String token) {
        super(message);
        this.kind = kind;
        this.position = position;
        this.length = length;
        this.token = token;
    }

    /** The stable {@code errorType} value for the ProblemDetail (always {@code "PARSE_ERROR"}). */
    public String getErrorType() {
        return "PARSE_ERROR";
    }

    /** Finer-grained cause, for the endpoint/tests. */
    public Kind getKind() {
        return kind;
    }

    /** 0-based char offset into the query string. */
    public int getPosition() {
        return position;
    }

    /** Highlight span length (best-effort; may be 0). */
    public int getLength() {
        return length;
    }

    /** Offending lexeme, or {@code null} when not applicable. */
    public String getToken() {
        return token;
    }
}
