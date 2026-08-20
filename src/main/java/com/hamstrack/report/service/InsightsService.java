package com.hamstrack.report.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.ReportProperties;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueLabel;
import com.hamstrack.report.dto.InsightsBucket;
import com.hamstrack.report.dto.InsightsCell;
import com.hamstrack.report.dto.InsightsDimension;
import com.hamstrack.report.dto.InsightsMeasure;
import com.hamstrack.report.dto.InsightsRequest;
import com.hamstrack.report.dto.InsightsResponse;
import com.hamstrack.report.dto.InsightsSlice;
import com.hamstrack.report.dto.ReportMeta;
import com.hamstrack.search.HqlCompiler;
import com.hamstrack.search.HqlValidator;
import com.hamstrack.search.ResolutionContext;
import com.hamstrack.search.ResolutionContextFactory;
import com.hamstrack.search.SearchNames;
import com.hamstrack.search.parser.HqlParser;
import com.hamstrack.search.parser.ast.Query;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Insights panel (reports-proposal §2.6) — <strong>the dashboard replacement</strong>.
 *
 * <h2>Why this shape, and not a grid of configurable widgets</h2>
 * The research behind this epic is unusually one-sided about gadget dashboards (§1.5): the gadgets
 * people build are the ones this epic originally proposed, nobody opens them twice, and the
 * recurring complaints are <em>"why is this so slow"</em>, <em>"why are my gadgets blank"</em> and
 * — the diagnosis that matters — <em>"these numbers don't match"</em>, whose documented cause is
 * <strong>scope</strong>: overlapping widget filters double-count the same issue across the page.
 * The vendor's own mitigation advice is to keep each gadget under a few hundred issues, i.e. the
 * feature does not survive contact with a real backlog.
 *
 * <p>So the legitimate need underneath — <em>"let me slice my data my way"</em> — is served by
 * attaching the analytics to a filtered view instead of composing a page out of independent ones.
 * Four consequences, and they are the whole argument:
 * <ol>
 *   <li><strong>One dataset.</strong> The panel's numbers come from the same HQL query, the same
 *       predicate and the same scope as the result list under it, so the panel and the list cannot
 *       disagree and no issue can be counted twice across the page. This is the global filter a
 *       widget grid structurally cannot have.</li>
 *   <li><strong>No layout.</strong> Nothing to save, nothing to break on upgrade, nothing to
 *       migrate, no per-user dashboard state, no schema.</li>
 *   <li><strong>A saved filter becomes a saved report at zero cost</strong> — saved filters already
 *       exist, and the panel's dataset <em>is</em> the filter.</li>
 *   <li><strong>One endpoint over machinery that already exists</strong>: a {@code GROUP BY} on the
 *       search Criteria query with paging and sorting dropped. No table, no column, no
 *       migration.</li>
 * </ol>
 *
 * <h2>Tenancy — the first workspace-level report, so read this rather than pattern-matching</h2>
 * Every other report in this epic hangs under {@code /projects/{projectId}} and is scoped by
 * {@code WorkspaceAccessService.resolveProject} plus a literal {@code project_id = :projectId} in
 * each statement. <strong>None of that applies here.</strong> This endpoint spans a workspace, so:
 * <ul>
 *   <li>membership is {@code requireMember} — 404 for a missing workspace and for a non-member
 *       alike, never a 403, never a 400 that would confirm the workspace exists;</li>
 *   <li>the row boundary is {@code SearchScope.scopePredicate} — workspace + the caller's visible
 *       (non-archived) projects — reached through {@link HqlCompiler#scopedPredicate}, the
 *       <strong>same</strong> method the search page and its result count go through. The
 *       aggregate does not rebuild the predicate, and that is the load-bearing decision in this
 *       class: a second path to the same rows is how the scope eventually gets lost, and
 *       {@code SearchScope}'s own javadoc calls this the project's #1 bug class;</li>
 *   <li>consequently no parsed token can widen the boundary — the scope is the outermost
 *       conjunction and the parsed query is nested strictly inside it.</li>
 * </ul>
 * Note for whoever reviews this next: {@code ReportQueryScopeTest} cannot see these statements —
 * it reflects over native {@code @Query} strings, and this is Criteria. {@code InsightsTenancyTest}
 * is the guard instead, and it asserts the stronger, behavioural thing (another workspace's issues
 * never appear in a bucket) rather than the presence of a predicate.
 *
 * <h2>Assignee is a legal slice here and is refused by velocity</h2>
 * See {@link InsightsDimension} — published metric vs. ad-hoc query. A real distinction, not an
 * inconsistency, and the line to defend if an assignee <em>report</em> is ever proposed.
 *
 * <h2>Capabilities narrow suggestions, never resolution (Rule A)</h2>
 * {@code /search/schema} omits the {@code SPRINT} dimension when no visible project runs sprints
 * and the {@code POINTS} measure when none estimates — the same "suggestions narrow, resolution
 * never does" rule that already governs the {@code sprint} and {@code storyPoints} HQL fields.
 * <strong>Nothing in this class reads a capability.</strong> A hidden slice that is requested
 * anyway is computed and returned with a 200; no status code here depends on a project toggle.
 *
 * <h2>Cost, and what bounds a query whose shape varies with input</h2>
 * This is the one endpoint in the epic where the SQL shape depends on the request, so the bound is
 * stated rather than assumed. After the shared search context is built, the report is
 * <strong>two statements unsegmented, three segmented</strong>:
 * <ol>
 *   <li>the slice aggregate — one {@code GROUP BY} over the scoped predicate, {@code LIMIT}
 *       {@link #MAX_SLICES}+1;</li>
 *   <li>the cell aggregate — the same with the segment dimension added, {@code LIMIT}
 *       {@link #MAX_CELLS}+1, and <em>only</em> when a segment was asked for;</li>
 *   <li>{@code meta.basedOnIssues} — the search's own count query, which is exactly the total the
 *       result list shows, taken <strong>independently of every cap above</strong> and never
 *       inferred from what was returned.</li>
 * </ol>
 * Each aggregate adds at most two {@code LEFT JOIN}s per dimension, all on indexed foreign keys,
 * and the deepest shape possible is slice + segment = four joins. <strong>Work</strong> is
 * O(matching issues): PostgreSQL aggregates the whole matching set before the {@code LIMIT} can
 * discard anything, so the caps bound the <em>response</em>, not the scan. That is the same
 * property every report on this base path has, and it is why the shared per-principal throttle
 * covers this path too.
 *
 * <p>Add <strong>one statement per Criteria query</strong> on top of that, which this slice
 * inherits rather than introduces: {@code SearchScope.scopePredicate} re-reads the caller's
 * visible project ids from the database every time a predicate is built, so the real totals are
 * 4 and 6. {@code POST …/search} already pays the same repeat twice. The list is also already on
 * the {@link ResolutionContext} handed to each of these calls — removing the repeat is a change
 * to shared search code, not to this report, and is deliberately left alone here.
 *
 * <p><strong>Measured</strong> ({@code EXPLAIN (ANALYZE, BUFFERS)}, 60 060 issues in one
 * workspace across 5 projects, 60 020 label links, warm cache, no HQL filter — i.e. the worst
 * <em>unfiltered</em> shape, which is <strong>not</strong> the same thing as the worst request the
 * endpoint accepts; see below):
 * <ul>
 *   <li>slice only, {@code STATUS} — <strong>53 ms</strong>, HashAggregate over an
 *       {@code idx_issues_workspace} scan;</li>
 *   <li>slice × segment, both ToOne ({@code STATUS} × {@code PRIORITY}) — <strong>72 ms</strong>;</li>
 *   <li>slice × segment with the many-valued join ({@code STATUS} × {@code LABEL}) —
 *       <strong>211 ms</strong> over 75 075 joined rows, the worst shape;</li>
 *   <li>{@code meta.basedOnIssues} — <strong>25–42 ms</strong>;</li>
 *   <li>the same slice under a typical HQL filter (assigned, last 14 days) — <strong>33 ms</strong>.</li>
 * </ul>
 * Two things that plan matters: the aggregate is a {@code HashAggregate} rather than a sort, and
 * the label pivot reaches {@code issue_labels} through the existing
 * {@code (issue_id, label_id)} unique index. It degenerated into a sequential scan of that table
 * only in the synthetic fixture, where one workspace WAS 90% of the rows — on a real
 * multi-tenant table the tenant is a small fraction and the index is chosen. That is why this
 * slice adds no index and no migration.
 *
 * <p><strong>A filter is not free, and the measurement above does not bound it</strong> (HD-140 R6
 * round 2, security item 13). The earlier version of this paragraph called the unfiltered request
 * "the most expensive the endpoint accepts", which runs the inference backwards: a filter narrows
 * the rows <em>returned</em> and adds work to every row <em>scanned</em>. An HQL query may carry up
 * to {@code HqlParser.MAX_PREDICATES} leaf predicates, and a leaf can be a pair of unanchored
 * {@code LIKE}s over a TEXT column, a correlated {@code EXISTS} over {@code issue_labels}, or a
 * correlated {@code EXISTS} over {@code issue_field_values} with a JSONB cast — none of them index
 * backed. This endpoint pays that <strong>three</strong> times per request (slice, cell, count)
 * where {@code POST …/search} pays it twice. So the numbers above size the shape, and <strong>the
 * rate budget, not the measurement, is what bounds this endpoint</strong>. The same sentence is
 * why {@code /search} itself needed one: it runs the identical predicate more expensively and had
 * no budget at all until {@code SearchRateLimitConfig} (item 12).
 *
 * <p>So the shape varies with the request and the bound does not: the caps bound the response,
 * the throttle bounds the rate, and the HQL filter narrows what comes back while adding to what is
 * scanned. A quarter of a second is the unfiltered ceiling, not a ceiling over all requests.
 *
 * <h2>Two caps, two flags, and why the bars are computed separately from their stacks</h2>
 * A bar's height is <strong>not</strong> the sum of its stacks. The cells are capped
 * independently, so summing them would make a truncated response quietly understate its own chart
 * — a wrong number with no symptom, which is the exact failure §1.5 is about. So the slice totals
 * get their own {@code GROUP BY} and are always exact, and the cells are a separate, separately
 * capped breakdown.
 */
@Service
@RequiredArgsConstructor
public class InsightsService {

    /**
     * The most bars one response may carry — a cap on the axis rather than on issue rows, because
     * this report materialises no issue rows at all.
     *
     * <p>Not configurable, for {@code CycleTimeReportService.MIN_PERCENTILE_SAMPLES}' reason: it is
     * a statement about what a chart and the table under it can mean, not about what an operator's
     * hardware can afford. 200 bars is already far past readable; it is this high so the table
     * stays useful on a high-cardinality dimension (assignee, label), not because anyone will plot
     * them all.
     */
    public static final int MAX_SLICES = 200;

    /**
     * The most (slice, segment) intersections one response may carry.
     *
     * <p>Worst case is about 1 000 cells at ~110 B of JSON plus up to 1 000 legend buckets at
     * ~120 B — roughly a quarter of a megabyte, on the same "the number here is rows, what actually
     * constrains it is bytes" arithmetic {@code ReportProperties.maxRows} spells out. The product
     * of two high-cardinality dimensions is unbounded (200 assignees × 2 000 labels), so something
     * has to bound it; ranking by the requested measure and keeping the top N is the version of
     * that which keeps the cells anybody was looking at.
     */
    public static final int MAX_CELLS = 1000;

    /** How much of a rejected {@code measure}/{@code slice}/{@code segment} a 422 quotes back. */
    private static final int MAX_ECHOED_TOKEN = 32;

    private final WorkspaceAccessService workspaceAccess;
    private final ResolutionContextFactory resolutionContextFactory;
    private final HqlValidator validator;
    private final HqlCompiler compiler;
    private final ReportProperties reportProperties;
    private final EntityManager em;

    @Transactional(readOnly = true)
    public InsightsResponse insights(User actor, UUID workspaceId, InsightsRequest req) {
        // 1. Tenancy first, exactly as every other report does it: a missing workspace and a
        //    non-member are indistinguishable 404s, and nothing below is reachable without
        //    membership — so no later refusal can confirm that this workspace exists.
        var ws = workspaceAccess.requireMember(actor, workspaceId).workspace();

        // 2. The panel's own parameters. 422 naming the parameter and its accepted values — see
        //    InsightsRequest for why these arrive as strings rather than bound enums.
        var measure = measure(req.measure());
        var slice = dimension(req.slice(), "slice", InsightsDimension.STATUS);
        var segment = req.segment() == null || req.segment().isBlank()
                ? null
                : dimension(req.segment(), "segment", null);
        if (segment == slice) {
            // A diagonal is not a breakdown. Refused rather than rendered, because the response
            // would look like a broken chart and the client would have no way to tell why.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "segment must differ from slice (both are '" + slice + "')");
        }

        // 3. The dataset: the same parse -> validate -> compile pipeline the search box runs, with
        //    the same 422s (PARSE_ERROR carrying a position, SEMANTIC_ERROR carrying a field).
        Query query = HqlParser.parse(req.queryOrEmpty());
        ResolutionContext ctx = resolutionContextFactory.build(actor, ws);
        validator.validate(query, ctx);

        // 4. The bars — exact totals, capped on the axis. One row past the cap IS the truncation
        //    signal, so no second count is needed to notice it.
        var sliceRows = aggregate(query, actor, ws, ctx, slice, null, measure, MAX_SLICES + 1);
        boolean slicesTruncated = sliceRows.size() > MAX_SLICES;
        var shippedSlices = slicesTruncated ? sliceRows.subList(0, MAX_SLICES) : sliceRows;

        var slices = new ArrayList<InsightsSlice>(shippedSlices.size());
        for (var row : shippedSlices) {
            slices.add(new InsightsSlice(
                    bucket(slice, row.get(0, UUID.class), row.get(1, String.class), ctx),
                    number(row, 2), points(row, 3), number(row, 4)));
        }

        // 5. The breakdown — only when asked for, and dropped for any bar that did not survive the
        //    slice cap: a stack belonging to no bar is unrenderable, not merely useless.
        var cells = new ArrayList<InsightsCell>();
        var segments = new LinkedHashMap<UUID, InsightsBucket>();
        boolean cellsTruncated = false;
        if (segment != null) {
            var cellRows = aggregate(query, actor, ws, ctx, slice, segment, measure, MAX_CELLS + 1);
            cellsTruncated = cellRows.size() > MAX_CELLS;
            var shippedCells = cellsTruncated ? cellRows.subList(0, MAX_CELLS) : cellRows;

            var shownSlices = slices.stream()
                    .map(s -> s.bucket().id())
                    .collect(Collectors.toCollection(java.util.HashSet::new));
            for (var row : shippedCells) {
                UUID sliceId = row.get(0, UUID.class);
                if (!shownSlices.contains(sliceId)) {
                    cellsTruncated = true;
                    continue;
                }
                UUID segmentId = row.get(2, UUID.class);
                segments.computeIfAbsent(segmentId,
                        id -> bucket(segment, id, row.get(3, String.class), ctx));
                cells.add(new InsightsCell(sliceId, segmentId,
                        number(row, 4), points(row, 5), number(row, 6)));
            }
        }

        // 6. meta.basedOnIssues is the DISTINCT ISSUES the numbers were computed from — the search's
        //    own count over the same predicate, taken above every cap and never derived from what
        //    was returned. It deliberately does NOT equal the sum of the series: on a many-valued
        //    dimension an issue lands in several buckets, and a truncated response ships fewer.
        long basedOnIssues = em.createQuery(compiler.buildCountQuery(query, actor, ws, ctx))
                .getSingleResult();

        return new InsightsResponse(
                measure, slice, segment,
                List.copyOf(slices), List.copyOf(segments.values()), List.copyOf(cells),
                slice.multiValued(), segment != null && segment.multiValued(),
                slicesTruncated, cellsTruncated,
                MAX_SLICES, MAX_CELLS,
                // truncated=false and cap=app.reports.max-rows are FACTS here, not stubs: this
                // report never materialises an issue row, so the row cap cannot bite (the same
                // thing FlowReportService reports, for the same reason). What CAN truncate is
                // buckets, and that has its own two flags above rather than borrowing a field
                // that means rows. firstIssueAt is a per-PROJECT property and this report spans a
                // workspace, so it is deliberately not populated — ReportMeta's javadoc permits
                // exactly that and forbids inventing a fourth meaning for it. unmatchedFilters
                // belongs to id-shaped filter parameters; an HQL name matching nothing is a 422
                // from the resolver, long before it could be reported here.
                new ReportMeta(OffsetDateTime.now(ZoneOffset.UTC), basedOnIssues, false,
                        reportProperties.maxRows(), null, List.of()));
    }

    // ------------------------------------------------------------------ the aggregate

    /**
     * One {@code GROUP BY} over the scoped predicate: {@code a} alone, or {@code a} x {@code b}.
     *
     * <p>The tuple layout is positional and both callers depend on it:
     * {@code [aId, aLabel, (bId, bLabel,) count, points, unestimated]}.
     *
     * <p>Three things here are load-bearing:
     * <ul>
     *   <li>the {@code WHERE} comes from {@link HqlCompiler#scopedPredicate} — the tenant scope is
     *       inside it, ANDed outermost, and this class never assembles a predicate of its own;</li>
     *   <li><strong>every</strong> aggregate is computed, not just the requested one. They ride the
     *       same scan for nothing measurable, and the alternative is a round trip every time
     *       somebody toggles count against points. The measure decides the {@code ORDER BY}, which
     *       is what decides who survives the {@code LIMIT};</li>
     *   <li>plain {@code count(*)} is correct even with the many-valued {@code LABEL} join, because
     *       that join can only multiply rows by a column that is itself in the {@code GROUP BY} —
     *       within one bucket an issue appears exactly once. A {@code COUNT(DISTINCT ...)} would be
     *       the same number for strictly more work.</li>
     * </ul>
     */
    private List<Tuple> aggregate(Query query, User actor, Workspace ws, ResolutionContext ctx,
                                  InsightsDimension a, InsightsDimension b,
                                  InsightsMeasure measure, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<Issue> root = cq.from(Issue.class);

        var axisA = axis(a, root, cb);
        var axisB = b == null ? null : axis(b, root, cb);

        Expression<Long> count = cb.count(root.get("id"));
        // COALESCE, not a bare SUM: an all-unestimated bucket must sort as 0 rather than as NULL,
        // which PostgreSQL orders FIRST under DESC — the emptiest bucket would otherwise lead the
        // points chart.
        Expression<BigDecimal> points = cb.coalesce(cb.sum(root.get("storyPoints")),
                cb.literal(BigDecimal.ZERO));
        Expression<Long> unestimated = cb.sum(cb.<Long>selectCase()
                .when(cb.isNull(root.get("storyPoints")), 1L)
                .otherwise(0L));

        var selection = new ArrayList<Selection<?>>();
        var grouping = new ArrayList<Expression<?>>();
        selection.add(axisA.id());
        selection.add(axisA.label());
        grouping.add(axisA.id());
        grouping.add(axisA.label());
        if (axisB != null) {
            selection.add(axisB.id());
            selection.add(axisB.label());
            grouping.add(axisB.id());
            grouping.add(axisB.label());
        }
        selection.add(count);
        selection.add(points);
        selection.add(unestimated);

        Expression<?> ranked = measure == InsightsMeasure.POINTS ? points : count;
        var ordering = new ArrayList<Order>();
        ordering.add(cb.desc(ranked));
        ordering.add(cb.asc(axisA.label()));
        // A total order, so two buckets that tie on measure AND label — the cross-project "In
        // Progress" — never swap places between two identical requests.
        ordering.add(cb.asc(axisA.id()));
        if (axisB != null) {
            ordering.add(cb.asc(axisB.label()));
            ordering.add(cb.asc(axisB.id()));
        }

        cq.multiselect(selection);
        cq.where(compiler.scopedPredicate(query, cq, root, cb, actor, ws, ctx));
        cq.groupBy(grouping);
        cq.orderBy(ordering);

        return em.createQuery(cq).setMaxResults(limit).getResultList();
    }

    /** A dimension's {@code (id, label)} pair, as expressions over the issue root. */
    private record Axis(Expression<UUID> id, Expression<String> label) {}

    /**
     * The join(s) one dimension needs. All {@code LEFT}, including for the non-nullable
     * associations: an inner join is equivalent there today, and a left one cannot silently drop
     * an issue if a column ever becomes nullable.
     */
    private Axis axis(InsightsDimension dimension, Root<Issue> root, CriteriaBuilder cb) {
        return switch (dimension) {
            case STATUS -> named(root.join("status", JoinType.LEFT));
            case TYPE -> named(root.join("type", JoinType.LEFT));
            case PRIORITY -> named(root.join("priority", JoinType.LEFT));
            case COMPONENT -> named(root.join("component", JoinType.LEFT));
            case SPRINT -> named(root.join("sprint", JoinType.LEFT));
            case PROJECT -> named(root.join("project", JoinType.LEFT));
            case ASSIGNEE -> {
                // Joined rather than read off ctx.members(), so an issue assigned to somebody who
                // has since left the workspace still renders with a name instead of as a blank
                // bar. Its bucket correctly gets no HQL fragment — see fragment(): a non-member's
                // name does not resolve in HQL either.
                var user = root.join("assignee", JoinType.LEFT);
                yield new Axis(user.get("id"), user.get("displayName"));
            }
            case LABEL -> {
                // The one many-valued dimension, and the only place this file needs a join the
                // entity model does not provide: Issue deliberately carries no @OneToMany to
                // issue_labels (a collection mapping would drag every issue's labels into every
                // flush and defeat batched page loading — see IssueLabel's javadoc). So it is an
                // entity join with an explicit ON, LEFT so unlabelled issues form the "no labels"
                // bucket instead of vanishing from their own chart.
                //
                // No workspace predicate is added on the join: issue_labels references its issue
                // through a COMPOSITE foreign key that includes workspace_id, so a row pairing
                // this issue with another tenant's label cannot exist. The scope on the issue root
                // is the boundary, and it is the only one there can be.
                Join<Issue, IssueLabel> link = root.join(IssueLabel.class, JoinType.LEFT);
                link.on(cb.equal(link.get("issue"), root));
                yield named(link.join("label", JoinType.LEFT));
            }
        };
    }

    private Axis named(From<?, ?> join) {
        return new Axis(join.get("id"), join.get("name"));
    }

    // ------------------------------------------------------------------ buckets & fragments

    /**
     * A bucket, plus the HQL fragment that reproduces <strong>exactly</strong> it — or no fragment
     * at all. See {@link InsightsBucket} for the three cases where there is none; the rule behind
     * all of them is that a fragment returning a different set of issues than the bar it sits on is
     * worse than no fragment, because the user clicks it and the numbers move.
     */
    private InsightsBucket bucket(InsightsDimension dimension, UUID id, String label,
                                  ResolutionContext ctx) {
        return new InsightsBucket(id, label, fragment(dimension, id, label, ctx));
    }

    private String fragment(InsightsDimension dimension, UUID id, String label,
                            ResolutionContext ctx) {
        var field = dimension.hqlField().orElse(null);
        if (field == null) {
            // PROJECT — the field exists (HD-101) but resolves a KEY, and this axis carries only
            // the name; see InsightsDimension#hqlField.
            return null;
        }
        if (id == null) {
            // The no-value bucket. Every dimension that can produce one is nullable in
            // FieldRegistry, so IS EMPTY is legal, and it is exact by construction.
            return field + " IS EMPTY";
        }
        if (dimension == InsightsDimension.ASSIGNEE) {
            // By email: unique within a workspace, so it resolves to this one user and no other.
            // A displayName is not unique and HQL turns it into an IN over every match.
            return ctx.members().stream()
                    .filter(m -> m.id().equals(id) && m.email() != null && !m.email().isBlank())
                    .findFirst()
                    .map(m -> field + " = " + quote(m.email()))
                    .orElse(null);
        }
        if (label == null || label.isBlank()) {
            // SearchNames folds a blank name to the empty key, which addresses (and merges) every
            // blank-named row in scope. Both existing readers of those maps refuse a blank key; so
            // does this one. It is a guard, not an optimisation.
            return null;
        }
        var byName = namesMap(dimension, ctx).get(SearchNames.key(label));
        // Exactly one id, and it is this one. Anything else means the fragment would be wider than
        // the bucket (a name shared by two visible projects) or would not resolve at all (a
        // COMPLETED sprint, an archived component or label — all deliberately excluded from HQL
        // name resolution, so the fragment would 422 the moment somebody clicked it).
        if (byName == null || byName.size() != 1 || !byName.get(0).equals(id)) {
            return null;
        }
        return field + " = " + quote(label);
    }

    private Map<String, List<UUID>> namesMap(InsightsDimension dimension, ResolutionContext ctx) {
        return switch (dimension) {
            case STATUS -> ctx.statusIdsByName();
            case TYPE -> ctx.typeIdsByName();
            case PRIORITY -> ctx.priorityIdsByName();
            case COMPONENT -> ctx.componentIdsByName();
            case SPRINT -> ctx.sprintIdsByName();
            case LABEL -> ctx.labelIdsByName();
            case ASSIGNEE, PROJECT -> Map.of();
        };
    }

    /**
     * An HQL double-quoted string literal. The lexer's only escapes are {@code \"}, {@code \'} and
     * {@code \\} (search proposal §4.2), so those are the only characters that need one — and they
     * are escaped HERE rather than left to a client that would have to reimplement the lexer to get
     * a label named {@code He said "no"} right.
     */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    // ------------------------------------------------------------------ parameters

    private InsightsMeasure measure(String token) {
        if (token == null || token.isBlank()) {
            return InsightsMeasure.COUNT;
        }
        try {
            return InsightsMeasure.valueOf(token.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Unknown measure '" + echo(token) + "' - expected one of "
                    + Arrays.toString(InsightsMeasure.values()));
        }
    }

    private InsightsDimension dimension(String token, String parameter, InsightsDimension fallback) {
        if ((token == null || token.isBlank()) && fallback != null) {
            return fallback;
        }
        return InsightsDimension.parse(token).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Unknown " + parameter + " '" + echo(token) + "' - expected one of "
                + Arrays.toString(InsightsDimension.values())));
    }

    /**
     * A rejected token, bounded, for putting back into the refusal that rejects it.
     *
     * <p>{@code InsightsRequest} already caps these three fields at 32 characters, so under
     * validation nothing here truncates anything — <strong>which is the point</strong>. The cap
     * protects the fields it is written on; this protects the message from whatever reaches it, so
     * a field added to that record without a {@code @Size}, or a caller that reaches this service
     * without passing through bean validation, cannot turn a 20 MB token into a 20 MB
     * {@code problem+json} body plus the copies made on the way (HD-140 R6 round 2, security item
     * 14). Echoing the value is worth keeping: a 422 that does not say what it rejected is the
     * blank-banner failure this project already fixed once.
     */
    private static String echo(String token) {
        if (token == null) {
            return "null";
        }
        return token.length() <= MAX_ECHOED_TOKEN
                ? token
                : token.substring(0, MAX_ECHOED_TOKEN) + "…";
    }

    // ------------------------------------------------------------------ tuple readers

    private static long number(Tuple row, int index) {
        var value = row.get(index);
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal points(Tuple row, int index) {
        var value = row.get(index);
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }
}
