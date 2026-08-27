package com.hamstrack.common.mail;

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
     * inconvenience, self-inflicted by the folding rules, and it expires. For the GLOBAL
     * per-recipient daily cap it is worse and it lands on somebody else — that ceiling is
     * sender-invariant, so an over-fold spends a slot belonging to <em>a different, innocent
     * person</em> who merely shares a folded key, and denies them an invitation they were entitled
     * to. Nobody involved can see why. That asymmetry is the argument against widening these rules
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
     * WINDOW, not the retention: every window here is at most 24 hours, so rows older than that are
     * already invisible to both ceilings, and rows written after the deploy key correctly from the
     * first send — both ceilings are fully re-armed within one window whether retention is 2 days
     * or 90. A {@code key_version} column would buy nothing, because the only query it enables
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
     * @param email the submitted address; folded with {@code Locale.ROOT} here, so callers may pass
     *              either the raw or the already-lower-cased form
     * @return a stable key for the destination inbox — never {@code null}, and never empty for a
     *         non-empty input. A malformed address (no {@code @}, empty local part) is keyed on
     *         verbatim rather than on a guess
     */
    public static String throttleKey(String email) {
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
