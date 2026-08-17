package com.hamstrack.search;

import com.hamstrack.issue.service.ClassificationNames;

import java.util.Locale;

/**
 * The <strong>one</strong> canonical map-key function for every name→id resolution map
 * search builds and reads (HD-90). A key is
 * {@link ClassificationNames#normalize(String)} folded to lower case: NFC → drop
 * non-whitespace control/format characters → collapse whitespace and Unicode separator
 * runs to a single space → strip → {@code toLowerCase(Locale.ROOT)}.
 *
 * <p><strong>Why both sides, not just the operand.</strong> Labels, components,
 * versions and sprints run through {@code ClassificationNames.normalize} on write, so
 * their stored names are already canonical — but statuses, types and priorities come
 * from the admin catalog, which does <em>not</em> normalize. Normalizing only the HQL
 * operand would therefore turn a today-working {@code status = "In  Progress"} (a
 * stored double space) into a 422: a regression traded for a fix. Building the map keys
 * with the same function keeps a stored {@code "In  Progress"} reachable by both
 * {@code "In  Progress"} and {@code "In Progress"}, and a stored {@code "2.4.0"}
 * reachable by a copy-pasted {@code "2.4.0 "} or a non-breaking-space variant.
 *
 * <p>It delegates to {@link ClassificationNames} on purpose — that class documents the
 * spoofing defence the rules exist for, and a second copy of the regexes would
 * eventually drift from the write path.
 *
 * <p>Not for machine identifiers: custom-field {@code key}s and option ids are slugs
 * matched against a lexer {@code IDENT} token (which cannot contain whitespace), so
 * they keep a plain lower-case key — see {@code ResolutionContextFactory}.
 *
 * <p><strong>The empty key is a real bucket — always go through a guard.</strong>
 * A catalog row whose whole name is whitespace or format characters folds to
 * {@code ""}, so {@code ""} addresses that row (and merges every such row of the
 * caller's scope). Nothing reaches it today because both readers refuse a blank key
 * first — {@code HqlValueResolver.requireName} raises a field-anchored 422 and
 * {@code CustomFieldMeta.resolveOption} returns {@code null}. That guard, not the
 * data, is what keeps a whitespace-only operand from matching. A NEW reader of any
 * {@code *IdsByName} map must reject a blank key the same way rather than calling
 * {@code map.get(SearchNames.key(...))} directly.
 */
public final class SearchNames {

    private SearchNames() {}

    /** Canonical lookup key for a user-facing name; {@code null} → {@code ""}. */
    public static String key(String raw) {
        return ClassificationNames.normalize(raw).toLowerCase(Locale.ROOT);
    }
}
