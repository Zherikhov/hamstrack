package com.hamstrack.search;

/**
 * A <em>semantic</em> HQL failure — detected only AFTER a successful parse, during
 * registry validation or value resolution (Advanced Search proposal §7.2). Unknown
 * field, illegal operator for a field, {@code IS EMPTY} on a non-nullable field,
 * non-sortable {@code ORDER BY} key, and every unresolvable value (no such
 * status/type/priority/user, malformed date) map to this type.
 *
 * <p>Rendered as a 422 {@code ProblemDetail} with {@code errorType:"SEMANTIC_ERROR"}
 * and, when known, {@code field} and {@code position} custom properties — mirroring
 * the parse-error shape so the SPA highlights the same way. Kept free of any Spring
 * dependency; {@code GlobalExceptionHandler} maps it.
 */
public class HqlSemanticException extends RuntimeException {

    /** The offending HQL field name, or {@code null} when the error isn't field-anchored. */
    private final String field;
    /** 0-based char offset into the query string for the highlight, or {@code -1} if unknown. */
    private final int position;

    public HqlSemanticException(String message) {
        this(message, null, -1);
    }

    public HqlSemanticException(String message, String field) {
        this(message, field, -1);
    }

    public HqlSemanticException(String message, String field, int position) {
        super(message);
        this.field = field;
        this.position = position;
    }

    /** The stable {@code errorType} value for the ProblemDetail (always {@code "SEMANTIC_ERROR"}). */
    public String getErrorType() {
        return "SEMANTIC_ERROR";
    }

    /** The offending field name, or {@code null}. */
    public String getField() {
        return field;
    }

    /** 0-based char offset into the query string, or {@code -1} when unknown. */
    public int getPosition() {
        return position;
    }
}
