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
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.util.Points;
import com.hamstrack.issue.dto.BoardIssuesResponse;
import com.hamstrack.issue.dto.CreateIssueRequest;
import com.hamstrack.issue.dto.IssueHistoryResponse;
import com.hamstrack.issue.dto.IssueResponse;
import com.hamstrack.issue.dto.LabelMatch;
import com.hamstrack.issue.dto.RankIssueRequest;
import com.hamstrack.issue.dto.UpdateIssueRequest;
import com.hamstrack.issue.entity.*;
import com.hamstrack.issue.exception.IssueNotFoundException;
import com.hamstrack.issue.repository.*;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.service.ProjectContext;
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
import java.time.ZoneOffset;
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
    private final ProjectRepository projectRepository;
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
    private final SprintService sprintService;
    /**
     * HD-137: the ONE writer of {@code sprint_scope_events}. Three of this class's
     * statements move an issue in or out of a sprint ({@code create}, {@code update},
     * {@code rank}) and each of them records it — see {@link SprintScopeLedger} for the
     * full list of doors and why missing one is invisible until a chart lies.
     */
    private final SprintScopeLedger scopeLedger;
    private final IssueRankService rankService;
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

    /**
     * <strong>Multi-permission, like {@link #update} and for the same reason (§6.5).</strong>
     * Filing an issue is {@code issue.create}; filing one that <em>names an assignee</em>
     * additionally needs {@code issue.assign}, and one that <em>lands in a sprint</em>
     * needs {@code sprint.assign}. {@code POST /issues} carrying {@code assigneeId} is
     * the same act as {@code POST} followed by {@code PATCH assigneeId} — gating only the
     * second would make {@code issue.assign} decorative, with the bypass shorter than the
     * guarded path.
     *
     * <p>Only what the payload actually carries is gated: a create with no assignee
     * requires no {@code issue.assign}, exactly as an unchanged field on {@link #update}
     * requires nothing. On a create there is no "unchanged" case to consider — a new
     * issue has no prior assignee or sprint, so presence <em>is</em> change. Order is the
     * same catalog order the PATCH path uses, so the first missing permission a caller is
     * told about does not depend on field iteration.
     *
     * <p>Behaviour-neutral today: the built-in Contributor holds all three, so this
     * bites only a custom role — which cannot exist before S4. Closing it now costs
     * nothing; closing it later would take away something people had come to rely on.
     */
    @Transactional
    public IssueResponse create(User actor, UUID workspaceId, UUID projectId, CreateIssueRequest req) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        // Permission first, project state second (§10.3.6): a 403 must never depend on
        // whether this project happens to be archived. All three checks read the request
        // shape only, so they run before anything is read from the database.
        var permissions = ctx.permissions();
        permissions.require(Permission.ISSUE_CREATE);
        if (req.assigneeId() != null) permissions.require(Permission.ISSUE_ASSIGN);
        if (req.sprintId() != null) permissions.require(Permission.SPRINT_ASSIGN);
        var workspace = ctx.workspace();
        var project = ctx.project();
        requireNotArchived(project);

        var type = resolveType(req.typeId(), project);
        var status = resolveStatus(req.statusId(), project);
        var priority = req.priorityId() != null
                ? resolvePriority(req.priorityId(), project)
                // re-resolve the default id to a managed entity (the config cache hands back detached ones)
                : resolvePriority(projectConfigService.defaultPriorityId(project), project);
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
        // Every create-time value is a NEW assignment, so BOTH halves of §6.3 apply and
        // both must hold: the actor's (`issue.assign`, checked above from the request
        // shape) and the target's (`issue.assignable`, checked here). They are different
        // questions — may you hand out work, and may this person be given it.
        var assignee = req.assigneeId() != null
                ? resolveAssignableAssignee(ctx, req.assigneeId())
                // The component lead is not a value the caller supplied, and a stale lead
                // is already skipped silently rather than failing the create (§5.1). A
                // lead who may not be given work in this project is skipped the same way:
                // 422ing an issue create over somebody else's role would be a worse
                // answer than filing it unassigned.
                : assignableOrNull(ctx, componentService.autoAssignee(workspace, component));
        // HD-22: same ordering rule again. A sprint of another project is a 422 "Unknown
        // sprint"; filing straight into a COMPLETED sprint is a 422 too (every
        // create-time value is a new assignment).
        var sprint = sprintService.resolveForIssue(project, req.sprintId());
        sprintService.requireAssignable(sprint);
        var storyPoints = requireValidStoryPoints(req.storyPoints());
        // The backlog rank: a new issue lands at the BOTTOM (§3.3.1) — filing an issue
        // is not a priority statement, the team ranks it at grooming. Read here with the
        // other queries, before any mutation.
        long bottomPosition = issueRepository.maxPosition(project) + IssueRankService.RANK_STEP;

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
        // NOT the issue sequence any more (§3.3.1): position is the shared, rescaled
        // backlog/board rank, and a fresh issue appends at max + RANK_STEP. Two
        // concurrent creates may receive the same position — harmless (the
        // `createdAt DESC` tie-break keeps the order stable) and the first drag
        // separates them.
        issue.setPosition(bottomPosition);
        issue.setSprint(sprint);
        issue.setStoryPoints(storyPoints);
        // UTC, never OffsetDateTime.now(): every timestamp this API serializes is UTC,
        // and stamping one field in the JVM's default zone is a bug VersionService and
        // SprintService each had to fix once already (HD-137 review R2-7). The instant is
        // the same either way in a timestamptz column — the offset that comes back out to
        // the SPA is not.
        if (status.getCategory() == StatusCategory.DONE) {
            issue.setClosedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        // HD-137 (§5.1): filing an issue straight into an in-progress OR a done status IS
        // its first entry into one, so work started now. DONE counts on purpose — an item
        // created already finished was started and finished in one move, and pretending
        // otherwise drops exactly the fastest work out of every cycle-time percentile.
        // Never derived from createdAt for anything else: an issue filed into a TODO
        // status keeps startedAt = null until it actually moves (§2.2).
        if (status.getCategory() == StatusCategory.IN_PROGRESS
            || status.getCategory() == StatusCategory.DONE) {
            issue.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
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
        // HD-137, DOOR 1 — and the one door no history-based audit could ever find: an
        // issue filed straight into a RUNNING sprint enlarges that sprint's scope, but
        // create-time values write no `sprint` history row (see just below), so the
        // burn-up would step up with nothing to explain it. After save(), because the
        // event carries the issue's id. A FUTURE sprint records nothing — its scope is
        // committed when it starts (SprintScopeLedger).
        scopeLedger.recordMove(workspaceId, project.getKey(), issue, null, sprint, actor);
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
                                          UUID fixVersionId, UUID sprintId, boolean noSprint) {
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        var labelFilter = LabelFilter.of(labelIds, labelMatch,
                classificationProperties.maxLabelsPerIssue());
        requireCoherentSprintFilter(sprintId, noSprint);

        int cap = boardProperties.maxIssues();
        // Fetch cap+1 (in board order) so a single query tells us whether more exist.
        var fetched = issueRepository.findByProjectFilteredCapped(
                project, statusId, assigneeId, priorityId, componentId,
                labelFilter.ids(), labelFilter.count(), labelFilter.requiredMatches(),
                fixVersionId, VersionLinkType.FIX, sprintId, noSprint,
                PageRequest.of(0, cap + 1));
        boolean truncated = fetched.size() > cap;
        var capped = truncated ? fetched.subList(0, cap) : fetched;
        // Only pay for the full count when truncated; otherwise it equals the size.
        long totalAvailable = truncated
                ? issueRepository.countByProjectFiltered(project, statusId, assigneeId, priorityId,
                        componentId, labelFilter.ids(), labelFilter.count(), labelFilter.requiredMatches(),
                        fixVersionId, VersionLinkType.FIX, sprintId, noSprint)
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
                                                 UUID fixVersionId, UUID sprintId, boolean noSprint,
                                                 boolean excludeDone, Pageable pageable) {
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        var labelFilter = LabelFilter.of(labelIds, labelMatch,
                classificationProperties.maxLabelsPerIssue());
        requireCoherentSprintFilter(sprintId, noSprint);
        var page = issueRepository.findByProjectFilteredPaged(
                project, statusId, assigneeId, priorityId, componentId,
                labelFilter.ids(), labelFilter.count(), labelFilter.requiredMatches(),
                fixVersionId, VersionLinkType.FIX, sprintId, noSprint,
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

    /**
     * <strong>Multi-permission by construction (§10.3.3).</strong> One PATCH can require
     * {@code issue.edit}, {@code issue.transition}, {@code issue.assign} and
     * {@code sprint.assign} at once, so this method does not have "a" permission. Three
     * rules, and all three matter:
     * <ol>
     *   <li><strong>Present <em>and actually changing</em></strong> — the SPA sends whole-
     *       form patches, so a field that arrives carrying its current value must not
     *       403. Sending an unchanged {@code statusId} alongside a new title needs
     *       {@code issue.edit} and nothing else.</li>
     *   <li><strong>Every permission is checked before any mutation.</strong> Half-applied
     *       authorization is not authorization — and the {@code @Version} rule demands the
     *       same ordering anyway (all reads, then all mutations).</li>
     *   <li>On failure, the 403 names the <strong>first</strong> missing permission in
     *       catalog order ({@code issue.edit} → {@code issue.transition} →
     *       {@code issue.assign} → {@code sprint.assign}), so the answer to a given
     *       payload is deterministic rather than dependent on field iteration order.</li>
     * </ol>
     *
     * <p>One shape the spec did not anticipate: <strong>custom fields are gated on
     * presence, not on change.</strong> Deciding whether {@code fields} actually changes
     * anything needs a per-field {@code issue_field_values} read, which is precisely what
     * {@code FieldValueService.applyValues} does <em>as it applies</em> — a dry run would
     * double the queries on every issue PATCH to refine an answer that only differs for a
     * role holding {@code issue.transition} without {@code issue.edit}. A non-empty
     * {@code fields} map therefore requires {@code issue.edit}; an empty or absent one
     * requires nothing.
     */
    @Transactional
    public IssueResponse update(User actor, UUID workspaceId, UUID projectId, long number, UpdateIssueRequest req) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        var workspace = ctx.workspace();
        var project = ctx.project();

        // Request shape, before anything is read: "put it in sprint X" and "take it out
        // of every sprint" cannot both hold. Letting sprintId silently win would make a
        // buggy client's contradictory payload look successful — and POST …/rank already
        // 400s on exactly this combination, so the two write paths must agree (§4.4).
        if (req.sprintId() != null && req.clearSprint()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Send either sprintId or clearSprint, not both");
        }

        // All reads first (avoid Hibernate auto-flush double-write — see CLAUDE.md gotchas)
        var newType = req.typeId() != null ? resolveType(req.typeId(), project) : null;
        var newStatus = req.statusId() != null ? resolveStatus(req.statusId(), project) : null;
        var newPriority = req.priorityId() != null ? resolvePriority(req.priorityId(), project) : null;
        // The assignee is deliberately NOT resolved here (HD-125 review, L4). Resolving it
        // with the other reads put its 422 ("Unknown assignee" = not a member of this
        // workspace) BEFORE the actor's own 403, which let someone without `issue.assign`
        // probe arbitrary user ids for workspace membership. It is resolved after the
        // permission block instead, and only when the assignee actually changes — which
        // also collapses four queries into two, since resolution and the assignable check
        // now share one lookup of the target's permissions.
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
        // HD-22: null when `sprintId` is absent (leave it alone, unless clearSprint asks
        // for null). Resolved with the other READS, before any mutation.
        var newSprint = sprintService.resolveForIssue(project, req.sprintId());
        var newStoryPoints = requireValidStoryPoints(req.storyPoints());

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

        // ---- what this PATCH actually changes -------------------------------------
        // Pure comparisons over the reads above; no query, no mutation. The label and
        // version diffs are computed HERE rather than inline further down (they are pure
        // too) because "does this PATCH edit anything" cannot be answered without them,
        // and the answer has to exist before the first mutation.
        LabelService.LabelChange labelChange =
                newLabels == null ? null : labelService.diffLabels(currentLabelRows, newLabels);
        VersionService.VersionChange fixChange = newFixVersions == null ? null
                : versionService.diffVersions(currentVersionRows, newFixVersions, VersionLinkType.FIX);
        VersionService.VersionChange affectsChange = newAffectsVersions == null ? null
                : versionService.diffVersions(currentVersionRows, newAffectsVersions, VersionLinkType.AFFECTS);

        boolean statusChanges = newStatus != null && !newStatus.getId().equals(issue.getStatus().getId());
        // Computed from the RAW id, so the actor's 403 can precede the value's 422 (L4):
        // an exact id comparison needs no entity, and resolving one first is what let a
        // caller without `issue.assign` learn whether an arbitrary user id belongs to this
        // workspace. Same shape as the mutation below, which compares the same two ids.
        UUID currentAssigneeId = idOf(issue.getAssignee());
        boolean assigneeChanges = req.assigneeId() != null
                ? !req.assigneeId().equals(currentAssigneeId)
                : req.clearAssignee() && currentAssigneeId != null;
        boolean sprintChanges = (newSprint != null || req.clearSprint())
                && !Objects.equals(idOf(issue.getSprint()), idOf(newSprint));
        boolean componentChanges = (newComponent != null || req.clearComponent())
                && !Objects.equals(idOf(issue.getComponent()), idOf(newComponent));
        boolean parentChanges = (newParent != null || req.clearParent())
                && !Objects.equals(idOf(issue.getParent()), idOf(newParent));
        // DO NOT SIMPLIFY THIS TO ONE BOOLEAN. Two flags, the second guarded by the
        // first, because that is exactly the if/else-if the mutation below runs: the
        // `else if` fires whenever the first condition is false, which includes "dueDate
        // present but unchanged". So a payload carrying an unchanged `dueDate` AND
        // `clearDueDate: true` really does clear the date — and a single "is dueDate
        // changing" boolean would answer false for it, letting that mutation through
        // with no `issue.edit` check at all. The gate has to mirror the mutation's own
        // branching, not a tidier restatement of it.
        boolean dueDateSet = req.dueDate() != null && !req.dueDate().equals(issue.getDueDate());
        boolean dueDateCleared = !dueDateSet && req.clearDueDate() && issue.getDueDate() != null;
        boolean dueDateChanges = dueDateSet || dueDateCleared;
        boolean storyPointsChange = (newStoryPoints != null || req.clearStoryPoints())
                && (newStoryPoints == null
                        ? issue.getStoryPoints() != null
                        : issue.getStoryPoints() == null
                          || issue.getStoryPoints().compareTo(newStoryPoints) != 0);
        boolean edits = (req.title() != null && !req.title().equals(issue.getTitle()))
                || (req.description() != null && !req.description().equals(issue.getDescription()))
                || (newType != null && !newType.getId().equals(issue.getType().getId()))
                || (newPriority != null && !newPriority.getId().equals(issue.getPriority().getId()))
                || dueDateChanges
                || componentChanges
                || parentChanges
                || storyPointsChange
                || (labelChange != null && labelChange.changed())
                || (fixChange != null && fixChange.changed())
                || (affectsChange != null && affectsChange.changed())
                // Presence, not change — see the method javadoc.
                || (req.fields() != null && !req.fields().isEmpty());

        // ---- every permission, before the first mutation (§10.3.3) -----------------
        var permissions = ctx.permissions();
        if (edits) permissions.require(Permission.ISSUE_EDIT, isReporter(issue, actor));
        if (statusChanges) permissions.require(Permission.ISSUE_TRANSITION);
        if (assigneeChanges) permissions.require(Permission.ISSUE_ASSIGN);
        // The second door (§6.5): the sprint endpoints are not the only way to move an
        // issue in or out of a sprint, and a permission that guards one door and not the
        // other is not a permission.
        if (sprintChanges) permissions.require(Permission.SPRINT_ASSIGN);

        // ---- the last read: the assignee, now that the actor is known to be entitled ---
        // The actor's 403 has already been answered, so what remains is the VALUE: an id
        // that is not a member of this workspace is "Unknown assignee" and one who may not
        // be given work here is "cannot be assigned" (§10.3.4), both 422. Resolved only on
        // an actual CHANGE, so an issue whose assignee has since lost `issue.assignable`
        // — or even left the workspace — stays editable (§11.5).
        User newAssignee = assigneeChanges && req.assigneeId() != null
                ? resolveAssignableAssignee(ctx, req.assigneeId())
                : null;

        // Project state comes after authorization, uniformly (§10.3.6), so a 403 never
        // depends on whether the project happens to be archived.
        requireNotArchived(project);

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
        if (statusChanges) {
            projectConfigService.validateTransition(project, issue.getStatus(), newStatus);
            historyEntries.add(makeHistory(issue, actor, "status", issue.getStatus().getName(), newStatus.getName()));
            issue.setStatus(newStatus);

            if (!Objects.equals(oldCategory, newCategory) && newCategory != null) {
                boolean isDone = newCategory.equals(StatusCategory.DONE);
                issue.setClosedAt(isDone ? OffsetDateTime.now(ZoneOffset.UTC) : null);
            }
            // HD-137 (§5.1): the FIRST entry into an IN_PROGRESS *or* DONE status stamps
            // startedAt. Three deliberate details, each of which is a wrong report if
            // reversed:
            //   * `getStartedAt() == null` — first entry only. A re-open followed by a
            //     second start must not move the mark, or the cycle time of exactly the
            //     work that went badly would shrink.
            //   * DONE counts as a start (dragged straight to Done = started, then
            //     finished), so the fastest items are not silently excluded.
            //   * NEVER cleared on the way out, unlike closedAt one line above: "is it
            //     closed" is a current-state question, "when did work start" is not.
            // Guarded by the category CHANGE like closedAt, so a TODO→TODO status move
            // (two to-do columns) is not a start.
            if (issue.getStartedAt() == null && !Objects.equals(oldCategory, newCategory)
                && (newCategory == StatusCategory.IN_PROGRESS || newCategory == StatusCategory.DONE)) {
                issue.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
            }
        }
        if (newPriority != null && !newPriority.getId().equals(issue.getPriority().getId())) {
            historyEntries.add(makeHistory(issue, actor, "priority",
                    issue.getPriority().getName(), newPriority.getName()));
            issue.setPriority(newPriority);
        }
        // Assignee: a non-null id sets it; clearAssignee (when no id) unsets it
        if (assigneeChanges) {
            String oldName = issue.getAssignee() != null ? issue.getAssignee().getDisplayName() : null;
            String newName = newAssignee != null ? newAssignee.getDisplayName() : null;
            historyEntries.add(makeHistory(issue, actor, "assignee", oldName, newName));
            issue.setAssignee(newAssignee);
        }
        if (dueDateSet) {
            historyEntries.add(makeHistory(issue, actor, "dueDate",
                    issue.getDueDate() != null ? issue.getDueDate().toString() : null,
                    req.dueDate().toString()));
            issue.setDueDate(req.dueDate());
        } else if (dueDateCleared) {
            historyEntries.add(makeHistory(issue, actor, "dueDate", issue.getDueDate().toString(), null));
            issue.setDueDate(null);
        }

        // Component (HD-31): a non-null componentId sets/changes it; clearComponent (no
        // id) unsets it — the assigneeId/clearAssignee convention. Auto-assign does NOT
        // fire here: changing a component later must never silently reassign someone's
        // work (§5.1). An ARCHIVED component is only rejected when it is an actual
        // change — an issue already carrying one stays editable (§5.4).
        if (componentChanges) {
            componentService.requireAssignable(newComponent);
            String oldName = issue.getComponent() != null ? issue.getComponent().getName() : null;
            String newName = newComponent != null ? newComponent.getName() : null;
            historyEntries.add(makeHistory(issue, actor, "component", oldName, newName));
            issue.setComponent(newComponent);
        }

        // Sprint (HD-22): a non-null sprintId sets/changes it; clearSprint (no id)
        // returns the issue to the backlog — the assigneeId/clearAssignee convention.
        // The RANK is deliberately untouched here: an issue that changes sprint keeps
        // its relative place, and re-ranking is the dedicated /rank endpoint's job.
        // A COMPLETED sprint is only rejected when it is an actual CHANGE — an issue
        // already sitting in one stays editable (its title, status, points…).
        // The freeze cuts BOTH ways: a completed sprint's membership is the delivery
        // record `complete` already reported, so an issue may no more be pulled OUT of
        // one than pushed INTO one.
        // The sprint the issue is LEAVING, captured before the field is overwritten: the
        // ledger (HD-137, door 2) closes that sprint's scope with it, and it is written
        // after the save below rather than here, so a body that also changes the estimate
        // records the points the sprint actually received.
        Sprint leftSprint = null;
        if (sprintChanges) {
            sprintService.requireDetachable(issue.getSprint());
            sprintService.requireAssignable(newSprint);
            String oldName = issue.getSprint() != null ? issue.getSprint().getName() : null;
            String newName = newSprint != null ? newSprint.getName() : null;
            historyEntries.add(makeHistory(issue, actor, "sprint", oldName, newName));
            leftSprint = issue.getSprint();
            issue.setSprint(newSprint);
        }

        // Story points (HD-22): same nullable-scalar convention. A no-op writes no
        // history row (compareTo, not equals: 5 and 5.00 are the same estimate).
        if (storyPointsChange) {
            var old = issue.getStoryPoints();
            historyEntries.add(makeHistory(issue, actor, "storyPoints",
                    old == null ? null : old.toPlainString(),
                    newStoryPoints == null ? null : newStoryPoints.toPlainString()));
            issue.setStoryPoints(newStoryPoints);
        }

        // Parent: a non-null parentId sets/changes it; clearParent (no id) detaches.
        // History records the old → new parent key.
        if (parentChanges) {
            String oldKey = issue.getParent() != null ? issueKey(issue.getParent()) : null;
            String newKey = newParent != null ? issueKey(newParent) : null;
            historyEntries.add(makeHistory(issue, actor, "parent", oldKey, newKey));
            issue.setParent(newParent);
        }

        // Labels (HD-30): full replacement when present. The diff itself was computed
        // with the other reads (it is pure, and the permission block needs its verdict);
        // only the history row and the version touch belong here. A set equal to the
        // current one is a no-op — no history row (the PATCH still bumps @Version).
        if (labelChange != null && labelChange.changed()) {
            historyEntries.add(makeHistory(issue, actor, "labels",
                    labelChange.oldNames(), labelChange.newNames()));
            // Labels live in a side table, so the Issue row would otherwise stay
            // clean and Hibernate would emit no UPDATE — leaving @Version and
            // updatedAt stale on a label-only PATCH. Touching updatedAt makes the
            // entity dirty, so the row is written exactly once: version +1 (§4.4)
            // and @LastModifiedDate re-stamps the audit time at pre-update.
            issue.setUpdatedAt(java.time.Instant.now());
        }

        // Fix / affects versions (HD-32): full replacement PER ROLE, independently —
        // sending only `fixVersionIds` never touches the affects set. Same shape as
        // labels: the diff is pure and was computed above, a set equal to the current one
        // is a no-op with no history row, and a real change touches updatedAt so the
        // side-table-only edit still bumps @Version exactly once.
        if (fixChange != null && fixChange.changed()) {
            historyEntries.add(makeHistory(issue, actor, "fixVersions",
                    fixChange.oldNames(), fixChange.newNames()));
            issue.setUpdatedAt(java.time.Instant.now());
        }
        if (affectsChange != null && affectsChange.changed()) {
            historyEntries.add(makeHistory(issue, actor, "affectsVersions",
                    affectsChange.oldNames(), affectsChange.newNames()));
            issue.setUpdatedAt(java.time.Instant.now());
        }

        // Custom fields: partial map, JSON null clears; changes land in history
        fieldValueService.applyValues(issue, req.fields(), false,
                (fieldName, oldVal, newVal) ->
                        historyEntries.add(makeHistory(issue, actor, fieldName, oldVal, newVal)));

        issueRepository.save(issue);
        labelService.applyLabelChange(issue, labelChange);
        versionService.applyVersionChanges(issue, fixChange, affectsChange);
        historyRepository.saveAll(historyEntries);
        // Door 2 (HD-137) — written last, next to the history rows it mirrors, so the
        // story points recorded are this request's final ones. A re-estimate on its own
        // is NOT a scope event (§2.3) and passes through here without a row, because
        // sprintChanges is false.
        if (sprintChanges) {
            scopeLedger.recordMove(workspaceId, project.getKey(), issue, leftSprint,
                    issue.getSprint(), actor);
        }

        // changeSet mirrors the history diff for future consumers (Phase-5 triggers);
        // the SSE payload is unchanged (the listener ignores it).
        var changeSet = historyEntries.stream()
                .map(h -> new FieldChange(h.getField(), h.getOldValue(), h.getNewValue()))
                .toList();
        // A PATCH that changed nothing must not announce an update (HD-125 review, L6).
        // Every real mutation writes at least one history row, so an empty changeSet IS
        // "nothing happened" — and announcing it anyway was a free amplifier: an
        // all-unchanged whole-form patch needs no permission at all (that is the point of
        // the per-field rules), so any member could turn one request into one push per
        // subscribed client, each of which refetches.
        if (!changeSet.isEmpty()) {
            eventPublisher.publishEvent(new IssueUpdated(workspaceId, projectId, number, changeSet));
        }
        return toResponse(issue);
    }

    /**
     * Move one issue in the shared backlog/board rank, optionally into or out of a
     * sprint in the same request (HD-22 §3.3, §4.4) — {@code POST …/issues/{n}/rank}.
     *
     * <p><strong>Permission: {@link Permission#ISSUE_RANK}</strong> (HD-123 §10.2), not
     * curation: dragging items around at a planning meeting is ordinary work the whole
     * team does, and requiring MANAGER would make the backlog read-only for most of it.
     * Ranking is nonetheless its own permission because "developers must not reprioritise
     * the backlog" is a real policy.
     *
     * <p><strong>This endpoint is a sprint door too</strong> (§6.5). {@code sprintId} /
     * {@code clearSprint} in a rank request move an issue in or out of a sprint exactly as
     * {@code PATCH /issues/{n}} and {@code POST /sprints/{id}/issues} do, so a sprint
     * change here additionally requires {@link Permission#SPRINT_ASSIGN}. §6.5 names only
     * the other two doors; leaving this one open would have made the permission
     * bypassable with a one-line curl, which is the exact failure the rule exists to
     * prevent.
     *
     * <p>The client sends only ANCHORS — the server computes the position
     * ({@link IssueRankService}), so a rank can never be invented or corrupted from a
     * payload, and {@code position} is not exposed on the response at all. Rank changes
     * write <strong>no history</strong> (positional churn would drown the log — the same
     * reasoning as label merge); a sprint change in the same request does.
     *
     * <p>{@code version} is OPTIONAL: present → 409 when stale, absent → the move
     * applies. Ranking is last-drag-wins; a mandatory optimistic round trip would 409-storm
     * a planning meeting (§3.3.3).
     */
    @Transactional
    public IssueResponse rank(User actor, UUID workspaceId, UUID projectId, long number,
                              RankIssueRequest req) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        // Permission first, project state second (§10.3.6) — for BOTH permissions this
        // endpoint can require. The sprint door cannot be evaluated here because it
        // depends on the issue's current sprint, so `requireNotArchived` waits until after
        // it (below) rather than running between the two: an archived project answering
        // 409 where every other converted site answers 403 would make the state of the
        // project observable through the authorization answer, which is precisely what
        // §10.3.6 forbids. Both checks still precede every mutation, including the rank
        // service's rebalance.
        ctx.permissions().require(Permission.ISSUE_RANK);
        var project = ctx.project();

        // ---- request shape: these are malformed requests, not business rejections ----
        if (req.sprintId() != null && req.clearSprint()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Send either sprintId or clearSprint, not both");
        }
        boolean sprintChangeRequested = req.sprintId() != null || req.clearSprint();
        if (req.afterIssueId() == null && req.beforeIssueId() == null && !sprintChangeRequested) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A rank request needs an anchor or a sprint change");
        }

        // ---- all reads first ----
        var issue = issueRepository.findByProjectAndNumber(project, number)
                .orElseThrow(IssueNotFoundException::new);
        if (req.version() != null && req.version() != issue.getVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Issue was modified by someone else — refresh and retry");
        }
        if (issue.getId().equals(req.afterIssueId()) || issue.getId().equals(req.beforeIssueId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "An issue can't be ranked against itself");
        }

        // The TARGET section: the sprint the issue ends up in (null = the backlog).
        UUID currentSprintId = issue.getSprint() == null ? null : issue.getSprint().getId();
        String oldSprintName = issue.getSprint() == null ? null : issue.getSprint().getName();
        UUID targetSprintId = req.sprintId() != null ? req.sprintId()
                : req.clearSprint() ? null : currentSprintId;
        boolean sprintChanges = !Objects.equals(currentSprintId, targetSprintId);
        if (sprintChanges) {
            // The third door (§6.5): moving between sections IS sprint assignment.
            ctx.permissions().require(Permission.SPRINT_ASSIGN);
        }

        // ---- every permission this request needs has now been checked; state next ----
        requireNotArchived(project);
        if (sprintChanges) {
            // A COMPLETED sprint's membership is a delivered fact: it can neither take a
            // new issue nor give one up (§4.5). Checked before any write happens.
            sprintService.requireDetachable(issue.getSprint());
            if (targetSprintId != null) {
                // Validates the id against THIS project (422 "Unknown sprint" for a
                // foreign one) and refuses a COMPLETED target.
                sprintService.requireAssignable(sprintService.resolveForIssue(project, targetSprintId));
            }
        }

        // Placement. NOTE: this may run a whole-project rebalance, whose @Modifying
        // carries clearAutomatically — so every entity loaded above is DETACHED
        // afterwards and must be re-read before it is mutated. It may also refuse with a
        // 429 when this project just re-spaced its ranks (the rebalance throttle).
        long newPosition = rankService.resolve(actor, project, issue.getId(), targetSprintId,
                req.afterIssueId(), req.beforeIssueId());

        // ---- then mutate, on freshly-managed entities ----
        var moved = issueRepository.findByProjectAndNumber(project, number)
                .orElseThrow(IssueNotFoundException::new);
        var historyEntries = new ArrayList<IssueHistory>(1);
        // HD-137, DOOR 3 — the door the proposal's table of five missed, because a drag
        // between the backlog and a sprint is a real membership change that merely
        // travels with a rank. `leftSprint` is read off the PRE-rebalance `issue` on
        // purpose: requireDetachable already dereferenced that association above, so its
        // state is loaded, whereas `moved.getSprint()` is a fresh lazy proxy the ledger
        // would have to initialise with an extra SELECT on every drag.
        Sprint leftSprint = null;
        Sprint targetSprint = null;
        if (sprintChanges) {
            targetSprint = targetSprintId == null ? null
                    : sprintService.resolveForIssue(project, targetSprintId);
            historyEntries.add(makeHistory(moved, actor, "sprint", oldSprintName,
                    targetSprint == null ? null : targetSprint.getName()));
            leftSprint = issue.getSprint();
            moved.setSprint(targetSprint);
        }
        moved.setPosition(newPosition);
        issueRepository.save(moved);
        historyRepository.saveAll(historyEntries);
        if (sprintChanges) {
            scopeLedger.recordMove(workspaceId, project.getKey(), moved, leftSprint,
                    targetSprint, actor);
        }

        var changeSet = historyEntries.stream()
                .map(h -> new FieldChange(h.getField(), h.getOldValue(), h.getNewValue()))
                .toList();
        eventPublisher.publishEvent(new IssueUpdated(workspaceId, projectId, number, changeSet));
        return toResponse(moved);
    }

    /**
     * <strong>{@link Permission#ISSUE_DELETE}, with the ownership modifier</strong>
     * (§6.4): {@code own} = the actor <em>reported</em> the issue (§19 OQ 2 — the
     * reporter, not the assignee). No built-in role holds it own-only today, so this is
     * behaviour-identical to the project-MANAGER gate it replaces; it exists because
     * "let people clean up their own mis-filed issues" is one of the asks the catalog was
     * built for.
     *
     * <p>The issue is loaded before the check because {@code isOwn} is a property of the
     * object; the archived-project 409 runs after it, because a 403 must not depend on
     * project state (§10.3.6). That is a deliberate flip of the old order.
     *
     * <p><strong>This is door 8 onto sprint membership</strong> (HD-137 review R3-1), and
     * the only one the {@code SprintScopeLedgerDoorsTest} source scan structurally cannot
     * find: deleting an issue ends its membership without ever writing {@code sprint_id},
     * so there is no {@code setSprint(…)} and no {@code SET sprint_id =} to match. See
     * {@link #recordDepartureOnDelete} for what it does and why it is conditional.
     */
    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, long number) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        var project = ctx.project();
        var issue = issueRepository.findByProjectAndNumber(project, number)
                .orElseThrow(IssueNotFoundException::new);
        ctx.permissions().require(Permission.ISSUE_DELETE, isReporter(issue, actor));
        requireNotArchived(project);

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

        recordDepartureOnDelete(workspaceId, project, issue, actor);

        // Must precede the delete: this reads issue_attachments to collect the storage keys,
        // and once `issue` is REMOVED there is no reliable rescue if reordered — Hibernate
        // autoflushes on query-space intersection, and the pending DELETE is on `issues`
        // while this query reads `issue_attachments` (disjoint spaces, so no autoflush).
        attachmentService.removeStoredFilesForIssue(issue);
        issueRepository.delete(issue);

        eventPublisher.publishEvent(new IssueDeleted(workspaceId, projectId, number));
    }

    /**
     * Door 8 (HD-137 review R3-1): a deleted issue leaves whatever sprint it was in, and
     * that departure is recorded — <strong>unless the sprint is COMPLETED</strong>.
     *
     * <p>The conditional IS the rule, so both halves are worth spelling out.
     *
     * <p><strong>COMPLETED → write nothing.</strong> A completed sprint's membership is
     * frozen: {@code SprintService.requireDetachable} refuses (422) to let even a curator
     * pull a DONE issue out of one, so "17 of 21 points delivered" cannot quietly become
     * another number. If a delete wrote a {@code REMOVED} there, the actor who was
     * explicitly refused the detach would get its exact effect by deleting the issue
     * instead — the review record would re-draw months later, with no error and nothing
     * to see. Deleting is a different act from editing history and must not be blocked;
     * the ledger simply keeps saying what the sprint delivered. (V18's issue FK is
     * {@code ON DELETE SET NULL (issue_id)} precisely so the existing rows survive the
     * issue and stay readable via {@code issue_key} + {@code story_points}.)
     *
     * <p><strong>Anything else (in practice ACTIVE) → record it.</strong> Here there is no
     * freeze to protect: {@code requireDetachable} would have allowed the removal through
     * the API, so a delete that wrote nothing would leave an {@code ADDED} with no
     * matching {@code REMOVED} and the running sprint's scope line would never come back
     * down — the burn-up would keep counting work that no longer exists. One
     * {@code REMOVED} restores the arithmetic; the FK then nulls its {@code issue_id}
     * moments later, which is exactly the case that column was made nullable for, and
     * both steps stay in the sum.
     *
     * <p>FUTURE needs no branch: {@code SprintScopeLedger} writes nothing for a sprint
     * that has not started (planning is not scope change), so the ledger's own gate
     * handles it.
     *
     * <p>It must run BEFORE {@code issueRepository.delete(issue)}, and the ledger method it
     * calls is a separate one from every other door's for a mechanical reason worth reading
     * once — see {@link SprintScopeLedger#recordDepartureBeforeDelete}: the row has to be
     * inserted while the issue is still merely managed, flushed, and then DETACHED, or
     * Hibernate's commit-time transient-reference check finds a managed row pointing at a
     * deleted entity and 500s the whole delete. That is not hypothetical — the same shape
     * without the detach is what made deleting an issue with an attachment fail until R4
     * (see {@code AttachmentService.removeStoredFilesForIssue}).
     *
     * <p><strong>One accepted anomaly, named rather than locked.</strong> The branch above
     * reads {@code sprint.getState()} from a row this transaction holds no lock on, so a
     * delete racing {@code POST /sprints/{id}/complete} can append a {@code REMOVED} to a
     * record that is frozen by the time it lands — one row, on one sprint, in the
     * milliseconds between the completion's conditional UPDATE and this read. It is
     * accepted deliberately: reaching it requires {@code SPRINT_MANAGE} (i.e. someone
     * already allowed to complete the sprint and to delete the issue), it is single-shot
     * rather than repeatable, and the only remedy — a row lock on {@code sprints} taken by
     * every issue delete — would serialise the most common write in the product behind
     * sprint lifecycle transactions for a race nobody has hit. Cheaper anomaly than cure.
     */
    private void recordDepartureOnDelete(UUID workspaceId, Project project, Issue issue, User actor) {
        var sprint = issue.getSprint();
        if (sprint == null || sprint.getState() == SprintState.COMPLETED) return;
        scopeLedger.recordDepartureBeforeDelete(workspaceId, project.getKey(), issue, sprint, actor);
    }

    // ---- catalog resolution ----
    //
    // CHECK MEMBERSHIP FIRST, THEN LOAD. The order is the whole of it, and it used to be the
    // other way round: these three did a bare findById across ALL scopes and handed the entity
    // to ProjectConfigService, whose refusal quoted its NAME. A member of workspace A passing
    // workspace B's private status id was told that the id exists, is unarchived, and what it is
    // called. Nothing cross-tenant was ever persisted — the guard did refuse the write — but the
    // refusal was itself the disclosure, and these were the only resolvers in create() that took
    // no project (resolveParent, and the component/label/version/sprint resolvers, all scope).
    //
    // Now the id is validated against the project's own effective config before anything is
    // loaded, so "unknown", "archived" and "belongs to another tenant" collapse into one answer
    // that names nothing. The load that follows cannot leak, because only an id already proven to
    // be offered here reaches it.
    //
    // The load is still a repository call rather than the cached instance: ProjectConfigCache
    // hands back DETACHED entities, and these are assigned straight onto the Issue.

    private IssueType resolveType(UUID id, Project project) {
        projectConfigService.requireTypeOffered(project, id);
        return issueTypeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "That issue type is not offered by this project"));
    }

    private Status resolveStatus(UUID id, Project project) {
        projectConfigService.requireStatusOffered(project, id);
        return statusRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "That status is not part of this project's workflow"));
    }

    private Priority resolvePriority(UUID id, Project project) {
        projectConfigService.requirePriorityOffered(project, id);
        return priorityRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "That priority is not offered by this project"));
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

    // ---- sprint / story-point validation (HD-22) ----

    /**
     * Validate a {@code storyPoints} payload (§3.4): {@code 0 ≤ v ≤ 999} with at most 2
     * decimals, else <strong>422</strong> — a nonsensical field value in a request the
     * caller is entitled to make. {@code null} (absent) is always fine and means
     * "unestimated", which is deliberately NOT the same statement as {@code 0}.
     *
     * <p>Checked here rather than left to {@code issues_story_points_ck} because a
     * driver-level constraint failure is a free 500 for any member — and the scale
     * check has no DB equivalent at all ({@code NUMERIC(5,2)} silently ROUNDS 1.234 to
     * 1.23, which would quietly change the user's number).
     *
     * <p>The bounds themselves live in {@link Points} because the HQL query path applies
     * the SAME domain to a {@code storyPoints} operand — a filter the write path could
     * never satisfy is a 422 there too, not a numeric-overflow 500 from the driver.
     */
    private static java.math.BigDecimal requireValidStoryPoints(java.math.BigDecimal raw) {
        if (raw == null) return null;
        if (!Points.inStoryPointRange(raw)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Story points must be between 0 and 999");
        }
        if (!Points.hasStoryPointScale(raw)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Story points allow at most 2 decimal places");
        }
        return raw;
    }

    /**
     * {@code ?sprintId=} and {@code ?noSprint=true} are contradictory ("in this sprint"
     * AND "in no sprint" can never both hold), so sending both is a malformed request —
     * <strong>400</strong>, not a silently empty result the caller would misread as
     * "nothing matches". A foreign sprint id on its own is NOT an error: it simply
     * matches nothing, which leaks nothing.
     */
    private static void requireCoherentSprintFilter(UUID sprintId, boolean noSprint) {
        if (sprintId != null && noSprint) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use either sprintId or noSprint, not both");
        }
    }

    /**
     * The assignee named by a request: a member of <em>this workspace</em> who holds
     * {@link Permission#ISSUE_ASSIGNABLE} in <em>this project</em>. Two failures, both
     * <strong>422</strong> because both are about the value rather than the caller's
     * entitlement, and deliberately worded apart (§10.3.4):
     * <ul>
     *   <li><em>"Unknown assignee"</em> — no such user, or not a member here. A bare
     *       {@code findById} would let callers reference and enumerate users from other
     *       tenants, so membership is the filter.</li>
     *   <li><em>"That user cannot be assigned in this project"</em> — a real colleague who
     *       may not be given work. §6.3's asymmetry: {@code issue.assign} is checked
     *       against the actor, {@code issue.assignable} against the target. A member must
     *       never be told a colleague does not exist, so the two messages must stay
     *       distinct.</li>
     * </ul>
     *
     * <p>One lookup of the target's permissions serves both questions — presence answers
     * membership, content answers assignability. Splitting them (as the first cut did) ran
     * the same resolution twice per assigned PATCH.
     */
    private User resolveAssignableAssignee(ProjectContext ctx, UUID assigneeId) {
        var target = userRepository.findById(assigneeId)
                .orElseThrow(() -> unknownAssignee());
        var permissions = workspaceAccess.projectPermissionsOf(target, ctx)
                .orElseThrow(() -> unknownAssignee());
        if (!permissions.has(Permission.ISSUE_ASSIGNABLE)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "That user cannot be assigned in this project");
        }
        return target;
    }

    private static ResponseStatusException unknownAssignee() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown assignee");
    }

    /** {@code target} if they may be given work in this project, else {@code null}. */
    private User assignableOrNull(ProjectContext ctx, User target) {
        if (target == null) return null;
        return workspaceAccess.projectPermissionsOf(target, ctx)
                .filter(p -> p.has(Permission.ISSUE_ASSIGNABLE))
                .map(p -> target)
                .orElse(null);
    }

    /** The ownership qualifier for {@code issue.edit} / {@code issue.delete} (§6.4). */
    private static boolean isReporter(Issue issue, User actor) {
        return issue.getReporter() != null && issue.getReporter().getId().equals(actor.getId());
    }

    /** Null-safe id of an entity that may be absent, for the "did it change" diffs. */
    private static UUID idOf(Issue issue) {
        return issue == null ? null : issue.getId();
    }

    private static UUID idOf(User user) {
        return user == null ? null : user.getId();
    }

    private static UUID idOf(Sprint sprint) {
        return sprint == null ? null : sprint.getId();
    }

    private static UUID idOf(Component component) {
        return component == null ? null : component.getId();
    }
}
