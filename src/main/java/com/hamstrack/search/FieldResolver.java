package com.hamstrack.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * <strong>The definition of HQL field-name precedence</strong> — the one place that turns a
 * name written by a user into the thing that name means (HD-114). Anything that has to know
 * what {@code status}, {@code story_points} or {@code my_field} refers to asks here; nothing
 * re-derives the order for itself.
 *
 * <pre>
 *   system field (FieldRegistry) → visible custom field (ResolutionContext)
 *                                → retired-key alias (RetiredFieldAliases)
 *                                → "unknown field" (422, with a "did you mean …" hint)
 * </pre>
 *
 * <p><strong>The order is a tenancy-safety property, not a style choice.</strong> Each step
 * exists because the step after it would otherwise shadow something that is not the shadowing
 * party's to take:
 *
 * <ul>
 *   <li><strong>Registry before custom fields.</strong> A registered name is a reserved system
 *       name, so no workspace's own field def can capture the vocabulary the product ships.</li>
 *   <li><strong>A tenant's own custom field before a retired alias.</strong> This is why a
 *       retired key must NOT be registered in {@link FieldRegistry}: registering
 *       {@code story_points} there would permanently shadow the {@code story_points} custom
 *       field of every workspace that has one, in every tenant, forever. Consulted last, the
 *       alias can only ever fill a name nobody else claimed — a tenant that keys its own field
 *       {@code story_points} resolves to <em>its own field</em>, and a workspace with no such
 *       field resolves the retired key to the native {@code storyPoints} so its saved filters
 *       keep working. See {@link RetiredFieldAliases} for what the alias is and the one way that
 *       decision stops being per-tenant.</li>
 * </ul>
 *
 * <h2>Why this is one component rather than a convention</h2>
 * Getting the order backwards does not fail loudly: it silently changes what other people's
 * saved queries mean. And an order that each consumer derives for itself is an order that holds
 * only for the consumers that exist on the day it is written — HD-107 taught the alias step to
 * the semantic check, the {@code ORDER BY} path had its own copy of the sequence and did not
 * learn it, and the result was a query accepted by validation and then rejected as an unknown
 * field while compiling. A resolution that lives in one component cannot go partially adopted:
 * a name resolves the same way for every consumer, in every entry point, including the ones
 * added after this sentence.
 *
 * <p>{@link Resolved} is shaped so a caller cannot re-derive the precedence by accident. It says
 * <em>which kind</em> of field the name turned out to be and nothing about how that was decided,
 * so there is no descriptor-plus-a-boolean pair for a caller to recombine in its own order, and
 * an exhaustive {@code switch} over it will not compile if a future kind is added and ignored.
 *
 * <h2>Errors belong here too</h2>
 * "Unknown field" and "not yet queryable" are answers to the question this class owns, so they
 * are thrown here rather than returned for each caller to phrase. That keeps the Levenshtein
 * "Did you mean …?" hint on every path a name can arrive by — before HD-114 the hint depended on
 * which check happened to reject the name first, and the same typo produced two different
 * messages.
 *
 * <h2>A registered-but-unavailable field is a system field (HD-114)</h2>
 * {@link FieldDescriptor#available()} {@code == false} marks a name that is reserved but not yet
 * queryable ({@code label} was one until HD-30). Such a name resolves as a <em>system</em> name
 * and 422s with "not yet queryable"; it deliberately does NOT fall through to the custom-field or
 * alias steps.
 *
 * <p>That decision resolves a divergence the two pre-HD-114 copies of this logic had, in which
 * the semantic check reported "not yet queryable" while the compilation path treated the same
 * name as not-a-system-field and let it fall through. The reason to keep the strict reading:
 * being registered is what makes a name reserved, and availability is only a statement about
 * <em>when</em> it becomes queryable — so letting an unavailable entry fall through would let a
 * tenant's custom field (or an alias) capture a name the product has already claimed and is about
 * to ship. That is the shadowing hazard above, pointed the other way, and it would go live at the
 * moment the field is enabled: every query written against the stand-in would silently change
 * meaning. A reserved name that answers "not yet" is the honest reading, and it makes enabling a
 * field a behavioural no-op for names that already resolved.
 *
 * <p><strong>The reading is not this class's private business.</strong> A surface that only
 * <em>lists</em> names asks {@link FieldRegistry} directly — "what names exist" is an ordinary
 * vocabulary question and carries none of the alias step's ordering hazard (see
 * {@code FieldResolutionOwnershipTest}) — but it has to read a registered name as claimed
 * whatever availability says, exactly as here. Read loosely, such a surface publishes a key that
 * every query against it then refuses: the same divergence, one surface further out.
 *
 * <p>Nothing in {@link FieldRegistry} is unavailable today, so both readings agree on live data —
 * which is precisely why the divergence survived unnoticed and why the decision is pinned by
 * {@code FieldResolutionParityTest} rather than left to whichever consumer is read first.
 */
@Component
@RequiredArgsConstructor
public class FieldResolver {

    private final FieldRegistry registry;
    private final RetiredFieldAliases retiredAliases;

    /**
     * What an HQL name turned out to refer to. Deliberately a closed set of <em>kinds</em>
     * rather than the ingredients a caller would need to re-decide: there is no "and is it
     * custom?" flag beside a descriptor, because that pair is the shape that lets a consumer
     * apply its own order.
     */
    public sealed interface Resolved {

        /** A field the product ships — a {@link FieldRegistry} entry, reached directly or via a retired-key alias. */
        record SystemField(FieldDescriptor descriptor) implements Resolved {}

        /** A custom field (M2 {@code field_defs}) reachable by the caller's visible projects. */
        record CustomField(CustomFieldMeta meta) implements Resolved {}
    }

    /**
     * Resolve an HQL field name for the given request context, following the precedence in the
     * class javadoc.
     *
     * <p>A name that resolves to nothing is a {@link HqlSemanticException} (422), anchored on the
     * name as the caller wrote it and carrying the "did you mean …" hint — a custom field the
     * caller cannot see is simply not in {@code ctx}, so it is indistinguishable from a typo and
     * its existence is never leaked. A name that is registered but not yet queryable is a 422 of
     * its own, anchored on the <em>canonical</em> registry name so an aliased or differently-cased
     * spelling still reports the field the product knows.
     */
    public Resolved resolve(String name, ResolutionContext ctx) {
        var registered = registry.find(name);
        if (registered.isPresent()) {
            return new Resolved.SystemField(requireAvailable(registered.get()));
        }
        // The tenant's own field wins over any alias — see the class javadoc.
        var custom = ctx.customField(name);
        if (custom.isPresent()) {
            return new Resolved.CustomField(custom.get());
        }
        var aliased = retiredAliases.canonicalName(name).flatMap(registry::find);
        if (aliased.isPresent()) {
            return new Resolved.SystemField(requireAvailable(aliased.get()));
        }
        String suggestion = registry.suggest(name).map(s -> " Did you mean '" + s + "'?").orElse("");
        throw new HqlSemanticException("Unknown field '" + name + "'." + suggestion, name);
    }

    private FieldDescriptor requireAvailable(FieldDescriptor f) {
        if (!f.available()) {
            throw new HqlSemanticException("Field '" + f.name() + "' is not yet queryable", f.name());
        }
        return f;
    }
}
