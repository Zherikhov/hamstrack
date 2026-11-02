package com.hamstrack.common.observability;

import java.util.regex.Pattern;

/**
 * <strong>The normalisation every value a row supplied passes through on its way into a log
 * record</strong> (HD-341).
 *
 * <p>One method today, and it is here rather than beside either of its callers because it now has
 * two: {@code ShadowedFieldStartupScan} and {@code AdminFieldService}. A copy in each is the shape
 * that drifted five email folds apart in this codebase, and the next record that carries foreign
 * text will want the same call.
 *
 * <h2>What the rule is</h2>
 * A log record that promises anything to a machine — a record whose prefix is listed in
 * {@code docs/observability.md} — is built only from values Hamstrack minted. A value it did not
 * mint (a {@code varchar} read back from a row, a request value, a library message) travels in an
 * SLF4J key-value pair, never interpolated into the message. The complement is half the contract:
 * a record whose prefix is <em>not</em> listed promises nothing, its message is prose, and no
 * extractor may key on it. Sealed by {@code ForgeableLogRecordTest}.
 */
public final class LogValues {

    private LogValues() {
    }

    /**
     * <strong>Line terminators out of any value a row supplies</strong>, applied where the value is
     * attached to the record.
     *
     * <p><strong>Why this survived the move to key-value pairs</strong>, which is the obvious
     * question to ask of it: because the escaping that makes a key-value pair safe belongs to the
     * JSON encoder and not to the key-value mechanism. MEASURED 2026-11-01 against logback 1.5.34
     * and Boot 4.1.0 — {@code StructuredLogEncoder(format=logstash)} escapes {@code "}, {@code \}
     * and a newline inside a member value, so a row-supplied value cannot close its member or open
     * a second one; logback's own {@code %kvp} conversion word escapes <strong>nothing</strong>
     * and renders {@code field.nl="first<newline>second"} as two physical lines. Both shipped
     * profiles are JSON, so no deployment depends on this — but an operator may put {@code %kvp}
     * on a console pattern, and a newline there would split the record again.
     *
     * <p><strong>And the JSON encoder does not escape all of them.</strong> MEASURED 2026-11-02 on
     * the codepoints of the encoded member: {@code \n}, {@code \r}, {@code "}, {@code \} and NEL
     * (U+0085) come out escaped, while <strong>U+2028 and U+2029 are emitted raw</strong> into the
     * JSON string. That is legal JSON and the record stays one line to every ASCII-terminator
     * reader, so it is not the forgery — but it is a second live reason this method runs before the
     * value is attached rather than being trusted away. The reading is pinned by
     * {@code LogRecordsTest}.
     *
     * <p><strong>And it is not made redundant by the DTO.</strong> {@code UpsertFieldRequest}
     * bounds the display name to {@link com.hamstrack.common.util.DisplayText#SINGLE_LINE} and the
     * key to {@code [a-z0-9_]}. Be exact about what that closes, because the obvious sentence
     * overstates it: {@code SINGLE_LINE} refuses line terminators and invisible or reordering
     * characters, and <em>permits</em> {@code '}, {@code "}, {@code (}, {@code )} and {@code =}
     * (MEASURED 2026-11-02 against the compiled pattern). So the <em>delimiter</em> forgery this
     * method's record was vulnerable to was reachable through the live {@code PATCH …/fields/{id}}
     * door all along, by a taxonomy admin at any scope editing the display name of a row that was
     * already shadowed; only the <em>line-terminator</em> variant was ever a console-door question.
     * The undoored routes are additional reach, not the only reach: the affected rows also arrive
     * by a migration, a seeder, direct SQL, or the console itself before that annotation shipped,
     * and neither column carries a CHECK constraint (MEASURED 2026-11-01 against
     * {@code pg_constraint}: {@code field_defs.key varchar(50)} and
     * {@code field_defs.name varchar(100)}, unconstrained). "Bounded at every minting door" is a
     * statement about doors, never about a column. Nothing rewrites an existing row, so no later
     * validation can reach it: the normalisation belongs at the sink, where every source passes.
     *
     * <p>A space rather than a removal, so a fragment cannot be glued onto the text around it.
     * <strong>One terminator becomes one space, which is not the same as the length being
     * unchanged</strong>: {@code \R} matches CRLF as a single unit, so a CRLF pair collapses to one
     * space and the value is one character shorter. MEASURED 2026-11-01, lengths before and after
     * over {@code "a" + terminator + "b"} — LF 3→3, <strong>CRLF 4→3</strong>, CR 3→3, NEL 3→3,
     * U+2028 3→3, U+2029 3→3, VT 3→3, FF 3→3. That is the behaviour wanted (a CRLF is one line
     * break, not two), and it is written down because the obvious sentence — "nothing else
     * changed" — is false for the commonest Windows value this will ever see. Minted values — a
     * UUID, an enum constant, a name from {@code FieldRegistry} — are not passed through this and
     * do not need to be.
     *
     * <p><strong>{@code CsvWriter.oneLine} is a look-alike and is deliberately not this method.</strong>
     * It flattens {@code \r} and {@code \n} only, and maps {@code null} to the empty string,
     * because its sink is a {@code #} comment line in a CSV where a reader ends a record at CR/LF
     * and at nothing else. Merging the two would either widen the CSV fold for no reason or narrow
     * this one, which is the wrong direction: a log shipper's idea of a line is not a CSV
     * parser's. Noted here so the next reader does not unify them by their names.
     *
     * @param value any text, {@code null} included
     * @return the same text with every Unicode linebreak replaced by one space, or {@code null}
     */
    public static String oneLine(String value) {
        return value == null ? null : LINE_TERMINATORS.matcher(value).replaceAll(" ");
    }

    /**
     * Any Unicode linebreak: {@code \R} matches CRLF as one unit as well as LF, CR, NEL and the
     * line/paragraph separators, so no shipper's idea of "a line" survives a value a row supplied.
     */
    private static final Pattern LINE_TERMINATORS = Pattern.compile("\\R");
}
