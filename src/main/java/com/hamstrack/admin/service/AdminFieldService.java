package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.*;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.*;
import com.hamstrack.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
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
    private final ProjectRepository projectRepository;
    private final ProjectCountService projectCountService;

    // ---------- field defs ----------

    @Transactional(readOnly = true)
    public List<AdminFieldResponse> listFields() {
        return fieldDefRepository.findAllByScopeWorkspaceIdIsNullOrderByName().stream()
                .map(f -> AdminFieldResponse.of(f, fieldUsage(f)))
                .toList();
    }

    @Transactional
    public AdminFieldResponse createField(UpsertFieldRequest req) {
        var key = req.key() == null || req.key().isBlank() ? slugify(req.name()) : req.key();
        if (fieldDefRepository.existsByScopeWorkspaceIdIsNullAndKey(key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field key already exists");
        }
        if (fieldDefRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field name already exists");
        }
        requireSelectOptions(req.type(), req);
        var f = new FieldDef();
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
    public AdminFieldResponse updateField(UUID id, UpsertFieldRequest req) {
        var f = requireField(id);
        if (!f.getName().equals(req.name())
                && fieldDefRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field name already exists");
        }
        if (req.type() != null && req.type() != f.getType()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Field type cannot change once created — stored values depend on it");
        }
        requireSelectOptions(f.getType(), req);
        f.setName(req.name());
        f.setConfig(req.config());
        f.setDescription(req.description());
        fieldDefRepository.save(f);
        return AdminFieldResponse.of(f, fieldUsage(f));
    }

    @Transactional
    public void setFieldArchived(UUID id, boolean archived) {
        var f = requireField(id);
        f.setArchivedAt(archived ? Instant.now() : null);
        fieldDefRepository.save(f);
    }

    @Transactional
    public void deleteField(UUID id, boolean dropValues) {
        var f = requireField(id);
        long values = valueRepository.countByField(f);
        if (values > 0 && !dropValues) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    values + " issues have a value for this field — pass dropValues=true to delete them, or archive instead");
        }
        // set memberships + values cascade via FK
        fieldDefRepository.delete(f);
    }

    // ---------- field sets ----------

    @Transactional(readOnly = true)
    public List<AdminFieldSetResponse> listSets() {
        return fieldSetRepository.findAllByScopeWorkspaceIdIsNullOrderByName().stream()
                .map(this::toSetResponse)
                .toList();
    }

    @Transactional
    public AdminFieldSetResponse createSet(UpsertFieldSetRequest req) {
        if (fieldSetRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field set name already exists");
        }
        var set = new FieldSet();
        set.setName(req.name());
        fieldSetRepository.save(set);
        applyItems(set, req);
        return toSetResponse(set);
    }

    @Transactional
    public AdminFieldSetResponse updateSet(UUID id, UpsertFieldSetRequest req) {
        var set = requireSet(id);
        if (!set.getName().equals(req.name())
                && fieldSetRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field set name already exists");
        }
        set.setName(req.name());
        fieldSetRepository.save(set);
        fieldSetItemRepository.deleteAllBySet(set);
        applyItems(set, req);
        return toSetResponse(set);
    }

    @Transactional
    public void deleteSet(UUID id) {
        var set = requireSet(id);
        if (set.isSystemDefault()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The system default field set cannot be deleted");
        }
        long projects = projectsUsing(set);
        if (projects > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    projects + " projects use this field set — reassign them first");
        }
        fieldSetRepository.delete(set);
    }

    public long projectsUsing(FieldSet set) {
        long bound = projectRepository.countByFieldSetId(set.getId());
        return set.isSystemDefault() ? bound + projectRepository.countByFieldSetIdIsNull() : bound;
    }

    // ---------- usage detail (popovers) ----------

    @Transactional(readOnly = true)
    public UsageDetailResponse fieldUsageDetail(UUID id) {
        var f = requireField(id);
        var sets = fieldSetItemRepository.findSetsUsingField(f.getId());
        var projects = sets.stream()
                .flatMap(set -> projectCountService.projectsListUsingFieldSet(set).stream())
                .toList();
        return new UsageDetailResponse(
                List.of(),
                sets.stream().map(FieldSet::getName).toList(),
                UsageDetailResponse.dedupe(projects),
                valueRepository.countByField(f));
    }

    // ---------- helpers ----------

    private void applyItems(FieldSet set, UpsertFieldSetRequest req) {
        var seen = new HashSet<UUID>();
        short pos = 0;
        for (var itemReq : req.items()) {
            if (!seen.add(itemReq.fieldId())) continue;
            var field = fieldDefRepository.findByIdAndScopeWorkspaceIdIsNull(itemReq.fieldId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown field"));
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

    private void requireSelectOptions(FieldType type, UpsertFieldRequest req) {
        if (type == FieldType.SELECT || type == FieldType.MULTI_SELECT) {
            var cfg = req.config();
            if (cfg == null || !cfg.has("options") || !cfg.get("options").isArray() || cfg.get("options").isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Select fields need at least one option");
            }
            for (var opt : cfg.get("options")) {
                if (!opt.hasNonNull("id") || opt.get("id").asText().isBlank()
                        || !opt.hasNonNull("label") || opt.get("label").asText().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Every option needs an id and a label");
                }
            }
        }
    }

    private UsageInfo fieldUsage(FieldDef f) {
        long sets = fieldSetItemRepository.countByField(f);
        long projects = fieldSetItemRepository.findSetsUsingField(f.getId()).stream()
                .mapToLong(this::projectsUsing).sum();
        return new UsageInfo(0, sets, projects, valueRepository.countByField(f));
    }

    private AdminFieldSetResponse toSetResponse(FieldSet set) {
        var items = fieldSetItemRepository.findAllBySetOrderByPosition(set).stream()
                .map(i -> new AdminFieldSetResponse.Item(
                        AdminFieldResponse.of(i.getField(), null),
                        i.isRequired(), i.isShowOnCreate()))
                .toList();
        return new AdminFieldSetResponse(set.getId(), set.getName(), set.isSystemDefault(),
                items, projectsUsing(set));
    }

    private String slugify(String name) {
        var slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return slug.isBlank() ? "field" : slug.substring(0, Math.min(slug.length(), 50));
    }

    private FieldDef requireField(UUID id) {
        return fieldDefRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field not found"));
    }

    private FieldSet requireSet(UUID id) {
        return fieldSetRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field set not found"));
    }
}
