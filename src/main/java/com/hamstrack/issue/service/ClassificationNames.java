package com.hamstrack.issue.service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Server-side name normalization shared by every classification primitive of the
 * HD-6 epic — labels (HD-30), components (HD-31) and versions (HD-32). One
 * implementation on purpose: the rules below are a display-spoofing defence, and two
 * copies would eventually drift.
 *
 * <p>The pipeline is NFC → drop non-whitespace control/format characters → collapse
 * whitespace <em>and</em> Unicode separator runs to a single space → {@code strip()}.
 *
 * <p><strong>{@code \p{Cf}} (format) matters as much as {@code \p{Cc}} (control):</strong>
 * the bidi overrides (U+202A–202E, U+2066–2069) and the zero-width characters
 * (U+200B/200E, U+FEFF) are invisible, so without stripping them a member could
 * create a name that <em>renders</em> identically to an existing one while occupying
 * a different unique slot — spoofing in every picker and filter.
 *
 * <p><strong>The separator class {@code \p{Z}}</strong> is the same attack through a
 * different code-point class and must be folded too: {@code \s} and
 * {@code String.strip()} do not treat U+00A0/U+2007/U+202F/U+3000 as whitespace, so a
 * name whose separator is one of those would otherwise survive both the collapse and
 * the strip and take a <em>second</em> unique slot next to the plain-space one — and
 * a leading U+00A0 would even sneak past the blank check. Hence <strong>collapse
 * BEFORE strip</strong>: the separators are folded to plain U+0020 first, so the
 * strip only ever has ASCII spaces left to remove.
 *
 * <p>Callers enforce their own length limit <em>after</em> normalization (the limit
 * is per-primitive: 60 for labels, 80 for components).
 */
public final class ClassificationNames {

    private ClassificationNames() {}

    // PRECOMPILED on purpose (HD-90 follow-up). String.replaceAll recompiles its Pattern
    // on every call, and this pipeline is no longer only a write-path concern: search
    // keys every name→id map with SearchNames.key, so it runs once per catalog row on
    // every /search, /schema and /suggest. Measured at 50k names, two String.replaceAll
    // calls cost ~61ms vs ~43ms precompiled — a per-request tax multiplied by the label/
    // component/version caps. The regexes and their order are UNCHANGED.
    /**
     * Control (Cc) and format (Cf) characters that are NOT whitespace. The whitespace
     * ones (tab/newline/…) survive so {@link #SEPARATOR_RUN} turns them into a space
     * instead of silently gluing two words together.
     */
    private static final Pattern INVISIBLES = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\s]]");

    /** Whitespace and Unicode-separator runs, collapsed to a single plain space. */
    private static final Pattern SEPARATOR_RUN = Pattern.compile("[\\s\\p{Z}]+");

    /** Normalize a user-supplied display name; {@code null} → {@code ""}. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String stripped = INVISIBLES.matcher(nfc).replaceAll("");
        return SEPARATOR_RUN.matcher(stripped).replaceAll(" ").strip();
    }
}
