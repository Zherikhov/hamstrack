package com.hamstrack.common.mail;

import com.hamstrack.auth.dto.ForgotPasswordRequest;
import com.hamstrack.auth.dto.RegisterRequest;
import com.hamstrack.auth.dto.ResendVerificationRequest;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.testsupport.ProductionBytecode;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.dto.InviteMemberRequest;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.repository.WorkspaceStorageUsageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.Column;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.IDN;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
     * <strong>The widest key EVERY writing DTO can produce fits
     * {@code mail_send_events.recipient_key}</strong> — parameterised over all four of them, which is
     * the correction HD-306 made to this file.
     *
     * <p>The tempting justification for that column's width — every flow that writes it carries
     * {@code @Size(max = 255)}, so 320 is margin — is false of <em>both</em> columns this table
     * stores an address in, and an earlier round of HD-306 wrote here that it was "true of
     * {@code recipient_email}". It is not: a {@code @Size} bounds the RAW text and both columns hold
     * text the server DERIVED from it, so neither is bounded by 255 at all
     * ({@link #theWidestAddressEveryWritingDtoStoresFitsTheForensicColumn(WritingDoor)} is the
     * other half of this claim, and it found the hole that sentence hid). Punycode adds without any
     * relation to what it was given: the invite fixture below is <strong>85 characters</strong>,
     * comfortably inside the DTO bound, and its key is <strong>320</strong>. So the DTO bound says
     * nothing at all about how wide either column has to be.
     *
     * <p><strong>Part of what bounds it lives inside {@code @Email}</strong>, which every writing flow
     * also carries: Hibernate Validator refuses a local part over 64 characters, and runs
     * {@code IDN.toASCII} itself before refusing a domain whose <em>ASCII</em> form exceeds 255 — hence
     * 64 + {@code "@"} + 255 = 320, exactly the column.
     *
     * <p><strong>And that was certified against {@code InviteMemberRequest} ALONE, which is a
     * uniqueness claim proved on one member</strong> (HD-306). This test used to send one address, built
     * through the one DTO whose {@code @Pattern} forces an ASCII local part, and concluded a property of
     * the column. {@code toLowerCase} runs on the LOCAL PART in {@code throttleKey}'s first line and it
     * APPENDS, so for the other three DTOs Hibernate Validator's 64 buys 128 characters of key and the
     * arithmetic is 128 + 1 + 255 = 384 ({@link #theRegisterDoorsWidestKeyIsTruncatedAndCounted()}).
     * What keeps every door inside the column now is the truncation, and this test is what says so for
     * each of them rather than for the one it was written against.
     *
     * <p>The column width is read off the entity rather than restated, so widening the column
     * without revisiting this arithmetic cannot pass here by coincidence.
     */
    @ParameterizedTest
    @EnumSource(WritingDoor.class)
    void theWidestKeyReachableThroughEveryWritingDtoFitsTheColumn(WritingDoor door) {
        var address = door.worstCaseAddress();

        assertThat(door.violations(address))
                .as("the worst case has to be REACHABLE or this case bounds nothing: %s must pass "
                    + "every constraint on %s's address field", address, door.dto())
                .isEmpty();

        assertThat(address.length())
                .as("and it is nowhere near the DTO's own @Size(max = 255) — which is the point: "
                    + "the length of the submitted address does not bound the length of the key")
                .isLessThan(255);

        assertThat(MailAddresses.throttleKey(address).length())
                .as("""
                        A key derived from an address %s ACCEPTS does not fit \
                        mail_send_events.recipient_key.

                        TWO steps in throttleKey lengthen rather than strip: punycode (one code point \
                        can expand to 29 ASCII characters) and the toLowerCase in its first line \
                        (U+0130 becomes two code points). So the width of this column has nothing to do \
                        with @Size(max = 255) on the request, and the 64 + 1 + 255 = 320 arithmetic \
                        inside @Email holds only for a DTO whose @Pattern forces an ASCII local part -- \
                        which is one of the four.

                        An over-long key is not a refused send, it is a DataIntegrityViolationException \
                        out of the INSERT that rolls back the ceiling row the caller was already \
                        counted on -- a free probe of a stranger's ceilings. So throttleKey truncates \
                        to this width at the site that produces the key, and counts it. If this case is \
                        red, that truncation has been removed or the column has been narrowed; do not \
                        widen the column instead -- a width resting on third-party invariants nothing \
                        here states is what failed the first time.""", door.dto())
                .isLessThanOrEqualTo(columnWidth("recipientKey"));
    }

    /**
     * <strong>The OTHER column this table stores an address in, and the same claim over it</strong>
     * (HD-306 fix loop): the widest address every writing DTO can put into
     * {@code mail_send_events.recipient_email} fits that column too.
     *
     * <p><strong>Why this case exists at all — the half a "recipient_key" claim cannot see.</strong>
     * {@code RecipientMailThrottle.record} writes TWO columns from the address its caller folded: the
     * derived key, and the folded address itself. HD-306 round 1 truncated the key and left the
     * address verbatim, so the two anonymous doors — {@code POST /api/auth/forgot-password} and
     * {@code /api/auth/resend-verification}, whose entire contract is one uniform response — could
     * drive a {@code 22001} out of the INSERT that records them, rolling back the ceiling row inside
     * the advisory lock: the free probe of a stranger's ceilings that this file's own truncation
     * argument exists to prevent, reached through the column the truncation did not cover.
     *
     * <p><strong>The fixture is a DIFFERENT worst case from the key's, and that is the lesson.</strong>
     * The key is widened by PUNYCODE, so its worst case is a short address with an expanding domain
     * ({@link WritingDoor#worstCaseAddress()}, 85 characters). The stored address is widened by the
     * FOLD, so its worst case is an address ON the DTO's {@code @Size(max = 255)} spending every
     * character it can on a mapping that lengthens ({@link WritingDoor#widestStoredAddress()}). One
     * fixture cannot certify both columns, which is why round 1's single fixture certified one of
     * them and reported clean.
     *
     * <p><strong>It deliberately does NOT rely on the door bound.</strong> Register and invite refuse
     * a folded address over 255 before the throttle is reached, so two of these four cases could be
     * argued away — and that argument is exactly what
     * {@code MailAddresses#requireStorableAddress} says it is not allowed to be ("hard rather than
     * impossible" was the round-1 wording, and it was false: the register door's own worst key is 384
     * with a folded address of 149). The column is either bounded at the site that writes it or it is
     * not bounded.
     *
     * <p>Both widths are read off {@link MailSendEvent} rather than restated.
     */
    @ParameterizedTest
    @EnumSource(WritingDoor.class)
    void theWidestAddressEveryWritingDtoStoresFitsTheForensicColumn(WritingDoor door) {
        var address = door.widestStoredAddress();

        assertThat(door.violations(address))
                .as("the worst case has to be REACHABLE or this case bounds nothing: %s's address "
                    + "field must accept a %d-character value that folds to %d", door.dto(),
                        address.length(), MailAddresses.storageFold(address).length())
                .isEmpty();

        assertThat(MailAddresses.storageFold(address).length() > columnWidth("recipientEmail"))
                .as("""
                        The premise of this case for %s has stopped holding: the widest address it \
                        accepts now folds to %d, and the column is %d.

                        For the three DTOs with no ASCII @Pattern on the local part the fold MUST be \
                        able to overflow -- if it cannot, this fixture is no longer a worst case and \
                        the assertion below is vacuous. For InviteMemberRequest it must NOT, and the \
                        reason is punycode rather than the fold: buying folded length in the domain \
                        costs ~5 ASCII characters per code point, so @Email's 255-character ASCII \
                        domain limit runs out first. Re-derive the fixture; do not relax the flag.""",
                        door.dto(), MailAddresses.storageFold(address).length(),
                        columnWidth("recipientEmail"))
                .isEqualTo(door.foldCanOverflowTheForensicColumn());

        assertThat(MailAddresses.fitStoredRecipient(MailAddresses.storageFold(address), length -> {})
                .length())
                .as("""
                        The address %s ACCEPTS does not fit mail_send_events.recipient_email once \
                        folded (%d characters against %d).

                        The fold runs in the SERVICE, above the throttle, on every one of these \
                        doors -- so @Size(max = 255) on the request bounds the raw text and this \
                        column holds something longer. An over-long value here is not a refused \
                        send: it is a 22001 out of the INSERT that records the send, INSIDE the \
                        advisory lock, which rolls back the ceiling row the caller has already been \
                        counted on and answers 400 with an ERROR line -- on two endpoints whose \
                        whole contract is one uniform response, unauthenticated.

                        So MailAddresses.fitStoredRecipient cuts it to this width at the site that \
                        writes it and counts the cut, exactly as throttleKey does for the key beside \
                        it. If this case is red, that call has been removed from \
                        RecipientMailThrottle.record or the column has been narrowed. Do not refuse \
                        instead: this column is forensic, nothing counts it, nothing matches on it, \
                        and the mail goes to the address the service holds.""",
                        door.dto(), MailAddresses.storageFold(address).length(),
                        columnWidth("recipientEmail"))
                .isLessThanOrEqualTo(columnWidth("recipientEmail"));
    }

    /**
     * <strong>The member the old single-DTO claim could not see, with its arithmetic and its
     * witness</strong> (HD-306). {@code RegisterRequest} carries no ASCII pattern, so 64 × U+0130 is a
     * legal local part that folds to 128 characters: the key WOULD be 128 + 1 + 255 = 384 against a
     * 320-wide column. The address is 85 characters and folds to 149 — well inside the 255 the three
     * storing doors now refuse above — so the door bound does not save this column and the truncation
     * is not decoration.
     *
     * <p>The pre-truncation length is computed from the parts rather than by calling {@code throttleKey}
     * with the truncation disabled: there is no such call, deliberately, and a test that could ask for
     * one would be asserting a mode production never runs in.
     */
    @Test
    void theRegisterDoorsWidestKeyIsTruncatedAndCounted() {
        var address = WritingDoor.REGISTER.worstCaseAddress();
        var registry = new SimpleMeterRegistry();
        var metrics = new ProductMetrics(registry, mock(UserRepository.class),
                mock(WorkspaceRepository.class), mock(ProjectRepository.class),
                mock(IssueRepository.class), mock(WorkspaceStorageUsageRepository.class));

        assertThat(WritingDoor.REGISTER.violations(address))
                .as("the fixture has to pass RegisterRequest's real constraints or it bounds nothing")
                .isEmpty();
        assertThat(address.length()).as("the submitted address").isEqualTo(85);
        assertThat(address.toLowerCase(java.util.Locale.ROOT).length())
                .as("folded, it is far inside the 255 the storing doors refuse above — which is why "
                    + "MailAddresses.requireStorableAddress does NOT save this column")
                .isEqualTo(149);

        int preTruncation = EXPANDING_LOCAL_PART.toLowerCase(java.util.Locale.ROOT).length()
                            + 1 + IDN.toASCII(worstCaseDomain(), IDN.ALLOW_UNASSIGNED).length();
        assertThat(preTruncation)
                .as("128 + \"@\" + 255: the arithmetic MailSendEvent.recipientKey's javadoc used to "
                    + "state as 64 + \"@\" + 255, certified against InviteMemberRequest alone")
                .isEqualTo(384)
                .isGreaterThan(columnWidth("recipientKey"));

        var key = MailAddresses.throttleKey(address, keyLength -> {
            assertThat(keyLength).as("the witness is told the PRE-truncation length, which is the "
                                     + "number an operator needs").isEqualTo(384);
            metrics.mailStoredAddressTruncated(ProductMetrics.TruncatedMailColumn.RECIPIENT_KEY);
        });

        assertThat(key.length())
                .as("the key is cut to the column rather than refused: over-folding is the fail-safe "
                    + "direction for a ceiling, and a 22001 here would roll back the very row the "
                    + "caller has already been counted on")
                .isEqualTo(columnWidth("recipientKey"))
                .isEqualTo(320);
        assertThat(registry.find("hamstrack.mail.stored_address_truncated")
                .tag("column", "recipient_key").counter())
                .as("""
                        The truncation is SILENT to the caller by design, so the counter is the only \
                        thing that can say it happened: two inboxes now share one ceiling bucket, and \
                        either somebody is probing with pathological addresses or the 320 arithmetic has \
                        drifted again. A drop with no witness is the shape behind five of this project's \
                        CRIT defects.""")
                .isNotNull()
                .extracting(io.micrometer.core.instrument.Counter::count).isEqualTo(1.0);
    }

    /**
     * <strong>No production class may take the witnessless {@code throttleKey(String)} overload.</strong>
     * The truncation itself cannot be forgotten — it is inside the method — but the <em>report</em> is
     * the caller's, and an overload that silently degrades is exactly the silence the retrospective
     * named. Matched on the parameter count, because that is what distinguishes the two overloads;
     * {@code ProductionBytecode} deliberately matches only owner + name, so this assertion is written
     * here rather than expressed through it.
     */
    @Test
    void noProductionCallerTakesTheWitnesslessOverload() {
        var offenders = new java.util.ArrayList<String>();
        for (var clazz : ProductionBytecode.main()) {
            if (clazz.getName().equals(MailAddresses.class.getName())) {
                continue; // the witnessed overload delegates to nothing; the 1-arg form delegates to it
            }
            for (var call : clazz.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().getName().equals(MailAddresses.class.getName())
                    && call.getName().equals("throttleKey")
                    && call.getTarget().getRawParameterTypes().size() == 1) {
                    offenders.add(clazz.getName() + " at " + call.getSourceCodeLocation());
                }
            }
        }

        assertThat(offenders)
                .as("""
                        A production class derives a recipient throttle key through the overload that \
                        reports nothing.

                        %s

                        throttleKey truncates a key that does not fit mail_send_events.recipient_key, \
                        which merges two inboxes into one ceiling bucket and costs an innocent \
                        bystander a slot. Pass the witness -- \
                        throttleKey(address, len -> { metrics.mailThrottleKeyTruncated(); log.info(...); }) \
                        -- as RecipientMailThrottle.spend does. The one-argument overload is for tests \
                        and for callers that only compare or measure a key.""",
                        String.join("\n", offenders))
                .isEmpty();
    }

    /**
     * <strong>Every production write of a STORED COPY of a recipient address goes through the shared
     * counted fit</strong> (HD-306 fix loop) — the assertion that ties the width case above to the
     * code, rather than to a helper the code might stop calling.
     *
     * <p>{@link #theWidestAddressEveryWritingDtoStoresFitsTheForensicColumn(WritingDoor)} measures
     * {@code MailAddresses.fitStoredRecipient}, so on its own it would stay green the day a write site
     * stopped calling it — which is precisely the failure round 1 shipped, one column over. Matched on
     * the METHOD that performs the write rather than on its class, because a class with two write
     * sites is a class where one of them can be missed.
     *
     * <p><strong>The category is the columns, not the column</strong> (round 3): two entities hold a
     * stored copy of a recipient address — {@code MailSendEvent.recipientEmail} and
     * {@code FailedEmail.recipient}, both {@code VARCHAR(320)} — and the second was cut by
     * {@code MailService.truncate}, an uncounted, surrogate-splitting {@code substring}, while the
     * exclusion in {@code RequestFieldLengthBoundTest} that covered all of them said each was cut "and
     * counted". {@link #SETTERS} is the enumeration; a third such column is a member the day its setter
     * is called.
     *
     * <p><strong>One declared wrapper is followed, and only while it still fits.</strong>
     * {@code MailService.fitStoredRecipient} exists because {@code MailAddresses} is a static utility
     * with no registry and cannot count its own cut, so the two {@code failed_email} writers reach the
     * fit through it. It is itself a fitting site, so it is followed exactly as long as it calls the
     * fit — the day it stops, it leaves the set and takes both of its callers with it.
     */
    @Test
    void everyProductionWriteOfTheForensicAddressGoesThroughTheFit() {
        var writeSites = new java.util.LinkedHashSet<String>();
        var fittingSites = new java.util.LinkedHashSet<String>();
        var throughWrapper = new java.util.LinkedHashSet<String>();
        for (var clazz : ProductionBytecode.main()) {
            for (var call : clazz.getMethodCallsFromSelf()) {
                var site = call.getOrigin().getOwner().getName() + "#" + call.getOrigin().getName();
                var target = call.getTargetOwner().getName() + "#" + call.getName();
                if (SETTERS.contains(target)) {
                    writeSites.add(site);
                }
                if (target.equals(MailAddresses.class.getName() + "#fitStoredRecipient")) {
                    fittingSites.add(site);
                }
                if (target.equals(COUNTING_WRAPPER)) {
                    throughWrapper.add(site);
                }
            }
        }
        if (fittingSites.contains(COUNTING_WRAPPER)) {
            fittingSites.addAll(throughWrapper);
        }

        assertThat(columnWidth(FailedEmail.class, "recipient"))
                .as("""
                        One cut serves every stored copy of a recipient address \
                        (MailAddresses.fitStoredMailValue, 320), so the columns must agree. \
                        failed_email.recipient is now %d and mail_send_events.recipient_email is %d: \
                        widening one of them without revisiting that literal gives the widened column \
                        a cut it does not need and hides the narrow one.""",
                        columnWidth(FailedEmail.class, "recipient"), columnWidth("recipientEmail"))
                .isEqualTo(columnWidth("recipientEmail"))
                .isEqualTo(320);

        assertThat(writeSites)
                .as("nothing in production writes any of %s at all — this case and the width case "
                    + "above are then both vacuous, so find the write and point them at it", SETTERS)
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(writeSites)
                .as("""
                        A production method stores a copy of a recipient address without fitting it \
                        to the 320-wide column that holds it.

                        The address reaching RecipientMailThrottle has been folded by its caller and \
                        toLowerCase APPENDS, so a value every writing DTO accepts with zero \
                        violations can be 347 characters against a 320-wide column: the INSERT then \
                        raises 22001 inside the advisory lock, rolls back the ceiling row the caller \
                        was already counted on, and answers 400 with an ERROR line -- on two \
                        unauthenticated doors whose whole contract is one uniform response. A \
                        failed_email row takes the same 22001 on a thread with no caller to tell.

                        Wrap the value in MailAddresses.fitStoredRecipient(address, length -> ...) \
                        with a witness, as RecipientMailThrottle.record does, or in \
                        MailService.fitStoredRecipient(type, address, metrics), which is that wrapper \
                        with failed_email.recipient's witness already on it. Do not refuse instead, \
                        and do not reach for MailService.truncate: these columns are forensic, and \
                        that cut counts nothing and splits surrogate pairs.""")
                .isSubsetOf(fittingSites);
    }

    /**
     * Every setter that writes a stored copy of a recipient address, {@code Owner#method}. Two
     * entities, three production call sites (2026-09-13): {@code MailService.deadLetter},
     * {@code UndeliverableMail.row}, {@code RecipientMailThrottle.record}.
     */
    private static final java.util.Set<String> SETTERS = java.util.Set.of(
            MailSendEvent.class.getName() + "#setRecipientEmail",
            FailedEmail.class.getName() + "#setRecipient");

    /** The one counted entry point the {@code failed_email} writers share; see the test's javadoc. */
    private static final String COUNTING_WRAPPER =
            MailService.class.getName() + "#fitStoredRecipient";

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
        // The domain is what this case is about, so it is asked of the DTO whose local part cannot
        // grow: with an expanding local part the key would be truncated and the question would be a
        // different one (theRegisterDoorsWidestKeyIsTruncatedAndCounted asks that one).

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

    /**
     * <strong>The same 64-character limit spent on the one lowercase mapping that lengthens</strong>
     * (U+0130 → {@code i} + U+0307), so it folds to 128 — the local part every writing DTO except
     * {@code InviteMemberRequest} accepts, and the reason that DTO's arithmetic is not the column's.
     */
    private static final String EXPANDING_LOCAL_PART = "İ".repeat(64);

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * <strong>Every DTO that reaches {@code RecipientMailThrottle}, with the worst case its own
     * constraints allow.</strong> Parameterising over this rather than over one member is HD-306's
     * correction: three of the four bound nothing about the local part, and it is the local part that
     * the fold lengthens.
     */
    private enum WritingDoor {
        /** {@code POST /api/auth/register} — {@code @Email @NotBlank @Size(max = 255)}, no pattern. */
        REGISTER(EXPANDING_LOCAL_PART, address -> VALIDATOR.validate(
                new RegisterRequest(address, "password-1234", "Person", true))),
        /** {@code POST /api/auth/forgot-password} — the same three, on a single-field record. */
        FORGOT_PASSWORD(EXPANDING_LOCAL_PART, address -> VALIDATOR.validate(
                new ForgotPasswordRequest(address))),
        /** {@code POST /api/auth/resend-verification} — likewise. */
        RESEND_VERIFICATION(EXPANDING_LOCAL_PART, address -> VALIDATOR.validate(
                new ResendVerificationRequest(address))),
        /**
         * {@code POST /api/workspaces/{ws}/invites} — the one that ALSO carries
         * {@code @Pattern("\\p{ASCII}*@[^@]*")}, so its local part cannot grow and its key is 320.
         */
        INVITE(LOCAL_PART_AT_THE_LIMIT, address -> VALIDATOR.validate(
                new InviteMemberRequest(address, null, "MEMBER")));

        private final String localPart;
        private final java.util.function.Function<String,
                java.util.Set<? extends jakarta.validation.ConstraintViolation<?>>> validate;

        WritingDoor(String localPart,
                    java.util.function.Function<String,
                            java.util.Set<? extends jakarta.validation.ConstraintViolation<?>>> validate) {
            this.localPart = localPart;
            this.validate = validate;
        }

        /**
         * <strong>The widest address this door's own constraints allow ONCE FOLDED</strong> — the
         * fixture for {@code mail_send_events.recipient_email}, and a different one from
         * {@link #worstCaseAddress()} for the reason that case's javadoc gives: the key is widened by
         * punycode and the stored address by the fold, so the two worst cases pull the fixture in
         * opposite directions (short address / expanding domain, against a maximal address spending
         * every character on a lengthening mapping).
         *
         * <p>Built and measured rather than assumed: the address sits on {@code @Size}'s 255, its local
         * part is this door's widest, and its domain is the widest FOLDING one that still passes
         * {@code @Email}'s ASCII-form limit ({@link #widestFoldingDomain}). Asserted here so a JDK whose
         * Nameprep tables move fails as a broken fixture rather than as a quietly weaker bound.
         */
        String widestStoredAddress() {
            var domain = widestFoldingDomain(RAW_ADDRESS_BOUND - localPart.length() - 1);
            var address = localPart + "@" + domain;
            assertThat(address).as("on @Size(max = 255), which is where a fold worst case lives")
                    .hasSize(RAW_ADDRESS_BOUND);
            return address;
        }

        /**
         * Whether {@link #widestStoredAddress()} can overflow {@code recipient_email} — asserted
         * rather than trusted, because it is the premise that makes the fit assertion non-vacuous.
         * True for every DTO that lets the LOCAL PART grow (64 × U+0130 → 128); false for
         * {@code InviteMemberRequest}, and not because of its {@code @Pattern} alone: folded length
         * bought in the domain costs about five ASCII characters per code point, so {@code @Email}'s
         * 255-character limit on the ASCII form runs out long before 320 folded characters do.
         */
        boolean foldCanOverflowTheForensicColumn() {
            return this != INVITE;
        }

        /**
         * The longest key this door's body can produce, built rather than assumed: a local part on
         * {@code @Email}'s 64-character limit, and a domain whose ASCII form is on its 255-character
         * one. Eight expanding labels reach 239, and the filler adds the last 16 (a dot and 15).
         *
         * <p>Each limit is asserted here rather than in the tests, so a JDK whose Nameprep tables move
         * fails as a broken fixture — which is what it would be — instead of as a quietly weaker bound.
         */
        String worstCaseAddress() {
            assertThat(localPart).as("@Email's local-part limit").hasSize(64);
            assertThat(IDN.toASCII(worstCaseDomain(), IDN.ALLOW_UNASSIGNED).length())
                    .as("@Email's domain limit is measured on the ASCII form, and this fixture is "
                        + "only a worst case while it sits exactly on it")
                    .isEqualTo(255);
            return localPart + "@" + worstCaseDomain();
        }

        /**
         * The address put through the constraints it actually meets, rather than through a locally
         * re-declared {@code @Email}. The bound this file certifies is only worth anything if it is the
         * one a request is held to.
         */
        java.util.Set<? extends jakarta.validation.ConstraintViolation<?>> violations(String address) {
            return validate.apply(address);
        }

        /** The record whose constraints this case is held to, for the failure message. */
        String dto() {
            return switch (this) {
                case REGISTER -> "RegisterRequest";
                case FORGOT_PASSWORD -> "ForgotPasswordRequest";
                case RESEND_VERIFICATION -> "ResendVerificationRequest";
                case INVITE -> "InviteMemberRequest";
            };
        }
    }

    private static String worstCaseDomain() {
        return String.join(".",
                EXPANDING_LABEL, EXPANDING_LABEL, EXPANDING_LABEL, EXPANDING_LABEL,
                EXPANDING_LABEL, EXPANDING_LABEL, EXPANDING_LABEL, EXPANDING_LABEL,
                FILLER_LABEL);
    }

    /** {@code @Size(max = 255)}, carried by every writing DTO — a repeated literal, never an import. */
    private static final int RAW_ADDRESS_BOUND = 255;

    /**
     * <strong>The domain of exactly {@code rawBudget} characters whose FOLDED form is the longest one
     * {@code @Email} still accepts.</strong> Searched rather than hand-built, so the fixture cannot
     * quietly stop being a worst case: every U+0130 in the domain buys one folded character and costs
     * about five in the punycode form Hibernate Validator measures, so the maximum is wherever those
     * two limits cross, and that crossing is a JDK Nameprep property rather than a number worth
     * transcribing here.
     *
     * @throws AssertionError when no domain of that budget fits, which would mean the premise of the
     *         forensic-column case has changed rather than that the case failed
     */
    private static String widestFoldingDomain(int rawBudget) {
        for (int expanding = Math.min(63, rawBudget - 2); expanding >= 0; expanding--) {
            var domain = expanding == 0
                    ? asciiLabels(rawBudget)
                    : "İ".repeat(expanding) + "." + asciiLabels(rawBudget - expanding - 1);
            if (domain.length() != rawBudget) {
                continue;
            }
            try {
                if (IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED).length() <= RAW_ADDRESS_BOUND) {
                    return domain;
                }
            } catch (IllegalArgumentException labelTooLong) {
                // The SECOND limit on buying folded length in the domain, and the one that bites
                // first: DNS bounds a LABEL at 63 ASCII characters, so a run of U+0130 long enough to
                // matter cannot be punycoded at all. Hibernate Validator calls the same IDN.toASCII
                // and refuses such an address, so this is "does not fit" rather than an error.
                continue;
            }
        }
        throw new AssertionError("no " + rawBudget + "-character domain passes @Email's 255-character "
                                 + "ASCII-form limit — the fixture for recipient_email's worst case "
                                 + "cannot be built, so re-derive it before trusting that case");
    }

    /** {@code zzz…}, dot-separated into labels inside DNS's 63, exactly {@code length} characters. */
    private static String asciiLabels(int length) {
        var labels = new java.util.ArrayList<String>();
        int remaining = length;
        while (remaining > 63) {
            labels.add("z".repeat(60));
            remaining -= 61;  // the label and the dot that follows it
        }
        labels.add("z".repeat(remaining));
        return String.join(".", labels);
    }

    /** The invite door's constraints, which {@link #pastTheWorstCaseItIsValidationThatRefusesAndNotTheColumn()} asks about. */
    private static java.util.Set<jakarta.validation.ConstraintViolation<InviteMemberRequest>>
            violations(String address) {
        return VALIDATOR.validate(new InviteMemberRequest(address, null, "MEMBER"));
    }

    /** The declared width of a {@link MailSendEvent} column, so no number here is a restatement. */
    private static int columnWidth(String field) {
        return columnWidth(MailSendEvent.class, field);
    }

    /** The declared width of any entity column, read off the entity rather than restated. */
    private static int columnWidth(Class<?> entity, String field) {
        try {
            return entity.getDeclaredField(field).getAnnotation(Column.class).length();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(entity.getSimpleName() + "." + field + " no longer exists — this "
                    + "file asserts that a stored address fits that column", e);
        }
    }
}
