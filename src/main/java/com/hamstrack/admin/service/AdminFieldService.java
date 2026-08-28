package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.*;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Custom field catalog + field set CRUD for the admin console. Deleting a
 * field that has values requires explicit confirmation (query param) and
 * drops the values — there is no meaningful "remap" for arbitrary shapes;
 * archiving is the safe alternative. Select options are referenced by id in
 * stored values, so removing an option leaves old values rendering the raw
 * id — the UI warns about it.
 */
@Service
@RequiredArgsConstructor
public class AdminFieldService {

    private final FieldDefRepository fieldDefRepository;
    private final FieldSetRepository fieldSetRepository;
    private final FieldSetItemRepository fieldSetItemRepository;
    private final IssueFieldValueRepository valueRepository;
    private final ProjectCountService projectCountService;
    /** The HQL vocabulary, consulted only to refuse a key it has claimed — see requireUnreservedKey. */
    private final com.hamstrack.search.FieldRegistry fieldRegistry;

    /**
     * Options in one SELECT/MULTI_SELECT field — a ceiling on a picker, not on content
     * (see {@link #requireSelectOptions}).
     */
    private static final int MAX_OPTIONS = 100;

    /** Max length of one option's id or label, matching {@code field_defs.name VARCHAR(100)}. */
    private static final int MAX_OPTION_TEXT = 100;

    /**
     * The whole {@code config} document, serialised, for <strong>every</strong> field type
     * (see {@link #requireConfigSize}).
     *
     * <p><strong>It overlaps the two option ceilings and is deliberately the tighter of the
     * two constraints at the extreme.</strong> A theoretical maximum SELECT — {@link #MAX_OPTIONS}
     * options each carrying a {@link #MAX_OPTION_TEXT}-character id <em>and</em> label — is about
     * 23 KB of JSON and is refused here, before {@link #requireSelectOptions} ever runs. That is
     * intended rather than an accident of arithmetic: 100 dropdown entries labelled with 100
     * characters each is not a picker anyone can use, and the realistic maximum is an order of
     * magnitude under this. Said out loud so the interaction is read as a decision, not
     * discovered later as a bug.
     */
    private static final int MAX_CONFIG_LENGTH = 20000;

    // ---------- field defs ----------

