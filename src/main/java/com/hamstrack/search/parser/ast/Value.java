package com.hamstrack.search.parser.ast;

import java.util.List;

/**
 * A literal or function-call value on the right-hand side of a predicate. Field-agnostic: pass 2's
 * value resolver interprets these against the target field's data type.
 */
public sealed interface Value permits Value.StringLiteral, Value.NumberLiteral, Value.FunctionCall {

    /**
     * A quoted string literal. {@link #value()} is the already-unescaped content (no surrounding
     * quotes, escapes decoded).
     */
    record StringLiteral(String value) implements Value {}

    /**
     * A numeric literal, preserved as its raw source text (e.g. {@code "-3.5"}) so pass 2 can parse
     * it into the precise numeric type the field needs without lexer-side precision loss.
     */
    record NumberLiteral(String raw) implements Value {}

    /**
     * A function call such as {@code currentUser()}, {@code now()}, {@code startOfWeek()}.
     * MVP functions take no arguments, but {@link #args()} is modeled for forward-compatibility.
     * {@link #name()} preserves source casing; pass 2 matches it case-insensitively.
     */
    record FunctionCall(String name, List<Value> args) implements Value {
        public FunctionCall {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }
}
