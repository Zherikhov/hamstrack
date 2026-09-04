package com.hamstrack.search;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>Every HQL key a migration retires has a recorded verdict</strong> (HD-161).
 *
 * <p>Retiring a placeholder custom field is a two-part change and only one part is in SQL:
 * the migration archives the {@code field_defs} row, and something has to keep the name
 * resolving for the saved filters that already use it. V11 did both for
 * {@code story_points}; V10 did only the first for {@code fix_version}, so every filter
 * written as {@code fix_version = "2.4.0"} answered "unknown field" for two releases while
 * the identically-shaped {@code story_points = 5} kept working. Nothing failed, nothing
 * logged, and the gap surfaced by accident rather than by looking — which is the actual
 * defect this test exists to make impossible.
 *
 * <p>So the sweep is mechanical rather than remembered: the archival statements are read
 * out of the Flyway migrations themselves, and a key that appears there without a verdict
 * fails the build. There are exactly two acceptable verdicts, and "nobody has complained"
 * is not one of them:
 *
 * <ul>
 *   <li>{@link Verdict#ALIASED} — {@link RetiredFieldAliases} maps it to the field that
 *       replaced it. Consulted last, so a tenant that owns a custom field under that key
 *       still reaches its own field ({@link FieldResolver}); the tenant-facing half of that
 *       is pinned per key in {@code DeliverySearchCompatibilityTest}, because it is the half
 *       whose failure is silent.</li>
 *   <li>{@link Verdict#ANSWERED_BY_A_LIVE_REGISTRY_NAME} — the retired key IS the canonical
 *       name of the live field that replaced it, so resolution's first step already answers
 *       it and an alias would be unreachable by construction.</li>
 * </ul>
 *
 * <p><strong>Read the failure message, not just the diff.</strong> When this test fails on a
 * new migration, the fix is a decision (which verdict, and why) written into
 * {@link #RECORDED} and — for {@link Verdict#ALIASED} — into {@link RetiredFieldAliases};
 * it is never to widen the parser until the key stops being found.
 *
 * <p>A plain unit test: no Spring context, no database. The migrations are read as text
 * because that is the artefact that does the retiring — asserting against a live schema
 * would only prove that whichever migrations happened to have run said what they said.
 */
class RetiredFieldSweepTest {

    /** What keeps a retired key resolving. Exactly these two; there is no "it just works". */
    private enum Verdict {
        /** A compatibility entry in {@link RetiredFieldAliases}, consulted after custom fields. */
        ALIASED,
        /** The retired key is itself a live {@link FieldRegistry} name — resolution step one. */
        ANSWERED_BY_A_LIVE_REGISTRY_NAME
    }

    /**
     * The sweep. Every key any migration archives, and what keeps it working.
     *
     * <p>{@code labels}/{@code components} are {@link Verdict#ALIASED} on purpose even though
     * a {@link FieldRegistry} plural alias also answers them today: that plural was registered
     * for typing comfort, so leaning on it would make a saved filter's survival depend on a
     * line documented as an ergonomic nicety. With the entry present, deleting the plural
     * changes the name's PRECEDENCE (a tenant's own field would start winning) instead of
     * breaking every filter written before V8/V9 — except for the tenant that owns the key,
     * for whom it is a silent change of meaning rather than of precedence (that tenant is
     * shadowed today; see {@link RetiredFieldAliases} and {@code docs/release-checklist.md}).
     */
    private static final Map<String, Verdict> RECORDED = new LinkedHashMap<>();

    static {
        RECORDED.put("labels", Verdict.ALIASED);                            // V8  → label
        RECORDED.put("components", Verdict.ALIASED);                        // V9  → component
        RECORDED.put("fix_version", Verdict.ALIASED);                       // V10 → fixVersion
        RECORDED.put("story_points", Verdict.ALIASED);                      // V11 → storyPoints
        RECORDED.put("sprint", Verdict.ANSWERED_BY_A_LIVE_REGISTRY_NAME);   // V11 → sprint
    }

    private final FieldRegistry registry = new FieldRegistry();
    private final RetiredFieldAliases aliases = new RetiredFieldAliases();
    private final FieldResolver resolver = new FieldResolver(registry, aliases);

    /**
     * The set of keys the migrations retire is exactly the set with a recorded verdict.
     *
     * <p>This is the half that catches the NEXT HD-161: a migration that archives a key
     * without anybody deciding what happens to the filters using it.
     */
    @Test
    void everyKeyAMigrationRetiresHasARecordedVerdict() throws Exception {
        var retired = retiredKeysFromMigrations();

        assertThat(retired)
                .as("no archival statement was found in any migration, so this sweep is "
                    + "scanning nothing — the migration directory or the statement shape moved, "
                    + "and the guarantee is gone rather than satisfied")
                .isNotEmpty();

        assertThat(retired)
                .as("The set of HQL keys retired by migrations no longer matches the recorded "
                    + "sweep. Retiring a placeholder field archives it in SQL and silently "
                    + "breaks every saved filter written against the key; the migration is only "
                    + "half the change. Decide, for each key below, which verdict applies and "
                    + "record it in RECORDED: ALIASED (add the mapping to RetiredFieldAliases — "
                    + "never to FieldRegistry, which would shadow the custom field of every "
                    + "tenant that keys one that way) or ANSWERED_BY_A_LIVE_REGISTRY_NAME (the "
                    + "retired key is already the canonical name of the field that replaced "
                    + "it). A key that DISAPPEARED from this set is just as suspicious: an "
                    + "applied migration is never edited.")
                .containsExactlyInAnyOrderElementsOf(RECORDED.keySet());
    }

    /**
     * Each recorded verdict is true of the code, and the key actually resolves.
     *
     * <p>Asserted through {@link FieldResolver} rather than by reading the registry and the
     * alias table separately, because "resolves to the field that replaced it" is the promise;
     * which of the two mechanisms delivers it is the implementation detail the verdict names.
     */
    @Test
    void everyRetiredKeyStillResolvesByTheMechanismItsVerdictClaims() {
        var ctx = contextWithNoCustomFields();

        for (var entry : RECORDED.entrySet()) {
            String key = entry.getKey();

            assertThatCode(() -> resolver.resolve(key, ctx))
                    .as("the retired key `%s` no longer resolves — every saved filter written "
                        + "against it now answers 422 'Unknown field'", key)
                    .doesNotThrowAnyException();

            var resolved = resolver.resolve(key, ctx);
            assertThat(resolved)
                    .as("the retired key `%s` must resolve to the SYSTEM field that replaced it", key)
                    .isInstanceOf(FieldResolver.Resolved.SystemField.class);
            var descriptor = ((FieldResolver.Resolved.SystemField) resolved).descriptor();

            switch (entry.getValue()) {
                case ALIASED -> {
                    assertThat(aliases.canonicalName(key))
                            .as("`%s` is recorded ALIASED but RetiredFieldAliases does not map it", key)
                            .contains(descriptor.name());
                    var registered = registry.find(key).map(FieldDescriptor::name);
                    assertThat(registered.isEmpty() || registered.get().equals(descriptor.name()))
                            .as("`%s` is recorded ALIASED but FieldRegistry resolves it to `%s`. A "
                                + "REGISTERED retired key outranks every tenant's own custom field "
                                + "of that key, in every workspace, forever — the shadowing hazard "
                                + "the alias table exists to avoid. (labels/components carry that "
                                + "knowingly, as ergonomic plurals of the same field; anything "
                                + "else is a mistake.)", key, registered.orElse("-"))
                            .isTrue();
                }
                case ANSWERED_BY_A_LIVE_REGISTRY_NAME -> {
                    assertThat(registry.find(key))
                            .as("`%s` is recorded as answered by a live registry name, but the "
                                + "registry does not know it", key)
                            .isPresent();
                    assertThat(descriptor.name())
                            .as("`%s` must resolve to the live field of that very name", key)
                            .isEqualToIgnoringCase(key);
                    assertThat(aliases.canonicalName(key))
                            .as("`%s` is answered by the registry, so an alias entry for it would "
                                + "be unreachable by construction — drop it, or change the verdict "
                                + "because the registry name went away", key)
                            .isEmpty();
                }
            }
        }
    }

    /**
     * <strong>Compatibility, not vocabulary.</strong> A retired key is never advertised by
     * {@code /search/schema}, which lists {@link FieldRegistry#availableFields()} under their
     * canonical names. Suggesting one would teach a dead name to people who never used it, and
     * would advertise a key a tenant may legitimately want for a custom field of its own.
     *
     * <p>The exception is the key whose verdict IS a live registry name: {@code sprint} is
     * current vocabulary that happens to spell a retired placeholder, so it is offered like
     * any other field.
     */
    @Test
    void aRetiredKeyIsNeverAdvertisedAsVocabulary() {
        var advertised = registry.availableFields().stream()
                .map(f -> f.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        // Tripwire: an empty vocabulary would pass every assertion below while proving nothing.
        assertThat(advertised).as("the registry advertises no fields at all").hasSizeGreaterThan(10);

        for (var entry : RECORDED.entrySet()) {
            if (entry.getValue() == Verdict.ALIASED) {
                assertThat(advertised)
                        .as("the retired key `%s` is being advertised as vocabulary — an alias is "
                            + "compatibility for the filters that already exist, not a name to "
                            + "teach to new ones", entry.getKey())
                        .doesNotContain(entry.getKey());
            } else {
                assertThat(advertised)
                        .as("`%s` is a live field name and must stay in the vocabulary", entry.getKey())
                        .contains(entry.getKey());
            }
        }
    }

    // ==================================================================== scanner

    /**
     * <strong>An archival is recognised wherever in the statement it sits.</strong>
     *
     * <p>The sweep's own matcher used to have the shape the sweep exists to abolish: it
     * required {@code archived_at} to be the FIRST assignment after {@code SET} and to be
     * stamped with the literal {@code NOW()}. Both fixtures below are ordinary SQL that any
     * author might write without a second thought, and under the narrow matcher both retired a
     * key this class never saw — silently, which is precisely the failure HD-161 was about.
     * The value of this test is that the next retirement cannot happen by omission; a matcher
     * that depends on clause order hands that value back for a formatting preference.
     */
    @Test
    void anArchivalIsSeenWhereverInTheStatementItSits() {
        String migration = """
                -- V99 — retire two placeholders superseded by native fields.
                UPDATE field_defs SET name = 'Foo (retired)', archived_at = NOW()
                 WHERE key = 'foo' AND scope_workspace_id IS NULL;
                UPDATE field_defs SET archived_at = CURRENT_TIMESTAMP WHERE key = 'bar';
                """;

        assertThat(archivedKeysIn(migration))
                .as("a retirement whose archived_at is not the first assignment, or which stamps "
                    + "CURRENT_TIMESTAMP instead of NOW(), retires the key just as completely — "
                    + "if the scanner cannot see it, the key reaches production with no verdict "
                    + "and every saved filter using it 422s in silence")
                .containsExactlyInAnyOrder("foo", "bar");
    }

    /**
     * The widening costs no precision: a statement that touches {@code field_defs} without
     * archiving anything is still not a retirement, and a retirement quoted inside a comment is
     * still narration. Pinned because a matcher loosened until it matches everything would pass
     * {@link #anArchivalIsSeenWhereverInTheStatementItSits()} while making the sweep meaningless.
     */
    @Test
    void aStatementThatArchivesNothingIsNotARetirement() {
        String migration = """
                UPDATE field_defs SET is_system = TRUE WHERE key IN ('story_points', 'severity');
                UPDATE field_defs SET archived_at = NULL WHERE key = 'brought_back';
                INSERT INTO field_defs (id, key, name) VALUES (gen_random_uuid(), 'labels', 'Labels');
                -- UPDATE field_defs SET archived_at = NOW() WHERE key = 'only_narrated';
                """;

        assertThat(archivedKeysIn(migration))
                .as("nothing here retires a key, so nothing may appear in the sweep")
                .isEmpty();
    }

    /**
     * An archival the scanner cannot read fails the build instead of contributing nothing.
     *
     * <p>This is the mitigation that makes the widened anchor safe: the loosened pattern can
     * match a statement whose keys are unreadable (an {@code IN (…)} list, a subselect, an
     * expression), and the one unacceptable outcome is that such a statement is treated as
     * having retired nothing. It is reported, loudly, with the statement in the message.
     */
    @Test
    void anArchivalNamingNoKeyLiteralIsReportedRatherThanDropped() {
        assertThatThrownBy(() -> archivedKeysIn(
                "UPDATE field_defs SET archived_at = NOW() WHERE key IN ('foo', 'bar');"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("names no key literal");
    }

    // ================================================================== migrations

    /**
     * The retirement statement: an {@code UPDATE field_defs} that stamps {@code archived_at}
     * with the current time <em>anywhere</em> in the statement, in either spelling.
     *
     * <p>It deliberately does NOT require {@code archived_at} to be the first assignment after
     * {@code SET}, nor the literal {@code NOW()}. The narrow form did, and that gave this sweep
     * the exact shape it exists to abolish, one layer out: a migration written as
     * {@code UPDATE field_defs SET name = '…', archived_at = NOW() WHERE key = 'foo'} — or with
     * {@code CURRENT_TIMESTAMP} — retires a key the sweep never sees, <em>silently</em>, and
     * the author does nothing more unusual than order two assignments the other way round. A
     * guarantee that holds only while everyone writes their clauses in one order is not a
     * guarantee; both variants are pinned by
     * {@link #anArchivalIsSeenWhereverInTheStatementItSits()}.
     *
     * <p>Widening the anchor does not widen what passes <em>without</em> a verdict: a matched
     * statement naming no {@code key = '…'} literal still fails loudly below, so an
     * {@code IN (…)} or expression-driven retirement is reported rather than quietly dropped.
     */
    private static final Pattern ARCHIVAL = Pattern.compile(
            "update\\s+field_defs\\b.*?\\barchived_at\\s*=\\s*(?:now\\s*\\(\\s*\\)|current_timestamp)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern KEY_LITERAL = Pattern.compile(
            "\\bkey\\s*=\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);

    /**
     * Every key archived by any Flyway migration, read from the SQL text.
     *
     * <p>Comments are stripped first: the migrations narrate each other, so a retirement
     * quoted inside a {@code --} note must not count as one, and a semicolon inside a comment
     * must not split a statement.
     */
    private static Set<String> retiredKeysFromMigrations() throws Exception {
        var files = migrationFiles();
        assertThat(files)
                .as("almost no migrations were found at %s — the sweep is reading the wrong "
                    + "place and would pass on an empty scan", migrationDirectory())
                .hasSizeGreaterThan(20);

        var keys = new LinkedHashSet<String>();
        for (var file : files) {
            keys.addAll(archivedKeysIn(Files.readString(file)));
        }
        return keys;
    }

    /**
     * The scanner itself: every key archived by one SQL text.
     *
     * <p>Split into a method taking text so the statement shapes it must recognise can be
     * pinned against fixtures rather than against whatever the migrations happen to contain
     * today — a matcher only ever exercised by SQL that already passes proves nothing about
     * the SQL somebody writes next.
     *
     * <h2>What this scanner is not</h2>
     * It is a regex over text, not a parser, and it is <strong>literal-unaware</strong> in two
     * known ways. Neither can hide a retirement today, and both fail loudly rather than
     * silently, so they are recorded here rather than fixed — but a reader debugging one will
     * be staring at a red build that names the wrong file, which is why they are written down:
     *
     * <ul>
     *   <li>{@code split(";")} splits on every semicolon, including one inside a dollar-quoted
     *       body ({@code $$ … $$}, used by several migrations for trigger/DO blocks) or inside
     *       a string literal. A statement cut in half can only <em>lose</em> a match, never
     *       invent one, and losing one makes the key disappear from the set — which fails
     *       {@link #everyKeyAMigrationRetiresHasARecordedVerdict()} loudly, pointing at the key
     *       rather than at the function body that did the cutting.</li>
     *   <li>{@link #stripComments(String)} removes {@code --} to end of line even when the
     *       {@code --} sits inside a string literal, truncating that statement's tail. Same
     *       shape, same loud failure, same misleading finger.</li>
     * </ul>
     *
     * <p>If either ever does bite, the fix is to make the splitter literal-aware — never to
     * relax an assertion until the build goes green.
     */
    private static Set<String> archivedKeysIn(String rawSql) {
        var keys = new LinkedHashSet<String>();
        String sql = stripComments(rawSql);
        for (String statement : sql.split(";")) {
            if (!ARCHIVAL.matcher(statement).find()) {
                continue;
            }
            var m = KEY_LITERAL.matcher(statement);
            boolean any = false;
            while (m.find()) {
                keys.add(m.group(1).toLowerCase(Locale.ROOT));
                any = true;
            }
            assertThat(any)
                    .as("an archival statement names no key literal, so this sweep cannot see "
                        + "what it retires: %s", statement.trim())
                    .isTrue();
        }
        return keys;
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    }

    private static List<Path> migrationFiles() throws Exception {
        try (var entries = Files.list(migrationDirectory())) {
            return entries.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
    }

    /**
     * The migration directory, preferring the build output on the classpath so the test does
     * not depend on the working directory; the source tree is the fallback for a runner that
     * does not copy resources.
     */
    private static Path migrationDirectory() throws Exception {
        var onClasspath = RetiredFieldSweepTest.class.getResource("/db/migration");
        if (onClasspath != null) {
            var path = Path.of(onClasspath.toURI());
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return Path.of("src", "main", "resources", "db", "migration");
    }

    // ==================================================================== fixtures

    /**
     * A context whose caller can see no custom fields — the state in which an alias is
     * <em>reachable</em>. {@link FieldResolver} reads only {@code customFieldsByKey} here, so
     * the remaining components are left null/empty rather than faked into looking meaningful.
     * The workspace that DOES own a field under a retired key is the other half of the rule and
     * is exercised end-to-end, against a real database, in {@code DeliverySearchCompatibilityTest}.
     */
    private static ResolutionContext contextWithNoCustomFields() {
        return new ResolutionContext(
                null, null, List.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(),
                new ResolutionContext.Capabilities(List.of(), List.of(), List.of()));
    }
}
