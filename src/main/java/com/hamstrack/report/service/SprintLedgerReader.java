package com.hamstrack.report.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.ReportProperties;
import com.hamstrack.issue.entity.Sprint;
import com.hamstrack.issue.entity.SprintScopeEventType;
import com.hamstrack.issue.entity.SprintState;
import com.hamstrack.issue.exception.SprintNotFoundException;
import com.hamstrack.issue.repository.SprintRepository;
import com.hamstrack.report.repository.SprintReportRepository;
import com.hamstrack.report.service.SprintLedger.LedgerIssue;
import com.hamstrack.report.service.SprintLedger.LedgerIssue.Move;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves a sprint report's <strong>subject</strong> and loads its ledger — the one piece of
 * plumbing the burn-up (§2.3) and the sprint review (§2.4) share, so that tenancy, the sprint
 * picker, the row cap and the meta block are written once and cannot drift apart between two
 * reports drawn from one table.
 *
 * <h2>Tenancy, and the sprint's place in it</h2>
 * {@code WorkspaceAccessService.resolveProject} first, before anything else is looked at: a missing
 * workspace, a missing project and a non-member all answer <strong>404</strong>, never a 403 and
 * never anything that confirms a project exists to somebody who may not know it does.
 *
 * <p>Then the sprint, through {@code findByIdAndProject} — inside the project that resolution just
 * proved membership of. A sprint id from another project (or another tenant) resolves to empty and
 * lands on the same 404 as one that never existed.
 *
 * <p><strong>{@code sprintId} is a SUBJECT, not a filter</strong>, and that is the whole difference
 * from R1/R3's {@code typeId}/{@code componentId}/{@code labelId}. Those are predicates applied
 * inside an already project-scoped statement, where an unknown id can only subtract — so they
 * narrow to an empty chart and are disclosed in {@code meta.unmatchedFilters}. This one addresses
 * the resource the whole report is about, so it resolves, and an id that does not resolve is a 404.
 * {@code ReportController} states both halves of that rule.
 *
 * <h2>The default: the ACTIVE sprint, and what happens when there is none</h2>
 * Omitting {@code sprintId} means "the sprint we are in" (§2.3). A project has at most one ACTIVE
 * sprint ({@code sprints_one_active_per_project_uk}), so that default is unambiguous.
 *
 * <p>When there is no ACTIVE sprint the answer is a <strong>200 with {@code sprint: null}</strong>
 * and empty everything, not a 404. The caller asked a well-formed question about a project it can
 * see, and the honest answer is "there is no sprint running" — while a 404 would be the same status
 * this class returns for "that sprint is not in this project", leaving a client unable to tell a
 * project between sprints from a bad id. It also keeps §6's empty state renderable: the UI replaces
 * the chart with the Rule C card, which it can only do if it got a body.
 *
 * <p>Deliberately NOT "fall back to the most recently completed sprint". That would often be the
 * more useful chart, and it would also be a report whose subject the caller never chose and cannot
 * see in the URL. The picker is the client's job, and a client that wants the last completed sprint
 * can ask for it by id.
 *
 * <h2>Capabilities decide nothing here</h2>
 * Neither {@code board} nor {@code estimation} is consulted. The API answers for any sprint that
 * exists, in any project, whatever its delivery capabilities (Rule A) — a KANBAN project's sprint
 * returns exactly the body a SCRUM project's does. Capabilities gate the UI; a status code that
 * depended on one would be the documented bug that model exists to delete. Nor is anything here
 * decided by whether sprints exist in the data.
 *
 * <h2>Cost</h2>
 * Three statements after the four the project resolution pays: the sprint, the ledger, and the
 * project's earliest issue for {@code meta}. Identical whether {@code sprintId} was supplied or
 * defaulted — the picker costs the same either way — and one fewer when there is no sprint at all.
 * Pinned by {@code SprintReportQueryCountTest}.
 */
@Service
@RequiredArgsConstructor
public class SprintLedgerReader {

    private final WorkspaceAccessService workspaceAccess;
    private final SprintRepository sprintRepository;
    private final SprintReportRepository sprintReportRepository;
    private final ReportProperties reportProperties;

