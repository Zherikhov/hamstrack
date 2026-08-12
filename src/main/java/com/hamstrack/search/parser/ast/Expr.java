package com.hamstrack.search.parser.ast;

import java.util.List;

/**
 * The boolean/predicate expression tree of an HQL query (Advanced Search proposal §4.2).
 *
 * <p>Sealed so pass 2's compiler can exhaustively pattern-match over the node kinds. The tree is
 * <strong>field-name-agnostic</strong>: field references are raw identifier strings exactly as the
 * user typed them (case preserved). Which fields/operators are legal is decided by pass 2's
 * {@code FieldRegistry}, not here.
 *
 * <p>Node kinds:
 * <ul>
 *   <li>{@link And}, {@link Or}, {@link Not} — boolean composition (precedence {@code NOT > AND > OR}
 *       is resolved by the parser, so the tree shape already encodes grouping);</li>
 *   <li>{@link Comparison} — {@code field OP value};</li>
 *   <li>{@link InList} — {@code field IN (v1, v2, …)};</li>
 *   <li>{@link IsEmpty} — {@code field IS [NOT] EMPTY} ({@link IsEmpty#negated()} = the NOT form).</li>
 * </ul>
 */
public sealed interface Expr permits Expr.And, Expr.Or, Expr.Not,
        Expr.Comparison, Expr.InList, Expr.IsEmpty {

    /** Logical AND of two subexpressions. */
    record And(Expr left, Expr right) implements Expr {}

    /** Logical OR of two subexpressions. */
    record Or(Expr left, Expr right) implements Expr {}

    /** Logical negation. */
    record Not(Expr operand) implements Expr {}

    /**
     * {@code field OP value}. Covers {@code = != > < >= <=} and the text-match {@code ~}
     * (§4.2 {@code textMatch}); the operator is carried in {@link #op()}.
     */
    record Comparison(String field, ComparisonOp op, Value value) implements Expr {}

    /** {@code field IN (v1, …)} — at least one value (guaranteed by the parser). */
    record InList(String field, List<Value> values) implements Expr {
        public InList {
            values = List.copyOf(values);
        }
    }

    /** {@code field IS EMPTY} / {@code field IS NOT EMPTY}. */
    record IsEmpty(String field, boolean negated) implements Expr {}
}
