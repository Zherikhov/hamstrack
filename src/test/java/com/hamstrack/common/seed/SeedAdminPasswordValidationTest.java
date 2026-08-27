package com.hamstrack.common.seed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * <strong>HD-200 — the published administrator account.</strong>
 *
 * <p>{@code .env.prod.example} shipped both halves of a working credential:
 * {@code SEED_ADMIN_EMAIL=admin@yourdomain.com} and
 * {@code SEED_ADMIN_PASSWORD=SEED_ADMIN_PASSWORD}. Every install created from the unedited
 * template therefore has an ACTIVE system administrator whose email and password are printed
 * in a public repository — an attacker types two strings and is an admin. Nothing about that
 * looks like an attack: a correct password is not a failed login, so the per-account backoff
 * never engages, and the audit log records an ordinary sign-in.
 *
 * <p><strong>Emptying the template does not reach it</strong>, which is the whole reason
 * this refusal exists rather than a documentation note. Seeding is idempotent: the account
 * already exists, so an operator who upgrades takes the "found the user, skip" branch and
 * keeps the published-password admin, with nothing anywhere saying so.
 *
 * <p>Two things this refusal has to say that {@code JwtService}'s did not: that removing the
 * variable does not change the account, and <em>which</em> account it means — the operator
 * may not remember creating it.
 */
class SeedAdminPasswordValidationTest {

    /**
     * Assembled rather than written out: the repository-wide scan in
     * {@code JwtSecretValidationTest} reads this file too, and a literal assignment here
     * would be read as one more place we published it.
     */
    private static final String PUBLISHED = "SEED_ADMIN" + "_PASSWORD";

    @Test
    void refusesThePasswordThisProjectPublished() {
        assertThatIllegalStateException()
                .isThrownBy(() -> DataSeeder.rejectPublishedPassword(PUBLISHED, "Admin@Example.COM"))
                .withMessageContaining("published in its own")
                .withMessageContaining(".env.prod.example");
    }

    /**
     * The upgrade path is the one that matters, and it is the one a template edit cannot
     * reach: the account exists, seeding would skip, and clearing the variable would leave
     * the published password working while making the application quiet about it.
     */
    @Test
    void saysThatRemovingTheVariableDoesNotChangeTheAccount() {
        String message = refusalFor("admin@example.com");
        assertThat(message)
                .withFailMessage("An operator whose install already has the account will clear the "
                        + "variable, restart, and believe it is fixed. The refusal must say that it is "
                        + "not, and what to do to the ACCOUNT.\nMessage was: " + message)
                .contains("REMOVING THIS VARIABLE DOES NOT CHANGE THE ACCOUNT")
                .contains("idempotent");
        assertThat(message)
                .withFailMessage("The refusal must prescribe an action its reader can actually perform - "
                        + "resetting or deleting that user.\nMessage was: " + message)
                .containsIgnoringCase("reset")
                .containsIgnoringCase("delete");
    }

    /**
     * <strong>It names the account.</strong> Lower-cased, because that is the row: the
     * seeder folds the address before looking it up, so the value in the file and the value
     * in {@code users} differ whenever the operator typed a capital.
     */
    @Test
    void namesTheAccountItIsTalkingAbout() {
        assertThat(refusalFor("Admin@Example.COM")).contains("admin@example.com");
    }

    /**
     * <strong>The remedy has to be performable at the moment it is read.</strong> This
     * refusal stops the application, so "sign in as that account and change the password" —
     * what it used to say — asks the reader to use a console that is not running. And
     * editing {@code .env} does not clear it either: the stored-password check
     * ({@code DataSeeder.rejectPublishedAdminHash}) reads the account, so an operator who
     * only repairs the file meets the next refusal instead.
     */
    @Test
    void prescribesSomethingPerformableWhileTheApplicationIsDown() {
        String message = refusalFor("Admin@Example.COM");
        assertThat(message)
                .withFailMessage("""

                        This refusal is what STOPS the application, so a remedy that needs the \
                        application is not one. It must name something that works against the \
                        database - and the statement has to be complete enough to paste, with the \
                        account's own address in it.

                        Message was: %s""", message)
                .contains("UPDATE users SET password_hash = NULL WHERE email = 'admin@example.com'");
        assertThat(message)
                .withFailMessage("""

                        Editing .env no longer clears this: the stored-password check reads the \
                        ACCOUNT. If the refusal does not say so, an operator repairs the file, \
                        restarts, meets a second refusal they were not warned about, and reads the \
                        release as broken.

                        Message was: %s""", message)
                .contains("STORED password");
    }

