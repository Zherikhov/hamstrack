package com.hamstrack.common.util;

/**
 * Bean-Validation patterns for <strong>display strings</strong> — user-supplied text that is
 * stored and later rendered somewhere this application does not control.
 *
 * <p>Exists because the obvious spelling is wrong in a way nothing notices.
 * {@code @Pattern(regexp = "[^\\p{Cntrl}]*")} looks like "no control characters" and is
 * not: Java's {@code \p{Cntrl}} is <strong>ASCII-only</strong> ({@code [\x00-\x1F\x7F]})
 * unless {@code UNICODE_CHARACTER_CLASS} is set, which Bean Validation does not set. So the
 * characters that actually break a downstream consumer walk straight through it:
 * <ul>
 *   <li>{@code U+0085} NEL — a line terminator to most log and CSV readers;</li>
 *   <li>{@code U+2028}/{@code U+2029} LINE and PARAGRAPH SEPARATOR — line terminators in
 *       JavaScript source and in many text pipelines;</li>
 *   <li>{@code U+061C} ARABIC LETTER MARK, {@code U+200E}/{@code U+200F} and
 *       {@code U+202A}–{@code U+202E}, {@code U+2066}–{@code U+2069} — bidi marks,
 *       overrides and isolates, which reorder the <em>rest of the line</em> around them: the
 *       trick that makes one string render as another in an email, an audit table or a code
 *       review. {@code U+061C} is the whole family's third mark, standardised alongside the
 *       isolates and with exactly {@code U+200E}/{@code U+200F}'s semantics — it was simply
 *       missed, and a reordering control the class does not list is a class that does not do
 *       what it says;</li>
 *   <li>{@code U+200B}–{@code U+200D} ZWSP/ZWNJ/ZWJ, {@code U+2060} word joiner and
 *       {@code U+FEFF} ZWNBSP/BOM — zero-width, so two names that differ only by one are
 *       indistinguishable on screen while comparing unequal everywhere: a second "Team lead"
 *       in a role picker. {@code U+FEFF} is additionally a field separator to a surprising
 *       number of parsers.</li>
 * </ul>
 *
 * <p><strong>Not a normalisation or a confusable rule</strong>, deliberately. This rejects
 * characters that are <em>invisible or reordering</em> — a bounded, enumerable set with no
 * legitimate use in a display name. It does not attempt homoglyphs (Cyrillic <em>а</em> for
 * Latin <em>a</em>), which are visible, unbounded, and legitimate in most of the world's
 * names; that is a confusable-detection problem and does not belong in a regex.
 *
 * <p>One negated character class with a single {@code *}, so it is linear and has nothing to
 * backtrack into — a validation pattern applied to attacker-supplied text must not be able
 * to become the denial of service it was added to prevent.
 *
 * <p>Note that the backslashes in the pattern are doubled. A single-backslash unicode
 * escape in Java <em>source</em> is decoded by the compiler before the string literal even
 * exists — including inside comments — so the single form would put a literal NEL into the
 * pattern instead of an escape for one, and the class would be matching the very character
 * it exists to reject.
 */
public final class DisplayText {

    /**
     * Any text with no control, line-separating, bidi-controlling or zero-width-invisible
     * character in it. Empty matches, so pair it with {@code @NotBlank} where the field is
     * required.
     */
    public static final String SINGLE_LINE =
            "[^\\p{Cntrl}\\u0085\\u061C\\u200B-\\u200F\\u2028\\u2029\\u202A-\\u202E"
            + "\\u2060\\u2066-\\u2069\\uFEFF]*";

    private DisplayText() {
    }
}
