package com.hamstrack.common.seed;

import com.hamstrack.HamstrackApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * <strong>HD-171 §4.4 rule 9 / AC 9b — the third door that writes a password, and the three
 * conditions its guard is gated on.</strong>
 *
 * <p>{@code seed.admin.password} is hashed by the same {@code BCryptPasswordEncoder} as the two HTTP
 * doors, so it shares their 72-byte ceiling. The guard exists <strong>only to replace a
 * message</strong>: without it the boot still fails, inside {@code encode}, with
 * {@code "password cannot be more than 72 bytes"} and no mention of the variable, the account or any
 * remedy — a guard that trailed the failure it was added to pre-empt.
 *
 * <p><strong>The pairing is with the WRITING DTOs, not with {@code LoginRequest}.</strong> An
 * earlier round paired this number to the login door's 1024, which pairs with nothing: login is a
 * reading door whose {@code matches} truncates safely, and its bound is a resource guard justified
 * as "finite". The number that must agree with 72 is the one on
 * {@code RegisterRequest.password} and {@code ResetPasswordRequest.newPassword} — and it agrees
 * <em>as bytes</em>, which is why both guards are needed at those doors and why this one counts
 * bytes too.
 *
 * <p><strong>The length guard is gated on "will this value actually be encoded?", and that is three
 * conditions rather than one.</strong> The published-password guard beside it is deliberately gated
 * on none of them — a published password is a compromise whether or not seeding happens, because an
 * earlier boot may already have created the account — but a <em>length</em> guard inherits no such
 * reason: an over-long value that is never encoded created nothing. Round 3 shipped only two of the
 * three conditions and refused a boot over a value nothing reads: an operator who rotates
 * {@code SEED_ADMIN_PASSWORD} to an {@code openssl rand -base64 96} value after a successful first
 * seed changed nothing that is ever encoded, and the next restart failed to boot.
 *
 * <p><strong>Why these cases boot a whole application.</strong> The claim is "the boot is refused",
 * or "the boot is not refused", and the third condition reads {@code users} — so the vehicle has to
 * be a boot against the real database, with the fixture row planted before the context exists. Same
 * reasoning, same shape, as {@code SeedAdminSquatterRefusalTest}.
 */
class SeedPasswordLimitTest {

    /** 73 ASCII characters: 73 bytes, one past the encoder's ceiling, and not a published value. */
    private static final String OVER_LONG = "z9Kq" + "a".repeat(69);

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java", "com", "hamstrack");

    /** The two doors that WRITE a password, and the component whose {@code @Size} must agree. */
    private static final Map<String, String> WRITING_DOORS = Map.of(
            "auth/dto/RegisterRequest.java", "password",
            "auth/dto/ResetPasswordRequest.java", "newPassword");

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    private final List<UUID> planted = new ArrayList<>();

