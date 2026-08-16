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
 * <p>Live fields: {@code status type priority assignee reporter parent text
 * created updated due label component fixVersion affectsVersion} (HD-30 promoted the
 * former {@code label} stub from not-available to live; HD-31 added
 * {@code component}; HD-32 added the two version roles). Custom fields are not
 * registered here at all — they are
 * resolved per request from the caller's {@link ResolutionContext}. A field can also
 * be registered as a <em>known-but-not-available</em> stub
 * ({@link FieldDescriptor#available()} == false) so it yields a clear "not yet
 * queryable" semantic error instead of "unknown field" while staying hidden from
 * {@code /schema} (§5.4). Adding a field needs no grammar/parser change.
 *
 * <p>A field may carry <strong>aliases</strong> (extra keys pointing at the same
 * descriptor, e.g. {@code labels} → {@code label}); {@link #availableFields}
 * de-duplicates so {@code /schema} lists each field once.
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

        // ---- LABEL_REF (HD-30) ----
        // Many-valued, so it has no entityPath: the compiler emits a correlated
        // EXISTS over IssueLabel instead (§3.5). Nullable (IS [NOT] EMPTY = "has no
        // labels"), never sortable — an issue has a *set* of labels, so there is no
        // meaningful ORDER BY key; `ORDER BY label` is a 422 from HqlValidator.
        var label = new FieldDescriptor("label", FieldDataType.LABEL_REF, EQ_ONLY,
                true, true, false, null, "LABEL", List.of(), true);
        register(label);
        register("labels", label);   // plural alias, same descriptor

        // ---- component (HD-31) ----
        // Single-valued ToOne, so it reuses the plain ENUM_REF id-set path — no new
        // compiler branch, just `entityPath = "component.id"`. Nullable
        // (IS [NOT] EMPTY = "has no component") and, unlike label, SORTABLE: one
        // component per issue means `component.name` is a meaningful ORDER BY key
        // (§3.5). Names resolve across the caller's VISIBLE PROJECTS only, so two
        // projects may each own a "Billing" and both match.
        var component = new FieldDescriptor("component", FieldDataType.ENUM_REF, EQ_ONLY,
                true, true, true, "component.id", "COMPONENT", List.of(), true);
        register(component);
        register("components", component);   // plural alias, same descriptor

        // ---- VERSION_REF (HD-32) ----
        // Two fields over ONE join table, told apart by link_type. Many-valued like
        // label, so neither has an entityPath: the compiler emits a correlated EXISTS
        // over IssueVersionLink filtered by the role (§3.5). Nullable
        // (IS [NOT] EMPTY = "has no fix version" — the "unassigned work" query),
        // never sortable: an issue has a *set* of versions, so there is no meaningful
        // ORDER BY key and `ORDER BY fixVersion` is a 422 from HqlValidator.
        //
        // Names resolve across the caller's VISIBLE PROJECTS only, so two projects may
        // each ship a "2.4.0" and both match — exactly like component.
        //
        // The canonical names are camelCase for display (/schema, error messages);
        // the registry KEY is lowercased below, which is also what makes the spec's
        // `fixversion`/`affectsversion` aliases work without a second entry.
        register(new FieldDescriptor("fixVersion", FieldDataType.VERSION_REF, EQ_ONLY,
                true, true, false, null, "VERSION", List.of(), true));
        register(new FieldDescriptor("affectsVersion", FieldDataType.VERSION_REF, EQ_ONLY,
                true, true, false, null, "VERSION", List.of(), true));

        // ---- sprint (HD-22 §4.7) ----
        // A single-valued ToOne like `component`, so it reuses the plain ENUM_REF id-set
        // path — no new compiler branch, just `entityPath = "sprint.id"`. Nullable
        // (IS [NOT] EMPTY = "not in any sprint", i.e. the backlog). Deliberately NOT
        // sortable: sprint order across several projects has no common meaning, so
        // `ORDER BY sprint` is a 422 from HqlValidator.
        //
        // Names resolve across the caller's VISIBLE PROJECTS only, so two projects may
        // each run a "Sprint 7" and both match. COMPLETED sprints are excluded from name
        // resolution (years of history would flood the namespace); issues carrying one
        // still match by id.
        var sprint = new FieldDescriptor("sprint", FieldDataType.ENUM_REF, EQ_ONLY,
                true, true, false, "sprint.id", "SPRINT", List.of(), true);
        register(sprint);
        register("sprints", sprint);   // plural alias, same descriptor

        // ---- storyPoints (HD-22 §4.7) ----
        // The first NATIVE numeric field: a real NUMERIC column on issues, so it takes
        // ordered comparisons directly (unlike the custom-field NUMBER path, which
        // compares inside a JSONB EXISTS). Nullable — `IS EMPTY` means UNESTIMATED,
        // which is deliberately not the same statement as `= 0` — and SORTABLE, which is
        // the point ("show me the big ones first").
        var storyPoints = new FieldDescriptor("storyPoints", FieldDataType.NUMBER, ORDERED,
                false, true, true, "storyPoints", null, List.of(), true);
        register(storyPoints);
        register("points", storyPoints);   // short alias, same descriptor
    }

    /**
     * Register a descriptor under its own name. The key is <strong>lowercased</strong>
     * because {@link #find} lowercases its argument: a descriptor whose display name
     * carries capitals ({@code fixVersion}) would otherwise be unreachable. This is
     * also what makes the spec's all-lowercase aliases ({@code fixversion},
     * {@code affectsversion}) resolve without a separate entry — they ARE the key.
     */
    private void register(FieldDescriptor d) {
        byName.put(d.name().toLowerCase(Locale.ROOT), d);
    }

    /**
     * Register an additional lookup name for an existing descriptor (e.g. the plural
     * {@code labels} → {@code label}). Aliases are extra map entries pointing at the
     * SAME descriptor instance, so {@link #availableFields} de-duplicates them and
     * {@code /schema} still lists each field exactly once.
     *
     * <p>The alias is lowercased for the same reason the canonical name is: {@link #find}
     * always lowercases its argument, so an alias registered verbatim with a capital in
     * it would be permanently unreachable — and silently so, since it would still count
     * as "registered" for validation. Today's aliases are already lowercase; this makes
     * that a property of the method rather than of the call sites.
     */
    private void register(String alias, FieldDescriptor d) {
        byName.put(alias.toLowerCase(Locale.ROOT), d);
    }

    /** Case-insensitive lookup. Includes not-yet-available stubs. */
    public Optional<FieldDescriptor> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * Live, queryable fields only (drives {@code /schema}; hides not-available stubs).
     * De-duplicated by descriptor so an aliased field ({@code label}/{@code labels})
     * is listed once, under its canonical name.
     */
    public List<FieldDescriptor> availableFields() {
        return byName.values().stream().filter(FieldDescriptor::available).distinct().toList();
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