    @Transactional(readOnly = true)
    public List<AdminFieldResponse> listFields(ScopeContext scope) {
        // Inherited fields are shown read-only in delegated consoles (see AdminCatalogService.listStatuses)
        var rows = scope.isGlobal()
                ? fieldDefRepository.findAllAtScope(null, null)
                : fieldDefRepository.findAllVisibleTo(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return rows.stream().map(f -> AdminFieldResponse.of(f, fieldUsage(scope, f))).toList();
    }

    @Transactional
    public AdminFieldResponse createField(ScopeContext scope, UpsertFieldRequest req) {
        var key = req.key() == null || req.key().isBlank() ? slugify(req.name()) : req.key();
        requireUnreservedKey(key);
        if (fieldDefRepository.existsVisibleToAndKey(scope.visibleWorkspaceId(), scope.visibleProjectId(), key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A field with key '" + key + "' already exists or is inherited — reuse it instead of duplicating");
        }
        if (fieldDefRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A field named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        requireConfigSize(req);
        requireSelectOptions(req.type(), req);
        var f = new FieldDef();
        scope.stamp(f);
        f.setKey(key);
        f.setName(req.name());
        f.setType(req.type());
        f.setConfig(req.config());
        f.setDescription(req.description());
        fieldDefRepository.save(f);
        return AdminFieldResponse.of(f, new UsageInfo(0, 0, 0, 0));
    }

    /** Type and key are immutable — stored values depend on both. */
    @Transactional
    public AdminFieldResponse updateField(ScopeContext scope, UUID id, UpsertFieldRequest req) {
        var f = requireField(scope, id);
        if (!f.getName().equals(req.name())
                && fieldDefRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A field named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        if (req.type() != null && req.type() != f.getType()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Field type cannot change once created — stored values depend on it");
        }
        requireConfigSize(req);
        requireSelectOptions(f.getType(), req);
        f.setName(req.name());
        f.setConfig(req.config());
        f.setDescription(req.description());
        fieldDefRepository.save(f);
        return AdminFieldResponse.of(f, fieldUsage(scope, f));
    }

    @Transactional
    public void setFieldArchived(ScopeContext scope, UUID id, boolean archived) {
        var f = requireField(scope, id);
        f.setArchivedAt(archived ? Instant.now() : null);
        fieldDefRepository.save(f);
    }

    @Transactional
    public void deleteField(ScopeContext scope, UUID id, boolean dropValues) {
        var f = requireField(scope, id);
        if (f.isSystem()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "System fields can only be archived, not deleted.");
        }
        // TWO COUNTS, exactly as AdminCatalogService's three deletes do it. THE DECISION MUST
        // COVER THE POPULATION THE DELETE AFFECTS — remapped or cascaded; only the MESSAGE may
        // be narrower.
        //
        // An earlier draft of this fix scoped the decision too, on the reasoning that this guard
        // protects no remap: `issue_field_values` cascades via FK, so nothing here can be
        // stranded and no 23503 can follow. That is true about STRANDING and silent about
        // CONSENT, which is the other thing this guard does. `dropValues` is the caller agreeing
        // to destroy data, and the cascade it authorises is unscoped. Compare the degenerate
        // state the pair exists to survive (scoped count 0, unscoped count > 0):
        //
        //   * catalog three — unscoped decides, so the delete is REFUSED. Nothing happens.
        //   * deleteField, scoped — the delete PROCEEDS, and ON DELETE CASCADE removes another
        //     tenant's issue_field_values rows silently, with dropValues never asked for.
        //
        // So the one place the pattern was not applied had the strictly worse degradation. And
        // "unreachable today by construction" is the argument this ticket already refused as a
        // reason to collapse the catalog counts into one; it cannot be accepted here.
        long values = valueRepository.countByField(f);
        if (values > 0 && !dropValues) {
            long mine = valueRepository.countByFieldScoped(f, scope.workspaceId(), scope.projectId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, valuesInUse(mine));
        }
        // set memberships + values cascade via FK
        fieldDefRepository.delete(f);
    }

    // ---------- field sets ----------

    @Transactional(readOnly = true)
    public List<AdminFieldSetResponse> listSets(ScopeContext scope) {
        var sets = scope.isGlobal()
                ? fieldSetRepository.findAllAtScope(null, null)
                : fieldSetRepository.findAllBindableForProject(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return sets.stream().map(set -> toSetResponse(scope, set)).toList();
    }

    @Transactional
    public AdminFieldSetResponse createSet(ScopeContext scope, UpsertFieldSetRequest req) {
        if (fieldSetRepository.existsAtScopeAndName(scope.workspaceId(), scope.projectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field set name already exists");
        }
        var set = new FieldSet();
        scope.stamp(set);
        set.setName(req.name());
        fieldSetRepository.save(set);
        applyItems(scope, set, req);
        return toSetResponse(scope, set);
    }

    @Transactional
    public AdminFieldSetResponse updateSet(ScopeContext scope, UUID id, UpsertFieldSetRequest req) {
        var set = requireSet(scope, id);
        if (!set.getName().equals(req.name())
                && fieldSetRepository.existsAtScopeAndName(scope.workspaceId(), scope.projectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field set name already exists");
        }
        set.setName(req.name());
        fieldSetRepository.save(set);
        fieldSetItemRepository.deleteAllBySet(set);
        // Flush DELETEs before re-inserting — Hibernate orders INSERTs ahead of
        // DELETEs in one flush, colliding with UNIQUE(set_id, field_id).
        fieldSetItemRepository.flush();
        applyItems(scope, set, req);
        return toSetResponse(scope, set);
    }

    @Transactional
    public void deleteSet(ScopeContext scope, UUID id) {
        var set = requireSet(scope, id);
        if (set.isSystemDefault()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The system default field set cannot be deleted");
        }
        long projects = projectsUsing(scope, set);
        if (projects > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    projects + " projects use this field set — reassign them first");
        }
        fieldSetRepository.delete(set);
    }

    public long projectsUsing(ScopeContext scope, FieldSet set) {
        return projectCountService.projectsUsingFieldSet(scope, set);
    }

    // ---------- usage detail (popovers) ----------

    @Transactional(readOnly = true)
    public UsageDetailResponse fieldUsageDetail(ScopeContext scope, UUID id) {
        var f = requireField(scope, id);
        var sets = fieldSetItemRepository.findSetsUsingField(f.getId()).stream()
                .filter(scope::canSee).toList();
        var projects = sets.stream()
                .flatMap(set -> projectCountService.projectsListUsingFieldSet(scope, set).stream())
                .toList();
        return new UsageDetailResponse(
                List.of(),
                sets.stream().map(FieldSet::getName).toList(),
                UsageDetailResponse.dedupe(projects),
                valueRepository.countByFieldScoped(f, scope.workspaceId(), scope.projectId()));
    }

    // ---------- helpers ----------

    private void applyItems(ScopeContext scope, FieldSet set, UpsertFieldSetRequest req) {
        var seen = new HashSet<UUID>();
        short pos = 0;
        for (var itemReq : req.items()) {
            if (!seen.add(itemReq.fieldId())) continue;
            // A field the set may include: visible to this scope (global ∪ ancestor-ws ∪ own project)
            var field = fieldDefRepository.findByIdVisibleTo(
                            itemReq.fieldId(), scope.visibleWorkspaceId(), scope.visibleProjectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown field"));
            var item = new FieldSetItem();
            item.setSet(set);
            item.setField(field);
            item.setPosition(pos++);
            item.setRequired(itemReq.required());
            // A required field the create form doesn't show would make creation impossible
            item.setShowOnCreate(itemReq.required() || itemReq.showOnCreate());
            fieldSetItemRepository.save(item);
        }
    }

    /**
     * <strong>The whole {@code config} document, bounded once for every field type</strong>
     * (HD-171 §4.3, round 2). {@code config} is a {@code JsonNode}, so no {@code @Size} on the
     * DTO reaches inside it, and {@code field_defs.config JSONB} has no width to overflow — so
     * bean validation genuinely cannot bound it and a service check must.
     *
     * <p><strong>Why this exists on top of the two option ceilings, which is the whole lesson:
     * those bound two <em>leaves</em>, and this bounds the <em>document</em>.</strong> Round 1
     * bounded {@code options[].id} and {@code options[].label} and then described {@code config}
     * as bounded, which it was not:
     * {@code {"options":[{"id":"a","label":"b","color":"<20 M characters>"}]}} passed, so did any
     * unrelated top-level key, and so did the entire config of every non-SELECT type, which never
     * enters that branch at all. A bound on the members of a set is not a bound on the set.
     *
     * <p><strong>And this field is egress.</strong> {@code ProjectConfigController} returns
     * {@code config} to every project member on the endpoint the SPA fetches for every board and
     * every issue form — so an unbounded document is not a stored blob, it is hundreds of
     * megabytes re-served on every page load, plantable by any workspace admin (contained to
     * their own tenant, which is what keeps it Low rather than High).
     *
     * <p>422 rather than 400, like every other refusal on this path: the request is well-formed
     * and the product declines it. Measured on the compact serialisation, which is what is stored
     * and what is re-served, not on the request's own whitespace.
     */
    private void requireConfigSize(UpsertFieldRequest req) {
        var cfg = req.config();
        if (cfg == null) return;
        int size = cfg.toString().length();
        if (size > MAX_CONFIG_LENGTH) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Field config is " + size + " characters; the maximum is " + MAX_CONFIG_LENGTH
                    + ". This document is sent to every project member on every board load.");
        }
    }

    /**
     * The two <em>option</em> ceilings — a bound on the leaves, with {@link #requireConfigSize}
     * bounding the document they live in (HD-171 §4.3). 422 for the same reason.
     *
     * <p>The numbers are ceilings on a picker, not on prose: 100 options is far past any usable
     * dropdown, and an option {@code id} is read back by {@code FieldValueService.optionIds} on
     * <em>every</em> custom-field write, so an unbounded one is unbounded work on a hot path.
     */
    private void requireSelectOptions(FieldType type, UpsertFieldRequest req) {
        if (type == FieldType.SELECT || type == FieldType.MULTI_SELECT) {
            var cfg = req.config();
            if (cfg == null || !cfg.has("options") || !cfg.get("options").isArray() || cfg.get("options").isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Select fields need at least one option");
            }
            if (cfg.get("options").size() > MAX_OPTIONS) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "A select field can have at most " + MAX_OPTIONS + " options");
            }
            for (var opt : cfg.get("options")) {
                if (!opt.hasNonNull("id") || opt.get("id").asText().isBlank()
                        || !opt.hasNonNull("label") || opt.get("label").asText().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Every option needs an id and a label");
                }
                if (opt.get("id").asText().length() > MAX_OPTION_TEXT
                        || opt.get("label").asText().length() > MAX_OPTION_TEXT) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "An option id and label must each be at most " + MAX_OPTION_TEXT
                            + " characters");
                }
            }
        }
    }

