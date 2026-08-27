package com.hamstrack.common.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>PostgreSQL's {@code DETAIL} line never reaches an exception message on this
 * pool</strong> — {@code logServerErrorDetail=false}, HD-133's privacy floor, held as behaviour
 * rather than as a line in a file.
 *
 * <p><strong>What it protects.</strong> PgJDBC folds the server's {@code DETAIL} into
 * {@code SQLException.getMessage()}, and Hibernate's {@code SqlExceptionHelper} logs that message
 * at ERROR <em>before</em> any application {@code catch} block runs — so no amount of care in a
 * service can keep it out, and the only layer that can fix it is the driver. On a {@code 23505}
 * the DETAIL spells the colliding key VALUES, which on the invite path is
 * {@code Key (workspace_id, lower(email))=(…, victim@example.test) already exists}: a third
 * party's address, in the logs of an application whose own rule for mail logging is DOMAIN ONLY.
 * The probe below therefore collides on a column literally called {@code email}, so the value the
 * assertions look for is the value the hazard is about.
 *
 * <p><strong>Why the negative assertion is not vacuous, which is the mistake this project has
 * already shipped once.</strong> {@code ReferencedRowConflictContractTest}'s leak guard checks
 * that a key value never reaches the wire — and since this property landed, that key never reaches
 * the exception message either, so the guard cannot fail. A {@code doesNotContain} is only worth
 * anything beside a demonstration that the string was available to leak. So every probe here is
 * run <strong>four ways on the same statement</strong>: through the application's own
 * {@link DataSource}, and through three raw connections that differ only in how they spell the
 * property. The three raw ones are what prove the address was there to be leaked, what the driver
 * does by default, and what a lower-cased key is worth.
 *
 * <p><strong>The case-sensitivity is the whole reason the property is threaded through
 * {@code ${...}}.</strong> PgJDBC matches {@code logServerErrorDetail} exactly; Spring's relaxed
 * binding would lower-case the key of a bare environment variable, and an unknown data-source
 * property is <em>accepted and ignored</em> rather than refused. So the failure mode of getting
 * this wrong is silence — no warning, no error, and a silent revert to the driver's own default,
 * which is {@code true}, which is leaking. {@link #aLowerCasedKeyIsAcceptedAndIgnored()} pins that
 * by measuring it rather than quoting the driver's documentation.
 *
 * <p><strong>And what must survive the mask</strong>, because a privacy control that broke the
 * error handling would be reverted within a week: the constraint NAME stays in the primary
 * message. That is what Hibernate's dialect extractor reads, what
 * {@code WorkspaceService.isDuplicateInvite}'s locale-proof fallback matches, and what every
 * constraint-name assertion in this suite depends on. It is asserted here beside the value that
 * must not survive, so the two can never be traded for one another by accident.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class ServerErrorDetailMaskTest {

    /** The property PgJDBC understands, spelled the one way it accepts. */
    private static final String DRIVER_KEY = "logServerErrorDetail";

    /** Where it is set, so a failure names the file to edit. */
    private static final String SPRING_KEY =
            "spring.datasource.hikari.data-source-properties." + DRIVER_KEY;

    /** The documented one-session hatch. Named in {@code .env.prod.example} and self-hosting docs. */
    private static final String ENV_VAR = "DB_LOG_SERVER_ERROR_DETAIL";

    /** A third party's address — the exact shape of what the invite path would otherwise log. */
    private static final String VICTIM = "victim@example.test";

    private static final String PROBE_TABLE = "hd133_detail_probe";

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    @Autowired DataSource dataSource;
    @Autowired Environment environment;

    /**
     * The application's own pool: the constraint name survives, the colliding address does not.
     */
    @Test
    void theApplicationsPoolReportsTheConstraintAndNotTheCollidingAddress() throws Exception {
        try (var conn = dataSource.getConnection()) {
            var refusal = collide(conn);

            assertThat(chainOf(refusal))
                    .as("""
                            the colliding VALUE is what PostgreSQL puts in DETAIL and what PgJDBC \
                            concatenates into the message Hibernate logs at ERROR before any \
                            application code runs. On the invite path that value is a third \
                            party's email address — not even the caller's — which is why the mask \
                            belongs at the driver and not in a handler.""")
                    .doesNotContain(VICTIM)
                    .doesNotContain("Detail:")
                    .doesNotContain("Key (");
            assertThat(chainOf(refusal))
                    .as("""
                            AND THE HALF THAT MUST SURVIVE. The constraint name lives in the \
                            PRIMARY message, which the mask does not touch: it is what Hibernate's \
                            dialect extractor reads, what WorkspaceService.isDuplicateInvite's \
                            locale-proof fallback matches on a server whose lc_messages is not \
                            English, and what every constraint-name assertion in this suite \
                            depends on. A mask that took this too would be reverted within a week \
                            and the address would come back with it.""")
                    .contains(PROBE_TABLE + "_pkey");
            assertThat(refusal.getSQLState()).isEqualTo("23505");
        }
    }

    /**
     * <strong>The driver's own default is to leak</strong>, and that is what makes every other
     * assertion in this file non-vacuous: the address was there to be disclosed, and something we
     * configured is why it was not.
     */
    @Test
    void withoutThePropertyTheDriverPutsTheAddressInTheMessage() throws Exception {
        try (var conn = raw(null, null)) {
            assertThat(chainOf(collide(conn)))
                    .as("""
                            PgJDBC defaults logServerErrorDetail to TRUE. If this ever stops \
                            holding, the negative assertions in this file stop proving anything \
                            and the one in ReferencedRowConflictContractTest goes with them — so \
                            it is measured here rather than assumed from the driver's docs.""")
                    .contains(VICTIM);
        }
    }

    /**
     * <strong>A lower-cased key is accepted and ignored</strong> — the silent revert that decides
     * how the property is written in {@code application.properties}.
     *
     * <p>Spring's relaxed binding lower-cases the key of a bare environment variable, and PgJDBC
     * matches {@code logServerErrorDetail} case-sensitively while ignoring properties it does not
     * recognise. So the wrong spelling produces no warning, no error and no clue — just the
     * driver's default, which is the leak. Threading the value through
     * {@code ${DB_LOG_SERVER_ERROR_DETAIL:false}} keeps the literal key in the file, where it is
     * spelled once and can be read.
     */
    @Test
    void aLowerCasedKeyIsAcceptedAndIgnored() throws Exception {
        try (var conn = raw("logservererrordetail", "false")) {
            assertThat(chainOf(collide(conn)))
                    .as("""
                            the connection did not fail, which is the point: an unrecognised \
                            data-source property is ACCEPTED and IGNORED. The mask is simply not \
                            applied, so the address is back in the message with nothing anywhere \
                            saying so.""")
                    .contains(VICTIM);
        }
        try (var conn = raw(DRIVER_KEY, "false")) {
            assertThat(chainOf(collide(conn)))
                    .as("the same value under the exact spelling does mask it — so the difference "
                        + "above is the CASE of the key and nothing else")
                    .doesNotContain(VICTIM);
        }
    }

    /**
     * <strong>The floor is {@code false} and the hatch is a variable</strong> — asserted on the
     * shipped file, because the behaviour above would also be satisfied by a value hard-coded
     * where no operator could reach it.
     *
     * <p>Two things are pinned and each cost a review round. The <em>placeholder</em>, because
     * {@code ?logServerErrorDetail=true} appended to {@code DB_URL} does win at the driver but is
     * unreachable on a real deployment: {@code docker-compose.prod.yml} sets {@code DB_URL} as a
     * compose {@code environment:} literal so {@code .env} cannot override it, and that file is
     * replaced wholesale by a deploy — HD-122's silent-revert trap. And the <em>default</em>,
     * because it is a privacy floor rather than a tuning knob: identical in {@code dc} and
     * {@code cloud}, since the address a Cloud install would leak belongs to a third party and the
     * one a self-hoster would leak belongs to their own user, in their own logs.
     */
    @Test
    void thePropertyIsAVariableWithAFalseDefaultAndTheExactDriverSpelling() throws Exception {
        var line = lineFor(SPRING_KEY);

        assertThat(line)
                .as("""
                        the exact driver spelling, in the file, once. PgJDBC matches this key \
                        case-sensitively, so a relaxed-bound environment variable would arrive \
                        lower-cased and be silently ignored (see aLowerCasedKeyIsAcceptedAndIgnored). \
                        The literal key is what keeps that from ever being the wiring.""")
                .startsWith(SPRING_KEY + "=");
        assertThat(line)
                .as("""
                        a ${...} placeholder, not a literal: the supported hatch has to be a \
                        variable in .env, because DB_URL is a compose environment literal that a \
                        deploy replaces wholesale, and an operator's edit there would be lost \
                        without being told. And the DEFAULT is the privacy floor — same value in \
                        dc and cloud, since it is not a tuning knob.""")
                .isEqualTo(SPRING_KEY + "=${" + ENV_VAR + ":false}");
        assertThat(environment.getProperty(SPRING_KEY))
                .as("and it resolves to that default in a context nobody set the variable in — "
                    + "the placeholder is what an unset deployment actually gets")
                .isEqualTo("false");
    }

    // ================================================================ probes

    /**
     * Provokes a {@code 23505} whose DETAIL names {@link #VICTIM}. A temporary table, so it is
     * private to this session and cannot outlive it — and dropped explicitly anyway, because the
     * application's pooled connection goes back into the pool.
     */
    private SQLException collide(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
            st.execute("CREATE TEMP TABLE " + PROBE_TABLE + " (email varchar(255) PRIMARY KEY)");
            try {
                st.execute("INSERT INTO " + PROBE_TABLE + " VALUES ('" + VICTIM + "')");
                st.execute("INSERT INTO " + PROBE_TABLE + " VALUES ('" + VICTIM + "')");
                throw new AssertionError("the probe did not collide — a unique index is missing, "
                                         + "so this test proves nothing about masking");
            } catch (SQLException expected) {
                return expected;
            } finally {
                st.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
            }
        }
    }

    /** Every message in the cause chain, since PgJDBC may nest and Hibernate re-wraps. */
    private static String chainOf(Throwable t) {
        var sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    /** A connection made outside Spring, so the property under test can be varied or omitted. */
    private static Connection raw(String key, String value) throws SQLException {
        var props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        if (key != null) {
            props.setProperty(key, value);
        }
        return DriverManager.getConnection(URL, props);
    }

    /** The one line of the shipped {@code application.properties} that sets {@code key}. */
    private static String lineFor(String key) throws Exception {
        try (InputStream in = ServerErrorDetailMaskTest.class
                .getResourceAsStream("/application.properties")) {
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var matches = text.lines()
                    .map(String::trim)
                    .filter(l -> l.startsWith(key + "="))
                    .toList();
            assertThat(matches)
                    .as("exactly one line must set %s. None means the mask is gone and every SQL "
                        + "log in the application emits row values again; two means one of them "
                        + "is dead and a reader cannot tell which", key)
                    .hasSize(1);
            return matches.get(0);
        }
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
