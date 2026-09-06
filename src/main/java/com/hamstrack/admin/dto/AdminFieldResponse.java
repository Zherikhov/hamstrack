package com.hamstrack.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldType;

import java.util.UUID;

/**
 * One custom field definition as the admin consoles see it.
 *
 * @param shadowedBy the canonical built-in HQL search name that has taken this field's
 *                   {@code key}, or {@code null} (HD-275). Populated from
 *                   {@code ShadowedFields.claimedBy}, which is <strong>archive-blind on
 *                   purpose</strong>: the rename affordance this field drives must be offered
 *                   for an archived row too, since an archived row is out of resolution and the
 *                   invariant licensing a rename holds even harder there. The console therefore
 *                   draws its <em>warning</em> on {@code shadowedBy != null && !archived} — a
 *                   warning about a row nothing resolves to is noise — while the <em>Key</em>
 *                   input it draws on {@code shadowedBy != null} alone.
 */
public record AdminFieldResponse(
        UUID id, String key, String name, FieldType type,
        JsonNode config, String description,
        boolean archived, boolean isSystem, UsageInfo usage, String scope,
        String shadowedBy
) {
    public static AdminFieldResponse of(FieldDef f, UsageInfo usage, String shadowedBy) {
        return new AdminFieldResponse(f.getId(), f.getKey(), f.getName(), f.getType(),
                f.getConfig(), f.getDescription(), f.getArchivedAt() != null, f.isSystem(), usage,
                f.scopeLabel(), shadowedBy);
    }
}
