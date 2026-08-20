package com.hamstrack.search;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueFieldValue;
import com.hamstrack.issue.entity.IssueLabel;
import com.hamstrack.issue.entity.IssueVersionLink;
import com.hamstrack.issue.entity.VersionLinkType;
import com.hamstrack.search.FieldResolver.Resolved;
import com.hamstrack.search.parser.ast.ComparisonOp;
import com.hamstrack.search.parser.ast.Expr;
import com.hamstrack.search.parser.ast.OrderBy;
import com.hamstrack.search.parser.ast.Query;
import com.hamstrack.search.parser.ast.Value;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
 * ALWAYS ANDed as the outermost conjunction — on the page query, on the count query and
 * on the Insights aggregate, all three of which get their predicate from the one
 * {@link #scopedPredicate} method rather than assembling their own. The
 * parsed predicate is nested strictly inside it, so no parsed token can widen or
 * escape the workspace/visible-project boundary. All scope values are derived
 * server-side from the authenticated actor.
 *
 * <p>The page query fetch-joins the five ToOne associations
 * (type/status/priority/assignee/reporter) like {@code IssueRepository
 * .findByProjectFilteredCapped} to avoid N+1; parent summaries and roll-ups are batched
 * by the service after the page is fetched (never fetch-joined — Cartesian). The
 * count query carries the same predicate with no fetches or sort.
 */
@Component
@RequiredArgsConstructor
public class HqlCompiler {

    private final EntityManager em;
    private final FieldResolver fieldResolver;
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
        // HD-31: component is a ToOne, so it rides the same fetch block — IssueResponse
        // renders it and it needs no batch loader.
        root.fetch("component", jakarta.persistence.criteria.JoinType.LEFT);

        // No distinct: only ToOne assocs are fetch-joined (they never multiply rows),
        // and DISTINCT would forbid ORDER BY on a joined column (priority.position).
        cq.select(root);
        cq.where(scopedPredicate(query, cq, root, cb, actor, ws, ctx));
        cq.orderBy(orderBy(query, root, cb, ctx));
        return cq;
    }

    /** Build the count query: same scope-ANDed predicate, no fetch/sort (§8.1). */
    public CriteriaQuery<Long> buildCountQuery(Query query, User actor, Workspace ws, ResolutionContext ctx) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Issue> root = cq.from(Issue.class);
        cq.select(cb.count(root));
        cq.where(scopedPredicate(query, cq, root, cb, actor, ws, ctx));
        return cq;
    }

    // ---- predicate assembly ----

    /**
     * Scope predicate ANDed OUTERMOST with the (optional) parsed filter. {@code outer}
     * is the enclosing query (page, count or aggregate), threaded through so custom-field
     * comparisons can create correlated {@code EXISTS} subqueries (HD-52).
     *
     * <p><strong>Public because a second consumer exists, and deliberately shaped so that
     * consumer cannot get the predicate wrong</strong> (HD-140 R6). The Insights panel runs a
     * {@code GROUP BY} over <em>the query already in the search box</em>, and the one way that
     * feature could reintroduce this project&#39;s #1 bug class is by rebuilding the predicate
     * beside this one — two paths to the same rows, one of which someday forgets
     * {@link SearchScope}. So the aggregate rebuilds nothing: it calls this method, which is the
     * same method {@link #buildPageQuery} and {@link #buildCountQuery} call.
     *
     * <p>Note what is and is not exposed. There is no accessor for the parsed filter alone —
     * the only {@link Predicate} this class will hand out is one that <em>already contains</em>
     * {@link SearchScope#scopePredicate} as its outermost conjunction. So nothing here will give a
     * caller a scopeless predicate.
     *
     * <h2>What the caller owes this method — the obligation a fragment carries and a whole query
     * does not</h2>
     * An earlier revision of this javadoc claimed the worst a caller could do was AND further
     * conjunctions on, "which can only narrow". <strong>That is a property of the two callers, not
     * of this method</strong> (HD-140 R6 round 2, tenancy + security). Handing out a predicate
     * instead of a finished {@link CriteriaQuery} moved one invariant from "the compiler owns the
     * query" to "the caller applies this correctly", and this class is a {@code @Component} that
     * any bean can inject. Four obligations, none of which this method can verify:
     * <ol>
     *   <li><strong>One root.</strong> This restricts <em>the {@link Root} it is handed</em>, not
     *       the query that root belongs to. A caller that adds a second {@code from(Issue.class)}
     *       and selects from <em>that</em> root has assembled a scopeless query entirely out of
     *       scoped parts: the predicate is present, it is satisfied, and it filters a root nothing
     *       is read from, while the second root cross-joins the whole table. So: apply the result
     *       to the sole {@code Root<Issue>} of the query — the one passed in — and reach every
     *       further axis by a {@code join} off it, the way {@code InsightsService.axis} does.</li>
     *   <li><strong>Conjunction only.</strong> {@code cb.or(scopedPredicate(…), somethingElse)} and
     *       {@code cb.not(scopedPredicate(…))} both compile, and both widen. The result may be
     *       ANDed with more predicates and nothing else.</li>
     *   <li><strong>{@code ws} is a resolved membership.</strong> Pass the workspace from
     *       {@code WorkspaceAccessService.requireMember}. This method authorizes nothing; it will
     *       happily scope to a workspace the actor is not a member of.</li>
     *   <li><strong>{@code ctx} is built from that same {@code ws}.</strong> A context built for
     *       another workspace resolves names against the wrong tenant, and the foreign ids it
     *       yields then meet a predicate for this one and match nothing. That leaks no row — but
     *       "unknown name" (422) and "known name, zero rows" (200) are distinguishable answers, so
     *       a mismatch is an existence oracle.</li>
     * </ol>
     *
     * <p>Obligation 1 is guarded on <strong>addition</strong> rather than on behaviour, which is
     * what {@code ReportQueryScopeTest} gives the native reports and what a behavioural test of the
     * callers that exist cannot give: {@code HqlCompilerConsumerTest} scans the classpath and fails
     * when any type other than {@link SearchService} and {@code InsightsService} references this
     * class, so a third consumer is a deliberate, reviewable edit instead of a silent one. Read
     * this list when that test fails; it is the checklist it is asking you to have gone through.
     * {@code InsightsTenancyTest} covers the behaviour of the consumer that does exist.
     *
     * <p><strong>The escape hatch, deliberately not taken.</strong> If a later slice wants a second
     * aggregate, the alternative is a {@code buildAggregateQuery(...)} here that takes the axis
     * expressions as a callback and returns a finished {@code CriteriaQuery} — the from-clause goes
     * back to being owned by this class and obligations 1 and 2 stop existing. It is not built for
     * one caller: a callback API justified by a single consumer is speculative generality, and the
     * scan is the cheaper guard until a second one appears.
     */
    public Predicate scopedPredicate(Query query, CommonAbstractCriteria outer, Root<Issue> root,
                                     CriteriaBuilder cb, User actor, Workspace ws, ResolutionContext ctx) {
        Predicate scope = searchScope.scopePredicate(actor, ws, root, cb);
        return query.filter()
                .map(f -> cb.and(scope, compile(f, outer, root, cb, ctx)))
                .orElse(scope);
    }

    private Predicate compile(Expr expr, CommonAbstractCriteria outer, Root<Issue> root,
                              CriteriaBuilder cb, ResolutionContext ctx) {
        return switch (expr) {
            case Expr.And a -> cb.and(compile(a.left(), outer, root, cb, ctx), compile(a.right(), outer, root, cb, ctx));
            case Expr.Or o -> cb.or(compile(o.left(), outer, root, cb, ctx), compile(o.right(), outer, root, cb, ctx));
            case Expr.Not n -> cb.not(compile(n.operand(), outer, root, cb, ctx));
            case Expr.Comparison c -> comparison(c, outer, root, cb, ctx);
            case Expr.InList in -> inList(in, outer, root, cb, ctx);
            case Expr.IsEmpty e -> isEmpty(e, outer, root, cb, ctx);
        };
    }

    // ---- name resolution ----
    //
    // Which field a name refers to is NOT decided in this class: every name — in a
    // comparison, an IN list, IS EMPTY or ORDER BY — goes to FieldResolver, which owns
    // the registry → visible custom field → retired-key alias precedence and the errors that
    // end it (HD-114). Whatever checked this query beforehand asked that same component the
    // same question, so a name it accepted cannot turn into an unknown field here — the
    // "validated, then unknown" failure HD-107 §9.2 hit when the sequence was written down in
    // more than one place.
    // Resolution still runs here rather than being carried over from validation: the raw
    // string is never trusted, and re-asking is free.

    // ---- comparison ----

    private Predicate comparison(Expr.Comparison c, CommonAbstractCriteria outer, Root<Issue> root,
                                 CriteriaBuilder cb, ResolutionContext ctx) {
        return switch (fieldResolver.resolve(c.field(), ctx)) {
            case Resolved.CustomField(CustomFieldMeta meta) ->
                    customComparison(c, meta, outer, root, cb, ctx);
            case Resolved.SystemField(FieldDescriptor f) ->
                    systemComparison(c, f, outer, root, cb, ctx);
        };
    }

    private Predicate systemComparison(Expr.Comparison c, FieldDescriptor f, CommonAbstractCriteria outer,
                                       Root<Issue> root, CriteriaBuilder cb, ResolutionContext ctx) {
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

        // label (HD-30): many-valued, so it compiles to a correlated EXISTS over the
        // join entity rather than a column comparison — `label != "x"` means "does NOT
        // carry x", which naturally includes issues with no labels at all.
        if (f.dataType() == FieldDataType.LABEL_REF) {
            var resolved = resolveIdSet(f, c.value(), ctx);
            Predicate carries = cb.exists(labelSubquery(outer, root, cb, resolved.ids()));
            return switch (op) {
                case EQ -> carries;
                case NEQ -> cb.not(carries);
                default -> throw new HqlSemanticException(
                        "Operator '" + op.symbol() + "' is not allowed on field '" + f.name() + "'", f.name());
            };
        }

        // fixVersion / affectsVersion (HD-32): many-valued like label, and both roles
        // share ONE join table — so the EXISTS additionally filters by link_type.
        // `fixVersion != "2.4.0"` means "does NOT ship in 2.4.0", which naturally
        // includes issues with no fix version at all (and issues that merely *affect*
        // 2.4.0 — a different role is a different statement).
        if (f.dataType() == FieldDataType.VERSION_REF) {
            var resolvedVersions = resolveIdSet(f, c.value(), ctx);
            Predicate carries = cb.exists(
                    versionSubquery(outer, root, cb, resolvedVersions.ids(), linkTypeOf(f)));
            return switch (op) {
                case EQ -> carries;
                case NEQ -> cb.not(carries);
                default -> throw new HqlSemanticException(
                        "Operator '" + op.symbol() + "' is not allowed on field '" + f.name() + "'", f.name());
            };
        }

        // storyPoints (HD-22 §4.7): the first NATIVE numeric system field, so it is a
        // plain column comparison — unlike the custom-field NUMBER path, which has to
        // cast out of JSONB inside a correlated EXISTS. `!=` deliberately also matches
        // UNESTIMATED issues (a null column is "not equal to 5" in the intuitive sense,
        // and SQL's three-valued logic would otherwise silently drop them), mirroring
        // how `due != …` and the id-set fields already behave.
        if (f.dataType() == FieldDataType.NUMBER) {
            var num = (ResolvedValue.NumberValue) valueResolver.resolve(f, c.value(), ctx);
            Path<BigDecimal> numPath = path(root, f.entityPath());
            BigDecimal n = num.value();
            return switch (op) {
                case EQ -> cb.equal(numPath, n);
                case NEQ -> cb.or(cb.isNull(numPath), cb.notEqual(numPath, n));
                case GT -> cb.greaterThan(numPath, n);
                case GTE -> cb.greaterThanOrEqualTo(numPath, n);
                case LT -> cb.lessThan(numPath, n);
                case LTE -> cb.lessThanOrEqualTo(numPath, n);
                default -> throw new HqlSemanticException(
                        "Operator '" + op.symbol() + "' is not allowed on field '" + f.name() + "'", f.name());
            };
        }

        // id-set membership fields: status/type/priority(=,!=), assignee/reporter,
        // component, sprint, parent
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

    private Predicate inList(Expr.InList in, CommonAbstractCriteria outer, Root<Issue> root,
                             CriteriaBuilder cb, ResolutionContext ctx) {
        return switch (fieldResolver.resolve(in.field(), ctx)) {
            case Resolved.CustomField(CustomFieldMeta meta) ->
                    customInList(in, meta, outer, root, cb, ctx);
            case Resolved.SystemField(FieldDescriptor f) ->
                    systemInList(in, f, outer, root, cb, ctx);
        };
    }

    private Predicate systemInList(Expr.InList in, FieldDescriptor f, CommonAbstractCriteria outer,
                                   Root<Issue> root, CriteriaBuilder cb, ResolutionContext ctx) {
        // priority IN (...) always uses id equality (§5.3: >/< use position, IN uses id)
        var ids = new ArrayList<UUID>();
        for (Value v : in.values()) {
            ids.addAll(resolveIdSet(f, v, ctx).ids());
        }
        if (ids.isEmpty()) {
            return cb.disjunction();
        }
        // label IN (a, b) = "carries a OR b" — one EXISTS over the union of ids.
        if (f.dataType() == FieldDataType.LABEL_REF) {
            return cb.exists(labelSubquery(outer, root, cb, ids));
        }
        // fixVersion IN (a, b) = "ships in a OR b", in THAT role — same shape, plus the
        // link_type filter.
        if (f.dataType() == FieldDataType.VERSION_REF) {
            return cb.exists(versionSubquery(outer, root, cb, ids, linkTypeOf(f)));
        }
        return path(root, f.entityPath()).in(ids);
    }

    // ---- IS [NOT] EMPTY ----

    private Predicate isEmpty(Expr.IsEmpty e, CommonAbstractCriteria outer, Root<Issue> root,
                              CriteriaBuilder cb, ResolutionContext ctx) {
        return switch (fieldResolver.resolve(e.field(), ctx)) {
            case Resolved.CustomField(CustomFieldMeta meta) -> {
                // Empty = no issue_field_values row for this field at all (HD-52).
                Predicate anyRow = cb.exists(fieldExists(outer, root, cb, meta.fieldId(), null));
                yield e.negated() ? anyRow : cb.not(anyRow);
            }
            case Resolved.SystemField(FieldDescriptor f) -> systemIsEmpty(e, f, outer, root, cb);
        };
    }

    private Predicate systemIsEmpty(Expr.IsEmpty e, FieldDescriptor f, CommonAbstractCriteria outer,
                                    Root<Issue> root, CriteriaBuilder cb) {
        // label IS EMPTY = "carries no label at all" (no id filter on the subquery).
        if (f.dataType() == FieldDataType.LABEL_REF) {
            Predicate anyLabel = cb.exists(labelSubquery(outer, root, cb, null));
            return e.negated() ? anyLabel : cb.not(anyLabel);
        }
        // fixVersion IS EMPTY = "has no fix version at all" — the "unassigned work"
        // query of §6.6. Scoped to the ROLE: an issue that only has affects versions is
        // still `fixVersion IS EMPTY`.
        if (f.dataType() == FieldDataType.VERSION_REF) {
            Predicate anyLink = cb.exists(versionSubquery(outer, root, cb, null, linkTypeOf(f)));
            return e.negated() ? anyLink : cb.not(anyLink);
        }
        Path<?> path = path(root, f.entityPath());
        return e.negated() ? cb.isNotNull(path) : cb.isNull(path);
    }

    /**
     * {@code EXISTS (SELECT 1 FROM IssueLabel il WHERE il.issue = <outer issue>
     * [AND il.label.id IN :ids])} — the many-valued label predicate (HD-30 §3.5),
     * mirroring the HD-52 custom-field EXISTS path.
     *
     * <p>The subquery correlates to the already-scoped outer {@code Issue} root, and
     * {@link SearchScope#scopePredicate} stays the OUTERMOST conjunction — so this
     * path cannot widen the workspace/visible-project boundary. Ids are bound Criteria
     * parameters; {@code null} means "any label row" (used by {@code IS EMPTY}).
     */
    private Subquery<Integer> labelSubquery(CommonAbstractCriteria outer, Root<Issue> root,
                                            CriteriaBuilder cb, List<UUID> labelIds) {
        Subquery<Integer> sq = outer.subquery(Integer.class);
        Root<IssueLabel> il = sq.from(IssueLabel.class);
        sq.select(cb.literal(1));
        Predicate correlate = cb.equal(il.get("issue"), root);
        sq.where(labelIds == null
                ? correlate
                : cb.and(correlate, il.get("label").get("id").in(labelIds)));
        return sq;
    }

    /**
     * {@code EXISTS (SELECT 1 FROM IssueVersionLink vl WHERE vl.issue = <outer issue>
     * AND vl.linkType = :role [AND vl.version.id IN :ids])} — the many-valued
     * fix/affects predicate (HD-32 §3.5).
     *
     * <p>Same shape as {@link #labelSubquery}, plus the {@code link_type} filter that
     * tells the two roles apart inside their shared join table: without it
     * {@code fixVersion = "2.4.0"} would also match issues that merely <em>affect</em>
     * 2.4.0. The subquery correlates to the already-scoped outer {@code Issue} root,
     * and {@link SearchScope#scopePredicate} stays the OUTERMOST conjunction — so this
     * path cannot widen the workspace/visible-project boundary. Ids and the role are
     * bound Criteria parameters; {@code null} ids mean "any link in that role" (used
     * by {@code IS EMPTY}).
     */
    private Subquery<Integer> versionSubquery(CommonAbstractCriteria outer, Root<Issue> root,
                                              CriteriaBuilder cb, List<UUID> versionIds,
                                              VersionLinkType linkType) {
        Subquery<Integer> sq = outer.subquery(Integer.class);
        Root<IssueVersionLink> vl = sq.from(IssueVersionLink.class);
        sq.select(cb.literal(1));
        Predicate correlate = cb.and(
                cb.equal(vl.get("issue"), root),
                cb.equal(vl.get("linkType"), linkType));
        sq.where(versionIds == null
                ? correlate
                : cb.and(correlate, vl.get("version").get("id").in(versionIds)));
        return sq;
    }

    /**
     * Which role a VERSION_REF descriptor stands for. Keyed off the descriptor's
     * canonical name, so the registry stays the single place the two fields are
     * declared.
     */
    private VersionLinkType linkTypeOf(FieldDescriptor f) {
        return "affectsVersion".equals(f.name()) ? VersionLinkType.AFFECTS : VersionLinkType.FIX;
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

    // ============================================================ custom fields (HD-52)
    //
    // Every custom-field predicate is a correlated EXISTS subquery over
    // issue_field_values, Criteria-only with BOUND parameters — never string SQL:
    //
    //   EXISTS (SELECT 1 FROM IssueFieldValue v
    //           WHERE v.issue = <outer issue> AND v.field.id = :fid AND <valuePred>)
    //
    // The subquery correlates to the already-scoped outer Issue root and filters by
    // field_id, so it can NEVER reach outside the workspace/visible-project boundary
    // (SearchScope stays outermost). JSONB access uses cb.function(...) exclusively.

    private Predicate customComparison(Expr.Comparison c, CustomFieldMeta meta, CommonAbstractCriteria outer,
                                       Root<Issue> root, CriteriaBuilder cb, ResolutionContext ctx) {
        ComparisonOp op = c.op();

        // != on a scalar custom field: match issues whose field is absent OR differs.
        // Implemented as NOT EXISTS(EXISTS with =) — an absent row naturally satisfies
        // "not equal", which is the intuitive result (HD-52 §4).
        if (op == ComparisonOp.NEQ) {
            Predicate existsEq = cb.exists(
                    valueSubquery(outer, root, cb, meta, valuePred(meta, ComparisonOp.EQ, c.value(), ctx)));
            return cb.not(existsEq);
        }

        return cb.exists(valueSubquery(outer, root, cb, meta, valuePred(meta, op, c.value(), ctx)));
    }

    private Predicate customInList(Expr.InList in, CustomFieldMeta meta, CommonAbstractCriteria outer,
                                   Root<Issue> root, CriteriaBuilder cb, ResolutionContext ctx) {
        // IN = "any of": one EXISTS whose value predicate is an OR of per-element preds.
        var perElement = new ArrayList<Predicate>();
        var sq = valueSubqueryOpen(outer, root, cb, meta);
        Root<IssueFieldValue> v = subqueryRoot(sq);
        for (Value value : in.values()) {
            perElement.add(valuePredOn(meta, ComparisonOp.EQ, value, ctx, v, cb));
        }
        sq.where(cb.and(correlate(cb, v, root, meta), cb.or(perElement.toArray(new Predicate[0]))));
        return cb.exists(sq);
    }

    /**
     * A ready-to-EXISTS subquery correlated to the outer issue + filtered by field_id,
     * ANDed with a value predicate built against the subquery's own IssueFieldValue root.
     */
    private Subquery<Integer> valueSubquery(CommonAbstractCriteria outer, Root<Issue> root,
                                            CriteriaBuilder cb, CustomFieldMeta meta,
                                            ValuePredBuilder valuePred) {
        Subquery<Integer> sq = valueSubqueryOpen(outer, root, cb, meta);
        Root<IssueFieldValue> v = subqueryRoot(sq);
        Predicate where = correlate(cb, v, root, meta);
        Predicate val = valuePred == null ? null : valuePred.build(v);
        sq.where(val == null ? where : cb.and(where, val));
        return sq;
    }

    /** Bare EXISTS-checking subquery (any row for the field) — used by IS EMPTY. */
    private Subquery<Integer> fieldExists(CommonAbstractCriteria outer, Root<Issue> root,
                                          CriteriaBuilder cb, UUID fieldId, ValuePredBuilder valuePred) {
        var meta = new CustomFieldMeta(fieldId, "", "", FieldType.TEXT, null, null);
        return valueSubquery(outer, root, cb, meta, valuePred);
    }

    private Subquery<Integer> valueSubqueryOpen(CommonAbstractCriteria outer, Root<Issue> root,
                                                CriteriaBuilder cb, CustomFieldMeta meta) {
        Subquery<Integer> sq = outer.subquery(Integer.class);
        sq.from(IssueFieldValue.class);
        sq.select(cb.literal(1));
        return sq;
    }

    @SuppressWarnings("unchecked")
    private Root<IssueFieldValue> subqueryRoot(Subquery<Integer> sq) {
        return (Root<IssueFieldValue>) sq.getRoots().iterator().next();
    }

    /** v.issue = <outer issue> AND v.field.id = :fid — the correlation + field filter. */
    private Predicate correlate(CriteriaBuilder cb, Root<IssueFieldValue> v, Root<Issue> outerRoot,
                                CustomFieldMeta meta) {
        return cb.and(
                cb.equal(v.get("issue"), outerRoot),
                cb.equal(v.get("field").get("id"), meta.fieldId()));
    }

    /** Build the value predicate against the subquery's IssueFieldValue root (deferred). */
    @FunctionalInterface
    private interface ValuePredBuilder {
        Predicate build(Root<IssueFieldValue> v);
    }

    private ValuePredBuilder valuePred(CustomFieldMeta meta, ComparisonOp op, Value value, ResolutionContext ctx) {
        return v -> valuePredOn(meta, op, value, ctx, v, em.getCriteriaBuilder());
    }

    /**
     * The per-type JSONB value predicate on a single {@code issue_field_values} row.
     * Scalar values are extracted as text via {@code jsonb_scalar_text(value)}
     * (the {@code #>> '{}'} empty-path operator returns the scalar). MULTI_SELECT uses
     * {@code jsonb_exists} (the {@code ?} array-contains operator). All operands are bound parameters.
     */
    private Predicate valuePredOn(CustomFieldMeta meta, ComparisonOp op, Value value,
                                  ResolutionContext ctx, Root<IssueFieldValue> v, CriteriaBuilder cb) {
        Path<?> valueColumn = v.get("value");
        return switch (meta.type()) {
            case TEXT, TEXTAREA, URL -> {
                Expression<String> text = scalarText(cb, valueColumn);
                String s = requireString(meta, value);
                yield switch (op) {
                    case EQ -> cb.equal(text, s);
                    case MATCH -> {
                        String pattern = "%" + escapeLike(s.toLowerCase()) + "%";
                        yield cb.like(cb.lower(text), pattern, '\\');
                    }
                    default -> illegalOp(meta, op);
                };
            }
            case NUMBER -> {
                Expression<BigDecimal> num = cb.function("jsonb_scalar_numeric", BigDecimal.class, valueColumn);
                BigDecimal n = requireNumber(meta, value);
                yield switch (op) {
                    case EQ -> cb.equal(num, n);
                    case GT -> cb.greaterThan(num, n);
                    case GTE -> cb.greaterThanOrEqualTo(num, n);
                    case LT -> cb.lessThan(num, n);
                    case LTE -> cb.lessThanOrEqualTo(num, n);
                    default -> illegalOp(meta, op);
                };
            }
            case DATE -> {
                Expression<LocalDate> date = cb.function("jsonb_scalar_date", LocalDate.class, valueColumn);
                LocalDate d = requireDate(meta, value);
                yield switch (op) {
                    case EQ -> cb.equal(date, d);
                    case GT -> cb.greaterThan(date, d);
                    case GTE -> cb.greaterThanOrEqualTo(date, d);
                    case LT -> cb.lessThan(date, d);
                    case LTE -> cb.lessThanOrEqualTo(date, d);
                    default -> illegalOp(meta, op);
                };
            }
            case SELECT -> {
                String optionId = requireOption(meta, value);
                yield cb.equal(scalarText(cb, valueColumn), optionId);   // EQ only reaches here
            }
            case USER -> {
                UUID userId = requireUser(meta, value, ctx);
                yield cb.equal(scalarText(cb, valueColumn), userId.toString());
            }
            case CHECKBOX -> {
                boolean b = requireBoolean(meta, value);
                yield cb.equal(scalarText(cb, valueColumn), Boolean.toString(b));
            }
            case MULTI_SELECT -> {
                // value is a JSON array of option ids: membership via jsonb_exists (?).
                String optionId = requireOption(meta, value);
                Expression<Boolean> has = cb.function("jsonb_exists", Boolean.class,
                        valueColumn, cb.literal(optionId));
                yield cb.isTrue(has);   // EQ (= contains) only reaches here
            }
        };
    }

    /**
     * The scalar JSONB value as text — {@code jsonb_scalar_text(value)} renders
     * {@code (value #>> '{}')} (see {@code JsonbFunctionContributor}). Used for the
     * scalar custom-field types; numeric/date comparisons instead use the dedicated
     * {@code jsonb_scalar_numeric} / {@code jsonb_scalar_date} casting functions.
     */
    private Expression<String> scalarText(CriteriaBuilder cb, Path<?> valueColumn) {
        return cb.function("jsonb_scalar_text", String.class, valueColumn);
    }

    private Predicate illegalOp(CustomFieldMeta meta, ComparisonOp op) {
        throw new HqlSemanticException(
                "Operator '" + op.symbol() + "' is not allowed on field '" + meta.key() + "'", meta.key());
    }

    // ---- custom-field value coercion (all → bound params) ----

    private String requireString(CustomFieldMeta meta, Value value) {
        if (value instanceof Value.StringLiteral s) return s.value();
        throw new HqlSemanticException("Field '" + meta.key() + "' expects a quoted value", meta.key());
    }

    private BigDecimal requireNumber(CustomFieldMeta meta, Value value) {
        if (value instanceof Value.NumberLiteral n) {
            try {
                return new BigDecimal(n.raw());
            } catch (NumberFormatException e) {
                throw new HqlSemanticException(
                        "Invalid number '" + n.raw() + "' for field '" + meta.key() + "'", meta.key());
            }
        }
        if (value instanceof Value.StringLiteral s) {
            try {
                return new BigDecimal(s.value());
            } catch (NumberFormatException e) {
                throw new HqlSemanticException(
                        "Field '" + meta.key() + "' expects a number", meta.key());
            }
        }
        throw new HqlSemanticException("Field '" + meta.key() + "' expects a number", meta.key());
    }

    private LocalDate requireDate(CustomFieldMeta meta, Value value) {
        String raw = requireString(meta, value);
        try {
            return LocalDate.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            throw new HqlSemanticException(
                    "Invalid date '" + raw + "'; expected YYYY-MM-DD", meta.key());
        }
    }

    private boolean requireBoolean(CustomFieldMeta meta, Value value) {
        String raw = requireString(meta, value);
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        throw new HqlSemanticException(
                "Field '" + meta.key() + "' expects \"true\" or \"false\"", meta.key());
    }

    /** Resolve a SELECT/MULTI_SELECT operand (option label OR id) to its option id, or 422. */
    private String requireOption(CustomFieldMeta meta, Value value) {
        String raw = requireString(meta, value);
        String id = meta.resolveOption(raw);
        if (id == null) {
            throw new HqlSemanticException(
                    "No option '" + raw + "' on field '" + meta.key() + "'", meta.key());
        }
        return id;
    }

    /** Resolve a USER custom-field operand via the same rules as assignee/reporter. */
    private UUID requireUser(CustomFieldMeta meta, Value value, ResolutionContext ctx) {
        if (value instanceof Value.FunctionCall fn) {
            if (fn.name().equalsIgnoreCase("currentUser")) return ctx.actor().getId();
            throw new HqlSemanticException(
                    "Function '" + fn.name() + "()' is not valid for field '" + meta.key() + "'", meta.key());
        }
        String raw = requireString(meta, value);
        // email → member
        var byEmail = ctx.members().stream()
                .filter(m -> m.email() != null && m.email().equalsIgnoreCase(raw))
                .map(ResolutionContext.Member::id).findFirst();
        if (byEmail.isPresent()) return byEmail.get();
        // displayName → member (first match; USER stores a single id)
        var byName = ctx.members().stream()
                .filter(m -> m.displayName() != null && m.displayName().equalsIgnoreCase(raw))
                .map(ResolutionContext.Member::id).findFirst();
        if (byName.isPresent()) return byName.get();
        // raw UUID fallback, only if a workspace member
        try {
            UUID id = UUID.fromString(raw);
            if (ctx.members().stream().anyMatch(m -> m.id().equals(id))) return id;
        } catch (IllegalArgumentException ignored) {
            // fall through to the not-found error
        }
        throw new HqlSemanticException(
                "No member matching '" + raw + "' in this workspace", meta.key());
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

    // `ctx` is threaded in for one reason: a sort key is a field name like any other, so
    // it is resolved by the same component the predicate paths use (see sortField). A sort
    // key that resolved by its own rules is how HD-107 §9.2 produced a query the validator
    // accepted and the compiler then called an unknown field.
    private List<Order> orderBy(Query query, Root<Issue> root, CriteriaBuilder cb, ResolutionContext ctx) {
        var orders = new ArrayList<Order>();
        if (query.orderBy().isPresent()) {
            for (OrderBy.SortKey key : query.orderBy().get().keys()) {
                FieldDescriptor f = sortField(key.field(), ctx);
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

    /**
     * The system field a sort key names — resolved through {@link FieldResolver} like every
     * other name, which is what makes {@code ORDER BY story_points} work (HD-107 §9.2: this
     * was the entry point that had its own copy of the precedence and did not learn the alias
     * step, so validation accepted the key and compilation then called it unknown).
     *
     * <p>A custom field is not sortable in MVP, so it is refused here in the words the
     * validator uses. Validation rejects it first, so this is unreachable through search —
     * kept because a sort key that resolved and then fell off the end of a switch would be a
     * 500, and because "not sortable" is the true statement about it either way.
     */
    private FieldDescriptor sortField(String name, ResolutionContext ctx) {
        return switch (fieldResolver.resolve(name, ctx)) {
            case Resolved.SystemField(FieldDescriptor f) -> f;
            case Resolved.CustomField(CustomFieldMeta meta) -> throw new HqlSemanticException(
                    "Field '" + meta.key() + "' is not sortable", meta.key());
        };
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
            // HD-31: sort by the component's NAME, through an explicit LEFT join.
            // `root.get("component").get("name")` would imply an INNER join and
            // silently drop every component-less issue from `ORDER BY component`.
            case "component" -> componentJoin(root).get("name");
            default -> root.get(f.entityPath());
        };
    }

    /**
     * The page query's existing {@code LEFT JOIN FETCH component} as a plain
     * {@link jakarta.persistence.criteria.Join}, so ordering reuses it instead of
     * adding a second join to the same table. Falls back to a fresh LEFT join (the
     * count query has no fetches, and a provider whose {@code Fetch} is not a
     * {@code Join} still gets correct SQL).
     */
    private jakarta.persistence.criteria.From<?, ?> componentJoin(Root<Issue> root) {
        for (var fetch : root.getFetches()) {
            if ("component".equals(fetch.getAttribute().getName())
                    && fetch instanceof jakarta.persistence.criteria.Join<?, ?> join) {
                return join;
            }
        }
        for (var join : root.getJoins()) {
            if ("component".equals(join.getAttribute().getName())) return join;
        }
        return root.join("component", jakarta.persistence.criteria.JoinType.LEFT);
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
