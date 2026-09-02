package com.hamstrack.auth.service;

import com.hamstrack.auth.dto.*;
import com.hamstrack.auth.entity.*;
import com.hamstrack.auth.exception.*;
import com.hamstrack.auth.repository.*;
import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.config.JwtProperties;
import com.hamstrack.common.mail.MailAddresses;
import com.hamstrack.common.mail.MailService;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.observability.ProductMetrics.LoginOutcome;
import com.hamstrack.common.observability.ProductMetrics.LoginReason;
import com.hamstrack.common.observability.ProductMetrics.PasswordResetPhase;
import com.hamstrack.common.ratelimit.RateLimitService;
import com.hamstrack.common.ratelimit.RecipientMailThrottle;
import com.hamstrack.common.seed.DataSeeder;
import com.hamstrack.common.security.JwtService;
import com.hamstrack.common.security.PasswordLimits;
import com.hamstrack.common.tx.AfterCommit;
import com.hamstrack.common.util.TokenUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    static final String REFRESH_COOKIE = "refresh_token";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final AppProperties appProperties;
    private final MailService mailService;
    private final RateLimitService rateLimitService;
    // HD-202. The SAME mechanism the invitation ceilings use — one control for "this instance is
    // about to mail an address somebody typed", not a second limiter that half-overlaps it. The
    // two uniform-response flows enter it through allowAnonymousSend, which spends and records
    // exactly as the invite path does and differs only in refusing without saying so; register
    // enters it through requireAndRecordWhereEndpointDiscloses, which answers 429 because register
    // already publishes address existence through its own 409. THREE FLOWS, THREE BUDGETS: register
    // holds EmailType.REGISTRATION_VERIFICATION rather than sharing resend-verification's, because
    // a shared bucket is fillable through whichever of its endpoints is cheapest to spend, and that
    // endpoint sends no mail at an address with no account.
    private final RecipientMailThrottle mailThrottle;
    private final ProductMetrics metrics;

    @Transactional
    public void register(RegisterRequest req) {
        // When public signup is off (DC default), self-registration is fully
        // closed — no first-user bootstrap. Accounts are created by the system
        // admin (Admin console → Users); the initial admin comes from SEED_ADMIN_*.
        if (!appProperties.registration().publicSignupEnabled()) {
            throw new RegistrationDisabledException();
        }
        if (appProperties.legal().termsAcceptanceRequired() && !req.hasAcceptedTerms()) {
            throw new TermsNotAcceptedException();
        }
        rejectPublishedPassword(req.password());
        rejectUnencodablePassword(req.password());
        // Locale.ROOT, never the JVM default. This fold IS the account identity: users.email
        // carries a byte-exact UNIQUE and every lookup is an exact match, so a fold that varies
        // with the container locale varies which address a person owns - a Turkish JVM stores
        // Ivan@x.com as a dotless-i address that exists nowhere, and the same typed address
        // stops resolving to the same row the day the locale changes. The rule is the category,
        // not this line: any fold whose result is stored, mailed, or used as a lookup key names
        // its locale (HD-120).
        var email = req.email().toLowerCase(Locale.ROOT);
        // Folded in SQL, with the same expression users_email_lower_uk is built from (V23) —
        // NOT existsByEmail. The fold above and PostgreSQL's lower() are different functions, and
        // this DTO bounds nothing: where they disagree an exact check says "free" while the index
        // says "taken", so an ordinary signup goes through a doomed INSERT — answered 409 only
        // because the catch below translates it, and a 500 the day that catch stops matching.
        // UserRepository states the rule.
        if (userRepository.existsByFoldedEmail(email)) {
            throw new EmailAlreadyUsedException();
        }
        // The password is hashed BEFORE the ceiling below, and that ordering is deliberate and
        // was argued against the review that asked for the opposite. Spending the ceiling first
        // would save a refused caller one bcrypt-12 (~370 ms) — but it would put that bcrypt
        // INSIDE the advisory lock RecipientMailThrottle takes on the recipient key, and that class's
        // standing rule is that nothing slow and nothing caller-controlled may appear between a
        // ceiling being spent and the commit that follows it. The key is an address an
        // unauthenticated caller chooses, so a lock held across a bcrypt is a wait that caller
        // imposes on the inbox's real owner, and ten concurrent requests naming one address would
        // sit on the connection pool until the 3 s lock timeout.
        //
        // THE FACT THAT MAKES THAT NON-OBVIOUS, AND THE ONE A READER GETS WRONG: pg_advisory_xact_lock
        // IS HELD TO COMMIT, NOT TO THE END OF THE METHOD THAT TOOK IT. There is no unlock call to
        // scan for and no scope to read it off, so "the throttle call returns, therefore the lock is
        // gone" is the natural and wrong reading. Everything after the ceiling — the hash, the users
        // INSERT, the transaction's own commit — runs inside it, which is why moving work ABOVE the
        // ceiling is the only way to keep it out.
        //
        // The saving was worth less than it looks in any case. A caller who wants our CPU uses a
        // FRESH inbox each time, which is a fresh bucket and is therefore always ALLOWED — so no
        // ordering of these two lines refuses them, and the bcrypt amplifier is bounded where it
        // always was, by the per-IP budget. What the ceiling closes is the mail bomb, and that is
        // closed either way.
        var passwordHash = passwordEncoder.encode(req.password());
        // HD-202 (review). THE INBOX CEILING ON REGISTRATION, WHICH REGISTER SPENDS ALONE.
        //
        // Register mails a verification link to an address anybody types, and it was left off the
        // recipient throttle on the written ground that "registration can produce at most one
        // message per address — the second attempt is a 409". That is true PER ADDRESS, and the
        // whole premise of MailAddresses.throttleKey is that an address is not the unit of harm:
        // users.email is unique on lower(email), i.e. on the SPELLING (V23), while every ceiling in
        // RecipientMailThrottle counts the INBOX. Those two keys disagree exactly here. With
        // PUBLIC_SIGNUP_ENABLED on, victim+1@gmail.com, victim+2@gmail.com and
        // v.i.ctim@googlemail.com are three distinct users rows, three 201s, three verification
        // mails — and one inbox. Same one-keystroke re-spelling defeat, against the one door that
        // was exempt from the mechanism built to close it.
        //
        // IT REFUSES THE REGISTRATION, NOT THE MAIL, and that is what answers the original
        // objection. Dropping the mail would leave a PENDING row nobody — its owner included —
        // could ever activate. Refusing HERE, above the INSERT, strands nothing at all.
        //
        // AND IT ANSWERS 429, which is available on this endpoint and on no other anonymous mailer
        // here: register is not an anti-enumeration endpoint, since the existsByFoldedEmail check
        // above already answers 409 for a taken address. forgot-password and resend-verification
        // must stay silent, and each door admits exactly the one refusal shape it renders
        // (MailThrottlePolicy.Refusal.RESPONDS_429_WHERE_ENDPOINT_DISCLOSES, which is a distinct
        // constant from the invitation path's RESPONDS_429 because the JUSTIFICATION is distinct:
        // there the caller is already authorized, here the ENDPOINT already discloses). Each door
        // admits exactly one constant, and the call sites of this one are sealed — down to the
        // enclosing method — by AuthMailDoorsTest.
        //
        // BELOW the duplicate pre-check on purpose: a request that is about to be refused with 409
        // sends no mail, so letting it spend a slot would hand an attacker a way to burn a real
        // user's registration budget through an endpoint that never mails.
        //
        // AND IT IS REGISTER'S OWN BUCKET, not the one resend-verification spends (HD-202 review).
        // Sharing them looked right — an attacker refused by one endpoint would otherwise use the
        // other — but the two endpoints do not cost the same to spend. resend-verification records
        // BEFORE the account lookup and unconditionally, because a row written only when the account
        // exists is the existence oracle that endpoint refuses to be; so at an address with no
        // PENDING account it sends nothing, logs nothing above DEBUG, and still fills this ceiling.
        // Five such requests an hour denied signup to every spelling of that inbox, for free,
        // silently, refillable for ever. Separate buckets mean the only way to fill THIS one is to
        // POST here, which costs a real verification mail the victim sees and a PENDING row an
        // administrator can find. The price is on AuthMailThrottleConfig: one inbox can now be sent
        // up to twice the per-window cap of verification mail.
        mailThrottle.requireAndRecordWhereEndpointDiscloses(
                EmailType.REGISTRATION_VERIFICATION, email);
        var user = new User();
        user.setEmail(email);
        user.setDisplayName(req.displayName());
        user.setPasswordHash(passwordHash);
        user.setStatus(UserStatus.PENDING);
        if (req.hasAcceptedTerms()) {
            // recorded whenever the box was ticked, even when not required
            user.setTermsAcceptedAt(Instant.now());
        }
        // saveAndFlush, and the flush is the contract rather than a style choice: a bare save()
        // defers the INSERT to commit, where the violation is raised AFTER this method has
        // returned and the catch below cannot see it — leaving today's 500. Two constraints can
        // fire here and the caller must not be able to tell which, but they fire for ONE reason
        // and it is not the one this comment used to give: the pre-check above and the index ask
        // the SAME PostgreSQL lower() of the same two values, so they cannot disagree about a
        // stored row — a mixed-case squatter is refused by the pre-check, measured, with no INSERT
        // attempted. What they differ by is the WINDOW between them, and a window is a race: two
        // concurrent registrations of one address, losing on users_email_key (a 500 before this
        // ticket) or on users_email_lower_uk (the same race under the new name). Anything else is
        // a real fault and keeps its 500.
        //
        // No lock is added for the race: the window is a single INSERT, there is no transactional
        // side effect to unwrite on this path, and an advisory lock on every signup would be a
        // real cost bought to improve a status code in a race. The verification mail is sent
        // below, after the row exists, so a rolled-back loser mails nothing.
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (!EmailUniqueness.isDuplicateEmail(e)) throw e;
            // THE RESPONSES ARE IDENTICAL, DELIBERATELY — same status, same sentence, no
            // errorType. A caller who could tell the two apart from the BODY would learn whether
            // the occupying row's spelling matches theirs, which is a property of somebody else's
            // account.
            //
            // THE CLOCK IS NOT IDENTICAL, AND THAT IS RECORDED RATHER THAN FIXED — BUT IT NO
            // LONGER RECORDS WHAT IT ONCE DID. The pre-check above returns before
            // passwordEncoder.encode, and bcrypt at strength 12 is ~370 ms on this project's own
            // measurement, so a fast 409 means "the address was already taken when you asked" and
            // a slow one means "you lost a race with another registration of the same address".
            // That residual needs no mixed-case row: with the pre-check folded it exists on a
            // perfectly clean database. And the distinction an earlier draft of this comment
            // recorded — canonical occupant versus non-canonical one — is GONE, because the folded
            // pre-check answers both of them fast; the two are indistinguishable on the clock as
            // well as in the body. What the clock still separates (a fast 409 from a slow 201) is
            // already carried by the status code, which register discloses by construction.
            //
            // The trade stays anyway: hashing before the pre-check to flatten the race would hand
            // every unauthenticated caller a bcrypt-12 per request, which is a worse deal than
            // disclosing that a race happened. login has the same shape for the same reason. If
            // the pre-check is ever removed, this comment goes with it rather than becoming true
            // by accident — a slow 409 would then mean something about somebody else's row again.
            throw new EmailAlreadyUsedException();
        }
        metrics.userRegistered();

        sendVerificationEmail(user);
    }

    @Transactional
    public AuthResponse verifyEmail(String rawToken, HttpServletResponse response) {
        var hash = sha256(rawToken);
        var verification = emailVerificationRepository.findByTokenHash(hash)
                .orElseThrow(InvalidTokenException::new);
        if (verification.isExpired() || verification.isUsed()) {
            throw new InvalidTokenException();
        }
        verification.setVerifiedAt(Instant.now());
        emailVerificationRepository.save(verification);

        var user = verification.getUser();
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        metrics.emailVerified();

        // The one-time token proves email ownership — same trust level as a
        // password reset link — so the user is logged in directly
        return issueTokens(user, response);
    }

    @Transactional
    public AuthResponse login(LoginRequest req, HttpServletResponse response) {
        var email = req.email().toLowerCase(Locale.ROOT);
        // Exponential backoff after consecutive failures — throws 429 while
        // blocked. Applied to unknown emails too, so the limiter itself can't
        // be used to probe which addresses are registered.
        rateLimitService.checkLoginAllowed(email);

        var user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            rateLimitService.recordLoginFailure(email);
            metrics.recordLogin(LoginOutcome.FAILURE, LoginReason.BAD_CREDENTIALS);
            throw new InvalidCredentialsException();
        }
        if (user.getStatus() == UserStatus.PENDING) {
            metrics.recordLogin(LoginOutcome.FAILURE, LoginReason.NOT_VERIFIED);
            throw new EmailNotVerifiedException();
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            metrics.recordLogin(LoginOutcome.FAILURE, LoginReason.DISABLED);
            throw new InvalidCredentialsException();
        }
        rateLimitService.resetLoginFailures(email);
        metrics.recordLogin(LoginOutcome.SUCCESS, LoginReason.OK);
        return issueTokens(user, response);
    }

    @Transactional
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        var rawToken = extractRefreshCookie(request);
        var hash = sha256(rawToken);
        var stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(InvalidTokenException::new);
        if (!stored.isValid()) {
            throw new InvalidTokenException();
        }
        if (stored.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new InvalidTokenException();
        }
        // Rotate: delete old token, issue new pair
        refreshTokenRepository.delete(stored);
        return issueTokens(stored.getUser(), response);
    }

    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            var rawToken = extractRefreshCookie(request);
            var hash = sha256(rawToken);
            refreshTokenRepository.findByTokenHash(hash)
                    .ifPresent(refreshTokenRepository::delete);
        } catch (InvalidTokenException ignored) {
            // no cookie — already logged out
        }
        clearRefreshCookie(response);
    }

    @Transactional
    public void resendVerification(String email) {
        var submitted = email.toLowerCase(Locale.ROOT);
        // HD-202. BEFORE the lookup, and unconditionally — see forgotPassword for the whole
        // argument, which is the same one twice.
        if (!mailThrottle.allowAnonymousSend(EmailType.VERIFICATION, submitted)) {
            return;
        }
        // Silently no-op for unknown or already-verified emails — no enumeration
        userRepository.findByEmail(submitted)
                .filter(user -> user.getStatus() == UserStatus.PENDING)
                .ifPresent(this::sendVerificationEmail);
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest req) {
        var submitted = req.email().toLowerCase(Locale.ROOT);
        // HD-202 — the per-ADDRESS ceiling, and THE POSITION OF THIS LINE IS THE FEATURE.
        //
        // Until this shipped, both anonymous mailers were bounded only by the per-IP window on
        // /api/auth/*, and a budget keyed on where a request came from can always be widened by
        // coming from somewhere else. Since HD-199 that key is the real visitor rather than the
        // reverse proxy, so "somewhere else" is unlimited: a proxy pool aims as many buckets as it
        // likes at one address, and every message goes out from our domain with the victim's own
        // address in To.
        //
        // BEFORE THE LOOKUP, AND SPENT WHETHER OR NOT THE ACCOUNT EXISTS. Moved below the
        // findByEmail - or, worse, inside the ifPresent where the mail is actually sent - it would
        // become the exact oracle this endpoint is built to refuse: throttled would mean
        // "registered" and not-throttled would mean "not registered" — a fix that reads exactly
        // like one while being its opposite. It would also break HD-208's other half: that a
        // known and an unknown address must take indistinguishable TIME here; spent first, both
        // branches pay the same lock, the same count and the same insert.
        //
        // A refusal is SILENT: no mail, and the same sentence the caller would have got anyway
        // (MailThrottlePolicy.Refusal.SILENT says why a 429 is not available to us here).
        if (!mailThrottle.allowAnonymousSend(EmailType.PASSWORD_RESET, submitted)) {
            return;
        }
        // Always return success to prevent email enumeration
        userRepository.findByEmail(submitted).ifPresent(user -> {
            var raw = generateRawToken();
            var reset = new PasswordReset();
            reset.setUser(user);
            reset.setTokenHash(sha256(raw));
            reset.setExpiresAt(Instant.now().plusSeconds(3600)); // 1 hour
            passwordResetRepository.save(reset);
            metrics.passwordReset(PasswordResetPhase.REQUESTED);
            // HD-181 — a link is promised only once the row it resolves to is durable. The send is
            // @Async, but @Async leaves this transaction OPEN behind it: the executor can be
            // holding the message while the transaction can still roll back, and the recipient then
            // has a reset link whose password_resets row never existed. Deferring costs nothing —
            // it is still a hand-off to the executor, made a few microseconds later. The address is
            // read into a local because the lambda must not touch the EntityManager at all — the
            // context is closing and, worse, still claims to be transactional (see AfterCommit).
            //
            // The DESCRIPTION carries the domain only, never the address: it is written verbatim
            // into a shipped log, and that is the rule RecipientMailThrottle already applies to its
            // own send line. Nothing is lost — a user who never gets a reset mail re-requests one.
            var recipient = user.getEmail();
            AfterCommit.run("password-reset email to a " + MailAddresses.domainOf(recipient) + " address",
                    () -> mailService.sendPasswordResetEmail(recipient, raw));
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        rejectPublishedPassword(req.newPassword());
        rejectUnencodablePassword(req.newPassword());
        var hash = sha256(req.token());
        var reset = passwordResetRepository.findByTokenHash(hash)
                .orElseThrow(InvalidTokenException::new);
        if (reset.isExpired() || reset.isUsed()) {
            throw new InvalidTokenException();
        }
        reset.setUsedAt(Instant.now());
        passwordResetRepository.save(reset);

        var user = reset.getUser();
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);

        // Invalidate all existing refresh tokens after password change
        refreshTokenRepository.deleteAllByUser(user);
        metrics.passwordReset(PasswordResetPhase.COMPLETED);
    }

    // --- helpers ---

    /**
     * <strong>The two places a password can enter {@code users} through the running
     * application</strong> — register, and completing a reset — refuse the value this project
     * published in its own {@code .env.prod.example} (HD-200).
     *
     * <p>Both bounded it only with {@code @Size(min = 8, max = 100)} and the literal is 19
     * characters, so an administrator could set their own password to it: the next boot then
     * refuses to start with a message about a template they never edited, and until that
     * restart the instance is administrable by anyone who can read the repository. This is
     * the same predicate the two startup guards use — {@code DataSeeder.isPublishedPassword},
     * over {@code DataSeeder.PUBLISHED_PASSWORDS} — so the three cannot disagree, and it is
     * emphatically NOT a strength check: what justifies the one entry is that we published
     * it, under that variable's own name.
     *
     * <p>Refused before the reset token is marked used, so a caller who hits this can retry
     * on the same link.
     */
    private void rejectPublishedPassword(String password) {
        if (DataSeeder.isPublishedPassword(password)) {
            throw new PublishedPasswordException();
        }
    }

    /**
     * <strong>The same two doors, refusing what the encoder cannot hash</strong> (HD-171 §4.4).
     *
     * <p>{@code passwordEncoder.encode} is BCrypt and throws above
     * {@value com.hamstrack.common.security.PasswordLimits#MAX_PASSWORD_BYTES} UTF-8 bytes, with
     * nothing translating that {@code IllegalArgumentException} — so both doors answered
     * <strong>500</strong> to a password a person could plausibly have chosen, and register is
     * unauthenticated. The two DTOs now carry {@code @Size(max = 72)}, and <strong>that annotation
     * is not enough, which is the whole reason this check exists beside it</strong>: {@code @Size}
     * counts UTF-16 units and BCrypt counts bytes, so 72 characters of Cyrillic is 144 bytes,
     * passes validation, and still cannot be hashed. See {@link PasswordLimits} for the unit.
     *
     * <p>Placed beside {@link #rejectPublishedPassword} and refused in the same shape — a 422
     * {@code AppException} — because both are the same kind of statement: the body is well-formed
     * and the value is one this application will not store. It is <em>not</em> a strength check
     * either.
     *
     * <p>Ordered before the reset token is marked used, exactly as the published-password refusal
     * is, so a caller who trips it can retry on the same link. (Register was never at risk of a
     * partial write — {@code encode} precedes the INSERT — but the reset path is only safe because
     * this runs first.)
     */
    private void rejectUnencodablePassword(String password) {
        if (PasswordLimits.exceedsEncoderLimit(password)) {
            throw new PasswordTooLongException(PasswordLimits.byteLength(password));
        }
    }

    private AuthResponse issueTokens(User user, HttpServletResponse response) {
        var accessToken = jwtService.generateAccessToken(user);
        var rawRefresh = jwtService.generateRawRefreshToken();

        var token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(sha256(rawRefresh));
        token.setExpiresAt(Instant.now().plus(jwtProperties.refreshTokenExpiration()));
        refreshTokenRepository.save(token);

        setRefreshCookie(response, rawRefresh);

        var expiresIn = jwtProperties.accessTokenExpiration().toSeconds();
        return new AuthResponse(accessToken, expiresIn, user.getId(), user.getEmail(), user.getDisplayName());
    }

    private void sendVerificationEmail(User user) {
        var raw = generateRawToken();
        var verification = new EmailVerification();
        verification.setUser(user);
        verification.setTokenHash(sha256(raw));
        verification.setExpiresAt(Instant.now().plusSeconds(86400)); // 24 hours
        emailVerificationRepository.save(verification);
        // HD-181, same reasoning as forgotPassword. Both callers — register and
        // resendVerification — are @Transactional, and being the last statement in register is not
        // protection: the INSERTs are still unflushed here, so a concurrent signup losing the race
        // on the users.email unique index fails at the commit that follows, after the confirmation
        // link has already left for the executor.
        // Domain only in the description, for the reason given in forgotPassword.
        var recipient = user.getEmail();
        AfterCommit.run("verification email to a " + MailAddresses.domainOf(recipient) + " address",
                () -> mailService.sendVerificationEmail(recipient, raw));
    }

    private String extractRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) throw new InvalidTokenException();
        return Arrays.stream(request.getCookies())
                .filter(c -> REFRESH_COOKIE.equals(c.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElseThrow(InvalidTokenException::new);
    }

    private void setRefreshCookie(HttpServletResponse response, String rawToken) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                refreshCookie(rawToken, jwtProperties.refreshTokenExpiration()).toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString());
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                // Secure follows the deployment scheme: Cloud/HTTPS gets it, a self-hosted
                // DC instance on plain HTTP still gets a working refresh flow
                .secure(appProperties.baseUrl().startsWith("https"))
                .path("/api/auth")
                .maxAge(maxAge)
                .sameSite("Strict")
                .build();
    }

    private String generateRawToken() {
        return TokenUtils.generateRawToken();
    }

    private String sha256(String input) {
        return TokenUtils.sha256(input);
    }
}
