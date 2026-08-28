package com.hamstrack.common.seed;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
// The eagerness is the point, and it is not style. refusePublishedCredentials() below is
// worth exactly its MOMENT — it has to run inside finishBeanFactoryInitialization, before
// finishRefresh re-adds the connectors — and until now nothing pinned that: under
// spring.main.lazy-initialization=true this bean is not instantiated until
// SpringApplication.callRunners, the very call site the guard was moved off, so the port is
// already bound when it refuses. Measured on a real SpringApplication with a real Tomcat:
// shipped and eager, 0 successful connections; the property alone, 386; @Lazy on this class
// and no property, 6; @Lazy(false) plus the property, 0 again. Those counts come from the
// test vehicle, which verifies one strength-4 hash - they establish THAT a window opens,
// not how wide. Nor is it redundant with the default —
// LazyInitializationBeanFactoryPostProcessor.postProcess returns early on any
// definition whose lazy-init flag is EXPLICITLY set, which is the whole of what this
// annotation does, so the property can no longer reach this bean. Deleting it as noise does
// not tidy a style, it reopens the window: the guard then runs from callRunners, the same
// frame in which three real boots measured 7.19 s, 7.34 s and 7.96 s of working login as the
// published-password administrator. Sealed by
// SeedGuardStartupOrderingTest.nothingDefersTheGuardPastThePortBind.
@Lazy(false)
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${seed.admin.email:}")
    private String adminEmail;

    @Value("${seed.admin.display-name:Admin}")
    private String adminDisplayName;

    @Value("${seed.admin.password:}")
    private String adminPassword;

    /**
     * Every value this project has published under this variable's own name. There is one:
     * {@code .env.prod.example} shipped {@code SEED_ADMIN_PASSWORD=SEED_ADMIN_PASSWORD}, so
     * every install created from the unedited template has an ACTIVE system administrator
     * whose email and password are both printed in a public repository (HD-200). No forging
     * and nothing to guess — and because a correct password is not a failed attempt, the
     * per-account login backoff never engages.
     *
     * <p><strong>Why a refusal and not just an emptied template.</strong> Emptying the
     * template cannot reach an installation that already copied it: seeding is idempotent,
     * so an operator who upgrades hits the "account exists, skip" branch and keeps the
     * published-password admin, with nothing anywhere saying so. Same reasoning, and the
     * same shape, as {@code JwtService.PUBLISHED_PLACEHOLDERS}.
     *
     * <p><strong>This is not a weak-password check and must never grow into one.</strong> A
     * denylist of bad passwords invites "mine is not on the list" and goes stale by the
     * week. What justifies this entry does not generalise: <em>we</em> published it, under
     * this variable's own name, so it can be named exactly — an instance running it is not
     * weakly protected, it is publicly administrable. The set's size is pinned by
     * {@code SeedAdminPasswordValidationTest}, which states the evidentiary standard for
     * adding a second.
     *
     * <p><strong>Three readers, one set.</strong> The configuration guard below, the
     * stored-hash guard below that, and {@code AuthService} — which refuses the same value
     * at the two places a password can enter {@code users} through the running application
     * (register, and completing a reset). Without that third reader an administrator could
     * type the published literal into "choose a new password", brick the next boot, and
     * leave the instance publicly administrable until somebody restarted it.
     */
    static final Set<String> PUBLISHED_PASSWORDS = Set.of("SEED_ADMIN_PASSWORD");

    /**
     * Is this the value this project published? Whitespace-insensitive for the reason
     * {@link #rejectPublishedPassword} gives, and the single place that question is asked —
     * so the guards at startup and the guards on the write paths cannot come to disagree
     * about what the set contains.
     */
    public static boolean isPublishedPassword(String password) {
        return password != null && PUBLISHED_PASSWORDS.contains(password.strip());
    }

    /**
     * Both refusals, at <strong>context refresh</strong>.
     *
     * <p><strong>The moment is the point, and it is not tidiness.</strong> An
     * {@code ApplicationRunner} runs from {@code callRunners()}, which Boot invokes
     * <em>after</em> the refresh that binds Tomcat's connectors — so a guard living there
     * refuses an instance that is already answering requests. Measured on a boot where the
     * offending administrator was the last candidate: {@code Started HamstrackApplication}
     * at 03:34:53.898, refusal at 03:35:01.088, and a login as the published-password
     * administrator succeeded inside that window and walked away with a 30-minute access
     * token. {@code restart: unless-stopped} then re-opens the window on every crash-loop
     * cycle, indefinitely. Initialising here instead fails during
     * {@code finishBeanFactoryInitialization}, before {@code finishRefresh} re-adds the
     * connectors that {@code TomcatWebServer} removed — so the port is never bound at all.
     *
     * <p>"It needs the database" is not a reason to run later: the repository is injected
     * and fully usable here (the {@code EntityManagerFactory} already depends on Flyway),
     * which is why both halves fit in one place.
     *
     * <p>Configuration first, because it is free and names the file the operator edits; the
     * stored-hash probe costs bcrypt and reaches the installations whose configuration has
     * nothing left to say. Sealed by {@code SeedGuardStartupOrderingTest}, which asserts the
     * MOMENT and not only the message: one earlier round moved the configuration guard into
     * {@code run} below its {@code return}s and the whole suite stayed green, and a later
     * one left the stored-hash guard in {@code run} for seven seconds of working login.
     */
    @PostConstruct
    void refusePublishedCredentials() {
        rejectPublishedPassword(adminPassword, adminEmail);
        rejectPublishedAdminHash();
    }

    /**
     * The CONFIGURATION half: what this installation is <em>told</em> to seed. It refuses on
     * the upgrade path too, where the account already exists and nothing else would ever
     * mention it again.
     *
     * <p>Asked ahead of the blank-email and blank-password branches in {@link #run}: those
     * skip seeding, and skipping is exactly what an already-seeded installation does. The
     * question is not "will I create an account?" but "is the password in this file
     * public?", and that has the same answer either way.
     *
     * <p>Static so the guard can be exercised — and the message read — without a Spring
     * context, and so the repository-wide scan in {@code JwtSecretValidationTest} can ask
     * "would the application refuse this published value?" of this variable as it already
     * does of {@code JWT_SECRET}.
     *
     * @param email the configured {@code seed.admin.email}, used only to NAME the account in
     *              the refusal. The operator may not remember creating it, and an upgrade
     *              that stops booting has to say which user it is talking about. (Routine
     *              log lines in this class still never carry the address; a fatal refusal
     *              the operator must act on is the one place that trade goes the other way.)
     */
    public static void rejectPublishedPassword(String password, String email) {
        // Stripped before the comparison: a dotenv line is read verbatim, so
        // `SEED_ADMIN_PASSWORD=SEED_ADMIN_PASSWORD ` (one trailing space, which no editor
        // shows) is a DIFFERENT string to this set and an IDENTICAL one to bcrypt — the
        // account it seeds is signed into with the published value all the same. A guard
        // that can be bypassed by whitespace nobody can see is not a guard.
        if (!isPublishedPassword(password)) {
            return;
        }
        boolean named = email != null && !email.isBlank();
        String account = named
                ? email.strip().toLowerCase(Locale.ROOT)
                : "the administrator seeded by an earlier SEED_ADMIN_EMAIL (this configuration no longer "
                  + "names one, which does not remove the account)";
        // Goes into a statement the operator pastes into psql. Nothing is executed here - this
        // is a message - but a remedy that is invalid SQL is a remedy nobody can run, which is
        // the failure mode this whole ticket keeps finding. When the address is gone the
        // statement cannot be completed for them, so it says so instead of inventing one.
        String target = named ? "'" + account.replace("'", "''") + "'" : "'…that administrator…'";
        throw new IllegalStateException(
                "seed.admin.password (SEED_ADMIN_PASSWORD) is the value this project published in its own "
                + ".env.prod.example, so the system administrator it seeds — " + account + " — can be signed "
                + "into by anyone who can read the repository: both halves of the credential are printed "
                + "there. It is refused by name because nothing else refuses it — a correct password is not a "
                + "failed login attempt, so the per-account backoff never engages. "
                + "REMOVING THIS VARIABLE DOES NOT CHANGE THE ACCOUNT: seeding is idempotent, so an upgrade "
                + "finds the existing user and skips, and the published password keeps working — which the "
                + "startup check on the STORED password will go on saying after you edit this file, because "
                + "it reads the account and not the configuration. So repair the ACCOUNT, and do it with the "
                + "application down, because it will not start for you to reach the admin console: "
                + "UPDATE users SET password_hash = NULL WHERE email = " + target + "; — that keeps "
                + "everything the account owns and only takes its password away. Then start up and set a new "
                + "one from 'Forgot password' on that address, or from another system administrator, Admin "
                + "console → Users → reset it; or delete the account there if it should never have existed. "
                + "Only then set a password of your own here — or remove seed.admin.email/seed.admin.password "
                + "entirely, which leaves the repaired account exactly as it is.");
    }

    /**
     * How many administrators {@link #rejectPublishedAdminHash()} verifies, oldest first.
     * The repository method name pins the same number a second time
     * ({@code findFirst25By…}), because Spring Data reads it out of the name — change both
     * or this constant becomes a comment.
     */
    static final int ADMINS_PROBED = 25;

    /**
     * <strong>Per-JVM memo of a pure function.</strong> "Does this stored bcrypt hash verify
     * one of the published passwords?" depends on nothing but its two inputs, and one of
     * them is a compile-time constant — so the answer for a given hash cannot change while
     * the process lives.
     *
     * <p>It exists for the test suite, where it is worth about five minutes: 47 Spring
     * contexts each refresh a {@code DataSeeder} against the same database, and the shared
     * test database has accumulated well over a thousand administrators, so the same
     * {@value #ADMINS_PROBED} hashes were verified 47 times over. A production JVM refreshes
     * one context, so there this holds at most {@code ADMINS_PROBED + 1} entries and is
     * never read twice.
     *
     * <p>It weakens nothing the seals assert: the key is the stored hash itself, so a
     * repaired account — a new password, or {@code password_hash = NULL} — is a different
     * key (or no key at all) and is verified afresh. No plaintext is held.
     */
    private static final Map<String, Boolean> VERIFIED_HASHES = new ConcurrentHashMap<>();

    /**
     * <strong>The state, not the configuration.</strong> {@link #rejectPublishedPassword}
     * reads {@code seed.admin.password}, which is what an installation is <em>configured</em>
     * with — and the installations that are actually compromised are exactly the ones whose
     * configuration no longer says anything. Two of them:
     *
     * <ul>
     *   <li>the operator who reads the refusal and does the intuitive thing — a new password
     *       in {@code .env}, restart — and gets a clean boot, because seeding is idempotent
     *       and the existing-account branch never re-passwords a user. The file is repaired;
     *       the account is not;</li>
     *   <li>the operator who deleted the {@code SEED_ADMIN_*} lines any time before 0.18.0,
     *       for whom the configuration guard has nothing left to look at.</li>
     * </ul>
     *
     * <p>So this asks the database instead: does any system administrator's stored hash
     * verify the published password? That reaches both, and it stops being true the moment
     * the account is actually repaired, which is the property a guard on configuration does
     * not have.
     *
     * <p><strong>It refuses rather than warns</strong>, for the same reason everything else
     * in HD-200 refuses: the account is signable-into by anyone who can read a public
     * repository, and a WARN in a startup log is read after the incident. The cost of that
     * choice is real and unusual here — this can fire on an install whose operator did
     * nothing wrong today, and it fires at the one moment they cannot use the admin console
     * to fix it — so the message has to carry them all the way to repaired without the
     * application running, and it does: one SQL statement, then boot, then a normal reset.
     *
     * <p><strong>Cost, and the bound it forced.</strong> Measured rather than estimated, and
     * at the strength {@code SecurityConfig} actually configures: bcrypt at strength 12 costs
     * about <strong>370 ms</strong> per verification on the machine this was timed on — not
     * the ~100 ms this javadoc used to claim, which is strength 10. The loop runs once per
     * (administrator × published password), so the bound is ~6.9 s of startup today and
     * would double silently if a second entry were ever added to
     * {@link #PUBLISHED_PASSWORDS}. Hence the bound at all: the {@value #ADMINS_PROBED}
     * OLDEST administrators carrying a password, plus whichever account
     * {@code seed.admin.email} names today. An unbounded version was written first and was
     * not viable — a database that had accumulated 1362 administrators added over two
     * minutes to every single startup.
     *
     * <p><strong>Two mechanisms, not one guarantee.</strong> It is tempting to write that the
     * seeded account is the first administrator by construction; the by-name lookup below is
     * the code conceding that it is not.
     * <ul>
     *   <li>The <em>ordering</em> reaches the account the installer creates on first boot —
     *       but only while fewer than {@value #ADMINS_PROBED} administrators predate it,
     *       which is the usual case and not a property.</li>
     *   <li>The <em>by-name</em> lookup reaches whatever {@code seed.admin.email} names
     *       today, wherever it sits in that ordering, which is how an install that began
     *       seeding years in is covered — but only while the variable is still set, and this
     *       method exists precisely because it often is not.</li>
     * </ul>
     * Together they still miss an administrator who is neither among the oldest nor
     * currently configured, so the shortfall is logged rather than narrowed silently, and a
     * partial check never passes for a complete one.
     */
    void rejectPublishedAdminHash() {
        var candidates = new LinkedHashMap<UUID, User>();
        for (User admin : userRepository
                .findFirst25BySystemRoleAndPasswordHashIsNotNullOrderByCreatedAtAsc(SystemRole.ADMIN)) {
            candidates.put(admin.getId(), admin);
        }
        // The one the configuration names today, which need not be among the oldest: an
        // install that began seeding years in is exactly the case the ordering above misses.
        if (adminEmail != null && !adminEmail.isBlank()) {
            userRepository.findByEmail(adminEmail.strip().toLowerCase(Locale.ROOT))
                    .filter(user -> user.getSystemRole() == SystemRole.ADMIN)
                    .filter(user -> user.getPasswordHash() != null && !user.getPasswordHash().isBlank())
                    .ifPresent(user -> candidates.put(user.getId(), user));
        }

        // Counted over the population the probe can actually examine - administrators that
        // HAVE a password - and reported as what was verified rather than as the constant.
        // Counting the whole role made this fire on installs where every password-carrying
        // administrator HAD been checked (26 admins, 22 with a password, all 22 verified,
        // and it still announced a shortfall and named 25 as the number probed). It could
        // never produce a false negative, but a partial-coverage warning that fires on
        // complete coverage is how such a warning gets tuned out.
        long withPassword = userRepository.countBySystemRoleAndPasswordHashIsNotNull(SystemRole.ADMIN);
        if (withPassword > candidates.size()) {
            // Said out loud rather than silently narrowed. The address is not logged - see
            // the note on rejectPublishedPassword about where that trade goes the other way.
            log.warn("Published-password check verified {} of the {} system administrators that have a "
                     + "password; {} were not verified (the probe covers the {} oldest, plus the account "
                     + "seed.admin.email names)",
                    candidates.size(), withPassword, withPassword - candidates.size(), ADMINS_PROBED);
        }

        var offenders = candidates.values().stream()
                .filter(user -> carriesAPublishedPassword(user.getPasswordHash()))
                .map(User::getEmail)
                .sorted()
                .toList();
        if (offenders.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "A system administrator on this instance HAS the password this project published in its "
                + "own .env.prod.example: " + String.join(", ", offenders) + ". This is read from the "
                + "STORED password and not from configuration, so it is true whatever SEED_ADMIN_PASSWORD "
                + "says today — including when it says nothing. If you already set a new password there and "
                + "restarted, that is exactly why you are seeing this: seeding is idempotent, it finds the "
                + "existing user and skips, and it never re-passwords one. You did nothing wrong today; the "
                + "account has been signable-into by anyone who can read the repository since it was created. "
                + "REPAIR IT WITH THE APPLICATION DOWN — this refusal happens while the context is still "
                + "starting, so the instance never binds its port and there is no admin console to reach. "
                + "Connect to the database and clear the password, which keeps the account and everything it "
                + "owns: UPDATE users SET password_hash = NULL WHERE email IN (…the address(es) above…); "
                + "then start the application, and use 'Forgot password' on that address to set a new one "
                + "(or, from another system administrator, Admin console → Users → reset it; or delete the "
                + "account there if it was never meant to exist). AND TREAT THIS AS A COMPROMISE RATHER THAN "
                + "A MISCONFIGURATION: the account has carried the published password for as long as it has "
                + "existed, and every version before the one printing this served requests with it active — "
                + "so the question is not whether the instance was ever reachable with that account, it is "
                + "who could reach it. DELETE FROM refresh_tokens, DELETE FROM password_resets WHERE "
                + "used_at IS NULL, and check the users list for accounts you did not create. Step by step: "
                + "docs/self-hosting.md, \"If your instance has the published admin account\".");
    }

    /**
     * Memoised per JVM — see {@link #VERIFIED_HASHES} for why that is a memo of a pure
     * function rather than a cache of a security decision.
     */
    private boolean carriesAPublishedPassword(String passwordHash) {
        return VERIFIED_HASHES.computeIfAbsent(passwordHash, hash -> PUBLISHED_PASSWORDS.stream()
                .anyMatch(published -> passwordEncoder.matches(published, hash)));
    }

    @Override
    public void run(ApplicationArguments args) {
        // NO GUARD LIVES HERE, and neither may move back. Both refusals are in
        // refusePublishedCredentials(), a @PostConstruct: a runner executes AFTER the
        // refresh that binds the port, so a guard here refuses an instance that is already
        // serving - three independent boots measured 7.19 s, 7.34 s and 7.96 s of working
        // login as the published-password administrator. The number is machine-dependent and
        // its size is not the point: the point is that the number exists at all, and that
        // `restart: unless-stopped` re-opens the window on every crash-loop cycle, forever.
        // SeedGuardStartupOrderingTest seals both the moment (the context fails while
        // refreshing) and its consequence (no WebServerInitializedEvent is ever published).
        if (adminEmail.isBlank()) {
            log.info("Admin seeding skipped — seed.admin.email not configured");
            return;
        }
        if (adminPassword.isBlank()) {
            log.warn("Admin seeding skipped — seed.admin.email is set but seed.admin.password is empty");
            return;
        }
        // Lowercase to match login, which looks the email up lowercased. Locale.ROOT for the
        // reason AuthService.register gives, and with a consequence unique to this class: the
        // lookup below is what decides between "promote the existing admin" and "create one".
        // A miss here does not fail - it MINTS a second ACTIVE SystemRole.ADMIN carrying
        // seed.admin.password, while the original stays active and orphaned, and this class
        // deliberately never logs the address, so the only trace is one extra users row. So a
        // deployment that once folded differently (a tr_TR/az/lt JVM: IT-Admin@corp.com became
        // <dotless-i>t-admin@corp.com) has a stale row this build can no longer find.
        //
        // Detection and remedy live in docs/self-hosting.md, "Duplicate accounts after an
        // upgrade" - the DC operator manual, because the person who has to run those queries
        // is an operator and not a maintainer. (It was first written into the release
        // checklist, which is a runbook about tagging that no self-hoster opens; a remedy
        // filed where its reader never looks is not a remedy.) The image pins the JVM locale
        // from 0.16.0 on, so this cannot recur there; that doc covers the rest (HD-120).
        //
        // AND THE LOOKUP FOLDS IN SQL (HD-167), WHICH IS ONLY HALF OF THE ANSWER. A find-or-create
        // is a WRITE-side question, so it folds: findByFoldedEmail asks it with the expression
        // users_email_lower_uk is built from, and a row that differs from the configured address
        // only in case is FOUND rather than duplicated. But this call site does not merely resolve,
        // it GRANTS - SystemRole.ADMIN, instance-wide, to whatever row comes back - so the fold
        // stops at finding and an EXACT comparison decides the grant.
        //
        // WHAT THE EXACT COMPARISON IS FOR, stated as the alternative rather than as the rule: the
        // row occupying the folded key may not be one this seeder ever wrote. Before V23 such a row
        // meant the exact find missed, the insert was refused by users_email_key, and the boot
        // FAILED LOUDLY out of this ApplicationRunner - an operator looked. A folded find with no
        // comparison converts exactly that into "existing seed account promoted to system ADMIN",
        // logged as a success: a stranger holds system ADMIN, SEED_ADMIN_PASSWORD was never applied
        // so the operator cannot even log in to see it, and this class deliberately never logs the
        // address, so nothing on the console says whose row it was. So the refusal below is not a
        // new strictness; it is the loud failure the fold would otherwise have swallowed, kept and
        // given a sentence. It is the same standing as rejectPublishedPassword: at boot, a refusal
        // an operator can read beats a grant nobody sees.
        //
        // Post-V23 the alternative is NOT "a silently duplicated account" - the index makes a
        // duplicate impossible, and any comment saying otherwise describes a pre-V23 world.
        //
        // No 23505 translation is needed here either, and the reason is mechanism rather than
        // population: run() is not @Transactional, so SimpleJpaRepository.save opens and commits its
        // own transaction and a unique violation surfaces from the save() call itself, out of the
        // ApplicationRunner. Adding @Transactional to run() later moves where that lands.
        // (Optional is safe only because the index exists - Flyway runs to completion before this
        // class can be called.)
        var email = adminEmail.toLowerCase(Locale.ROOT);
        var existing = userRepository.findByFoldedEmail(email).orElse(null);
        if (existing != null && !existing.getEmail().equals(email)) {
            // No address in the message, by the same rule the rest of this class follows - the id
            // is what takes an operator to the row, and they are at a database prompt anyway.
            throw new IllegalStateException(
                    "Admin seeding refused: a users row (id " + existing.getId() + ") holds the "
                            + "folded form of seed.admin.email with a different spelling. This "
                            + "seeder did not write it and will not grant it system ADMIN. Either "
                            + "correct that row's address or point seed.admin.email at the account "
                            + "you mean; see docs/self-hosting.md, section \"Duplicate accounts "
                            + "after an upgrade\".");
        }
        if (existing != null) {
            // Accounts seeded before system roles existed must still get ADMIN
            if (existing.getSystemRole() != SystemRole.ADMIN) {
                existing.setSystemRole(SystemRole.ADMIN);
                userRepository.save(existing);
                log.info("Existing seed account promoted to system ADMIN (from seed.admin.email)");
            }
            return;
        }

        var admin = new User();
        admin.setEmail(email);
        admin.setDisplayName(adminDisplayName);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setStatus(UserStatus.ACTIVE);
        admin.setSystemRole(SystemRole.ADMIN);
        userRepository.save(admin);

        log.info("Admin account created from seed.admin.email");
    }
}
