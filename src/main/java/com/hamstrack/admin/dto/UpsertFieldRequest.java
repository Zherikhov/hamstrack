package com.hamstrack.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.common.util.DisplayText;
import com.hamstrack.issue.entity.FieldType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code key} blank on create = derived from the name. Afterwards it is fixed — unlike the type,
 * <strong>not unconditionally</strong>: an update may change it exactly while a built-in
 * {@code FieldRegistry} search name has taken it, which is the affected tenant's only exit from
 * that shadowing (ADR-0036, HD-275 §6.2). Outside that population a changed key is refused 422.
 *
 * <p><strong>A rename is triggered by DIFFERENCE, never by presence.</strong> A key that is
 * absent, null, blank or equal to the current one (any casing) renames nothing and refuses
 * nothing. A client that echoes the stored key back on save — as this console did until HD-275,
 * and as any round-tripping client naturally does — would otherwise have every ordinary edit of
 * every unshadowed custom field answered 422, for changing nothing; the trigger is a property of
 * the request, so it holds for whatever client sends the next one. Blank on update likewise never
 * means "re-derive from the name": that would rename a field silently whenever somebody edited
 * its display label.
 *
 * <p>{@code config}: {@code {"options":[{id,label,color}]}} for selects, {@code {"min","max"}}
 * for numbers.
 *
 * <p><strong>{@code name} is {@link DisplayText#SINGLE_LINE} because it is now logged.</strong>
 * The key is pattern-bounded to {@code [a-z0-9_]} and always was; the display name was bounded
 * only in length, and {@code ShadowedFieldStartupScan} prints it under the stable
 * {@code shadowed-field-def:} prefix that the operator documentation tells people to alert on.
 * A taxonomy admin owning a live shadowed field could otherwise put a line terminator in the
 * display name and forge a second line under that prefix at every boot — the ordinary
 * log-forging shape, one annotation to close, and the same annotation every other display name
 * in this codebase already carries. {@code description} takes {@link DisplayText#MULTI_LINE}
 * instead: the console renders it as a textarea, and the single-line class rejects the newline,
 * so applying {@code name}'s pattern to prose answers 400 to an Enter keypress — the shipped bug
 * that split those two constants apart.
 */
public record UpsertFieldRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = DisplayText.SINGLE_LINE,
                message = "Name must not contain control characters") String name,
        @Pattern(regexp = "[a-z0-9_]*") @Size(max = 50) String key,
        @NotNull FieldType type,
        JsonNode config,
        @Size(max = 500) @Pattern(regexp = DisplayText.MULTI_LINE,
                message = "Description must not contain control characters") String description
) {}
