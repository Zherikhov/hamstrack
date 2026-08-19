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
 *   <li>{@code U+200B}–{@code U+200D} ZWSP/ZWNJ/ZWJ, {@code U+2060}–{@code U+2064} word
 *       joiner and the invisible math operators, {@code U+00AD} SOFT HYPHEN,
 *       {@code U+180E} MONGOLIAN VOWEL SEPARATOR (zero-width since Unicode 6.3) and
 *       {@code U+FEFF} ZWNBSP/BOM — zero-width, so two names that differ only by one are
 *       indistinguishable on screen while comparing unequal everywhere: a second "Team lead"
 *       in a role picker. {@code U+FEFF} is additionally a field separator to a surprising
 *       number of parsers. The word joiner's four immediate neighbours are listed for the
 *       same reason it is: an invisible character the class skips is a gap in the rule, not
 *       a nuance of it;</li>
 *   <li>{@code U+FFF9}–{@code U+FFFB} INTERLINEAR ANNOTATION ANCHOR/SEPARATOR/TERMINATOR —
 *       these delimit an annotated run, so a renderer that honours them can show one
 *       substring while the stored value holds another <em>entire</em> hidden run, not just
 *       a hidden character;</li>
 *   <li>{@code U+E0000}–{@code U+E007F} tag characters — the canonical text-smuggling
 *       block: {@code U+E0020}–{@code U+E007E} mirror printable ASCII one-for-one and render
 *       as nothing, so an arbitrary readable sentence can be appended to an innocent display
 *       name and travel invisibly through every store, log and email this application
 *       writes.</li>
 * </ul>
 *
 * <p><strong>Not a normalisation or a confusable rule</strong>, deliberately. This rejects
 * characters that are <em>invisible or reordering</em> — a bounded, enumerable set with no
 * legitimate use in a display name, and the enumeration above is that set in full. It does
 * not attempt homoglyphs (Cyrillic <em>а</em> for Latin <em>a</em>), which are visible,
 * unbounded, and legitimate in most of the world's names; that is a confusable-detection
 * problem and does not belong in a regex.
 *
 * <p><strong>Enumerated on purpose, not {@code \p{Cf}}.</strong> Negating the whole format
 * category is shorter and wrong: {@code Cf} also contains {@code U+0600}–{@code U+0605},
 * {@code U+06DD} and {@code U+070F}, which occur in ordinary Arabic text, and
 * {@code U+110BD} in Kaithi. A validator built on it rejects real names, which is a worse
 * failure than the one it prevents. The set here is finite and each row is listed because
 * something concrete breaks on it.
 *
 * <p><strong>The tag block needs {@code \x{E0000}-\x{E007F}}, not a {@code \\u} escape.</strong>
 * It is supplementary-plane, and {@code \\uXXXX} cannot name a codepoint above
 * {@code U+FFFF}: the plausible-looking {@code \\uE000-\\uE07F} compiles cleanly and guards
 * the Private Use Area instead, i.e. it protects nothing and rejects something legitimate.
 * {@code DisplayTextTest} asserts the rejection against the <em>compiled</em> pattern for
 * exactly this reason — the syntax being present is not evidence that it took.
 *
 * <p><strong>The two constants below are byte-identical apart from the TAB/LF/CR
 * admission</strong> and must stay that way; a row added to one and missed in the other is
 * drift that has already happened once here and is caught now by a codepoint sweep in
 * {@code DisplayTextTest}.
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
            "[^\\p{Cntrl}"
            + "\\u0085\\u00AD\\u061C\\u180E\\u200B-\\u200F\\u2028\\u2029\\u202A-\\u202E"
            + "\\u2060-\\u2064\\u2066-\\u2069\\uFEFF\\uFFF9-\\uFFFB\\x{E0000}-\\x{E007F}]*";

    /**
     * {@link #SINGLE_LINE} minus the three whitespace controls a person can actually type:
     * TAB ({@code U+0009}), LF ({@code U+000A}) and CR ({@code U+000D}). Everything
     * invisible or reordering stays rejected. Empty matches, so pair it with
     * {@code @NotBlank} where the field is required.
     *
     * <p>Exists because "no control characters" and "one line" are not the same rule, and
     * {@link #SINGLE_LINE} silently enforces both: {@code \p{Cntrl}} is
     * {@code [\x00-\x1F\x7F]}, which contains {@code \n}. Applied to a field the UI renders
     * as a textarea — a role's description — pressing Enter mid-sentence answered
     * {@code 400 "Description must not contain control characters"}, i.e. the paragraph
     * field refused paragraphs. Use this for prose; use {@link #SINGLE_LINE} for anything
     * that is a <em>label</em> (a name, a title), where a line break is not typed on purpose
     * and where the value gets embedded in single-line contexts — a log line, a CSV cell, an
     * email subject — that a newline splits in two.
     *
     * <p><strong>NEL, LINE SEPARATOR and PARAGRAPH SEPARATOR are deliberately still out</strong>,
     * even though they are "line breaks" too. No keyboard emits them, and they are line
     * terminators to <em>some</em> readers and not others — precisely the asymmetry that lets
     * one hide a second line inside what a parser thinks is one field. The three re-admitted
     * characters are the ones every reader agrees about.
     *
     * <p>Note the class is spelled as explicit ranges around the three exceptions rather
     * than as {@code \p{Cntrl}} minus something: character-class subtraction is not Java
     * regex syntax, and the near-miss spellings ({@code [[^\p{Cntrl}]\n]}) read as unions to
     * anyone skimming. Still one negated class with a single {@code *}, so it stays linear
     * with nothing to backtrack into. Everything after the leading control ranges is the
     * same text as in {@link #SINGLE_LINE}, character for character — edit the two together
     * or the sweep in {@code DisplayTextTest} will say so.
     */
    public static final String MULTI_LINE =
            "[^\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F"
            + "\\u0085\\u00AD\\u061C\\u180E\\u200B-\\u200F\\u2028\\u2029\\u202A-\\u202E"
            + "\\u2060-\\u2064\\u2066-\\u2069\\uFEFF\\uFFF9-\\uFFFB\\x{E0000}-\\x{E007F}]*";

    private DisplayText() {
    }
}
