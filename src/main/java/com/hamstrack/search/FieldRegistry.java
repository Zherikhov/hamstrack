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
 * <p><strong>The constructor below is the list of live fields</strong> — deliberately not
 * restated in prose here, because an enumeration in a javadoc goes stale one entry before
 * anybody notices and then misleads exactly the reader who trusted it. Custom fields are not
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

        // ---- project (HD-101) ----
        // Single-valued and NOT NULL, so it reuses the plain ENUM_REF id-set path — no new
        // compiler branch, just `entityPath = "project.id"`.
        //
        // Why it exists: search is workspace-scoped by construction (SearchScope ANDs
        // `project.id IN :visibleProjectIds` onto every compiled query), so before this field
        // there was no way to ask the most ordinary question a workspace with more than one
        // project has — "what is going on in THIS project". It also disambiguates the fields
        // whose names resolve ACROSS the visible projects: two projects may each ship a "2.4.0"
        // or run a "Sprint 1", and `project = "HD" AND fixVersion = "2.4.0"` is the only way to
        // say which one is meant.
        //
        // A `project` clause can only NARROW: it compiles inside the scope predicate, never
        // beside it, so `project != "HD"` means "in a visible project other than HD" and no
        // operand can name a project the caller could not already search (an unknown or
        // invisible one is a field-anchored 422 from HqlValueResolver — never a 404, never a
        // silent empty result that would confirm the project exists somewhere).
        //
        // <strong>No parsed term may ever be folded into SearchScope.</strong> Narrowing
        // `visibleProjectIds` to a named project would look like an optimisation and would make
        // the tenant boundary a function of query text — the one property SearchScope's javadoc
        // exists to protect. That holds for every term the language will ever gain; `project` is
        // simply the first one for which the shortcut is tempting, because the scope predicate
        // already restricts the same column. The term stays an ordinary predicate nested inside
        // the scope, which is what makes the guarantee structural rather than case-by-case.
        //
        // KEY ONLY, never name: `UNIQUE(workspace_id, key)` makes a key an identity, while
        // `projects.name` carries no uniqueness constraint at all — so a name operand would
        // resolve to a SET, and this field would join the category of ambiguous name-resolved
        // fields it was added to disambiguate. The human name still reaches the user, in the
        // suggestion label and in the "did you mean" hint on a miss — see
        // HqlValueResolver#resolveProject.
        //
        // nullable = FALSE: every issue has a project, so `project IS EMPTY` is a statement that
        // could never be true. It is refused by HqlValidator ("Field 'project' cannot be empty")
        // rather than silently matching nothing.
        //
        // sortable = TRUE, on `project.key`: an issue has exactly one project and a key is a
        // total order that means the same thing in every row — the same argument that makes
        // `component` sortable. (The reasons the non-sortable fields give are different ones:
        // `sprint` has no cross-project order, `label`/`fixVersion` are SETS per issue.) The
        // NOT NULL column also means no LEFT-join dance: an implicit inner join drops no rows.
        //
        // No plural alias. `labels`/`components`/`sprints` are plurals of many-valued or
        // arguably-plural things; an issue has one project. Adding an alias later is free,
        // removing one is not.
        //
        // DC and Cloud are identical here, deliberately and permanently: this is a query
        // language term over data both modes hold, and a term that existed in one mode only
        // would make saved-filter text non-portable across an export/import. It must not become
        // profile- or property-gated.
        //
        // <strong>A workspace that already keys a CUSTOM field `project` is affected by this
        // registration.</strong> A registered name is a reserved system name and outranks any
        // tenant's own field (FieldResolver), so in such a workspace `project = …` stops meaning
        // that custom field and starts meaning this one, and — the silent half — /schema drops
        // the tenant's field from the search vocabulary with no message anywhere, while it keeps
        // working everywhere else in the product. That is the intended precedence (the product's
        // vocabulary cannot be captured per-tenant) and the same class of event
        // RetiredFieldAliases documents from the other direction; AdminFieldService now refuses
        // to create NEW fields under a registered key, which stops the recurrence but is not
        // retroactive.
        register(new FieldDescriptor("project", FieldDataType.ENUM_REF, EQ_ONLY,
                true, false, true, "project.id", "PROJECT", List.of(), true));

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

        // ---- closed (HD-119) ----
        // The close date, made queryable. `issues.closed_at` has been on the issue response
        // since HD-57 and documented since HD-91, but the language had no name for it, so the
        // most ordinary reporting question there is — "what did we close last week" — could not
        // be written down even though the row already held the answer.
        //
        // CANONICAL NAME `closed`, not `closedAt`. The date fields name the EVENT rather than
        // the column, and every one of them is spelled `…At` in the JSON payload too — so
        // "an integrator types the name the payload showed them" does not distinguish this
        // field from its siblings: it applies to all of them, and the language already answered
        // it. A vocabulary that spelled one date field after its column and the rest after
        // their events would be unguessable in both directions, so /schema advertises
        // `closed`, and /schema is the list people copy from.
        //
        // The payload spelling still resolves, as an ALIAS — nobody should be punished for
        // typing the name they just read in a response. It is compatibility, not vocabulary:
        // availableFields() de-duplicates by descriptor, so /schema lists this field once,
        // under `closed`. `createdAt`/`updatedAt` deliberately get no matching alias in this
        // change: registering a name RESERVES it against every tenant's custom field of that
        // key (FieldResolver; runbook + detection SQL in docs/release-checklist.md), so each
        // name is a release-time decision carrying its own collision check — never a symmetry
        // tidy-up done in passing.
        //
        // TIMESTAMP / ORDERED / sortable, on the inclusive-day UTC window every timestamp
        // field uses (§6.3): `closed = "2026-08-01"` is that whole UTC day, `closed <=
        // "2026-08-01"` runs to its end, and now()/startOfWeek() mean here what they mean
        // everywhere else.
        //
        // nullable = TRUE, and this is the field's one real subtlety: `closed IS EMPTY` means
        // CURRENTLY OPEN. It does not mean "was never finished". IssueService stamps closed_at
        // when an issue enters a DONE-category status and CLEARS it when the issue leaves that
        // category, so a reopened issue is empty again and the date it used to carry is gone —
        // the column answers a question about the issue's current state. (`issues.started_at`
        // beside it is never cleared, for exactly the opposite reason.) A reader who takes
        // `IS EMPTY` for "never closed" gets a different answer on precisely the issues that
        // were reopened, so the property is stated here rather than left to be discovered.
        var closed = new FieldDescriptor("closed", FieldDataType.TIMESTAMP, ORDERED,
                false, true, true, "closedAt", "DATE", List.of("now()", "startOfWeek()"), true);
        register(closed);
        register("closedAt", closed);   // the response-payload spelling, same descriptor

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
