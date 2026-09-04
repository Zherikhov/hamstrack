package com.hamstrack.common.seed;

import com.hamstrack.HamstrackApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * <strong>HD-167 — the admin seed folds to FIND and compares exactly to GRANT</strong>
 * (acceptance criteria 12 and 13 of {@code docs/design/email-uniqueness-proposal.md} §12).
 *
 * <p><strong>The change under test is small and its failure mode is silent, which is the whole
 * reason for this file.</strong> {@code DataSeeder.run} used to resolve the seed administrator
 * with {@code findByEmail} — exact. HD-167 made it {@code findByFoldedEmail}, so that a row
 * differing from {@code seed.admin.email} only in case is <em>found</em> rather than missed. That
 * change alone would have been a security regression, and the regression is invisible:
 *
 * <ul>
 *   <li>Before V23, such a row meant the exact find missed, the insert was refused by
 *       {@code users_email_key}, and the boot FAILED LOUDLY out of the {@code ApplicationRunner}.
 *       An operator looked.</li>
 *   <li>A folded find with no comparison converts exactly that into
 *       <em>"Existing seed account promoted to system ADMIN"</em> — logged as a success. A
 *       stranger holds instance-wide {@code SystemRole.ADMIN}; {@code SEED_ADMIN_PASSWORD} was
 *       never applied, so the operator cannot even log in to see it; and this class deliberately
 *       never logs the address, so nothing on the console says whose row it was.</li>
 * </ul>
 *
 * <p>Post-V23 the alternative is NOT "a silently duplicated account" — {@code users_email_lower_uk}
 * makes a duplicate impossible. What the fold displaces is the loud failure, so the seeder keeps
 * one deliberately. The proposal's AC 12 was amended on 2026-08-28 for exactly that reason; it
 * used to read "finds it and promotes it rather than minting a second administrator", which was
 * written against a pre-V23 world.
 *
 * <p><strong>Why this boots a whole application instead of calling {@code run} directly.</strong>
 * {@code SeedGuardStartupOrderingTest} was opened by a regression the entire suite let through: a
 * guard was moved below the returns its own javadoc forbids and 1218 tests stayed green, because
 * the only test of it <em>called the static method by hand</em> — it asserted what the refusal
 * SAYS and never that anything reaches it. The claim here is "the boot is refused", so the vehicle
 * has to be a boot. Both cases run the real {@code HamstrackApplication} against the real
 * database, with the fixture row planted by direct SQL <em>before</em> the context exists — which
 * is the one ordering a {@code @SpringBootTest} cannot express.
 *
 * <p><strong>The row is planted by direct SQL for the usual reason and one extra one.</strong> No
 * writer in this application can produce a mixed-case address (every one of them folds with
 * {@code Locale.ROOT}), and — since V23 — the migration refuses to upgrade a database holding one.
 * A row of exactly that shape is what a foreign writer leaves, and it is what this refusal is for.
 * Every one is deleted afterwards, because leaving it would seed the shared development database
 * with a row that blocks a future rebuild.
 */
class SeedAdminSquatterRefusalTest {

    /** Long, and not one of the values {@code DataSeeder.rejectPublishedPassword} refuses. */
    private static final String SEED_PASSWORD = "hd167-seed-admin-password";

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    private final List<UUID> planted = new ArrayList<>();

