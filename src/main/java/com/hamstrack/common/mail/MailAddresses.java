package com.hamstrack.common.mail;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.IDN;
import java.util.Locale;
import java.util.Set;

/**
 * Address handling that more than one mail path needs, in one place.
 *
 * <p><strong>{@link #domainOf} is the bridge between an alert that cannot carry identifying labels
 * and an operator who needs to know who</strong> (HD-190 §10.1). {@code ProductMetrics}' cardinality
 * rule forbids putting an address or an id in Prometheus, which is right and which also means the
 * volume alerts cannot distinguish one abuser inviting four hundred addresses across four hundred
 * domains from one customer onboarding four hundred colleagues at one domain. Those two are
 * trivially distinguishable in a log line that carries the <em>domain only</em> — and the local part
 * is what makes an address personal data, so it never appears.
 *
 * <p><strong>{@link #throttleKey} is what makes the recipient-keyed ceilings bind at all.</strong>
 * A ceiling that compares raw addresses counts mailboxes it can distinguish, not inboxes a human
 * opens, and those are not the same set — see that method for the direction-of-harm argument, which
 * is the opposite of the one on the invite <em>redemption</em> path.
 *
 * <p><strong>{@link #requireStorableAddress} is the exact opposite of {@link #throttleKey}, and the
 * two must never be confused</strong> (HD-306). {@code throttleKey} produces a COUNTING key — never
 * a recipient, never an identity, over-folded on purpose, and truncated rather than refused when it
 * does not fit. {@code requireStorableAddress} produces the IDENTITY an account or an invitation IS:
 * folded once with {@code Locale.ROOT} (HD-120) and <em>refused</em> when the folded form is wider
 * than the column that must hold it, because truncating an identity hands the account to a different
 * address. All of these live here because all of them are address handling that more than one mail
 * path needs.
 *
 * <p><strong>So the question this class answers about a length is never "which door is this?" but
 * "what IS the value?"</strong>, and the family splits on that and on nothing else: an identity is
 * refused ({@link #requireStorableAddress} → {@code users.email}, {@code workspace_invites.email}),
 * and a derived key or a forensic copy is cut to its column at the site that writes it
 * ({@link #throttleKey} → {@code mail_send_events.recipient_key}, {@link #fitStoredRecipient} →
 * {@code .recipient_email} and, through {@code MailService.fitStoredRecipient}, →
 * {@code failed_email.recipient}). HD-306 got that split right for one column at a time across two
 * rounds, which is why the cut now happens in one shared place with one shared width — and why the
 * third column stopped using {@code MailService.truncate}'s uncounted, surrogate-splitting
 * {@code substring} in the fix loop that noticed the difference.
 */
public final class MailAddresses {

    /**
     * The one provider whose local part is folded further than {@code +tag}. Google documents that
     * {@code gmail.com} ignores dots and that {@code googlemail.com} is the same mailbox namespace;
     * both are stable, published facts about delivery rather than guesses.
     */
    private static final Set<String> GMAIL_DOMAINS = Set.of("gmail.com", "googlemail.com");

    private MailAddresses() {
    }

    /**
     * The part after the last {@code @}, or {@code "unknown"} when there is no usable one.
     *
     * <p>Never the local part, and never the whole address: this value is written to a log an
     * operator greps and a shipper forwards, so it has to be safe to keep for as long as logs are
     * kept. A malformed address yields {@code "unknown"} rather than itself.
     */
    public static String domainOf(String email) {
        if (email == null) {
            return "unknown";
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return "unknown";
        }
        return email.substring(at + 1);
    }

