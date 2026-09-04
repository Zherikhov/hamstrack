package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.ClassificationProperties;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.util.ColorFormat;
import com.hamstrack.issue.dto.CreateLabelRequest;
import com.hamstrack.issue.dto.LabelRef;
import com.hamstrack.issue.dto.LabelResponse;
import com.hamstrack.issue.dto.LabelUsageResponse;
import com.hamstrack.issue.dto.MergeLabelsRequest;
import com.hamstrack.issue.dto.MergeLabelsResponse;
import com.hamstrack.issue.dto.UpdateLabelRequest;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueLabel;
import com.hamstrack.issue.entity.Label;
import com.hamstrack.issue.exception.LabelNameConflictException;
import com.hamstrack.issue.exception.LabelNotFoundException;
import com.hamstrack.issue.repository.IssueLabelRepository;
import com.hamstrack.issue.repository.LabelRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import com.hamstrack.workspace.service.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
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
 * Workspace labels (HD-30, labels-components-versions-proposal §4) — CRUD, archive,
 * merge, delete — plus the issue-side attach/detach and batched page loading the
 * board/backlog/search rely on.
 *
 * <p><strong>Tenancy (§3.1):</strong> every entry point resolves through
 * {@link WorkspaceAccessService#requireMember} — a missing workspace and a
 * non-member both yield <strong>404</strong>, never 403. 403 is reserved for a
 * <em>member without the permission</em>. Label lookups always go through
 * {@code findByIdAndWorkspace}: a foreign id is a 404 on a direct read and a
 * <strong>422</strong> "Unknown label" inside an issue payload (an invalid field
 * value, leaking nothing about the other tenant).
 *
 * <p><strong>Permissions (§3.3, HD-126 S3):</strong> reads are open to every workspace
 * member; create = {@code label.create}; rename/recolor/describe =
 * {@code label.manage} <em>qualified by ownership</em> ({@link #requireEditor});
 * archive/unarchive/merge/delete = {@code label.manage} <em>unrestricted</em>
 * ({@link #requireCurator}). One catalog key, two arities — and the built-in workspace
 * Member's own-only grant is what preserves "may rename the label I made, may not
 * archive it" without splitting the catalog.
 *
 * <p>Labels are <em>content</em>, not bound taxonomy: nothing here touches
 * {@code ProjectConfigService} or {@code ProjectConfigResponse} (§3.2).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LabelService {

    private final WorkspaceAccessService workspaceAccess;
    private final LabelRepository labelRepository;
    private final IssueLabelRepository issueLabelRepository;
    private final LabelConflictLookup conflictLookup;
    private final ClassificationProperties classificationProperties;

    /** Max name length AFTER normalization (matches {@code labels.name VARCHAR(60)}). */
    private static final int MAX_NAME_LENGTH = 60;

    /** The one constraint a label write may legitimately lose to (V8__labels.sql). */
    private static final String NAME_UNIQUE_CONSTRAINT = "labels_workspace_name_uk";


    /**
     * The 8 auto-assign swatches (§4.1), built from DESIGN.md "Beacon" tokens. A label
     * created without a color gets {@code palette[floorMod(hash(lower(name)), 8)]} —
     * deterministic, so the same name always looks the same and a list of
     * auto-colored labels still reads as intentional.
     */
    private static final List<String> PALETTE = List.of(
            "#0EA5A4",  // brand teal
            "#F79009",  // amber
            "#667085",  // slate
            "#F04438",  // error
            "#12B981",  // success
            "#7C6CF5",  // violet
            "#3B5BFD",  // blue
            "#EAB308"); // yellow

    // ================================================================= catalog CRUD

    /**
     * The workspace's labels, ordered by {@code lower(name)}. {@code withUsage} adds
     * {@code issueCount} via ONE grouped query (never per label).
     */
    @Transactional(readOnly = true)
    public List<LabelResponse> list(User actor, UUID workspaceId, boolean includeArchived, boolean withUsage) {
        var ws = workspaceAccess.requireMember(actor, workspaceId).workspace();
        var labels = labelRepository.findAllByWorkspace(ws, includeArchived);
        if (!withUsage || labels.isEmpty()) {
            return labels.stream().map(l -> LabelResponse.of(l, null)).toList();
        }
        var counts = usageCounts(labels);
        return labels.stream()
                .map(l -> LabelResponse.of(l, counts.getOrDefault(l.getId(), 0)))
                .toList();
    }

    /**
     * <strong>Permission: {@code label.create}</strong> (HD-126 S3, §10.1). Δ-free — the
     * built-in workspace Member holds it, so label creation stays self-serve (§3.3 — the
     * flagged design assumption); it is its own catalog entry so that a workspace which
     * wants a curated tag vocabulary can now say so.
     *
     * <p>Because creation is self-serve, the catalog is bounded by
     * {@code app.classification.max-labels-per-workspace} (422 when full): without it
     * a single member could grow an unbounded workspace catalog, which every
     * {@code /search}, {@code /schema} and picker load then has to resolve.
     */
    @Transactional
    public LabelResponse create(User actor, UUID workspaceId, CreateLabelRequest req) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        ctx.permissions().require(Permission.LABEL_CREATE);
        var ws = ctx.workspace();

        String name = requireValidName(req.name());
        String color = req.color() != null ? requireValidColor(req.color()) : colorForName(name);

        // Pre-check the common case so the picker gets `existingId` without a race.
        labelRepository.findByWorkspaceAndNameIgnoreCase(ws, name)
                .ifPresent(existing -> { throw duplicate(existing); });

        int maxPerWorkspace = classificationProperties.maxLabelsPerWorkspace();
        if (labelRepository.countByWorkspace(ws) >= maxPerWorkspace) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "This workspace already has the maximum of " + maxPerWorkspace
                            + " labels — archive or delete some first");
        }

        var label = new Label();
        label.setWorkspace(ws);
        label.setName(name);
        label.setColor(color);
        label.setDescription(trimToNull(req.description()));
        label.setCreatedBy(actor);
        try {
            // Flush now so a concurrent-create race surfaces HERE (as a translated
            // DataIntegrityViolationException) instead of at commit, where it would
            // escape as a 500 after the controller already built a 201.
            labelRepository.saveAndFlush(label);
        } catch (DataIntegrityViolationException e) {
            // ONLY the name index means "someone else won the race"; anything else is a
            // genuine fault and must keep its 500 instead of masquerading as a 409.
            if (!isNameConflict(e)) throw e;
            // The unique index arbitrated and we lost. This transaction is aborted, so
            // the "who won?" read must run on a fresh one (see LabelConflictLookup).
            var winnerId = conflictLookup.findWinnerId(workspaceId, name).orElse(null);
            throw new LabelNameConflictException(
                    "A label named '" + name + "' already exists in this workspace", winnerId);
        }
        return LabelResponse.of(label, null);
    }

    /** Rename / recolor / describe — OWNER/ADMIN, or the label's creator (§3.3). */
    @Transactional
    public LabelResponse update(User actor, UUID workspaceId, UUID labelId, UpdateLabelRequest req) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        var label = requireLabel(ctx.workspace(), labelId);
        requireEditor(ctx, actor, label);

        // ---- all reads first (CLAUDE.md: mutate-then-query double-writes the row) ----
        String newName = null;
        if (req.name() != null) {
            newName = requireValidName(req.name());
            // A pure casing change keeps the same unique slot — allow it.
            if (!newName.equalsIgnoreCase(label.getName())) {
                labelRepository.findByWorkspaceAndNameIgnoreCase(ctx.workspace(), newName)
                        .ifPresent(existing -> { throw duplicate(existing); });
            }
        }
        String newColor = req.color() != null ? requireValidColor(req.color()) : null;
        // Captured BEFORE the mutation: the conflict message/lookup below must not be
        // read off an entity that is dirty (and, after a failed flush, unusable).
        String attemptedName = newName != null ? newName : label.getName();

        // ---- then mutate ----
        if (newName != null) label.setName(newName);
        if (newColor != null) label.setColor(newColor);
        if (req.description() != null) label.setDescription(trimToNull(req.description()));

        // The pre-check above is not atomic with the flush, so a concurrent rename
        // into the same slot is arbitrated by labels_workspace_name_uk. Recover the
        // same way create() does — a 409 with `existingId`, never a 500.
        try {
            // Flush so the response carries the audited updatedAt, not the pre-update one.
            return LabelResponse.of(labelRepository.saveAndFlush(label), null);
        } catch (DataIntegrityViolationException e) {
            if (!isNameConflict(e)) throw e;
            // This transaction is aborted, so the "who won?" read runs on a fresh one.
            var winnerId = conflictLookup.findWinnerId(workspaceId, attemptedName).orElse(null);
            throw new LabelNameConflictException(
                    "A label named '" + attemptedName + "' already exists in this workspace", winnerId);
        }
    }

    /**
     * Archive (§4.4): always allowed, even in use. The label leaves every picker and
     * the default settings list; existing attachments are preserved and render dimmed.
     * This is the recommended safe alternative to delete.
     */
    @Transactional
    public LabelResponse archive(User actor, UUID workspaceId, UUID labelId) {
        return setArchived(actor, workspaceId, labelId, true);
    }

    /** Unarchive — the row already owns its unique name slot, so this can't conflict. */
    @Transactional
    public LabelResponse unarchive(User actor, UUID workspaceId, UUID labelId) {
        return setArchived(actor, workspaceId, labelId, false);
    }

    private LabelResponse setArchived(User actor, UUID workspaceId, UUID labelId, boolean archived) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        requireCurator(ctx);
        var label = requireLabel(ctx.workspace(), labelId);
        label.setArchivedAt(archived ? Instant.now() : null);
        return LabelResponse.of(labelRepository.saveAndFlush(label), null);
    }

    /**
     * Merge {@code sourceIds} into {@code targetId} (§4.4) — OWNER/ADMIN only.
     *
     * <p><strong>Execution order matters.</strong> First delete the source
     * attachments whose issue already carries the target, <strong>then
     * {@code flush()}</strong>, then re-point the survivors. Skipping the flush hits
     * the documented "Hibernate orders INSERT/UPDATE before DELETE within one flush"
     * trap and the re-point collides with {@code UNIQUE (issue_id, label_id)} — the
     * same bug that bit every admin set editor.
     *
     * <p>No per-issue history rows: a merge can touch thousands of issues and one
     * request must stay bounded. The response + the target's bumped {@code updatedAt}
     * are the record (§4.4, open question 3).
     *
     * <p><strong>Archived projects are NOT excluded</strong> (decision D1, HD-30 fix
     * round 1). {@code requireNotArchived(project)} guards <em>authoring</em> — a
     * member editing an issue inside a frozen project. Merge is the opposite: a
     * workspace OWNER/ADMIN curating the workspace's own vocabulary. Honouring the
     * per-project freeze here would mean a single archived project can veto a
     * workspace-wide rename/merge forever (the duplicate label could never be
     * collapsed while any archived project still carries it), and would leave the
     * catalog permanently inconsistent — the merged-away label rows would survive with
     * no catalog row to render, since the {@code labels} row is deleted regardless.
     * Same reasoning for {@code DELETE ?force=true}. The freeze protects issue
     * content; the label catalog is workspace-level metadata above it.
     */
    @Transactional
    public MergeLabelsResponse merge(User actor, UUID workspaceId, UUID targetId, MergeLabelsRequest req) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        requireCurator(ctx);
        var ws = ctx.workspace();

        var target = requireLabel(ws, targetId);

        var sourceIds = new LinkedHashSet<>(req.sourceIds());
        if (sourceIds.contains(targetId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A label can't be merged into itself");
        }
        // Resolved WITHIN the workspace — a source from another tenant simply doesn't
        // come back, and the size mismatch turns it into a 422 that leaks nothing.
        var sources = labelRepository.findAllByIdInAndWorkspace(sourceIds, ws);
        if (sources.size() != sourceIds.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "One or more source labels don't exist in this workspace");
        }

        issueLabelRepository.deleteSourceRowsAlreadyOnTarget(sources, target);
        issueLabelRepository.flush();                       // ← the non-negotiable flush
        int reassigned = issueLabelRepository.repointToTarget(sources, target);

        labelRepository.deleteAll(sources);
        // Touch updatedAt so the merge is visible in the catalog's audit surface.
        target.setUpdatedAt(Instant.now());
        labelRepository.save(target);

        return new MergeLabelsResponse(targetId, sources.size(), reassigned);
    }

    /**
     * Delete (§4.4) — OWNER/ADMIN only. In use → <strong>409</strong> unless
     * {@code force}, which drops the attachments first. There is deliberately no
     * remap-on-delete: a label carries no positional or config meaning, so
     * {@link #merge} covers the real use case.
     *
     * <p>Like {@link #merge}, a forced delete detaches the label from issues in
     * <em>archived</em> projects too (decision D1) — the catalog row disappears either
     * way, so leaving orphaned join rows behind would only corrupt the read model.
     */
    @Transactional
    public void delete(User actor, UUID workspaceId, UUID labelId, boolean force) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        requireCurator(ctx);
        var label = requireLabel(ctx.workspace(), labelId);

        long inUse = issueLabelRepository.countByLabel(label);
        if (inUse > 0 && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This label is used on " + inUse + " issue(s) — archive it, or delete with force");
        }
        if (inUse > 0) {
            issueLabelRepository.deleteAllByLabel(label);
            issueLabelRepository.flush();
        }
        labelRepository.delete(label);
    }

    /** Usage count for one label — any workspace member (reads are unrestricted). */
    @Transactional(readOnly = true)
    public LabelUsageResponse usage(User actor, UUID workspaceId, UUID labelId) {
        var ws = workspaceAccess.requireMember(actor, workspaceId).workspace();
        var label = requireLabel(ws, labelId);
        return new LabelUsageResponse((int) issueLabelRepository.countByLabel(label));
    }

    // ================================================================= HQL support

    /**
     * Bounded (≤ {@code limit}) typeahead over the workspace's non-archived label
     * names — backs {@code /search/suggest?field=label} when the {@code /schema}
     * picklist is truncated (§3.5). Caller has already verified membership.
     */
    @Transactional(readOnly = true)
    public List<String> suggestNames(Workspace workspace, String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return labelRepository
                .suggestByWorkspace(workspace, q, org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(Label::getName)
                .toList();
    }

    // ============================================================== issue integration

    /**
     * Resolve a {@code labelIds} payload against the issue's OWN workspace (§3.1).
     * Returns {@code null} when the field is absent (leave the set unchanged).
     *
     * <p>Rejects with <strong>422</strong>: more than
     * {@code app.classification.max-labels-per-issue} ids, and any id that is unknown
     * or belongs to another workspace ("Unknown label"). Archived labels are NOT
     * rejected here — an issue that already carries one must stay editable; the
     * archived check applies only to <em>newly added</em> labels, in
     * {@link #diffLabels} / {@link #attachAll}.
     */
    @Transactional(readOnly = true)
    public List<Label> resolveForIssue(Workspace workspace, List<UUID> labelIds) {
        if (labelIds == null) return null;
        var distinct = new LinkedHashSet<>(labelIds);
        distinct.remove(null);
        if (distinct.isEmpty()) return List.of();

        int max = classificationProperties.maxLabelsPerIssue();
        if (distinct.size() > max) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "At most " + max + " labels per issue");
        }
        var resolved = labelRepository.findAllByIdInAndWorkspace(distinct, workspace);
        if (resolved.size() != distinct.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown label");
        }
        return resolved;
    }

    /**
     * Attach a freshly resolved set to a NEW issue (create path). Every label here is
     * an addition, so an archived one is a 422. No history rows — create-time values
     * never write history (consistent with custom fields).
     */
    @Transactional
    public void attachAll(Issue issue, List<Label> labels) {
        if (labels == null || labels.isEmpty()) return;
        var rows = new ArrayList<IssueLabel>(labels.size());
        for (var label : labels) {
            requireNotArchived(label);
            rows.add(newRow(issue, label));
        }
        issueLabelRepository.saveAll(rows);
    }

    /**
     * The full-replacement diff between an issue's current attachments and the desired
     * set. <strong>Pure</strong> — computes only, so the caller can run it with all the
     * other reads BEFORE mutating the issue (the {@code @Version} double-bump rule).
     */
    public LabelChange diffLabels(List<IssueLabel> currentRows, List<Label> desired) {
        var desiredById = new LinkedHashMap<UUID, Label>();
        for (var l : desired) desiredById.put(l.getId(), l);

        var currentById = new LinkedHashMap<UUID, Label>();
        var removed = new ArrayList<IssueLabel>();
        for (var row : currentRows) {
            currentById.put(row.getLabel().getId(), row.getLabel());
            if (!desiredById.containsKey(row.getLabel().getId())) removed.add(row);
        }
        var added = new ArrayList<Label>();
        for (var entry : desiredById.entrySet()) {
            if (!currentById.containsKey(entry.getKey())) added.add(entry.getValue());
        }
        // Attaching an ARCHIVED label is a 422; keeping one already attached is fine.
        for (var l : added) requireNotArchived(l);

        boolean changed = !added.isEmpty() || !removed.isEmpty();
        return new LabelChange(added, removed, changed,
                displayNames(currentById.values()), displayNames(desiredById.values()));
    }

    /**
     * Persist a {@link LabelChange}. Deletes first and {@code flush()}es before the
     * inserts — the added/removed sets are disjoint so a collision is impossible
     * today, but the explicit ordering keeps the documented
     * "INSERT ordered before DELETE ⇒ UNIQUE violation" trap permanently closed.
     */
    @Transactional
    public void applyLabelChange(Issue issue, LabelChange change) {
        if (change == null || !change.changed()) return;
        if (!change.removed().isEmpty()) {
            issueLabelRepository.deleteAll(change.removed());
            issueLabelRepository.flush();
        }
        if (!change.added().isEmpty()) {
            var rows = new ArrayList<IssueLabel>(change.added().size());
            for (var label : change.added()) {
                rows.add(newRow(issue, label));
            }
            issueLabelRepository.saveAll(rows);
        }
    }

    /**
     * The ONLY place an {@code issue_labels} row is built (§3.8). Stamps the row with
     * the ISSUE's workspace, which the composite FKs
     * {@code (issue_id, workspace_id) → issues} and
     * {@code (label_id, workspace_id) → labels} then verify against both parents: if
     * the label came from another tenant the INSERT fails outright instead of quietly
     * creating a cross-tenant attachment. Every write path goes through here so the
     * stamp can't be forgotten.
     */
    private IssueLabel newRow(Issue issue, Label label) {
        var row = new IssueLabel();
        row.setIssue(issue);
        row.setLabel(label);
        row.setWorkspace(issue.getWorkspace());
        return row;
    }

    /** The attachment rows of one issue (managed, label joined) — for the update diff. */
    @Transactional(readOnly = true)
    public List<IssueLabel> attachmentsOf(Issue issue) {
        return issueLabelRepository.findAllByIssue(issue);
    }

    /** Labels of ONE issue, ordered by {@code lower(name)} — the single-issue GET. */
    @Transactional(readOnly = true)
    public List<LabelRef> labelsForIssue(Issue issue) {
        return toRefs(issueLabelRepository.findAllByIssue(issue));
    }

    /**
     * Labels for a WHOLE page of issues in one query, keyed by issue id (§3.7 — the
     * "no N+1" requirement; mirrors {@code FieldValueService.valuesByIssue}). A board
     * page of 100 issues must cost a constant number of queries.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<LabelRef>> labelsByIssue(Collection<Issue> issues) {
        if (issues.isEmpty()) return Map.of();
        var rowsByIssue = new HashMap<UUID, List<IssueLabel>>();
        for (var row : issueLabelRepository.findAllByIssueIn(issues)) {
            rowsByIssue.computeIfAbsent(row.getIssue().getId(), k -> new ArrayList<>()).add(row);
        }
        var byIssue = new HashMap<UUID, List<LabelRef>>(rowsByIssue.size());
        rowsByIssue.forEach((issueId, rows) -> byIssue.put(issueId, toRefs(rows)));
        return byIssue;
    }

    /**
     * The result of a full-replacement label diff.
     *
     * @param added    labels to attach (already checked non-archived)
     * @param removed  the attachment rows to delete
     * @param changed  false when the requested set equals the current one (no-op: no
     *                 history row, and the issue is still saved by the caller)
     * @param oldNames comma-joined display names before, or null when empty
     * @param newNames comma-joined display names after, or null when empty
     */
    public record LabelChange(List<Label> added, List<IssueLabel> removed, boolean changed,
                             String oldNames, String newNames) {}

    // ================================================================= helpers

    /** Deterministic auto-color (§4.1) — same name ⇒ same swatch, forever. */
    public static String colorForName(String name) {
        int hash = name.toLowerCase(Locale.ROOT).hashCode();
        return PALETTE.get(Math.floorMod(hash, PALETTE.size()));
    }

    private List<LabelRef> toRefs(List<IssueLabel> rows) {
        return rows.stream()
                .map(IssueLabel::getLabel)
                .sorted(Comparator.comparing(l -> l.getName().toLowerCase(Locale.ROOT)))
                .map(LabelRef::of)
                .toList();
    }

    private static String displayNames(Collection<Label> labels) {
        if (labels.isEmpty()) return null;
        return labels.stream()
                .map(Label::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
    }

    /**
     * {@code labelId → issueCount} for a whole catalog. Batched ({@link UsageCounts})
     * because {@code countsByLabels} binds one JDBC parameter per label: a catalog past
     * PostgreSQL's 65 535-parameter ceiling would fail outright instead of merely
     * costing more.
     */
    private Map<UUID, Integer> usageCounts(List<Label> labels) {
        return UsageCounts.countIn(labels, issueLabelRepository::countsByLabels);
    }

    private Label requireLabel(Workspace workspace, UUID labelId) {
        return labelRepository.findByIdAndWorkspace(labelId, workspace)
                .orElseThrow(LabelNotFoundException::new);
    }

    private void requireNotArchived(Label label) {
        if (label.isArchived()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Label '" + label.getName() + "' is archived");
        }
    }

    /**
     * Archive / unarchive / merge / delete — <strong>{@code label.manage} with
     * <em>no</em> ownership argument</strong> (HD-126 S3, §10.1).
     *
     * <p>The missing argument is the whole design, not an omission.
     * {@code PermissionSet.require(permission)} is satisfied only by an
     * <em>unrestricted</em> grant, so the built-in workspace Member — who holds
     * {@code label.manage} <em>own-only</em> so that {@link #requireEditor} keeps letting
     * them rename what they made — can never reach these four. That is today's rule
     * exactly: a member may rename the label they created, and may not archive it.
     * Passing {@code isOwn} here would hand every workspace member archive/merge/delete
     * over their own labels, which is the one way to get this pair wrong.
     */
    private void requireCurator(WorkspaceContext ctx) {
        ctx.permissions().require(Permission.LABEL_MANAGE);
    }

    /**
     * Rename / recolor / describe — <strong>{@code label.manage} qualified by ownership</strong>
     * (HD-126 S3, §10.1, §6.4). Ownership of a label is its {@code created_by}, computed
     * here and never by {@code PermissionSet}.
     *
     * <p>Replaces an admin-bypass-then-creator-fallback that answered the same for every
     * actor: OWNER/ADMIN hold the unrestricted grant, Member holds the own-only one.
     */
    private void requireEditor(WorkspaceContext ctx, User actor, Label label) {
        var creator = label.getCreatedBy();
        boolean isOwn = creator != null && creator.getId().equals(actor.getId());
        ctx.permissions().require(Permission.LABEL_MANAGE, isOwn);
    }

    private LabelNameConflictException duplicate(Label existing) {
        String detail = existing.isArchived()
                ? "An archived label already uses this name — unarchive or rename it"
                : "A label named '" + existing.getName() + "' already exists in this workspace";
        return new LabelNameConflictException(detail, existing.getId());
    }

    /**
     * Name normalization (§4.1): NFC, drop non-whitespace control AND format
     * characters, trim, collapse internal whitespace runs to one space. Spaces are
     * allowed — a deliberate divergence from Jira. Casing is preserved for display;
     * uniqueness is case-insensitive (the {@code labels_workspace_name_uk} functional
     * index).
     *
     * <p>{@code \p{Cf}} (format) matters as much as {@code \p{Cc}} (control): the
     * bidi overrides (U+202A–202E, U+2066–2069) and the zero-width characters
     * (U+200B/200E, U+FEFF) are invisible, so without stripping them a member could
     * create a label that <em>renders</em> identically to an existing one while
     * occupying a different unique slot — display spoofing in every picker and filter.
     *
     * <p>The separator class {@code \p{Z}} is the same attack through a different
     * code-point class and must be folded too: {@code \s} and {@code String.trim()}
     * are ASCII-only, so a name whose separator is U+00A0 (or U+2007, U+202F, U+3000)
     * would otherwise survive both the collapse and the trim and take a <em>second</em>
     * unique slot next to the plain-space one while rendering identically — and a
     * leading U+00A0 would even sneak past the blank check.
     */
    private String requireValidName(String raw) {
        String name = normalizeName(raw);
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Label name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Label name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    /**
     * Is this integrity violation the label-name unique index losing a race, or a real
     * fault? Translating <em>every</em> {@code DataIntegrityViolationException} into a
     * 409 "already exists" would hide a genuine 500-class bug behind a plausible-looking
     * conflict, which is exactly the shape that makes an incident hard to diagnose.
     *
     * <p>Hibernate's {@code ConstraintViolationException} carries the violated
     * constraint/index name (Postgres reports it on SQLSTATE 23505); Spring keeps it as
     * the cause of the translated exception. When no name is available (a non-Hibernate
     * translation path) we fall back to the duplicate-key <em>shape</em> — still far
     * narrower than "any integrity violation".
     *
     * <p>Only the constraint NAME is logged: no SQL, no exception message, no user
     * input — the error-hygiene rule the security review signed off on.
     */
    private static boolean isNameConflict(DataIntegrityViolationException e) {
        String constraint = constraintNameOf(e);
        boolean nameConflict = constraint != null
                ? NAME_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)
                : e instanceof DuplicateKeyException;
        if (nameConflict) {
            log.debug("Label write lost the unique-name race on constraint [{}]",
                    constraint != null ? constraint : NAME_UNIQUE_CONSTRAINT);
        } else {
            log.warn("Label write failed on an unexpected constraint [{}] — rethrowing",
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

    /**
     * Delegates to {@link ClassificationNames#normalize} — the ONE implementation
     * shared with components (HD-31) and versions (HD-32), so the anti-spoofing rules
     * can't drift between the three primitives. Behavior is unchanged.
     */
    static String normalizeName(String raw) {
        return ClassificationNames.normalize(raw);
    }

    /**
     * The belt behind {@code @Pattern(regexp = ColorFormat.REGEX)} on the label DTOs. Over the web
     * the annotation fires first, so this is unreachable for a format violation there; it exists
     * for the in-process callers bean validation never sees — {@code DemoDataService} builds a
     * {@code CreateLabelRequest} directly — and it trims, which an annotation cannot.
     *
     * <p><strong>Both spell the shape AND the sentence from
     * {@link com.hamstrack.common.util.ColorFormat}</strong>, so which of the two answered is
     * invisible to the caller, which is the entire requirement: one shape, one sentence. Until
     * HD-176's review the DTOs carried an inline copy of the expression with a wording of their
     * own, which left {@code ColorFormat.MESSAGE} dead on the very path it was written for.
     *
     * <p>400 here, and the status deliberately is <em>not</em> shared: the same format is refused
     * with 422 wherever the value is buried in a JSON document no annotation can reach. A status
     * belongs to the endpoint's convention; a sentence belongs to the shape.
     */
    private String requireValidColor(String raw) {
        String color = raw.trim();
        if (!ColorFormat.isValid(color)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ColorFormat.MESSAGE);
        }
        return color;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
