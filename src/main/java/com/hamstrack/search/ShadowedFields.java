package com.hamstrack.search;

import com.hamstrack.issue.entity.FieldDef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * <strong>The one answer to "has the product's own vocabulary taken this key, and under which
 * name"</strong> (HD-275 §5). A {@link FieldRegistry} entry reserves a search name against every
 * tenant's custom field of that key, retroactively and forever ({@link FieldResolver} consults the
 * registry <em>before</em> the caller's own fields), so a {@code field_defs} row created under a
 * key the registry later claimed keeps working everywhere in the product <em>except</em> search,
 * where the name means the built-in field and {@code /schema} used to omit the tenant's silently.
 *
 * <p>This is a component and not a convention for the same reason {@link FieldResolver} is: an
 * answer each consumer derives for itself is an answer that holds only for the consumers that
 * existed the day it was written. Everything that asks — {@code /search/schema},
 * {@code AdminFieldResponse}, the rename permission, the create-time refusal and the startup
 * scan — asks here.
 *
 * <h2>Two predicates, deliberately different — do not collapse them</h2>
 * They differ on exactly one row shape (archived), and that row shape is not hypothetical: V3
 * seeds global system placeholders keyed {@code labels}, {@code sprint} and {@code components},
 * all three registry-claimed, which V8/V11/V9 archive. A surface that treated those as shadowed
 * would make <em>every Hamstrack instance in existence</em> warn about its own seed data on every
 * boot, and the signal would be dead on arrival. So:
 *
 * <ul>
 *   <li>{@link #claimedBy(String)} — <strong>archive-blind and availability-blind.</strong>
 *       Registration is what claims a name; {@link FieldDescriptor#available()} says only
 *       <em>when</em> it starts answering, so a reserved-but-not-yet-queryable entry claims its
 *       key just as firmly. Aliases are ordinary registry entries, so {@code labels},
 *       {@code components}, {@code closedat}, {@code sprints}, {@code points} and
 *       {@code fixversion} are claimed keys too — correct, because the shadowing is caused by the
 *       <em>lookup</em> and not by the canonical spelling. This drives
 *       {@code AdminFieldResponse.shadowedBy} and the rename permission, both of which must hold
 *       for an <em>archived</em> row as well: an archived row is out of resolution entirely, so
 *       the invariant licensing a rename holds even harder there, and renaming-then-unarchiving is
 *       the ordinary recovery path for a field somebody archived <em>because</em> it had stopped
 *       working.</li>
 *   <li>{@link #shadowing(FieldDef)} — {@code claimedBy} <strong>and the row is live</strong>.
 *       This drives every warning surface, because a warning about a row that is out of
 *       resolution is noise.</li>
 * </ul>
 *
 * <p>The name reported is always the registry's <strong>canonical</strong> one, so a key of
 * {@code labels} reports {@code label}: that is the name whose data actually answers the query,
 * which is what a reader needs to be told.
 */
@Component
@RequiredArgsConstructor
public class ShadowedFields {

    private final FieldRegistry registry;

    /**
     * The canonical built-in search name that has claimed this key, or empty.
     *
     * <p>Archive-blind and availability-blind by design — see the class javadoc. Case-insensitive,
     * because {@link FieldRegistry#find} lowercases.
     *
     * @param key a custom field's machine key (null is simply unclaimed)
     */
    public Optional<String> claimedBy(String key) {
        return registry.find(key).map(FieldDescriptor::name);
    }

    /**
     * The canonical built-in search name that is <em>actively shadowing</em> this field
     * definition, or empty — {@link #claimedBy} plus "and the definition is live".
     *
     * <p>An archived definition is never shadowed: {@code ResolutionContextFactory} skips archived
     * defs, so nothing resolves to it under any name and there is nothing to warn about.
     */
    public Optional<String> shadowing(FieldDef field) {
        if (field == null || field.getArchivedAt() != null) return Optional.empty();
        return claimedBy(field.getKey());
    }
}
