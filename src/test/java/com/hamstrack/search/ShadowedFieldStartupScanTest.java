package com.hamstrack.search;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

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
    @Autowired FieldRegistry registry;
    @Autowired FieldDefRepository fieldDefRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

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

        var warnings = warningsFromScan();
        var mine = warnings.stream().filter(w -> w.contains(def.getId().toString())).toList();

        assertThat(mine).as("exactly one line names this collision").hasSize(1);
        assertThat(warnings.getLast())
                .as("the summary counts every live collision the scan found")
                .contains("shadowed-field-def:")
                .contains("custom field definition(s) are shadowed");
        assertThat(mine.getFirst())
                .contains("shadowed-field-def:")
                .contains("Team components")
                .contains("components")
                .contains(def.getId().toString())
                .as("the scope tells an operator whether this is one tenant's problem or the "
                    + "whole instance's")
                .contains("workspace " + ws.getId())
                .as("the canonical built-in name is the one whose data actually answers the "
                    + "query — reporting the alias the tenant typed would name the wrong field")
                .contains("'component'")
                .as("a refusal must name a remedy its reader can perform")
                .contains("rename its key");

        fieldDefRepository.delete(def);
    }

    /**
     * <strong>AC-6 (the scan's half) — an ARCHIVED claimed-key row is not shadowed.</strong>
     *
     * <p>This is the same predicate {@link #aCleanInstanceLogsNothingAtWarn()} relies on, stated
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

        fieldDefRepository.delete(def);
    }

    /**
     * <strong>A line terminator in a row's display name cannot forge a second line under the
     * prefix operators alert on.</strong>
     *
     * <p>{@code UpsertFieldRequest} bounds the display name to a single line, which closes the
     * console door and only that door. This class exists because rows arrive by three routes that
     * never meet a DTO — a migration, a seeder, direct SQL — and because the console itself was
     * unbounded until that annotation shipped, so an upgraded instance can be carrying such a row
     * already. Nothing rewrites it, so no later validation can reach it: without escaping at the
     * sink it would forge a line under {@code shadowed-field-def:} on <em>every boot, for ever</em>.
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

        var mine = warningsFromScan().stream()
                .filter(w -> w.contains(def.getId().toString()))
                .toList();

        assertThat(mine).as("exactly one line names this collision").hasSize(1);
        assertThat(mine.getFirst())
                .as("one row is one log line, whatever text the row carries — otherwise the tail "
                    + "of the name arrives at Loki as its own shadowed-field-def: record")
                .doesNotContain("\n")
                .doesNotContain("\r")
                .as("flattened, never hidden: an operator still has to be able to find the field")
                .contains("Team labels")
                .contains("custom field 'nothing'");

        fieldDefRepository.delete(def);
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

        assertThat(warnings(appender))
                .singleElement()
                .asString()
                .contains("shadowed-field-def: scan could not run");
    }

    // ==================================================================== helpers

    /** Insert a {@code field_defs} row the way the affected population really arose. */
    private FieldDef insertDirectly(UUID workspaceId, String key, String name) {
        var f = new FieldDef();
        f.setScopeWorkspaceId(workspaceId);
        f.setKey(key);
        f.setName(name);
        f.setType(FieldType.TEXT);
        return fieldDefRepository.saveAndFlush(f);
    }

    private List<String> warningsFromScan() {
        var appender = attach();
        try {
            scan.scan();
        } finally {
            detach(appender);
        }
        return warnings(appender);
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
        creator.setEmail(("scan-" + System.nanoTime() + "@example.com").toLowerCase());
        creator.setDisplayName("Scan User");
        creator.setPasswordHash(passwordEncoder.encode("test-password-1"));
        creator.setStatus(UserStatus.ACTIVE);
        creator.setSystemRole(SystemRole.USER);
        creator = userRepository.save(creator);

        var w = new Workspace();
        w.setName("WS");
        w.setSlug("scan-" + UUID.randomUUID().toString().substring(0, 8) + "-" + (System.nanoTime() % 100000));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }
}
