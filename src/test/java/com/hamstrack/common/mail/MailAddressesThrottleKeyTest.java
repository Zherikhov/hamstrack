package com.hamstrack.common.mail;

import com.hamstrack.workspace.dto.InviteMemberRequest;
import jakarta.persistence.Column;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.IDN;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>The ceilings count one key per INBOX, not one per spelling</strong> (HD-190 section 4.3 /
 * acceptance criterion 12).
 *
 * <p>This is the test the whole feature stands on. Keyed on the lower-cased address alone, every
 * ceiling in HD-190 is decorative: {@code victim+1@gmail.com}, {@code victim+2@gmail.com} and
 * {@code v.i.c.t.i.m@googlemail.com} are distinct strings that land in one human's inbox, so the
 * ticket's attack — one victim, many workspaces — is re-spelled as one victim, many local-part tags,
 * at the cost of one keystroke, and both persisted counts read zero on every request. The first cut
 * of this feature shipped exactly that.
 *
 * <p><strong>Direction of harm decides the folding, and it points opposite ways on the two paths
 * that compare an invited address.</strong> For a <em>ceiling</em>, an extra match raises a count
 * and refuses sooner, so over-folding is fail-safe and under-folding is the hole. On the invite
 * <em>redemption</em> path (HD-120, {@code InviteEmailBindingTest}) an extra match lets the wrong
 * person accept somebody else's invitation, so addresses are compared exactly. Both rules are
 * asserted in this codebase and neither may be transplanted onto the other; the pair is what makes
 * {@link #foldingIsNotAppliedToTheAddressWeActuallyMailOrMatch()} worth having.
 *
 * <p><strong>Over-folding is fail-safe but not free, which is why the rules stop where they do.</strong>
 * For the per-(sender, recipient) cooldown an over-fold costs an honest sender a wait they did not
 * earn — their own inconvenience, and it expires. For the global daily cap it spends a slot
 * belonging to <em>a different, innocent person</em> who merely shares a folded key, because that
 * ceiling is sender-invariant. So every rule folded here is a published, provider-documented fact
 * about delivery or a standard normalisation, and confusable mapping — a guess about identity — is
 * not among them ({@link #confusableSpellingsAreDifferentInboxes()}).
 */
class MailAddressesThrottleKeyTest {

    /** The victim in the ticket, in the spelling an honest sender would type. */
    private static final String VICTIM = "victim@gmail.com";

    /**
     * <strong>The attack, as a single assertion.</strong> Every spelling here reaches one human's
     * inbox, so every one of them must spend the same bucket. Each entry was a free send before the
     * key existed:
     * <ul>
     *   <li>{@code +tag} — sub-addressing, delivered by Gmail and most providers;</li>
     *   <li>the dots and the {@code googlemail.com} alias — both published Gmail behaviour, and
     *       between them they make the Gmail key space unbounded on their own, which is why folding
     *       {@code +tag} alone would have left the largest consumer provider fully exposed;</li>
     *   <li>the quoted local part — RFC 5321 allows it and Hibernate Validator's {@code @Email}
     *       accepts it, so it is the {@code +tag} hole again wearing punctuation on any receiver
     *       that unquotes (Postfix, Exchange);</li>
     *   <li>case — the boundary already lower-cases, and this pins that the key does too, so a
     *       caller reaching the throttle by some other route cannot buy a bucket with the shift key.</li>
     * </ul>
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "victim+1@gmail.com",
            "victim+2@gmail.com",
            "victim+anything-at-all@gmail.com",
            "v.i.c.t.i.m@gmail.com",
            "v.i.c.t.i.m@googlemail.com",
            "victim@googlemail.com",
            "\"victim\"@gmail.com",
            "\"v.i.c.t.i.m\"@googlemail.com",
            "\"victim+9\"@gmail.com",
            "VICTIM@GMAIL.COM",
            "  victim@gmail.com  ",
    })
    void everyRespellingOfOneInboxSharesTheVictimsBucket(String respelling) {
        assertThat(MailAddresses.throttleKey(respelling))
                .as("%s reaches the same human as %s, so it must spend the same ceiling. A key "
                    + "that tells these apart counts mailboxes it can distinguish rather than "
                    + "inboxes somebody opens, and the ticket's attack is then re-spelled for one "
                    + "keystroke while both counts read zero", respelling, VICTIM)
                .isEqualTo(MailAddresses.throttleKey(VICTIM));
    }

    /**
     * The other half, and the reason the test above is a folding rule rather than a bug: a
     * <em>different person</em> keeps a different bucket. Without this, "fold everything to one
     * key" would pass the assertion above and turn the daily cap into an instance-wide outage.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "someone.else@gmail.com",
            "someoneelse@gmail.com",
            "victim@example.com",
            "victim@gmail.com.evil.test",
            "notvictim@gmail.com",
    })
    void aDifferentInboxKeepsItsOwnBucket(String other) {
        assertThat(MailAddresses.throttleKey(other))
                .as("%s is a different person from %s. Over-folding is the fail-safe direction for "
                    + "a ceiling but it is NOT free: the global daily cap is sender-invariant, so "
                    + "a spurious match spends a slot belonging to somebody innocent who can never "
                    + "find out why their invitation was refused", other, VICTIM)
                .isNotEqualTo(MailAddresses.throttleKey(VICTIM));
    }

    /**
     * <strong>Dot-folding is Gmail's rule and must not leak to other providers.</strong> Everywhere
     * else a dot is an ordinary local-part character and two dotted spellings are two people, so
     * folding them would be the innocent-third-party cost above, paid across the whole internet.
     */
    @Test
    void dotsAreOnlyIgnoredWhereTheProviderSaysTheyAre() {
        assertThat(MailAddresses.throttleKey("v.i.c.t.i.m@example.com"))
                .as("example.com does not publish Gmail's dot rule — a heuristic here would fold "
                    + "two strangers into one bucket")
                .isNotEqualTo(MailAddresses.throttleKey("victim@example.com"));

        assertThat(MailAddresses.throttleKey("v.i.c.t.i.m@gmail.com"))
                .as("gmail.com does, and it is a published fact about delivery rather than a guess")
                .isEqualTo(MailAddresses.throttleKey("victim@gmail.com"));
    }

    /**
     * {@code +tag} is stripped on every provider, and that IS a generalisation — but the safe one:
     * on a provider that does not implement sub-addressing, {@code a+b@} and {@code a@} are two
     * mailboxes folded into one bucket, which costs an over-fold, and the alternative costs a hole
     * on every provider that does. Mail still goes to the submitted address
     * ({@link #foldingIsNotAppliedToTheAddressWeActuallyMailOrMatch()}), so nothing is misdelivered.
     */
    @Test
    void subAddressingIsStrippedOnEveryProviderNotJustGmail() {
        assertThat(MailAddresses.throttleKey("victim+release@example.com"))
                .isEqualTo(MailAddresses.throttleKey("victim@example.com"));
    }

    /**
     * An internationalised domain and its {@code xn--} spelling are one domain at the DNS level and
     * one inbox in practice. Keying on the spelling would give a victim on such a domain two keys
     * and twice every ceiling — a doubling bought with a keyboard layout.
     */
    @Test
    void anInternationalisedDomainSharesTheBucketOfItsPunycodeSpelling() {
        assertThat(MailAddresses.throttleKey("opfer@münchen.de"))
                .isEqualTo(MailAddresses.throttleKey("opfer@xn--mnchen-3ya.de"));
    }

    /**
     * Punycode conversion has to run <em>before</em> the Gmail lookup, or a unicode spelling of a
     * Gmail domain misses the dot rule and buys an unbounded key space back. Ordering inside one
     * method is the kind of thing a tidy-up reorders, so it is pinned rather than assumed.
     */
    @Test
    void theDomainIsNormalisedBeforeTheProviderRuleIsLookedUp() {
        // "ｇｍａｉｌ.ｃｏｍ" in fullwidth forms — IDN.toASCII maps these to plain gmail.com.
        var fullwidth = "v.i.c.t.i.m@ｇｍａｉｌ.ｃｏｍ";

        assertThat(MailAddresses.throttleKey(fullwidth))
                .as("the domain must be normalised first: if the Gmail lookup ran on the raw "
                    + "spelling, dots in the local part would survive and the largest consumer "
                    + "provider would be back to an unbounded key space")
                .isEqualTo(MailAddresses.throttleKey(VICTIM));
    }

    // =================================================== the key's width, and what actually bounds it

    /**
     * <strong>Punycode is the one step in {@code throttleKey} that LENGTHENS its input, and
     * {@code mail_send_events.recipient_key} fits the worst case with zero characters to
     * spare.</strong>
     *
     * <p>The tempting justification for that column's width — every flow that writes it carries
     * {@code @Size(max = 255)}, so 320 is margin — is true of {@code recipient_email} and false
     * here. Every other rule in {@code throttleKey} strips ({@code +tag}, quotes, Gmail dots);
     * {@code IDN.toASCII} adds, and it adds without any relation to what it was given: the address
     * built below is <strong>85 characters</strong>, comfortably inside the DTO bound, and its key
     * is <strong>320</strong>. So the DTO bound says nothing at all about how wide this column has
     * to be, and one character more would be a
     * {@code DataIntegrityViolationException} on a path with no handler — a 500 in place of a
     * ceiling, reached by typing an address.
     *
     * <p><strong>What does bound it lives inside {@code @Email}</strong>, which every writing flow
     * also carries: Hibernate Validator refuses a local part over 64 characters, and runs
     * {@code IDN.toASCII} itself before refusing a domain whose <em>ASCII</em> form exceeds 255. So
     * the ceiling is 64 + {@code "@"} + 255 = 320, exactly the column, and it rests on a
     * third-party invariant that nothing in this codebase states. Which is why it is constructed
     * here rather than reasoned about: the address below is built to sit on both of those limits at
     * once, and {@link #pastTheWorstCaseItIsValidationThatRefusesAndNotTheColumn()}
     * is the other direction — the next step up is refused by {@code @Email}, not by Postgres.
     *
     * <p>The column width is read off the entity rather than restated, so widening the column
     * without revisiting this arithmetic cannot pass here by coincidence.
     */
    @Test
    void theWidestKeyReachableThroughTheRealDtoConstraintsExactlyFillsTheColumn() {
        var address = worstCaseAddress();

        assertThat(violations(address))
                .as("the worst case has to be REACHABLE or this test bounds nothing: %s must pass "
                    + "every constraint on InviteMemberRequest.email", address)
                .isEmpty();

        assertThat(address.length())
                .as("and it is nowhere near the DTO's own @Size(max = 255) — which is the point: "
                    + "the length of the submitted address does not bound the length of the key")
                .isEqualTo(85)
                .isLessThan(255);

        var key = MailAddresses.throttleKey(address);

        assertThat(key.length())
                .as("""
                        A key derived from an address the DTO ACCEPTS does not fit \
                        mail_send_events.recipient_key.

                        Punycode is the one step in throttleKey that lengthens rather than strips, \
                        and a single code point can expand to 29 ASCII characters, so the width of \
                        this column has nothing to do with @Size(max = 255) on the request. It is \
                        bounded by @Email instead: local part <= 64, ASCII domain <= 255, hence \
                        64 + 1 + 255 = 320 and no margin.

                        An over-long key is not a refused invitation -- it is a \
                        DataIntegrityViolationException out of the INSERT, which this application \
                        does not handle, so it answers 500 on a request that should have spent a \
                        ceiling. If a folding rule was added that APPENDS to a key rather than only \
                        stripping from it, widen the column (and failed_email.recipient with it) \
                        before doing anything else.""")
                .isEqualTo(columnWidth("recipientKey"))
                .isEqualTo(320);

        assertThat(key.length())
                .as("the key is nearly four times the address it came from — 'every writing flow "
                    + "is @Size(max = 255), so 320 is margin' is an argument about "
                    + "recipient_email and it does not transfer to this column")
                .isGreaterThan(address.length());
    }

    /**
     * The other direction, and the one that makes the number above a <em>ceiling</em> rather than a
     * measurement: one more expanding label puts the ASCII domain over 255, and it is
     * {@code @Email} that refuses it — at the boundary, as a 400 — rather than the column refusing
     * it at the INSERT as a 500.
     *
     * <p>Asserted on <em>which</em> constraint objects, because the DTO also carries
     * {@code @Size(max = 255)} and {@code @Pattern}: an address rejected by one of those would
     * produce the same empty-handed green while proving nothing about the domain limit this whole
     * bound rests on.
     */
    @Test
    void pastTheWorstCaseItIsValidationThatRefusesAndNotTheColumn() {
        var overlong = LOCAL_PART_AT_THE_LIMIT + "@" + EXPANDING_LABEL + "." + worstCaseDomain();

        assertThat(IDN.toASCII(EXPANDING_LABEL + "." + worstCaseDomain(), IDN.ALLOW_UNASSIGNED)
                .length())
                .as("the premise: this domain's ASCII form is past Hibernate Validator's 255")
                .isGreaterThan(255);
        assertThat(overlong.length())
                .as("...while the address itself is still well inside the DTO's @Size(max = 255), "
                    + "so @Size cannot be what refuses it")
                .isLessThan(255);

        var violations = violations(overlong);

        assertThat(violations)
                .as("nothing refused an address whose key would be %d characters — the column is "
                    + "%d, and an over-long key surfaces as an unhandled "
                    + "DataIntegrityViolationException rather than as a 400",
                        MailAddresses.throttleKey(overlong).length(), columnWidth("recipientKey"))
                .isNotEmpty();
        assertThat(violations)
                .as("and @Email must be the constraint that does it: the 64/255 pair inside "
                    + "@Email is the ENTIRE justification for a 320-character column, so a green "
                    + "here that came from @Size or @Pattern would be certifying the wrong bound")
                .anyMatch(v -> v.getConstraintDescriptor().getAnnotation() instanceof Email);
    }

    /**
     * <strong>Confusables are NOT folded, and that is deliberate.</strong> Homoglyph mapping is a
     * guess about identity rather than a fact about delivery: {@code раypal@example.com} (Cyrillic)
     * and {@code paypal@example.com} are two mailboxes belonging to two different humans, and
     * collapsing them applies the innocent-third-party cost to strangers at scale.
     *
     * <p><strong>One member of the trio the spec names does NOT hold, and the spec is wrong rather
     * than the code.</strong> {@code docs/design/invite-budget-proposal.md} section 15 criterion 12
     * lists dotless i (U+0131), long s (U+017F) <em>and the Kelvin sign (U+212A)</em> as still
     * distinct keys. The first two are; the third is not, and cannot be without changing something
     * else — {@code String.toLowerCase(Locale.ROOT)} maps U+212A to plain {@code k}, so the fold
     * happens in the very first line of {@code throttleKey}, before any rule this feature owns gets
     * a say. It is asserted here in the direction it actually behaves, with the consequence stated:
     * an over-fold, i.e. the fail-safe direction for a ceiling, costing at most a shared bucket
     * between two addresses that differ only by a character no mail client offers.
     */
    @Test
    void confusableSpellingsAreDifferentInboxes() {
        assertThat(MailAddresses.throttleKey("ıvan@example.com"))
                .as("dotless i (U+0131) is a different letter, and toLowerCase(Locale.ROOT) leaves "
                    + "it alone — the users table, the UNIQUE index and Postgres lower() all agree "
                    + "these are two accounts, so the ceiling must not decide they are one person")
                .isNotEqualTo(MailAddresses.throttleKey("ivan@example.com"));

        assertThat(MailAddresses.throttleKey("ſam@example.com"))
                .as("long s (U+017F) likewise")
                .isNotEqualTo(MailAddresses.throttleKey("sam@example.com"));

        assertThat(MailAddresses.throttleKey("раypal@example.com"))
                .as("Cyrillic er/a are a different mailbox belonging to a different human — this "
                    + "is the case the javadoc names, and folding it would deny invitations to "
                    + "strangers at scale for a reason nobody involved can see")
                .isNotEqualTo(MailAddresses.throttleKey("paypal@example.com"));

        assertThat(MailAddresses.throttleKey("Kelvin@example.com"))
                .as("Kelvin sign (U+212A) DOES fold, because toLowerCase(Locale.ROOT) maps it to "
                    + "plain k before throttleKey sees it — the design doc's criterion 12 is wrong "
                    + "about this one. Harmless, because it is the over-folding direction: it can "
                    + "only refuse sooner, never hand out a free send. Do not 'fix' this by "
                    + "special-casing the code point; the fold is in the JDK's case mapping, and "
                    + "the same mapping is what the invite REDEMPTION path stores through")
                .isEqualTo(MailAddresses.throttleKey("kelvin@example.com"));
    }

    /**
     * <strong>Degenerate input produces a key, never an exception and never an empty string.</strong>
     * {@code throttleKey} runs on the request path of a ceiling, so a malformed address that threw
     * would turn a 400-shaped mistake into a 500, and one that returned {@code ""} would put every
     * malformed address in the instance into one bucket — a global cap, spendable by anybody, aimed
     * at nobody.
     *
     * <p>{@code +tag@example.com} is the interesting member: the whole local part is a tag, so
     * stripping it would leave {@code @example.com} and collapse every such address in a domain
     * into one bucket. It keys on what was submitted instead.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "+tag@example.com",
            "\"\"@example.com",
            "a@",
            "@b.com",
            "notanemail",
            "@",
            "\"@\"@example.com",
    })
    void degenerateInputKeysVerbatimAndNeverBlank(String degenerate) {
        var key = MailAddresses.throttleKey(degenerate);

        assertThat(key)
                .as("%s must still produce a usable key: an empty one would put every malformed "
                    + "address in the instance into a single shared bucket", degenerate)
                .isNotBlank();
    }

    /**
     * The one input with no address in it at all. {@code null} is not reachable from
     * {@code inviteMember} today ({@code @NotBlank} is above it), which is exactly why it is pinned:
     * the next caller of this mechanism is HD-202's anonymous flows, and "the DTO validates it" is a
     * property of a call site rather than of this method.
     */
    @Test
    void nullIsAKeyAndNotACrash() {
        assertThat(MailAddresses.throttleKey(null)).isEmpty();
    }

    /**
     * Distinct degenerate inputs stay distinct. The sibling test only asks that each produces
     * <em>something</em>; without this, returning one constant would satisfy it.
     */
    @Test
    void degenerateInputsAreNotAllOneBucket() {
        var keys = Arrays.stream(new String[]{
                "+tag@example.com", "+other@example.com", "\"\"@example.com",
                "a@", "@b.com", "notanemail"}).map(MailAddresses::throttleKey).toList();

        assertThat(keys)
                .as("malformed addresses must not collapse into one shared bucket — that bucket "
                    + "would be a ceiling anybody could exhaust on everybody else's behalf")
                .doesNotHaveDuplicates();
    }

    /**
     * <strong>The key is a throttle key and never a recipient, and never an identity.</strong> Mail
     * goes to the submitted address, because {@code +} is an ordinary local-part character on
     * providers that do not implement sub-addressing and a dot is an ordinary one everywhere but
     * Gmail; and the invitation itself is matched exactly, because on the redemption path an extra
     * match lets the wrong person accept.
     *
     * <p>Asserted as the inequality that makes those two statements have content: the value stored
     * in {@code mail_send_events.recipient_email}, mailed to, and compared on accept, is
     * <em>different</em> from the value counted. If they were ever the same value, both arguments
     * above would be describing one thing and one of them would be wrong.
     */
    @Test
    void foldingIsNotAppliedToTheAddressWeActuallyMailOrMatch() {
        var submitted = "v.i.c.t.i.m+release@googlemail.com";

        assertThat(MailAddresses.throttleKey(submitted))
                .as("the counted key is folded")
                .isEqualTo("victim@gmail.com")
                .isNotEqualTo(submitted);

        assertThat(MailAddresses.domainOf(submitted))
                .as("the log line carries the domain only — the local part is what makes an "
                    + "address personal data and it never reaches a log or a metric")
                .isEqualTo("googlemail.com")
                .doesNotContain("victim");
    }

    /** A malformed address must not put a half-parsed local part into the operator's log. */
    @Test
    void anUnusableAddressLogsNoDomainAtAll() {
        assertThat(MailAddresses.domainOf("notanemail")).isEqualTo("unknown");
        assertThat(MailAddresses.domainOf("a@")).isEqualTo("unknown");
        assertThat(MailAddresses.domainOf(null)).isEqualTo("unknown");
    }

    // ------------------------------------------------------- the widest-key fixture

    /**
     * U+FDFA ARABIC LIGATURE SALLALLAHOU ALAYHE WASALLAM — <strong>one code point that punycodes to
     * a 29-character label.</strong> Not a curiosity: IDNA2003's Nameprep decomposes this ligature
     * into an entire phrase before Punycode runs, so it is the cheapest way to buy ASCII length, and
     * it is a perfectly ordinary character to have on a keyboard in the languages that use it.
     * Hibernate Validator's domain pattern accepts any code point in {@code U+0080..U+FFFF}, so
     * nothing between a request body and this expansion says no.
     */
    private static final String EXPANDING_LABEL = "\uFDFA";

    /**
     * U+00A8 DIAERESIS four times — 15 ASCII characters. The filler that takes the domain from 239
     * to exactly 255, so the worst case sits <em>on</em> Hibernate Validator's limit rather than
     * near it.
     */
    private static final String FILLER_LABEL = "\u00A8\u00A8\u00A8\u00A8";

    /** 64 ASCII characters: Hibernate Validator's {@code MAX_LOCAL_PART_LENGTH}, to the character. */
    private static final String LOCAL_PART_AT_THE_LIMIT = "a".repeat(64);

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * The longest key any request body can produce, built rather than assumed: a local part on
     * {@code @Email}'s 64-character limit, and a domain whose ASCII form is on its 255-character
     * one. Eight expanding labels reach 239, and the filler adds the last 16 (a dot and 15).
     *
     * <p>Each limit is asserted here rather than in the tests, so a JDK whose Nameprep tables move
     * fails as a broken fixture — which is what it would be — instead of as a quietly weaker bound.
     */
    private static String worstCaseAddress() {
        var address = LOCAL_PART_AT_THE_LIMIT + "@" + worstCaseDomain();
        assertThat(LOCAL_PART_AT_THE_LIMIT).as("@Email's local-part limit").hasSize(64);
        assertThat(IDN.toASCII(worstCaseDomain(), IDN.ALLOW_UNASSIGNED).length())
                .as("@Email's domain limit is measured on the ASCII form, and this fixture is "
                    + "only a worst case while it sits exactly on it")
                .isEqualTo(255);
        return address;
    }

    private static String worstCaseDomain() {
        return String.join(".",
                EXPANDING_LABEL, EXPANDING_LABEL, EXPANDING_LABEL, EXPANDING_LABEL,
                EXPANDING_LABEL, EXPANDING_LABEL, EXPANDING_LABEL, EXPANDING_LABEL,
                FILLER_LABEL);
    }

    /**
     * The address put through the constraints it actually meets — {@code InviteMemberRequest.email}
     * — rather than through a locally re-declared {@code @Email}. The bound this file certifies is
     * only worth anything if it is the one a request is held to.
     */
    private static java.util.Set<jakarta.validation.ConstraintViolation<InviteMemberRequest>>
            violations(String address) {
        return VALIDATOR.validate(new InviteMemberRequest(address, null, "MEMBER"));
    }

    /** The declared width of a {@link MailSendEvent} column, so no number here is a restatement. */
    private static int columnWidth(String field) {
        try {
            return MailSendEvent.class.getDeclaredField(field).getAnnotation(Column.class).length();
        } catch (NoSuchFieldException e) {
            throw new AssertionError("MailSendEvent." + field + " no longer exists — this file "
                    + "asserts that a throttle key fits that column", e);
        }
    }
}
