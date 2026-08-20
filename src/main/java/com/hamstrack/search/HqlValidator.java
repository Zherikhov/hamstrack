package com.hamstrack.search;

import com.hamstrack.search.FieldResolver.Resolved;
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
 * <p><strong>What a name means is not decided here.</strong> Every name in the tree —
 * in a comparison, in an {@code IN} list, in {@code IS EMPTY}, in {@code ORDER BY} —
 * goes through {@link FieldResolver}, which owns the registry → custom field →
 * retired-alias precedence and the two errors that end it (unknown / not yet
 * queryable). This class only decides what may be <em>done</em> with a resolved field.
 * That split is what makes "validated, then unknown while compiling" impossible: a name
 * this class accepted resolves the same way for anything that later executes it (HD-114;
 * HD-107 §9.2 is the bug that happened when the order was written down more than once).
 *
 * <p>Value resolvability (no such status/user/option/date) is validated later, during
 * compilation, because it needs the workspace state.
 */
@Component
@RequiredArgsConstructor
public class HqlValidator {

    private final FieldResolver fieldResolver;

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
                switch (fieldResolver.resolve(c.field(), ctx)) {
                    case Resolved.SystemField(FieldDescriptor sys) -> {
                        if (!sys.allows(c.op())) {
                            throw new HqlSemanticException(
                                    "Operator '" + c.op().symbol() + "' is not allowed on field '" + sys.name() + "'",
                                    sys.name());
                        }
                    }
                    case Resolved.CustomField(CustomFieldMeta cf) -> {
                        if (!CustomFieldOps.comparisonOps(cf.type()).contains(c.op())) {
                            throw new HqlSemanticException(
                                    "Operator '" + c.op().symbol() + "' is not allowed on field '" + cf.key() + "'",
                                    cf.key());
                        }
                    }
                }
            }
            case Expr.InList in -> {
                switch (fieldResolver.resolve(in.field(), ctx)) {
                    case Resolved.SystemField(FieldDescriptor sys) -> {
                        if (!sys.supportsIn()) {
                            throw new HqlSemanticException(
                                    "The IN operator is not allowed on field '" + sys.name() + "'", sys.name());
                        }
                    }
                    case Resolved.CustomField(CustomFieldMeta cf) -> {
                        if (!CustomFieldOps.supportsIn(cf.type())) {
                            throw new HqlSemanticException(
                                    "The IN operator is not allowed on field '" + cf.key() + "'", cf.key());
                        }
                    }
                }
            }
            case Expr.IsEmpty e -> {
                switch (fieldResolver.resolve(e.field(), ctx)) {
                    case Resolved.SystemField(FieldDescriptor sys) -> {
                        if (!sys.nullable()) {
                            throw new HqlSemanticException(
                                    "Field '" + sys.name() + "' cannot be empty", sys.name());
                        }
                    }
                    case Resolved.CustomField(CustomFieldMeta cf) -> {
                        if (!CustomFieldOps.nullable(cf.type())) {
                            throw new HqlSemanticException(
                                    "Field '" + cf.key() + "' cannot be empty", cf.key());
                        }
                    }
                }
            }
        }
    }

    private void validateOrderBy(OrderBy orderBy, ResolutionContext ctx) {
        for (var key : orderBy.keys()) {
            switch (fieldResolver.resolve(key.field(), ctx)) {
                case Resolved.SystemField(FieldDescriptor sys) -> {
                    if (!sys.sortable()) {
                        throw new HqlSemanticException(
                                "Field '" + sys.name() + "' is not sortable", sys.name());
                    }
                }
                // Custom fields are non-sortable in MVP.
                case Resolved.CustomField(CustomFieldMeta cf) -> throw new HqlSemanticException(
                        "Field '" + cf.key() + "' is not sortable", cf.key());
            }
        }
    }
}
