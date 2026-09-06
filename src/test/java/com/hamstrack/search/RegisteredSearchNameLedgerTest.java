package com.hamstrack.search;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>Registering a search name is a deliberate, release-note-worthy act — not an additive
 * change</strong> (HD-275 §9.2).
 *
 * <p>A {@link FieldRegistry} entry <em>reserves</em> its name against every tenant's custom field
 * of that key, retroactively and forever: {@link FieldResolver} consults the registry before the
 * caller's own fields, so from that release on {@code key = "…"} answers from the built-in field
 * and {@code /schema} stops offering the tenant's. Nothing about adding a line to a constructor
 * looks like that, which is the entire problem this ledger exists to solve: it makes the addition
 * fail the build until the author writes the name down here, and the failure message is the
 * checklist rather than a diff.
 *
 * <p>This is the third acceptance criterion of HD-275 and the only one that <em>prevents</em> a
 * collision rather than reporting one that already exists. The other two halves — the startup scan
 * and {@code /search/schema} — derive their watch set from {@link FieldRegistry#claimedKeys()}
 * itself, so they need no edit at all when a name is added; that asymmetry is deliberate and is
 * step 3 of the message below, which exists to stop a future author "wiring up" something that is
 * already a property.
 *
 * <p>A plain unit test: no Spring context, no database. Mirrors {@code RetiredFieldSweepTest}'s
 * shape, including the part that matters most — {@link #theComparisonRefusesAnExtraName()} and
 * {@link #theComparisonRefusesAMissingName()} call the comparison directly with synthetic sets,
 * because <strong>a guard nobody has watched fail is a belief.</strong>
 */
class RegisteredSearchNameLedgerTest {

    /**
     * <strong>Every name {@link FieldRegistry} claims</strong> — canonical names and aliases alike,
     * lowercased, available or not. Aliases are in here because a key is shadowed by whatever the
     * <em>lookup</em> answers: {@code labels} reserves its key exactly as firmly as {@code label}
     * does, and it is precisely the plural aliases that caused this ticket.
     *
     * <p>Grouped by the field they name so a reader can see at a glance which entries are aliases;
     * the grouping carries no meaning to the assertion, which compares sets.
     */
    private static final Set<String> RECORDED = new LinkedHashSet<>(Set.of(
            // ENUM_REF
            "status", "type", "priority", "project",
            // USER_REF / ISSUE_REF / TEXT
            "assignee", "reporter", "parent", "text",
            // dates
            "created", "updated", "due",
            "closed", "closedat",              // closedAt = the response-payload spelling
            // many-valued and ToOne catalogs
            "label", "labels",                 // labels = ergonomic plural AND the retired V8 key
            "component", "components",         // components = ergonomic plural AND the retired V9 key
            "fixversion", "affectsversion",    // registered lowercased from fixVersion/affectsVersion
            "sprint", "sprints",
            "storypoints", "points"));

    /**
     * The registry claims exactly the recorded names.
     *
     * <p>Both directions fail: an addition is the event this ledger is named for, and a removal is
     * the mirror-image event ({@code RetiredFieldAliases} is what makes a removal survivable for
     * the filters already written against the name).
     */
    @Test
    void theRegistryClaimsExactlyTheRecordedNames() {
        requireRecorded(RECORDED, new FieldRegistry().claimedKeys());
    }

    /**
     * A tripwire on the ledger itself: a {@link FieldRegistry#claimedKeys()} that answered an empty
     * set would satisfy nothing while looking green, and would silently disarm the startup scan and
     * {@code /schema} at the same time, since both derive their watch set from it.
     */
    @Test
    void theClaimedSetIsNotDegenerate() {
        var claimed = new FieldRegistry().claimedKeys();

        assertThat(claimed)
                .as("the registry claims almost no names — claimedKeys() is reading the wrong "
                    + "thing, and every surface that derives its watch set from it is now silent")
                .hasSizeGreaterThan(15);
        assertThat(claimed)
                .as("claimedKeys() must include ALIASES, not only canonical names: a key is "
                    + "shadowed by whatever the LOOKUP answers, and the plural aliases are exactly "
                    + "what caused HD-275")
                .contains("labels", "components");
        assertThat(claimed)
                .as("claimedKeys() must be lowercased — FieldRegistry.find() lowercases its "
                    + "argument, so a capitalised entry here would be a key nothing can match")
                .allMatch(k -> k.equals(k.toLowerCase(java.util.Locale.ROOT)));
    }

    // ============================================== the guard, watched failing (HD-275 §9.3)

    /**
     * <strong>An added name is refused, and the refusal is the checklist.</strong> Called directly
     * with a synthetic claimed set, because the only way to know this guard works is to see it
     * fail — and it cannot be seen failing by adding a real registry entry, which is the change it
     * exists to interrogate.
     */
    @Test
    void theComparisonRefusesAnExtraName() {
        var claimed = new LinkedHashSet<>(RECORDED);
        claimed.add("epic");

        assertThatThrownBy(() -> requireRecorded(RECORDED, claimed))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("epic")
                .as("the failure must hand the author the propagation checklist, not a set diff")
                .hasMessageContaining("docs/release-checklist.md")
                .hasMessageContaining("RESERVED against every tenant's custom field")
                .hasMessageContaining("HD-275");
    }

    /**
     * <strong>A removed name is refused too.</strong> Deleting a registration is not a tidy-up: it
     * changes what an existing saved filter <em>means</em> for any tenant that owns a custom field
     * of that key, silently and with a 200 either side.
     */
    @Test
    void theComparisonRefusesAMissingName() {
        var claimed = new LinkedHashSet<>(RECORDED);
        claimed.remove("labels");

        assertThatThrownBy(() -> requireRecorded(RECORDED, claimed))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("labels")
                .hasMessageContaining("RetiredFieldAliases");
    }

    /** And it stays quiet when the two agree — otherwise the two cases above prove nothing. */
    @Test
    void theComparisonPassesWhenTheSetsAgree() {
        assertThatCode(() -> requireRecorded(RECORDED, new LinkedHashSet<>(RECORDED)))
                .doesNotThrowAnyException();
    }

    // ==================================================================== the comparison

    /**
     * The whole guard, as a pure function over two sets so it can be exercised with synthetic ones.
     *
     * @param recorded the ledger above
     * @param claimed  {@link FieldRegistry#claimedKeys()}
     * @throws AssertionError naming what moved, with the checklist
     */
    private static void requireRecorded(Set<String> recorded, Set<String> claimed) {
        var added = new TreeSet<>(claimed);
        added.removeAll(recorded);
        var removed = new TreeSet<>(recorded);
        removed.removeAll(claimed);
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }
        throw new AssertionError(checklist(added, removed));
    }

    /** The propagation checklist (HD-275 §9.2) — the message IS the deliverable. */
    private static String checklist(Set<String> added, Set<String> removed) {
        var names = added.isEmpty() ? removed : added;
        var joined = String.join(", ", names);
        var sb = new StringBuilder();
        if (!added.isEmpty()) {
            sb.append("FieldRegistry now claims a name this ledger does not record: ")
              .append(String.join(", ", added)).append(".\n\n");
        }
        if (!removed.isEmpty()) {
            sb.append("This ledger records a name FieldRegistry no longer claims: ")
              .append(String.join(", ", removed)).append(".\n\n");
        }
        sb.append("""
                A registry name is RESERVED against every tenant's custom field of that key, \
                retroactively and forever (FieldResolver). Adding one is a release-note-worthy \
                event, not an additive change.

                Before you add %s to RECORDED below:
                  1. Run the collision query in docs/release-checklist.md -> "Releases that \
                register a new HQL field name", against production AND against any instance you \
                support. Record the answer in the release notes even when it is zero.
                  2. If it finds rows: those tenants' fields become unsearchable on upgrade. They \
                keep working everywhere else. The remedy they have is a key rename (HD-275) - say \
                so in the release notes.
                  3. Nothing else is needed: the startup scan and /search/schema derive their \
                watch set from FieldRegistry, so %s is reported automatically.
                Removing a name is the mirror-image event: see RetiredFieldAliases before you do \
                it - for a tenant that owns a field under that key it is a silent change of \
                MEANING, not of precedence.""".formatted(joined, joined));
        return sb.toString();
    }
}