    @Transactional(readOnly = true)
    public SprintLedger read(User actor, UUID workspaceId, UUID projectId, UUID sprintId) {
        // 1. Tenancy first. 404 for a missing workspace, a missing project and a non-member alike.
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        var project = ctx.project();

        // 2. Then the subject, INSIDE that project. A foreign sprint id is a 404, not a 403.
        var sprint = sprintId == null
                ? sprintRepository.findActiveByProject(project).orElse(null)
                : sprintRepository.findByIdAndProject(sprintId, project)
                        .orElseThrow(SprintNotFoundException::new);

        int cap = reportProperties.maxRows();
        // Its own statement, and always run: the ledger query returns no rows for an empty sprint,
        // so a scalar spliced into it would silently go missing exactly there. It also carries
        // basedOnIssues, counted in the database ABOVE the cap — see the repository for why that
        // number may not be derived from the rows that survived truncation.
        var meta = row(sprintReportRepository.metaCounts(project.getId(), ctx.workspace().getId(),
                sprint == null ? null : sprint.getId().toString()));
        var firstIssueAt = instant(meta[0]);
        long basedOnIssues = count(meta[1]);

        if (sprint == null) {
            return new SprintLedger(null, List.of(), false, cap, firstIssueAt, null, 0);
        }

        // One row past the cap: a full result set IS the truncation signal.
        var rows = sprintReportRepository.ledger(
                sprint.getId(), ctx.workspace().getId(), project.getId(), cap + 1);
        boolean truncated = rows.size() > cap;
        var shipped = truncated ? rows.subList(0, cap) : rows;
        return new SprintLedger(sprint, group(shipped), truncated, cap, firstIssueAt,
                endBoundary(sprint), basedOnIssues);
    }

