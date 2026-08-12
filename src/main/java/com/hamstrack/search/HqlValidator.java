package com.hamstrack.search;

import com.hamstrack.search.parser.ast.Expr;
import com.hamstrack.search.parser.ast.OrderBy;
import com.hamstrack.search.parser.ast.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Semantic validation of a parsed {@link Query} against the {@link FieldRegistry}
 * (Advanced Search proposal §7.2) — the structural half of "known only after
 * parse". Rejects, as {@link HqlSemanticException} (422, {@code errorType=
 * SEMANTIC_ERROR}, field-anchored):
 *
 * <ul>
 *   <li>an unknown field (with a Levenshtein "did you mean …" suggestion);</li>
 *   <li>a field that is registered but not yet queryable ({@code label}, custom
 *       keys) — a distinct "not yet available" message;</li>
 *   <li>an operator illegal for the field ({@code ~} on {@code status}, etc.);</li>
 *   <li>{@code IS [NOT] EMPTY} on a non-nullable field;</li>
 *   <li>an {@code ORDER BY} key that is unknown or not sortable ({@code text}).</li>
 * </ul>
 *
 * <p>Value resolvability (no such status/user/date) is validated later, during
 * compilation, because it needs the workspace {@link ResolutionContext}.
 */
@Component
@RequiredArgsConstructor
public class HqlValidator {

    private final FieldRegistry registry;

    public void validate(Query query) {
        query.filter().ifPresent(this::validateExpr);
        query.orderBy().ifPresent(this::validateOrderBy);
    }

    private void validateExpr(Expr expr) {
        switch (expr) {
            case Expr.And a -> {
                validateExpr(a.left());
                validateExpr(a.right());
            }
            case Expr.Or o -> {
                validateExpr(o.left());
                validateExpr(o.right());
            }
            case Expr.Not n -> validateExpr(n.operand());
            case Expr.Comparison c -> {
                var f = requireField(c.field());
                if (!f.allows(c.op())) {
                    throw new HqlSemanticException(
                            "Operator '" + c.op().symbol() + "' is not allowed on field '" + f.name() + "'",
                            f.name());
                }
            }
            case Expr.InList in -> {
                var f = requireField(in.field());
                if (!f.supportsIn()) {
                    throw new HqlSemanticException(
                            "The IN operator is not allowed on field '" + f.name() + "'", f.name());
                }
            }
            case Expr.IsEmpty e -> {
                var f = requireField(e.field());
                if (!f.nullable()) {
                    throw new HqlSemanticException(
                            "Field '" + f.name() + "' cannot be empty", f.name());
                }
            }
        }
    }

    private void validateOrderBy(OrderBy orderBy) {
        for (var key : orderBy.keys()) {
            var f = requireField(key.field());
            if (!f.sortable()) {
                throw new HqlSemanticException(
                        "Field '" + f.name() + "' is not sortable", f.name());
            }
        }
    }

    /** Resolve a field or throw the unknown/not-available semantic error. */
    private FieldDescriptor requireField(String name) {
        var found = registry.find(name);
        if (found.isEmpty()) {
            String suggestion = registry.suggest(name).map(s -> " Did you mean '" + s + "'?").orElse("");
            throw new HqlSemanticException("Unknown field '" + name + "'." + suggestion, name);
        }
        var f = found.get();
        if (!f.available()) {
            throw new HqlSemanticException(
                    "Field '" + f.name() + "' is not yet queryable", f.name());
        }
        return f;
    }
}
