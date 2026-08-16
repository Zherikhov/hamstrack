package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.config.BoardProperties;
import com.hamstrack.common.config.ClassificationProperties;
import com.hamstrack.common.dto.PageResponse;
import com.hamstrack.common.event.FieldChange;
import com.hamstrack.common.event.IssueCreated;
import com.hamstrack.common.event.IssueDeleted;
import com.hamstrack.common.event.IssueUpdated;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.issue.dto.BoardIssuesResponse;
import com.hamstrack.issue.dto.CreateIssueRequest;
import com.hamstrack.issue.dto.IssueHistoryResponse;
import com.hamstrack.issue.dto.IssueResponse;
import com.hamstrack.issue.dto.LabelMatch;
import com.hamstrack.issue.dto.UpdateIssueRequest;
import com.hamstrack.issue.entity.*;
import com.hamstrack.issue.exception.IssueNotFoundException;
import com.hamstrack.issue.repository.*;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IssueService {

    private final WorkspaceAccessService workspaceAccess;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final IssueRepository issueRepository;
    private final IssueTypeRepository issueTypeRepository;
    private final StatusRepository statusRepository;
    private final PriorityRepository priorityRepository;
    private final UserRepository userRepository;
    private final IssueHistoryRepository historyRepository;
    private final ProjectConfigService projectConfigService;
    private final FieldValueService fieldValueService;
    private final LabelService labelService;
    private final ComponentService componentService;
    private final VersionService versionService;
    private final AttachmentService attachmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductMetrics metrics;
    private final BoardProperties boardProperties;
    private final ClassificationProperties classificationProperties;
    // The MVC Jackson 3 mapper (carries the Jackson2NodeModule bridge). Used to
    // serialize the creation response INSIDE the creation transaction, so a
    // serialization failure rolls the insert back rather than committing then
    // 500ing during MVC's post-commit writing — see createSerialized / bug #2.
    private final JsonMapper jsonMapper;

    /**
     * The board/backlog label filter parameters (HD-30 §3.6), normalized once so every
     * repository call binds the same triple.
     *
     * <p><strong>Empty-{@code IN}-list sentinel:</strong> JPQL/Hibernate reject an empty
     * {@code IN} list, so with no labels selected we bind
     * {@code ids = [00000000-0000-0000-0000-000000000000]} (a UUID no row can carry)
     * together with {@code count = 0} — and {@code :labelCount = 0} short-circuits the
     * whole predicate before the sub-select is ever evaluated. Never bind an empty list.
     *
     * <p><strong>Bounded:</strong> the repeatable {@code ?labelId=} parameter is
     * capped at {@code app.classification.max-labels-per-issue} distinct ids (an issue
     * can't carry more than that anyway, so a longer list can only be noise or abuse).
     * Beyond it → 400. Two reasons: a large {@code IN} inside a correlated per-row
     * sub-select is real work on every board load, and each distinct list length
     * compiles to a distinct SQL string — an uncapped parameter churns the query-plan
     * cache.
     *
     * @param ids             label ids to match, or the sentinel when the filter is off
     * @param count           number of distinct selected labels (0 = filter off)
     * @param requiredMatches 1 for {@code any} (OR), {@code count} for {@code all} (AND)
     */
    record LabelFilter(List<UUID> ids, int count, long requiredMatches) {

        private static final List<UUID> NO_MATCH_SENTINEL = List.of(new UUID(0, 0));

        static LabelFilter of(List<UUID> labelIds, LabelMatch labelMatch, int maxLabels) {
            if (labelIds == null || labelIds.isEmpty()) {
                return new LabelFilter(NO_MATCH_SENTINEL, 0, 1L);
            }
            var distinct = new java.util.LinkedHashSet<>(labelIds);
            distinct.remove(null);
            if (distinct.isEmpty()) {
                return new LabelFilter(NO_MATCH_SENTINEL, 0, 1L);
            }
            if (distinct.size() > maxLabels) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "At most " + maxLabels + " labelId filter values");
            }
            // Default is "any" (OR) — matches tag-filter intuition (open question 2).
            return new LabelFilter(List.copyOf(distinct), distinct.size(),
                    labelMatch == LabelMatch.ALL ? distinct.size() : 1L);
        }
    }

    /**
     * Atomically create: assembles AND serializes the {@link IssueResponse} inside the
     * creation transaction, returning the response bytes. This guarantees the
     * client-visible outcome is all-or-nothing — either a 201 with a fully
     * materialized body or a 4xx/5xx with the insert rolled back. Previously the
     * DTO was returned and serialized by Spring MVC *after* the transaction
     * committed, so any assembly/serialization failure (e.g. navigating a child
     * issue's parent, or the Jackson 2/3 JSONB boundary on a custom-field value)
     * left the row persisted while the client saw a 500 — a retry then created a
     * duplicate. Serializing here moves every failure mode ahead of the commit.
     */
    @Transactional
    public byte[] createSerialized(User actor, UUID workspaceId, UUID projectId, CreateIssueRequest req) {
        var response = create(actor, workspaceId, projectId, req);
        // Serialize while the transaction is still open: a failure here rolls back.
        return jsonMapper.writeValueAsBytes(response);
    }

    @Transactional
    public IssueResponse create(User actor, UUID workspaceId, UUID projectId, CreateIssueRequest req) {
        var ctx = workspaceAccess.requireProjectMember(actor, workspaceId, projectId);
        var workspace = ctx.workspace();
        var project = ctx.project();
        requireNotArchived(project);

        var type = projectConfigService.requireTypeInSet(project, resolveType(req.typeId()));
        var status = projectConfigService.requireStatusInWorkflow(project, resolveStatus(req.statusId()));
        var priority = req.priorityId() != null
                ? projectConfigService.requirePriorityInSet(project, resolvePriority(req.priorityId()))
                // re-resolve the default id to a managed entity (the config cache hands back detached ones)
                : resolvePriority(projectConfigService.defaultPriorityId(project));
        // HD-30: resolve labels BEFORE the issue is built/saved — a foreign, unknown
        // or over-cap id must 422 before anything is written (and resolving after a
        // mutation is the documented @Version double-bump trap).
        var labels = labelService.resolveForIssue(workspace, req.labelIds());
        // HD-31: same ordering rule. A component of another project is a 422 "Unknown
        // component"; assigning an ARCHIVED one is a 422 too (every create-time value
        // is a new assignment).
        var component = componentService.resolveForIssue(project, req.componentId());
        componentService.requireAssignable(component);
        // HD-32: same ordering rule again. Each role is resolved against the issue's OWN
        // project and carries its own cap — a foreign/unknown id or an over-cap set is a
        // 422 before anything is written.
        var fixVersions = versionService.resolveForIssue(project, req.fixVersionIds(), VersionLinkType.FIX);
        var affectsVersions = versionService.resolveForIssue(
                project, req.affectsVersionIds(), VersionLinkType.AFFECTS);
        // Auto-assign (§5.1): an explicit assigneeId ALWAYS wins; otherwise a component
        // with auto-assign + a lead who is still a workspace member supplies one. A
        // stale lead is skipped silently — it must never fail issue creation. Resolved
        // here, with the other reads, for the same @Version ordering reason.
        var assignee = req.assigneeId() != null
                ? resolveAssignee(workspace, req.assigneeId())
                : componentService.autoAssignee(workspace, component);

        // Atomic seq increment
        long seq = projectRepository.incrementAndGetIssueSeq(project.getId());

        var issue = new Issue();
        issue.setWorkspace(workspace);
        issue.setProject(project);
        issue.setNumber(seq);
        issue.setTitle(req.title());
        issue.setDescription(req.description());
        issue.setType(type);
        issue.setStatus(status);
        issue.setPriority(priority);
        issue.setReporter(actor);
        issue.setPosition(seq);
        if (status.getCategory() == StatusCategory.DONE) {
            issue.setClosedAt(OffsetDateTime.now());
        }

        issue.setAssignee(assignee);
        issue.setComponent(component);
        if (req.parentId() != null) {
            var parent = resolveParent(req.parentId(), project);
            // Adjacency guard: parent.type must be exactly one tier above the child
            // (Revision 2). Cycle/self-guards are trivially N/A on creation (the new
            // issue has no descendants yet).
            requireLegalParentLevel(parent.getType(), type);
            issue.setParent(parent);
        }
        issue.setDueDate(req.dueDate());
        issueRepository.save(issue);
        // Labels attach after the insert (the join rows need a persisted issue id).
        // No history entries for create-time values (consistent with custom fields).
        labelService.attachAll(issue, labels);
        // Same for the version links (HD-32) — the join rows need a persisted issue id,
        // and create-time values write no history.
        versionService.attachAll(issue, fixVersions, VersionLinkType.FIX);
        versionService.attachAll(issue, affectsVersions, VersionLinkType.AFFECTS);
        // Only system (global) type names are label-safe; scoped types → "custom"
        metrics.issueCreated(type.getName(),
                type.getScopeWorkspaceId() == null && type.getScopeProjectId() == null);

        // Custom fields: validates against the project's field set (incl.
        // required-on-create); no history entries for initial values
        fieldValueService.applyValues(issue, req.fields(), true, (f, o, n) -> {});

        eventPublisher.publishEvent(new IssueCreated(workspaceId, projectId, issue.getNumber()));
        return toResponse(issue);
    }

    /**
     * Capped board issue list (HD-79). The no-{@code size} board path used to return
     * EVERY issue in the project (OOM/latency risk); this bounds it to
     * {@code app.board.max-issues}, enforced server-side (never client-overridable).
     * Fetches {@code cap+1} in one query to detect truncation, trims to {@code cap},
     * and reports {@code totalAvailable} via a cheap filtered count so the SPA can
     * render a truncation banner. Tenant scoping + filters are identical to before.
     */
    @Transactional(readOnly = true)
    public BoardIssuesResponse listCapped(User actor, UUID workspaceId, UUID projectId,
                                          UUID statusId, UUID assigneeId, UUID priorityId,
                                          UUID componentId, List<UUID> labelIds, LabelMatch labelMatch,
                                          UUID fixVersionId) {
        var project = workspaceAccess.requireProjectMember(actor, workspaceId, projectId).project();
        var labelFilter = LabelFilter.of(labelIds, labelMatch,
                classificationProperties.maxLabelsPerIssue());

        int cap = boardProperties.maxIssues();
        // Fetch cap+1 (in board order) so a single query tells us whether more exist.
        var fetched = issueRepository.findByProjectFilteredCapped(
                project, statusId, assigneeId, priorityId, componentId,
                labelFilter.ids(), labelFilter.count(), labelFilter.requiredMatches(),
                fixVersionId, VersionLinkType.FIX,
                PageRequest.of(0, cap + 1));
        boolean truncated = fetched.size() > cap;
        var capped = truncated ? fetched.subList(0, cap) : fetched;
        // Only pay for the full count when truncated; otherwise it equals the size.
        long totalAvailable = truncated
                ? issueRepository.countByProjectFiltered(project, statusId, assigneeId, priorityId,
                        componentId, labelFilter.ids(), labelFilter.count(), labelFilter.requiredMatches(),
                        fixVersionId, VersionLinkType.FIX)
                : capped.size();
        return new BoardIssuesResponse(toResponses(capped), truncated, totalAvailable, cap);
    }

    /**
     * Paged issue list (backlog). {@code excludeDone} filters out DONE-category
     * statuses server-side so page counts are correct. The board keeps using the
     * full-list {@link #list} above.
     */
    @Transactional(readOnly = true)
    public PageResponse<IssueResponse> listPaged(User actor, UUID workspaceId, UUID projectId,
                                                 UUID statusId, UUID assigneeId, UUID priorityId,
                                                 UUID componentId, List<UUID> labelIds, LabelMatch labelMatch,
                                                 UUID fixVersionId, boolean excludeDone, Pageable pageable) {
        var project = workspaceAccess.requireProjectMember(actor, workspaceId, projectId).project();
        var labelFilter = LabelFilter.of(labelIds, labelMatch,
                classificationProperties.maxLabelsPerIssue());
        var page = issueRepository.findByProjectFilteredPaged(
                project, statusId, assigneeId, priorityId, componentId,
                labelFilter.ids(), labelFilter.count(), labelFilter.requiredMatches(),
                fixVersionId, VersionLinkType.FIX,
                excludeDone, StatusCategory.DONE, pageable);
        var responses = toResponses(page.getContent());
        var byId = responses.stream().collect(Collectors.toMap(IssueResponse::id, r -> r));
        return PageResponse.of(page.map(i -> byId.get(i.getId())));
    }

    @Transactional(readOnly = true)
    public IssueResponse get(User actor, UUID workspaceId, UUID projectId, long number) {
        var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, number).issue();
        return toResponse(issue);
    }

    @Transactional(readOnly = true)
    public List<IssueResponse> children(User actor, UUID workspaceId, UUID projectId, long number) {
        var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, number).issue();
        return toResponses(issueRepository.findByParent(issue));
    }

    @Transactional(readOnly = true)
    public PageResponse<IssueHistoryResponse> getHistory(User actor, UUID workspaceId, UUID projectId,
                                                         long number, Pageable pageable) {
        var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, number).issue();
        return PageResponse.of(historyRepository.findByIssue(issue, pageable).map(IssueHistoryResponse::of));
    }

    @Transactional
    public IssueResponse update(User actor, UUID workspaceId, UUID projectId, long number, UpdateIssueRequest req) {
        var ctx = workspaceAccess.requireProjectMember(actor, workspaceId, projectId);
        var workspace = ctx.workspace();
        var project = ctx.project();
        requireNotArchived(project);

        // All reads first (avoid Hibernate auto-flush double-write — see CLAUDE.md gotchas)
        var newType = req.typeId() != null
                ? projectConfigService.requireTypeInSet(project, resolveType(req.typeId()))
                : null;
        var newStatus = req.statusId() != null
                ? projectConfigService.requireStatusInWorkflow(project, resolveStatus(req.statusId()))
                : null;
        var newPriority = req.priorityId() != null
                ? projectConfigService.requirePriorityInSet(project, resolvePriority(req.priorityId()))
                : null;
        var newAssignee = req.assigneeId() != null
                ? resolveAssignee(workspace, req.assigneeId())
                : null;
        var newCategory = req.statusId() != null
                ? newStatus.getCategory()
                : null;
        // HD-30: null when `labelIds` is absent (leave the set alone); otherwise the
        // fully-resolved replacement set. Resolved with the other READS, before any
        // mutation, so nothing can trigger an early auto-flush.
        var newLabels = labelService.resolveForIssue(workspace, req.labelIds());
        // HD-31: null when `componentId` is absent (leave it alone, unless
        // clearComponent asks for null). Resolved with the other READS.
        var newComponent = componentService.resolveForIssue(project, req.componentId());
        // HD-32: null per role when that role's array is absent (leave it alone);
        // otherwise the fully-resolved replacement set. Resolved with the other READS.
        var newFixVersions = versionService.resolveForIssue(
                project, req.fixVersionIds(), VersionLinkType.FIX);
        var newAffectsVersions = versionService.resolveForIssue(
                project, req.affectsVersionIds(), VersionLinkType.AFFECTS);

        var issue = issueRepository.findByProjectAndNumber(project, number)
                .orElseThrow(IssueNotFoundException::new);
        var oldCategory = issue.getStatus().getCategory();
        // Still a read: the issue's current attachments, needed for the diff below.
        var currentLabelRows = newLabels == null ? List.<IssueLabel>of() : labelService.attachmentsOf(issue);
        // One load serves BOTH version roles — the diff filters it per link type.
        var currentVersionRows = newFixVersions == null && newAffectsVersions == null
                ? List.<IssueVersionLink>of() : versionService.linksOf(issue);

        if (req.version() != null && req.version() != issue.getVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Issue was modified by someone else — refresh and retry");
        }

        // Resolve the candidate parent and validate the whole chain BEFORE any
        // mutation (CLAUDE.md @Version double-write ordering). The type/parent
        // conflict rules below are validated against the *resulting* (type, parent).
        var effectiveType = newType != null ? newType : issue.getType();
        Issue newParent = null;
        if (req.parentId() != null) {
            newParent = resolveParent(req.parentId(), project);
            if (newParent.getId().equals(issue.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "An issue can't be its own parent");
            }
            requireLegalParentLevel(newParent.getType(), effectiveType);
            requireNoCycle(issue, newParent);
        }
        // Effective parent after this PATCH: cleared, changed, or unchanged.
        Issue effectiveParent = req.parentId() != null ? newParent
                : (req.clearParent() ? null : issue.getParent());

        // §4.4.3 — a type change that makes the (retained) current parent illegal
        // under adjacency. Distinct message per Revision 2.
        if (newType != null && effectiveParent != null && violatesAdjacency(effectiveParent.getType(), effectiveType)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Changing type to " + newType.getName() + " conflicts with its parent "
                            + issueKey(effectiveParent)
                            + " — clear the parent or pick a parent exactly one level above");
        }
        // §4.4.4 — a type change that would make this issue's existing children
        // illegal. Under adjacency a child edge is illegal when the new type is not
        // exactly one tier above that child (stricter than the old ">=" rule: e.g.
        // promoting a Story that parents a Sub-task up to an Epic leaves a gap-2 edge).
        if (newType != null && newType.getHierarchyLevel() != issue.getType().getHierarchyLevel()) {
            long invalid = issueRepository.findByParent(issue).stream()
                    .filter(c -> violatesAdjacency(effectiveType, c.getType()))
                    .count();
            if (invalid > 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "This issue has children; changing its type to " + newType.getName()
                                + " would make " + invalid + " child relationship(s) invalid"
                                + " (a parent must be exactly one level above its children)");
            }
        }

        var historyEntries = new ArrayList<IssueHistory>();

        if (req.title() != null && !req.title().equals(issue.getTitle())) {
            historyEntries.add(makeHistory(issue, actor, "title", issue.getTitle(), req.title()));
            issue.setTitle(req.title());
        }
        if (req.description() != null && !req.description().equals(issue.getDescription())) {
            historyEntries.add(makeHistory(issue, actor, "description",
                    issue.getDescription() != null ? "..." : null, "..."));
            issue.setDescription(req.description());
        }
        if (newType != null && !newType.getId().equals(issue.getType().getId())) {
            historyEntries.add(makeHistory(issue, actor, "type", issue.getType().getName(), newType.getName()));
            issue.setType(newType);
        }
        if (newStatus != null && !newStatus.getId().equals(issue.getStatus().getId())) {
            projectConfigService.validateTransition(project, issue.getStatus(), newStatus);
            historyEntries.add(makeHistory(issue, actor, "status", issue.getStatus().getName(), newStatus.getName()));
            issue.setStatus(newStatus);

            if (!Objects.equals(oldCategory, newCategory) && newCategory != null) {
                boolean isDone = newCategory.equals(StatusCategory.DONE);
                issue.setClosedAt(isDone ? OffsetDateTime.now() : null);
            }
        }
        if (newPriority != null && !newPriority.getId().equals(issue.getPriority().getId())) {
            historyEntries.add(makeHistory(issue, actor, "priority",
                    issue.getPriority().getName(), newPriority.getName()));
            issue.setPriority(newPriority);
        }
        // Assignee: a non-null id sets it; clearAssignee (when no id) unsets it
        if (newAssignee != null || req.clearAssignee()) {
            var oldId = issue.getAssignee() != null ? issue.getAssignee().getId() : null;
            var newId = newAssignee != null ? newAssignee.getId() : null;
            if (!Objects.equals(oldId, newId)) {
                String oldName = issue.getAssignee() != null ? issue.getAssignee().getDisplayName() : null;
                String newName = newAssignee != null ? newAssignee.getDisplayName() : null;
                historyEntries.add(makeHistory(issue, actor, "assignee", oldName, newName));
                issue.setAssignee(newAssignee);
            }
        }
        if (req.dueDate() != null && !req.dueDate().equals(issue.getDueDate())) {
            historyEntries.add(makeHistory(issue, actor, "dueDate",
                    issue.getDueDate() != null ? issue.getDueDate().toString() : null,
                    req.dueDate().toString()));
            issue.setDueDate(req.dueDate());
        } else if (req.clearDueDate() && issue.getDueDate() != null) {
            historyEntries.add(makeHistory(issue, actor, "dueDate", issue.getDueDate().toString(), null));
            issue.setDueDate(null);
        }

        // Component (HD-31): a non-null componentId sets/changes it; clearComponent (no
        // id) unsets it — the assigneeId/clearAssignee convention. Auto-assign does NOT
        // fire here: changing a component later must never silently reassign someone's
        // work (§5.1). An ARCHIVED component is only rejected when it is an actual
        // change — an issue already carrying one stays editable (§5.4).
        if (newComponent != null || req.clearComponent()) {
            var oldId = issue.getComponent() != null ? issue.getComponent().getId() : null;
            var newId = newComponent != null ? newComponent.getId() : null;
            if (!Objects.equals(oldId, newId)) {
                componentService.requireAssignable(newComponent);
                String oldName = issue.getComponent() != null ? issue.getComponent().getName() : null;
                String newName = newComponent != null ? newComponent.getName() : null;
                historyEntries.add(makeHistory(issue, actor, "component", oldName, newName));
                issue.setComponent(newComponent);
            }
        }

        // Parent: a non-null parentId sets/changes it; clearParent (no id) detaches.
        // History records the old → new parent key.
        if (newParent != null || req.clearParent()) {
            var oldId = issue.getParent() != null ? issue.getParent().getId() : null;
            var newId = newParent != null ? newParent.getId() : null;
            if (!Objects.equals(oldId, newId)) {
                String oldKey = issue.getParent() != null ? issueKey(issue.getParent()) : null;
                String newKey = newParent != null ? issueKey(newParent) : null;
                historyEntries.add(makeHistory(issue, actor, "parent", oldKey, newKey));
                issue.setParent(newParent);
            }
        }

        // Labels (HD-30): full replacement when present. The diff is PURE (no query),
        // so it runs here with the other mutations; a set equal to the current one is
        // a no-op — no history row (the PATCH still bumps @Version, as documented).
        LabelService.LabelChange labelChange = null;
        if (newLabels != null) {
            labelChange = labelService.diffLabels(currentLabelRows, newLabels);
            if (labelChange.changed()) {
                historyEntries.add(makeHistory(issue, actor, "labels",
                        labelChange.oldNames(), labelChange.newNames()));
                // Labels live in a side table, so the Issue row would otherwise stay
                // clean and Hibernate would emit no UPDATE — leaving @Version and
                // updatedAt stale on a label-only PATCH. Touching updatedAt makes the
                // entity dirty, so the row is written exactly once: version +1 (§4.4)
                // and @LastModifiedDate re-stamps the audit time at pre-update.
                issue.setUpdatedAt(java.time.Instant.now());
            }
        }

        // Fix / affects versions (HD-32): full replacement PER ROLE, independently —
        // sending only `fixVersionIds` never touches the affects set. Same shape as
        // labels: the diff is PURE (no query), a set equal to the current one is a
        // no-op with no history row, and a real change touches updatedAt so the
        // side-table-only edit still bumps @Version exactly once.
        VersionService.VersionChange fixChange = null;
        VersionService.VersionChange affectsChange = null;
        if (newFixVersions != null) {
            fixChange = versionService.diffVersions(currentVersionRows, newFixVersions, VersionLinkType.FIX);
            if (fixChange.changed()) {
                historyEntries.add(makeHistory(issue, actor, "fixVersions",
                        fixChange.oldNames(), fixChange.newNames()));
                issue.setUpdatedAt(java.time.Instant.now());
            }
        }
        if (newAffectsVersions != null) {
            affectsChange = versionService.diffVersions(
                    currentVersionRows, newAffectsVersions, VersionLinkType.AFFECTS);
            if (affectsChange.changed()) {
                historyEntries.add(makeHistory(issue, actor, "affectsVersions",
                        affectsChange.oldNames(), affectsChange.newNames()));
                issue.setUpdatedAt(java.time.Instant.now());
            }
        }

        // Custom fields: partial map, JSON null clears; changes land in history
        fieldValueService.applyValues(issue, req.fields(), false,
                (fieldName, oldVal, newVal) ->
                        historyEntries.add(makeHistory(issue, actor, fieldName, oldVal, newVal)));

        issueRepository.save(issue);
        labelService.applyLabelChange(issue, labelChange);
        versionService.applyVersionChanges(issue, fixChange, affectsChange);
        historyRepository.saveAll(historyEntries);

        // changeSet mirrors the history diff for future consumers (Phase-5 triggers);
        // the SSE payload is unchanged (the listener ignores it).
        var changeSet = historyEntries.stream()
                .map(h -> new FieldChange(h.getField(), h.getOldValue(), h.getNewValue()))
                .toList();
        eventPublisher.publishEvent(new IssueUpdated(workspaceId, projectId, number, changeSet));
        return toResponse(issue);
    }

    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, long number) {
        var project = workspaceAccess.requireProjectMember(actor, workspaceId, projectId).project();
        requireNotArchived(project);
        requireProjectRole(actor, project, ProjectRole.MANAGER);
        var issue = issueRepository.findByProjectAndNumber(project, number)
                .orElseThrow(IssueNotFoundException::new);

        // Orphan direct children to root (parent = null) before deleting (proposal
        // Revision 2 §4.7-superseded). Re-homing to the grandparent would break the
        // new strict-adjacency rule — the grandparent sits two tiers above each
        // child (grandparent = deleted+1, deleted = child+1), a gap-2 edge — so we
        // detach instead. Orphaning is always adjacency-legal (a root has no parent
        // edge) and loses no data: the children survive and can be re-parented.
        // Load children first for history.
        var children = issueRepository.findByParent(issue);
        if (!children.isEmpty()) {
            var oldKey = issueKey(issue);
            var historyEntries = new ArrayList<IssueHistory>();
            for (var child : children) {
                historyEntries.add(makeHistory(child, actor, "parent", oldKey, null));
                // Re-point the *managed* child entity rather than a bulk JPQL UPDATE:
                // findByParent loaded these into the persistence context with
                // parent == issue, and a bulk update wouldn't refresh that L1 copy —
                // so when `issue` is deleted below, the stale managed children would
                // flush a reference to a removed entity (TransientPropertyValueException).
                child.setParent(null);
            }
            issueRepository.saveAll(children);
            historyRepository.saveAll(historyEntries);
        }

        attachmentService.removeStoredFilesForIssue(issue);
        issueRepository.delete(issue);

        eventPublisher.publishEvent(new IssueDeleted(workspaceId, projectId, number));
    }

    // ---- catalog resolution ----
    // Find by id across ALL scopes (global / workspace / project-private); the
    // real gate is ProjectConfigService.requireXInSet/Workflow at every call
    // site, which rejects anything not in this project's effective config —
    // so a foreign or wrong-scope id can never attach to an issue.

    private IssueType resolveType(UUID id) {
        return issueTypeRepository.findById(id)
                .filter(t -> t.getArchivedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown issue type"));
    }

    private Status resolveStatus(UUID id) {
        return statusRepository.findById(id)
                .filter(s -> s.getArchivedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown status"));
    }

    private Priority resolvePriority(UUID id) {
        return priorityRepository.findById(id)
                .filter(p -> p.getArchivedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown priority"));
    }

    // ---- hierarchy helpers ----

    // Resolve a candidate parent within THIS project. A foreign/unknown id → 422
    // "Unknown parent issue" (an invalid field value, not a 404 existence leak).
    private Issue resolveParent(UUID parentId, Project project) {
        return issueRepository.findByIdAndProject(parentId, project)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown parent issue"));
    }

    // Strict adjacency (proposal Revision 2, Variant A): a parent → child edge is
    // legal only when the parent's type sits EXACTLY one tier above the child's.
    // Expressed as the violation (a level gap != 1) because every caller rejects
    // illegal edges — so e.g. Epic(2) → Sub-task(0) (gap 2) and task-tier →
    // task-tier (gap 0) both violate adjacency and are forbidden.
    private static boolean violatesAdjacency(IssueType parentType, IssueType childType) {
        return parentType.getHierarchyLevel() - childType.getHierarchyLevel() != 1;
    }

    private void requireLegalParentLevel(IssueType parentType, IssueType childType) {
        if (violatesAdjacency(parentType, childType)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A " + childType.getName() + " can't be a child of a " + parentType.getName()
                            + " — a parent must be exactly one level above its child");
        }
    }

    // Walk the prospective parent's ancestor chain; if the issue being updated
    // appears in it, the edge would create a cycle. Bounded by a max-depth cap
    // of 20 (also guards a corrupt pre-existing cycle from looping forever).
    private void requireNoCycle(Issue issue, Issue candidateParent) {
        Issue cursor = candidateParent;
        int depth = 0;
        while (cursor != null) {
            if (cursor.getId().equals(issue.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "This would create a cycle");
            }
            if (++depth > 20) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Hierarchy too deep");
            }
            cursor = cursor.getParent();
        }
    }

    private String issueKey(Issue i) {
        return i.getProject().getKey() + "-" + i.getNumber();
    }

    // ---- response assembly (batched, no N+1) ----

    // Single-issue response: navigate the parent proxy and count children with
    // cheap count queries inside the @Transactional method.
    private IssueResponse toResponse(Issue issue) {
        IssueResponse.ParentRef parentRef = null;
        if (issue.getParent() != null) {
            var p = issue.getParent();
            parentRef = new IssueResponse.ParentRef(p.getId(), issueKey(p), p.getTitle(), p.getType().getId());
        }
        int childCount = (int) issueRepository.countByParent(issue);
        int doneCount = childCount == 0 ? 0
                : (int) issueRepository.countByParentAndStatusCategory(issue, StatusCategory.DONE);
        return IssueResponse.of(issue, fieldValueService.values(issue),
                labelService.labelsForIssue(issue), versionService.versionsForIssue(issue),
                parentRef, new IssueResponse.Rollup(childCount, doneCount));
    }

    /**
     * Map a batch of already-loaded, managed {@link Issue} entities to
     * {@link IssueResponse}s with roll-up counts and parent summaries batched (no
     * N+1) — the same assembly the board/backlog use. Exposed for HQL search, whose
     * compiler fetches the page through Criteria but still needs the identical row
     * shape. Must be called inside a read transaction (navigates lazy parent ids).
     */
    @Transactional(readOnly = true)
    public List<IssueResponse> toResponsesBatched(List<Issue> issues) {
        return toResponses(issues);
    }

    // List/board response: roll-up counts via one grouped query keyed by these
    // issue ids, and parent summaries via one batched query keyed by the distinct
    // parent ids (never a fetch-join on the hot board query — §7.3).
    private List<IssueResponse> toResponses(List<Issue> issues) {
        if (issues.isEmpty()) return List.of();
        var valuesByIssue = fieldValueService.valuesByIssue(issues);
        // HD-30 §3.7: ONE query for the whole page's labels, keyed by issue id — a
        // board page of 100 issues must stay a constant number of queries.
        var labelsByIssue = labelService.labelsByIssue(issues);
        // HD-32 §3.7: ONE query for the whole page's version links — BOTH roles come
        // back together and are split per issue, so the page stays a constant number of
        // queries no matter how many issues carry versions.
        var versionsByIssue = versionService.versionsByIssue(issues);

        var issueIds = issues.stream().map(Issue::getId).toList();
        var rollups = new HashMap<UUID, IssueResponse.Rollup>();
        for (var row : issueRepository.rollupByParentIds(issueIds, StatusCategory.DONE)) {
            var parentId = (UUID) row[0];
            int total = ((Number) row[1]).intValue();
            int done = row[2] == null ? 0 : ((Number) row[2]).intValue();
            rollups.put(parentId, new IssueResponse.Rollup(total, done));
        }

        Set<UUID> parentIds = new HashSet<>();
        for (var i : issues) {
            if (i.getParent() != null) parentIds.add(i.getParent().getId());
        }
        var parentRefs = new HashMap<UUID, IssueResponse.ParentRef>();
        if (!parentIds.isEmpty()) {
            for (var row : issueRepository.parentSummaries(parentIds)) {
                var id = (UUID) row[0];
                var key = (String) row[1] + "-" + ((Number) row[2]).longValue();
                parentRefs.put(id, new IssueResponse.ParentRef(id, key, (String) row[3], (UUID) row[4]));
            }
        }

        return issues.stream()
                .map(i -> IssueResponse.of(i, valuesByIssue.get(i.getId()),
                        labelsByIssue.get(i.getId()), versionsByIssue.get(i.getId()),
                        i.getParent() == null ? null : parentRefs.get(i.getParent().getId()),
                        rollups.get(i.getId())))
                .toList();
    }

    private IssueHistory makeHistory(Issue issue, User actor, String field, String oldVal, String newVal) {
        var h = new IssueHistory();
        h.setIssue(issue);
        h.setChangedBy(actor);
        h.setField(field);
        h.setOldValue(oldVal);
        h.setNewValue(newVal);
        return h;
    }

    private void requireNotArchived(Project project) {
        if (project.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is archived");
        }
    }

    // Assignee must be a member of the workspace — a bare findById would let callers
    // reference (and enumerate) users from other tenants
    private User resolveAssignee(Workspace workspace, UUID assigneeId) {
        return userRepository.findById(assigneeId)
                .filter(u -> workspaceMemberRepository.existsByWorkspaceAndUser(workspace, u))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown assignee"));
    }

    private void requireProjectRole(User actor, Project project, ProjectRole required) {
        var role = projectMemberRepository.findByProjectAndUser(project, actor)
                .map(ProjectMember::getRole)
                .orElse(ProjectRole.VIEWER);
        if (!role.isAtLeast(required)) {
            throw new com.hamstrack.project.exception.InsufficientProjectRoleException();
        }
    }
}
