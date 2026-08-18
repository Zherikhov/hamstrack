package com.hamstrack.common.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <strong>The enumeration in {@link DisplayText} is the whole rule</strong>, so it is the
 * thing worth testing directly.
 *
 * <p>Both constants are a single negated character class: everything the class does is
 * decided by which rows are listed in it. Its javadoc claims the rejected set is "a bounded,
 * enumerable set" of invisible or reordering characters — a claim that is only true if the
 * enumeration is complete, and since S4 it was not. Five families walked straight through:
 * SOFT HYPHEN, MONGOLIAN VOWEL SEPARATOR, the invisible math operators next door to the
 * already-listed word joiner, the interlinear annotation trio, and the tag block — the
 * canonical text-smuggling channel, where a whole readable sentence can be encoded as
 * invisible characters appended to a name.
 *
 * <p>Three properties, each of which failed at least once:
 * <ul>
 *   <li><strong>every enumerated codepoint is actually rejected</strong>, by <em>both</em>
 *       constants. A row listed in one and missed in the other is the drift this class has
 *       already had once;</li>
 *   <li><strong>the tag block is supplementary-plane</strong> ({@code U+E0000}–{@code U+E007F}),
 *       so {@code \\uXXXX} cannot express it — a {@code \\uE000-\\uE07F} typo compiles, looks
 *       right, and silently guards the Private Use Area instead. Asserted against the
 *       compiled pattern, not against the source text;</li>
 *   <li><strong>the legitimate format characters still pass.</strong> The tempting shortcut
 *       here is to negate {@code \\p{Cf}} wholesale, which would also reject
 *       {@code U+0600}–{@code U+0605}, {@code U+06DD}, {@code U+070F} and {@code U+110BD} —
 *       ordinary characters in Arabic and Kaithi text, i.e. a validator that rejects real
 *       names. Explicit enumeration is the design; this test is what keeps it honest.</li>
 * </ul>
 *
 * <p>Plain JUnit, no Spring: this is a property of two string constants, and a test that
 * needs a database to state it is a test nobody runs while editing the regex.
 */
class DisplayTextTest {

    private static final Pattern SINGLE = Pattern.compile(DisplayText.SINGLE_LINE);
    private static final Pattern MULTI = Pattern.compile(DisplayText.MULTI_LINE);

    /** The five families that were missing from the enumeration, as inclusive ranges. */
    private static final Map<String, int[]> NEWLY_ENUMERATED = new LinkedHashMap<>();

    static {
        NEWLY_ENUMERATED.put("SOFT HYPHEN", new int[] {0x00AD, 0x00AD});
        NEWLY_ENUMERATED.put("MONGOLIAN VOWEL SEPARATOR", new int[] {0x180E, 0x180E});
        NEWLY_ENUMERATED.put("invisible math operators", new int[] {0x2061, 0x2064});
        NEWLY_ENUMERATED.put("interlinear annotation", new int[] {0xFFF9, 0xFFFB});
        NEWLY_ENUMERATED.put("tag characters", new int[] {0xE0000, 0xE007F});
    }

    private static String display(int cp) {
        return "Team lead" + new String(Character.toChars(cp));
    }

    private static String name(int cp) {
        return String.format("U+%04X", cp);
    }

    // ================================================= the enumeration, both constants

    @Test
    void singleLineRejectsEveryNewlyEnumeratedRange() {
        NEWLY_ENUMERATED.forEach((family, range) -> {
            for (int cp = range[0]; cp <= range[1]; cp++) {
                assertFalse(SINGLE.matcher(display(cp)).matches(),
                        "SINGLE_LINE must reject " + name(cp) + " (" + family + "): it is invisible, "
                        + "so two display names differing only by it are indistinguishable on screen "
                        + "while comparing unequal everywhere");
            }
        });
    }

    @Test
    void multiLineRejectsEveryNewlyEnumeratedRange() {
        NEWLY_ENUMERATED.forEach((family, range) -> {
            for (int cp = range[0]; cp <= range[1]; cp++) {
                assertFalse(MULTI.matcher(display(cp)).matches(),
                        "MULTI_LINE must reject " + name(cp) + " (" + family + "): it admits TAB, LF "
                        + "and CR and NOTHING else — an invisible character is no more welcome in "
                        + "prose than in a label");
            }
        });
    }

