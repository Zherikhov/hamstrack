package com.hamstrack.report.repository;

import com.hamstrack.issue.entity.Issue;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The statements behind {@code GET …/reports/cycle-time} (reports-proposal §2.2) — and
 * <strong>exactly two run per request</strong>: one bounded row query for the dots and one
 * all-aggregate query for the lines and the counts. {@code CycleTimeQueryCountTest} pins it.
 *
 * <p>Written to the conventions {@code FlowReportRepository} established, for the reasons stated
 * there: native SQL (percentiles are {@code percentile_cont … WITHIN GROUP}, which is not JPQL),
 * the bare {@link Repository} marker rather than {@code JpaRepository} (this is a read model over
 * {@code issues}, not a second door to the aggregate), and nullable filter ids bound as
 * <strong>text</strong> and cast in SQL, because a native query gives PostgreSQL no way to type a
 * NULL {@code uuid} bind parameter.
 *
 * <h2>Tenancy</h2>
 * Every statement states {@code project_id = :projectId} itself, inside {@link #PROJECT_SCOPE} —
 * the tenant predicate is part of the copy-paste unit, not next to it, so pasting the fragment
 * cannot produce an unscoped statement. {@code ReportQueryScopeTest} enforces both that promise
 * and the backstop (one predicate per {@code issues} reference, counted). A lost predicate here
 * has no symptom: the chart renders, the percentiles are internally consistent, and they are
 * about somebody else's project.
 *
 * <h2>The honesty rule, in SQL</h2>
 * {@link #CYCLE_DAYS} is {@code closed_at - started_at} and is NULL whenever {@code started_at}
 * is. Nothing in this file coalesces it to {@code created_at}, and nothing may: that substitution
 * turns every cycle number into a lead number, moves the p85 the aging report draws across its
 * columns, and leaves no trace in the response. The gap is reported instead, as
 * {@code missingStartCount}.
 *
 * <p>PostgreSQL does the rest for free: {@code percentile_cont} is an ordered-set aggregate and,
 * like every aggregate, <strong>ignores NULL inputs</strong>. So the cycle percentiles are
 * computed over exactly the issues that have a start, with no {@code FILTER} clause and no second
 * pass, and they come back NULL when none does.
 *
 * <h2>Why there are two of every statement</h2>
 * R1's split, inherited: the filtered variants keep the {@code (CAST(:x AS uuid) IS NULL OR …)}
 * catch-alls and the unfiltered ones mention no filter column at all. pgjdbc goes server-prepared
 * after {@code prepareThreshold=5} executions on a connection, and a generic plan does not
 * const-fold those predicates away — so the unfiltered path would be one statistics change away
 * from losing its {@code idx_issues_project_closed} range scan, silently, with no test able to
 * notice. The split removes the decision rather than betting on it.
 */
public interface CycleTimeReportRepository extends Repository<Issue, UUID> {

    /**
     * The tenant scope, {@code WHERE} included — the smallest fragment any statement here may
     * start from. Nothing in this class opens a {@code WHERE} of its own.
     */
    String PROJECT_SCOPE = """
                 WHERE i.project_id = :projectId
            """;

    /**
     * The scope plus the three optional filters of §2.2, in one place. Each filter is a no-op
     * when its parameter is null. {@code labelId} goes through {@code EXISTS} rather than a join,
     * so a multi-label issue is counted once — a join would duplicate its row, which on this
     * report would not merely inflate a count but skew every percentile toward whichever issues
     * happen to carry the most labels.
     */
    String PROJECT_SCOPE_AND_FILTERS = PROJECT_SCOPE + """
                   AND (CAST(:typeId AS uuid) IS NULL OR i.type_id = CAST(:typeId AS uuid))
                   AND (CAST(:componentId AS uuid) IS NULL OR i.component_id = CAST(:componentId AS uuid))
                   AND (CAST(:labelId AS uuid) IS NULL OR EXISTS (
                           SELECT 1 FROM issue_labels il
                            WHERE il.issue_id = i.id AND il.label_id = CAST(:labelId AS uuid)))
            """;

    /**
     * "Finished inside the window", stated once. {@code closed_at IS NOT NULL} is redundant
     * against the range predicates and is written anyway, so the statement matches the partial
     * {@code idx_issues_project_closed} (V17) predicate literally and can never drift out of it.
     */
    String CLOSED_IN_WINDOW = """
                   AND i.closed_at IS NOT NULL
                   AND i.closed_at >= :startInclusive
                   AND i.closed_at <  :endExclusive
            """;

    /**
     * Cycle time in days — NULL when {@code started_at} is, which is the honesty rule expressed as
     * an expression. {@code EXTRACT(EPOCH FROM interval)} returns {@code numeric} on PG 14+; the
     * cast to {@code double precision} is what {@code percentile_cont} takes, and defining it once
     * means the dots and the lines cannot disagree about the arithmetic.
     */
    String CYCLE_DAYS =
            "CAST(EXTRACT(EPOCH FROM (i.closed_at - i.started_at)) AS double precision) / 86400";

    /** Lead time in days. Defined for every completed issue — {@code created_at} is NOT NULL. */
    String LEAD_DAYS =
            "CAST(EXTRACT(EPOCH FROM (i.closed_at - i.created_at)) AS double precision) / 86400";

    /**
     * The dot payload — one row per issue, no joins, no taxonomy.
     *
     * <p>Every day-valued number this report returns is rounded to <strong>two decimals</strong>,
     * in SQL rather than in Java so the dots and the lines round identically. Fractions are kept
     * because a large share of real work finishes inside a day, and rounding those to 0 or 1 is
     * the difference between a scatter with a shape and a bar at the origin; only two of them,
     * because a cycle time is not measured to the second and printing it as though it were
     * invites a precision nobody has.
     *
     * <p>Composed by concatenation rather than written as one text block because a text block
     * cannot interpolate {@link #CYCLE_DAYS}, and duplicating those expressions inline — in four
     * statements — is how the dots and the lines end up computing subtly different numbers.
     */
    String ITEM_COLUMNS = """
            SELECT i.id AS issue_id,
                   i.number AS issue_number,
                   i.title AS title,
                   i.type_id AS type_id,
                   i.started_at AS started_at,
                   i.closed_at AS closed_at,
            """
            + "       " + "ROUND(CAST(" + CYCLE_DAYS + " AS numeric), 2)" + " AS cycle_days,\n"
            + "       " + "ROUND(CAST(" + LEAD_DAYS + " AS numeric), 2)" + " AS lead_days\n"
            + "  FROM issues i\n";

    /** Most recent closure first, deterministically — see {@link #completedIssues}. */
    String ITEM_ORDER = """
             ORDER BY i.closed_at DESC, i.id DESC
             LIMIT :limit
            """;

    /**
     * The five windowed aggregates of the response: the sample, the gap the honesty rule leaves,
     * and both percentile pairs. Every one of them is a statement about the <em>window</em>.
     *
     * <p>{@code meta.firstIssueAt} is deliberately <strong>not</strong> among them — see
     * {@link #FIRST_ISSUE_AT_OPEN}, which is why this constant stops one column short of the
     * response and the two statements below splice the sixth in themselves.
     */
    String STATS_AGGREGATES = """
            SELECT count(*) AS sample_size,
                   count(*) FILTER (WHERE i.started_at IS NULL) AS missing_start,
            """
            + "       " + "ROUND(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY " + CYCLE_DAYS
            + ") AS numeric), 2) AS cycle_p50,\n"
            + "       " + "ROUND(CAST(percentile_cont(0.85) WITHIN GROUP (ORDER BY " + CYCLE_DAYS
            + ") AS numeric), 2) AS cycle_p85,\n"
            + "       " + "ROUND(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY " + LEAD_DAYS
            + ") AS numeric), 2) AS lead_p50,\n"
            + "       " + "ROUND(CAST(percentile_cont(0.85) WITHIN GROUP (ORDER BY " + LEAD_DAYS
            + ") AS numeric), 2) AS lead_p85,\n";

    /**
     * {@code meta.firstIssueAt}, opened — <strong>its own project-scoped subselect, filters
     * applied and the window deliberately NOT</strong>. Each statement splices the scope fragment
     * it wants between this and {@link #FIRST_ISSUE_AT_CLOSE}, exactly as
     * {@code FlowReportRepository} does for the same field.
     *
     * <h2>Why it is not just another column of the aggregate</h2>
     * Until HD-138 R3 round 3 it was {@code min(i.created_at)} inside {@link #STATS_AGGREGATES},
     * which made it the earliest of the <em>windowed, filtered, completed</em> sample — a
     * different population from the one flow computes under the same name, on a field of the
     * shared {@link com.hamstrack.report.dto.ReportMeta} record. The wrongness was invisible
     * because both spellings produce a plausible date.
     *
     * <p>The field answers "how far back does this project's history reach", which is a property
     * of the <strong>project</strong> and not of the sample; the SPA reads it through one shared
     * helper ({@code reports/window.ts historyDays}) to decide whether to warn that a chart is
     * standing on very little data. Computed over the window it was worse than inconsistent, it
     * was <strong>circular</strong>: the earliest issue inside a 14-day window is at most 14 days
     * old, so a five-year-old project asking for a fortnight reported a fortnight of history and
     * the thin-data banner fired on every project on earth. A number read over the window can
     * only ever return the window.
     *
     * <p>Filters ARE applied, matching flow exactly: on a filtered chart the honest claim is "the
     * first issue this chart could ever have shown". The window is the one thing that must not be
     * applied, because the window is what the reader is choosing and this number is what tells
     * them whether their choice is wider than the data.
     *
     * <p>Cost is one uncorrelated scalar subquery in the same statement — no extra round trip, and
     * unfiltered it is an index min served by {@code idx_issues_project_created}.
     */
    String FIRST_ISSUE_AT_OPEN = """
                   (SELECT min(i.created_at)
                      FROM issues i

            """;

    /**
     * {@code meta.firstIssueAt} closed, and the aggregate's own {@code FROM} opened.
     *
     * <p>The subselect binds its own {@code i}, shadowing the outer one for the length of the
     * subquery. That shadowing is what lets both halves reuse the same {@code PROJECT_SCOPE}
     * fragments, and it is safe precisely because the inner {@code FROM} is right there — if it
     * were ever deleted, the inner {@code i} would silently correlate to the outer row instead.
     * {@code ReportQueryScopeTest} does not model correlation, but it does catch the likelier
     * mistake, an {@code issues} reference nobody scoped: this pair adds one reference and one
     * scope, so the counts stay balanced only while both are present.
     */
    String FIRST_ISSUE_AT_CLOSE = """
                   ) AS first_issue_at
              FROM issues i
            """;

    // ==================================================================== the dots

    /**
     * One row per completed issue in the window, <strong>unfiltered</strong>, most recently closed
     * first. Served by the partial {@code idx_issues_project_closed} (V17) for the range and the
     * ordering; the row payload is a heap fetch per issue, which is inherent — this is the one
     * report in the epic that returns issues rather than aggregates.
     *
     * <p><strong>{@code :limit} is the row cap plus one.</strong> The caller asks for one more row
     * than it is willing to ship and ships {@code cap} of them; a full result set is how it knows
     * the cap bit. Deliberately not a second {@code count(*)}: the aggregate query beside this one
     * already returns the true total, so the extra row is a cross-check costing one tuple rather
     * than one statement.
     *
     * <p>The DESC ordering is what gives the truncation a meaning a human can read — what survives
     * is "the most recent N", the sentence §6 requires the UI to print. {@code i.id} is the
     * tiebreaker, so two issues closed in the same millisecond cannot swap places between requests
     * and make a truncated report non-deterministic.
     *
     * @return {@code [UUID issueId, Number number, String title, UUID typeId, OffsetDateTime|null
     *         startedAt, OffsetDateTime closedAt, Number|null cycleDays, Number leadDays]}
     */
    @Query(value = ITEM_COLUMNS + PROJECT_SCOPE + CLOSED_IN_WINDOW + ITEM_ORDER, nativeQuery = true)
    List<Object[]> completedIssues(@Param("projectId") UUID projectId,
                                   @Param("startInclusive") OffsetDateTime startInclusive,
                                   @Param("endExclusive") OffsetDateTime endExclusive,
                                   @Param("limit") int limit);

    /** The dots with the §2.2 filters applied. Same shape, same contract. */
    @Query(value = ITEM_COLUMNS + PROJECT_SCOPE_AND_FILTERS + CLOSED_IN_WINDOW + ITEM_ORDER,
            nativeQuery = true)
    List<Object[]> completedIssuesFiltered(@Param("projectId") UUID projectId,
                                           @Param("startInclusive") OffsetDateTime startInclusive,
                                           @Param("endExclusive") OffsetDateTime endExclusive,
                                           @Param("limit") int limit,
                                           @Param("typeId") String typeId,
                                           @Param("componentId") String componentId,
                                           @Param("labelId") String labelId);

    // ==================================================================== the lines

    /**
     * The lines and every count on the response in ONE statement, <strong>unfiltered</strong>. Six
     * aggregates over a single scan of the window — PostgreSQL computes all of them from the same
     * rows, so asking for the percentiles costs no scan the sample size did not already pay for.
     * Nothing is counted in Java.
     *
     * <p><strong>The percentiles are over the whole matching set, not over the capped page.</strong>
     * An aggregate materialises no rows, so restricting it to the page would cost the same and mean
     * less: the lines describe the window the caller asked about, and {@code sampleSize} versus
     * {@code items.length} is what tells a reader the dots are a subset of it.
     *
     * <p>{@code percentile_cont} ignores NULLs, so {@code cycle_p50}/{@code cycle_p85} are computed
     * from exactly the issues that have a {@code started_at} and are NULL when there are none — the
     * honesty rule needing no code. {@code missing_start} counts the rest, which is the number the
     * UI prints beside them.
     *
     * @return exactly one row, {@code [Number sampleSize, Number missingStartCount, Number|null
     *         cycleP50, Number|null cycleP85, Number|null leadP50, Number|null leadP85,
     *         OffsetDateTime|null firstIssueAt]}
     */
    @Query(value = STATS_AGGREGATES + FIRST_ISSUE_AT_OPEN + PROJECT_SCOPE
            + FIRST_ISSUE_AT_CLOSE + PROJECT_SCOPE + CLOSED_IN_WINDOW, nativeQuery = true)
    List<Object[]> windowStats(@Param("projectId") UUID projectId,
                               @Param("startInclusive") OffsetDateTime startInclusive,
                               @Param("endExclusive") OffsetDateTime endExclusive);

    /**
     * The same six aggregates with the §2.2 filters applied, plus the three columns that only exist
     * when a filter does: <strong>did each supplied filter match anything in this project at
     * all?</strong>
     *
     * <p>Columns 8–10 are {@code true} when the filter was not supplied, and otherwise
     * {@code EXISTS(…)} over the project's <em>issues</em> — not over the taxonomy. The question a
     * reader has is "did my filter match nothing?", and a valid-but-never-used type answers that
     * the same way an id from another tenant does; both are reported, neither is distinguishable in
     * the response, and so nothing is disclosed about what exists outside this project. {@code OR}
     * short-circuits, so an absent filter costs nothing.
     *
     * <p>The aggregates sit in a derived table so the EXISTS flags can be selected beside them
     * without being dragged into a {@code GROUP BY}.
     *
     * @return exactly one row: the seven columns of {@link #windowStats} followed by
     *         {@code [Boolean typeMatched, Boolean componentMatched, Boolean labelMatched]}
     */
    @Query(value = """
            SELECT a.sample_size,
                   a.missing_start,
                   a.cycle_p50,
                   a.cycle_p85,
                   a.lead_p50,
                   a.lead_p85,
                   a.first_issue_at,
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
              FROM (
            """ + STATS_AGGREGATES + FIRST_ISSUE_AT_OPEN + PROJECT_SCOPE_AND_FILTERS
            + FIRST_ISSUE_AT_CLOSE + PROJECT_SCOPE_AND_FILTERS + CLOSED_IN_WINDOW + """
                   ) a
            """, nativeQuery = true)
    List<Object[]> windowStatsFiltered(@Param("projectId") UUID projectId,
                                       @Param("startInclusive") OffsetDateTime startInclusive,
                                       @Param("endExclusive") OffsetDateTime endExclusive,
                                       @Param("typeId") String typeId,
                                       @Param("componentId") String componentId,
                                       @Param("labelId") String labelId);
}
