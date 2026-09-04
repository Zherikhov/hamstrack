package com.hamstrack.search;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Compatibility aliases for HQL field names that a release <em>retired</em> — a
 * key users may still have in a saved filter, mapped to the name that replaced it
 * (delivery-paths proposal §9.2, HD-107, HD-161).
 *
 * <p>The map below is the list; it is deliberately not restated as a count or an
 * enumeration in prose, because both go stale one entry before anybody notices. The
 * <em>rule</em> is what this javadoc states, and the rule is a category: <strong>every
 * key a migration archives is either aliased here or is itself a live
 * {@link FieldRegistry} name — there is no third outcome, and "nobody complained" is not
 * one.</strong> HD-161 exists because {@code fix_version} was left out silently: V10
 * archived it exactly as V11 archived {@code story_points}, one got an alias and the other
 * did not, and the gap surfaced by accident rather than by looking.
 *
 * <h2>What "retired" means here</h2>
 * V8/V9/V10/V11 each promoted a V1/V3-seeded global system {@code field_defs} placeholder
 * to a first-class native field and ARCHIVED the placeholder ({@code archived_at = NOW()},
 * never a delete). {@link ResolutionContextFactory} skips archived field defs, so from that
 * migration onward the old key resolves to nothing and every saved filter written against it
 * fails with "unknown field" the next time its owner runs it. The alias is what makes the
 * promotion invisible to those filters. No migration ever rewrites the stored text of
 * anybody's filter (§9.2) — the compatibility is at resolution time, permanently.
 *
 * <p>{@code RetiredFieldSweepTest} reads the archival statements straight out of the Flyway
 * migrations and fails when a retired key is neither aliased here nor a live registry name,
 * so the next retirement cannot repeat HD-161's silence. Adding a retired key without a
 * verdict is a failing build, not a discovery three releases later.
 *
 * <h2>The two mechanisms, and why only one of them is a promise</h2>
 * A retired key can survive on either of two mechanisms, and they are not equivalent:
 *
 * <ul>
 *   <li><strong>An entry in this table</strong> — compatibility, stated as such, consulted
 *       last, and safe for a tenant that owns the key (below).</li>
 *   <li><strong>A {@link FieldRegistry} name that happens to spell the retired key</strong>
 *       — {@code sprint} is the canonical name of a live field, and {@code labels}/
 *       {@code components} are ergonomic plural aliases of {@code label}/{@code component}
 *       that were registered for typing comfort, not for compatibility. A registry hit is
 *       load-bearing for old filters while being documented as an ergonomic nicety, which
 *       makes it a guarantee nobody wrote down and nobody can see breaking: deleting a
 *       plural alias reads as a cosmetic tidy-up and silently 422s every filter written
 *       before V8/V9.</li>
 * </ul>
 *
 * <p>So {@code labels} and {@code components} carry an entry here <em>as well</em>. It is
 * unreachable today — the registry answers first — and that is the point: it is the belt
 * under the braces, so the retirement stays compatible if the ergonomic plural is ever
 * removed, and removing that plural becomes a change of precedence rather than a change of
 * outcome — <strong>except for a workspace that already owns a custom field keyed
 * {@code labels}/{@code components}, for whom it is a silent change of MEANING.</strong>
 * Such a workspace is shadowed today (next paragraph): its filters answer 200 from the
 * native label/component links it never set on its own field. Delete the plural and the
 * same filters still answer 200 — now from that field. No error, no log line, no 422 to
 * notice; only the rows change. That is the one reader for whom "precedence, not outcome"
 * is false, and it is the whole reason this caveat is stated rather than rounded off.
 *
 * <p>Note the one asymmetry that plural registration causes, recorded rather than
 * fixed here: a name in the registry is RESERVED, so
 * {@code AdminFieldService.requireUnreservedKey} refuses a NEW custom field keyed
 * {@code labels}/{@code components} (409) and any workspace that already had one — creatable
 * before V8/V9 registered the plural, since that check is create-time and not retroactive — is
 * shadowed: {@code key = "…"} answers from the native field and {@code /search/schema} omits
 * their field entirely, with no error anywhere. Whereas {@code story_points} and
 * {@code fix_version}, which live only in this table, stay creatable and stay the tenant's own.
 * That difference is a property of registering a name, not of retiring one; see
 * {@link FieldResolver}, and {@code docs/release-checklist.md} → "Releases that register a new
 * HQL field name" for the runbook and the detection SQL that finds affected tenants.
 *
 * <p><strong>This is deliberately NOT a {@link FieldRegistry} entry, and the
 * precedence is the whole point.</strong> A registered name outranks any workspace's
 * own custom field, so registering a retired key there would permanently shadow the
 * custom field of every tenant that keys one that way, forever. The aliases are therefore
 * consulted <em>last</em>, after both normal resolution steps have missed:
 *
 * <pre>
 *   system field (FieldRegistry) → visible custom field (ResolutionContext)
 *                                → retired-key alias → "unknown field" (422)
 * </pre>
 *
 * <p>Consequences of that ordering, both intended: a tenant that keys its own
 * custom field with a retired key resolves to <em>its own field</em>, never to
 * the native column; and a workspace with no such custom field resolves the retired
 * key to its replacement, so the saved filter keeps working.
 *
 * <p><strong>That sequence is defined and enforced in {@link FieldResolver}</strong>
 * (HD-114) — this class only says which key maps to which canonical name. The ordering
 * rationale lives with the resolver because it is a property of resolution as a whole,
 * not of the alias table: any name lookup that derived its own order could put this step
 * in the wrong place, and one that asks the resolver cannot. Each entry added here widens
 * the surface of that rule by one name, which is why the tenant-owns-the-key case is pinned
 * per entry rather than once for the table.
 *
 * <p><strong>Caveat — that precedence is not purely a per-tenant decision.</strong>
 * {@code FieldDef.scopeWorkspaceId} is nullable, so a <em>global</em> field def is
 * reachable by every project of every workspace and enters
 * {@code ResolutionContext.customFieldsByKey} for all tenants at once. If an instance
 * admin ever creates a global custom field under a retired key, the alias stops
 * firing in <em>every</em> workspace simultaneously and that key changes meaning
 * everywhere, not in one tenant. No data crosses a workspace boundary
 * when that happens — values stay per-issue JSONB behind the scope predicate — but the
 * blast radius is the whole instance, so a global def that reuses a retired key must
 * be treated as a breaking change rather than a local one.
 *
 * <p>Aliases are compatibility, not vocabulary: they are never offered in
 * {@code /search/schema} suggestions (they are not registry entries, and {@code /schema}
 * lists registry entries), and no migration ever rewrites the stored text of anybody's
 * saved filter (§9.2).
 */