    private UsageInfo fieldUsage(ScopeContext scope, FieldDef f) {
        var sets = fieldSetItemRepository.findSetsUsingField(f.getId()).stream()
                .filter(scope::canSee).toList();
        long projects = sets.stream()
                .mapToLong(set -> projectCountService.projectsUsingFieldSet(scope, set)).sum();
        return new UsageInfo(0, sets.size(), projects,
                valueRepository.countByFieldScoped(f, scope.workspaceId(), scope.projectId()));
    }

    private AdminFieldSetResponse toSetResponse(ScopeContext scope, FieldSet set) {
        var items = fieldSetItemRepository.findAllBySetOrderByPosition(set).stream()
                .map(i -> new AdminFieldSetResponse.Item(
                        AdminFieldResponse.of(i.getField(), null),
                        i.isRequired(), i.isShowOnCreate()))
                .toList();
        return new AdminFieldSetResponse(set.getId(), set.getName(), set.isSystemDefault(),
                items, projectsUsing(scope, set), set.scopeLabel());
    }

    private String slugify(String name) {
        var slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return slug.isBlank() ? "field" : slug.substring(0, Math.min(slug.length(), 50));
    }

    /**
     * Refuse a key the HQL vocabulary has claimed (HD-101). A registered search field always
     * outranks a custom field of the same key, so such a field would be born half-invisible: it
     * would work everywhere in the product except search, where the name means the system field
     * and {@code /schema} silently omits the tenant's — no error, no log line, no affordance.
     *
     * <p><strong>Checked after slugification, and on create only.</strong> After, because the key
     * is derived from the display name when omitted, so a field a curator simply calls "Project"
     * walks straight past a check placed before it — which is exactly how this collision arises
     * without anybody choosing it. Create-only, because the key is immutable on update and
     * because this must never reject or migrate a row that already exists: it stops recurrence,
     * it is not retroactive.
     */
    private void requireUnreservedKey(String key) {
        if (fieldRegistry.find(key).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'" + key + "' is a reserved search field name — pick a different key "
                            + "(a custom field with this key would not be searchable)");
        }
    }

    private FieldDef requireField(ScopeContext scope, UUID id) {
        return fieldDefRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field not found"));
    }

    private FieldSet requireSet(ScopeContext scope, UUID id) {
        return fieldSetRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field set not found"));
    }

    /**
     * The "values would be destroyed" refusal, built from the <strong>scoped</strong> count while
     * a different, unscoped count made the decision — the same pair
     * {@code AdminCatalogService.inUse} uses, and the same degradation.
     *
     * <p><strong>The zero-scoped branch quotes no number AND prescribes nothing</strong>, exactly
     * as {@code AdminCatalogService.inUse} does, and for the same structural reason: in that
     * state the caller can see none of the affected rows, so "pass {@code dropValues=true} to
     * delete them" would be prescribing an <em>unscoped cascade over rows they cannot see</em>.
     * Archiving is the only remedy that is safe without visibility, so it is the only one named.
     *
     * <p>Weaker here than in its twin, and neutralised anyway. {@code dropValues} is this
     * endpoint's own consent flag, so a caller could pass it regardless — where
     * {@code replaceWithId} additionally repointed foreign rows at a replacement drawn from the
     * caller's scope — and the state is unreachable today. It is fixed because leaving one half
     * of a matched pair alone is how the next reader concludes the two cases differ on purpose.
     *
     * <p>The {@code mine >= 1} branch stays prescriptive: there the caller has visible rows, the
     * count is theirs, and this is the normal path.
     */
    private static String valuesInUse(long mine) {
        if (mine == 0) {
            return "This field is still in use — archive it instead";
        }
        return (mine == 1 ? "1 issue has a value for this field"
                          : mine + " issues have a value for this field")
               + " — pass dropValues=true to delete " + (mine == 1 ? "it" : "them")
               + ", or archive instead";
    }
}