    @AfterEach
    void removePlantedRows() throws SQLException {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            for (var id : planted) {
                st.execute("DELETE FROM users WHERE id = '" + id + "'");
            }
        }
        planted.clear();
    }

    // ------------------------------------------------------------------ the pairing (AC 9b, half 1)

    @Test
    void theSeedCeilingIsTheSameNumberTheWritingDoorsAdvertise() throws IOException {
        assertThat(OVER_LONG.getBytes(StandardCharsets.UTF_8).length)
                .as("the fixture must be exactly one byte past the ceiling")
                .isEqualTo(DataSeeder.MAX_SEED_PASSWORD_BYTES + 1);

        var resolved = 0;
        for (var door : WRITING_DOORS.entrySet()) {
            var source = Files.readString(MAIN_SOURCES.resolve(door.getKey()), StandardCharsets.UTF_8);
            var declaration = Pattern.compile(
                            "@Size\\s*\\([^)]*\\bmax\\s*=\\s*(\\d+)[^)]*\\)[^;,)]*?\\bString\\s+"
                            + door.getValue() + "\\b")
                    .matcher(source);
            assertThat(declaration.find())
                    .as("no @Size(max = …) resolves to %s#%s — the declaration's layout changed and "
                        + "this check silently stopped reading it", door.getKey(), door.getValue())
                    .isTrue();
            resolved++;
            assertThat(Integer.parseInt(declaration.group(1)))
                    .as("""
                            %s#%s advertises a range the encoder refuses. BCrypt throws above %d \
                            BYTES when creating a hash, so a door promising more answers 500 to \
                            plain user input. The @Size is the coarse half (it counts UTF-16 \
                            units); AuthService.rejectUnencodablePassword is the half that counts \
                            bytes. Both numbers move together or neither moves.""",
                            door.getKey(), door.getValue(), DataSeeder.MAX_SEED_PASSWORD_BYTES)
                    .isEqualTo(DataSeeder.MAX_SEED_PASSWORD_BYTES);
        }
        assertThat(resolved)
                .as("both writing doors must be read — one that stopped matching is a door with no "
                    + "checked bound, under an assertion that stays green")
                .isEqualTo(WRITING_DOORS.size());
    }

    // ------------------------------------------------- the gate (AC 9b, half 2): refused when read

    @Test
    void anOverLongSeedPasswordThatWouldBeEncodedRefusesTheBoot() {
        var address = address("would-encode");

        var failure = catchThrowable(() -> close(boot(address)));

        assertThat(failure)
                .as("""
                        seed.admin.email names an account that does not exist yet, so run() WOULD \
                        reach passwordEncoder.encode — and encode throws. The boot fails either \
                        way; what this guard buys is a message an operator can act on instead of \
                        "password cannot be more than 72 bytes" from inside a crypto library.""")
                .isNotNull();
        var refusal = rootCauseOf(failure);
        assertThat(refusal).isInstanceOf(IllegalStateException.class);
        assertThat(refusal.getMessage())
                .as("""
                        the refusal must name the VARIABLE the operator edits, the LIMIT in the \
                        unit BCrypt measures in, and a remedy they can perform right now — the \
                        application is down, so "log in and change it" is not one. It must also \
                        explain the arithmetic: 72 bytes is not something a person typing a \
                        passphrase can evaluate.""")
                .contains("SEED_ADMIN_PASSWORD")
                .contains(String.valueOf(DataSeeder.MAX_SEED_PASSWORD_BYTES))
                .contains("bytes")
                .contains("SEED_ADMIN_EMAIL");
    }

    // ------------------------------------------- and NOT refused when the value is never encoded

    /**
     * No {@code seed.admin.email} at all: nothing is seeded, so nothing is encoded, so an over-long
     * value is a string in a file that no code path reads.
     */
    @Test
    void anOverLongSeedPasswordBootsNormallyWithNoSeedAddressConfigured() {
        close(boot(""));
    }

    /**
     * <strong>The condition round 3 shipped without, and the one an upgrade actually meets.</strong>
     * Seeding is idempotent: with an account already at that folded address, {@code run} takes the
     * "found the user" branch and never encodes anything. An operator who rotated
     * {@code SEED_ADMIN_PASSWORD} to a long random value after their first successful seed — or who
     * pointed {@code SEED_ADMIN_EMAIL} at an account made by registration — changed nothing that is
     * ever read, and must not be met with a failed boot on their next restart.
     */
    @Test
    void anOverLongSeedPasswordBootsNormallyWhenTheAccountAlreadyExists() throws SQLException {
        var address = address("already-there");
        plant(address);

        close(boot(address));
    }

    // ================================================================ the vehicle

    private ConfigurableApplicationContext boot(String email) {
        return new SpringApplicationBuilder(HamstrackApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .properties(
                        "seed.admin.email=" + email,
                        "seed.admin.password=" + OVER_LONG,
                        "app.demo.seed-on-first-login=false",
                        "app.rate-limit.enabled=false",
                        "spring.docker.compose.enabled=false")
                .run();
    }

    private static void close(ConfigurableApplicationContext context) {
        if (context != null) context.close();
    }

    /** Walked rather than matched, because AssertJ's {@code rootCause()} refuses a bare throwable. */
    private static Throwable rootCauseOf(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    // ================================================================ fixture

    private void plant(String email) throws SQLException {
        var id = UUID.randomUUID();
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("""
                    INSERT INTO users (id, email, display_name, status, system_role)
                    VALUES ('%s', '%s', 'HD-171 seed fixture', 'ACTIVE', 'USER')
                    """.formatted(id, email));
        }
        planted.add(id);
    }

    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@hd171.test";
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
