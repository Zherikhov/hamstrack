package com.hamstrack.common.hibernate;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Registers Postgres JSONB helpers as HQL/Criteria-callable functions so the HQL
 * search compiler (HD-52) can express JSONB scalar extraction with bound parameters
 * and no raw SQL strings.
 *
 * <p>Postgres's built-in {@code jsonb_extract_path_text} requires at least one variadic
 * path argument, which a zero-arg Criteria {@code cb.function} call cannot supply, so we
 * wrap the {@code #>>} empty-path operator in named functions instead. Numeric/date
 * comparisons cast in-SQL (a Criteria {@code Expression.as(...)} does <em>not</em> emit a
 * SQL {@code CAST}, so {@code text >= numeric} fails without an explicit cast):
 *
 * <ul>
 *   <li>{@code jsonb_scalar_text(value)}    → {@code (value #>> '{}')} as text;</li>
 *   <li>{@code jsonb_scalar_numeric(value)} → numeric, or NULL when the value isn't a JSON number;</li>
 *   <li>{@code jsonb_scalar_date(value)}    → date, or NULL when the value isn't ISO-date-shaped.</li>
 * </ul>
 *
 * <p>The numeric/date casts are <em>total</em> — a non-castable value yields NULL rather than
 * raising {@code invalid input syntax}. This matters because a NUMBER/DATE comparison compiles to
 * an {@code EXISTS} correlated on {@code issue_id}, and the planner may evaluate the {@code ::numeric}
 * / {@code ::date} cast on sibling {@code issue_field_values} rows of OTHER fields on the same issue
 * (e.g. a TEXT field holding "prod") before the {@code field_id} filter prunes them — a same-level AND
 * gives no ordering guarantee. Casting a sibling text row would 500 the whole query. Guarding the cast
 * so a non-castable value returns NULL makes the comparison NULL (the row simply doesn't match), which
 * is the correct semantics — a non-numeric sibling row is not {@code >= 5}.
 *
 * <p>Registered via {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}.
 */
public class JsonbFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        var registry = functionContributions.getFunctionRegistry();
        var basicTypes = functionContributions.getTypeConfiguration().getBasicTypeRegistry();

        registry.patternDescriptorBuilder("jsonb_scalar_text", "(?1 #>> '{}')")
                .setInvariantType(basicTypes.resolve(StandardBasicTypes.STRING))
                .setExactArgumentCount(1)
                .register();

        // Total cast: only real JSON numbers are cast; anything else (text sibling rows,
        // arrays, etc.) yields NULL rather than raising invalid-input-syntax.
        registry.patternDescriptorBuilder("jsonb_scalar_numeric",
                        "(CASE WHEN jsonb_typeof(?1) = 'number' THEN (?1 #>> '{}')::numeric END)")
                .setInvariantType(basicTypes.resolve(StandardBasicTypes.BIG_DECIMAL))
                .setExactArgumentCount(1)
                .register();

        // Total cast: DATE values are JSON strings "YYYY-MM-DD" and a TEXT field is also a JSON
        // string, so jsonb_typeof can't tell them apart — guard on the ISO-date shape instead.
        // A TEXT sibling that isn't ISO-date-shaped yields NULL and can't 500.
        // NOTE: the residual, extremely-unlikely case is a TEXT value shaped exactly like an
        // invalid date (e.g. "2026-13-40") — that still passes the regex and would raise on ::date.
        // Acceptable given write-time DATE validation guarantees DATE rows are well-formed, and a
        // TEXT value with that exact shape is not a realistic input.
        registry.patternDescriptorBuilder("jsonb_scalar_date",
                        "(CASE WHEN (?1 #>> '{}') ~ '^\\d{4}-\\d{2}-\\d{2}$' THEN (?1 #>> '{}')::date END)")
                .setInvariantType(basicTypes.resolve(StandardBasicTypes.LOCAL_DATE))
                .setExactArgumentCount(1)
                .register();
    }
}
