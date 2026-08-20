package com.hamstrack.report.repository;

import com.hamstrack.issue.entity.SprintScopeEvent;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * The statements behind {@code GET …/reports/sprint-burnup} and {@code GET …/reports/sprint-review}
 * (reports-proposal §2.3, §2.4) — <strong>two, shared by both reports</strong>: one sprint's whole
 * scope ledger with the current state of each issue beside it, and the pair of {@code meta}
 * scalars beside it ({@code firstIssueAt}, {@code basedOnIssues}).
 *
 * <p>This is the ledger's first reader. {@code SprintScopeEventRepository} (which is the ledger's
 * <em>writer</em>, and deliberately declares nothing but an append) said the read side would land
 * here, "where {@code ReportQueryScopeTest} holds every native statement to its tenant predicate";
 * HD-149 widened that test to cover the writer's interface too, so the promise now holds from both
 * ends.
 *
 * <h2>The join to {@code issues} is a LEFT join, and that is the load-bearing line of the file</h2>
 * {@code sprint_scope_events.issue_id} is <strong>nullable</strong>: V18 made the issue FK
 * {@code ON DELETE SET NULL} rather than {@code CASCADE}, precisely so that deleting an issue
 * cannot quietly rewrite a completed sprint's record — {@code SprintService.requireDetachable}
 * refuses (422) to pull a DONE issue out of a COMPLETED sprint, and under {@code CASCADE} anyone
 * refused that could get the same effect by deleting the issue instead.
 *
 * <p>An <strong>inner</strong> join here would silently drop exactly those rows: a sprint whose
 * issues were later deleted would lose steps from its scope line and lines from its review, with
 * no error, no gap and nothing in the response to notice. The report would simply be a different,
 * smaller sprint. So the join is {@code LEFT}, every issue-side column is nullable to the reader,
 * and the ledger's snapshotted {@code issue_key} and {@code story_points} are what a departed
 * issue is rendered from.
 *
 * <h2>Scope</h2>
 * Same convention as the other three report repositories: native SQL, the bare {@link Repository}
 * marker, and the tenant predicate <em>inside</em> the copy-paste unit. There are three predicates
 * here rather than one, and each is deliberate:
 * <ul>
 *   <li>{@code e.sprint_id = :sprintId} — the sprint is the report's SUBJECT and has already been
 *       resolved through {@code findByIdAndProject}, i.e. through workspace membership. That alone
 *       is a complete scope;</li>
 *   <li>{@code e.workspace_id = :workspaceId} — restated anyway, because this statement's own text
 *       must be safe to read in isolation. A future caller that resolves the sprint some other way
 *       (a velocity sweep across a workspace is exactly that, and it is the next slice) inherits a
 *       statement whose tenancy does not depend on how its arguments were obtained;</li>
 *   <li>{@code i.project_id = :projectId} in the {@code ON} clause — the {@code issues} side
 *       carries its own scope, so no row of another project can enter this result even if the
 *       ledger were somehow inconsistent. It is also what {@code ReportQueryScopeTest} requires,
 *       and the requirement is right: "the sprint is already scoped, so the join does not need to
 *       be" is the one-line reasoning that produces a cross-tenant read with no symptom.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 * §2.3 calls this the cheapest report in the set and it is: {@code idx_sprint_scope_events_sprint}
 * — {@code (sprint_id, occurred_at)} — serves both the predicate and the ordering, and one sprint's
 * ledger is O(sprint size + changes), i.e. hundreds of rows, two per issue at most for a sprint
 * whose plan held. V18 created that index with no reader; §12 expects R4 to be the slice that
 * finally exercises it, and this statement is that slice.
 */
public interface SprintReportRepository extends Repository<SprintScopeEvent, UUID> {

