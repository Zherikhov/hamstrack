package com.hamstrack.auth;

import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.mail.MailAddresses;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>{@code POST /api/auth/register} spends a per-inbox registration budget</strong>
 * (HD-202 review) — the mail bomb the ticket was written to close, reproduced against the one door
 * that was deliberately left off the mechanism.
 *
 * <h2>The exemption that was wrong, in one sentence</h2>
 * "Registration can produce at most one message per address — the second attempt is a {@code 409}"
 * is true <em>per address</em>, and the entire premise of {@code MailAddresses.throttleKey} is that
 * <strong>an address is not the unit of harm</strong>. {@code users.email} is unique on
 * {@code lower(email)} (V23), i.e. keyed on the <em>spelling</em>; the ceiling is keyed on the
 * <em>inbox</em>. Those two keys disagree exactly here, and one keystroke is what separates them.
 *
 * <h2>The attack these tests run</h2>
 * With public signup on, an unauthenticated caller posts {@code victim+1@…}, {@code victim+2@…},
 * {@code v.i.ctim@googlemail.com}. Each is a distinct {@code users} row, each passes
 * {@code existsByFoldedEmail}, each got a {@code 201} and one verification mail, and every one of
 * them landed in {@code victim@…}'s inbox. The recipient throttle was never consulted. Secondary
 * costs, both of which this closes too: each of those messages bought a bcrypt-12 (~370 ms) of our
 * CPU, and each littered {@code users} with a {@code PENDING} row.
 *
 * <h2>Why the refusal is a 429 here and silence next door</h2>
 * {@code resend-verification} mails the same message to the same person and must stay silent,
 * because its uniform answer is the anti-enumeration contract. Register is not an anti-enumeration
 * endpoint — it already answers {@code 409} for a taken address — so a {@code 429} discloses one
 * folded-key bit to a caller who has just been told about the exact spelling anyway, and silence
 * would be strictly worse: a dropped verification mail leaves an account nobody, its owner
 * included, can ever activate. That is
 * {@code MailThrottlePolicy.Refusal.RESPONDS_429_WHERE_ENDPOINT_DISCLOSES}, and
 * {@code AuthMailDoorsTest} is what stops the loud door spreading.
 *
 * <h2>Why the two are SEPARATE budgets, which is the reverse of what this file first shipped</h2>
 * The original argument for one bucket was that an attacker denied by one endpoint would otherwise
 * use the other. True of the MAIL BOMB, and backwards for the DENIAL: the two endpoints do not cost
 * the same to spend. {@code resend-verification} records before the account lookup and
 * unconditionally — it must, or the row is the existence oracle it refuses to be — so at an address
 * with <em>no</em> {@code PENDING} account it sends nothing, logs nothing above {@code DEBUG}, and
 * still fills the ceiling. Five requests an hour therefore locked every spelling of a stranger's
 * inbox out of signup, for free, silently, refillable for ever. Separate buckets mean the only way
 * to fill this one is to POST here, which costs a real mail the victim can see and a
 * {@code PENDING} row an administrator can find. The price is a doubled per-inbox mail bound, and
 * {@link #theRegisterAndResendBudgetsAreSeparate()} is what would fail if somebody merged them
 * again.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        // Not what is under test, and it must not be what refuses.
        "app.rate-limit.auth-ip-requests-per-minute=1000",
        "app.rate-limit.trust-forwarded-for=true",
        "app.registration.public-signup-enabled=true",
        "app.demo.seed-on-first-login=false",
        "app.legal.terms-acceptance-required=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AuthMailRegisterThrottleTest {

    /** {@code app.auth-mail.max-per-recipient-per-window}'s shipped default. */
    private static final int CAP = 5;

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate transactions;

    @PersistenceContext EntityManager em;

    @MockitoBean JavaMailSender mailSender;

    /**
     * <strong>The ticket's own attack, run against register.</strong> Two different addresses, two
     * legal new accounts by every check register makes, one inbox — and the second is refused.
     *
     * <p>Note what would have made this pass before the fix and must not now: nothing about the
     * <em>addresses</em> repeats. A ceiling keyed on {@code users.email}, on the raw submitted
* string, or on anything the request carries sees two unrelated signups. What refuses here is
     * the per-inbox cooldown on {@code EmailType.REGISTRATION_VERIFICATION}.
     */
    @Test
    void aSecondSpellingOfOneInboxIsRefusedRatherThanMailed() throws Exception {
        var inbox = "hd202reg-" + UUID.randomUUID().toString().replace("-", "");

        register(inbox + "+1@gmail.com").andExpect(status().isCreated());
        register(inbox + "+2@gmail.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        assertThat(recordedRegistrationSends(inbox + "@gmail.com"))
                .as("one message reached this inbox, not two. Before the fix both were 201s with a "
                    + "verification mail attached, because users.email is unique on the SPELLING "
                    + "and every ceiling here counts the INBOX")
                .isEqualTo(1);
    }

    /**
     * <strong>And the volume cap holds when the caller paces themselves past the cooldown</strong>
     * — which is what an attacker who has read the numbers does, and the only thing the cooldown
     * alone would not stop.
     *
     * <p>Paced with the clock rather than with a sleep: the recorded rows are moved back past the
     * cooldown between requests, which is deterministic where sleeping a minute per request is
     * neither fast nor reliable. What is left refusing at the end is the cap.
     */
    @Test
    void aPacedAttackIsStoppedByTheVolumeCap() throws Exception {
        var inbox = "hd202paced-" + UUID.randomUUID().toString().replace("-", "");

        for (int i = 1; i <= CAP; i++) {
            register(inbox + "+" + i + "@gmail.com").andExpect(status().isCreated());
            ageOutTheCooldown(inbox + "@gmail.com");
        }
        assertThat(recordedRegistrationSends(inbox + "@gmail.com"))
                .as("%d paced registrations must each be served, or the assertion below is about "
                    + "the cooldown and not the cap", CAP)
                .isEqualTo(CAP);

        register(inbox + "+over@gmail.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        assertThat(recordedRegistrationSends(inbox + "@gmail.com"))
                .as("the (cap+1)-th is outside every cooldown and must still be refused")
                .isEqualTo(CAP);
    }

    /**
     * <strong>A dot-folded spelling counts too</strong>, which is what makes the Gmail key space
     * unbounded and the {@code +tag} half of the fold insufficient on its own.
     */
    @Test
    void aDotFoldedSpellingSpendsTheSameRegistrationBudget() throws Exception {
        // Short on purpose: @Email holds the local part to RFC 5321's 64 octets, and interleaving
        // dots DOUBLES its length — a full UUID here is a 79-character local part and a 400 that
        // looks exactly like a throttle that did not fire.
        var local = "hd202d" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        register(local + "@gmail.com").andExpect(status().isCreated());
        register(dotted(local) + "@googlemail.com").andExpect(status().isTooManyRequests());

        assertThat(userRepository.existsByFoldedEmail(dotted(local) + "@googlemail.com"))
                .as("the refused registration must not have created an account — the refusal is "
                    + "above the users INSERT precisely so that nothing is stranded")
                .isFalse();
    }

    /**
     * <strong>What is refused is the REGISTRATION, not the mail</strong> — and that is the whole
     * answer to the objection that closed this gap in the first place.
     *
* <p>Dropping the mail and returning {@code 201} would leave a {@code PENDING} row whose owner
     * cannot activate it: the mail that carries the only link was the thing dropped, and they were
     * told it had been sent. Refusing above the INSERT strands nothing at all.
     */
    @Test
    void aRefusedRegistrationLeavesNoAccountBehind() throws Exception {
        var inbox = "hd202none-" + UUID.randomUUID().toString().replace("-", "");
        register(inbox + "+1@gmail.com").andExpect(status().isCreated());

        var refused = inbox + "+stranded@gmail.com";
        register(refused).andExpect(status().isTooManyRequests());

        assertThat(userRepository.existsByFoldedEmail(refused))
                .as("a 429 that had already written the row would leave an account activatable by "
                    + "nobody, which is the exact outcome the original exemption was protecting "
                    + "against — and the reason it chose to protect it by doing nothing at all")
                .isFalse();
    }

    /**
     * <strong>Register and resend-verification hold SEPARATE buckets</strong>, and the direction
     * that matters is resend → register.
     *
     * <p>While they shared one, {@code POST /api/auth/resend-verification} at an address with no
     * account sent no mail, logged nothing above {@code DEBUG}, and still spent a registration
     * slot — so an unauthenticated stranger could hold any inbox out of signup indefinitely, for
     * free, with no signal on either side. That is what this asserts is no longer possible: a
     * resend at an address nobody has registered must leave register's ceiling untouched.
     */
    @Test
    void theRegisterAndResendBudgetsAreSeparate() throws Exception {
        var inbox = "hd202split-" + UUID.randomUUID().toString().replace("-", "");

        // The free half of the old attack: an address with NO account at all. Silent by design, so
        // the bookkeeping is the only witness either way.
        for (int i = 0; i < CAP + 2; i++) {
            mockMvc.perform(post("/api/auth/resend-verification")
                            .header("X-Forwarded-For", "198.51.100.31")
                            .contentType(APPLICATION_JSON)
                            .content("{\"email\":\"" + inbox + "+" + i + "@gmail.com\"}"))
                    .andExpect(status().isOk());
            ageOutTheCooldown(inbox + "@gmail.com");
        }

        assertThat(recordedRegistrationSends(inbox + "@gmail.com"))
                .as("resend-verification must spend NOTHING from the registration budget. It "
                    + "records unconditionally and mails nothing when no account exists, so a "
                    + "shared bucket is a free, silent, refillable denial of account creation "
                    + "against any address a stranger can name")
                .isZero();

        // And signup is still open at that inbox, which is the whole point of the split. A 429 here
        // is the bug: it means resend traffic reached register's ceiling after all.
        register(inbox + "@gmail.com").andExpect(status().isCreated());
    }

    /**
     * <strong>An address that is simply taken still answers 409, and spends nothing.</strong>
     *
* <p>The ceiling sits BELOW the duplicate pre-check on purpose: a request that is about to be
     * refused with {@code 409} sends no mail, so letting it spend a slot would hand an attacker a
     * way to burn an inbox's registration budget through an endpoint that never mails — which is
     * the same free-and-silent denial the budget split above exists to close, one door along.
     */
    @Test
    void aDuplicateRegistrationAnswers409AndSpendsNoSlot() throws Exception {
        var address = "hd202dup-" + UUID.randomUUID().toString().replace("-", "") + "@example.test";
        register(address).andExpect(status().isCreated());

        long afterFirst = recordedRegistrationSends(address);
        register(address).andExpect(status().isConflict());

        assertThat(recordedRegistrationSends(address))
                .as("the 409 mails nothing, so it must cost the address nothing. Spending here "
                    + "would let anybody exhaust a registered user's verification budget by "
                    + "re-submitting their address")
                .isEqualTo(afterFirst);
    }

    // ------------------------------------------------------------------ fixture

    private org.springframework.test.web.servlet.ResultActions register(String email)
            throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .header("X-Forwarded-For", "198.51.100.30")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Correct-Horse-9\","
                         + "\"displayName\":\"HD202 Register\"}"));
    }

    /** Rows the throttle wrote for this inbox's REGISTRATION bucket. */
    private long recordedRegistrationSends(String email) {
        return transactions.execute(status -> em.createQuery("""
                        SELECT count(e) FROM MailSendEvent e
                         WHERE e.emailType = :type AND e.recipientKey = :key
                        """, Long.class)
                .setParameter("type", EmailType.REGISTRATION_VERIFICATION.name())
                .setParameter("key", MailAddresses.throttleKey(email))
                .getSingleResult());
    }

    /**
     * Moves this inbox's recorded sends back past the cooldown, so the next request meets only the
     * volume cap. Native, because {@code created_at} is mapped {@code updatable = false}.
     */
    private void ageOutTheCooldown(String email) {
        transactions.executeWithoutResult(status -> em.createNativeQuery(
                        "UPDATE mail_send_events SET created_at = :old WHERE recipient_key = :key")
                .setParameter("old", java.time.Instant.now().minusSeconds(180))
                .setParameter("key", MailAddresses.throttleKey(email))
                .executeUpdate());
    }

    /** {@code abc} to {@code a.b.c} — the other half of the Gmail fold. */
    private static String dotted(String local) {
        return String.join(".", local.split(""));
    }
}
