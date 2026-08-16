package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.AgileProperties;
import com.hamstrack.common.config.ClassificationProperties;
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
import java.util.UUID;

/**
 * The planning aggregate behind {@code GET …/projects/{p}/backlog} (HD-22,
 * agile-sprints-proposal §4.4, §4.6) — one request returns every section the
 * BacklogPage renders: the project's OPEN sprints (ACTIVE first, then FUTURE by
 * sequence) above the ranked backlog.
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
     * A UUID no row can carry, bound when the project has no open sprint: an empty
     * {@code IN} list is invalid in JPQL/Hibernate, and the grouped stats query still
     * has to return its NULL (backlog) group.
     */
    private static final List<UUID> NO_SPRINTS_SENTINEL = List.of(new UUID(0, 0));

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
        var project = workspaceAccess.requireProjectMember(actor, workspaceId, projectId).project();
        var labelFilter = IssueService.LabelFilter.of(labelIds, labelMatch,
                classificationProperties.maxLabelsPerIssue());

        var openSprints = sprintService.openSprintsOf(project);
        var sprintIds = openSprints.isEmpty()
                ? NO_SPRINTS_SENTINEL
                : openSprints.stream().map(Sprint::getId).toList();

        // ONE grouped query for the ENTIRE view — the NULL group is the backlog (§4.6).
        var statsBySprint = new HashMap<UUID, SprintService.StatsRow>();
        for (var row : issueRepository.planningStats(project, sprintIds,
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

    private List<IssueResponse> responses(List<Issue> rows, Map<UUID, IssueResponse> byId) {
        return rows.stream().map(i -> byId.get(i.getId())).toList();
    }
}
