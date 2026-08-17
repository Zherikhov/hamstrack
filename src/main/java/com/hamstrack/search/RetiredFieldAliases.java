package com.hamstrack.search;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Compatibility aliases for HQL field names that a release <em>retired</em> — a
 * key users may still have in a saved filter, mapped to the name that replaced it
 * (delivery-paths proposal §9.2, HD-107).
 *
 * <p>Today there is exactly one entry: {@code story_points} → {@code storyPoints}.
 * V11 archived the global {@code story_points} custom field and promoted the value
 * to the native {@code issues.story_points} column under the HQL name
 * {@code storyPoints}; {@link ResolutionContextFactory} skips archived
 * {@code field_defs}, so every filter written as {@code story_points = 5} would
 * otherwise fail with "unknown field" the next time its owner ran it. (V11 also
 * archived a {@code sprint} placeholder field, but {@code sprint} is a live
 * {@link FieldRegistry} name, so it resolves natively and needs no alias.)
 *
 * <p><strong>This is deliberately NOT a {@link FieldRegistry} entry, and the
 * precedence is the whole point.</strong> {@code HqlCompiler.isCustom} treats a
 * name as a custom field only when the registry does <em>not</em> know it — the
 * registry always wins. Registering {@code story_points} there would therefore
 * permanently shadow any workspace's own custom field keyed {@code story_points},
 * in every tenant, forever. So the alias is consulted <em>last</em>:
 *
 * <pre>
 *   system field (FieldRegistry) → visible custom field (ResolutionContext)
 *                                → retired-key alias → "unknown field" (422)
 * </pre>
 *
 * <p>Consequences of that ordering, both intended: a tenant that keys its own
 * custom field {@code story_points} resolves to <em>its own field</em>, never to
 * the native column; and a workspace with no such custom field resolves the retired
 * key to the native {@code storyPoints}, so the saved filter keeps working.
 *
 * <p><strong>Caveat — that precedence is not purely a per-tenant decision.</strong>
 * {@code FieldDef.scopeWorkspaceId} is nullable, so a <em>global</em> field def is
 * reachable by every project of every workspace and enters
 * {@code ResolutionContext.customFieldsByKey} for all tenants at once. If an instance
 * admin ever creates a global custom field keyed {@code story_points}, the alias stops
 * firing in <em>every</em> workspace simultaneously and {@code story_points = 5}
 * changes meaning everywhere, not in one tenant. No data crosses a workspace boundary
 * when that happens — values stay per-issue JSONB behind the scope predicate — but the
 * blast radius is the whole instance, so a global def that reuses a retired key must
 * be treated as a breaking change rather than a local one.
 *
 * <p>Aliases are compatibility, not vocabulary: they are never offered in
 * {@code /search/schema} suggestions, and no migration ever rewrites the stored
 * text of anybody's saved filter (§9.2).
 */
@Component
public class RetiredFieldAliases {

    /** lowercase retired key → the canonical {@link FieldRegistry} name that replaced it. */
    private static final Map<String, String> BY_RETIRED_KEY = Map.of(
            "story_points", "storyPoints");

    /**
     * The canonical field name a retired key maps to, or empty when the name is not
     * a retired key. Case-insensitive, like every other HQL name lookup.
     *
     * <p>Callers MUST consult this only after both normal resolution steps have
     * missed — see the class javadoc; the ordering is a tenancy-safety property,
     * not a style choice.
     */
    public Optional<String> canonicalName(String retiredKey) {
        if (retiredKey == null) return Optional.empty();
        return Optional.ofNullable(BY_RETIRED_KEY.get(retiredKey.toLowerCase(Locale.ROOT)));
    }
}
