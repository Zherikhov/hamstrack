package com.hamstrack.search;

import com.hamstrack.search.parser.ast.ComparisonOp;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Central catalog of queryable HQL fields (Advanced Search proposal §5, locked
 * decision §0.2). Maps an HQL field name (case-insensitively, normalized to its
 * canonical lowercase key) to a {@link FieldDescriptor} that drives parse
 * validation, value resolution, Criteria compilation and the {@code /schema}
 * autocomplete endpoint. One place to register a queryable field.
 *
 * <p>MVP live fields: {@code status type priority assignee reporter parent text
 * created updated due}. {@code label} and custom fields are registered as
 * <em>known-but-not-available</em> stubs ({@link FieldDescriptor#available()} ==
 * false) so they yield a clear "not yet queryable" semantic error instead of
 * "unknown field", but are hidden from {@code /schema} so autocomplete never
 * suggests a dead field (§5.4). Adding a field later needs no grammar/parser change.
 */
@Component
public class FieldRegistry {

    private static final Set<ComparisonOp> EQ_ONLY = Set.of(ComparisonOp.EQ, ComparisonOp.NEQ);
    private static final Set<ComparisonOp> ORDERED = Set.of(
            ComparisonOp.EQ, ComparisonOp.NEQ,
            ComparisonOp.GT, ComparisonOp.LT, ComparisonOp.GTE, ComparisonOp.LTE);
    private static final Set<ComparisonOp> MATCH_ONLY = Set.of(ComparisonOp.MATCH);

    private final Map<String, FieldDescriptor> byName = new LinkedHashMap<>();

    public FieldRegistry() {
        // ---- ENUM_REF (name → catalog-id set reachable by visible projects) ----
        register(new FieldDescriptor("status", FieldDataType.ENUM_REF, EQ_ONLY,
                true, false, true, "status.id", "STATUS", List.of(), true));
        register(new FieldDescriptor("type", FieldDataType.ENUM_REF, EQ_ONLY,
                true, false, true, "type.id", "TYPE", List.of(), true));
        // priority also supports ordered comparison via catalog `position` (§5.3),
        // handled specially by the compiler (=/!=/IN on id, >/</>=/<= on position).
        register(new FieldDescriptor("priority", FieldDataType.ENUM_REF, ORDERED,
                true, false, true, "priority.id", "PRIORITY", List.of(), true));

        // ---- USER_REF ----
        register(new FieldDescriptor("assignee", FieldDataType.USER_REF, EQ_ONLY,
                true, true, true, "assignee.id", "USER", List.of("currentUser()"), true));
        register(new FieldDescriptor("reporter", FieldDataType.USER_REF, EQ_ONLY,
                true, false, true, "reporter.id", "USER", List.of("currentUser()"), true));

        // ---- ISSUE_REF ----
        register(new FieldDescriptor("parent", FieldDataType.ISSUE_REF, EQ_ONLY,
                false, true, true, "parent.id", null, List.of(), true));

        // ---- TEXT ----
        register(new FieldDescriptor("text", FieldDataType.TEXT, MATCH_ONLY,
                false, false, false, null, null, List.of(), true));

        // ---- DATE / TIMESTAMP ----
        register(new FieldDescriptor("created", FieldDataType.TIMESTAMP, ORDERED,
                false, false, true, "createdAt", "DATE", List.of("now()", "startOfWeek()"), true));
        register(new FieldDescriptor("updated", FieldDataType.TIMESTAMP, ORDERED,
                false, false, true, "updatedAt", "DATE", List.of("now()", "startOfWeek()"), true));
        register(new FieldDescriptor("due", FieldDataType.DATE, ORDERED,
                false, true, true, "dueDate", "DATE", List.of("now()", "startOfWeek()"), true));

        // ---- reserved, not yet available (§5.4): parse OK → "not yet queryable" 422 ----
        register(new FieldDescriptor("label", FieldDataType.ENUM_REF, EQ_ONLY,
                true, false, false, null, null, List.of(), false));
    }

    private void register(FieldDescriptor d) {
        byName.put(d.name(), d);
    }

    /** Case-insensitive lookup. Includes not-yet-available stubs. */
    public Optional<FieldDescriptor> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Live, queryable fields only (drives {@code /schema}; hides not-available stubs). */
    public List<FieldDescriptor> availableFields() {
        return byName.values().stream().filter(FieldDescriptor::available).toList();
    }

    /**
     * The registered field name (available or not) nearest the given typo by
     * Levenshtein distance, for the "did you mean …" suggestion in the unknown-field
     * error (§7.2). Only suggests within a small edit distance.
     */
    public Optional<String> suggest(String typo) {
        if (typo == null || typo.isBlank()) return Optional.empty();
        String lower = typo.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String candidate : byName.keySet()) {
            int d = levenshtein(lower, candidate);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        // Only propose when the typo is close (≤ half the field length, min 2).
        int threshold = Math.max(2, best == null ? 0 : best.length() / 2);
        return bestDist <= threshold ? Optional.ofNullable(best) : Optional.empty();
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
