package com.hamstrack.search;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.search.parser.ast.ComparisonOp;
import com.hamstrack.search.parser.ast.Expr;
import com.hamstrack.search.parser.ast.OrderBy;
import com.hamstrack.search.parser.ast.Query;
import com.hamstrack.search.parser.ast.Value;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Compiles a validated {@link Query} AST into JPA Criteria queries against the
 * {@code issues} table (Advanced Search proposal §3a, §8.1), using <strong>bound
 * parameters only</strong> — no user text ever reaches SQL as a string, so
 * injection is impossible by construction (§9). Precedence is already encoded in
 * the tree.
 *
 * <p><strong>Tenancy invariant:</strong> {@link SearchScope#scopePredicate} is
 * ALWAYS ANDed as the outermost conjunction (both the page and count query). The
 * parsed predicate is nested strictly inside it, so no parsed token can widen or
 * escape the workspace/visible-project boundary. All scope values are derived
 * server-side from the authenticated actor.
 *
 * <p>The page query fetch-joins the five ToOne associations
 * (type/status/priority/assignee/reporter) like {@code IssueRepository
 * .findByProjectFiltered} to avoid N+1; parent summaries and roll-ups are batched
 * by the service after the page is fetched (never fetch-joined — Cartesian). The
 * count query carries the same predicate with no fetches or sort.
 */
@Component
@RequiredArgsConstructor
public class HqlCompiler {

    private final EntityManager em;
    private final FieldRegistry registry;
    private final HqlValueResolver valueResolver;
    private final HqlParentResolver parentResolver;
    private final SearchScope searchScope;

    /**
     * Build the page query: scope-ANDed predicate + ToOne fetch joins + ORDER BY
     * (from the AST, else the default stable sort). {@code offset}/{@code limit}
     * are applied by the caller via {@code TypedQuery} for LIMIT/OFFSET.
     */
    public CriteriaQuery<Issue> buildPageQuery(Query query, User actor, Workspace ws, ResolutionContext ctx) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Issue> cq = cb.createQuery(Issue.class);
        Root<Issue> root = cq.from(Issue.class);

        // Fetch-join the ToOne assocs IssueResponse renders (avoids 1 + ~5N).
        root.fetch("type", jakarta.persistence.criteria.JoinType.LEFT);
        root.fetch("status", jakarta.persistence.criteria.JoinType.LEFT);
        root.fetch("priority", jakarta.persistence.criteria.JoinType.LEFT);
        root.fetch("assignee", jakarta.persistence.criteria.JoinType.LEFT);
        root.fetch("reporter", jakarta.persistence.criteria.JoinType.LEFT);

        // No distinct: only ToOne assocs are fetch-joined (they never multiply rows),
        // and DISTINCT would forbid ORDER BY on a joined column (priority.position).
        cq.select(root);
        cq.where(fullPredicate(query, root, cb, actor, ws, ctx));
        cq.orderBy(orderBy(query, root, cb));
        return cq;
    }

    /** Build the count query: same scope-ANDed predicate, no fetch/sort (§8.1). */
    public CriteriaQuery<Long> buildCountQuery(Query query, User actor, Workspace ws, ResolutionContext ctx) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Issue> root = cq.from(Issue.class);
        cq.select(cb.count(root));
        cq.where(fullPredicate(query, root, cb, actor, ws, ctx));
        return cq;
    }

    // ---- predicate assembly ----

    /** Scope predicate ANDed OUTERMOST with the (optional) parsed filter. */
    private Predicate fullPredicate(Query query, Root<Issue> root, CriteriaBuilder cb,
                                    User actor, Workspace ws, ResolutionContext ctx) {
        Predicate scope = searchScope.scopePredicate(actor, ws, root, cb);
        return query.filter()
                .map(f -> cb.and(scope, compile(f, root, cb, ctx)))
                .orElse(scope);
    }

    private Predicate compile(Expr expr, Root<Issue> root, CriteriaBuilder cb, ResolutionContext ctx) {
        return switch (expr) {
            case Expr.And a -> cb.and(compile(a.left(), root, cb, ctx), compile(a.right(), root, cb, ctx));
            case Expr.Or o -> cb.or(compile(o.left(), root, cb, ctx), compile(o.right(), root, cb, ctx));
            case Expr.Not n -> cb.not(compile(n.operand(), root, cb, ctx));
            case Expr.Comparison c -> comparison(c, root, cb, ctx);
            case Expr.InList in -> inList(in, root, cb, ctx);
            case Expr.IsEmpty e -> isEmpty(e, root, cb);
        };
    }

    private FieldDescriptor field(String name) {
        // Validated already, but re-resolve defensively (never trust the raw string).
        return registry.find(name)
                .filter(FieldDescriptor::available)
                .orElseThrow(() -> new HqlSemanticException("Unknown field '" + name + "'", name));
    }

    // ---- comparison ----

    private Predicate comparison(Expr.Comparison c, Root<Issue> root, CriteriaBuilder cb, ResolutionContext ctx) {
        FieldDescriptor f = field(c.field());
        ComparisonOp op = c.op();

        // text ~ term
        if (f.dataType() == FieldDataType.TEXT) {
            var term = (ResolvedValue.TextTerm) valueResolver.resolve(f, c.value(), ctx);
            return textMatch(term.term(), root, cb);
        }

        // priority with an ORDERED operator ranks by catalog `position` (§5.3). Rank
        // is INVERTED: a lower position = more urgent = "greater" priority, so
        // `priority > "Low"` means "more urgent than Low" and `ORDER BY priority DESC`
        // = most-urgent-first (the epic's acceptance query, §14). We flip the operator
        // against the raw position column to express urgency semantics.
        if (f.name().equals("priority") && isOrdered(op)) {
            var pos = valueResolver.resolvePriorityPosition(f, c.value(), ctx);
            Path<Short> path = root.get("priority").get("position");
            return ordered(flipForUrgency(op), path, pos.position(), cb);
        }

        // date / timestamp comparison (§6.3)
        if (f.dataType() == FieldDataType.DATE || f.dataType() == FieldDataType.TIMESTAMP) {
            var bounds = (ResolvedValue.DateBounds) valueResolver.resolve(f, c.value(), ctx);
            return dateComparison(f, op, bounds, root, cb);
        }

        // id-set membership fields: status/type/priority(=,!=), assignee/reporter, parent
        var resolved = resolveIdSet(f, c.value(), ctx);
        Path<UUID> idPath = path(root, f.entityPath());
        return switch (op) {
            case EQ -> idPath.in(resolved.ids());
            case NEQ -> cb.or(cb.isNull(idPath), cb.not(idPath.in(resolved.ids())));
            default -> throw new HqlSemanticException(
                    "Operator '" + op.symbol() + "' is not allowed on field '" + f.name() + "'", f.name());
        };
    }

    private ResolvedValue.Ids resolveIdSet(FieldDescriptor f, Value value, ResolutionContext ctx) {
        if (f.dataType() == FieldDataType.ISSUE_REF) {
            return parentResolver.resolve(f, value, ctx);
        }
        var rv = valueResolver.resolve(f, value, ctx);
        if (rv instanceof ResolvedValue.Ids ids) return ids;
        throw new HqlSemanticException("Field '" + f.name() + "' expects a value", f.name());
    }

    // ---- IN list ----

    private Predicate inList(Expr.InList in, Root<Issue> root, CriteriaBuilder cb, ResolutionContext ctx) {
        FieldDescriptor f = field(in.field());
        // priority IN (...) always uses id equality (§5.3: >/< use position, IN uses id)
        var ids = new ArrayList<UUID>();
        for (Value v : in.values()) {
            ids.addAll(resolveIdSet(f, v, ctx).ids());
        }
        if (ids.isEmpty()) {
            return cb.disjunction();
        }
        return path(root, f.entityPath()).in(ids);
    }

    // ---- IS [NOT] EMPTY ----

    private Predicate isEmpty(Expr.IsEmpty e, Root<Issue> root, CriteriaBuilder cb) {
        FieldDescriptor f = field(e.field());
        Path<?> path = path(root, f.entityPath());
        return e.negated() ? cb.isNotNull(path) : cb.isNull(path);
    }

    // ---- date comparison ----

    private Predicate dateComparison(FieldDescriptor f, ComparisonOp op, ResolvedValue.DateBounds b,
                                     Root<Issue> root, CriteriaBuilder cb) {
        if (f.dataType() == FieldDataType.DATE) {
            // due: a real DATE column — direct comparison against the LocalDate.
            Path<LocalDate> path = root.get(f.entityPath());
            LocalDate d = b.date();
            return switch (op) {
                case EQ -> cb.equal(path, d);
                case NEQ -> cb.or(cb.isNull(path), cb.notEqual(path, d));
                case GT -> cb.greaterThan(path, d);
                case GTE -> cb.greaterThanOrEqualTo(path, d);
                case LT -> cb.lessThan(path, d);
                case LTE -> cb.lessThanOrEqualTo(path, d);
                default -> throw new HqlSemanticException(
                        "Operator '" + op.symbol() + "' is not allowed on field '" + f.name() + "'", f.name());
            };
        }
        // created / updated: TIMESTAMP with inclusive end-of-day boundaries (§6.3).
        Path<Instant> path = root.get(f.entityPath());
        Instant lo = b.lowerInstant();
        Instant hiEx = b.upperInstantExclusive();
        return switch (op) {
            case EQ -> cb.and(cb.greaterThanOrEqualTo(path, lo), cb.lessThan(path, hiEx));   // within that UTC day
            case NEQ -> cb.or(cb.lessThan(path, lo), cb.greaterThanOrEqualTo(path, hiEx));
            case GT -> cb.greaterThanOrEqualTo(path, hiEx);   // > day  → from the next day
            case GTE -> cb.greaterThanOrEqualTo(path, lo);    // >= day → from day start
            case LT -> cb.lessThan(path, lo);                 // < day  → before day start
            case LTE -> cb.lessThan(path, hiEx);              // <= day → up to end-of-day (inclusive)
            default -> throw new HqlSemanticException(
                    "Operator '" + op.symbol() + "' is not allowed on field '" + f.name() + "'", f.name());
        };
    }

    // ---- text match: LOWER(title) LIKE %term% OR LOWER(description) LIKE %term% ----

    private Predicate textMatch(String rawTerm, Root<Issue> root, CriteriaBuilder cb) {
        String escaped = escapeLike(rawTerm.toLowerCase());
        String pattern = "%" + escaped + "%";
        Predicate title = cb.like(cb.lower(root.get("title")), pattern, '\\');
        Predicate desc = cb.like(cb.lower(root.get("description")), pattern, '\\');
        return cb.or(title, desc);
    }

    // Escape LIKE metacharacters so a term containing % or _ matches literally (§5.2).
    private String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ---- ordered scalar comparison (priority position) ----

    private Predicate ordered(ComparisonOp op, Path<Short> path, short value, CriteriaBuilder cb) {
        return switch (op) {
            case GT -> cb.greaterThan(path, value);
            case GTE -> cb.greaterThanOrEqualTo(path, value);
            case LT -> cb.lessThan(path, value);
            case LTE -> cb.lessThanOrEqualTo(path, value);
            default -> throw new IllegalStateException("non-ordered op reached ordered()");
        };
    }

    private boolean isOrdered(ComparisonOp op) {
        return op == ComparisonOp.GT || op == ComparisonOp.GTE
                || op == ComparisonOp.LT || op == ComparisonOp.LTE;
    }

    // Priority rank is the inverse of catalog position (lower position = more
    // urgent). To make ">"/"<" mean "more/less urgent", flip the operator before
    // applying it to the raw position column.
    private ComparisonOp flipForUrgency(ComparisonOp op) {
        return switch (op) {
            case GT -> ComparisonOp.LT;
            case GTE -> ComparisonOp.LTE;
            case LT -> ComparisonOp.GT;
            case LTE -> ComparisonOp.GTE;
            default -> op;
        };
    }

    // ---- ORDER BY ----

    private List<Order> orderBy(Query query, Root<Issue> root, CriteriaBuilder cb) {
        var orders = new ArrayList<Order>();
        if (query.orderBy().isPresent()) {
            for (OrderBy.SortKey key : query.orderBy().get().keys()) {
                FieldDescriptor f = field(key.field());
                Path<?> path = sortPath(root, f);
                boolean desc = key.direction() == OrderBy.Direction.DESC;
                // priority rank is inverted vs its `position` column (lower position =
                // more urgent), so "priority DESC" (most-urgent-first, the epic's query)
                // is position ASC. Flip the direction only for priority.
                if (f.name().equals("priority")) desc = !desc;
                orders.add(desc ? cb.desc(path) : cb.asc(path));
            }
        } else {
            // Default stable sort: updated DESC, id DESC (§8.1).
            orders.add(cb.desc(root.get("updatedAt")));
        }
        // Always break ties on id for a stable, deterministic page.
        orders.add(cb.desc(root.get("id")));
        return orders;
    }

    private Path<?> sortPath(Root<Issue> root, FieldDescriptor f) {
        // ENUM_REF sorts by the referenced row's catalog position (join present);
        // everything else sorts by its own path.
        return switch (f.name()) {
            case "status" -> root.get("status").get("position");
            case "type" -> root.get("type").get("position");
            case "priority" -> root.get("priority").get("position");
            case "assignee" -> root.get("assignee").get("displayName");
            case "reporter" -> root.get("reporter").get("displayName");
            case "parent" -> root.get("parent").get("id");
            default -> root.get(f.entityPath());
        };
    }

    private <T> Path<T> path(Root<Issue> root, String dotted) {
        String[] parts = dotted.split("\\.");
        Path<?> p = root.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            p = p.get(parts[i]);
        }
        @SuppressWarnings("unchecked")
        Path<T> cast = (Path<T>) p;
        return cast;
    }
}
