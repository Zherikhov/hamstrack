package com.hamstrack.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The instance-wide {@code app.*} settings an operator sets before the process starts.
 *
 * <p><strong>{@code @Validated} because a component below declares a constraint.</strong> On a
 * {@code @ConfigurationProperties} class that annotation is what makes a declared constraint fire
 * at all: binding happens once at startup, nothing is dispatched to, and bean validation has no
 * other entry point — so an out-of-range value aborts the boot instead of being carried into the
 * running instance. That is the {@link MailAsyncProperties} / {@link WorkspaceProperties} pattern
 * and the invariant this codebase keeps: <em>a properties class that declares a constraint carries
 * {@code @Validated}</em>, because one without it is a constraint nobody enforces.
 *
 * <p>The opposite rule holds for the other kind of bean. On anything Spring MVC dispatches to, the
 * same annotation <em>suppresses</em> MVC's own method validation and is forbidden (ADR-0018) — see
 * {@code MetaController}, which reads this class and is deliberately un-annotated. The two kinds of
 * bean therefore take opposite answers to one question, and neither answer is a default: a declared
 * constraint is enforced only where its own kind of bean has been wired to enforce it.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String baseUrl,
        @DefaultValue("noreply@example.com") String mailFrom,
        Registration registration,
        Legal legal,
        /*
         * @Valid, not merely @Validated on the enclosing type: the constraint that matters is on a
         * component of the NESTED record, and a cascade is what carries validation across that
         * boundary by construction rather than by the binder happening to visit the nested value on
         * its way past. Same shape, for the same reason, as MailAsyncProperties' nested groups.
         */
        @DefaultValue @Valid Privacy privacy,
        Demo demo,
        Onboarding onboarding
) {
    public record Registration(boolean publicSignupEnabled) {}

    public record Demo(@DefaultValue("true") boolean seedOnFirstLogin) {}

    // First-login onboarding (choose to create or join a team). Cloud-only:
    // the cloud profile enables it, the base default is off (so DC skips it).
    public record Onboarding(@DefaultValue("false") boolean enabled) {}

    public record Legal(
            @DefaultValue("true") boolean publicLandingEnabled,
            @DefaultValue("true") boolean termsAcceptanceRequired
    ) {}

    /**
     * Privacy/compliance contact (HD-193, {@code docs/design/account-deletion-proposal.md}).
     *
     * <p>{@code contactEmail} is the address a user is told to write to when they want their
     * account deleted, and it is served on the <strong>unauthenticated</strong>
     * {@code GET /api/meta} as {@code privacyContactEmail}. So <strong>an operator who sets
     * this publishes it</strong> — to anyone who can reach the instance, signed in or not.
     * That is the operator's call to make and not ours, which is why the default is empty
     * rather than a placeholder address: a placeholder here would be an address published on
     * every install by default, and a wrong one.
     *
     * <p><strong>Empty means "not configured", never "there is no way to ask".</strong> The
     * Account page renders its deletion section in full either way and falls back to telling
     * the user that this installation's administrator handles the request (proposal section
     * 9.3): an affordance visible only where somebody remembered to set a property is
     * unreachable for exactly the operators who did not know they had to set it.
     *
     * <p>A blank value is normalised to empty for the same reason — a whitespace-only
     * setting is a typo, and counting it as "configured" would publish a {@code mailto:} link
     * to nowhere in place of the fallback sentence.
     *
     * <p><strong>Validated at startup, because the value is published verbatim and then
     * interpolated into a {@code mailto:}.</strong> The constraints below are the reason the
     * enclosing record carries {@code @Validated}. Nothing downstream escapes this string:
     * {@code GET /api/meta} serves it unauthenticated and the Account page builds a mail link out
     * of it. No privilege boundary is crossed — the operator is configuring their own instance —
     * but a garbled address is something they should be told about while they can still fix it,
     * rather than something an end user discovers by clicking a link that composes the wrong
     * message. So it aborts the boot, in the {@link MailAsyncProperties} /
     * {@link WorkspaceProperties} fail-fast-never-clamp direction.
     *
     * <p><strong>Three constraints, because {@code @Email} on its own is neither of the two checks
     * this value needs.</strong> It is a shape check: it is not a length check (that argument, and
     * the 255, belong to {@code EmailLengthBoundTest} and every other address field in the
     * product), and it is not a "still one address" check either. Hibernate Validator's email
     * domain is an RFC 5322 <em>atom</em>, and {@code DomainNameUtil.DOMAIN_CHARS_WITHOUT_DASH}
     * includes {@code ! # $ % & ' * + / = ? ^ _ ` &#123; | &#125; ~} — so
     * {@code privacy@example.org&bcc=victim} is a <strong>valid</strong> email address and
     * {@code @Email} passes it. In a {@code mailto:} URL those same characters stop being part of
     * the address and start being extra mail headers, so a user who clicks "email us about deleting
     * my account" would silently copy a third party. Verified against hibernate-validator 9.1.0,
     * and observed passing here before the {@code @Pattern} existed. So each annotation refuses a
     * set the others do not, and dropping any one of them drops a case nothing else covers —
     * {@code PrivacyContactEmailTest} exercises them in separate methods for exactly that reason.
     *
     * <p><strong>Empty stays legal, and that is load-bearing rather than incidental.</strong>
     * Hibernate Validator's email validator treats a zero-length value as valid, and the pattern is
     * written with {@code *} rather than {@code +} for the same reason, so the documented "unset"
     * state — {@code PRIVACY_CONTACT_EMAIL=} in an env file, or the variable absent entirely —
     * binds cleanly on every default DC install. The constraints therefore refuse a value that was
     * meant to be an address and is not, and never the operator who published none. The canonical
     * constructor still runs first, so a whitespace-only setting is stripped to empty and passes as
     * "unset" rather than being refused as malformed.
     */
    public record Privacy(
            @DefaultValue("")
            @Email(message = NOT_AN_ADDRESS)
            @Pattern(regexp = MAILTO_SAFE, message = NOT_ONE_ADDRESS)
            @Size(max = 255, message = TOO_LONG)
            String contactEmail
    ) {
        /**
         * Every character that is <em>not</em> a mailto: separator, zero or more times — so the
         * empty "unset" value matches and anything carrying a second header does not.
         *
         * <p>A blacklist rather than an address whitelist on purpose: {@code @Email} already
         * decides what an address is, and this one has a single job, which is to say the string is
         * still <em>one</em> address by the time a browser reads it as a URL.
         */
        private static final String MAILTO_SAFE = "[^\\s?&#%,;<>\"\\\\]*";

        /*
         * The @Size bound is the same 255 every address field in this codebase carries, for the
         * reason EmailLengthBoundTest gives about all of them: @Email accepts roughly 320
         * characters, so it is a shape check and never a length one, and the bound belongs on the
         * field rather than on today's itinerary for it. Nothing here writes a column - this value
         * is config, not a row - but it IS published on an unauthenticated endpoint and pasted into
         * a link, and 255 is the width every other address in the product is held to.
         *
         * It is written as a literal in the annotation, and the same number is repeated in the
         * message below, because EmailLengthBoundTest's scan reads DIGITS: `@Size(max = SOME_NAME)`
         * is indistinguishable from no bound at all to it, and the failure it produces then says
         * "no @Size" and sends the next reader looking for the wrong thing. Refactoring the two
         * into a constant is therefore an edit that turns this field's guard off silently.
         */

        /*
         * The three refusals, hoisted out of the annotations. Each names PRIVACY_CONTACT_EMAIL -
         * the thing the operator actually typed - because bean validation's own rendering names the
         * bound property, which is not a string they have ever seen.
         */
        private static final String NOT_AN_ADDRESS =
                "must be an email address, or empty to publish none. PRIVACY_CONTACT_EMAIL is "
                + "served verbatim on the unauthenticated GET /api/meta and turned into a mailto: "
                + "link, so leave the value empty rather than setting something that is not an "
                + "address";

        private static final String NOT_ONE_ADDRESS =
                "must not contain a character that changes what a mailto: link means: whitespace, "
                + "a quote, a backslash, or one of ? & # % , ; < >. PRIVACY_CONTACT_EMAIL becomes "
                + "the mailto: the Account page opens, where those separators introduce extra "
                + "headers (cc, bcc) instead of addressing the mailbox you meant. Set a plain "
                + "address, or leave it empty to publish none";

        private static final String TOO_LONG =
                "must be at most 255 characters. PRIVACY_CONTACT_EMAIL is published to anyone who "
                + "can reach this instance, so it is bounded like every other address the product "
                + "handles";

        public Privacy {
            contactEmail = contactEmail == null ? "" : contactEmail.strip();
        }
    }
}
