package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.config.ClassificationProperties;
import com.hamstrack.common.security.Permission;
import com.hamstrack.issue.dto.ComponentResponse;
import com.hamstrack.issue.dto.ComponentUsageResponse;
import com.hamstrack.issue.dto.CreateComponentRequest;
import com.hamstrack.issue.dto.UpdateComponentRequest;
import com.hamstrack.issue.entity.Component;
import com.hamstrack.issue.exception.ComponentNameConflictException;
import com.hamstrack.issue.exception.ComponentNotFoundException;
import com.hamstrack.issue.repository.ComponentRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Project components (HD-31, labels-components-versions-proposal §5) — CRUD,
 * archive, delete — plus the issue-side resolution and the create-time auto-assign.
 *
 * <p><strong>Tenancy (§3.1):</strong> every entry point resolves through
 * {@link WorkspaceAccessService#resolveProject} — a missing workspace, a missing
 * project and a non-member all yield <strong>404</strong>, never 403. 403 is reserved
 * for a <em>member without the permission</em> ({@link Permission#COMPONENT_MANAGE}),
 * and by construction it can only reach someone whose membership is already proved.
 * Component lookups always go through {@code findByIdAndProject}: a foreign id is a 404
 * on a direct read and a <strong>422</strong> "Unknown component" inside an issue payload
 * (an invalid field value, leaking nothing about the other tenant).
 *
 * <p><strong>Permissions (§3.3, HD-123 §10.2):</strong> read = any project member (i.e. a
 * workspace member for whom the project resolves); create/edit/archive/delete =
 * {@link Permission#COMPONENT_MANAGE}, held by the built-in project MANAGER and — via
 * {@link Permission#PROJECT_CURATE_ALL} — by a workspace OWNER/ADMIN in every project of
 * their workspace. Assigning a component <em>to an issue</em> is deliberately
 * {@code issue.edit}, not this: this permission governs the catalog object's lifecycle
 * (§6.5).
 *
 * <p>Components are <em>content</em>, not bound taxonomy: nothing here touches
 * {@code ProjectConfigService} or {@code ProjectConfigResponse} (§3.2).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ComponentService {

    private final WorkspaceAccessService workspaceAccess;
    private final ComponentRepository componentRepository;
    private final IssueRepository issueRepository;
    private final UserRepository userRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ClassificationProperties classificationProperties;

    /** Max name length AFTER normalization (matches {@code components.name VARCHAR(80)}). */
    private static final int MAX_NAME_LENGTH = 80;

    /** The one constraint a component write may legitimately lose to (V9__components.sql). */
    private static final String NAME_UNIQUE_CONSTRAINT = "components_project_name_uk";

    // ================================================================= catalog CRUD

    /**
     * The project's components, ordered by {@code lower(name)} (§5.3). {@code withUsage}
     * adds {@code issueCount} via ONE grouped query (never per component).
     */
    @Transactional(readOnly = true)
    public List<ComponentResponse> list(User actor, UUID workspaceId, UUID projectId,
                                        boolean includeArchived, boolean withUsage) {
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        var components = componentRepository.findAllByProject(project, includeArchived);
        if (!withUsage || components.isEmpty()) {
            return components.stream().map(c -> ComponentResponse.of(c, null)).toList();
        }
        var counts = usageCounts(components);
        return components.stream()
                .map(c -> ComponentResponse.of(c, counts.getOrDefault(c.getId(), 0)))
                .toList();
    }

    /** One component — any project member (reads are unrestricted within the project). */
    @Transactional(readOnly = true)
    public ComponentResponse get(User actor, UUID workspaceId, UUID projectId, UUID componentId) {
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        return ComponentResponse.of(requireComponent(project, componentId), null);
    }

    /**
     * Create — project MANAGER or workspace OWNER/ADMIN (§3.3); frozen while the
     * project is archived.
     *
     * <p>The curator gate is <strong>not</strong> a volume barrier: any authenticated
     * user can create their own workspace, is its OWNER and is therefore curator of
     * every project in it, so component creation is effectively self-serve. The catalog
     * is therefore bounded by {@code app.classification.max-components-per-project}
     * (422 when full), exactly as the label catalog is bounded per workspace —
     * {@code ResolutionContextFactory} loads every visible project's whole component
     * catalog on every {@code /search}, {@code /search/schema} and
     * {@code /search/suggest} call, which on shared Cloud infrastructure makes an
     * unbounded catalog a cost co-resident tenants pay.
     */
    @Transactional
    public ComponentResponse create(User actor, UUID workspaceId, UUID projectId,
                                    CreateComponentRequest req) {
        // HD-123 S2: permission first, project state second (§10.3.6).
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.COMPONENT_MANAGE);
        var project = ctx.project();
        requireNotArchived(project);
        var workspace = project.getWorkspace();

        String name = requireValidName(req.name());
        var lead = req.leadId() != null ? resolveLead(workspace, req.leadId()) : null;
        requireAutoAssignHasLead(req.autoAssign(), lead);

        // Pre-check the common case so a duplicate is a clean 409 instead of a
        // constraint violation that has already marked the transaction rollback-only.
        componentRepository.findByProjectAndNameIgnoreCase(project, name)
                .ifPresent(existing -> { throw duplicate(existing); });

        int maxPerProject = classificationProperties.maxComponentsPerProject();
        if (componentRepository.countByProject(project) >= maxPerProject) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "This project already has the maximum of " + maxPerProject
                            + " components — archive or delete some first");
        }

        var component = new Component();
        component.setWorkspace(workspace);
        component.setProject(project);
        component.setName(name);
        component.setDescription(trimToNull(req.description()));
        component.setLead(lead);
        component.setAutoAssign(req.autoAssign());
        try {
            // Flush now so a concurrent-create race surfaces HERE (as a translated
            // DataIntegrityViolationException) instead of at commit, where it would
            // escape as a 500 after the controller already built a 201.
            componentRepository.saveAndFlush(component);
        } catch (DataIntegrityViolationException e) {
            // ONLY the name index means "someone else won the race"; anything else is a
            // genuine fault and must keep its 500 instead of masquerading as a 409.
            if (!isNameConflict(e)) throw e;
            throw new ComponentNameConflictException(
                    "A component named '" + name + "' already exists in this project");
        }
        return ComponentResponse.of(component, null);
    }

    /** Rename / describe / re-lead / toggle auto-assign — curator only. */
    @Transactional
    public ComponentResponse update(User actor, UUID workspaceId, UUID projectId, UUID componentId,
                                    UpdateComponentRequest req) {
        // HD-123 S2: permission first, project state second (§10.3.6).
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.COMPONENT_MANAGE);
        var project = ctx.project();
        requireNotArchived(project);
        var workspace = project.getWorkspace();
        var component = requireComponent(project, componentId);

        // ---- all reads first (CLAUDE.md: mutate-then-query double-writes the row) ----
        String newName = null;
        if (req.name() != null) {
            newName = requireValidName(req.name());
            // A pure casing change keeps the same unique slot — allow it.
            if (!newName.equalsIgnoreCase(component.getName())) {
                componentRepository.findByProjectAndNameIgnoreCase(project, newName)
                        .ifPresent(existing -> { throw duplicate(existing); });
            }
        }
        var newLead = req.leadId() != null ? resolveLead(workspace, req.leadId()) : null;
        // The lead this component ends up with: replaced, cleared, or untouched.
        var effectiveLead = req.leadId() != null ? newLead
                : (req.clearLead() ? null : component.getLead());
        boolean effectiveAutoAssign = req.autoAssign() != null ? req.autoAssign() : component.isAutoAssign();
        requireAutoAssignHasLead(effectiveAutoAssign, effectiveLead);
        // create() can only ever pair auto-assign with a lead resolveLead() just proved is
        // a member; update() could not, because an untouched `leadId` reuses the STORED
        // lead without re-checking. So `autoAssign: true` was accepted for a lead who had
        // already left the workspace — inert (autoAssignee re-checks at issue-create time,
        // §5.4) but a UX wart: the setting reads as on while it can never fire.
        // Enforced ONLY when the request actually asserts autoAssign — a rename or a
        // description edit must not start failing because the lead left months ago.
        if (Boolean.TRUE.equals(req.autoAssign()) && req.leadId() == null) {
            requireLeadStillMember(workspace, effectiveLead);
        }
        // Captured BEFORE the mutation: the conflict message below must not be read off
        // an entity that is dirty (and, after a failed flush, unusable).
        String attemptedName = newName != null ? newName : component.getName();

        // ---- then mutate ----
        if (newName != null) component.setName(newName);
        if (req.description() != null) component.setDescription(trimToNull(req.description()));
        if (req.leadId() != null || req.clearLead()) component.setLead(effectiveLead);
        if (req.autoAssign() != null) component.setAutoAssign(effectiveAutoAssign);

        // The pre-check above is not atomic with the flush, so a concurrent rename into
        // the same slot is arbitrated by components_project_name_uk. Recover the same
        // way create() does — a 409, never a 500.
        try {
            // Flush so the response carries the audited updatedAt, not the pre-update one.
            return ComponentResponse.of(componentRepository.saveAndFlush(component), null);
        } catch (DataIntegrityViolationException e) {
            if (!isNameConflict(e)) throw e;
            throw new ComponentNameConflictException(
                    "A component named '" + attemptedName + "' already exists in this project");
        }
    }

    /**
     * Archive (§5.4): always allowed, even in use. The component leaves every picker
     * and the default settings list; existing assignments are preserved and render
     * dimmed. This is the recommended safe alternative to delete.
     */
    @Transactional
    public ComponentResponse archive(User actor, UUID workspaceId, UUID projectId, UUID componentId) {
        return setArchived(actor, workspaceId, projectId, componentId, true);
    }

    /** Unarchive — the row already owns its unique name slot, so this can't conflict. */
    @Transactional
    public ComponentResponse unarchive(User actor, UUID workspaceId, UUID projectId, UUID componentId) {
        return setArchived(actor, workspaceId, projectId, componentId, false);
    }

    private ComponentResponse setArchived(User actor, UUID workspaceId, UUID projectId,
                                          UUID componentId, boolean archived) {
        // HD-123 S2: permission first, project state second (§10.3.6).
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.COMPONENT_MANAGE);
        var project = ctx.project();
        requireNotArchived(project);
        var component = requireComponent(project, componentId);
        component.setArchivedAt(archived ? Instant.now() : null);
        return ComponentResponse.of(componentRepository.saveAndFlush(component), null);
    }

    /**
     * Delete (§5.4) — curator only. In use → <strong>409</strong> unless {@code force},
     * which nulls the component on every affected issue and then drops the row. There
     * is deliberately no remap-to-another-component in MVP: archive covers the
     * "keep the history" case, and a {@code remapTo} parameter can be added later
     * without a breaking change. No per-issue history rows either — a force delete can
     * touch thousands of issues and one request must stay bounded (§5.4).
     *
     * <p><strong>The explicit bulk UPDATE is not optional</strong> (§5.2 trap): the FK
     * is {@code ON DELETE SET NULL (component_id)}, which clears the column
     * <em>behind</em> JPA's back — a managed, now-stale {@code Issue} flushed later in
     * the same transaction would write the old id back (the {@code issue_seq} clobber
     * class of bug). Nulling the column through JPQL first keeps the persistence
     * context honest; the FK stays as the safety net for out-of-band deletes.
     * {@code clearAutomatically} is safe HERE because this method has no other pending
     * inserts — do not copy it into a method that does.
     */
    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, UUID componentId, boolean force) {
        // HD-123 S2: permission first, project state second (§10.3.6).
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.COMPONENT_MANAGE);
        var project = ctx.project();
        requireNotArchived(project);
        var component = requireComponent(project, componentId);

        long inUse = issueRepository.countByComponent(component);
        if (inUse > 0 && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This component is used on " + inUse
                            + " issue(s) — archive it, or delete with force");
        }
        if (inUse > 0) {
            issueRepository.clearComponent(component);
        }
        componentRepository.delete(component);
    }

    /** Usage count for one component — any project member (reads are unrestricted). */
    @Transactional(readOnly = true)
    public ComponentUsageResponse usage(User actor, UUID workspaceId, UUID projectId, UUID componentId) {
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        var component = requireComponent(project, componentId);
        return new ComponentUsageResponse((int) issueRepository.countByComponent(component));
    }

    // ================================================================= HQL support

    /**
     * Bounded (≤ {@code limit}) typeahead over the non-archived component names of the
     * actor's VISIBLE projects — backs {@code /search/suggest?field=component} when the
     * {@code /schema} picklist is truncated (§3.5). The caller has already verified
     * workspace membership and passes {@code SearchScope.visibleProjectIds}, so a name
     * can never resolve through a project the actor cannot see.
     *
     * <p>Names are de-duplicated case-insensitively: two projects may each own a
     * "Billing", and the suggestion list is about names, not rows.
     */
    @Transactional(readOnly = true)
    public List<String> suggestNames(Collection<UUID> visibleProjectIds, String query, int limit) {
        // An empty IN list is invalid in JPQL/Hibernate — short-circuit instead.
        if (visibleProjectIds == null || visibleProjectIds.isEmpty()) return List.of();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        var byLower = new LinkedHashMap<String, String>();
        for (String name : componentRepository.suggestByProjects(
                visibleProjectIds, q, PageRequest.of(0, limit))) {
            byLower.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        return List.copyOf(byLower.values());
    }

    // ============================================================== issue integration

    /**
     * Resolve a {@code componentId} payload against the issue's OWN project (§3.1).
     * Returns {@code null} when the field is absent. A component of another project
     * (or another tenant, or simply unknown) is a <strong>422</strong> "Unknown
     * component" — never a 404, which would disclose existence.
     *
     * <p>Archived components are NOT rejected here: an issue that already carries one
     * must stay editable. The archived check applies only when the component actually
     * <em>changes</em> — see {@link #requireAssignable}.
     */
    @Transactional(readOnly = true)
    public Component resolveForIssue(Project project, UUID componentId) {
        if (componentId == null) return null;
        return componentRepository.findByIdAndProject(componentId, project)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Unknown component"));
    }

    /** Attaching an ARCHIVED component is a 422 (§5.4); keeping one already attached is fine. */
    public void requireAssignable(Component component) {
        if (component != null && component.isArchived()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Component '" + component.getName() + "' is archived");
        }
    }

    /**
     * The create-time auto-assign rule (§5.1): when the component has
     * {@code autoAssign} <em>and</em> a lead, the new issue is assigned to that lead —
     * but only if the request carried no explicit assignee (the caller checks that) and
     * only if the lead is <strong>still a workspace member</strong>.
     *
     * <p>A stale lead must never fail issue creation, so a non-member lead is skipped
     * <em>silently</em> ({@code null}) and the issue stays unassigned. Never fires on
     * update — changing a component later must not silently reassign someone's work.
     */
    @Transactional(readOnly = true)
    public User autoAssignee(Workspace workspace, Component component) {
        if (component == null || !component.isAutoAssign()) return null;
        var lead = component.getLead();
        if (lead == null) return null;
        return workspaceMemberRepository.existsByWorkspaceAndUser(workspace, lead) ? lead : null;
    }

    // ================================================================= helpers

    /**
     * {@code componentId → issueCount} for a whole catalog. Batched
     * ({@link UsageCounts}) because {@code countsByComponents} binds one JDBC parameter
     * per component: a catalog past PostgreSQL's 65 535-parameter ceiling would fail
     * outright instead of merely costing more.
     */
    private Map<UUID, Integer> usageCounts(List<Component> components) {
        return UsageCounts.countIn(components, issueRepository::countsByComponents);
    }

    private Component requireComponent(Project project, UUID componentId) {
        return componentRepository.findByIdAndProject(componentId, project)
                .orElseThrow(ComponentNotFoundException::new);
    }

    /**
     * The lead must be a member of the component's workspace (§5.4, W9) — a bare
     * {@code findById} would let a caller reference (and enumerate) users of other
     * tenants. Unknown or non-member → 422 "Unknown lead", which discloses nothing.
     */
    private User resolveLead(Workspace workspace, UUID leadId) {
        return userRepository.findById(leadId)
                .filter(u -> workspaceMemberRepository.existsByWorkspaceAndUser(workspace, u))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Unknown lead"));
    }

    /**
     * Turning auto-assign ON must land on a lead who can actually receive issues, i.e.
     * one who is still a workspace member — the same guarantee {@link #resolveLead}
     * gives {@link #create}. A departed lead is a 422 with the SAME wording the
     * "no lead at all" case gets: the operator's fix is identical (pick a lead or turn
     * the switch off), and a distinct "the lead left" message would confirm a specific
     * user's membership state to a caller who may not be entitled to it.
     */
    private void requireLeadStillMember(Workspace workspace, User lead) {
        if (lead != null && !workspaceMemberRepository.existsByWorkspaceAndUser(workspace, lead)) {
            requireAutoAssignHasLead(true, null);
        }
    }

    /** A switch with nothing to switch to is a configuration mistake, not a no-op (§5.4). */
    private void requireAutoAssignHasLead(boolean autoAssign, User lead) {
        if (autoAssign && lead == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Auto-assign needs a lead — pick one or turn auto-assign off");
        }
    }

    /** Managing an archived project's config is frozen, exactly as issue edits are (§5.4). */
    private void requireNotArchived(Project project) {
        if (project.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is archived");
        }
    }

    private ComponentNameConflictException duplicate(Component existing) {
        return new ComponentNameConflictException(existing.isArchived()
                ? "An archived component already uses this name — unarchive or rename it"
                : "A component named '" + existing.getName() + "' already exists in this project");
    }

    /**
     * Name normalization (§4.1, shared by the whole epic): NFC, non-whitespace control
     * AND format characters dropped, whitespace/separator runs collapsed to one space,
     * then stripped — see {@link ClassificationNames}. Casing is preserved for display;
     * uniqueness is case-insensitive per project (the
     * {@code components_project_name_uk} functional index). The length limit is
     * enforced AFTER normalization.
     */
    private String requireValidName(String raw) {
        String name = ClassificationNames.normalize(raw);
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Component name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Component name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    /**
     * Is this integrity violation the component-name unique index losing a race, or a
     * real fault? Translating <em>every</em> {@code DataIntegrityViolationException}
     * into a 409 "already exists" would hide a genuine 500-class bug behind a
     * plausible-looking conflict — the shape that makes an incident hard to diagnose.
     *
     * <p>Only the constraint NAME is logged here: no SQL, no exception message, no user
     * input (the error-hygiene rule the HD-30 security review signed off on).
     *
     * <p><strong>One deliberate exception exists, and it is not a licence.</strong>
     * {@code GlobalExceptionHandler.handleDataIntegrityViolation} — the backstop under every
     * path like this one — logs {@code ex.toString()}. It has to: a {@code 23503} arrives in two
     * opposite directions with opposite remedies ("still referenced from" versus "is not present
     * in"), the two are indistinguishable from SQLSTATE alone, and the client is deliberately
     * told neither, so that message is the only place an operator can learn which. It is safe for
     * the reason this rule exists: every parameter is bound, so the statement logs with
     * placeholders and no user input reaches the line. A call site with its OWN sentence — like
     * this one — knows its direction already and needs none of that.
     */
    private static boolean isNameConflict(DataIntegrityViolationException e) {
        String constraint = constraintNameOf(e);
        boolean nameConflict = constraint != null
                ? NAME_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)
                : e instanceof DuplicateKeyException;
        if (nameConflict) {
            log.debug("Component write lost the unique-name race on constraint [{}]",
                    constraint != null ? constraint : NAME_UNIQUE_CONSTRAINT);
        } else {
            log.warn("Component write failed on an unexpected constraint [{}] — rethrowing",
                    constraint != null ? constraint : "unknown");
        }
        return nameConflict;
    }

    private static String constraintNameOf(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) return cve.getConstraintName();
        }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
