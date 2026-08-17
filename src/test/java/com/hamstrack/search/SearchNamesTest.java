package com.hamstrack.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link SearchNames} (no Spring, no DB) — the key function HD-90
 * put on <em>both</em> sides of every name→id lookup in search.
 *
 * <p>The integration suite ({@code SearchNameCanonicalizationTest}) proves the behaviour
 * end to end; this one pins the contract itself, because the contract is what a future
 * "simplify this" change would break. In particular a re-implementation as
 * {@code raw.trim().toLowerCase()} — which looks equivalent and passes every ASCII
 * example — fails {@link #separatorsThatAreNotWhitespaceFoldTooSoTrimIsNotEnough()} and
 * {@link #innerSeparatorRunsCollapseSoTheAdminCatalogsDoubleSpaceStaysReachable()}.
 */
class SearchNamesTest {

    /** U+00A0 NO-BREAK SPACE: {@code \p{Z}}, not {@code \s} — {@code trim()} keeps it. */
    private static final String NBSP = "\u00A0";

    @Test
    void nullAndBlankFoldToTheEmptyKeyWhichCallersMustGuard() {
        assertEquals("", SearchNames.key(null));
        assertEquals("", SearchNames.key(""));
        assertEquals("", SearchNames.key("   "));
        assertEquals("", SearchNames.key(NBSP + NBSP));
        // \p{Cf} format characters are invisible but NOT whitespace: String.isBlank()
        // returns false for this, which is exactly why the readers must test the KEY.
        assertTrue(!"\u200B\uFEFF\u202A".isBlank(), "premise: these are not 'blank' to Java");
        assertEquals("", SearchNames.key("\u200B\uFEFF\u202A"));
    }

    @Test
    void theKeyIsCaseFoldedAndTrimmed() {
        assertEquals("2.4.0", SearchNames.key("2.4.0"));
        assertEquals("2.4.0", SearchNames.key(" 2.4.0 "));
        assertEquals("in progress", SearchNames.key("In Progress"));
        assertEquals("in progress", SearchNames.key("IN PROGRESS"));
    }

    /**
     * The reported HD-90 symptom, reduced to one line: a copy-pasted name differs from
     * the stored one only by separators that {@code trim()} does not remove.
     */
    @Test
    void separatorsThatAreNotWhitespaceFoldTooSoTrimIsNotEnough() {
        assertEquals(SearchNames.key("2.4.0"), SearchNames.key("2.4.0" + NBSP));
        assertEquals(SearchNames.key("2.4.0"), SearchNames.key(NBSP + "2.4.0"));
        // the premise that makes this test worth having
        assertNotEquals("2.4.0", ("2.4.0" + NBSP).trim(),
                "premise: trim() does NOT strip U+00A0, so a trim()-shaped fix misses this");
        // U+2007 FIGURE SPACE / U+202F NARROW NBSP / U+3000 IDEOGRAPHIC SPACE, same class
        assertEquals(SearchNames.key("2.4.0"), SearchNames.key("2.4.0\u2007"));
        assertEquals(SearchNames.key("2.4.0"), SearchNames.key("2.4.0\u202F"));
        assertEquals(SearchNames.key("2.4.0"), SearchNames.key("2.4.0\u3000"));
    }

    /**
     * The regression half of the fix. The admin catalog stores names verbatim, so
     * {@code "In  Progress"} (two spaces) really exists — and because the MAP is keyed
     * with this same function, both spellings address the same bucket. A key function
     * that only stripped the ends would leave those two in different buckets and turn a
     * working saved filter into a 422.
     */
    @Test
    void innerSeparatorRunsCollapseSoTheAdminCatalogsDoubleSpaceStaysReachable() {
        assertEquals(SearchNames.key("In Progress"), SearchNames.key("In  Progress"));
        assertEquals(SearchNames.key("In Progress"), SearchNames.key("In" + NBSP + "Progress"));
        assertEquals(SearchNames.key("In Progress"), SearchNames.key("In \t Progress"));
        assertNotEquals("in progress", "In  Progress".trim().toLowerCase(java.util.Locale.ROOT),
                "premise: trim().toLowerCase() keeps the inner double space");
    }

    /** Distinct names must stay distinct — folding must not merge unrelated rows. */
    @Test
    void differentNamesKeepDifferentKeys() {
        assertNotEquals(SearchNames.key("2.4.0"), SearchNames.key("2.4.01"));
        assertNotEquals(SearchNames.key("In Progress"), SearchNames.key("InProgress"),
                "collapsing a separator run is not the same as deleting the separator");
        assertNotEquals(SearchNames.key("Billing"), SearchNames.key("Billing Core"));
    }
}