    @AfterEach
    void removeThePlantedRows() throws SQLException {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            for (var id : planted) {
                exec(conn, "DELETE FROM users WHERE id = '" + id + "'");
            }
        }
        planted.clear();
    }

    /**
     * <strong>A row that holds the folded form of {@code seed.admin.email} with a different
     * spelling refuses the boot</strong> (AC 12).
     *
     * <p>Three separate things are asserted and none of them implies the others:
     * <ol>
     *   <li><strong>the boot fails</strong> — not that a method threw, but that
     *       {@code SpringApplication.run} does not return, which is what "an operator looked"
     *       actually means;</li>
     *   <li><strong>the stranger's row was not granted {@code ADMIN}</strong> — the silent outcome
     *       the refusal exists to prevent. A refusal that fired <em>after</em> the grant would
     *       satisfy the first assertion and none of the point;</li>
     *   <li><strong>the message names the id and no address</strong> — the id is what takes an
     *       operator to the row, and they are at a database prompt anyway. Third-party addresses
     *       written into a shipped log are not something this project does, for the same reason
     *       {@code logServerErrorDetail=false} shipped one release earlier.</li>
     * </ol>
     */
    @Test
    void aStrangerHoldingTheFoldedKeyRefusesTheBootInsteadOfBeingHandedAdmin() throws Exception {
        var address = address("seed-squatter");
        var squatter = plant(address.toUpperCase(Locale.ROOT));

        var failure = catchThrowable(() -> close(boot(address)));

        assertThat(failure)
                .as("""
                        THE BOOT MUST NOT COMPLETE. This row was not written by this seeder, and \
                        the alternative to refusing is not a duplicate — V23's index makes a \
                        duplicate impossible — it is a SILENT instance-wide ADMIN grant to a \
                        stranger, logged with a line that reads like success. Before HD-167 the \
                        exact find missed here and users_email_key refused the insert, so the \
                        boot failed loudly; the folded find is what would have swallowed that, \
                        and this refusal is the loud failure kept rather than a new strictness.""")
                .isNotNull();
        var refusal = rootCauseOf(failure);
        assertThat(refusal).isInstanceOf(IllegalStateException.class);
        assertThat(refusal.getMessage())
                .as("""
                        the refusal must name the row (an id an operator can SELECT on), say that \
                        this seeder did not write it, and point at the procedure. It must NOT \
                        name the address: its reader is at a database prompt by definition, so \
                        the id is the performable handle, and a third party's address in a \
                        shipped log is what this project spent a release removing.""")
                .contains("Admin seeding refused")
                .contains(squatter.toString())
                .contains("seed.admin.email")
                .contains("Duplicate accounts after an upgrade")
                .doesNotContain(address)
                .doesNotContain(address.toUpperCase(Locale.ROOT));

        assertThat(systemRoleOf(squatter))
                .as("""
                        AND THE GRANT MUST NOT HAVE HAPPENED. A refusal raised after \
                        setSystemRole(ADMIN) would satisfy every assertion above and none of the \
                        point: SEED_ADMIN_PASSWORD is never applied to a found row, so the \
                        operator cannot log in to notice, and the seeder never logs an address, \
                        so the console says nothing about whose row it was.""")
                .isEqualTo("USER");
        assertThat(rowsFolding(address))
                .as("and nothing was minted alongside it — which V23's index would refuse anyway, "
                    + "and which is why the refusal rather than the index is the control here")
                .isEqualTo(1);
    }

    /**
     * <strong>An exactly matching row is still promoted, and the configured value is folded before
     * the comparison</strong> (AC 13).
     *
     * <p>The refusal above is narrow on purpose, and this is the assertion that says so rather
     * than leaving it to be inferred. Two different things are being separated:
     *
     * <ul>
     *   <li>a mixed-case <strong>{@code seed.admin.email}</strong> for a correctly stored account
     *       — which is an operator typing capitals into an environment variable, and must keep
     *       working. The property here is deliberately spelled in mixed case while the row is
     *       stored folded, so a fold dropped from the <em>configuration</em> side turns this case
     *       red instead of turning the case above green;</li>
     *   <li>a mixed-case <strong>stored row</strong> — which is a foreign writer's, and is what
     *       the refusal is for.</li>
     * </ul>
     *
     * <p>The promotion itself is the older behaviour this ticket had to preserve: accounts seeded
     * before system roles existed must still get {@code ADMIN}, so a seeder that refused
     * everything it found would break every upgrade.
     */
    @Test
    void anExactlyMatchingRowIsPromotedEvenFromAMixedCaseConfiguration() throws Exception {
        var address = address("seed-exact");
        var existing = plant(address);

        close(boot(mixCase(address)));

        assertThat(systemRoleOf(existing))
                .as("""
                        the row's address IS seed.admin.email once the configured value is folded, \
                        so this is the account the operator means and it must be promoted. \
                        Refusing here would break every upgrade of an installation seeded before \
                        system roles existed — and would do it over a capital letter in an \
                        environment variable.""")
                .isEqualTo("ADMIN");
        assertThat(emailOf(existing))
                .as("promoted, not rewritten. The seeder changes a role, never an address — "
                    + "changing one changes which mailbox can reset that account's password")
                .isEqualTo(address);
        assertThat(rowsFolding(address))
                .as("and no second administrator was minted beside it")
                .isEqualTo(1);
    }

    // ================================================================ the vehicle

    /**
     * Boots the real application with {@code seed.admin.email} pointed at {@code email}.
     *
     * <p>{@code web-application-type=none} because the claim is about {@code callRunners()} and
     * not about a port — this is the opposite half of the story from
     * {@code SeedGuardStartupOrderingTest}, whose subject is a guard that must fire <em>before</em>
     * the connectors bind. This one is an {@code ApplicationRunner} on purpose: it needs the
     * database, it grants nothing until it runs, and its refusal stops the boot the way any
     * runner's does.
     */
    private ConfigurableApplicationContext boot(String email) {
        return new SpringApplicationBuilder(HamstrackApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .properties(
                        "seed.admin.email=" + email,
                        "seed.admin.password=" + SEED_PASSWORD,
                        "app.demo.seed-on-first-login=false",
                        "app.rate-limit.enabled=false",
                        "spring.docker.compose.enabled=false")
                .run();
    }

    private static void close(ConfigurableApplicationContext context) {
        if (context != null) context.close();
    }

    /**
     * The refusal is thrown from an {@code ApplicationRunner}, so Boot wraps it before it reaches
     * the caller — and the wrapping is Boot's business, not this test's. Walked rather than
     * matched with AssertJ's {@code rootCause()}, which refuses a throwable that has no cause at
     * all; the same lesson, from the same place, as
     * {@code SeedGuardStartupOrderingTest.rootCauseOf}.
     */
    private static Throwable rootCauseOf(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    // ================================================================ fixture

    private UUID plant(String email) throws SQLException {
        var id = UUID.randomUUID();
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            exec(conn, """
                    INSERT INTO users (id, email, display_name, status, system_role)
                    VALUES ('%s', '%s', 'HD-167 seed fixture', 'ACTIVE', 'USER')
                    """.formatted(id, email));
        }
        planted.add(id);
        return id;
    }

    private String systemRoleOf(UUID id) throws SQLException {
        return scalar("SELECT system_role FROM users WHERE id = '" + id + "'");
    }

    private String emailOf(UUID id) throws SQLException {
        return scalar("SELECT email FROM users WHERE id = '" + id + "'");
    }

    private long rowsFolding(String email) throws SQLException {
        return Long.parseLong(scalar(
                "SELECT count(*) FROM users WHERE lower(email) = lower('" + email + "')"));
    }

    /** Unique per run, so no case can collide with another's address or with a leftover row. */
    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@hd167.test";
    }

    /** The shape an operator's environment variable takes when they type capitals. */
    private static String mixCase(String email) {
        return email.substring(0, 1).toUpperCase(Locale.ROOT)
               + email.substring(1, email.indexOf('@')).toUpperCase(Locale.ROOT)
               + email.substring(email.indexOf('@'));
    }

    // ================================================================ plumbing

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static String scalar(String sql) throws SQLException {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).withFailMessage("expected a row from: " + sql).isTrue();
            return rs.getString(1);
        }
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
