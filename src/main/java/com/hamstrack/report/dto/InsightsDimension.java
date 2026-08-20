package com.hamstrack.report.dto;

import java.util.Locale;
import java.util.Optional;

/**
 * What the Insights panel groups by — the x axis ({@code slice}) and, optionally, the colour
 * ({@code segment}). Exactly the list in reports-proposal §2.6, and the same list serves both
 * roles because "group by A, coloured by B" is symmetric.
 *
 * <h2>Assignee is here, and is refused by velocity — that is not an inconsistency</h2>
 * §2.5 refuses a per-person velocity on evidence, and {@code VelocityRefusalTest} enforces the
 * refusal structurally. This is a different object. Velocity is a <em>published metric</em>: it has
 * a stable URL, it is quoted in a ceremony, and a per-person column in it becomes a comparison
 * whether or not anyone meant it to. Insights is an <em>ad-hoc query somebody typed</em> — its
 * dataset is whatever HQL is in the box, it is not addressable as "the team's number", and the
 * same person could get the same breakdown today by running eight searches and writing the totals
 * down. Refusing it here would protect nothing and remove the panel's most obvious use ("who is
 * everything sitting with?"). The line is <strong>published metric vs. ad-hoc query</strong>, and
 * it is the line to defend if a later slice proposes an assignee <em>report</em>.
 *
 * @param hqlField the HQL field name a click on this bucket narrows by, or {@code null} when the
 *                 dimension has no HQL vocabulary at all. Today that is {@link #PROJECT}: it is a
 *                 perfectly good grouping (issues carry a project and {@code SearchScope} already
 *                 restricts which) but HQL has no {@code project} field, so a project bucket
 *                 carries no fragment and the panel cannot make it clickable. Adding one is its
 *                 own ticket — it is a {@code FieldRegistry} entry plus name resolution, and it
 *                 sits inside the scope predicate rather than beside it, so it can only narrow.
 * @param multiValued whether one issue can land in more than one bucket of this dimension. True
 *                 only for {@link #LABEL}. It is published on the response because it changes what
 *                 the numbers mean: with a multi-valued dimension the buckets <strong>sum to more
 *                 than {@code meta.basedOnIssues}</strong>, and a stacked bar's segments can add
 *                 up to more than the bar. That is arithmetic, not a bug — but it is exactly the
 *                 "these numbers don't match what I expected" complaint the whole proposal is
 *                 organised around (§1.5), so the response states it rather than letting the
 *                 reader discover it.
 */
public enum InsightsDimension {

    STATUS("status", false),
    TYPE("type", false),
    PRIORITY("priority", false),
    /** Nullable — unassigned issues form the {@code null} bucket, narrowed by {@code assignee IS EMPTY}. */
    ASSIGNEE("assignee", false),
    /** Nullable — issues with no component form the {@code null} bucket. */
    COMPONENT("component", false),
    /**
     * The one many-valued dimension: an issue with three labels appears in three buckets. Issues
     * with no labels form the {@code null} bucket ({@code label IS EMPTY}).
     */
    LABEL("label", true),
    /** Nullable — backlog issues (in no sprint) form the {@code null} bucket. */
    SPRINT("sprint", false),
    /** Never null, and never clickable — see {@code hqlField}. */
    PROJECT(null, false);

    private final String hqlField;
    private final boolean multiValued;

    InsightsDimension(String hqlField, boolean multiValued) {
        this.hqlField = hqlField;
        this.multiValued = multiValued;
    }

    /** The HQL field a bucket of this dimension narrows by, or empty when there is none. */
    public Optional<String> hqlField() {
        return Optional.ofNullable(hqlField);
    }

    /** Whether one issue can appear in several buckets — see the class javadoc. */
    public boolean multiValued() {
        return multiValued;
    }

    /** Case-insensitive lookup of a request token; empty when the token names nothing. */
    public static Optional<InsightsDimension> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(token.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
