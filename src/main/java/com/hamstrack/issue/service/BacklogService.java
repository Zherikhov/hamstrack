package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.AgileProperties;
import com.hamstrack.common.config.ClassificationProperties;
import com.hamstrack.issue.dto.BacklogSectionResponse;
import com.hamstrack.issue.dto.BacklogViewResponse;
import com.hamstrack.issue.dto.IssueResponse;
import com.hamstrack.issue.dto.LabelMatch;
import com.hamstrack.issue.dto.SectionStats;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.Sprint;
import com.hamstrack.issue.entity.StatusCategory;
import com.hamstrack.issue.entity.VersionLinkType;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The project's planning READS — the whole view and any one section of it (HD-22,
 * agile-sprints-proposal §4.4, §4.6; HD-96). {@link #view} returns every section the
 * BacklogPage renders in one request — the project's OPEN sprints (ACTIVE first, then
 * FUTURE by sequence) above the ranked backlog — and {@link #section} returns exactly one
 * of them, for a client refreshing a section it already has.
 *
 * <p><strong>Every read here answers under one honesty protocol.</strong> Whatever a
 * planning read returns, it says how much it withheld the same way: {@code sectionCap} in
 * force, {@code truncated} meaning "this section holds more matching issues than
 * {@code sectionCap}", {@code totalAvailable} and {@link SectionStats} computed over the
 * WHOLE section by the grouped query rather than over the rows returned. HD-96 was a
 * refresh that went out through the general issue list instead, where {@code truncated}
 * means "more than the page you received" and the page size was silently narrowed — same
 * words, different predicate, no error anywhere. A section fetch therefore takes no
 * {@code page} and no {@code size}: there is no number for a client to hold, none to echo
 * back, and nothing to clamp.
 *
 * <p><strong>Why this is its own service.</strong> It needs both {@link SprintService}
 * (sections, stats) and {@link IssueService} (the batched row assembly), while
 * {@code IssueService} itself needs {@code SprintService} to resolve a
 * {@code sprintId} payload. Putting the view here keeps that dependency graph acyclic
 * — constructor injection cannot resolve a cycle.
 *
 * <p><strong>Query budget (§4.6).</strong> A 300-issue planning view is
 * {@code 1 (sprints) + 1 (grouped stats) + S (section fetches) + a constant} — never
 * per-issue. Every section's rows are concatenated and mapped through
 * {@link IssueService#toResponsesBatched} ONCE, so the label / version / custom-field /
 * roll-up / parent loaders each run a single time for the whole view instead of once
 * per section.
 *
 * <p><strong>Truncation is per section</strong> (the HD-79 pattern), and the stats are
 * computed over the WHOLE section by the grouped query — so a truncated section still
 * shows honest totals.
 */
@Service
@RequiredArgsConstructor
public class BacklogService {

    private final WorkspaceAccessService workspaceAccess;
    private final SprintService sprintService;
    private final IssueService issueService;
    private final IssueRepository issueRepository;
    private final AgileProperties agileProperties;
    private final ClassificationProperties classificationProperties;

    /**
     * The whole planning view — any project member (reads are unrestricted within the
     * project; the sprint LIFECYCLE is what needs a curator).
     *
     * <p>{@code includeDone} defaults to false at the controller: a done, unranked issue
     * is planning noise. It applies to the BACKLOG section only — sprint sections always
     * include their DONE issues, because that is the sprint's record of what it
     * delivered.
     */
    @Transactional(readOnly = true)
    public BacklogViewResponse view(User actor, UUID workspaceId, UUID projectId,
                                    UUID statusId, UUID assigneeId, UUID priorityId,
                                    UUID componentId, List<UUID> labelIds, LabelMatch labelMatch,
                                    UUID fixVersionId, boolean includeDone) {
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        var labelFilter = IssueService.LabelFilter.of(labelIds, labelMatch,
                classificationProperties.maxLabelsPerIssue());

        var openSprints = sprintService.openSprintsOf(project);
        var sprintIds = openSprints.isEmpty()
                ? IssueRepository.NO_SPRINTS_SENTINEL
                : openSprints.stream().map(Sprint::getId).toList();

        // ONE grouped query for the ENTIRE view — the NULL group is the backlog (§4.6),
        // and the view renders that group, so this is the caller that asks for it.
        var statsBySprint = new HashMap<UUID, SprintService.StatsRow>();
        for (var row : issueRepository.planningStats(project, sprintIds, true,
                statusId, assigneeId, priorityId, componentId,
                labelFilter.ids(), labelFilter.count(), labelFilter.requiredMatches(),
                fixVersionId, VersionLinkType.FIX, StatusCategory.DONE)) {
            statsBySprint.put((UUID) row[0], SprintService.statsRow(row));
        }

        int cap = agileProperties.sectionMaxIssues();
        // Fetch cap+1 per section so a single query per section tells us whether more
        // exist — the same trick the capped board list uses.
        var pageable = PageRequest.of(0, cap + 1);

        var sprintRows = new LinkedHashMap<UUID, List<Issue>>();
        for (var sprint : openSprints) {
            sprintRows.put(sprint.getId(), issueRepository.findSectionIssues(
                    project, sprint.getId(), statusId, assigneeId, priorityId, componentId,
                    labelFilter.ids(), labelFilter.count(), labelFilter.requiredMatches(),
                    fixVersionId, VersionLinkType.FIX,
                    false, StatusCategory.DONE, pageable));
        }
        var backlogRows = issueRepository.findSectionIssues(
                project, null, statusId, assigneeId, priorityId, componentId,
                labelFilter.ids(), labelFilter.count(), labelFilter.requiredMatches(),
                fixVersionId, VersionLinkType.FIX,
                !includeDone, StatusCategory.DONE, pageable);

        // ONE batched assembly for every section's rows together, so the label / version
        // / field-value / roll-up / parent loaders run once for the whole view.
        var all = new ArrayList<Issue>();
        sprintRows.values().forEach(all::addAll);
        all.addAll(backlogRows);
        var responseById = new HashMap<UUID, IssueResponse>(all.size());
        for (var response : issueService.toResponsesBatched(all)) {
            responseById.put(response.id(), response);
        }

        var sections = new ArrayList<BacklogViewResponse.SprintSection>(openSprints.size());
        for (var sprint : openSprints) {
            var stats = statsBySprint.containsKey(sprint.getId())
                    ? statsBySprint.get(sprint.getId()).full()
                    : SectionStats.EMPTY;
            var rows = sprintRows.get(sprint.getId());
            boolean truncated = rows.size() > cap;
            sections.add(new BacklogViewResponse.SprintSection(
                    sprintService.respondWith(sprint, stats),
                    responses(truncated ? rows.subList(0, cap) : rows, responseById),
                    truncated, stats.issueCount(), stats));
        }

        // The backlog's NULL group. With includeDone = false the stats are DERIVED from
        // the same grouped row by subtracting its DONE half — honest totals without a
        // second query.
        var backlogRow = statsBySprint.get(null);
        var backlogStats = backlogRow == null ? SectionStats.EMPTY
                : includeDone ? backlogRow.full() : backlogRow.excludingDone();
        boolean backlogTruncated = backlogRows.size() > cap;
        var backlog = new BacklogViewResponse.Section(
                responses(backlogTruncated ? backlogRows.subList(0, cap) : backlogRows, responseById),
                backlogTruncated, backlogStats.issueCount(), backlogStats);

        // Both caps travel with the view: `sectionCap` is what got rendered,
        // `bulkMoveCap` is how many ids one "move all to…" request may carry. They are
        // independent operator knobs (300 vs 100 by default), so the client must be told
        // the second rather than infer it from the first.
        return new BacklogViewResponse(sections, backlog, cap,
                agileProperties.maxIssuesPerBulkMove());
    }

    /**
     * ONE section of the planning view (HD-96) — {@code sprintId == null} is the BACKLOG
     * section, the encoding this domain already uses ({@code Issue.sprint} null is the
     * backlog; the grouped stats query's NULL group is the backlog).
     *
     * <p><strong>It must return exactly what {@link #view} returns for the same section
     * under the same filters</strong>, which is why it reuses that method's queries rather
     * than the general issue list's: {@code findSectionIssues} owns the canonical rank
     * ordering (so no caller ever declares a second copy of it) and fetch-joins
     * {@code i.sprint}, which {@code IssueResponse} renders and which is LAZY; and
     * {@code planningStats} is the same grouped, cap-blind, filter-aware query, so the
     * numbers cannot drift by construction rather than by discipline. Read
     * {@code BacklogSectionParityTest} before changing either call — it asserts the two
     * surfaces field-for-field.
     *
     * <p>Same permissions as the aggregate: any member of the owning workspace, resolved
     * through {@code resolveProject} (404 for a missing workspace, a missing project, a
     * project in another workspace and a non-member alike), and the sprint resolved
     * <em>through the project</em> by {@code requireSprint} — a bare {@code findById} here
     * would answer for a sibling project's sprint. No permission is required: a refresh must
     * not fail where the render that produced it succeeded. A COMPLETED sprint answers 200
     * like {@code GET /sprints/{id}} does; a section that has dropped out of the aggregate is
     * still a section that existed a moment ago, and refusing would let state change a
     * status code.
     *
     * <p><strong>Query budget, and what a refresh does NOT divide.</strong>
     * {@code 2 (tenancy) + 1 (stats) + 1 (rows) + the constant assembly block}, plus one to
     * resolve the sprint when the request names one. A section fetch divides the row
     * assembly and the response — the aggregate runs one row query per open section and
     * assembles them all — but it <em>repeats</em> the grouped stats query, which is the
     * unconditional, cap-blind term: it reads and groups a whole section whatever the filters
     * say and whatever the cap is. So refreshing every section of a view one at a time ships
     * the same bytes as one {@code GET …/backlog} while running that aggregation once per
     * section rather than once. That is why the budget belongs to the whole planning surface
     * rather than to this endpoint, and why it is one pattern
     * ({@code PlanningRateLimitConfig.PLANNING_PATH}) covering the aggregate and every
     * section alike — the reasoning is unchanged from when HD-174 was a follow-up; only the
     * tense is.
     *
     * <p><strong>And the occupancy half is earned here too, on a term a rate cannot
     * see.</strong> This method is {@code @Transactional(readOnly = true)} over its whole
     * body, so one pool connection is held across every statement above — 11–12 for a
     * section, {@code 12 + N} for {@link #view}. {@code DB_STATEMENT_TIMEOUT_MS} bounds each
     * statement and nothing bounds their sum, which is why the planning surface takes a
     * permit from the shared expensive-read share (ADR-0031) as well as spending a budget.
     *
     * <p>Not to be paraphrased as "everything else is bounded by what it returns" — it is
     * not. {@code findSectionIssues} is capped in OUTPUT only: a {@code LIMIT} bounds what
     * comes back, never what is selected, and the section's {@code ORDER BY} orders the whole
     * filtered set before the limit can apply, so a filter matching little still visits the
     * section to return almost nothing. The distinction that actually earns a budget is
     * <em>unconditional and cap-blind</em>, not bounded versus unbounded.
     *
     * <p>Narrowed as far as the data model allows: a SPRINT section asks for its own group
     * only, so the aggregation reads and groups that sprint's rows instead of the project's
     * (and the planner drops the sort it needed to separate two groups). The BACKLOG section
     * still scans its group, because that group is genuinely what was asked for — that half
     * is inherent, and it is the term HD-174's budget is earned by.
     *
     * <p>{@code includeDone} applies to the BACKLOG section only, exactly as in the
     * aggregate: a sprint section always includes its DONE issues, because that is the
     * sprint's record of what it delivered.
     */
    @Transactional(readOnly = true)
    public BacklogSectionResponse section(User actor, UUID workspaceId, UUID projectId,
                                          UUID sprintId,
                                          UUID statusId, UUID assigneeId, UUID priorityId,
                                          UUID componentId, List<UUID> labelIds, LabelMatch labelMatch,
                                          UUID fixVersionId, boolean includeDone) {
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        var labelFilter = IssueService.LabelFilter.of(labelIds, labelMatch,
                classificationProperties.maxLabelsPerIssue());

        Sprint sprint = sprintId == null ? null : sprintService.requireSprint(project, sprintId);
        boolean isBacklog = sprint == null;
        UUID sectionKey = isBacklog ? null : sprint.getId();

        // The aggregate's grouped stats query — the SAME statement, so the numbers cannot
        // drift — asking for this section's group and no other. That group is not free: it
        // is the unconditional, cap-blind term of this handler (see
        // IssueRepository.planningStats), so a sprint fetch aggregates that sprint's rows
        // instead of the project's rather than scanning both groups and discarding half of
        // the answer here. sectionStats derives the id list and the backlog flag together
        // from ONE nullable key, so the pairing that would silently answer zeros cannot be
        // written here. The wanted group is still picked BY KEY rather than assumed to be
        // the only row — the query's shape is the caller's request, not its guarantee.
        SprintService.StatsRow sectionRow = null;
        for (var row : issueRepository.sectionStats(project, sectionKey,
                statusId, assigneeId, priorityId, componentId,
                labelFilter.ids(), labelFilter.count(), labelFilter.requiredMatches(),
                fixVersionId, VersionLinkType.FIX, StatusCategory.DONE)) {
            if (Objects.equals(row[0], sectionKey)) {
                sectionRow = SprintService.statsRow(row);
            }
        }
        // A backlog hiding its DONE issues derives its numbers by subtracting the same
        // row's DONE half — honest totals without a second query, as in view().
        var stats = sectionRow == null ? SectionStats.EMPTY
                : isBacklog && !includeDone ? sectionRow.excludingDone()
                : sectionRow.full();

        int cap = agileProperties.sectionMaxIssues();
        // cap + 1: one query answers both "which rows" and "are there more" (§4.5 step 5).
        var rows = issueRepository.findSectionIssues(
                project, sectionKey, statusId, assigneeId, priorityId, componentId,
                labelFilter.ids(), labelFilter.count(), labelFilter.requiredMatches(),
                fixVersionId, VersionLinkType.FIX,
                isBacklog && !includeDone, StatusCategory.DONE, PageRequest.of(0, cap + 1));
        boolean truncated = rows.size() > cap;

        return new BacklogSectionResponse(
                isBacklog ? null : sprintService.respondWith(sprint, stats),
                issueService.toResponsesBatched(truncated ? rows.subList(0, cap) : rows),
                truncated, stats.issueCount(), stats,
                cap, agileProperties.maxIssuesPerBulkMove());
    }

    private List<IssueResponse> responses(List<Issue> rows, Map<UUID, IssueResponse> byId) {
        return rows.stream().map(i -> byId.get(i.getId())).toList();
    }
}