    /**
     * The two constants have drifted apart before, which is what a review caught. Rather
     * than compare the source strings (they legitimately differ at the front), this sweeps
     * every codepoint and asserts the verdicts agree everywhere except on the three
     * whitespace controls {@code MULTI_LINE} exists to admit.
     */
    @Test
    void theTwoConstantsDifferOnlyByTabLfAndCr() {
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                continue; // an unpaired surrogate is not a character anyone can submit
            }
            String s = new String(Character.toChars(cp));
            boolean single = SINGLE.matcher(s).matches();
            boolean multi = MULTI.matcher(s).matches();
            if (cp == '\t' || cp == '\n' || cp == '\r') {
                assertFalse(single, name(cp) + " must stay out of SINGLE_LINE — a label gets embedded "
                        + "in log lines, CSV cells and email subjects, which a newline splits in two");
                assertTrue(multi, name(cp) + " is the whole reason MULTI_LINE exists");
            } else {
                assertTrue(single == multi, "SINGLE_LINE and MULTI_LINE disagree on " + name(cp)
                        + " (single=" + single + ", multi=" + multi + "). The two classes must be "
                        + "byte-identical apart from admitting TAB/LF/CR; a row added to one and "
                        + "missed in the other is exactly the drift a review already caught once");
            }
        }
    }

    // ================================================= the supplementary-plane trap

    /**
     * {@code U+E0000}–{@code U+E007F} is above the BMP, so a {@code \\uXXXX} class cannot
     * express it and a plausible-looking {@code \\uE000-\\uE07F} would guard the Private
     * Use Area instead. This asks the compiled pattern, so the syntax cannot merely look
     * correct.
     */
    @Test
    void theTagBlockIsRejectedAsAnActualSupplementaryRange() {
        int tagLatinSmallA = 0xE0061;  // TAG LATIN SMALL LETTER A
        assertTrue(Character.isSupplementaryCodePoint(tagLatinSmallA),
                "if this ever stops being supplementary the test below proves nothing");
        assertTrue(new String(Character.toChars(tagLatinSmallA)).length() == 2,
                "a tag character is a surrogate pair in a Java String — the class must match it as "
                + "one codepoint, not as two lone surrogates");

        for (int cp : new int[] {0xE0000, 0xE0001, 0xE0020, 0xE0061, 0xE007F}) {
            assertFalse(SINGLE.matcher(display(cp)).matches(),
                    "SINGLE_LINE must reject tag character " + name(cp));
            assertFalse(MULTI.matcher(display(cp)).matches(),
                    "MULTI_LINE must reject tag character " + name(cp));
        }

        // the actual attack: a readable sentence smuggled invisibly onto an innocent name
        StringBuilder smuggled = new StringBuilder("Alice");
        for (char c : "grant me admin".toCharArray()) {
            smuggled.appendCodePoint(0xE0000 + c);
        }
        assertFalse(SINGLE.matcher(smuggled).matches(),
                "a tag-encoded payload appended to a display name renders as plain Alice and "
                + "must not validate");
        assertFalse(MULTI.matcher(smuggled).matches(),
                "the same payload is no safer in a description field");

        // and the neighbouring Private Use Area, which a - typo would guard
        // instead, is NOT what this range is about
        assertTrue(SINGLE.matcher(display(0xE000)).matches(),
                "U+E000 is Private Use, not a tag character — rejecting it would mean the range was "
                + "spelled with BMP escapes, i.e. the supplementary escape never took");
    }

    // ================================================= what must keep passing

    /**
     * The reason this class enumerates instead of negating {@code \p{Cf}}: these are format
     * characters that occur in ordinary Arabic and Kaithi text.
     */
    @Test
    void legitimateFormatCharactersStillPass() {
        for (int cp : new int[] {0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605, 0x06DD, 0x070F, 0x110BD}) {
            assertTrue(SINGLE.matcher(display(cp)).matches(),
                    name(cp) + " is legitimate in Arabic/Kaithi text — SINGLE_LINE must accept it. "
                    + "A blanket Cf negation would reject it and the validator would be turning "
                    + "away real names");
            assertTrue(MULTI.matcher(display(cp)).matches(),
                    name(cp) + " must equally survive MULTI_LINE");
        }
    }

    @Test
    void ordinaryTextStillValidates() {
        for (String ok : new String[] {"", "Team lead", "Проект «Альфа»", "Ünïcodé — dash",
                "日本語のプロジェクト", "a-b_c.d/e (f) [g] {h} 100%"}) {
            assertTrue(SINGLE.matcher(ok).matches(), "SINGLE_LINE must accept: " + ok);
            assertTrue(MULTI.matcher(ok).matches(), "MULTI_LINE must accept: " + ok);
        }
        assertTrue(MULTI.matcher("first line\nsecond line\twith a tab\r\n").matches(),
                "MULTI_LINE is what a textarea-backed field uses");
    }

    /** The families that were already enumerated before this hardening must stay rejected. */
    @Test
    void theOriginalEnumerationIsUntouched() {
        for (int cp : new int[] {0x0000, 0x001F, 0x007F, 0x0085, 0x061C, 0x200B, 0x200C, 0x200D,
                0x200E, 0x200F, 0x2028, 0x2029, 0x202A, 0x202E, 0x2060, 0x2066, 0x2069, 0xFEFF}) {
            assertFalse(SINGLE.matcher(display(cp)).matches(),
                    "SINGLE_LINE regression on the pre-existing row " + name(cp));
            assertFalse(MULTI.matcher(display(cp)).matches(),
                    "MULTI_LINE regression on the pre-existing row " + name(cp));
        }
    }
}
