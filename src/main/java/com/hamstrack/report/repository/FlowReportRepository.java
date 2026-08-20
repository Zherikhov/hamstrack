package com.hamstrack.report.repository;

import com.hamstrack.issue.entity.Issue;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The statements behind {@code GET …/reports/flow} (reports-proposal §2.1) — and
 * <strong>exactly three run per request</strong>, whatever the size of the project or the
 * width of the window. Two grouped queries and one combined counts query, all aggregated
 * inside PostgreSQL: no issue row is ever materialised, so there is no N+1 to grow and
 * nothing to page. {@code FlowReportQueryCountTest} pins the number.
 *
 * <p><strong>Native SQL, deliberately.</strong> The bucketing is {@code date_trunc}, which
 * is not JPQL, and the whole design of the report is "group in the database, never count
 * in Java" (§2.1). Writing it in HQL through {@code function('date_trunc', …)} would hide
 * the same SQL behind a less legible wrapper.
 *
 * <p>Extends the bare {@link Repository} marker rather than {@code JpaRepository}: this is
 * a read model over {@code issues}, not a second door to the {@code Issue} aggregate, and
 * inheriting {@code save}/{@code deleteAll} on a reporting interface would be an invitation
 * nobody needs. Same reasoning as {@code RoleRepository}.
 *
 * <h2>Tenancy</h2>
 * Every statement here — including every scalar subquery of the counts queries — states
 * {@code project_id = :projectId} itself. Not one of them infers the tenant from a join or
 * from the caller's good manners. The project id reaching this class has already been
 * resolved through {@code WorkspaceAccessService.resolveProject}, so the boundary is
 * enforced twice; that is the intent, because these are the only unscoped-by-default
 * statements in the feature and a lost predicate here would read another tenant's issues
 * into a chart without any exception being thrown.
 *
 * <p><strong>The scope is inside the reusable fragment, not next to it</strong> (round 2,
 * item 6). {@link #PROJECT_SCOPE} carries the {@code WHERE} and the tenant predicate, and
 * {@link #PROJECT_SCOPE_AND_FILTERS} extends it — so the unit a future report copy-pastes
 * cannot yield an unscoped statement. {@code ReportQueryScopeTest} asserts both halves of
 * that: every {@code *SCOPE*} fragment contains the predicate, and every native query in the
 * report package names it at least once per {@code issues} reference.
 *
 * <p><strong>Trap worth stating, because its symptom is a number and not an
 * exception:</strong> the fragment is composed with the rest of a statement by bare
 * {@code AND}s. Appending an <em>unparenthesised</em> {@code OR} after it (as the
 * "issues touched in the window" count nearly does — note its inner parentheses) would make
 * the tenant predicate one branch of a top-level OR, i.e. optional. The report would still
 * render, still be internally consistent, and be about the whole install.
 *
 * <h2>Why there are two of every statement</h2>
 * The filters used to live in one {@code (CAST(:x AS uuid) IS NULL OR …)} catch-all spliced
 * into every statement, so an unfiltered report and a filtered one ran the same SQL. Correct,
 * and dependent on a planner decision nobody controls: pgjdbc's {@code prepareThreshold}
 * defaults to <strong>5</strong>, so after five executions on a connection the statement goes
 * server-prepared, and PostgreSQL then <em>may</em> switch to a <strong>generic</strong> plan
 * — one in which {@code CAST($5 AS uuid) IS NULL} is no longer const-folded to {@code true},
 * so the planner must keep a filter referencing {@code type_id}/{@code component_id}, columns
 * the V17 indexes do not carry. The index-only scans become heap scans.
 *
 * <p>Measured (round 2, item 1 — 100k-issue project, 90-day window, warm cache, median of 5,
 * {@code plan_cache_mode} forced both ways):
 * <table>
 *   <caption>combined counts statement</caption>
 *   <tr><th>plan</th><th>catch-all</th><th>split</th></tr>
 *   <tr><td>custom</td><td>~14 ms, 883 buffers, {@code Heap Fetches: 0}</td><td>~14 ms</td></tr>
 *   <tr><td>generic</td><td><strong>~110 ms, 5888 buffers</strong>, bitmap heap scans</td>
 *       <td>~14 ms, 887 buffers, {@code Heap Fetches: 0}</td></tr>
 * </table>
 *
 * <p><strong>Read that table honestly: the generic row is what happens IF the generic plan is
 * chosen, and today it is not.</strong> Under the default {@code plan_cache_mode=auto},
 * PostgreSQL compares the generic plan's estimated cost against the average custom one and
 * keeps the cheaper — and on this shape the const-folded custom plan wins, verified by eight
 * consecutive executions of the prepared statement (still {@code Index Only Scan},
 * {@code Heap Fetches: 0}, ~12 ms). So the catch-all was not slow in production; it was one
 * statistics change away from being 8x slower, silently, with no test able to notice.
 *
 * <p>The split removes the decision instead of betting on it: the unfiltered statements
 * mention no filter column at all, so there is no generic plan for them to degrade into. It
 * costs three extra methods and nothing else — same three round trips per request, same
 * ~14 ms. The filtered statements keep the catch-all, because a filtered report is inherently
 * heap-bound ({@code labelId} adds an {@code issue_labels} semi-join and a heap fetch per
 * candidate row either way — ~200 ms generic on the same fixture), and that is the honest
 * price of the question rather than a planner accident.
 *
 * <h2>Why the nullable ids are Strings</h2>
 * A nullable {@code UUID} bind parameter in a <em>native</em> query gives PostgreSQL no way
 * to type {@code NULL}, and the usual symptom is a runtime "could not determine data type"
 * or an {@code operator does not exist: uuid = bytea}. The filters are therefore bound as
 * text and cast in SQL ({@code CAST(:typeId AS uuid)}), which types the null explicitly and
 * still parses a real id. {@code projectId} stays a {@code UUID} because it is never null.
 */
public interface FlowReportRepository extends Repository<Issue, UUID> {

    /**
     * The tenant scope, {@code WHERE} included — the smallest fragment any statement here
     * may start from. Nothing in this class opens a {@code WHERE} of its own.
     */
    String PROJECT_SCOPE = """
                 WHERE i.project_id = :projectId
            """;

    /**
     * The scope plus the three optional filters of §2.1, in one place. Each filter is a
     * no-op when its parameter is null. {@code labelId} goes through {@code EXISTS} rather
     * than a join, so a multi-label issue is still counted once — a join would double it and
     * silently inflate every number on the page.
     *
     * <p>One definition rather than five copies, because the series, the opening balance and
     * the {@code basedOnIssues} count MUST filter identically: a future edit that updated
     * four of five copies would produce a chart whose lines quietly disagree with each other.
     */
    String PROJECT_SCOPE_AND_FILTERS = PROJECT_SCOPE + """
                   AND (CAST(:typeId AS uuid) IS NULL OR i.type_id = CAST(:typeId AS uuid))
                   AND (CAST(:componentId AS uuid) IS NULL OR i.component_id = CAST(:componentId AS uuid))
                   AND (CAST(:labelId AS uuid) IS NULL OR EXISTS (
                           SELECT 1 FROM issue_labels il
                            WHERE il.issue_id = i.id AND il.label_id = CAST(:labelId AS uuid)))
            """;

    // ================================================================ series A — created

    /**
     * Series A — issues created per bucket, <strong>unfiltered</strong>. Index-served by
     * {@code idx_issues_project_created} (V17): the leading {@code project_id} equality plus
     * the half-open {@code created_at} range is one index-<em>only</em> range scan over the
     * window ({@code Heap Fetches: 0}), and it stays index-only under a generic plan because
     * this statement names no column the index does not carry — see the class note.
     *
     * <p>{@code AT TIME ZONE 'UTC'} is not decoration. {@code date_trunc} on a
     * {@code timestamptz} truncates in the <em>session</em> time zone, so without it the
     * bucket boundaries would depend on the JDBC connection's {@code TimeZone} setting and
     * the same window would bucket differently on two servers. Converting to a UTC wall
     * clock first makes the boundaries the ones §6 promises, everywhere.
     *
     * <p>Returns only buckets that have at least one issue, ascending; the service
     * zero-fills the rest. Each row is {@code [LocalDate bucket, Number count]}.
     */
    @Query(value = """
            SELECT CAST(date_trunc(CAST(:unit AS text), i.created_at AT TIME ZONE 'UTC') AS date) AS bucket,
                   count(*) AS issue_count
              FROM issues i
            """ + PROJECT_SCOPE + """
               AND i.created_at >= :startInclusive
               AND i.created_at <  :endExclusive
             GROUP BY 1
             ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> createdPerBucket(@Param("projectId") UUID projectId,
                                    @Param("unit") String unit,
                                    @Param("startInclusive") OffsetDateTime startInclusive,
                                    @Param("endExclusive") OffsetDateTime endExclusive);

    /** Series A with the §2.1 filters applied. Same shape, same contract. */
    @Query(value = """
            SELECT CAST(date_trunc(CAST(:unit AS text), i.created_at AT TIME ZONE 'UTC') AS date) AS bucket,
                   count(*) AS issue_count
              FROM issues i
            """ + PROJECT_SCOPE_AND_FILTERS + """
               AND i.created_at >= :startInclusive
               AND i.created_at <  :endExclusive
             GROUP BY 1
             ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> createdPerBucketFiltered(@Param("projectId") UUID projectId,
                                            @Param("unit") String unit,
                                            @Param("startInclusive") OffsetDateTime startInclusive,
                                            @Param("endExclusive") OffsetDateTime endExclusive,
                                            @Param("typeId") String typeId,
                                            @Param("componentId") String componentId,
                                            @Param("labelId") String labelId);

    // =============================================================== series B — resolved

    /**
     * Series B — issues resolved per bucket, i.e. bucketed by {@code closed_at},
     * <strong>unfiltered</strong>. Served by the partial {@code idx_issues_project_closed}
     * (V17).
     *
     * <p>{@code closed_at IS NOT NULL} is stated even though the range predicate implies it,
     * so the statement matches the partial index's own predicate literally and can never
     * drift out of it.
     *
     * <p>This is a "closed now" question, not a history question: an issue that was closed
     * inside the window and has since been reopened has had its {@code closed_at} cleared
     * and does not appear here at all. See {@code FlowReportResponse} for the disclosure
     * this obliges the UI to print.
     */
    @Query(value = """
            SELECT CAST(date_trunc(CAST(:unit AS text), i.closed_at AT TIME ZONE 'UTC') AS date) AS bucket,
                   count(*) AS issue_count
              FROM issues i
            """ + PROJECT_SCOPE + """
               AND i.closed_at IS NOT NULL
               AND i.closed_at >= :startInclusive
               AND i.closed_at <  :endExclusive
             GROUP BY 1
             ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> resolvedPerBucket(@Param("projectId") UUID projectId,
                                     @Param("unit") String unit,
                                     @Param("startInclusive") OffsetDateTime startInclusive,
                                     @Param("endExclusive") OffsetDateTime endExclusive);

    /** Series B with the §2.1 filters applied. Same shape, same contract. */
    @Query(value = """
            SELECT CAST(date_trunc(CAST(:unit AS text), i.closed_at AT TIME ZONE 'UTC') AS date) AS bucket,
                   count(*) AS issue_count
              FROM issues i
            """ + PROJECT_SCOPE_AND_FILTERS + """
               AND i.closed_at IS NOT NULL
               AND i.closed_at >= :startInclusive
               AND i.closed_at <  :endExclusive
             GROUP BY 1
             ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> resolvedPerBucketFiltered(@Param("projectId") UUID projectId,
                                             @Param("unit") String unit,
                                             @Param("startInclusive") OffsetDateTime startInclusive,
                                             @Param("endExclusive") OffsetDateTime endExclusive,
                                             @Param("typeId") String typeId,
                                             @Param("componentId") String componentId,
                                             @Param("labelId") String labelId);

    // ================================================================= whole-window scalars

    /**
     * The whole-window scalars the series cannot produce, in ONE statement so the report
     * stays at three round trips. <strong>Unfiltered</strong> variant.
     *
     * <p><strong>Column 1 — the opening balance</strong>, i.e. how many issues were open the
     * instant the window started. Without it the "open at bucket end" line would have to
     * begin at zero on a project with ten years of history, which is not a smaller claim but
     * a wrong one. It is computed as {@code (created before the window) − (closed before the
     * window)} rather than as a single {@code created_at < start AND (closed_at IS NULL OR
     * closed_at >= start)} scan, because the subtraction lets PostgreSQL answer each half
     * from one of the V17 indexes alone, index-only — whereas the single-scan form has to
     * visit the heap for every row it counts.
     *
     * <p>The subtraction cannot go negative: an issue cannot be closed before it was created,
     * so every row in the second count is also in the first.
     *
     * <p><strong>Column 2 — {@code basedOnIssues}</strong>: how many DISTINCT issues the two
     * series were computed from. Not {@code created + resolved}, which double-counts every
     * issue that was created and closed inside the same window and would overstate the
     * report's own evidence — the exact species of quiet wrongness {@code meta} exists to
     * prevent. The {@code OR} of two indexed ranges is planned as a bitmap-OR of the same two
     * V17 indexes <em>when the window is selective</em>; at low selectivity the planner
     * correctly abandons the BitmapOr for a full project bitmap plus a heap filter, which is
     * the cheaper plan for that shape and is not a regression.
     *
     * <p><strong>Column 3 — {@code firstIssueAt}</strong>: the project's earliest
     * {@code created_at}, so the UI can say "we only have N days of history" without a second
     * request for a date ({@code project.createdAt}) that was never the right one. Free:
     * {@code min()} over the leading edge of {@code idx_issues_project_created} is a
     * 4-buffer index-only {@code Limit 1}.
     *
     * <p>Cost note, stated because it is the one part of this report that is NOT bounded by
     * the window: both halves of the opening balance count every issue the project created
     * (or closed) before {@code startInclusive}, so they are O(project history) in index
     * entries. That is inherent — "how many were open at time T" has no cheaper honest answer
     * without a precomputed ledger, which v1 refuses (§0). Two index-only counts of a
     * 100k-issue project are ~14 ms warm; if this ever stops being true the fix is a
     * materialised balance, not a wrong number. It is also why the endpoint is throttled
     * per principal — the cost does not shrink with the window the caller asks for.
     *
     * @return exactly one row, {@code [Number openAtWindowStart, Number distinctIssuesInWindow,
     *         OffsetDateTime|null firstIssueAt]}
     */
    @Query(value = """
            SELECT (SELECT count(*)
                      FROM issues i
            """ + PROJECT_SCOPE + """
                       AND i.created_at < :startInclusive
                   ) - (SELECT count(*)
                          FROM issues i
            """ + PROJECT_SCOPE + """
                           AND i.closed_at IS NOT NULL
                           AND i.closed_at < :startInclusive
                   ) AS open_at_start,
                   (SELECT count(*)
                      FROM issues i
            """ + PROJECT_SCOPE + """
                       AND ((i.created_at >= :startInclusive AND i.created_at < :endExclusive)
                         OR (i.closed_at  >= :startInclusive AND i.closed_at  < :endExclusive))
                   ) AS based_on_issues,
                   (SELECT min(i.created_at)
                      FROM issues i
            """ + PROJECT_SCOPE + """
                   ) AS first_issue_at
            """, nativeQuery = true)
    List<Object[]> windowCounts(@Param("projectId") UUID projectId,
                                @Param("startInclusive") OffsetDateTime startInclusive,
                                @Param("endExclusive") OffsetDateTime endExclusive);

    /**
     * The same three scalars with the §2.1 filters applied, plus the three that only exist
     * when a filter does: <strong>did each supplied filter match anything in this project at
     * all?</strong> (round 2, item 7).
     *
     * <p>Columns 4–6 are {@code true} when the filter was not supplied, and otherwise
     * {@code EXISTS(…)} over the project's issues — deliberately over the <em>issues</em>,
     * not over the taxonomy: the question a reader has is "did my filter match nothing?", and
     * a valid-but-never-used type answers that question the same way an id from another
     * tenant does. Both are reported, neither is distinguishable in the response, and so
     * nothing is disclosed about what exists outside this project. {@code OR} short-circuits,
     * so an absent filter costs nothing.
     *
     * <p>The one non-cheap path is a filter that matches <em>nothing</em>: {@code EXISTS} then
     * scans the project's issues before it can say no ({@code type_id} and
     * {@code component_id} have no project-leading index). That is the rare case by
     * construction — a filter that matches stops at the first row — and it is what the
     * per-principal throttle bounds.
     *
     * <p>{@code firstIssueAt} is filtered like everything else, so on a filtered chart it
     * reads "the first issue this chart could ever have shown", which is the honest answer to
     * "how much history is behind this line".
     *
     * @return exactly one row, {@code [Number openAtWindowStart, Number distinctIssuesInWindow,
     *         OffsetDateTime|null firstIssueAt, Boolean typeMatched, Boolean componentMatched,
     *         Boolean labelMatched]}
     */
    @Query(value = """
            SELECT (SELECT count(*)
                      FROM issues i
            """ + PROJECT_SCOPE_AND_FILTERS + """
                       AND i.created_at < :startInclusive
                   ) - (SELECT count(*)
                          FROM issues i
            """ + PROJECT_SCOPE_AND_FILTERS + """
                           AND i.closed_at IS NOT NULL
                           AND i.closed_at < :startInclusive
                   ) AS open_at_start,
                   (SELECT count(*)
                      FROM issues i
            """ + PROJECT_SCOPE_AND_FILTERS + """
                       AND ((i.created_at >= :startInclusive AND i.created_at < :endExclusive)
                         OR (i.closed_at  >= :startInclusive AND i.closed_at  < :endExclusive))
                   ) AS based_on_issues,
                   (SELECT min(i.created_at)
                      FROM issues i
            """ + PROJECT_SCOPE_AND_FILTERS + """
                   ) AS first_issue_at,
                   (CAST(:typeId AS uuid) IS NULL OR EXISTS (
                       SELECT 1 FROM issues i
                        WHERE i.project_id = :projectId
                          AND i.type_id = CAST(:typeId AS uuid))) AS type_matched,
                   (CAST(:componentId AS uuid) IS NULL OR EXISTS (
                       SELECT 1 FROM issues i
                        WHERE i.project_id = :projectId
                          AND i.component_id = CAST(:componentId AS uuid))) AS component_matched,
                   (CAST(:labelId AS uuid) IS NULL OR EXISTS (
                       SELECT 1 FROM issue_labels il
                        JOIN issues i ON i.id = il.issue_id
                       WHERE i.project_id = :projectId
                         AND il.label_id = CAST(:labelId AS uuid))) AS label_matched
            """, nativeQuery = true)
    List<Object[]> windowCountsFiltered(@Param("projectId") UUID projectId,
                                        @Param("startInclusive") OffsetDateTime startInclusive,
                                        @Param("endExclusive") OffsetDateTime endExclusive,
                                        @Param("typeId") String typeId,
                                        @Param("componentId") String componentId,
                                        @Param("labelId") String labelId);
}
