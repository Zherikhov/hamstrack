package com.hamstrack.issue.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Server-side name normalization shared by every classification primitive of the
 * HD-6 epic — labels (HD-30), components (HD-31) and versions (HD-32) — and by every
 * other door that stores a user-supplied display name (sprints, saved filters). One
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
 * <h2>The length limit is measured AFTER normalization, and {@link #requireValidName} is
 * where every writing door measures it</h2>
 * NFC is not length-preserving in the shrinking direction only: a character on the
 * Unicode <em>composition exclusion</em> list decomposes and is never recomposed, so
 * U+0958 becomes two code points, U+FB2C three — 120 of them canonicalize to 240 or 360
 * characters. A {@code @Size} on the request record bounds the <em>raw</em> text and the
 * column bounds the <em>stored</em> text, so a door that normalizes between the two and
 * measures nothing lets a member drive the commit into SQLSTATE 22001 on demand (the
 * saved-filter door did exactly that until HD-297 — the HD-171 "derived value has no DTO
 * to annotate" shape, once more). The limit is per door (60 for labels, 80 for components,
 * 60 for versions and sprints, 120 for saved filters), and the door passes its own
 * {@code MAX_NAME_LENGTH} — an ADR-0017 repeated literal equal to its {@code @Size} and its
 * {@code @Column(length)}. {@code RequestFieldLengthBoundTest} enumerates every production
 * class that calls into this class (or {@link SearchNames}, or {@link Normalizer}) and
 * requires each writing one to refuse a name that only grows under NFC.
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

    /**
     * The one write-side gate: {@link #normalize} the name, refuse it when nothing is left
     * (whitespace, or only the invisible characters {@code @NotBlank} cannot see), refuse it
     * when the <em>normalized</em> form is longer than {@code maxLength}, and return the
     * canonical form to store. Both refusals are 400 and name the noun and, for the length,
     * the limit; when the raw text was within the limit and normalization pushed it over,
     * the message says so, because "at most 120 characters" is otherwise a lie to someone
     * who typed 120. Every door that stores a display name calls this rather than
     * measuring on its own — five copies of this method drifted once, and the fifth had no
     * length check at all.
     *
     * @param raw       the caller's text, {@code null} allowed
     * @param maxLength the door's post-normalization limit, equal to its column width
     * @param noun      what is being named, capitalised, for the message ("Label", "Filter")
     */
    public static String requireValidName(String raw, int maxLength, String noun) {
        String name = normalize(raw);
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, noun + " name must not be blank");
        }
        if (name.length() > maxLength) {
            String grew = raw.length() <= maxLength
                    ? " once normalized (Unicode normalization turned its " + raw.length()
                      + " characters into " + name.length() + ") — use a shorter name"
                    : "";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    noun + " name must be at most " + maxLength + " characters" + grew);
        }
        return name;
    }
}
