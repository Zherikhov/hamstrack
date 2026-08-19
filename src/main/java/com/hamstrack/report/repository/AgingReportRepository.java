package com.hamstrack.report.repository;

import com.hamstrack.issue.entity.Issue;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * The statements behind {@code GET …/reports/aging} (reports-proposal §2.2) — <strong>three
 * declared, and two on the steady-state path</strong>: the open issues, the open counts, and the
 * lifetime cycle-time percentiles drawn across them. {@code CycleTimeQueryCountTest} pins all
 * three numbers, and pins that the columns cost zero.
 *
 * <p>Same conventions as {@code FlowReportRepository} and {@code CycleTimeReportRepository}:
 * native SQL, the bare {@link Repository} marker, and the tenant predicate inside
 * {@link #PROJECT_SCOPE} rather than beside it, so the copy-paste unit cannot yield an unscoped
 * statement. This report takes no filters — it has no window and no narrowing parameters at all,
 * which is also why there is no filtered/unfiltered split here.
 *
 * <h2>What "open" means, and why it is asked of the status rather than of {@code closed_at}</h2>
 * An issue is open here iff its current status's <strong>category is not DONE</strong>. The
 * tempting alternative, {@code closed_at IS NULL}, is equivalent today only because the
 * application maintains that invariant (stamped on entering DONE, cleared on leaving); the
 * category is the definition, and it is also what the columns are keyed on, so asking the same
 * question of the same table is what keeps an item from being in the report but in no column —
 * or in a column but not the report.
 *
 * <h2>The join to {@code statuses} selects nothing, deliberately</h2>
 * It exists to <em>filter</em> ({@code s.category <> 'DONE'}) and for nothing else: no column of
 * {@code statuses} is returned by any statement here. That is a decision rather than an
 * oversight, so it is written down. A <strong>stranded</strong> issue — one whose status has left
 * the project's workflow — still reaches the response, but it arrives in a
 * <strong>deliberately anonymous</strong> trailing column ({@code statusId: null}, a constant
 * name, no category), because the only status rows this report is entitled to name are the ones
 * {@code ProjectConfigService} resolved from the project's own effective workflow. Selecting
 * {@code s.name}/{@code s.category} here would name a row reached <em>by primary key from an
 * issue</em> rather than by any project-scoped lookup — a different and weaker provenance. An
 * earlier revision selected both columns, the service never read either, and this javadoc
 * described the resulting feature as if it existed; the columns are gone and this paragraph
 * replaces the description, so the next reader does not implement the sentence.
 */
public interface AgingReportRepository extends Repository<Issue, UUID> {

    /**
     * The tenant scope, {@code WHERE} included — the smallest fragment any statement here may
     * start from. Nothing in this class opens a {@code WHERE} of its own.
     */
    String PROJECT_SCOPE = """
                 WHERE i.project_id = :projectId
            """;

    /**
     * Age in days, from {@code started_at} when there is one and from {@code created_at} when
     * there is not.
     *
     * <p><strong>This is the one place in the feature where a fallback to {@code created_at} is
     * correct, and the asymmetry with cycle time is deliberate.</strong> Cycle time measures
     * finished work, where substituting filing time for start time produces a number that is
     * simply wrong. Age asks how long something has been waiting, and "filed 40 days ago, never
     * picked up" is a true and materially different fact from "in progress for 40 days" — both
     * belong on this board. The response returns the nullable {@code started_at} beside the
     * number so the reader can tell which of the two they are looking at, rather than being
     * handed a figure with no provenance.
     *
     * <p>{@code now()} is the transaction timestamp, so every item in one response is aged
     * against the same instant and {@code meta.computedAt} is exactly the anchor it claims to be.
     */
    String AGE_DAYS = "CAST(EXTRACT(EPOCH FROM (now() - COALESCE(i.started_at, i.created_at)))"
            + " AS double precision) / 86400";

    /** "Currently open", stated once — see the class note on why the category and not {@code closed_at}. */
    String PROJECT_SCOPED_OPEN_ISSUES = """
              FROM issues i
              JOIN statuses s ON s.id = i.status_id
            """ + PROJECT_SCOPE + """
               AND s.category <> 'DONE'
            """;

    /**
     * Every open issue of the project, <strong>oldest first</strong>, capped.
     *
     * <p>{@code :limit} is the cap plus one, so a full result set is how the caller learns the cap
     * bit; it ships {@code cap} rows and sets {@code meta.truncated}. Truncating the
     * <strong>oldest-first</strong> order means what survives is the aging end of the queue — the
     * opposite choice from the cycle-time report, and the right one for each: a cycle-time reader
     * wants the most recent work, an aging reader wants the most rotten item.
     *
     * <p><strong>No {@code count(*) OVER ()} here, and that is a measured decision rather than a
     * stylistic one.</strong> Carrying the true open count as a window function would keep the
     * report's two counts in one statement — but a {@code WindowAgg} between the {@code Limit} and
     * the {@code Sort} makes PostgreSQL's top-N heapsort unavailable, so it sorts and materialises
     * <em>every</em> open row with its payload instead of the {@code limit} it will return. On the
     * 100k-issue fixture that was the difference between a 2.9 MB in-memory quicksort and a
     * 3.4 MB external merge spilling to temp files: <strong>171 ms → 65 ms</strong> at the default
     * cap, and 49 ms at a realistic WIP volume. The count moved to {@link #openCounts}, where it
     * costs an aggregate scan and no sort at all.
     *
     * <p>Cost: a scan of the project's issues (there is no index on "not done" — the predicate
     * lives in another table, so no index on {@code issues} can express it), then a top-N sort.
     * That is bounded by work in flight rather than by history, which is the number a healthy
     * project keeps small on purpose. The sort key is {@code COALESCE(started_at, created_at)}
     * rather than the age expression, because they order identically and this one does not
     * evaluate {@code now()} per comparison.
     *
     * <p><strong>No new index, and the reason is a measurement rather than a shrug.</strong> On
     * the 100k-issue fixture this statement runs in 67 ms at the default cap and 49 ms at a
     * realistic WIP volume — an order of magnitude inside the "under 1 s warm" budget §12 asks
     * for. An expression index on {@code (project_id, COALESCE(started_at, created_at))} was
     * built and measured, and it is genuinely effective: it turns the scan-and-sort into an
     * ordered index scan that stops at the {@code LIMIT}, taking the realistic case from
     * <strong>48.9 ms to 0.98 ms</strong> (it does nothing for the pathological 20 000-open-issue
     * case, 72 ms vs 67 ms). It was <em>not</em> added, because a sixteenth index on the hottest
     * table in the schema is a permanent write cost paid by every issue create and every status
     * move, and this report is nowhere near its budget. Recorded here so the next person reaches
     * for the measurement instead of rediscovering it — and so that if the aging page ever does
     * become hot, the fix is known, cheap and already benchmarked.
     *
     * @return {@code [UUID issueId, Number number, String title, UUID statusId,
     *         UUID|null assigneeId, OffsetDateTime|null startedAt, Number ageDays]} — no column
     *         of {@code statuses} among them; see the class note on why the join selects nothing
     */
    @Query(value = """
            SELECT i.id AS issue_id,
                   i.number AS issue_number,
                   i.title AS title,
                   i.status_id AS status_id,
                   i.assignee_id AS assignee_id,
                   i.started_at AS started_at,
            """
            + "       ROUND(CAST(" + AGE_DAYS + " AS numeric), 2) AS age_days\n"
            + PROJECT_SCOPED_OPEN_ISSUES + """
             ORDER BY COALESCE(i.started_at, i.created_at) ASC, i.id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> openIssues(@Param("projectId") UUID projectId, @Param("limit") int limit);

    /**
     * The live half of the report's {@code meta}: how many issues are open (so a truncated
     * response can still state its own denominator), and how far back the project's history
     * reaches.
     *
     * <p><strong>Per request, never cached</strong> — this is the live half. It is bounded by
     * work in flight, costs one aggregate pass and no sort, and it is the number the reader is
     * looking at right now; serving it from a minute-old snapshot beside a live item list would
     * let {@code basedOnIssues} disagree with the rows printed underneath it.
     *
     * <h2>{@code first_issue_at} is the project's earliest issue, not the earliest OPEN one</h2>
     * It was the latter until HD-138 R3 round 3, which made {@code meta.firstIssueAt} mean three
     * different things across three reports that share one {@link
     * com.hamstrack.report.dto.ReportMeta} record — project-wide on flow, windowed on cycle time,
     * open-only here. One name, three populations, every one of them a plausible date.
     *
     * <p>Aligned rather than renamed, for a reason specific to this report: the fact the old
     * column stated is <strong>already in the response body</strong>. Items come back oldest
     * first, so the earliest open issue is the first item of the first non-empty column — with
     * its key, its title and its age, which is strictly more than a bare timestamp in
     * {@code meta}. Whereas "how far back does this project go" is not derivable from this
     * response at all, and it is exactly the provenance the report's own reference lines need:
     * the p50/p85 drawn across these columns are computed over the project's <em>entire</em>
     * completed history, so the reader's first question about them is how much history that is.
     * A fourth field name would have grown a shared contract to carry a fact the body already
     * carries, and left the useful one unavailable.
     *
     * <p>The subselect binds its own {@code i}, shadowing the outer one — the same shape and the
     * same reasoning as {@code CycleTimeReportRepository.FIRST_ISSUE_AT_OPEN}. No filters exist
     * on this report, so {@link #PROJECT_SCOPE} alone is the whole of it. It is an index min on
     * {@code idx_issues_project_created} and costs no extra round trip.
     *
     * @return exactly one row, {@code [Number totalOpen, OffsetDateTime|null firstIssueAt]}
     */
    @Query(value = """
            SELECT count(*) AS total_open,
                   (SELECT min(i.created_at)
                      FROM issues i
            """ + PROJECT_SCOPE + """
                   ) AS first_issue_at
            """ + PROJECT_SCOPED_OPEN_ISSUES, nativeQuery = true)
    List<Object[]> openCounts(@Param("projectId") UUID projectId);

    /**
     * The p50/p85 <strong>cycle time</strong> of everything the project has ever finished, and
     * the size of the sample they were computed from.
     *
     * <p><strong>The percentiles have no window, on purpose.</strong> The line's claim is "older
     * than 85% of everything the team has ever completed", and a windowed line would make a much
     * narrower claim while looking identical. The cost is one aggregate pass over the project's
     * completed-and-started issues — 44 ms on the 100k-issue fixture — which is the honest price
     * of that sentence.
     *
     * <p><strong>It is also the one statement in this feature that is O(history) with nothing the
     * caller can narrow</strong>: no window, no {@code LIMIT}, it grows forever, and the throttle
     * charges one unit for it exactly as for a cheap request. It is therefore fronted by
     * {@link com.hamstrack.report.service.LifetimeCycleTimeCache}, a 60-second per-project
     * snapshot — the same freshness the response's {@code Cache-Control: private, max-age=60}
     * already advertises to the client. The sentence above is preserved <em>exactly</em>: the
     * pass still covers the whole history when it runs, it just runs at most once per project per
     * minute per node.
     *
     * <p>That is the entire reason this is a statement of its own rather than the second derived
     * table of a joined pair (which is how R3 first shipped it): the two halves of this report
     * have different freshness requirements and only one of them is unbounded, and a cache can
     * only front the half that tolerates being a minute old. The price is one extra round trip on
     * a miss — see {@code CycleTimeQueryCountTest}, which pins both the 2-statement warm path and
     * the 3-statement cold one.
     *
     * <p><strong>Measured on the 100k-issue fixture (20k of them open), database time per
     * request:</strong>
     * <pre>
     *   before  items 42 ms + combined stats 78 ms  = ~120 ms, every request
     *   warm    items 42 ms + open counts   23 ms  =  ~65 ms, 59 seconds out of 60
     *   cold    items 42 ms + 23 ms + this  55 ms  = ~120 ms, at most once per project/minute
     * </pre>
     * So the steady state is roughly <strong>46% less database time</strong> and the miss is
     * exactly the old cost — splitting the pair is free in query time (78 ms combined vs 23 + 55
     * apart), and costs only the extra round trip. The saving grows with history, which is the
     * point: the 55 ms half is the one that keeps growing, and it is now amortised over a minute
     * of requests instead of paid by each.
     *
     * <p>{@code started_at IS NOT NULL} is stated rather than left to {@code percentile_cont}'s
     * NULL-skipping, because it also keeps {@code sample_size} honest: the count must be of the
     * issues the percentiles were actually computed from, or the suppression threshold ("need 5,
     * have 3") would be measured against a different set than the numbers it guards.
     *
     * @return exactly one row, {@code [Number sampleSize, Number|null p50, Number|null p85]}
     */
    @Query(value = """
            SELECT count(*) AS sample_size,
            """
            + "       ROUND(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY "
            + CycleTimeReportRepository.CYCLE_DAYS + ") AS numeric), 2) AS p50,\n"
            + "       ROUND(CAST(percentile_cont(0.85) WITHIN GROUP (ORDER BY "
            + CycleTimeReportRepository.CYCLE_DAYS + ") AS numeric), 2) AS p85\n"
            + "  FROM issues i\n"
            + PROJECT_SCOPE + """
               AND i.closed_at IS NOT NULL
               AND i.started_at IS NOT NULL
            """, nativeQuery = true)
    List<Object[]> completedCycleTimeStats(@Param("projectId") UUID projectId);
}