    /**
     * <strong>Fold an address for STORAGE, and refuse what the target column cannot hold</strong>
     * (HD-306). Mirrors {@code ClassificationNames.requireValidName(raw, maxLength, noun)}
     * deliberately: that is this codebase's one shape for "measure a derived value's length where it
     * is derived", and a case fold is a derived value exactly as NFC is.
     *
     * <p><strong>A case fold is not length-preserving.</strong> {@code String.toLowerCase} maps
     * U+0130 LATIN CAPITAL LETTER I WITH DOT ABOVE to two code points ({@code i} + U+0307), so a
     * 255-character address whose local part is 64 of them passes {@code @Email @Size(max = 255)}
     * with zero violations and folds to <strong>319</strong> characters (measured 2026-09-13 against
     * Hibernate Validator 9.1.0 on JDK 21). A {@code @Size} on the request record bounds the RAW
     * text and the column bounds the STORED text; a door that folds between the two and measures
     * nothing lets its caller drive the commit into SQLSTATE 22001 on demand — a 400 the global
     * handler logs at ERROR, after a bcrypt and a mail ceiling have already been spent. Roughly
     * twenty unconditional UPPERCASE mappings lengthen too (U+00DF → {@code SS}, the U+FB00 ligature
     * block, several Greek diacritic forms), so a {@code toUpperCase} door is a member of this
     * category even where a pattern happens to keep it safe today.
     *
     * <p><strong>This is the opposite of {@link #throttleKey} and must not be swapped with it.</strong>
     * {@code throttleKey} is a THROTTLE KEY and never a recipient or a stored identity: it over-folds
     * on purpose (the fail-safe direction for a ceiling) and truncates when it does not fit.
     * {@code requireStorableAddress} returns the IDENTITY — {@code users.email} carries a byte-exact
     * unique and every lookup is an exact match, {@code acceptInvite} matches the invited address
     * exactly — so the only correct answer to "too long" here is a refusal. Truncating would hand
     * the account to a <em>different address</em>.
     *
     * <p><strong>It does not trim, and that is deliberate.</strong> The fold IS the account identity,
     * so this method changes nothing about which address a caller is asking for; a door that means to
     * strip surrounding whitespace (the seeded administrator's configured address does) strips before
     * calling and gets the length of the value it will actually store.
     *
     * <p><strong>400, not 422.</strong> The discriminator, stated once so the next bound does not
     * re-argue it: <em>a bound that is a column width answers 400; a bound that is an algorithm's
     * refusal answers 422.</em> BCrypt's 72 bytes ({@code PasswordLimits}) is a property of the
     * encoder and cannot be expressed in the unit the client counts in, so it keeps its 422. 255
     * characters is the same kind of statement {@code @Size} already makes on the same field with the
     * same status. The parenthesis appears only when the raw value was WITHIN the limit, because
     * "at most 255 characters" is otherwise a lie to somebody who typed 255.
     *
     * @param raw       the submitted address, {@code null} allowed (treated as {@code ""} and refused
     *                  by nothing — {@code @NotBlank} answers first, as it does for
     *                  {@code ClassificationNames.normalize})
     * @param maxLength the door's post-fold limit, equal to its column width. An ADR-0017 repeated
     *                  literal at each call site: a door that imported the constant it is testing
     *                  would agree with any value that constant took
     * @return the folded address, so a caller cannot use this gate and then store the raw value
     */
    public static String requireStorableAddress(String raw, int maxLength) {
        if (exceedsStorableLength(raw, maxLength)) {
            var grew = raw.length() <= maxLength
                    ? " (lower-casing it turned its " + raw.length() + " characters into "
                      + storageFold(raw).length() + ")"
                    : "";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Email address must be at most " + maxLength + " characters" + grew
                    + " — use a shorter address.");
        }
        return storageFold(raw);
    }

    /**
     * The storage fold on its own: {@code Locale.ROOT}, no trimming, {@code null} → {@code ""}.
     *
     * <p>Exported so that a door whose refusal is <em>not</em> an HTTP status can still say what the
     * stored value would have been. Pairs with {@link #exceedsStorableLength} exactly as
     * {@code PasswordLimits.byteLength} pairs with {@code PasswordLimits.exceedsEncoderLimit}, and
     * for the same reason: three doors refuse with a 400 and one refuses a boot, so the
     * <em>measurement</em> is shared and only the refusal differs.
     */
    public static String storageFold(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    }

    /**
     * <strong>The bound itself, in one place.</strong> Every door that stores a folded address asks
     * this and nothing else — a second length check written by hand is how five display-name folds
     * drifted apart before HD-297, and this family has four doors on day one.
     *
     * @param maxLength the target column's width; see {@link #requireStorableAddress} for why it is a
     *                  repeated literal at each call site rather than an imported constant
     */
    public static boolean exceedsStorableLength(String raw, int maxLength) {
        return storageFold(raw).length() > maxLength;
    }

    /**
     * The value the recipient-keyed ceilings count on: one key per <em>inbox</em>, not one key per
     * spelling of an address (HD-190 §6.2/§6.3, {@code mail_send_events.recipient_key}).
     *
     * <p><strong>Why this exists.</strong> Lower-casing alone made every ceiling in the feature
     * decorative. {@code victim+1@gmail.com}, {@code victim+2@gmail.com} and
     * {@code v.i.c.t.i.m@googlemail.com} are distinct strings that all land in one human's inbox, so
     * an attacker re-spelled the ticket's attack — "one victim, many workspaces" became "one victim,
     * many local-part tags" — at the cost of one keystroke, and both persisted ceilings read zero on
     * every request. Dots make the Gmail key space effectively unbounded on their own, so folding
     * {@code +tag} without folding dots would leave the control fully defeated for the largest
     * consumer mail provider, which is where the typical victim reads their mail.
     *
     * <p><strong>Over-folding is the safe direction HERE, and that is not a general rule.</strong>
     * For a ceiling, an extra match raises a count and refuses <em>sooner</em>. Under-folding is the
     * hole — it hands out free sends. The opposite is true on the invite <em>redemption</em> path
     * (HD-120), where an extra match lets the wrong person accept somebody else's invitation, and
     * where addresses are therefore compared exactly. Do not transplant either argument to the
     * other; the direction of harm is what decides, and it points opposite ways.
     *
     * <p><strong>But "safe" is not "free", and the worst case is different for each ceiling that
     * uses this key — the milder one is not the bound.</strong> For the per-(sender, recipient)
     * cooldown an over-fold costs an honest sender a wait they did not earn: their own
     * inconvenience, self-inflicted by the folding rules, and it expires.
     *
     * <p><strong>For a sender-invariant VOLUME cap it is worse, it lands on somebody else, and how
     * much worse depends entirely on what that ceiling is standing in front of.</strong> Such a
     * ceiling counts a stranger's traffic against a slot, so an over-fold spends the allowance of
     * <em>a different, innocent person</em> who merely shares a folded key — and nobody involved
     * can see why, because these refusals are terse and some of them are silent. What that costs
     * them is whatever the mail behind the ceiling was <em>for</em>: an invitation they were
     * entitled to, or their own account recovery, or their ability to register at all. So the rule
     * is a property of the class rather than of today's members: <strong>an over-fold denies a
     * bystander the most valuable thing any ceiling on this key guards, and that value goes up
     * every time a new kind of mail joins the mechanism</strong> — it went up once already, without
     * a line of this file changing. That asymmetry is the argument against widening these rules
     * on a guess, and it is why there is no configurable delimiter here: every rule below is a
     * published, provider-documented fact about delivery or a standard normalisation, never a
     * heuristic about who somebody is.
     *
     * <p><strong>Which is why confusables are NOT folded, and must not be.</strong> Homoglyph
     * mapping (Cyrillic а onto Latin a, dotless ı onto i) is a guess about identity rather than a
     * fact about delivery: {@code раypal@example.com} and {@code paypal@example.com} are two
     * different mailboxes belonging to two different humans, and collapsing them applies the
     * paragraph above to strangers at scale. The redemption path draws the same line for the
     * opposite reason (see {@code WorkspaceService.acceptInvite}); both times the answer is that
     * case-insensitive/confusable matching is a second spelling of somebody else's address.
     *
     * <p><strong>A change to these rules needs no recorded key version, and the reason is not
     * "retention is short".</strong> Keys are matched by EQUALITY, so any change — widening or
     * narrowing — can only make already-written rows stop matching; it can never create a match
     * that did not exist. The failure mode is therefore always an UNDERCOUNT, i.e. fail-open, never
     * a spurious refusal against somebody who did nothing. And the bound on it is the CEILING
     * WINDOW, not the retention: no ceiling window may exceed 24 hours
     * ({@code MailThrottlePolicy.MAX_CEILING_WINDOW}, enforced at bean creation), so rows older
     * than that are already invisible to every ceiling, and rows written after the deploy key
     * correctly from the first send — every ceiling is fully re-armed within its own window
     * whatever the retention is. A {@code key_version} column would buy nothing, because the only query it enables
     * ("count rows whose version is current") IS that undercount, merely written down. What does
     * matter, and is easy to miss: {@code mail_send_events.recipient_email} stores the address
     * exactly, so a folding change is BACKFILLABLE if it ever needs to be.
     *
     * <p><strong>The alternative that was rejected.</strong> Stripping {@code +tag} only, plus a
     * per-{@code (sender, recipient-domain)} daily sub-ceiling to catch the fan-out without modelling
     * any provider. Rejected because a per-domain ceiling binds hardest on exactly the honest case —
     * an admin onboarding twenty colleagues who all use {@code gmail.com} — while an attacker
     * spreading over a handful of domains slips under it. A control whose false positives are honest
     * bulk onboarding and whose true positives are optional is the wrong shape.
     *
     * <p><strong>This is a throttle key and never a recipient.</strong> Mail is sent to the address
     * as submitted (lower-cased), because {@code +} is a perfectly ordinary local-part character on
     * providers that do not implement sub-addressing. {@code recipient_email} keeps the exact value
     * for the same reason: it is what the refusal echoes and what forensics needs.
     *
     * <p><strong>The key is TRUNCATED to fit its column, and that is the opposite decision from
     * {@link #requireStorableAddress}'s</strong> (HD-306). {@code toLowerCase} runs on the local part
     * in this method's first line and it APPENDS: 64 × U+0130 folds to 128 characters, so an address
     * of 85 characters that {@code RegisterRequest} accepts with zero violations produces a key of
     * <strong>384</strong> (measured 2026-09-13) against a 320-wide column. A 22001 raised
     * <em>inside</em> the throttle is the worst available outcome, because it rolls back the
     * {@code mail_send_events} row the caller has already been counted on — the free-probe hazard the
     * invite ordering comment exists to prevent. Over-folding, by contrast, is this class's published
     * fail-safe direction for a ceiling: an extra match raises a count and refuses sooner. So the key
     * is cut rather than refused, and the cut is COUNTED.
     *
     * <p><strong>The door bound in {@link #requireStorableAddress} does NOT make reaching this
     * truncation unlikely, and an earlier round of HD-306 said it made it "hard rather than
     * impossible".</strong> That was measured false in the same session it was written: the register
     * door's own worst case is a key of 384 produced by an address whose FOLDED form is 149 characters,
     * i.e. 106 characters clear of the 255 that door refuses at — the door bound is about the address
     * and this truncation is about a punycode expansion of its domain, so the one is simply not
     * evidence about the other. The two statements are independent, and each column is bounded at the
     * site that writes it or not at all.
     *
     * @param email       the submitted address; folded with {@code Locale.ROOT} here, so callers may
     *                    pass either the raw or the already-lower-cased form
     * @param onTruncated notified with the key's PRE-truncation length when the key did not fit, so
     *                    the degradation has a witness where a witness can exist — this class is a
     *                    static utility with no registry and no logger, and {@code ProductMetrics}
     *                    owns every meter name in the product. The truncation itself is
     *                    unconditional and cannot be forgotten by a call site; only the reporting is
     *                    the caller's, and {@code MailAddressesThrottleKeyTest} refuses any
     *                    production call to the witnessless overload
     * @return a stable key for the destination inbox — never {@code null}, never empty for a
     *         non-empty input, and never wider than {@code mail_send_events.recipient_key}. A
     *         malformed address (no {@code @}, empty local part) is keyed on verbatim rather than on
     *         a guess
     */
    public static String throttleKey(String email, java.util.function.IntConsumer onTruncated) {
        return fitStoredMailValue(foldedThrottleKey(email), onTruncated);
    }

    /**
     * The same key with no witness for a truncation — for tests and for any caller that only compares
     * or measures a key. Production goes through
     * {@link #throttleKey(String, java.util.function.IntConsumer)}; nothing in {@code src/main} may
     * call this overload, and a test holds that (a silent degradation is the shape behind five of this
     * project's CRIT defects).
     */
    public static String throttleKey(String email) {
        return throttleKey(email, null);
    }

    /**
     * <strong>A stored copy of the address, cut to the column that holds it</strong> — written by
     * {@code RecipientMailThrottle.record} into {@code mail_send_events.recipient_email} beside the
     * key {@link #throttleKey} produces, and by {@code MailService.fitStoredRecipient} (which adds
     * that column's witness) into {@code failed_email.recipient} (HD-306 fix loop).
     *
     * <p><strong>Why this exists, i.e. what HD-306 round 1 missed.</strong> That round bounded the
     * KEY and left this column verbatim, and both are written from the same caller-folded address:
     * {@code AuthService.resendVerification} and {@code AuthService.forgotPassword} fold with
     * {@code toLowerCase(Locale.ROOT)} and hand the result to the throttle, so an address their
     * {@code @Size(max = 255)} accepts with zero violations arrives here at <strong>347</strong>
     * characters (measured 2026-09-13, {@code MailAddressesThrottleKeyTest}) against a 320-wide
     * column. Both doors are UNAUTHENTICATED and both answer one uniform sentence whatever happens,
     * so the 22001 out of that INSERT — raised inside the advisory lock, rolling back the ceiling row
     * the caller has already been counted on, answered 400 and logged at ERROR — is both a shape
     * change on an endpoint that must not have one and a free probe of a stranger's ceilings.
     *
     * <p><strong>TRUNCATED and not refused, the same decision as the key's and for a reason of its
     * own.</strong> This column is FORENSIC: nothing counts it, nothing matches on it, no unique
     * index reads it, and the message itself goes to the address the service holds in its own
     * variable — so a cut value costs an operator a few characters of a domain suffix and costs
     * nobody an identity. Contrast {@link #requireStorableAddress}, which refuses: {@code users.email}
     * and {@code workspace_invites.email} ARE identities, and truncating one hands the account to a
     * different address. The discriminator is what the value IS, never which door writes it.
     *
     * @param recipientEmail the address as the caller folded it; {@code null} is treated as
     *                       {@code ""}, because a throttle that threw on a malformed address would
     *                       convert a 400-shaped mistake into a 500 on a mail path
     * @param onTruncated    notified with the PRE-truncation length, and <strong>required</strong>
     *                       rather than nullable: unlike {@link #throttleKey} this has no witnessless
     *                       overload for tests to reach for, so there is no call shape a production
     *                       site could take by accident and nothing for a bytecode rule to police
     * @return the value to store, never wider than {@code mail_send_events.recipient_email}
     */
    public static String fitStoredRecipient(String recipientEmail,
                                           java.util.function.IntConsumer onTruncated) {
        java.util.Objects.requireNonNull(onTruncated, "onTruncated");
        return fitStoredMailValue(recipientEmail == null ? "" : recipientEmail, onTruncated);
    }

    /**
     * <strong>One width and one cut for both of {@code mail_send_events}' address columns</strong>
     * ({@code recipient_key} and {@code recipient_email}), which is the shape HD-306's fix loop
     * asked for: the two were bounded in separate rounds, and two copies of this method would have
     * been two chances to bound one of them and report clean.
     *
     * <p>320 is the width of both, and of {@code failed_email.recipient}, which reaches this cut
     * through {@code MailService.fitStoredRecipient}. An ADR-0017 repeated literal: widening any of
     * those columns without revisiting this line is what {@code MailAddressesThrottleKeyTest} reads
     * off the entities to catch.
     *
     * @param onTruncated nullable HERE and only here, because {@link #throttleKey(String)}'s
     *                    witnessless overload delegates through it; both public entry points state
     *                    their own contract
     */
    private static String fitStoredMailValue(String value, java.util.function.IntConsumer onTruncated) {
        if (value.length() <= STORED_MAIL_VALUE_WIDTH) {
            return value;
        }
        if (onTruncated != null) {
            onTruncated.accept(value.length());
        }
        // Never split a surrogate pair: a lone surrogate is not valid UTF-8 and would trade one
        // storage defect for another at the driver.
        int cut = Character.isHighSurrogate(value.charAt(STORED_MAIL_VALUE_WIDTH - 1))
                ? STORED_MAIL_VALUE_WIDTH - 1
                : STORED_MAIL_VALUE_WIDTH;
        return value.substring(0, cut);
    }

    /** @see #fitStoredMailValue */
    private static final int STORED_MAIL_VALUE_WIDTH = 320;

    private static String foldedThrottleKey(String email) {
        if (email == null) {
            return "";
        }
        var folded = email.trim().toLowerCase(Locale.ROOT);
        int at = folded.lastIndexOf('@');
        if (at <= 0 || at == folded.length() - 1) {
            return folded;
        }
        var local = folded.substring(0, at);
        // Punycode BEFORE the domain is compared to anything. An internationalised domain and its
        // xn-- form are one domain at the DNS level and one inbox in practice, so keying on the
        // spelling gives a victim on such a domain two keys and twice every ceiling. For almost
        // every input this is normalisation to the wire form, not a guess about which domains
        // resemble which — the line the javadoc draws at confusables stays where it is. It also has
        // to run before the Gmail lookup below, or a unicode spelling of a Gmail domain would miss
        // it.
        //
        // THE KNOWN EXCEPTION, ACCEPTED RATHER THAN UNNOTICED: java.net.IDN implements IDNA2003, and
        // its Nameprep step is not what a modern resolver does to the four UTS-46 DEVIATION
        // characters. It maps eszett (U+00DF) to "ss" and final sigma (U+03C2) to sigma, and deletes
        // ZWJ (U+200D) and ZWNJ (U+200C). Browsers and registrars use UTS-46 NONTRANSITIONAL, where
        // the eszett domain and the double-s domain are two DIFFERENT live registrations — so for
        // exactly those four this call produces a wire form no resolver would, and it does merge two
        // distinct domains, which is the confusables side of the line the paragraph above draws.
        // Kept anyway: the JDK ships no UTS-46, and dropping the conversion to avoid this reopens a
        // real under-fold (two keys and twice every ceiling for every IDN victim) in order to close
        // a rarer over-fold. Nobody gains a send from it — each message costs one slot under either
        // spelling — so the whole cost is the collateral over-fold the daily cap's javadoc already
        // names: an innocent person at one of the two domains spends a slot belonging to a different
        // person at the other, and neither can see why. Revisit if a UTS-46 implementation is ever on
        // the classpath for another reason; it is not worth a dependency of its own.
        var domain = asciiDomain(folded.substring(at + 1));
        int plus = local.indexOf('+');
        if (plus >= 0) {
            local = local.substring(0, plus);
        }
        // Quoted local parts, folded to the same key as their unquoted spelling. RFC 5321 allows
        // "victim"@x.com, and Hibernate Validator's @Email accepts it, so without this line
        // "victim"@gmail.com / "v.i.c.t.i.m"@gmail.com / "victim+9"@gmail.com are three more keys
        // for one mailbox on any receiver that unquotes (Postfix, Exchange) — the +tag hole again
        // wearing punctuation. Stripping rather than refusing, because this is the over-folding
        // direction and over-folding is the fail-safe one for a ceiling. Runs AFTER the +tag strip
        // so that "victim+9" loses the tag first, and BEFORE the Gmail dot-strip so that a quoted
        // dotted local part folds the same way an unquoted one does.
        local = local.replace("\"", "");
        if (GMAIL_DOMAINS.contains(domain)) {
            local = local.replace(".", "");
            domain = "gmail.com";
        }
        if (local.isEmpty()) {
            // "+tag@example.com" — the whole local part was a tag. Keying on "@example.com" would
            // collapse every such address in a domain into one bucket, so keep what was submitted.
            local = folded.substring(0, at);
        }
        return local + "@" + domain;
    }

    /**
     * The domain in its ASCII (punycode) form, or verbatim when it cannot be converted.
     *
     * <p>Falls back rather than throwing: this runs on the request path of a ceiling, and a key that
     * is merely unusual is a key — refusing to produce one would turn a malformed address into a
     * 500, and returning nothing would let it past the ceilings entirely. {@code ALLOW_UNASSIGNED}
     * because we are normalising a spelling, not validating the address; validation is the DTO's
     * job and has already happened.
     */
    private static String asciiDomain(String domain) {
        try {
            return IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return domain;
        }
    }
}
