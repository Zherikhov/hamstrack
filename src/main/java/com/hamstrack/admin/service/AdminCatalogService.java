package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.*;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.IssueTypeSet;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.PrioritySet;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.issue.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Catalog CRUD (statuses, priorities, issue types) for every admin console,
 * scoped by a {@link ScopeContext}: global (system admin), workspace-scoped
 * (workspace OWNER/ADMIN) or project-private (project MANAGER). Rows are created
 * stamped with the scope and only rows at the caller's scope resolve for
 * edit/delete — inherited rows are invisible here (they're selected, not edited).
 *
 * <p>Deletion never leaves dangling references: entries used by issues require a
 * replacement (remap) or can be archived; workflow/set memberships are validated
 * so no workflow ends up empty and no set loses its default. Usage and integrity
 * checks span all scopes — a global status may be used by workflows anywhere.
 *
 * <p><strong>Since HD-13 that is enforced by the database, not only by the three
 * methods below</strong> ({@code issues_status_id_fkey} / {@code issues_type_id_fkey},
 * {@code V19__issues_taxonomy_fk.sql}; {@code issues_priority_id_fkey} since V1), which
 * turns two existing details into load-bearing ones. Neither may be tidied:
 * <ul>
 *   <li><strong>The guard count and the bulk remap must stay UNSCOPED.</strong>
 *       {@code issueRepository.countByStatus}/{@code countByType}/{@code countByPriority}
 *       and {@code remapStatus}/{@code remapType}/{@code remapPriority} deliberately span
 *       every tenant, and they are the same population. Narrow the count and a delete that
 *       should have been refused proceeds; narrow the remap and rows outside the caller's
 *       scope survive it. Either way the {@code DELETE} then meets a live reference and
 *       PostgreSQL raises {@code 23503}. Sealed by
 *       {@code CatalogDeleteGuardsStayUnscopedTest}.</li>
 *   <li><strong>The number SHOWN is the scoped one; the number that DECIDES is not.</strong>
 *       {@code docs/design/cross-tenant-data-exposure-audit.md} §4.4 rates the guard messages a
 *       cross-tenant disclosure, correctly — an unscoped total tells a delegated admin how much
 *       other tenants use a shared entry. The resolution is not to scope the query and not to
 *       drop the number, but to run <em>both</em>: {@code countByStatus} decides,
 *       {@code countByStatusScoped} is quoted. They return the same value today, because
 *       {@code requireStatus} resolves through {@code findByIdAtScope} and so the guard is only
 *       reachable for an own-scope row, which only own-scope issues can reference. Writing both
 *       is what turns that coincidence from a paragraph into code: if the construction is ever
 *       broken, the decision stays correct and the message stops leaking, rather than both
 *       failing at once.</li>
 *   <li><strong>The remap runs BEFORE the delete</strong>, as an immediate bulk
 *       {@code UPDATE} rather than a queued action, so the wire order is {@code UPDATE} then
 *       {@code DELETE} regardless of Hibernate's flush ordering. Do not move it.
 *       {@code @Modifying(clearAutomatically = true)} on the remaps is likewise deliberate:
 *       it is what stops a stale {@code Issue} loaded earlier in the transaction from
 *       flushing the old id back over the remap.</li>
 * </ul>
 *
 * <p>The foreign keys buy integrity and <strong>not tenancy</strong> — they are
 * single-column, so a parent row in any tenant satisfies them. Keeping a remap inside the
 * caller's tenant is this class's own job, done by resolving {@code replaceWithId} through
 * {@code findByIdVisibleTo} (404 on a miss, never 403).
 */
@Service
@RequiredArgsConstructor
public class AdminCatalogService {

    private final StatusRepository statusRepository;
    private final PriorityRepository priorityRepository;
    private final IssueTypeRepository issueTypeRepository;
    private final IssueRepository issueRepository;
    private final WorkflowStatusRepository workflowStatusRepository;
    private final WorkflowTransitionRepository workflowTransitionRepository;
    private final PrioritySetItemRepository prioritySetItemRepository;
    private final IssueTypeSetItemRepository issueTypeSetItemRepository;
    private final ProjectCountService projectCountService;

    // ---------- statuses ----------

    @Transactional(readOnly = true)
    public List<AdminStatusResponse> listStatuses(ScopeContext scope) {
        // Delegated consoles also SEE inherited (global/workspace) rows — read-only,
        // tagged by scope — so a project admin sees what the project already uses;
        // only own-scope rows are editable (enforced by findByIdAtScope on write).
        var rows = scope.isGlobal()
                ? statusRepository.findAllAtScope(null, null)
                : statusRepository.findAllVisibleTo(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return rows.stream().map(s -> AdminStatusResponse.of(s, statusUsage(scope, s))).toList();
    }

    @Transactional
    public AdminStatusResponse createStatus(ScopeContext scope, UpsertStatusRequest req) {
        if (statusRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A status named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        var s = new Status();
        scope.stamp(s);
        s.setName(req.name());
        s.setCategory(req.category());
        if (req.color() != null) s.setColor(req.color());
        s.setPosition(req.position() != null ? req.position() : nextPosition(
                statusRepository.findAllAtScope(scope.workspaceId(), scope.projectId()).stream()
                        .mapToInt(Status::getPosition).max().orElse(-1)));
        statusRepository.save(s);
        return AdminStatusResponse.of(s, statusUsage(scope, s));
    }

    @Transactional
    public AdminStatusResponse updateStatus(ScopeContext scope, UUID id, UpsertStatusRequest req) {
        var s = requireStatus(scope, id);
        if (!s.getName().equals(req.name())
                && statusRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A status named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        s.setName(req.name());
        s.setCategory(req.category());
        if (req.color() != null) s.setColor(req.color());
        if (req.position() != null) s.setPosition(req.position());
        statusRepository.save(s);
        return AdminStatusResponse.of(s, statusUsage(scope, s));
    }

    @Transactional
    public void setStatusArchived(ScopeContext scope, UUID id, boolean archived) {
        var s = requireStatus(scope, id);
        s.setArchivedAt(archived ? Instant.now() : null);
        statusRepository.save(s);
    }

    /**
     * Delete with optional remap. Refused (409) while issues reference the
     * status and no replacement is given, or when removing it would leave a
     * workflow empty. Workflow memberships/transitions are cleaned up by FK
     * cascade after issues are remapped.
     */
    @Transactional
    public void deleteStatus(ScopeContext scope, UUID id, UUID replaceWithId) {
        var s = requireStatus(scope, id);
        // TWO COUNTS, ON PURPOSE — one decides, the other is displayed. The DECISION must stay
        // unscoped: since V19 the remap below and this guard cover the same population, so a
        // scoped decision would let a delete through, leave rows pointing at the row, and turn
        // the DELETE into a 23503. The DISPLAYED number must be scoped: a delegated admin may
        // not learn how many issues other tenants have on a shared entry.
        //
        // They coincide by construction today, because requireStatus resolves through
        // findByIdAtScope — so this guard is only ever reachable for an own-scope row, which only
        // own-scope issues can reference. That is the argument FOR writing it this way rather
        // than for collapsing it: it turns "these coincide by construction" from a paragraph into
        // code, so if the construction is ever broken the decision stays correct while the
        // message stops leaking, instead of both going wrong together.
        long issues = issueRepository.countByStatus(s);
        if (issues > 0 && replaceWithId == null) {
            long mine = issueRepository.countByStatusScoped(s, scope.workspaceId(), scope.projectId());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    inUse(mine, "status"));
        }
        for (var wf : workflowStatusRepository.findWorkflowsUsingStatus(id)) {
            if (workflowStatusRepository.findAllByWorkflowOrderByPosition(wf).size() <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Workflow '" + wf.getName() + "' would be left without statuses");
            }
        }
        if (replaceWithId != null) {
            // Scoped, not findById: the replacement must be a row THIS scope can see
            // (global ∪ own workspace ∪ own project), exactly as every sibling admin
            // service resolves a catalog child. A bare findById let a workspace- or
            // project-scoped admin name ANOTHER tenant's project-scoped status and have
            // the bulk remap below repoint their issues at it — a cross-tenant write the
            // new issues_status_id_fkey accepts happily, because the parent row exists
            // (V19's header says why the constraint cannot express tenancy). 404 on a
            // miss, never 403: a row the caller cannot see must be indistinguishable
            // from one that does not exist.
            var replacement = statusRepository.findByIdVisibleTo(
                            replaceWithId, scope.visibleWorkspaceId(), scope.visibleProjectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Replacement status not found"));
            if (replacement.getId().equals(s.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Replacement must differ");
            }
            // Every workflow that contains the deleted status must offer the
            // replacement, otherwise remapped issues would leave their board
            for (var wf : workflowStatusRepository.findWorkflowsUsingStatus(id)) {
                if (!workflowStatusRepository.existsByWorkflowAndStatus(wf, replacement)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Replacement status is not part of workflow '" + wf.getName() + "'");
                }
            }
            issueRepository.remapStatus(s, replacement);
        }
        statusRepository.delete(s);
    }

    // ---------- priorities ----------

    @Transactional(readOnly = true)
    public List<AdminPriorityResponse> listPriorities(ScopeContext scope) {
        var rows = scope.isGlobal()
                ? priorityRepository.findAllAtScope(null, null)
                : priorityRepository.findAllVisibleTo(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return rows.stream().map(p -> AdminPriorityResponse.of(p, priorityUsage(scope, p))).toList();
    }

    @Transactional
    public AdminPriorityResponse createPriority(ScopeContext scope, UpsertPriorityRequest req) {
        if (priorityRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A priority named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        var p = new Priority();
        scope.stamp(p);
        p.setName(req.name());
        if (req.color() != null) p.setColor(req.color());
        p.setIcon(req.icon());
        p.setPosition(req.position() != null ? req.position() : nextPosition(
                priorityRepository.findAllAtScope(scope.workspaceId(), scope.projectId()).stream()
                        .mapToInt(Priority::getPosition).max().orElse(-1)));
        priorityRepository.save(p);
        return AdminPriorityResponse.of(p, priorityUsage(scope, p));
    }

    @Transactional
    public AdminPriorityResponse updatePriority(ScopeContext scope, UUID id, UpsertPriorityRequest req) {
        var p = requirePriority(scope, id);
        if (!p.getName().equals(req.name())
                && priorityRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A priority named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        p.setName(req.name());
        if (req.color() != null) p.setColor(req.color());
        p.setIcon(req.icon());
        if (req.position() != null) p.setPosition(req.position());
        priorityRepository.save(p);
        return AdminPriorityResponse.of(p, priorityUsage(scope, p));
    }

    @Transactional
    public void setPriorityArchived(ScopeContext scope, UUID id, boolean archived) {
        var p = requirePriority(scope, id);
        p.setArchivedAt(archived ? Instant.now() : null);
        priorityRepository.save(p);
    }

    @Transactional
    public void deletePriority(ScopeContext scope, UUID id, UUID replaceWithId) {
        var p = requirePriority(scope, id);
        // Two counts — see the note in deleteStatus. Unscoped decides, scoped is displayed.
        long issues = issueRepository.countByPriority(p);
        if (issues > 0 && replaceWithId == null) {
            long mine = issueRepository.countByPriorityScoped(p, scope.workspaceId(), scope.projectId());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    inUse(mine, "priority"));
        }
        var sets = prioritySetItemRepository.findSetsUsingPriority(id);
        for (var set : sets) {
            if (prioritySetItemRepository.findAllBySetOrderByPosition(set).size() <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Priority set '" + set.getName() + "' would be left empty");
            }
        }
        Priority replacement = null;
        if (replaceWithId != null) {
            // Scoped, not findById — see the note in deleteStatus. Priorities are the
            // empirical proof that the FK does not carry tenancy: issues_priority_id_fkey
            // has been in the schema since V1 and never stopped this.
            replacement = priorityRepository.findByIdVisibleTo(
                            replaceWithId, scope.visibleWorkspaceId(), scope.visibleProjectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Replacement priority not found"));
            if (replacement.getId().equals(p.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Replacement must differ");
            }
            issueRepository.remapPriority(p, replacement);
        }
        // Keep every affected set with a default: hand the flag to the
        // replacement (if member) or to the first remaining item
        for (var set : sets) {
            var items = prioritySetItemRepository.findAllBySetOrderByPosition(set);
            var deletedItem = items.stream()
                    .filter(i -> i.getPriority().getId().equals(p.getId())).findFirst().orElse(null);
            if (deletedItem != null && deletedItem.isDefaultForNewIssues()) {
                var remaining = items.stream()
                        .filter(i -> !i.getPriority().getId().equals(p.getId())).toList();
                var repl = replacement;
                var heir = remaining.stream()
                        .filter(i -> repl != null && i.getPriority().getId().equals(repl.getId()))
                        .findFirst().orElse(remaining.getFirst());
                heir.setDefaultForNewIssues(true);
                prioritySetItemRepository.save(heir);
            }
        }
        prioritySetItemRepository.deleteAllByPriority(p);
        priorityRepository.delete(p);
    }

    // ---------- issue types ----------

    @Transactional(readOnly = true)
    public List<AdminIssueTypeResponse> listIssueTypes(ScopeContext scope) {
        var rows = scope.isGlobal()
                ? issueTypeRepository.findAllAtScope(null, null)
                : issueTypeRepository.findAllVisibleTo(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return rows.stream().map(t -> AdminIssueTypeResponse.of(t, issueTypeUsage(scope, t))).toList();
    }

    @Transactional
    public AdminIssueTypeResponse createIssueType(ScopeContext scope, UpsertIssueTypeRequest req) {
        if (issueTypeRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An issue type named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        var t = new IssueType();
        scope.stamp(t);
        t.setName(req.name());
        if (req.color() != null) t.setColor(req.color());
        t.setIcon(req.icon());
        t.setPosition(req.position() != null ? req.position() : nextPosition(
                issueTypeRepository.findAllAtScope(scope.workspaceId(), scope.projectId()).stream()
                        .mapToInt(IssueType::getPosition).max().orElse(-1)));
        issueTypeRepository.save(t);
        return AdminIssueTypeResponse.of(t, new UsageInfo(0, 0, 0, 0));
    }

    @Transactional
    public AdminIssueTypeResponse updateIssueType(ScopeContext scope, UUID id, UpsertIssueTypeRequest req) {
        var t = requireIssueType(scope, id);
        if (!t.getName().equals(req.name())
                && issueTypeRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An issue type named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        t.setName(req.name());
        if (req.color() != null) t.setColor(req.color());
        t.setIcon(req.icon());
        if (req.position() != null) t.setPosition(req.position());
        issueTypeRepository.save(t);
        return AdminIssueTypeResponse.of(t, issueTypeUsage(scope, t));
    }

    @Transactional
    public void setIssueTypeArchived(ScopeContext scope, UUID id, boolean archived) {
        var t = requireIssueType(scope, id);
        t.setArchivedAt(archived ? Instant.now() : null);
        issueTypeRepository.save(t);
    }

    @Transactional
    public void deleteIssueType(ScopeContext scope, UUID id, UUID replaceWithId) {
        var t = requireIssueType(scope, id);
        // Two counts — see the note in deleteStatus. Unscoped decides, scoped is displayed.
        long issues = issueRepository.countByType(t);
        if (issues > 0 && replaceWithId == null) {
            long mine = issueRepository.countByTypeScoped(t, scope.workspaceId(), scope.projectId());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    inUse(mine, "type"));
        }
        // The base (global) catalog must never be emptied; inherited types cover
        // delegated scopes, so they may delete their last own type freely.
        if (scope.isGlobal() && issueTypeRepository.findAllAtScope(null, null).size() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "At least one issue type must remain");
        }
        for (var set : issueTypeSetItemRepository.findSetsUsingType(id)) {
            if (issueTypeSetItemRepository.findAllBySetOrderByPosition(set).size() <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Issue type set '" + set.getName() + "' would be left empty");
            }
        }
        if (replaceWithId != null) {
            // Scoped, not findById — see the note in deleteStatus.
            var replacement = issueTypeRepository.findByIdVisibleTo(
                            replaceWithId, scope.visibleWorkspaceId(), scope.visibleProjectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Replacement type not found"));
            if (replacement.getId().equals(t.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Replacement must differ");
            }
            issueRepository.remapType(t, replacement);
        }
        // Set memberships are cleaned up by FK cascade
        issueTypeRepository.delete(t);
    }

    // ---------- usage detail (popovers) ----------

    @Transactional(readOnly = true)
    public UsageDetailResponse statusUsageDetail(ScopeContext scope, UUID id) {
        var s = requireStatus(scope, id);
        var workflows = workflowStatusRepository.findWorkflowsUsingStatus(s.getId()).stream()
                .filter(scope::canSee).toList();
        var projects = workflows.stream()
                .flatMap(wf -> projectCountService.projectsListUsingWorkflow(scope, wf).stream())
                .toList();
        return new UsageDetailResponse(
                workflows.stream().map(Workflow::getName).toList(),
                List.of(),
                UsageDetailResponse.dedupe(projects),
                issueRepository.countByStatusScoped(s, scope.workspaceId(), scope.projectId()));
    }

    @Transactional(readOnly = true)
    public UsageDetailResponse priorityUsageDetail(ScopeContext scope, UUID id) {
        var p = requirePriority(scope, id);
        var sets = prioritySetItemRepository.findSetsUsingPriority(p.getId()).stream()
                .filter(scope::canSee).toList();
        var projects = sets.stream()
                .flatMap(set -> projectCountService.projectsListUsingPrioritySet(scope, set).stream())
                .toList();
        return new UsageDetailResponse(
                List.of(),
                sets.stream().map(PrioritySet::getName).toList(),
                UsageDetailResponse.dedupe(projects),
                issueRepository.countByPriorityScoped(p, scope.workspaceId(), scope.projectId()));
    }

    @Transactional(readOnly = true)
    public UsageDetailResponse issueTypeUsageDetail(ScopeContext scope, UUID id) {
        var t = requireIssueType(scope, id);
        var sets = issueTypeSetItemRepository.findSetsUsingType(t.getId()).stream()
                .filter(scope::canSee).toList();
        var projects = sets.stream()
                .flatMap(set -> projectCountService.projectsListUsingIssueTypeSet(scope, set).stream())
                .toList();
        return new UsageDetailResponse(
                List.of(),
                sets.stream().map(IssueTypeSet::getName).toList(),
                UsageDetailResponse.dedupe(projects),
                issueRepository.countByTypeScoped(t, scope.workspaceId(), scope.projectId()));
    }

    // ---------- helpers ----------

    // Usage is aggregated only over containers (workflows/sets) the scope can
    // see and only over projects/issues within the scope — so a delegated
    // console never reports figures spanning other tenants (see ScopeContext.canSee).
    private UsageInfo statusUsage(ScopeContext scope, Status s) {
        var workflows = workflowStatusRepository.findWorkflowsUsingStatus(s.getId()).stream()
                .filter(scope::canSee).toList();
        long projects = workflows.stream()
                .mapToLong(wf -> projectCountService.projectsUsingWorkflow(scope, wf)).sum();
        return new UsageInfo(workflows.size(), 0, projects,
                issueRepository.countByStatusScoped(s, scope.workspaceId(), scope.projectId()));
    }

    private UsageInfo priorityUsage(ScopeContext scope, Priority p) {
        var sets = prioritySetItemRepository.findSetsUsingPriority(p.getId()).stream()
                .filter(scope::canSee).toList();
        long projects = sets.stream()
                .mapToLong(set -> projectCountService.projectsUsingPrioritySet(scope, set)).sum();
        return new UsageInfo(0, sets.size(), projects,
                issueRepository.countByPriorityScoped(p, scope.workspaceId(), scope.projectId()));
    }

    private UsageInfo issueTypeUsage(ScopeContext scope, IssueType t) {
        var sets = issueTypeSetItemRepository.findSetsUsingType(t.getId()).stream()
                .filter(scope::canSee).toList();
        long projects = sets.stream()
                .mapToLong(set -> projectCountService.projectsUsingIssueTypeSet(scope, set)).sum();
        return new UsageInfo(0, sets.size(), projects,
                issueRepository.countByTypeScoped(t, scope.workspaceId(), scope.projectId()));
    }

    private short nextPosition(int currentMax) {
        return (short) (currentMax + 1);
    }

    private Status requireStatus(ScopeContext scope, UUID id) {
        return statusRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Status not found"));
    }

    private Priority requirePriority(ScopeContext scope, UUID id) {
        return priorityRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Priority not found"));
    }

    private IssueType requireIssueType(ScopeContext scope, UUID id) {
        return issueTypeRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue type not found"));
    }

    /**
     * The "still in use" refusal, built from the <strong>scoped</strong> count (see the note in
     * {@link #deleteStatus}: a different, unscoped count made the decision).
     *
     * <p>Handles the case that can only arise if the coincidence those two counts rely on is ever
     * broken — an entry used by issues the caller cannot see, so the unscoped count refused while
     * the scoped one is <strong>zero</strong>. The refusal is still correct and must still
     * happen; it simply cannot quote a number, and "0 issues use this status" would read as a
     * bug.
     *
     * <p><strong>The degraded sentence is neutral on purpose.</strong> It said "Issues elsewhere
     * use this status", which is a boolean answer to "do other tenants use this?" — a disclosure
     * in the one state where the caller can see nothing, which is precisely where the message
     * should say least. "This status is still in use" refuses identically. It also stops
     * <em>prescribing</em> in that state: following {@code replaceWithId} there would run the
     * unscoped remap and repoint other tenants' issues at a replacement drawn from the caller's
     * own scope. Unreachable today, and V19's pre-flight found no such data — but a self-hosted
     * instance carrying legacy cross-scope rows is exactly who would meet it.
     *
     * <p>Pluralised because these messages are read by people, and because the previous version
     * emitted "1 issues use this status" for the commonest case of all.
     */
    private static String inUse(long mine, String what) {
        if (mine == 0) {
            // Neither a number nor a prescription — see the javadoc. "Still in use" is the whole
            // of what this caller can act on, and archiving is the only remedy that is safe here.
            return "This " + what + " is still in use — archive it instead";
        }
        return (mine == 1 ? "1 issue uses this " + what : mine + " issues use this " + what)
               + " — pass replaceWithId to remap " + (mine == 1 ? "it" : "them")
               + ", or archive instead";
    }
}