    /**
     * One sprint's whole ledger, oldest first, with each event's issue as it stands TODAY.
     *
     * <p>Both R4 reports run this once and derive everything from it in memory: the two burn-up
     * lines, the scope-change log, and all five review lists. That is not a shortcut around a
     * grouped query — it is the only way the two reports can be guaranteed to agree, since they
     * disagree on purpose about which points to use and would otherwise re-derive membership twice
     * from two different statements.
     *
     * <p><strong>Oldest first, and the ordering is what makes truncation survivable.</strong>
     * {@code :limit} is the row cap plus one, the caller ships {@code cap} rows and sets
     * {@code meta.truncated} — the same signal-by-one-extra-row the other reports use. Truncating
     * an ASC ordering drops the NEWEST events, so what survives is the commitment and the earliest
     * changes: the head of the chart stays exact and its tail goes missing, which is the
     * recoverable failure. The opposite ordering would lose the commitment batch and make every
     * number wrong. In practice this cannot bite — the cap is 20 000 events and a sprint's ledger
     * is hundreds — but "in practice" is not an ordering guarantee, so the ordering is.
     *
     * <p>{@code e.id} is the tiebreaker: the commitment batch stamps one identical
     * {@code occurred_at} on every member, so without it the rows of a single sprint start could
     * come back in a different order between two requests.
     *
     * <p>No {@code count(*) OVER ()}. It would give an exact event total when the cap bites, at the
     * price of a {@code WindowAgg} between the {@code Limit} and the {@code Sort} — the measured
     * regression documented on {@code AgingReportRepository.openIssues}. The cap-plus-one row
     * already answers the only question a client asks, which is whether this is everything.
     *
     * @param sprintId    the resolved sprint — resolved through {@code findByIdAndProject}, i.e.
     *                    through membership, before this is called
     * @param workspaceId the caller's workspace, restated as this statement's own scope
     * @param projectId   scopes the {@code issues} side of the LEFT join
     * @param limit       the row cap plus one
     * @return {@code [UUID|null eventIssueId, String issueKey, String event, OffsetDateTime
     *         occurredAt, UUID|null actorId, BigDecimal|null snapshotPoints, UUID|null issueId,
     *         String|null title, UUID|null typeId, UUID|null assigneeId, UUID|null statusId,
     *         BigDecimal|null currentPoints, OffsetDateTime|null closedAt]} — every issue-side
     *         column is null for an issue that has since been deleted
     */
    @Query(value = """
            SELECT e.issue_id     AS event_issue_id,
                   e.issue_key    AS issue_key,
                   e.event        AS event,
                   e.occurred_at  AS occurred_at,
                   e.actor_id     AS actor_id,
                   e.story_points AS snapshot_points,
                   i.id           AS issue_id,
                   i.title        AS title,
                   i.type_id      AS type_id,
                   i.assignee_id  AS assignee_id,
                   i.status_id    AS status_id,
                   i.story_points AS current_points,
                   i.closed_at    AS closed_at
              FROM sprint_scope_events e
              LEFT JOIN issues i ON i.id = e.issue_id AND i.project_id = :projectId
             WHERE e.sprint_id = :sprintId
               AND e.workspace_id = :workspaceId
             ORDER BY e.occurred_at ASC, e.id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> ledger(@Param("sprintId") UUID sprintId,
                          @Param("workspaceId") UUID workspaceId,
                          @Param("projectId") UUID projectId,
                          @Param("limit") int limit);

    /**
     * The two {@code meta} scalars neither {@link #ledger} nor the caller can compute:
     * {@code firstIssueAt} and {@code basedOnIssues}.
     *
     * <p>A statement of its own rather than columns of {@link #ledger}, for a reason specific to
     * these two reports: the ledger query can legitimately return <strong>zero rows</strong> (a
     * sprint that never started; a started sprint nobody put anything in), and a scalar carried in
     * the select list of a query with no rows is a scalar that never arrives. The other reports can
     * splice these into an aggregate that always returns exactly one row; this one cannot, and
     * discovering that from a null {@code firstIssueAt} on an empty sprint would be a bug nobody
     * would think to look for.
     *
     * <h2>{@code firstIssueAt} — filters yes, window never</h2>
     * (HD-138 R3 round 3.) It is a property of the PROJECT, not of the sample this report
     * describes. This report has no filters and its window is the sprint, so the whole of the rule
     * here is: do not scope it to the sprint. An index min on {@code idx_issues_project_created}.
     *
     * <h2>{@code basedOnIssues} — counted in the DATABASE, above the cap</h2>
     * The distinct issues this sprint's ledger holds, <strong>independent of
     * {@code app.reports.max-rows}</strong>. Counting the grouped rows the reports actually shipped
     * would be the same number today and the wrong one the moment the cap bit: the family contract
     * ({@code ReportMeta}) is that this field states how many issues the report was ABOUT, which
     * when truncated is deliberately larger than what came back — it is what the reader can no
     * longer see. A field whose whole job is to disclose truncation must not shrink with the data
     * it is disclosing, and the sprint reports had it shrinking (HD-29 R4 round 2).
     *
     * <p>Unreachable today — hundreds of ledger rows against a cap of 20 000 — and fixed anyway,
     * because "unreachable" is a property of today's data and this contract is read by every
     * client of every report.
     *
     * <p>Counted the same way {@code SprintLedgerReader.group} groups: by {@code issue_id} where
     * there is one and by the snapshotted {@code issue_key} where there is not, so a deleted
     * issue counts once rather than collapsing every departed issue of the sprint into one. Two
     * definitions of "distinct issue" over one table would eventually disagree, so this one names
     * the other.
     *
     * <p>Costs no round trip and no statement: it rides on the scalar above. It scans the same
     * {@code idx_sprint_scope_events_sprint} range {@link #ledger} already reads — one sprint's
     * rows, hundreds of them — which is why it is a subquery here rather than a
     * {@code count(*) OVER ()} inside {@link #ledger}, where the measured cost is a {@code
     * WindowAgg} wedged between the {@code Limit} and the {@code Sort} (see that method).
     *
     * @param projectId   the project, for the project-wide {@code firstIssueAt}
     * @param workspaceId the caller's workspace, restated as this statement's own scope
     * @param sprintId    the resolved sprint, or {@code null} when the project has none — the
     *                    count is then 0, which is exactly right for a report with no subject
     * @return exactly one row, {@code [OffsetDateTime|null firstIssueAt, Number basedOnIssues]}
     */
    @Query(value = """
            SELECT min(i.created_at) AS first_issue_at,
                   (SELECT count(DISTINCT coalesce(CAST(e.issue_id AS text),
                                                   'key:' || e.issue_key))
                      FROM sprint_scope_events e
                     WHERE e.sprint_id = CAST(:sprintId AS uuid)
                       AND e.workspace_id = :workspaceId) AS based_on_issues
              FROM issues i
             WHERE i.project_id = :projectId
            """, nativeQuery = true)
    List<Object[]> metaCounts(@Param("projectId") UUID projectId,
                              @Param("workspaceId") UUID workspaceId,
                              @Param("sprintId") String sprintId);
}
