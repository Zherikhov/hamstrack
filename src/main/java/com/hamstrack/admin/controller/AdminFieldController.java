package com.hamstrack.admin.controller;

import com.hamstrack.admin.dto.*;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.admin.service.AdminFieldService;
import com.hamstrack.auth.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Custom field catalog and field sets for the system administrator. A field's type is
 * immutable after creation; its key is fixed too, <strong>except while a built-in
 * {@link com.hamstrack.search.FieldRegistry} search name has taken it</strong>, in which case
 * {@code PATCH} renames it (ADR-0036 / HD-275 — the exit from a key the product shadowed).
 * Deleting a field with values requires the explicit {@code dropValues=true} confirmation.
 * Minting a key — on create, and as a rename's target — refuses (409) one the registry has
 * claimed, checked after slugification. Guarded by hasRole(ADMIN) in SecurityConfig.
 *
 * <p><strong>A rename here reaches global rows, which means every workspace on the instance at
 * once.</strong> {@code ScopeContext.global()} resolves only rows stamped with no scope, so a
 * delegated admin cannot touch them and this console cannot touch a tenant's; the flip side is
 * that a global key change is a breaking change for every tenant querying it, and belongs in the
 * release notes on the same terms {@code RetiredFieldAliases} states for a retired global def.
 *
 * <p><strong>Two refusal families, and which one a rule lands in is a property of the
 * rule: 409 is a collision with something that already exists, 422 is everything the
 * product declines on its own terms.</strong> The 422 family has grown since HD-171 —
 * an immutable field's type being changed, a {@code config} document or an option leaf
 * past its bound, an option {@code color} that is not one — and it will grow again,
 * which is why it is given here by its shape rather than enumerated: a list of refusals
 * reads as complete one entry before it stops being so.
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
    public AdminFieldResponse updateField(@AuthenticationPrincipal User actor,
                                          @PathVariable UUID id, @Valid @RequestBody UpsertFieldRequest req) {
        return fieldService.updateField(actor, SCOPE, id, req);
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
