package com.hamstrack.report.repository;

import com.hamstrack.issue.entity.SprintScopeEvent;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * The two statements behind {@code GET …/reports/velocity} (reports-proposal §2.5) — one sweep
 * across up to twelve sprints' ledgers, and the pair of {@code meta} scalars beside it.
 *
 * <h2>One sweep, not twelve reads</h2>
 * {@code SprintLedgerReader} answers R4's question — <em>one</em> sprint, with every issue's current
 * state beside it — in three statements. Velocity asks the same question of N sprints, and calling
 * that reader N times would be 3N statements plus N tenancy resolutions for a report whose entire
 * cost claim is that it is bounded by N. So the sweep below reads all N ledgers in one statement
 * and the caller cuts the result into per-sprint groups.
 *
 * <p><strong>What is NOT re-derived is membership.</strong> The rows come back in the shape the
 * single-sprint reader groups, and the caller assembles the same
 * {@code SprintLedger.LedgerIssue} records — so "was this issue in the sprint at time T" is still
 * answered by {@code LedgerIssue.memberAt}/{@code memberBefore}, and "what became of it" by
 * {@code LedgerIssue.outcomeAt}, one implementation, shared with the sprint review. That is the one
 * thing this slice was told not to trade for a cheaper query. A grouped
 * {@code SUM(...) FILTER (WHERE ...)} statement would also have been one round trip, and would have
 * been a <em>second</em> definition of sprint membership written in SQL, free to disagree with the
 * review printed on the next tab.
 *
 * <h2>Tenancy — three layers, and the first is the one R4's review added a guard for</h2>
 * <ul>
 *   <li><strong>The driving table carries the tenant.</strong> {@code e.workspace_id = :workspaceId}
 *       is a conjunctive predicate on the very first {@code FROM}. Not decoration: a sweep across
 *       sprints is exactly the statement shape {@code ReportQueryScopeTest}'s driving-table rule was
 *       written for, a slice ahead of this one — a scoped {@code JOIN} hanging off an unscoped
 *       driver returns the driver's own rows for every tenant, with the joined columns nulling out
 *       and nothing in the response to notice.</li>
 *   <li><strong>The sprint set is resolved, never bound from a request.</strong>
 *       {@code workspace_id} is a complete <em>tenant</em> scope but not a <em>project</em> scope,
 *       and this endpoint is project-scoped. The ids come from
 *       {@code SprintRepository.findRecentCompletedByProject}, keyed by a {@code Project} the caller
 *       has proved membership of. No caller-supplied sprint id reaches this statement at all —
 *       which is why velocity has no {@code findByIdAndProject} of its own and cannot 404 on a
 *       foreign sprint: there is no parameter that could name one.</li>
 *   <li><strong>The joined side carries its own scope.</strong> {@code i.project_id = :projectId} in
 *       the {@code ON} clause, so no other project's issue row can enter the result even if the
 *       ledger were somehow inconsistent.</li>
 * </ul>
 *
 * <h2>The join is LEFT, for the same reason it is LEFT in {@code SprintReportRepository}</h2>
 * {@code sprint_scope_events.issue_id} is nullable — V18's issue FK is {@code ON DELETE SET NULL},
 * so that deleting an issue cannot quietly rewrite a completed sprint's record. An inner join would
 * drop exactly those rows, and a velocity bar is an aggregate: a sprint whose issues were later
 * deleted would simply report a smaller number, with no error and nothing to notice. That failure is
 * worse here than in R4, because a wrong bar also moves the forecast band drawn beside it.
 *
 * <h2>No column here names a person</h2>
 * {@code actor_id} is on every row of this table and is deliberately not selected; neither is
 * {@code assignee_id}, which the single-sprint read does select for the review's issue rows. §1.4
 * refuses per-person velocity on evidence, and a refusal that lives only in a DTO is one
 * {@code SELECT} away from being undone. This statement cannot break down by person because it does
 * not know who anybody is. {@code VelocityRefusalTest} pins that from the outside.
 *
 * <h2>Cost — and the bound is the ledger's size, not the sprint count</h2>
 * {@code idx_sprint_scope_events_sprint} — {@code (sprint_id, occurred_at)} — serves both
 * statements as a ScalarArrayOp index scan, one range per sprint. §12 expected R4 or R5 to be the
 * slice that finally exercises that index; both do.
 *
 * <p>"Twelve sprints" bounds the <em>sample</em>, and it is tempting to quote it as the bound on
 * the work. It is not. Both statements are O(Σ the sampled sprints' ledger sizes), and a sprint's
 * ledger is not a fixed size: it grows by one row every time anybody moves an issue into or out of
 * that sprint, for as long as the sprint lives. The sweep at least stops at {@code cap + 1} rows
 * returned; {@code metaCounts} deliberately does not, because {@code basedOnIssues} has to be
 * counted ABOVE the cap to mean anything (see below), so its {@code count(DISTINCT …)} reads every
 * event of every sampled sprint however many there are. That was reviewed and accepted rather than
 * overlooked — measured at ~46 ms against twenty times the volume these fixtures carry — and it is
 * recorded here honestly so the next person tuning this knows which number actually moves.
 */
public interface VelocityReportRepository extends Repository<SprintScopeEvent, UUID> {

    /**
     * Every ledger event of the named sprints, grouped by sprint and oldest first within each.
     *
     * <p><strong>The sprint ids arrive as one PostgreSQL array literal</strong> — {@code {a,b,c}},
     * cast in the statement — rather than as an expanded {@code IN (:ids)} list. Three reasons, and
     * the third is why this is not merely a preference: it is one bind instead of N, so the
     * statement text is identical for a 6-sprint and a 12-sprint request and stays plan-cacheable;
     * it generalises the {@code CAST(:sprintId AS uuid)} bind
     * {@code SprintReportRepository.metaCounts} already uses for a nullable id; and
     * <strong>the empty set is representable</strong> — {@code {}} matches nothing, whereas an empty
     * native {@code IN} list is a syntax error, i.e. a runtime failure on the one input every
     * brand-new project has. {@code = ANY} on the leading index column plans identically to
     * {@code IN}.
     *
     * <p><strong>Ordered by sprint first, and that is what makes the row cap survivable.</strong>
     * The caller fetches {@code cap + 1} rows. Ordering by {@code sprint_id} keeps each sprint's
     * events contiguous, so a truncated read cannot leave a sprint half-read <em>without the caller
     * being able to tell which one</em> — it is the group the extra row continues. A sprint
     * assembled from half its ledger would report a wrong {@code committed} and a wrong
     * {@code completed}, both entirely plausible, and would move the forecast band with them; so
     * the caller drops that group whole rather than shipping a number it cannot stand behind.
     *
     * <p><strong>Contiguity is not the whole story, and the rest of it is a trap.</strong> A cap
     * that bites leaves at most one group <em>incomplete</em> — but it leaves every group after
     * that one <strong>entirely absent</strong>, and those ids are UUID v7, i.e. time-ordered, so
     * the sprints that vanish are the most recently created ones. Absent is not distinguishable
     * from "this sprint has no ledger rows", which is a legitimate state with an honest reading of
     * four zeros; read that way, the newest sprints of a truncated report would each be drawn as an
     * empty bar and — the real damage — those zeros would enter the forecast as samples and drag
     * p50 and p85 down. So the caller drops every sampled sprint with no shipped rows once
     * {@code truncated} is set, and suppresses the band. See {@code VelocityService.group}.
     *
     * <p>{@code occurred_at} then {@code id} order the events inside a group, exactly as the
     * single-sprint read does: the commitment batch stamps one identical {@code occurred_at} on
     * every member, so without the id tiebreaker one sprint's start could come back in a different
     * order between two requests.
     *
     * <p>The {@code sprint_id} order is deliberately relied on for nothing but contiguity — the
     * caller already holds the sprints in completion order and looks each group up by id. It is
     * <em>not</em> arbitrary, though it reads that way: these are UUID v7 ids, so this is creation
     * order, which is what makes the paragraph above true. Nothing may be inferred from it beyond
     * that, since creation order and completion order are only usually the same.
     *
     * @param workspaceId the caller's workspace — this statement's own tenant scope
     * @param projectId   scopes the {@code issues} side of the LEFT join
     * @param sprintIds   a PostgreSQL {@code uuid[]} literal of ids resolved through the project;
     *                    {@code {}} legitimately matches nothing
     * @param limit       the row cap plus one
     * @return {@code [UUID sprintId, UUID|null eventIssueId, String issueKey, String event,
     *         OffsetDateTime occurredAt, BigDecimal|null snapshotPoints,
     *         OffsetDateTime|null closedAt]} — {@code closedAt} is null both for an open issue and
     *         for a deleted one, which is why a departed issue is never claimed as completed
     */
    @Query(value = """
            SELECT e.sprint_id    AS sprint_id,
                   e.issue_id     AS event_issue_id,
                   e.issue_key    AS issue_key,
                   e.event        AS event,
                   e.occurred_at  AS occurred_at,
                   e.story_points AS snapshot_points,
                   i.closed_at    AS closed_at
              FROM sprint_scope_events e
              LEFT JOIN issues i ON i.id = e.issue_id AND i.project_id = :projectId
             WHERE e.workspace_id = :workspaceId
               AND e.sprint_id = ANY (CAST(:sprintIds AS uuid[]))
             ORDER BY e.sprint_id ASC, e.occurred_at ASC, e.id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> ledgerAcrossSprints(@Param("workspaceId") UUID workspaceId,
                                       @Param("projectId") UUID projectId,
                                       @Param("sprintIds") String sprintIds,
                                       @Param("limit") int limit);

    /**
     * The two {@code meta} scalars, in one statement — {@code firstIssueAt} and
     * {@code basedOnIssues}.
     *
     * <p>Its own statement rather than columns on the sweep, for the reason
     * {@code SprintReportRepository.metaCounts} documents, which this report inherits doubled: the
     * sweep legitimately returns <strong>zero rows</strong> (a project whose completed sprints were
     * all empty, or one with no completed sprints at all), and a scalar in the select list of a
     * query with no rows never arrives.
     *
     * <h2>{@code firstIssueAt} — filters yes, window never</h2>
     * The project's earliest issue, not the earliest issue of the sampled sprints. It is a property
     * of the PROJECT (see {@code ReportMeta}), and velocity's "window" is a set of sprints — scoping
     * it to them would report a five-year-old project as having six sprints of history and fire the
     * thin-data banner on exactly the projects with the most of it.
     *
     * <h2>{@code basedOnIssues} — counted in the database, ABOVE the cap</h2>
     * The distinct issues the sampled sprints' ledgers hold, independent of
     * {@code app.reports.max-rows}. Counting the groups the report actually assembled would be the
     * same number today and the wrong one the moment the cap bit: the field's job is to state how
     * many issues the report was ABOUT, so when a cap bites it is deliberately LARGER than what came
     * back — it is precisely what the reader can no longer see. R4 shipped it shrinking and had to
     * fix it; this statement is written the corrected way from the start.
     *
     * <p>Distinctness is counted the way the caller groups: by {@code issue_id} where there is one
     * and by the snapshotted {@code issue_key} where there is not, so a deleted issue counts once
     * instead of collapsing every departed issue into a single phantom. An issue that appeared in
     * two of the sampled sprints counts <strong>once</strong> — the same "distinct issues this
     * report saw" the rest of the family means.
     *
     * @param projectId   the project, for the project-wide {@code firstIssueAt}
     * @param workspaceId the caller's workspace, restated as the subquery's own scope
     * @param sprintIds   the same {@code uuid[]} literal the sweep takes; {@code {}} counts 0
     * @return exactly one row, {@code [OffsetDateTime|null firstIssueAt, Number basedOnIssues]}
     */
    @Query(value = """
            SELECT min(i.created_at) AS first_issue_at,
                   (SELECT count(DISTINCT coalesce(CAST(e.issue_id AS text),
                                                   'key:' || e.issue_key))
                      FROM sprint_scope_events e
                     WHERE e.workspace_id = :workspaceId
                       AND e.sprint_id = ANY (CAST(:sprintIds AS uuid[]))) AS based_on_issues
              FROM issues i
             WHERE i.project_id = :projectId
            """, nativeQuery = true)
    List<Object[]> metaCounts(@Param("projectId") UUID projectId,
                              @Param("workspaceId") UUID workspaceId,
                              @Param("sprintIds") String sprintIds);
}
