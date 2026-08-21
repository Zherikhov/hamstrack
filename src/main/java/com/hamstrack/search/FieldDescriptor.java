package com.hamstrack.search;

import com.hamstrack.search.parser.ast.ComparisonOp;

import java.util.List;
import java.util.Set;

/**
 * One queryable HQL field's declarative metadata (Advanced Search proposal §5). A
 * descriptor decouples the public language from DB column names and is the single
 * source of truth for: parse-time validation (allowed operators, nullability,
 * sortability), value resolution (via {@link #dataType()}), Criteria compilation
 * (via {@link #entityPath()}), and the {@code /schema} autocomplete endpoint.
 *
 * @param name          canonical lowercase HQL field name (registry key)
 * @param dataType      the value/resolution family (drives resolver + compiler)
 * @param operators     the {@link ComparisonOp}s legal in a {@code field OP value}
 *                      comparison (excludes IN / IS EMPTY, which are modelled below)
 * @param supportsIn    whether {@code field IN (…)} is legal
 * @param nullable      whether {@code field IS [NOT] EMPTY} is legal (null-bearing)
 * @param sortable      whether the field may appear in {@code ORDER BY}
 * @param entityPath    the JPA path from the {@code Issue} root the compiler binds
 *                      against (e.g. {@code "status.id"}); {@code null} for {@code text}
 *                      which has a bespoke predicate
 * @param valueSuggest  the {@code /schema} value-source token (STATUS/TYPE/PRIORITY/
 *                      USER/DATE/…), or {@code null} when there is no picklist
 * @param functions     zero-arg functions offered for this field in autocomplete
 *                      (e.g. {@code currentUser()}, {@code now()})
 * @param available     false for a name this registry has RESERVED but not yet made
 *                      queryable — it parses, and resolution 422s with a clear
 *                      "not yet queryable" message rather than "unknown field".
 *                      Registration is what reserves the name; availability only says
 *                      WHEN it starts answering. So a reserved name outranks a tenant's
 *                      own custom field of the same key even while unavailable — see
 *                      {@link FieldResolver} — which is what stops the meaning of every
 *                      stored filter using that key from flipping on the day it goes live
 */
public record FieldDescriptor(
        String name,
        FieldDataType dataType,
        Set<ComparisonOp> operators,
        boolean supportsIn,
        boolean nullable,
        boolean sortable,
        String entityPath,
        String valueSuggest,
        List<String> functions,
        boolean available
) {
    public FieldDescriptor {
        operators = operators == null ? Set.of() : Set.copyOf(operators);
        functions = functions == null ? List.of() : List.copyOf(functions);
    }

    public boolean allows(ComparisonOp op) {
        return operators.contains(op);
    }
}
