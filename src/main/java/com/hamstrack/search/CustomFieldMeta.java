package com.hamstrack.search;

import com.hamstrack.issue.entity.FieldType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Per-request metadata for one custom field (M2 {@code field_defs}) that a search
 * caller may query (HD-52). Built by {@link ResolutionContextFactory} from the
 * non-archived fields reachable by the caller's <em>visible</em> projects (the same
 * source {@code /schema} and the config endpoint use), and consumed by
 * {@link HqlValidator} (allowed operators per type) and {@link HqlCompiler} (the
 * correlated {@code EXISTS} subquery over {@code issue_field_values}).
 *
 * <p>The HQL field name for a custom field is its immutable {@code key}. Lookup
 * precedence is <strong>system field first</strong> (see {@link FieldRegistry}); a
 * key that is neither a system field nor a visible custom field is "unknown field".
 * A custom field a caller cannot see is never leaked — it simply isn't in the map.
 *
 * @param fieldId     the {@code field_defs} id, bound as {@code v.field.id = :fid}
 * @param key         the field's machine key (== the HQL field name)
 * @param name        the field's display name (for {@code /schema} label + errors)
 * @param type        the {@link FieldType} — drives allowed operators + value shape
 * @param optionsById SELECT/MULTI_SELECT option id → label (for the {@code /schema}
 *                    picklist; empty for other types), insertion-ordered
 * @param optionIdByLabel case-insensitive option label → id, plus id → id, so a user
 *                    may write either the human label or the raw option id
 */
public record CustomFieldMeta(
        UUID fieldId,
        String key,
        String name,
        FieldType type,
        Map<String, String> optionsById,
        Map<String, String> optionIdByLabel
) {
    public CustomFieldMeta {
        optionsById = optionsById == null ? Map.of() : Map.copyOf(optionsById);
        optionIdByLabel = optionIdByLabel == null ? Map.of() : Map.copyOf(optionIdByLabel);
    }

    /** Resolve a user-supplied SELECT/MULTI_SELECT value (label or id) to its option id, or null. */
    public String resolveOption(String labelOrId) {
        if (labelOrId == null) return null;
        return optionIdByLabel.get(labelOrId.toLowerCase(Locale.ROOT));
    }

    /** Option labels in insertion order (for the {@code /schema} picklist). */
    public List<String> optionLabels() {
        return List.copyOf(optionsById.values());
    }
}
