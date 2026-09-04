package com.hamstrack.common.util;

import java.util.regex.Pattern;

/**
 * The one definition of "is this string a colour?". <strong>A write path in this product that
 * accepts a user-chosen colour spells its rule from here</strong> — the DTO {@code @Pattern}s from
 * {@link #REGEX} or {@link #SIX_DIGIT_REGEX}, the imperative service belts from {@link #isValid} —
 * so a caller who gets the shape wrong is answered the same sentence wherever they were standing.
 *
 * <p><strong>The shape and the sentence live together on purpose.</strong> A rule stated in two
 * places drifts in one of them, and this class has already watched it happen twice: the option
 * check was added a release after the label check, and the label DTOs kept an inline copy of the
 * regex with a wording of their own for long enough that the sentence here was dead code on the
 * very path it was written for (HD-176 review, Low 2). Nothing here is referenced "for tidiness" —
 * a copy of the expression is a copy of the decision.
 *
 * <p><strong>Two shapes, and the second one is a column width rather than a taste.</strong>
 * {@link #REGEX} accepts the 8-digit alpha form because {@code labels.color} is {@code VARCHAR(9)}
 * and stores it; {@link #SIX_DIGIT_REGEX} refuses it because the taxonomy colour columns
 * ({@code statuses}, {@code priorities}, {@code issue_types}) are {@code VARCHAR(7)} and physically
 * cannot hold nine characters. That is the whole difference, and it is stated so the narrower
 * constant is not "unified" away by a later reader who reads it as an oversight:
 * {@code ddl-auto=validate} does <em>not</em> compare column lengths (it compares JDBC type codes
 * only), so widening one of those three DTOs to {@link #REGEX} boots perfectly clean and fails at
 * INSERT. Widening the shape is a migration first and an annotation second.
 *
 * <p><strong>The status code deliberately does not live here.</strong> A body-anchored field
 * answers 400 because the constraint is declared on the DTO; a colour buried in a {@code JsonNode}
 * {@code config} answers 422 because every refusal on the admin-field path is 422. That is a
 * property of the endpoint, not of the format — two statuses over one shape is correct; two
 * sentences over one shape is what this class exists to prevent.
 *
 * <p><strong>This is a FORMAT check and nothing more.</strong> It does not, and must not, look at
 * contrast: a stored colour is an identity hue and the readable foreground is derived from it at
 * render time (ADR-0027, HD-176 decision 2), so {@code #FFFF00} is a perfectly legal answer here
 * and a {@code PUT} carrying it must still answer 200. The reason a format check exists at all is
 * that a stored colour is re-served to the SPA and reaches a {@code style} attribute, and an
 * arbitrary string arriving there is not a colour problem.
 */
public final class ColorFormat {

    private ColorFormat() {}

    /**
     * {@code #RRGGBB} or {@code #RRGGBBAA}, as a string so a DTO can name it in
     * {@code @Pattern(regexp = ColorFormat.REGEX)}. Use it wherever the target column is at least
     * {@code VARCHAR(9)}; see {@link #SIX_DIGIT_REGEX} for the ones that are not.
     */
    public static final String REGEX = "^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$";

    /** {@link #REGEX}, compiled — the imperative half, for values no annotation can reach. */
    public static final Pattern PATTERN = Pattern.compile(REGEX);

    /**
     * The refusal sentence for {@link #REGEX} — it names the shape it wants rather than only
     * reporting that something was wrong, which is the difference between a message a caller can
     * act on and one they cannot. Carried by the DTO annotations too, so the field-error map and
     * the service's {@code detail} read identically.
     */
    public static final String MESSAGE = "Color must be #RRGGBB or #RRGGBBAA";

    /**
     * {@code #RRGGBB} only — for a colour whose column is {@code VARCHAR(7)} and cannot hold the
     * alpha form. Narrower than {@link #REGEX} for a reason recorded on the class, not by accident.
     */
    public static final String SIX_DIGIT_REGEX = "^#[0-9A-Fa-f]{6}$";

    /** The refusal sentence for {@link #SIX_DIGIT_REGEX}; same contract as {@link #MESSAGE}. */
    public static final String SIX_DIGIT_MESSAGE = "Color must be #RRGGBB";

    /** {@code false} for {@code null}, for a blank string and for anything that is not the shape. */
    public static boolean isValid(String raw) {
        return raw != null && PATTERN.matcher(raw).matches();
    }
}