    /**
     * The variable can be removed while the account stays — so the refusal must still be
     * able to say <em>something</em> about which account it means, rather than silently
     * naming none.
     */
    @Test
    void stillRefusesWhenTheAddressHasBeenRemoved() {
        String message = refusalFor("   ");
        assertThat(message)
                .contains("SEED_ADMIN_EMAIL")
                .contains("does not remove the account");
    }

    /**
     * <strong>Whitespace nobody can see is not a bypass.</strong> A dotenv value is read
     * verbatim, so {@code SEED_ADMIN_PASSWORD=SEED_ADMIN_PASSWORD } with one trailing space
     * is a different string to this set and an identical one to bcrypt: the account it seeds
     * is signed into with the published value all the same.
     *
     * <p><strong>The direct call is the only vehicle that can carry this claim.</strong>
     * {@code SeedGuardStartupOrderingTest} had a twin of this case going through
     * {@code ApplicationContextRunner.withPropertyValues}, and it stayed green with
     * {@code .strip()} deleted: Spring's property machinery trims the value, so the guard saw
     * 19 characters rather than 20 and the case asserted nothing about whitespace at all. It
     * has been deleted rather than left as a green assertion with a true-sounding name. The
     * premise is still right — a real deployment supplies this through an environment
     * variable, which preserves the space — so it is asserted here, where the value reaches
     * the guard untouched.
     */
    @Test
    void surroundingWhitespaceIsNotABypass() {
        for (String spaced : java.util.List.of(PUBLISHED + " ", " " + PUBLISHED, "\t" + PUBLISHED + "\n")) {
            assertThatIllegalStateException()
                    .describedAs("`%s` (with invisible whitespace) was accepted", spaced)
                    .isThrownBy(() -> DataSeeder.rejectPublishedPassword(spaced, "admin@example.com"));
        }
    }

    @Test
    void acceptsAPasswordAnOperatorChose() {
        assertThatCode(() -> DataSeeder.rejectPublishedPassword("k7Qv2#tR9pLm4zXw", "admin@example.com"))
                .doesNotThrowAnyException();
        // Nothing here is a strength check: an absent or empty password is somebody else's
        // rule (a blank one logs a WARN and skips seeding), and this guard has no opinion.
        assertThatCode(() -> DataSeeder.rejectPublishedPassword(null, "admin@example.com"))
                .doesNotThrowAnyException();
        assertThatCode(() -> DataSeeder.rejectPublishedPassword("", "admin@example.com"))
                .doesNotThrowAnyException();
        // Near misses are not the published value. This refuses one string, by name.
        assertThatCode(() -> DataSeeder.rejectPublishedPassword(PUBLISHED.toLowerCase(), "a@example.com"))
                .doesNotThrowAnyException();
    }

    /**
     * <strong>Pinned to its size, because it is one word away from a weak-password
     * checker.</strong> The standard for an entry is evidentiary and narrow: <em>this
     * repository published that exact string as production configuration, under the
     * variable's own name</em>, so the account it protects is not weakly protected but
     * publicly signable-into. If you cannot name the commit ({@code git log -S}), the value
     * belongs in an operator's password policy and not here — a denylist that grows on
     * "this looks weak" invites "mine is not on the list" and is stale by the week.
     */
    @Test
    void thePublishedDenylistIsPinnedToItsEvidence() {
        assertThat(DataSeeder.PUBLISHED_PASSWORDS)
                .withFailMessage("""

                        DataSeeder.PUBLISHED_PASSWORDS changed size. It is not a weak-password denylist \
                        and must not become one.

                        An entry is justified only by evidence: THIS repository published that exact \
                        string as production configuration, under SEED_ADMIN_PASSWORD's own name, so \
                        every install made from the template has an administrator anybody can sign in \
                        as - and no template edit can reach those installs, because seeding is \
                        idempotent. Name the commit (git log -S'<value>') in the review, then update \
                        this count.""")
                .hasSize(1);
    }

    private static String refusalFor(String email) {
        try {
            DataSeeder.rejectPublishedPassword(PUBLISHED, email);
        } catch (IllegalStateException refused) {
            return refused.getMessage();
        }
        throw new AssertionError("The published password was accepted - see "
                + "refusesThePasswordThisProjectPublished");
    }
}
