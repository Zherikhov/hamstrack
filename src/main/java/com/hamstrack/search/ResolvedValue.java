package com.hamstrack.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A parser {@link com.hamstrack.search.parser.ast.Value} after the resolver has
 * interpreted it against the target field and the workspace (Advanced Search
 * proposal §6). The compiler binds these as Criteria parameters — never as text.
 *
 * <p>Shape per field data type:
 * <ul>
 *   <li>{@link Ids} — a set of catalog / user / issue UUIDs (ENUM_REF names,
 *       USER_REF, ISSUE_REF, and {@code priority} equality all resolve to an id
 *       set; a name can map to several ids, so equality compiles to {@code IN});</li>
 *   <li>{@link PositionValue} — a priority's catalog {@code position}, for the
 *       ordered {@code >}/{@code <} comparisons on {@code priority} (§5.3);</li>
 *   <li>{@link DateBounds} — a date literal / function resolved to a UTC
 *       {@code [lower, upperExclusive)} window for timestamp fields, or a single
 *       {@code LocalDate} for the real {@code due} DATE column (§6.3);</li>
 *   <li>{@link TextTerm} — a text-match term (raw, ILIKE-escaped by the compiler).</li>
 * </ul>
 */
public sealed interface ResolvedValue
        permits ResolvedValue.Ids, ResolvedValue.PositionValue, ResolvedValue.NumberValue,
                ResolvedValue.DateBounds, ResolvedValue.TextTerm {

    /** One or more ids the value resolves to (catalog / user / issue). */
    record Ids(List<UUID> ids) implements ResolvedValue {
        public Ids {
            ids = List.copyOf(ids);
        }
    }

    /**
     * A native NUMERIC column operand — {@code storyPoints} (HD-22 §4.7), the first
     * system field of type {@link FieldDataType#NUMBER}. Distinct from
     * {@link PositionValue}, which is a {@code priority}'s catalog rank rather than a
     * value the user typed, and from the custom-field NUMBER path, which compares
     * inside a JSONB {@code EXISTS} instead of against a column.
     */
    record NumberValue(java.math.BigDecimal value) implements ResolvedValue {}

    /** A priority catalog {@code position} for ordered comparison. */
    record PositionValue(short position) implements ResolvedValue {}

    /**
     * A resolved date. For a {@code DATE} field ({@code due}) only {@link #date} is
     * set. For a {@code TIMESTAMP} field ({@code created}/{@code updated}) the
     * inclusive-day window is {@code [lowerInstant, upperInstantExclusive)} (§6.3).
     */
    record DateBounds(java.time.LocalDate date, Instant lowerInstant, Instant upperInstantExclusive)
            implements ResolvedValue {}

    /** A raw text-match term (the compiler lowercases + escapes {@code %}/{@code _}). */
    record TextTerm(String term) implements ResolvedValue {}
}