    /**
     * Where this sprint's record stops — resolved once per request, for both reports.
     *
     * <p>{@code completed_at} for a sprint that has been completed, and <strong>now</strong> for
     * everything else, including a sprint that has run past its planned {@code end_at}: the line
     * ends where it ends, and drawing a record out to a future planned end would be a projection
     * neither report makes. {@link SprintLedger} carries the rest of the reasoning, including why
     * this is an instant rather than the end of a day.
     */
    private static OffsetDateTime endBoundary(Sprint sprint) {
        return sprint.getState() == SprintState.COMPLETED && sprint.getCompletedAt() != null
                ? sprint.getCompletedAt()
                : OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ grouping

    /**
     * Ledger rows into one entry per issue, each carrying its moves in the order the statement
     * returned them (oldest first).
     *
     * <p><strong>Grouped by issue id when there is one, and by the snapshotted key when there is
     * not.</strong> A deleted issue's rows all carry {@code issue_id = NULL}, so grouping on the id
     * alone would collapse every departed issue of a sprint into one phantom whose ADDs and REMOVEs
     * interleave — a scope line that is not merely wrong but incoherent. The key distinguishes
     * them, and it is unique per issue within a project (issue numbers are never reused). The id is
     * still preferred wherever it exists, so that a project re-key cannot split one live issue's
     * history in two.
     */
    private static List<LedgerIssue> group(List<Object[]> rows) {
        Map<Object, Accumulator> byIssue = new LinkedHashMap<>();
        for (var row : rows) {
            var eventIssueId = uuid(row[0]);
            var key = (String) row[1];
            var accumulator = byIssue.computeIfAbsent(
                    eventIssueId != null ? eventIssueId : "key:" + key,
                    identity -> new Accumulator(key, row));
            accumulator.add(row);
        }
        var issues = new ArrayList<LedgerIssue>(byIssue.size());
        byIssue.values().forEach(accumulator -> issues.add(accumulator.build()));
        return List.copyOf(issues);
    }

    /**
     * Collects one issue's rows. The live-issue columns are identical on every row of a group (they
     * come from one LEFT join), so the first row establishes them; only the moves and the entry
     * snapshot accumulate.
     */
    private static final class Accumulator {
        private final String key;
        private final UUID issueId;
        private final String title;
        private final UUID typeId;
        private final UUID assigneeId;
        private final UUID statusId;
        private final OffsetDateTime closedAt;
        private final BigDecimal currentPoints;
        private final List<Move> moves = new ArrayList<>(2);
        /** The estimate on the issue's FIRST ADD — what it weighed when it entered this sprint. */
        private BigDecimal snapshotPoints;
        private boolean snapshotTaken;

        private Accumulator(String key, Object[] first) {
            this.key = key;
            this.issueId = uuid(first[6]);
            this.title = (String) first[7];
            this.typeId = uuid(first[8]);
            this.assigneeId = uuid(first[9]);
            this.statusId = uuid(first[10]);
            this.currentPoints = decimal(first[11]);
            this.closedAt = instant(first[12]);
        }

        private void add(Object[] row) {
            var event = SprintScopeEventType.valueOf((String) row[2]);
            moves.add(new Move(instant(row[3]), event, uuid(row[4])));
            if (!snapshotTaken && event == SprintScopeEventType.ADDED) {
                snapshotPoints = decimal(row[5]);
                snapshotTaken = true;
            }
        }

        /**
         * <p><strong>A deleted issue's moves lose their actor here</strong> (HD-29 R4 round 2).
         * The row in the database keeps its {@code actor_id} — nothing is erased — but a report
         * about an issue that no longer exists does not name the person who moved it. Attribution
         * for a live issue is reachable from its history; {@code issue_history.issue_id} is
         * {@code ON DELETE CASCADE}, so after a delete this ledger would otherwise be the only
         * surviving place in the product that says who did what to that issue and exactly when,
         * which is a wider survival than the design argued for. See {@link Move}.
         */
        private LedgerIssue build() {
            boolean deleted = issueId == null;
            var shipped = deleted ? anonymised(moves) : List.copyOf(moves);
            return new LedgerIssue(issueId, key, deleted, title, typeId, assigneeId,
                    statusId, closedAt, currentPoints, snapshotPoints, shipped);
        }

        private static List<Move> anonymised(List<Move> moves) {
            var out = new ArrayList<Move>(moves.size());
            for (var move : moves) {
                out.add(new Move(move.at(), move.event(), null));
            }
            return List.copyOf(out);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The one row of a single-row aggregate. An aggregate with no {@code GROUP BY} always returns
     * exactly one row, so the empty case is a driver fault rather than an empty project — answered
     * with nulls rather than an index exception.
     */
    private static Object[] row(List<Object[]> rows) {
        return rows.isEmpty() ? new Object[]{null, null} : rows.get(0);
    }

    /** A native {@code bigint} count — the shapes a driver can hand one back as. */
    private static long count(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    /** A native {@code numeric} column. Never widened to double: points are exact decimals. */
    private static BigDecimal decimal(Object raw) {
        return switch (raw) {
            case null -> null;
            case BigDecimal value -> value;
            case Number value -> BigDecimal.valueOf(value.doubleValue());
            default -> throw new IllegalStateException(
                    "unexpected numeric type from the report query: " + raw.getClass());
        };
    }

    /** A native {@code uuid} column. Accepts the text form a driver change could produce. */
    private static UUID uuid(Object raw) {
        return switch (raw) {
            case null -> null;
            case UUID id -> id;
            case String s -> UUID.fromString(s);
            default -> throw new IllegalStateException(
                    "unexpected uuid type from the report query: " + raw.getClass());
        };
    }

    /** A native {@code timestamptz} column, normalised to UTC — the shapes a driver can hand back. */
    private static OffsetDateTime instant(Object raw) {
        return switch (raw) {
            case null -> null;
            case OffsetDateTime odt -> odt.withOffsetSameInstant(ZoneOffset.UTC);
            case java.time.Instant i -> i.atOffset(ZoneOffset.UTC);
            case java.sql.Timestamp ts -> ts.toInstant().atOffset(ZoneOffset.UTC);
            default -> throw new IllegalStateException(
                    "unexpected timestamp type from the report query: " + raw.getClass());
        };
    }
}