@Component
public class RetiredFieldAliases {

    /**
     * lowercase retired key → the canonical {@link FieldRegistry} name that replaced it.
     *
     * <p>Each entry names the migration that archived the placeholder, so a reader can go
     * from a key to the release that retired it without grepping. A {@link LinkedHashMap}
     * rather than {@code Map.of} only so that order is retirement order for anything that
     * iterates.
     */
    private static final Map<String, String> BY_RETIRED_KEY = new LinkedHashMap<>();

    static {
        // V8 — the `labels` MULTI_SELECT placeholder → the first-class label field.
        // Also reachable as a FieldRegistry plural alias today; see the class javadoc for
        // why the retirement does not lean on that.
        BY_RETIRED_KEY.put("labels", "label");
        // V9 — the `components` MULTI_SELECT placeholder → the first-class component field.
        // Same doubled mechanism as `labels`.
        BY_RETIRED_KEY.put("components", "component");
        // V10 — the `fix_version` SELECT placeholder → the native fixVersion field (HD-161:
        // this retirement shipped without its alias and broke pre-V10 saved filters).
        BY_RETIRED_KEY.put("fix_version", "fixVersion");
        // V11 — the `story_points` NUMBER placeholder → the native issues.story_points column.
        BY_RETIRED_KEY.put("story_points", "storyPoints");
        // V11 also archived the `sprint` SELECT placeholder. It gets NO entry, and needs none:
        // `sprint` is the canonical name of the live registry field that replaced it, so the
        // first resolution step already answers it. An alias would be unreachable by
        // construction — recorded here so its absence reads as a decision, not an omission.
    }

    /**
     * The canonical field name a retired key maps to, or empty when the name is not
     * a retired key. Case-insensitive, like every other HQL name lookup.
     *
     * <p>This step is only correct as the LAST one — see the class javadoc; the ordering
     * is a tenancy-safety property, not a style choice. Resolve names through
     * {@link FieldResolver}, which is where that ordering is applied, rather than calling
     * this and deciding where it fits.
     */
    public Optional<String> canonicalName(String retiredKey) {
        if (retiredKey == null) return Optional.empty();
        return Optional.ofNullable(BY_RETIRED_KEY.get(retiredKey.toLowerCase(Locale.ROOT)));
    }
}
