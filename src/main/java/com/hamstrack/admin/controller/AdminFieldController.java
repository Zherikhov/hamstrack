package com.hamstrack.admin.controller;

import com.hamstrack.admin.dto.*;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.admin.service.AdminFieldService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Custom field catalog and field sets for the system administrator. Field
 * type and key are immutable after creation (stored values depend on them);
 * deleting a field with values requires the explicit {@code dropValues=true}
 * confirmation. Creating a field also refuses (409) a key already claimed by
 * {@link com.hamstrack.search.FieldRegistry}, checked after slugification and on
 * create only. Guarded by hasRole(ADMIN) in SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminFieldController {

    private final AdminFieldService fieldService;

    private static final ScopeContext SCOPE = ScopeContext.global();

    // ---------- field defs ----------

    @GetMapping("/fields")
    public List<AdminFieldResponse> listFields() {
        return fieldService.listFields(SCOPE);
    }

    @PostMapping("/fields")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminFieldResponse createField(@Valid @RequestBody UpsertFieldRequest req) {
        return fieldService.createField(SCOPE, req);
    }

    @PatchMapping("/fields/{id}")
    public AdminFieldResponse updateField(@PathVariable UUID id, @Valid @RequestBody UpsertFieldRequest req) {
        return fieldService.updateField(SCOPE, id, req);
    }

    @PostMapping("/fields/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveField(@PathVariable UUID id) {
        fieldService.setFieldArchived(SCOPE, id, true);
    }

    @PostMapping("/fields/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchiveField(@PathVariable UUID id) {
        fieldService.setFieldArchived(SCOPE, id, false);
    }

    @DeleteMapping("/fields/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteField(@PathVariable UUID id,
                            @RequestParam(defaultValue = "false") boolean dropValues) {
        fieldService.deleteField(SCOPE, id, dropValues);
    }

    @GetMapping("/fields/{id}/usage")
    public UsageDetailResponse fieldUsage(@PathVariable UUID id) {
        return fieldService.fieldUsageDetail(SCOPE, id);
    }

    // ---------- field sets ----------

    @GetMapping("/field-sets")
    public List<AdminFieldSetResponse> listSets() {
        return fieldService.listSets(SCOPE);
    }

    @PostMapping("/field-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminFieldSetResponse createSet(@Valid @RequestBody UpsertFieldSetRequest req) {
        return fieldService.createSet(SCOPE, req);
    }

    @PatchMapping("/field-sets/{id}")
    public AdminFieldSetResponse updateSet(@PathVariable UUID id, @Valid @RequestBody UpsertFieldSetRequest req) {
        return fieldService.updateSet(SCOPE, id, req);
    }

    @DeleteMapping("/field-sets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSet(@PathVariable UUID id) {
        fieldService.deleteSet(SCOPE, id);
    }
}
