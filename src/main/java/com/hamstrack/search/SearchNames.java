package com.hamstrack.search;

import com.hamstrack.issue.service.ClassificationNames;

import java.util.Locale;

/**
 * The canonical cleaning pair for every operand search reads out of an HQL string
 * literal (HD-90, HD-121). Both methods run {@link ClassificationNames#normalize(String)}
 * — NFC → drop non-whitespace control/format characters → collapse whitespace and
 * Unicode separator runs to a single space → strip — and they differ in exactly one
 * step, the case fold:
 *
 * <ul>
 *   <li>{@link #key(String)} folds with {@code Locale.ROOT}. It is the <strong>one</strong>
 *       map-key function for every name→id map search builds and reads, applied to the
 *       stored name and to the operand alike.</li>
 *   <li>{@link #canonical(String)} does not fold. It is for an operand whose match is
 *       decided somewhere this class does not own — in SQL, by a parser, by an exact
 *       comparison — and for anything an error message quotes back.</li>
 * </ul>
 *
 * <p><strong>Why both sides of a map, not just the operand.</strong> Labels, components,
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
 * <h2>Which of the two, and why it is not "is this a machine identifier?"</h2>
 * The discriminator is <strong>where the match happens</strong>. An earlier version of
 * this note excluded "machine identifiers" as a class and justified the exclusion with a
 * property that only some of them have — being a lexer {@code IDENT} token, which cannot
 * contain whitespace. That holds for a custom-field {@code key} (an HQL field name is an
 * {@code IDENT}; {@code HqlParser} rejects anything else) and for nothing else it named:
 * an option id, a project key and an issue key all arrive as string <em>literals</em>,
 * which carry whatever a paste carries. Option ids had in fact been keyed with
 * {@link #key} since HD-90 while that sentence said they were not, and the issue key was
 * still on a bare {@code trim()} until HD-121. So:
 *
 * <ul>
 *   <li>Arrives as an {@code IDENT} token → clean nothing. The grammar has already
 *       excluded every separator, so no pipeline could change the outcome.</li>
 *   <li>A string literal looked up in a map keyed by {@link #key} → {@link #key}. Folding
 *       a slug-shaped operand is a harmless no-op, which is what lets one map hold an
 *       option's human label and its raw id at once.</li>
 *   <li>A string literal matched anywhere <em>else</em> → {@link #canonical}. The fold has
 *       no counterpart on the other side of such a match, so it cannot help it; all it can
 *       do is change the spelling an error echoes back at the person who typed it. See
 *       {@link HqlParentResolver} for the worked instance.</li>
 * </ul>
 *
 * <p><strong>The empty key is a real bucket — always go through a guard.</strong>
 * A catalog row whose whole name is whitespace or format characters folds to
 * {@code ""}, so {@code ""} addresses that row (and merges every such row of the
 * caller's scope). Nothing reaches it because a reader of one of those maps refuses a
 * blank key before it looks anything up — as a field-anchored 422 where the caller
 * typed the operand, or as a dropped suggestion where the code built it (see
 * {@code HqlValueResolver.requireName} and {@code CustomFieldMeta.resolveOption} for
 * the two shapes). That guard, not the data, is what keeps a whitespace-only operand
 * from matching, so a reader of any {@code *IdsByName} map must reject a blank key the
 * same way rather than calling {@code map.get(SearchNames.key(...))} directly.
 */
public final class SearchNames {

    private SearchNames() {}

    /** Canonical lookup key for a user-facing name; {@code null} → {@code ""}. */
    public static String key(String raw) {
        return canonical(raw).toLowerCase(Locale.ROOT);
    }

    /**
     * The same canonical cleaning as {@link #key} <em>without</em> the case fold;
     * {@code null} → {@code ""}. Use it when the operand's match is settled elsewhere
     * (a {@code upper(...) = upper(...)} predicate, {@code LocalDate.parse}, an exact
     * comparison) or when the result will be quoted back to the caller: folding cannot
     * make such a match succeed, and it does change what the caller is shown.
     */
    public static String canonical(String raw) {
        return ClassificationNames.normalize(raw);
    }
}
