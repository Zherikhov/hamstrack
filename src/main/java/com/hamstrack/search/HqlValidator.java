package com.hamstrack.search;

import com.hamstrack.search.parser.ast.Expr;
import com.hamstrack.search.parser.ast.OrderBy;
import com.hamstrack.search.parser.ast.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Semantic validation of a parsed {@link Query} against the {@link FieldRegistry}
 * and the per-request {@link ResolutionContext}'s custom fields (Advanced Search
 * proposal §7.2, HD-52) — the structural half of "known only after parse". Rejects,
 * as {@link HqlSemanticException} (422, {@code errorType=SEMANTIC_ERROR},
 * field-anchored):
 *
 * <ul>
 *   <li>an unknown field — neither a system field nor a custom field reachable by
 *       the caller's visible projects (with a Levenshtein "did you mean …" hint);</li>
 *   <li>a field that is registered but not yet queryable — a distinct
 *       "not yet available" message (no such field today; {@code label} went live
 *       with HD-30);</li>
 *   <li>an operator illegal for the field ({@code ~} on {@code status}; {@code >} on
 *       a SELECT custom field, etc.);</li>
 *   <li>{@code IS [NOT] EMPTY} on a non-nullable field;</li>
 *   <li>an {@code ORDER BY} key that is unknown or not sortable ({@code text}, or any
 *       custom field — custom fields are non-sortable in MVP).</li>
 * </ul>
 *
 * <p><strong>Lookup precedence:</strong> a system field ({@link FieldRegistry}) FIRST;
 * only if the name isn't a system field is it matched against the ctx's visible custom
 * fields (HD-52). A custom field a caller can't see is never in ctx → "unknown field"
 * (never leaked). Value resolvability (no such status/user/option/date) is validated
 * later, during compilation, because it needs the workspace state.
 */
@Component
@RequiredArgsConstructor
public class HqlValidator {

    private final FieldRegistry registry;

    public void validate(Query query, ResolutionContext ctx) {
        query.filter().ifPresent(f -> validateExpr(f, ctx));
        query.orderBy().ifPresent(o -> validateOrderBy(o, ctx));
    }

    private void validateExpr(Expr expr, ResolutionContext ctx) {
        switch (expr) {
            case Expr.And a -> {
                validateExpr(a.left(), ctx);
                validateExpr(a.right(), ctx);
            }
            case Expr.Or o -> {
                validateExpr(o.left(), ctx);
                validateExpr(o.right(), ctx);
            }
            case Expr.Not n -> validateExpr(n.operand(), ctx);
            case Expr.Comparison c -> {
                var sys = systemField(c.field());
                if (sys != null) {
                    if (!sys.allows(c.op())) {
                        throw new HqlSemanticException(
                                "Operator '" + c.op().symbol() + "' is not allowed on field '" + sys.name() + "'",
                                sys.name());
                    }
                } else {
                    var cf = requireCustom(c.field(), ctx);
                    if (!CustomFieldOps.comparisonOps(cf.type()).contains(c.op())) {
                        throw new HqlSemanticException(
                                "Operator '" + c.op().symbol() + "' is not allowed on field '" + cf.key() + "'",
                                cf.key());
                    }
                }
            }
            case Expr.InList in -> {
                var sys = systemField(in.field());
                if (sys != null) {
                    if (!sys.supportsIn()) {
                        throw new HqlSemanticException(
                                "The IN operator is not allowed on field '" + sys.name() + "'", sys.name());
                    }
                } else {
                    var cf = requireCustom(in.field(), ctx);
                    if (!CustomFieldOps.supportsIn(cf.type())) {
                        throw new HqlSemanticException(
                                "The IN operator is not allowed on field '" + cf.key() + "'", cf.key());
                    }
                }
            }
            case Expr.IsEmpty e -> {
                var sys = systemField(e.field());
                if (sys != null) {
                    if (!sys.nullable()) {
                        throw new HqlSemanticException(
                                "Field '" + sys.name() + "' cannot be empty", sys.name());
                    }
                } else {
                    var cf = requireCustom(e.field(), ctx);
                    if (!CustomFieldOps.nullable(cf.type())) {
                        throw new HqlSemanticException(
                                "Field '" + cf.key() + "' cannot be empty", cf.key());
                    }
                }
            }
        }
    }

    private void validateOrderBy(OrderBy orderBy, ResolutionContext ctx) {
        for (var key : orderBy.keys()) {
            var sys = systemField(key.field());
            if (sys != null) {
                if (!sys.sortable()) {
                    throw new HqlSemanticException(
                            "Field '" + sys.name() + "' is not sortable", sys.name());
                }
            } else {
                // Custom fields are non-sortable in MVP.
                var cf = requireCustom(key.field(), ctx);
                throw new HqlSemanticException(
                        "Field '" + cf.key() + "' is not sortable", cf.key());
            }
        }
    }

    /**
     * Resolve a name to a system {@link FieldDescriptor}, or {@code null} if the name
     * isn't a system field. A registered-but-not-available field is still a system
     * name and throws its own "not yet queryable" error here.
     */
    private FieldDescriptor systemField(String name) {
        var found = registry.find(name);
        if (found.isEmpty()) return null;
        var f = found.get();
        if (!f.available()) {
            throw new HqlSemanticException(
                    "Field '" + f.name() + "' is not yet queryable", f.name());
        }
        return f;
    }

    /** Resolve a non-system name to a visible custom field, or throw "unknown field". */
    private CustomFieldMeta requireCustom(String name, ResolutionContext ctx) {
        return ctx.customField(name).orElseThrow(() -> {
            String suggestion = registry.suggest(name).map(s -> " Did you mean '" + s + "'?").orElse("");
            return new HqlSemanticException("Unknown field '" + name + "'." + suggestion, name);
        });
    }
}
