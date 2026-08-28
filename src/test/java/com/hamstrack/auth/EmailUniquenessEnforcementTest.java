package com.hamstrack.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.JwtService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-167 — {@code users_email_lower_uk} enforced against a real PostgreSQL, at both
 * writers</strong> (acceptance criteria 7, 8, 9 and 11 of
 * {@code docs/design/email-uniqueness-proposal.md} §12).
 *
 * <p><strong>What makes this file necessary is that the two ways of arriving at the 409 are
 * INDISTINGUISHABLE from the response.</strong> The folded pre-check and the constraint
 * translation produce the same status, the same sentence and no discriminator — deliberately, so
 * a caller cannot learn whether the occupying row's spelling matches theirs. So a test that
 * asserted only the status code would keep passing with the translation deleted, with the index
 * dropped, or with the pre-check unfolded, and would report a green ticket in all three cases.
 * Every case below therefore carries a <em>second</em> observable that says which route answered.
 *
 * <p><strong>The two observables, and why each is the only one available.</strong>
 * <ul>
 *   <li><strong>The clock, for the pre-check.</strong> {@code register} returns from the pre-check
 *       <em>before</em> {@code passwordEncoder.encode}, and this application's encoder is bcrypt
 *       at strength 12 — a few hundred milliseconds of deliberate work. A 409 that cost a bcrypt
 *       went through the INSERT; one that did not, did not. The comparison is made against a
 *       successful registration measured in the same run rather than against a fixed number, so it
 *       is a statement about this machine and not about a constant somebody will have to retune.
 *       (That the two 409s differ on the clock at all is recorded in {@code AuthService.register}
 *       as an accepted residual, not a defect: flattening it would hand every unauthenticated
 *       caller a bcrypt-12 per request.)</li>
 *   <li><strong>A forced interleaving, for the constraint.</strong> A competing transaction
 *       inserts the row and holds it uncommitted; the request under test passes a pre-check that
 *       cannot see it (READ COMMITTED), blocks at {@code saveAndFlush} on the unique index, and
 *       receives the violation the moment the holder commits. Same vehicle, and the same reason
 *       for it, as {@code DuplicateInviteRefusalTest.aLostRaceAnswersTheSame409AndNeverA500}.</li>
 * </ul>
 *
 * <p><strong>The competitor's row is spelled in MIXED CASE, and that is the point rather than
 * flavour.</strong> {@code RACED@…} and {@code raced@…} are not byte-equal, so
 * {@code users_email_key} — the pre-V23 constraint — <em>cannot</em> fire on that insert. The only
 * constraint that can is {@code users_email_lower_uk}. So this is the one arrangement in which a
 * 409 proves the new index both exists and is translated; against a byte-identical competitor the
 * same test would pass on a database where V23 had never run.
 *
 * <p><strong>Why a squatter test is here at all, given that it does NOT reach the index.</strong>
 * The proposal filed AC 8 as "the reachable case" and amended it on 2026-08-28 when the measurement
 * said otherwise: the pre-check asks {@code lower(stored) = lower(:typed)}, the index enforces
 * uniqueness of {@code lower(stored)}, and both go through the <em>same</em> PostgreSQL
 * {@code lower()} — so they cannot disagree about a committed row, and the squatter never reaches
 * an INSERT. It stays because it pins the outcome an operator's install actually depends on, and
 * because the clock is what distinguishes "the pre-check saw it" from "the index caught it", which
 * is the difference between a refusal and a doomed write.
 *
 * <p><strong>Cleanup.</strong> Every row this file plants by hand is mixed-case, i.e. exactly the
 * shape V23's pre-flight refuses to migrate, so leaving them behind would seed the shared
 * development database with rows that block a future rebuild of it. They are tracked by id and
 * deleted after every case. Accounts created through {@code register} are left, as every other
 * test in this suite leaves them: they are ordinary folded rows and own child records.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "app.legal.terms-acceptance-required=false",
        "app.registration.public-signup-enabled=true",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class EmailUniquenessEnforcementTest {

    /** No SMTP in CI, and a real attempt is a five-second connect timeout per registration. */
    @MockitoBean JavaMailSender mailSender;

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @PersistenceContext EntityManager em;

    private final ObjectMapper json = new ObjectMapper();

    /** Every row planted by hand, so none of them outlives the case that planted it. */
    private final List<UUID> planted = new ArrayList<>();

    /**
     * A bare {@code @MockitoBean} answers {@code null} to {@code createMimeMessage()}, so every
     * verification mail this file triggers fails three times and DEAD-LETTERS — writing rows to
     * {@code mail_dead_letters} that have nothing to do with the subject, and a stack trace per
     * registration into a log somebody will read while diagnosing a real failure. Same stub, for
     * the same reason, as {@code MailDurabilityTest}.
     */
    @org.junit.jupiter.api.BeforeEach
    void giveTheMockASendableMessage() {
        org.mockito.Mockito.when(mailSender.createMimeMessage()).thenAnswer(
                inv -> new org.springframework.mail.javamail.JavaMailSenderImpl().createMimeMessage());
    }

    @AfterEach
    void removeThePlantedRows() {
        planted.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        planted.clear();
    }

    // ================================================================ the fold

    /**
     * <strong>A case variant of a registered address is refused</strong> (AC 7).
     *
     * <p>Already true before this ticket, through {@code Locale.ROOT} folding at the boundary, and
     * pinned here precisely because it is <em>already</em> true: a change to that fold — moving
     * it, weakening it, forgetting it on a new write path — would otherwise regress silently, and
     * the whole argument for V23 is that a rule which holds only while every writer remembers it
     * is not an invariant.
     */
    @Test
    void aCaseVariantOfARegisteredAddressIsRefused() throws Exception {
        var address = address("fold");

        register(address).andExpect(status().isCreated());
        var refusal = register(address.toUpperCase(Locale.ROOT)).andExpect(status().isConflict());

        assertThat(detailOf(refusal)).isEqualTo("Email is already registered");
        assertThat(rowsFolding(address))
                .as("one account, whichever spelling was typed")
                .isEqualTo(1);
    }

    /**
     * <strong>A mixed-case squatter is refused by the PRE-CHECK, with no INSERT attempted</strong>
     * (AC 8).
     *
     * <p>The row is planted by direct SQL because no writer in this application can produce it —
     * every one of them folds with {@code Locale.ROOT} — so it stands in for the row a foreign writer
     * leaves: the LDAP/SSO provisioning, admin bulk import and support scripts this ticket was
     * filed about.
     *
     * <p><strong>The clock is the assertion; the status code is the premise.</strong> Both routes
     * to this 409 produce byte-identical bodies, so a status-only test would pass with the
     * pre-check reverted to {@code existsByEmail} — at which point every ordinary signup against a
     * squatted key runs a doomed INSERT, answered 409 only for as long as the translation keeps
     * matching, and answered <strong>500</strong> the day it does not (a renamed constraint, a new
     * writer that forgets it). The refusal must not depend on the translation being right, and
     * this is the observable that says it does not.
     */
    @Test
    void aMixedCaseSquatterIsRefusedBeforeAnyInsertIsAttempted() throws Exception {
        var address = address("squatter");

        // Warm the path first — the first request through a fresh context pays for filter
        // initialisation and query-plan compilation, which would otherwise land on whichever
        // measurement went first and read as bcrypt.
        register(address("warmup")).andExpect(status().isCreated());
        var accepted = timed(() -> register(address("timing")).andExpect(status().isCreated()));

        plant(address.toUpperCase(Locale.ROOT));
        var refused = timed(() -> register(address).andExpect(status().isConflict()));

        assertThat(detailOf(refused.result())).isEqualTo("Email is already registered");
        assertThat(refused.millis() * 2)
                .as("""
                        THE PRE-CHECK ANSWERED, AND THE CLOCK IS THE ONLY THING THAT SAYS SO. \
                        register returns from the folded pre-check BEFORE passwordEncoder.encode, \
                        and this application's encoder is bcrypt at strength 12; a 409 that came \
                        back from the constraint instead paid that cost on the way to a doomed \
                        INSERT. Measured in this run: an accepted registration took %d ms, this \
                        refusal took %d ms. If they are comparable, the pre-check stopped seeing \
                        a stored row that differs only in case — which is what asking \
                        existsByEmail instead of existsByFoldedEmail does — and the 409 is now \
                        the translation's to lose.""",
                        accepted.millis(), refused.millis())
                .isLessThan(accepted.millis());
        assertThat(rowsFolding(address))
                .as("nothing was written: the squatter is the only row holding that folded key")
                .isEqualTo(1);
    }

    // ================================================================ the constraint

    /**
     * <strong>A lost race on the FOLDED index answers the same 409, never a 500</strong>
     * (AC 9, at {@code AuthService.register}).
     *
     * <p>This is the only reachable route to {@code EmailUniqueness} — stated as a measurement
     * rather than as a design intent, because the ticket's own text got it wrong twice. The
     * pre-check and the index ask the same question of the same PostgreSQL {@code lower()}, so
     * they can differ only by the window between them, and a window is a race.
     *
     * <p>The competitor's spelling is what makes this case about V23 rather than about V1:
     * {@code RACED@…} is not byte-equal to {@code raced@…}, so {@code users_email_key} cannot
     * fire and only {@code users_email_lower_uk} can. Delete V23 and this case does not merely
     * change its route — it returns <strong>201</strong>, with two accounts sharing one folded
     * address, which is the state the whole ticket exists to make impossible.
     */
    @Test
    @Timeout(60)
    void aLostRaceOnTheFoldedIndexIsStillTheSame409() throws Exception {
        var address = address("raced");
        var refusal = losingTo(address.toUpperCase(Locale.ROOT), () -> register(address));

        assertThat(refusal.result().andReturn().getResponse().getStatus())
                .as("""
                        the pre-check is the sentence and the index is the invariant, so the \
                        loser of a race must arrive at the same answer by the other route. A 500 \
                        is the outcome this ticket exists to remove — and it is what happens with \
                        the catch in AuthService.register deleted, or with EmailUniqueness no \
                        longer naming users_email_lower_uk. A 201 means the index is not \
                        enforcing at all.""")
                .isEqualTo(409);
        assertThat(detailOf(refusal.result()))
                .as("and the sentence is the pre-check's, to the byte — a caller must not be able "
                    + "to tell which route refused them, because that would say whether the "
                    + "occupying row's spelling matches their own")
                .isEqualTo("Email is already registered");
        assertThat(refusal.millis())
                .as("""
                        THE PROOF THAT THE INDEX ANSWERED AND NOT THE PRE-CHECK. The competing \
                        row is uncommitted while this request runs, so READ COMMITTED hides it \
                        from the pre-check; the request then blocks at saveAndFlush until the \
                        competitor commits ~800 ms later. A pre-check answer comes back in \
                        single-digit milliseconds. Measured: %d ms""".formatted(refusal.millis()))
                .isGreaterThan(300);
        assertThat(rowsFolding(address))
                .as("""
                        exactly one account holds that folded address, which is the whole \
                        invariant. Two here means users_email_lower_uk did not exist or did not \
                        apply — and note that users_email_key CANNOT have produced this refusal, \
                        because the two spellings are not byte-equal.""")
                .isEqualTo(1);
    }

    /**
     * <strong>The same race at the OTHER writer</strong> (AC 9, at {@code AdminUserService.create}).
     *
     * <p>The translation is one class, so the decision is already sealed by
     * {@code EmailUniquenessTranslationTest}. What is not shared, and what this case is for, is
     * the <em>wiring</em>: the {@code catch} and — the part that is easy to lose in a refactor —
     * the {@code saveAndFlush}. A bare {@code save()} defers the INSERT to commit, where the
     * violation is raised after the service method has returned and no catch of ours can see it;
     * the endpoint would then 500 with the class looking perfectly correct.
     *
     * <p>An administrator losing this race is rarer than a signup losing it, and that is an
     * argument for the test rather than against it: nobody will meet this in the wild in time to
     * report it, and the console shows an unexplained server error where "Email is already
     * registered" is on the screen one row above.
     */
    @Test
    @Timeout(60)
    void theAdminWriterAnswersTheSameWayWhenItLosesTheRace() throws Exception {
        var token = "Bearer " + jwtService.generateAccessToken(admin());
        var address = address("admin-raced");

        var refusal = losingTo(address.toUpperCase(Locale.ROOT), () -> mockMvc.perform(
                post("/api/admin/users")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"displayName\":\"Raced Admin\"}")));

        assertThat(refusal.result().andReturn().getResponse().getStatus())
                .as("""
                        AdminUserService.create carries the same contract as register and it is \
                        the flush that makes the catch reachable at all: with save() instead of \
                        saveAndFlush the INSERT happens at commit, past this method, and the \
                        admin console shows an unexplained 500 for a condition it has a sentence \
                        for. Fixing one writer and not the other is how one idiom becomes two.""")
                .isEqualTo(409);
        assertThat(detailOf(refusal.result())).isEqualTo("Email is already registered");
        assertThat(refusal.millis()).isGreaterThan(300);
        assertThat(rowsFolding(address)).isEqualTo(1);
    }

    // ================================================================ what must NOT fold

    /**
     * <strong>{@code login} still resolves by EXACT match, so a squatter answers to nobody</strong>
     * (AC 11).
     *
     * <p>The rule this pins is the one that decides which comparisons V23 was allowed to change:
     * <em>a check that can only REFUSE folds; a lookup that RESOLVES an identity compares
     * exactly.</em> An extra match on a refusal declines someone who was entitled — recoverable,
     * visible, and the caller is told. An extra match on a resolution admits the wrong person.
     * This is also the deciding argument in ADR-0016 for an index over {@code citext}: under a
     * case-insensitive type this lookup would silently become case-insensitive, and on a database
     * holding {@code Ivan@} and {@code ivan@} as two people the typed address would resolve to
     * whichever row the planner returned — the guarantee and the hazard in one commit, with the
     * hazard on the authentication path.
     *
     * <p>Both spellings are asserted, and the second is the one worth reading: the mixed-case
     * account cannot log in <strong>under its own spelling either</strong>, because
     * {@code login} folds the typed address before looking it up. That is not a gap — it is the
     * fail-closed direction, and it is exactly the "this row's owner already cannot log in"
     * V23's pre-flight refuses upgrades over.
     */
    @Test
    void aMixedCaseAccountAnswersToNeitherSpellingAndTheFoldedOneStillWorks() throws Exception {
        var squatted = address("squat");
        var ordinary = address("ordinary");
        plant(squatted.toUpperCase(Locale.ROOT));
        register(ordinary).andExpect(status().isCreated());
        activate(ordinary);

        assertThat(login(squatted).andReturn().getResponse().getStatus())
                .as("""
                        THE FOLDED ADDRESS MUST NOT RESOLVE TO THE MIXED-CASE ROW. login uses \
                        findByEmail — exact — on purpose. Fold this lookup (or make the column \
                        citext) and, on a database that holds both spellings as two people, the \
                        typed address resolves to whichever row the planner returns.""")
                .isEqualTo(401);
        assertThat(login(squatted.toUpperCase(Locale.ROOT)).andReturn().getResponse().getStatus())
                .as("""
                        and it does not answer to its OWN spelling either, because login folds \
                        the typed address before looking it up. Stated here rather than left \
                        implicit, because it is the premise of V23's pre-flight: a mixed-case row \
                        cannot log in and cannot be mailed a reset link TODAY, which is why a \
                        lone one blocks the upgrade instead of being waved through as harmless.""")
                .isEqualTo(401);
        assertThat(login(ordinary).andReturn().getResponse().getStatus())
                .as("the control: an ordinary folded account with the same password still logs "
                    + "in, so the two refusals above are about the stored spelling and not about "
                    + "the fixture being broken")
                .isEqualTo(200);
    }

    // ================================================================ vehicles

    /**
     * Runs {@code request} while a competing transaction holds an uncommitted {@code users} row
     * for {@code competingSpelling}, and returns the answer with its wall-clock cost.
     *
     * <p>The competitor is deliberately not a second HTTP request: two registrations racing on a
     * scheduler would reach the index only by luck, and a case that reached it by luck stops
     * covering the translation on the first machine where the luck runs out — silently, and while
     * still passing. Holding the row uncommitted for ~800 ms makes the interleaving a fact.
     */
    private Timed losingTo(String competingSpelling, ThrowingSupplier request) throws Exception {
        var inserted = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var id = UUID.randomUUID();
        planted.add(id);

        var competitor = new Thread(() -> {
            try {
                transactions.executeWithoutResult(status -> {
                    em.createNativeQuery("""
                                    INSERT INTO users (id, email, display_name, status, system_role)
                                    VALUES (:id, :email, 'HD-167 competitor', 'ACTIVE', 'USER')
                                    """)
                            .setParameter("id", id)
                            .setParameter("email", competingSpelling)
                            .executeUpdate();
                    em.flush();
                    inserted.countDown();
                    // Long enough for the request under test to reach its own insert and block on
                    // the index, short enough to stay well inside the lock timeout it runs under.
                    sleep(800);
                });
            } catch (Throwable t) {
                failure.set(t);
                inserted.countDown();
            }
        }, "hd167-competitor");
        competitor.start();

        assertThat(inserted.await(10, TimeUnit.SECONDS))
                .as("the competing transaction never got its row in, so nothing was raced")
                .isTrue();
        var timed = timed(request);
        competitor.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(failure.get())
                .as("the competitor itself failed, so this measured something else entirely")
                .isNull();
        return timed;
    }

    /** A request and what it cost, so a case can assert on both without measuring twice. */
    private record Timed(ResultActions result, long millis) {
    }

    private Timed timed(ThrowingSupplier request) throws Exception {
        var startedAt = System.nanoTime();
        var result = request.get();
        return new Timed(result, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private interface ThrowingSupplier {
        ResultActions get() throws Exception;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ fixture

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\","
                         + "\"displayName\":\"HD-167 Tester\",\"termsAccepted\":true}"));
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private static final String PASSWORD = "hd167-test-password";

    /**
     * A {@code users} row written by DIRECT SQL, which is the only way to produce the mixed-case
     * spelling under test: all three application writers fold with {@code Locale.ROOT}, and V23's
     * pre-flight refuses to migrate a database that holds one.
     */
    private void plant(String email) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, status, system_role)
                VALUES (?, ?, 'HD-167 squatter', ?, 'ACTIVE', 'USER')
                """, id, email, passwordEncoder.encode(PASSWORD));
        planted.add(id);
    }

    /** Registration leaves an account PENDING; login has a different refusal for that. */
    private void activate(String email) {
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE email = ?", email);
    }

    private User admin() {
        var user = new User();
        user.setEmail(address("sysadmin"));
        user.setDisplayName("HD-167 Admin");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setStatus(UserStatus.ACTIVE);
        user.setSystemRole(SystemRole.ADMIN);
        return userRepository.save(user);
    }

    /** Counted the way the index counts, so a case-variant duplicate would show up as two. */
    private long rowsFolding(String email) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE lower(email) = lower(?)", Long.class, email);
    }

    /** Unique per run, so no case can ever collide with another's address. */
    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@hd167.test";
    }

    private String detailOf(ResultActions actions) throws Exception {
        var node = json.readTree(actions.andReturn().getResponse().getContentAsString());
        return node.hasNonNull("detail") ? node.get("detail").asText() : "";
    }
}
