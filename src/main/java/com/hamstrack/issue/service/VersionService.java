package com.hamstrack.issue.service;

import com.hamstrack.admin.scope.ScopeResolver;
import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.ClassificationProperties;
import com.hamstrack.issue.dto.CreateVersionRequest;
import com.hamstrack.issue.dto.IssueVersionRefs;
import com.hamstrack.issue.dto.ReleaseVersionRequest;
import com.hamstrack.issue.dto.UpdateVersionRequest;
import com.hamstrack.issue.dto.VersionRef;
import com.hamstrack.issue.dto.VersionResponse;
import com.hamstrack.issue.dto.VersionUsageResponse;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueVersionLink;
import com.hamstrack.issue.entity.StatusCategory;
import com.hamstrack.issue.entity.Version;
import com.hamstrack.issue.entity.VersionLinkType;
import com.hamstrack.issue.exception.VersionNameConflictException;
import com.hamstrack.issue.exception.VersionNotFoundException;
import com.hamstrack.issue.repository.IssueVersionLinkRepository;
import com.hamstrack.issue.repository.VersionRepository;
import com.hamstrack.project.entity.Project;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Project versions (HD-32, labels-components-versions-proposal §6) — CRUD, the
 * release lifecycle, archive and delete — plus the issue-side fix/affects links and
 * the batched page loading the board/backlog/search rely on.
 *
 * <p><strong>Tenancy (§3.1):</strong> every entry point resolves through
 * {@link WorkspaceAccessService#requireProjectMember} (reads) or
 * {@link ScopeResolver#requireProjectCurator} (writes) — a missing workspace, a
 * missing project and a non-member all yield <strong>404</strong>, never 403. 403 is
 * reserved for a <em>member without the curation role</em>. Version lookups always
 * go through {@code findByIdAndProject}: a foreign id is a 404 on a direct read and a
 * <strong>422</strong> "Unknown version" inside an issue payload (an invalid field
 * value, leaking nothing about the other tenant).
 *
 * <p><strong>Permissions (§3.3):</strong> read = any project member (i.e. a workspace
 * member for whom the project resolves); create/edit/release/un-release/archive/
 * delete = project MANAGER <em>or</em> workspace OWNER/ADMIN.
 *
 * <p>Versions are <em>content</em>, not bound taxonomy: nothing here touches
 * {@code ProjectConfigService} or {@code ProjectConfigResponse} (§3.2).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VersionService {

    private final WorkspaceAccessService workspaceAccess;
    private final ScopeResolver scopeResolver;
    private final VersionRepository versionRepository;
    private final IssueVersionLinkRepository linkRepository;
    private final ClassificationProperties classificationProperties;

    /** Max name length AFTER normalization (matches {@code versions.name VARCHAR(60)}). */
    private static final int MAX_NAME_LENGTH = 60;

    /** The one constraint a version write may legitimately lose to (V10__versions.sql). */
    private static final String NAME_UNIQUE_CONSTRAINT = "versions_project_name_uk";

    /** The one constraint a link RE-POINT may legitimately lose to (V10__versions.sql). */
    private static final String LINK_UNIQUE_CONSTRAINT =
            "issue_versions_issue_id_version_id_link_type_key";

    /**
     * Plan-date bounds. {@code LocalDate} spans ±999 999 999 years; PostgreSQL's
     * {@code date} stops at 5874897-12-31 and a value beyond it fails in the driver, so
     * an unbounded field is a free 500 for any member. These are deliberately far wider
     * than any real release plan — this is an absurdity guard, not a business rule.
     */
    private static final LocalDate MIN_RELEASE_DATE = LocalDate.of(1000, 1, 1);
    private static final LocalDate MAX_RELEASE_DATE = LocalDate.of(9999, 12, 31);

    // ================================================================= catalog CRUD

    /**
     * The project's versions in the Releases-page order (§6.1) — unreleased first,
     * then by planned date (undated last), then by {@code lower(name)}. Progress is
     * ALWAYS included: it is ONE grouped query for the whole list, never one per row,
     * so there is nothing to opt out of.
     */
    @Transactional(readOnly = true)
    public List<VersionResponse> list(User actor, UUID workspaceId, UUID projectId,
                                      boolean includeArchived, boolean includeReleased) {
        var project = workspaceAccess.requireProjectMember(actor, workspaceId, projectId).project();
        var versions = versionRepository.findAllByProject(project, includeArchived, includeReleased);
        return respondAll(versions);
    }

    /** One version — any project member (reads are unrestricted within the project). */
    @Transactional(readOnly = true)
    public VersionResponse get(User actor, UUID workspaceId, UUID projectId, UUID versionId) {
        var project = workspaceAccess.requireProjectMember(actor, workspaceId, projectId).project();
        return respond(requireVersion(project, versionId));
    }

    /**
     * Create — project MANAGER or workspace OWNER/ADMIN (§3.3); frozen while the
     * project is archived. A version is always born <strong>unreleased</strong>;
     * {@code releaseDate} is only the plan.
     */
    @Transactional
    public VersionResponse create(User actor, UUID workspaceId, UUID projectId,
                                  CreateVersionRequest req) {
        var project = scopeResolver.requireProjectCurator(actor, workspaceId, projectId);
        requireNotArchived(project);

        String name = requireValidName(req.name());
        requireValidReleaseDate(req.releaseDate());

        // Pre-check the common case so a duplicate is a clean 409 instead of a
        // constraint violation that has already marked the transaction rollback-only.
        versionRepository.findByProjectAndNameIgnoreCase(project, name)
                .ifPresent(existing -> { throw duplicate(existing); });

        // Catalog ceiling (§3.9), mirroring components exactly. Archived and released
        // versions count: they keep their unique name slot and are still loaded, so
        // excluding them would make the cap bypassable by create → archive → repeat.
        int maxPerProject = classificationProperties.maxVersionsPerProject();
        if (versionRepository.countByProject(project) >= maxPerProject) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "This project already has the maximum of " + maxPerProject
                            + " versions — delete some first");
        }

        var version = new Version();
        version.setWorkspace(project.getWorkspace());
        version.setProject(project);
        version.setName(name);
        version.setDescription(trimToNull(req.description()));
        version.setReleaseDate(req.releaseDate());
        version.setReleased(false);
        try {
            // Flush now so a concurrent-create race surfaces HERE (as a translated
            // DataIntegrityViolationException) instead of at commit, where it would
            // escape as a 500 after the controller already built a 201.
            versionRepository.saveAndFlush(version);
        } catch (DataIntegrityViolationException e) {
            // ONLY the name index means "someone else won the race"; anything else is a
            // genuine fault and must keep its 500 instead of masquerading as a 409.
            if (!isNameConflict(e)) throw e;
            throw new VersionNameConflictException(
                    "A version named '" + name + "' already exists in this project");
        }
        // A brand-new version has no links, so the grouped progress query would return
        // nothing — skip it rather than pay a round-trip for a guaranteed zero.
        return VersionResponse.of(version, 0, 0, 0);
    }

    /**
     * Rename / describe / re-plan — curator only. The release lifecycle deliberately
     * does NOT move through here (see {@link UpdateVersionRequest}).
     */
    @Transactional
    public VersionResponse update(User actor, UUID workspaceId, UUID projectId, UUID versionId,
                                  UpdateVersionRequest req) {
        var project = scopeResolver.requireProjectCurator(actor, workspaceId, projectId);
        requireNotArchived(project);
        var version = requireVersion(project, versionId);

        // ---- all reads first (CLAUDE.md: mutate-then-query double-writes the row) ----
        requireValidReleaseDate(req.releaseDate());
        String newName = null;
        if (req.name() != null) {
            newName = requireValidName(req.name());
            // A pure casing change keeps the same unique slot — allow it.
            if (!newName.equalsIgnoreCase(version.getName())) {
                versionRepository.findByProjectAndNameIgnoreCase(project, newName)
                        .ifPresent(existing -> { throw duplicate(existing); });
            }
        }
        // Captured BEFORE the mutation: the conflict message below must not be read off
        // an entity that is dirty (and, after a failed flush, unusable).
        String attemptedName = newName != null ? newName : version.getName();

        // ---- then mutate ----
        if (newName != null) version.setName(newName);
        if (req.description() != null) version.setDescription(trimToNull(req.description()));
        // releaseDate is a nullable scalar: a date sets it, clearReleaseDate unsets it.
        if (req.releaseDate() != null) {
            version.setReleaseDate(req.releaseDate());
        } else if (req.clearReleaseDate()) {
            version.setReleaseDate(null);
        }

        // The pre-check above is not atomic with the flush, so a concurrent rename into
        // the same slot is arbitrated by versions_project_name_uk. Recover the same way
        // create() does — a 409, never a 500.
        try {
            // Flush so the response carries the audited updatedAt, not the pre-update one.
            return respond(versionRepository.saveAndFlush(version));
        } catch (DataIntegrityViolationException e) {
            if (!isNameConflict(e)) throw e;
            throw new VersionNameConflictException(
                    "A version named '" + attemptedName + "' already exists in this project");
        }
    }

    /**
     * Release (§6.1, §6.4) — curator only, and <strong>reversible</strong> (the
     * story's acceptance criterion; see {@link #unrelease}).
     *
     * <p><strong>Concurrency (trap T1):</strong> the flip is a conditional
     * {@code UPDATE … WHERE released = false} checked by its affected-row count, NOT a
     * read-then-write. Two users clicking "Release" concurrently would both pass a
     * read-then-write guard and BOTH run the destructive
     * {@code moveUnresolvedToVersionId}; with the conditional update exactly one wins
     * and the other gets <strong>409 "already released"</strong>. An
     * idempotent-looking success would hide the double-click, which is precisely the
     * case that must not be hidden.
     *
     * <p>Releasing with unresolved issues is <strong>allowed</strong> — a version with
     * open work is a real, common state and blocking it would push people to lie to
     * the tracker (§6.1). {@code moveUnresolvedToVersionId} optionally re-points the
     * FIX links of every non-DONE issue to another unreleased, non-archived version of
     * the same project; DONE issues stay on the released version.
     *
     * <p>{@code release_date} defaults to the server's UTC today when the request
     * carries none and none is stored (§6.4).
     */
    @Transactional
    public VersionResponse release(User actor, UUID workspaceId, UUID projectId, UUID versionId,
                                   ReleaseVersionRequest req) {
        var project = scopeResolver.requireProjectCurator(actor, workspaceId, projectId);
        requireNotArchived(project);
        var version = requireVersion(project, versionId);

        // ---- all reads + validation first, before anything is written ----
        Version moveTarget = null;
        if (req != null && req.moveUnresolvedToVersionId() != null) {
            moveTarget = requireMoveTarget(project, version, req.moveUnresolvedToVersionId());
        }
        if (req != null) requireValidReleaseDate(req.releaseDate());
        LocalDate releaseDate = req != null && req.releaseDate() != null ? req.releaseDate()
                : version.getReleaseDate() != null ? version.getReleaseDate()
                : LocalDate.now(ZoneOffset.UTC);

        // ---- the conditional UPDATE is the arbiter (T1) ----
        // UTC, not OffsetDateTime.now(): every other timestamp this API serializes is
        // UTC, and stamping releasedAt in the JVM's default zone made one field of one
        // response carry the server's local offset.
        if (versionRepository.markReleased(versionId, project.getId(),
                OffsetDateTime.now(ZoneOffset.UTC), releaseDate) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This version is already released");
        }
        // markReleased flushed then CLEARED the persistence context (it had to: the
        // bulk UPDATE leaves the managed copy stale). `project`/`version`/`moveTarget`
        // are detached from here on — they are only ever used as query parameters
        // below, which bind their identifiers, so that is safe.

        if (moveTarget != null) {
            // Trap T2: re-pointing can collide with a FIX link the issue already has.
            // DELETE the colliding source rows, FLUSH, then re-point the rest — within
            // one flush Hibernate orders INSERT/UPDATE before DELETE, which is the
            // UNIQUE-violation trap that already bit label merge and the admin set
            // editors.
            final var target = moveTarget;
            remapping(() -> {
                linkRepository.deleteUnresolvedFixLinksCollidingWith(
                        version, target, VersionLinkType.FIX, StatusCategory.DONE);
                linkRepository.flush();
                linkRepository.repointUnresolvedFixLinks(
                        version, target, VersionLinkType.FIX, StatusCategory.DONE);
            });
        }
        return respond(requireVersion(project, versionId));
    }

    /**
     * Un-release (§6.1) — clears {@code released}/{@code releasedAt} and
     * <strong>preserves {@code releaseDate}</strong> (it stays the plan), so the
     * round-trip loses no data. Conditional for the same reason as {@link #release}:
     * un-releasing something that isn't released is a <strong>409</strong>, not a
     * silent no-op.
     */
    @Transactional
    public VersionResponse unrelease(User actor, UUID workspaceId, UUID projectId, UUID versionId) {
        var project = scopeResolver.requireProjectCurator(actor, workspaceId, projectId);
        requireNotArchived(project);
        requireVersion(project, versionId);   // scope it to the project before mutating

        if (versionRepository.markUnreleased(versionId, project.getId()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This version is not released");
        }
        return respond(requireVersion(project, versionId));
    }

    /**
     * Archive (§6.4): always allowed, even in use, and orthogonal to
     * {@code released} — archiving old shipped releases is how the Releases list
     * stays short. The version leaves every picker and the default list; existing
     * links are preserved and render dimmed.
     */
    @Transactional
    public VersionResponse archive(User actor, UUID workspaceId, UUID projectId, UUID versionId) {
        return setArchived(actor, workspaceId, projectId, versionId, true);
    }

    /** Unarchive — the row already owns its unique name slot, so this can't conflict. */
    @Transactional
    public VersionResponse unarchive(User actor, UUID workspaceId, UUID projectId, UUID versionId) {
        return setArchived(actor, workspaceId, projectId, versionId, false);
    }

    private VersionResponse setArchived(User actor, UUID workspaceId, UUID projectId,
                                        UUID versionId, boolean archived) {
        var project = scopeResolver.requireProjectCurator(actor, workspaceId, projectId);
        requireNotArchived(project);
        var version = requireVersion(project, versionId);
        version.setArchivedAt(archived ? Instant.now() : null);
        return respond(versionRepository.saveAndFlush(version));
    }

    /**
     * Delete (§6.4) — curator only. Linked to issues → <strong>409</strong> unless
     * {@code force} (drop every link) <em>or</em> {@code remapToId} (re-point every
     * link, <strong>both roles</strong>, to another version of the same project).
     * Remap is offered here — unlike components — because a version does carry
     * positional meaning: "the work moved to the next release" is the normal reason to
     * delete one. When both are supplied, {@code remapToId} wins: it is the strictly
     * more specific, non-destructive intent.
     *
     * <p>Deleting a <em>released</em> version is allowed with the same rules: it is a
     * curation decision, and {@code force}/{@code remapToId} already require intent.
     *
     * <p>No per-issue history rows — a delete can touch thousands of issues and one
     * request must stay bounded, the same reasoning as label merge (§4.4) and
     * component force-delete (§5.4).
     *
     * <p><strong>Trap T3:</strong> the remap is the UNIQUE-ordering trap again, on
     * {@code UNIQUE (issue_id, version_id, link_type)} — DELETE the colliding source
     * rows, {@code flush()}, then re-point the survivors.
     */
    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, UUID versionId,
                       boolean force, UUID remapToId) {
        var project = scopeResolver.requireProjectCurator(actor, workspaceId, projectId);
        requireNotArchived(project);
        var version = requireVersion(project, versionId);

        // Validate the remap target BEFORE anything is written (a bad target must not
        // leave the version half-detached).
        Version remapTo = remapToId != null ? requireRemapTarget(project, version, remapToId) : null;

        long inUse = linkRepository.countByVersion(version);
        if (inUse > 0 && remapTo == null && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This version is linked to " + inUse
                            + " issue(s) — archive it, remap them to another version, or delete with force");
        }
        if (inUse > 0) {
            if (remapTo != null) {
                final var target = remapTo;
                remapping(() -> {
                    linkRepository.deleteSourceRowsAlreadyOnTarget(version, target);
                    linkRepository.flush();                  // ← the non-negotiable flush (T3)
                    linkRepository.repointToTarget(version, target);
                });
            } else {
                linkRepository.deleteAllByVersion(version);
            }
            linkRepository.flush();
        }
        versionRepository.delete(version);
    }

    /**
     * Usage for one version — any project member (reads are unrestricted). All three
     * counters come from the SAME grouped query the list uses;
     * {@code unresolvedFixIssueCount} is what
     * {@code moveUnresolvedToVersionId} would move.
     */
    @Transactional(readOnly = true)
    public VersionUsageResponse usage(User actor, UUID workspaceId, UUID projectId, UUID versionId) {
        var project = workspaceAccess.requireProjectMember(actor, workspaceId, projectId).project();
        var version = requireVersion(project, versionId);
        var p = progressOf(version);
        return new VersionUsageResponse(p.fixCount(), p.affectsCount(), p.fixCount() - p.fixDoneCount());
    }

    // ================================================================= HQL support

    /**
     * Bounded (≤ {@code limit}) typeahead over the non-archived version names of a set
     * of the actor's VISIBLE projects — backs
     * {@code /search/suggest?field=fixVersion|affectsVersion} when the {@code /schema}
     * picklist is truncated (§3.5).
     *
     * <p><strong>Contract:</strong> the caller has already verified workspace
     * membership and passes a SUBSET of {@code SearchScope.visibleProjectIds} — never
     * a wider set. Today {@code SearchService} narrows it further still, to
     * {@code ResolutionContext.Capabilities.releaseProjectIds} (the visible projects
     * with {@code releases} on, HD-107 §9.1). Narrowing is always safe; widening this
     * argument would let a name resolve through a project the actor cannot see, and
     * this method does NOT re-check visibility itself — so nothing may ever expand it.
     *
     * <p>Because a subset is expected, EMPTY is a NORMAL input (e.g. no visible project
     * has {@code releases} on), not an anomaly — which makes the short-circuit on the
     * first line of the body load-bearing rather than defensive: an empty IN list is
     * invalid in JPQL/Hibernate, and "no projects" must resolve to "no suggestions",
     * never to "no project filter".
     *
     * <p>Names are de-duplicated case-insensitively: two projects may each ship a
     * "2.4.0", and the suggestion list is about names, not rows.
     */
    @Transactional(readOnly = true)
    public List<String> suggestNames(Collection<UUID> visibleProjectIds, String query, int limit) {
        // An empty IN list is invalid in JPQL/Hibernate — short-circuit instead.
        if (visibleProjectIds == null || visibleProjectIds.isEmpty()) return List.of();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        var byLower = new LinkedHashMap<String, String>();
        for (String name : versionRepository.suggestByProjects(
                visibleProjectIds, q, PageRequest.of(0, limit))) {
            byLower.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        return List.copyOf(byLower.values());
    }

    // ============================================================== issue integration

    /**
     * Resolve a {@code fixVersionIds} / {@code affectsVersionIds} payload against the
     * issue's OWN project (§3.1). Returns {@code null} when the field is absent
     * (leave that role's set unchanged).
     *
     * <p>Rejects with <strong>422</strong>: more than
     * {@code app.classification.max-version-links-per-issue} ids
     * (<strong>per link type</strong> — the two roles have independent budgets), and
     * any id that is unknown or belongs to another project ("Unknown version").
     * Archived versions are NOT rejected here — an issue that already carries one must
     * stay editable; the archived check applies only to <em>newly added</em> links, in
     * {@link #diffVersions} / {@link #attachAll}.
     */
    @Transactional(readOnly = true)
    public List<Version> resolveForIssue(Project project, List<UUID> versionIds,
                                         VersionLinkType linkType) {
        if (versionIds == null) return null;
        var distinct = new LinkedHashSet<>(versionIds);
        distinct.remove(null);
        if (distinct.isEmpty()) return List.of();

        int max = classificationProperties.maxVersionLinksPerIssue();
        if (distinct.size() > max) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "At most " + max + " " + roleLabel(linkType) + " per issue");
        }
        var resolved = versionRepository.findAllByIdInAndProject(distinct, project);
        if (resolved.size() != distinct.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown version");
        }
        return resolved;
    }

    /**
     * Attach a freshly resolved set to a NEW issue (create path). Every version here
     * is an addition, so an archived one is a 422. No history rows — create-time
     * values never write history (consistent with labels and custom fields).
     */
    @Transactional
    public void attachAll(Issue issue, List<Version> versions, VersionLinkType linkType) {
        if (versions == null || versions.isEmpty()) return;
        var rows = new ArrayList<IssueVersionLink>(versions.size());
        for (var version : versions) {
            requireNotArchived(version);
            rows.add(newRow(issue, version, linkType));
        }
        linkRepository.saveAll(rows);
    }

    /**
     * The full-replacement diff between an issue's current links <em>of one role</em>
     * and the desired set. <strong>Pure</strong> — computes only, so the caller can
     * run it with all the other reads BEFORE mutating the issue (the {@code @Version}
     * double-bump rule).
     *
     * @param currentRows ALL of the issue's link rows; filtered to {@code linkType}
     *                    here, so one load serves both roles
     */
    public VersionChange diffVersions(List<IssueVersionLink> currentRows, List<Version> desired,
                                      VersionLinkType linkType) {
        var desiredById = new LinkedHashMap<UUID, Version>();
        for (var v : desired) desiredById.put(v.getId(), v);

        var currentById = new LinkedHashMap<UUID, Version>();
        var removed = new ArrayList<IssueVersionLink>();
        for (var row : currentRows) {
            if (row.getLinkType() != linkType) continue;
            currentById.put(row.getVersion().getId(), row.getVersion());
            if (!desiredById.containsKey(row.getVersion().getId())) removed.add(row);
        }
        var added = new ArrayList<Version>();
        for (var entry : desiredById.entrySet()) {
            if (!currentById.containsKey(entry.getKey())) added.add(entry.getValue());
        }
        // Linking an ARCHIVED version is a 422; keeping one already linked is fine.
        for (var v : added) requireNotArchived(v);

        boolean changed = !added.isEmpty() || !removed.isEmpty();
        return new VersionChange(linkType, added, removed, changed,
                displayNames(currentById.values()), displayNames(desiredById.values()));
    }

    /**
     * Persist the fix and/or affects {@link VersionChange}s of one PATCH. Both roles
     * are applied together — all deletes first, then {@code flush()}, then all inserts
     * — so the documented "INSERT ordered before DELETE ⇒ UNIQUE violation" trap stays
     * closed even when one role adds a version the other role just dropped.
     */
    @Transactional
    public void applyVersionChanges(Issue issue, VersionChange... changes) {
        var removed = new ArrayList<IssueVersionLink>();
        var added = new ArrayList<IssueVersionLink>();
        for (var change : changes) {
            if (change == null || !change.changed()) continue;
            removed.addAll(change.removed());
            for (var version : change.added()) added.add(newRow(issue, version, change.linkType()));
        }
        if (!removed.isEmpty()) {
            linkRepository.deleteAll(removed);
            linkRepository.flush();
        }
        if (!added.isEmpty()) {
            linkRepository.saveAll(added);
        }
    }

    /**
     * The ONLY place an {@code issue_versions} row is built (§3.8). Stamps the row
     * with the ISSUE's workspace, which the composite FKs
     * {@code (issue_id, workspace_id) → issues} and
     * {@code (version_id, workspace_id) → versions} then verify against both parents:
     * if the version came from another tenant the INSERT fails outright instead of
     * quietly creating a cross-tenant link. Every write path goes through here so the
     * stamp can't be forgotten.
     */
    private IssueVersionLink newRow(Issue issue, Version version, VersionLinkType linkType) {
        var row = new IssueVersionLink();
        row.setIssue(issue);
        row.setVersion(version);
        row.setWorkspace(issue.getWorkspace());
        row.setLinkType(linkType);
        return row;
    }

    /** The link rows of one issue (managed, version joined) — for the update diff. */
    @Transactional(readOnly = true)
    public List<IssueVersionLink> linksOf(Issue issue) {
        return linkRepository.findAllByIssue(issue);
    }

    /** Fix + affects versions of ONE issue, each ordered by {@code lower(name)}. */
    @Transactional(readOnly = true)
    public IssueVersionRefs versionsForIssue(Issue issue) {
        return split(linkRepository.findAllByIssue(issue));
    }

    /**
     * Fix + affects versions for a WHOLE page of issues in one query, keyed by issue
     * id (§3.7 — the "no N+1" requirement; mirrors
     * {@code LabelService.labelsByIssue}). BOTH roles come back in the same query and
     * are split here, so a board page of 100 issues stays a constant number of
     * queries.
     */
    @Transactional(readOnly = true)
    public Map<UUID, IssueVersionRefs> versionsByIssue(Collection<Issue> issues) {
        if (issues.isEmpty()) return Map.of();
        var rowsByIssue = new HashMap<UUID, List<IssueVersionLink>>();
        for (var row : linkRepository.findAllByIssueIn(issues)) {
            rowsByIssue.computeIfAbsent(row.getIssue().getId(), k -> new ArrayList<>()).add(row);
        }
        var byIssue = new HashMap<UUID, IssueVersionRefs>(rowsByIssue.size());
        rowsByIssue.forEach((issueId, rows) -> byIssue.put(issueId, split(rows)));
        return byIssue;
    }

    /**
     * The result of a full-replacement diff for ONE role.
     *
     * @param linkType the role being replaced
     * @param added    versions to link (already checked non-archived)
     * @param removed  the link rows to delete
     * @param changed  false when the requested set equals the current one (no-op: no
     *                 history row)
     * @param oldNames comma-joined display names before, or null when empty
     * @param newNames comma-joined display names after, or null when empty
     */
    public record VersionChange(VersionLinkType linkType, List<Version> added,
                                List<IssueVersionLink> removed, boolean changed,
                                String oldNames, String newNames) {}

    // ================================================================= helpers

    private IssueVersionRefs split(List<IssueVersionLink> rows) {
        var fix = new ArrayList<Version>();
        var affects = new ArrayList<Version>();
        for (var row : rows) {
            (row.getLinkType() == VersionLinkType.FIX ? fix : affects).add(row.getVersion());
        }
        return new IssueVersionRefs(toRefs(fix), toRefs(affects));
    }

    private List<VersionRef> toRefs(List<Version> versions) {
        return versions.stream()
                .sorted(Comparator.comparing(v -> v.getName().toLowerCase(Locale.ROOT)))
                .map(VersionRef::of)
                .toList();
    }

    private static String displayNames(Collection<Version> versions) {
        if (versions.isEmpty()) return null;
        return versions.stream()
                .map(Version::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
    }

    /** Per-version FIX/AFFECTS roll-up (§6.1). */
    private record Progress(int fixCount, int fixDoneCount, int affectsCount) {
        static final Progress EMPTY = new Progress(0, 0, 0);
    }

    private VersionResponse respond(Version version) {
        var p = progressOf(version);
        return VersionResponse.of(version, p.fixCount(), p.fixDoneCount(), p.affectsCount());
    }

    private List<VersionResponse> respondAll(List<Version> versions) {
        if (versions.isEmpty()) return List.of();
        var progress = progress(versions);
        return versions.stream()
                .map(v -> {
                    var p = progress.getOrDefault(v.getId(), Progress.EMPTY);
                    return VersionResponse.of(v, p.fixCount(), p.fixDoneCount(), p.affectsCount());
                })
                .toList();
    }

    private Progress progressOf(Version version) {
        return progress(List.of(version)).getOrDefault(version.getId(), Progress.EMPTY);
    }

    /**
     * {@code versionId → Progress} for a whole list in ONE grouped query (§6.1) —
     * never one query per version. Batched ({@link UsageCounts#rowsIn}) because
     * {@code progressByVersions} binds one JDBC parameter per version, and
     * {@code app.classification.max-versions-per-project} is operator-tunable up to
     * 100 000 — well past PostgreSQL's 65 535-parameter ceiling. The cap bounds the
     * catalog; batching keeps a raised cap degrading into more round-trips instead of a
     * driver-level failure. Both are needed.
     */
    private Map<UUID, Progress> progress(List<Version> versions) {
        var byVersion = new HashMap<UUID, Progress>(versions.size());
        var rows = UsageCounts.rowsIn(versions,
                batch -> linkRepository.progressByVersions(batch, StatusCategory.DONE));
        for (var row : rows) {
            var id = (UUID) row[0];
            var linkType = (VersionLinkType) row[1];
            int count = ((Number) row[2]).intValue();
            int done = row[3] == null ? 0 : ((Number) row[3]).intValue();
            var current = byVersion.getOrDefault(id, Progress.EMPTY);
            byVersion.put(id, linkType == VersionLinkType.FIX
                    ? new Progress(current.fixCount() + count, current.fixDoneCount() + done,
                            current.affectsCount())
                    : new Progress(current.fixCount(), current.fixDoneCount(),
                            current.affectsCount() + count));
        }
        return byVersion;
    }

    /**
     * Run a link re-point (release-time move or delete-with-remap) and turn a LOST RACE
     * into a <strong>409</strong> instead of a 500. The DELETE → {@code flush()} →
     * re-point sequence closes trap T2/T3 against the rows it can see, but it is not
     * atomic against a concurrent {@code PATCH /issues/{n}} that adds the target version
     * to one of those issues in between: that re-creates the collision and the re-point
     * violates {@code issue_versions_issue_id_version_id_link_type_key}. That is a
     * retryable conflict, not a fault — anything else keeps its 500.
     *
     * <p>The transaction is already rollback-only by then, so the enclosing operation
     * (the release, or the delete) is undone with it: the 409 means "nothing happened,
     * try again", never a half-applied remap.
     */
    private void remapping(Runnable remap) {
        try {
            remap.run();
        } catch (DataIntegrityViolationException e) {
            if (!isLinkConflict(e)) throw e;
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another change added this version to one of the same issues — retry");
        }
    }

    /**
     * Reject a {@link LocalDate} PostgreSQL's {@code date} type cannot hold (§6.3).
     * {@code LocalDate} happily parses {@code +999999999-12-31}, which then reaches
     * {@code saveAndFlush} and comes back as a driver-level failure — a free 500 for any
     * member. A plan date outside {@link #MIN_RELEASE_DATE}…{@link #MAX_RELEASE_DATE} is
     * a nonsensical field value in an otherwise legitimate request, i.e. a
     * <strong>422</strong>. Null (absent) is always fine.
     */
    private static void requireValidReleaseDate(LocalDate date) {
        if (date == null) return;
        if (date.isBefore(MIN_RELEASE_DATE) || date.isAfter(MAX_RELEASE_DATE)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Release date must be between " + MIN_RELEASE_DATE + " and " + MAX_RELEASE_DATE);
        }
    }

    private Version requireVersion(Project project, UUID versionId) {
        return versionRepository.findByIdAndProject(versionId, project)
                .orElseThrow(VersionNotFoundException::new);
    }

    /**
     * The {@code moveUnresolvedToVersionId} target (§6.4, trap T5): a version of the
     * <strong>same project</strong>, not the version being released, not archived and
     * <strong>not released</strong> — moving open work onto an already-shipped release
     * is never what the operator meant. Anything else is a <strong>422</strong>: it is
     * an invalid field value inside a request the caller is entitled to make, and a
     * foreign id discloses nothing.
     */
    private Version requireMoveTarget(Project project, Version source, UUID targetId) {
        if (source.getId().equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Unresolved issues can't be moved to the version being released");
        }
        var target = versionRepository.findByIdAndProject(targetId, project)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Unknown version"));
        if (target.isArchived()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Version '" + target.getName() + "' is archived");
        }
        if (target.isReleased()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Version '" + target.getName() + "' is already released — pick an unreleased one");
        }
        return target;
    }

    /**
     * The {@code remapToId} target of a delete (§6.4, trap T5): a version of the same
     * project, not the one being deleted, not archived. A <em>released</em> target IS
     * allowed here (unlike the release-time move) — back-porting the links of a
     * deleted version onto a shipped release is legitimate bookkeeping, exactly as
     * linking a released version as fix version is (§6.4).
     */
    private Version requireRemapTarget(Project project, Version source, UUID targetId) {
        if (source.getId().equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A version can't be remapped to itself");
        }
        var target = versionRepository.findByIdAndProject(targetId, project)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Unknown version"));
        if (target.isArchived()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Version '" + target.getName() + "' is archived");
        }
        return target;
    }

    /** Linking an ARCHIVED version is a 422 (§6.4); keeping one already linked is fine. */
    private void requireNotArchived(Version version) {
        if (version.isArchived()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Version '" + version.getName() + "' is archived");
        }
    }

    /** Managing an archived project's release plan is frozen, exactly as issue edits are (§6.4). */
    private void requireNotArchived(Project project) {
        if (project.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is archived");
        }
    }

    private static String roleLabel(VersionLinkType linkType) {
        return linkType == VersionLinkType.FIX ? "fix versions" : "affects versions";
    }

    private VersionNameConflictException duplicate(Version existing) {
        return new VersionNameConflictException(existing.isArchived()
                ? "An archived version already uses this name — unarchive or rename it"
                : "A version named '" + existing.getName() + "' already exists in this project");
    }

    /**
     * Name normalization (§4.1, shared by the whole epic): NFC, non-whitespace control
     * AND format characters dropped, whitespace/separator runs collapsed to one space,
     * then stripped — see {@link ClassificationNames}. Casing is preserved for
     * display; uniqueness is case-insensitive per project (the
     * {@code versions_project_name_uk} functional index). The length limit is enforced
     * AFTER normalization.
     */
    private String requireValidName(String raw) {
        String name = ClassificationNames.normalize(raw);
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Version name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Version name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    /**
     * Is this integrity violation the version-name unique index losing a race, or a
     * real fault? Translating <em>every</em> {@code DataIntegrityViolationException}
     * into a 409 "already exists" would hide a genuine 500-class bug behind a
     * plausible-looking conflict — the shape that makes an incident hard to diagnose.
     *
     * <p>Only the constraint NAME is logged: no SQL, no exception message, no user
     * input (the error-hygiene rule the HD-30 security review signed off on).
     */
    private static boolean isNameConflict(DataIntegrityViolationException e) {
        String constraint = constraintNameOf(e);
        boolean nameConflict = constraint != null
                ? NAME_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)
                : e instanceof DuplicateKeyException;
        // Never print the constraint we ASSUMED — during an incident a log line naming
        // versions_project_name_uk when nothing was observed reads as evidence.
        if (nameConflict) {
            log.debug("Version write lost the unique-name race on constraint [{}]",
                    constraint != null ? constraint : "unknown");
        } else {
            log.warn("Version write failed on an unexpected constraint [{}] — rethrowing",
                    constraint != null ? constraint : "unknown");
        }
        return nameConflict;
    }

    /**
     * Is this integrity violation the {@code issue_versions} unique key losing a race
     * with a concurrent issue edit (→ 409, see {@link #remapping}), or a real fault
     * (→ keep the 500)? Same hygiene as {@link #isNameConflict}: constraint name only.
     */
    private static boolean isLinkConflict(DataIntegrityViolationException e) {
        String constraint = constraintNameOf(e);
        boolean linkConflict = constraint != null
                ? LINK_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)
                : e instanceof DuplicateKeyException;
        if (linkConflict) {
            log.debug("Version link re-point lost a race on constraint [{}]",
                    constraint != null ? constraint : "unknown");
        } else {
            log.warn("Version link re-point failed on an unexpected constraint [{}] — rethrowing",
                    constraint != null ? constraint : "unknown");
        }
        return linkConflict;
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
