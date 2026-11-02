package com.hamstrack.search;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.testsupport.FixtureRows;
import com.hamstrack.common.testsupport.LogRecords;
import com.hamstrack.common.testsupport.Unique;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <strong>The startup scan, watched doing all three of its jobs</strong> (HD-275 §8, AC-8/9/10).
 *
 * <p>The scan is the operator-facing half of ending the silence: a {@code field_defs} row under a
 * key the product later registered keeps working everywhere except search, where it is silently
 * unreachable, and nothing anywhere said so. A boot-time WARN is what an operator can actually
 * see.
 *
 * <p>Its three properties are asserted here, and the middle one is the one whose absence would
 * destroy the feature rather than merely weaken it:
 *
 * <ul>
 *   <li>it names a real collision, inserted the way real collisions arose;</li>
 *   <li><strong>it says nothing on a clean instance</strong> — every Hamstrack database carries
 *       V3's archived {@code labels}/{@code sprint}/{@code components} placeholders under
 *       registry-claimed keys, so a scan that ignored {@code archived_at} would warn on every boot
 *       of every instance and the signal would be worthless from the first release;</li>
 *   <li>it cannot fail a boot.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class ShadowedFieldStartupScanTest {

    @Autowired ShadowedFieldStartupScan scan;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired FieldRegistry registry;

    /**
     * <strong>The boot still reaches {@code scan()} now that it is package-private</strong>
     * (HD-340 R4).
     *
     * <p>Every other test here calls {@code scan.scan()} directly — correct for asking what the
     * scan does, and blind to whether Spring still <em>calls</em> it. That blindness is the whole
     * risk of the visibility change: the method's only output is log lines, so a release in which
     * Spring stopped registering non-public {@code @EventListener}s would take this feature away
     * in total silence while every assertion in this file stayed green.
     *
     * <p>Measured rather than remembered (2026-10-24, Boot 4.1 / Spring 7): a package-private
     * listener is registered and invoked, and {@code @Transactional} on it is honoured — the
     * latter contradicting Spring's documented proxy-mode rule that only public methods are
     * advised. So the registration is asserted against the live multicaster rather than trusted.
     * Asked of the registry and not by publishing the event, because {@code ApplicationReadyEvent}
     * has other listeners in this context and firing it for a question about one of them would
     * buy side effects nobody asked for.
     */
    @Test
    void springStillRegistersThePackagePrivateReadyListener() {
        var multicaster = applicationContext.getBean(
                org.springframework.context.event.ApplicationEventMulticaster.class);
        // getApplicationListeners() is protected on AbstractApplicationEventMulticaster and there
        // is no public way to ask. If a future Spring renames it this throws rather than passing,
        // which is the right direction: the question "is scan() still invoked" then needs asking
        // some other way, not skipping.
        Collection<?> listeners = org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(multicaster, "getApplicationListeners");
        // Keyed on the METHOD, not the class. Today this class has exactly one @EventListener, so
        // "a listener mentioning ShadowedFieldStartupScan" and "a listener for scan()" coincide —
        // and the day a second one arrives, a class-keyed filter would stay green with
        // @EventListener removed from scan() alone. ProductionBytecode's own javadoc states that
        // trap: a class is the unit only when the class has one such place, which is a fact
        // nobody re-checks. MEASURED 2026-10-24: the adapter renders as
        // "void com.hamstrack.search.ShadowedFieldStartupScan.scan()".
        var registered = java.util.Objects.requireNonNull(listeners).stream()
                .map(Object::toString)
                .filter(name -> name.contains("ShadowedFieldStartupScan") && name.contains("scan("))
                .toList();

        assertThat(registered)
                .withFailMessage("""

                        Spring has no listener registered for ShadowedFieldStartupScan.

                        scan() is package-private on purpose — it is half of the containment on \
                        FieldDefRepository.findAllLiveByKeyIn, which answers across every tenant \
                        (see CalledOnlyFromSealTest). That only works while Spring keeps invoking \
                        non-public @EventListener methods. If this is red after a Spring upgrade, \
                        the shadowed-key report has stopped running on every instance and nothing \
                        else would have told you: its entire output is log lines.

                        Make scan() public again and re-run — if that fixes it, the framework \
                        behaviour changed, and the containment needs the seal's arms alone, with \
                        the sentence on FieldDefRepository weakened to match.

                        Registered listeners mentioning this class: %s""", registered)
                .hasSize(1);
    }
    @Autowired FieldDefRepository fieldDefRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    /**
     * <strong>Every {@code field_defs} row this class plants, removed after the test that planted
     * it</strong> (HD-329's mechanism, HD-341's omission).
     *
     * <p>The three fixtures here used to end with {@code fieldDefRepository.delete(def)} as the
     * last statement of the test body, which deletes nothing when the test fails or errors. That
     * is not a leak confined to the suite: a live claimed-key row is exactly what
     * {@link ShadowedFieldStartupScan} exists to report, the scan reads <em>across every
     * tenant</em>, and nothing ever rewrites such a row — so one failed run leaves the development
     * instance warning correctly about a fixture, on every boot, for ever. Measured on this
     * database on 2026-11-01: one survivor from a red run of this very file, its display name
     * carrying the forged second line from {@link #aLineTerminatorInARowsNameCannotForgeALogLine}.
     *
     * <p>Workspace-scoped rather than global, and recorded here anyway: {@link FixtureRows}'s
     * subject is a row visible to readers outside the run, and this reader is instance-wide by
     * design. The scope column decides who sees the <em>field</em>, not who sees the WARN.
     *
     * <p>The census is what makes the teardown non-vacuous — a row created without being recorded
     * would otherwise be as invisible as the deletes that never ran. Recorded at the one site that
     * creates these rows ({@link #insertDirectly}) so a fourth fixture cannot forget.
     */
    private final FixtureRows planted = new FixtureRows();

    @BeforeEach
    void countFieldDefsBefore() {
        planted.watch(fieldDefRepository, "field_defs");
    }

    @AfterEach
    void removeWhatWasPlanted() {
        planted.deleteAll();
    }

    /**
     * <strong>AC-8 — V3's own archived seeds produce no WARN, so a clean instance is quiet.</strong>
     *
     * <p>Every Hamstrack database carries three global system placeholders keyed {@code labels},
     * {@code sprint} and {@code components} — all three registry-claimed — seeded by V3 and
     * archived by V8/V11/V9. On a clean instance those are the <em>only</em> claimed-key rows
     * there are, so "the scan ignores them" and "a clean instance logs nothing" are the same
     * statement. This assertion is what stands between the feature and a WARN every operator
     * learns to ignore before it ever means anything.
     *
     * <p>Phrased as "warns about exactly the live rows, and none of the archived seeds" rather
     * than "logs nothing", because a shared test database is not a clean instance: a sibling test
     * that legitimately leaves a live collision behind would turn the simpler assertion red while
     * saying nothing about the property under test.
     */
    @Test
    void v3sArchivedSeedPlaceholdersProduceNoWarning() {
        var seeds = List.of("labels", "sprint", "components").stream()
                .map(k -> fieldDefRepository
                        .findByScopeWorkspaceIdIsNullAndScopeProjectIdIsNullAndKey(k)
                        .orElseThrow(() -> new AssertionError(
                                "V3's '" + k + "' placeholder is gone, so this test is asserting "
                                + "about nothing — if a migration really removed it, the clean-"
                                + "instance case needs a new fixture, not a deleted assertion")))
                .toList();
        assertThat(seeds)
                .as("all three must be ARCHIVED, or the premise of the whole quiet-instance rule "
                    + "has changed")
                .allSatisfy(f -> assertThat(f.getArchivedAt()).isNotNull());

        var live = fieldDefRepository.findAllLiveByKeyIn(registry.claimedKeys());
        var warnings = warningsFromScan();

        for (var seed : seeds) {
            assertThat(warnings)
                    .as("an archived def is out of resolution and therefore not shadowed; "
                        + "warning about '%s' would make every instance in existence warn about "
                        + "its own seed data on every boot", seed.getKey())
                    .noneMatch(w -> w.contains(seed.getId().toString()));
        }
        assertThat(warnings)
                .as("one line per LIVE collision plus a summary — and on a clean instance, where "
                    + "the archived seeds are the only claimed-key rows, that is nothing at all")
                .hasSize(live.isEmpty() ? 0 : live.size() + 1);
    }

    /**
     * <strong>AC-9 — a real collision, inserted through the repository.</strong>
     *
     * <p>Bypassing {@code AdminFieldService} is not a shortcut: it is <em>how this population came
     * to exist</em>. The reserved-key guard is never retroactive — it refuses a claimed key at the
     * doors that mint one and leaves every existing row alone — and it did not exist when the
     * affected rows were written; a migration or a seeder never went through the service at all. A test that could only produce the row through the guarded door would be testing a
     * state the guard forbids.
     */
    @Test
    void aLiveCollisionIsNamedWithItsIdKeyScopeAndTheBuiltInThatTookIt() {
        var ws = workspace();
        var def = insertDirectly(ws.getId(), "components", "Team components");

        var events = warnEventsFromScan();
        var mine = events.stream()
                .filter(e -> e.getFormattedMessage().contains(def.getId().toString()))
                .toList();

        assertThat(mine).as("exactly one record names this collision").hasSize(1);
        assertThat(events.getLast().getFormattedMessage())
                .as("the summary counts every live collision the scan found")
                .contains("shadowed-field-def:")
                .contains("custom field definition(s) are shadowed");
        assertThat(mine.getFirst().getFormattedMessage())
                .contains("shadowed-field-def:")
                .contains(def.getId().toString())
                .as("the scope tells an operator whether this is one tenant's problem or the "
                    + "whole instance's")
                .contains("workspace " + ws.getId())
                .as("the canonical built-in name is the one whose data actually answers the "
                    + "query — reporting the alias the tenant typed would name the wrong field. "
                    + "It is minted, so it stays in the message; its quotes do not (HD-341)")
                .contains("component")
                .as("a refusal must name a remedy its reader can perform")
                .contains("rename its key");
        // HD-341: the two values the ROW supplied moved out of the message into key-value members,
        // so the assertions that used to read them off the sentence read them off the record. They
        // are not weaker — a member is compared whole, where `contains` on prose could be satisfied
        // by a substring of a neighbouring word.
        assertThat(pairs(mine.getFirst()))
                .as("flattened, never hidden: an operator still has to be able to find the field")
                .containsEntry("field.name", "Team components")
                .containsEntry("field.key", "components")
                .containsEntry("field.scope", "workspace " + ws.getId())
                .containsEntry("field.builtin", "component")
                .containsEntry("field.id", def.getId());
    }

    /**
     * <strong>A key the database's fold claims and this JVM's fold does not is still named with
     * its built-in field</strong> (HD-341 fix round).
     *
     * <p>Three relations decided this record and they disagree. The query matches
     * {@code lower(f.key)}; {@code FieldRegistry.find} matches {@code key.toLowerCase(ROOT)};
     * {@code claimedKeyOf} matches {@code equalsIgnoreCase}. MEASURED 2026-11-01 — Postgres
     * {@code lower()} of {@code SPRINT} spelled with U+0130 is {@code 'sprint'} (6 chars), Java's
     * {@code toLowerCase(ROOT)} of the same key is 7 characters and is not {@code "sprint"}, and
     * {@code equalsIgnoreCase} says true. So this row reached the report and the message read
     * "shadowed by the built-in search field <code>?</code>" on every boot, with the row's own key
     * no longer in the sentence to identify it by — and HD-341 is what published that
     * {@code ?} as a contract member.
     *
     * <p>The fix resolves once: the claimed key is found by the one relation, and the built-in
     * descriptor comes from <em>it</em>. This arm is the behavioural proof, with the character
     * that actually diverges rather than a case variant that would pass either way.
     */
    @Test
    void aKeyFoldedByTheDatabaseButNotByTheJvmIsStillNamedWithItsBuiltInField() {
        var ws = workspace();
        var def = insertDirectly(ws.getId(), "SPRİNT", "Team sprint");
        var sprint = registry.find("sprint").orElseThrow(() ->
                new AssertionError("the registry no longer claims 'sprint', so this arm asserts "
                                   + "about nothing — pick another claimed key with an I in it"));

        var mine = warnEventsFromScan().stream()
                .filter(e -> e.getFormattedMessage().contains(def.getId().toString()))
                .toList();

        assertThat(mine)
                .as("the query folds this key into the claimed set (measured), so the row IS "
                    + "reported — if this is empty the Postgres fold changed and the premise of "
                    + "the whole arm with it")
                .hasSize(1);
        assertThat(mine.getFirst().getFormattedMessage())
                .as("a placeholder may not stand in for a published member: '?' is what this said "
                    + "before the resolution was made single")
                .doesNotContain("field ?")
                .contains(sprint.name());
        assertThat(pairs(mine.getFirst()))
                .containsEntry("field.builtin", sprint.name())
                .as("the row's own spelling stays available, verbatim")
                .containsEntry("field.key", "SPRİNT");
    }

    /**
     * <strong>And a row whose key this JVM cannot fold into any claimed key is reported anyway,
     * naming no built-in field.</strong>
     *
     * <p>The remaining branch after the resolution was made single, and it gets a witness rather
     * than a placeholder: the row is reported with four of the five members, and the message says
     * in words that no built-in name could be resolved. Reached through a mocked repository,
     * because the branch is defined by the database's fold and this JVM's disagreeing — so the
     * honest way to ask the question is to hand the loop a row the query returned whose key folds
     * to no claimed key here, which is what the mock does and what no real row can be relied on to
     * do (a case variant would not: {@code equalsIgnoreCase} folds even the dotless
     * {@code sprınt} onto {@code sprint}, because {@code Character.toUpperCase(U+0131)} is
     * {@code 'I'} — MEASURED, and the reason this fixture uses a key that is not a claimed name at
     * all).
     */
    @Test
    void aRowWhoseKeyThisJvmCannotFoldIsReportedWithoutABuiltInName() {
        var unfoldable = new FieldDef();
        unfoldable.setScopeWorkspaceId(UUID.randomUUID());
        unfoldable.setKey("not_a_claimed_search_name");
        unfoldable.setName("Team sprint");
        unfoldable.setType(FieldType.TEXT);
        var repository = Mockito.mock(FieldDefRepository.class);
        Mockito.when(repository.findAllLiveByKeyIn(Mockito.any())).thenReturn(List.of(unfoldable));

        var appender = attach();
        try {
            new ShadowedFieldStartupScan(registry, repository).scan();
        } finally {
            detach(appender);
        }
        // M7 is a published record and this is the only arm that produces one, so this is the only
        // place the production encoder can ever see it (HD-341 security round).
        LogRecords.everyRecordIsOneJsonObject(appender.list);
        var perRow = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> !e.getFormattedMessage().contains("definition(s) are shadowed"))
                .toList();

        assertThat(perRow).as("one record for the row, never a dropped row").hasSize(1);
        assertThat(perRow.getFirst().getFormattedMessage())
                .contains("shadowed-field-def:")
                .as("it names the condition instead of printing a placeholder where a built-in "
                    + "name belongs")
                .contains("cannot name from the row's own key")
                .doesNotContain("field ?")
                .contains("rename its key");
        assertThat(pairs(perRow.getFirst()))
                .as("four of the five members, field.builtin being the one it cannot supply — the "
                    + "key and the name are still carried, so the operator can find the field")
                .containsEntry("field.key", "not_a_claimed_search_name")
                .containsEntry("field.name", "Team sprint")
                .doesNotContainKey("field.builtin");
    }

    /**
     * <strong>AC-6 (the scan's half) — an ARCHIVED claimed-key row is not shadowed.</strong>
     *
     * <p>This is the same predicate {@link #v3sArchivedSeedPlaceholdersProduceNoWarning()} relies
     * on, stated
     * about a row this test controls rather than about seed data, so a future migration that
     * un-archives or re-seeds a placeholder cannot quietly turn that test into a tautology.
     */
    @Test
    void anArchivedCollisionIsNotShadowed() {
        var ws = workspace();
        var def = insertDirectly(ws.getId(), "labels", "Old team labels");
        def.setArchivedAt(java.time.Instant.now());
        fieldDefRepository.saveAndFlush(def);

        assertThat(warningsFromScan())
                .as("an archived def is skipped by ResolutionContextFactory, so nothing resolves "
                    + "to it under any name and there is nothing to warn about")
                .noneMatch(w -> w.contains(def.getId().toString()));
    }

    /**
     * <strong>A line terminator in a row's display name cannot forge a second line under the
     * published prefix.</strong>
     *
     * <p>{@code UpsertFieldRequest} bounds the display name to a single line, which closes
     * <em>this</em> door — the line-terminator one — and only this door. It is not the whole
     * forgery: {@code DisplayText.SINGLE_LINE} permits {@code '}, {@code "}, {@code (}, {@code )}
     * and {@code =} (MEASURED 2026-11-02 against the compiled pattern), so the delimiter variant
     * was reachable through the live {@code PATCH …/fields/&#123;id&#125;} door throughout, by a
     * taxonomy admin at any scope editing the display name of an already-shadowed row, inside the
     * 100-character bound. This class also exists because rows arrive by three routes that never
     * meet a DTO — a migration, a seeder, direct SQL — and because the console itself was unbounded
     * until that annotation shipped, so an upgraded instance can be carrying such a row already.
     * Nothing rewrites it, so no later validation can reach it: without normalising at the sink it
     * would forge a line under {@code shadowed-field-def:} on <em>every boot, for ever</em>.
     *
     * <p><strong>{@code LogValues.oneLine} is not made redundant by HD-341's move to key-value
     * members</strong>, which is why this assertion stayed when the record's format changed: the
     * JSON encoder escapes a newline in a member, but logback's {@code %kvp} escapes nothing
     * (MEASURED 2026-11-01), so on a console pattern an operator may write, a newline in a pair
     * still splits the physical record.
     *
     * <p>Written through the repository for the same reason every fixture here is: that is the
     * shape of the population, and a test that could only produce the row through the guarded door
     * would be testing a state the guard forbids — and would prove nothing about the three doors
     * that have no guard.
     */
    @Test
    void aLineTerminatorInARowsNameCannotForgeALogLine() {
        var ws = workspace();
        var def = insertDirectly(ws.getId(), "labels",
                "Team labels\nshadowed-field-def: custom field 'nothing' (key 'x') is fine");

        var mine = warnEventsFromScan().stream()
                .filter(e -> e.getFormattedMessage().contains(def.getId().toString()))
                .toList();

        assertThat(mine).as("exactly one record names this collision").hasSize(1);
        assertThat(mine.getFirst().getFormattedMessage())
                .as("one row is one record, whatever text the row carries — otherwise the tail of "
                    + "the name arrives at Loki as its own shadowed-field-def: record")
                .doesNotContain("\n")
                .doesNotContain("\r")
                .as("""
                        INVERTED by HD-341, and this is the whole point of the change: the row's \
                        text used to be interpolated into the message, so a name carrying the \
                        template's own grammar produced a plausible record naming a field that \
                        does not exist. The message is now built from minted values only.""")
                .doesNotContain("custom field 'nothing'")
                .doesNotContain("Team labels");
        assertThat(pairs(mine.getFirst()))
                .as("flattened, never hidden: an operator still has to be able to find the field, "
                    + "and the forged text is carried verbatim where it can imitate nothing")
                .containsEntry("field.name",
                        "Team labels shadowed-field-def: custom field 'nothing' (key 'x') is fine");
    }

    /**
     * <strong>AC-10 — a scan that cannot run says so, and the instance still boots.</strong>
     *
     * <p>A tenant's perfectly legal data must never stop an instance starting, and the shadowing
     * is the product's doing rather than the tenant's. WARN rather than silence because reaching
     * this branch means the report is <em>absent</em>, which is exactly the kind of thing an
     * operator should not have to infer from a missing line.
     */
    @Test
    void aScanWhoseQueryThrowsWarnsAndDoesNotPropagate() {
        var broken = Mockito.mock(FieldDefRepository.class);
        Mockito.when(broken.findAllLiveByKeyIn(Mockito.any()))
                .thenThrow(new IllegalStateException("boom"));
        var brokenScan = new ShadowedFieldStartupScan(registry, broken);

        var appender = attach();
        try {
            assertThatCode(brokenScan::scan)
                    .as("an exception escaping an ApplicationReadyEvent listener fails the boot; "
                        + "a report is never worth that")
                    .doesNotThrowAnyException();
        } finally {
            detach(appender);
        }

        // The third published record this file produces, and the only one carrying a throwable —
        // which is the case where a member named stack_trace would collide (MEASURED 2026-11-02:
        // stack_trace and tags throw only on a record that has a throwable or a marker, so an
        // encode over a record without one reports them clean). HD-341 security round.
        LogRecords.everyRecordIsOneJsonObject(appender.list);

        assertThat(warnings(appender))
                .singleElement()
                .asString()
                .contains("shadowed-field-def: scan could not run");
    }

    // ==================================================================== helpers

    /**
     * Insert a {@code field_defs} row the way the affected population really arose — and record it
     * for the teardown here, not at the call sites, so a fixture added later cannot forget.
     */
    private FieldDef insertDirectly(UUID workspaceId, String key, String name) {
        var f = new FieldDef();
        f.setScopeWorkspaceId(workspaceId);
        f.setKey(key);
        f.setName(name);
        f.setType(FieldType.TEXT);
        f = fieldDefRepository.saveAndFlush(f);
        planted.record(fieldDefRepository, f.getId(), "shadowed field_defs row '" + key + "'");
        return f;
    }

    private List<String> warningsFromScan() {
        return warnEventsFromScan().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * The WARN events themselves, because since HD-341 the values a row supplied are key-value
     * members of the record rather than text in its message — so an assertion about them has to
     * read the event and not a formatted string.
     *
     * <p><strong>And every one of them goes through the production encoder here, in the capture
     * and not in the arms.</strong> Until the HD-341 security round the only record in this tree
     * ever handed to that encoder was the startup scan's main WARN, in one arm of
     * {@code ForgeableLogRecordTest}; this file pinned its records on their member <em>names</em>,
     * read through a map that collapses a name attached twice. A duplicated member is lost at
     * runtime exactly as a schema collision is — the throw is inside logback, so no counter can
     * witness it and a build is the only witness available. Put here rather than in each arm so a
     * fixture added later cannot skip it.
     */
    private List<ILoggingEvent> warnEventsFromScan() {
        var appender = attach();
        try {
            scan.scan();
        } finally {
            detach(appender);
        }
        var warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        LogRecords.everyRecordIsOneJsonObject(warns);
        return warns;
    }

    /** One record's key-value pairs as a map, which is how the contract names them. */
    private static java.util.Map<String, Object> pairs(ILoggingEvent event) {
        var out = new java.util.LinkedHashMap<String, Object>();
        var kvp = event.getKeyValuePairs();
        if (kvp != null) {
            kvp.forEach(pair -> out.put(pair.key, pair.value));
        }
        return out;
    }

    private static ListAppender<ILoggingEvent> attach() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger().addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        logger().detachAppender(appender);
        appender.stop();
    }

    private static List<String> warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static ch.qos.logback.classic.Logger logger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShadowedFieldStartupScan.class);
    }

    private Workspace workspace() {
        var creator = new User();
        creator.setEmail(Unique.email("scan"));
        creator.setDisplayName("Scan User");
        creator.setPasswordHash(passwordEncoder.encode("test-password-1"));
        creator.setStatus(UserStatus.ACTIVE);
        creator.setSystemRole(SystemRole.USER);
        creator = userRepository.save(creator);

        var w = new Workspace();
        w.setName("WS");
        w.setSlug(Unique.slug("scan"));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }
}
