package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.mail.MailSendEventRepository;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import com.hamstrack.common.persistence.LockTimeout;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * <strong>The three doors of {@link RecipientMailThrottle}, and the guards that stop a copied call
 * site from picking the wrong one</strong> (HD-202 review).
 *
 * <h2>Why this file exists at all</h2>
 * Two reviewers independently called those guards "the single most load-bearing lines" in the
 * change — and <em>nothing asserted them</em>. That is this project's own stated failure mode:
 * <em>a translation catch that is never entered looks identical to one that works, because both
 * produce no error in the happy path</em>. A guard that never fires is exactly the same shape. It
 * is also cheap to get wrong invisibly: delete either {@code if} and every existing test still
 * passes, because no production call site violates them today. That is precisely the state in which
 * the next call site is added.
 *
 * <h2>What each guard is protecting</h2>
 * The refusal shape is a property of the mail type, declared on {@link MailThrottlePolicy}, because
 * the call site is what gets copied. A {@code requireAndRecord} copied onto
 * {@code forgot-password} would answer {@code 429} where the endpoint's whole contract is that its
 * answer never varies — publishing, to anybody on the internet, that <em>somebody</em> asked for a
 * reset at an address in the last minute. An {@code allowAnonymousSend} copied onto the invite path
 * would silently swallow a refusal its caller is meant to act on.
 *
 * <p>The third door cannot be guarded by the type alone, because what makes it safe is a property
 * of the ENDPOINT and a type cannot see where it is called from — so its protection is a
 * <strong>sealed set of call sites</strong> instead, and that seal is in this file for the same
 * reason the throws are. It is sealed to the enclosing METHOD, not to the file: all three auth
 * flows live in {@code AuthService}, so a file-granular seal would have watched the disclosing call
 * move from {@code register} into {@code forgotPassword} without a word.
 */
class AuthMailDoorsTest {

    /** Never reached: every assertion below throws before any query is issued. */
    private final MailSendEventRepository repository = mock(MailSendEventRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final LockTimeout lockTimeout = mock(LockTimeout.class);
    private final ProductMetrics metrics = mock(ProductMetrics.class);

    // ============================================================ 1. the two throws

    /**
     * <strong>A silent policy may not be spent through the door that answers 429.</strong>
     *
     * <p>The failure this excludes is the only one that matters on this endpoint pair, and it does
     * not look like a bug: {@code requireAndRecord} is the older, more obvious method, it is what
     * the invitation path calls, and a developer adding a ceiling to a new anonymous mailer would
     * reach for it first. Without this throw the result is a working, tested, plausible-looking
     * feature that answers {@code 429} on {@code forgot-password}.
     */
    @Test
    void theVisibleDoorRefusesToServeASilentPolicy() {
        var throttle = throttleWith(policy(EmailType.PASSWORD_RESET,
                MailThrottlePolicy.Refusal.SILENT, null));

        assertThatThrownBy(() -> throttle.requireAndRecord(
                EmailType.PASSWORD_RESET, "victim@example.test", null, null))
                .as("this door throws a 429, and a 429 is how an endpoint whose response must be "
                    + "uniform becomes an oracle. It must refuse the CALL, not the caller.")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowAnonymousSend");

        assertThatThrownBy(() -> throttle.requireAndRecordWhereEndpointDiscloses(
                EmailType.PASSWORD_RESET, "victim@example.test"))
                .as("and so does the third door — a SILENT policy has no endpoint that discloses, "
                    + "so reaching this door with one means somebody mounted it on a uniform "
                    + "endpoint")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESPONDS_429_WHERE_ENDPOINT_DISCLOSES");

        verifyNoInteractions(repository, entityManager, lockTimeout, metrics);
    }

    /**
     * <strong>Each door admits EXACTLY the constant naming it — the leak a "not the opposite one"
     * predicate leaves open</strong> (HD-202 final review).
     *
     * <p>The two guards above used to read {@code refusal != SILENT} and
     * {@code refusal != RESPONDS_429}, which partitions three states into two overlapping
     * admissible sets. The consequence was not theoretical: the type that may answer {@code 429}
     * on a disclosing endpoint was ALSO admissible through the plain {@code requireAndRecord}, and
     * the call-site seal below watches only the disclosing door — so writing
     * {@code requireAndRecord(...)} in {@code resendVerification} would have passed the type guard,
     * passed the seal, and made that endpoint answer {@code 429}, publishing that somebody asked
     * for mail at that inbox in the last minute. That is precisely the leak the three-door design
     * exists to prevent, and only luck of coverage (other tests assert {@code 200} on those
     * endpoints) stood between it and a green build — no protection at all for a NEW uniform
     * endpoint.
     *
     * <p>So the two cross-admissions are asserted here, in both directions, as refusals.
     */
    @Test
    void eachDoorAdmitsExactlyItsOwnRefusalShapeAndNoOther() {
        var disclosing = throttleWith(policy(EmailType.REGISTRATION_VERIFICATION,
                MailThrottlePolicy.Refusal.RESPONDS_429_WHERE_ENDPOINT_DISCLOSES, wording()));

        assertThatThrownBy(() -> disclosing.requireAndRecord(
                EmailType.REGISTRATION_VERIFICATION, "victim@example.test", null, null))
                .as("""
                    THE ANONYMOUS 429 SHAPE IS NOT ADMISSIBLE ON THE AUTHORIZED-CALLER DOOR.

                    requireAndRecord answers 429 on the strength of the CALLER already being
                    authorized; RESPONDS_429_WHERE_ENDPOINT_DISCLOSES answers it on the strength
                    of the ENDPOINT already disclosing. Letting the second through here re-opens
                    the hole this test was written for: the seal below watches the OTHER door, so
                    a requireAndRecord copied onto a uniform-response endpoint would be guarded by
                    nothing at all.""")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESPONDS_429");

        var authorized = throttleWith(policy(EmailType.INVITE,
                MailThrottlePolicy.Refusal.RESPONDS_429, wording()));

        assertThatThrownBy(() -> authorized.requireAndRecordWhereEndpointDiscloses(
                EmailType.INVITE, "victim@example.test"))
                .as("and the mirror: the invitation path's justification is the caller, not the "
                    + "endpoint, so it may not enter the door whose whole warrant is a property of "
                    + "the endpoint")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESPONDS_429_WHERE_ENDPOINT_DISCLOSES");

        assertThatThrownBy(() -> disclosing.allowAnonymousSend(
                EmailType.REGISTRATION_VERIFICATION, "victim@example.test"))
                .as("a type that answers 429 anywhere may not be dropped in silence — a silently "
                    + "refused registration leaves an account nobody can activate")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SILENT");

        verifyNoInteractions(repository, entityManager, lockTimeout, metrics);
    }

    /**
     * <strong>A 429 policy may not be spent through the door that drops mail in silence.</strong>
     *
     * <p>The mirror failure, and the quieter of the two: nothing breaks, no test fails, and an
     * invitation that should have been refused with a wait the sender can act on simply does not
     * arrive. The invite path's whole refusal wording — argued over, held to "a refusal may only
     * prescribe an action its reader can perform" — would go unrendered forever.
     */
    @Test
    void theSilentDoorRefusesToServeAPolicyThatAnswers429() {
        var throttle = throttleWith(policy(EmailType.INVITE,
                MailThrottlePolicy.Refusal.RESPONDS_429, wording()));

        assertThatThrownBy(() -> throttle.allowAnonymousSend(EmailType.INVITE, "x@example.test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requireAndRecord");

        verifyNoInteractions(repository, entityManager, lockTimeout, metrics);
    }

    /**
     * <strong>Every refusal shape opens exactly one door, and the mapping is total.</strong>
     *
     * <p>Asserted over {@code Refusal.values()} rather than over today's three constants, because
     * the failure this file exists for is a FOURTH state arriving: a constant nobody wires to a
     * door is a policy that cannot be spent anywhere (three throws, no ceiling), and a constant
     * wired to two is the overlap that
     * {@link #eachDoorAdmitsExactlyItsOwnRefusalShapeAndNoOther()} was written after.
     */
    @Test
    void everyRefusalShapeOpensExactlyOneDoor() {
        for (var refusal : MailThrottlePolicy.Refusal.values()) {
            var policy = policy(EmailType.VERIFICATION, refusal,
                    refusal == MailThrottlePolicy.Refusal.SILENT ? null : wording());

            var doors = List.of(policy.mayRefuseSilently(), policy.mayRefuseWithStatus(),
                            policy.mayRefuseWhereEndpointDiscloses()).stream()
                    .filter(Boolean::booleanValue).count();

            assertThat(doors)
                    .as("""
                        %s opens %d of the three doors of RecipientMailThrottle, and it must \
                        open exactly one.

                        ZERO means a policy carrying this shape can be spent nowhere: every \
                        door throws, and the ceiling silently does not exist. MORE THAN ONE \
                        means two doors accept it, which is the shape that let a type entitled \
                        to the anonymous 429 through requireAndRecord as well - and only ONE \
                        door has its call sites sealed, so the other is where a copied line \
                        would land.

                        Wire the new shape to its door with an exact == predicate on \
                        MailThrottlePolicy, and add the door itself if it needs one.""",
                        refusal, doors)
                    .isEqualTo(1L);
        }
    }

    /**
     * <strong>A policy that can answer 429 on any door must carry a sentence, and a fully silent
     * one must not.</strong> The second half is the one with teeth: a sentence nobody can read is a
     * claim nobody re-reads, and this project has shipped an unperformable refusal three times.
     */
    @Test
    void theWordingRequirementFollowsTheRefusalShape() {
        assertThatThrownBy(() -> policy(EmailType.REGISTRATION_VERIFICATION,
                MailThrottlePolicy.Refusal.RESPONDS_429_WHERE_ENDPOINT_DISCLOSES, null))
                .as("the disclosing door renders this wording; without one, a refused registration "
                    + "would be a 429 with nothing in it")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no wording");

        assertThatThrownBy(() -> policy(EmailType.PASSWORD_RESET,
                MailThrottlePolicy.Refusal.SILENT, wording()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never rendered");
    }

    // ============================================================ 2. the sealed call sites

    /**
     * <strong>The seal that replaces the type invariant the third door cannot have.</strong>
     *
     * <p>{@code requireAndRecordWhereEndpointDiscloses} answers {@code 429} for a type that is
     * {@code SILENT} on its other endpoint, so no check inside {@link RecipientMailThrottle} can
     * tell a legitimate call site from a copied one — the policy says both shapes are allowed.
     * What makes the guarantee real is that the SET of call sites is small, named, and asserted
     * here: mounting this door on an endpoint whose response must be uniform is exactly the leak
     * the other two doors exist to prevent, and it would be a two-line change nobody would notice.
     *
     * <p>Read out of the source tree rather than by reflection, because a call site is a fact about
     * code and not about bytecode this test can enumerate. The failure message is the checklist.
     */
    @Test
    void onlyRegisterUsesTheDoorThatAnswers429OnAnOtherwiseSilentType() throws IOException {
        var callers = enclosingMethodsCalling("requireAndRecordWhereEndpointDiscloses(");

        assertThat(callers)
                .as("""
                    A NEW CALL SITE FOR THE DISCLOSING DOOR.

                    RecipientMailThrottle.requireAndRecordWhereEndpointDiscloses answers 429 for a \
                    mail type whose OTHER endpoint refuses in silence. No guard inside that class \
                    can tell your call site from a mistake, because the policy declares both \
                    shapes as legitimate — this list is the guard. Before adding one, answer:

                      DOES THE ENDPOINT ALREADY DISCLOSE WHAT THE REFUSAL WOULD? POST /api/auth/\
                    register does: it answers 409 for a taken address, so address existence is \
                    published by construction and a 429 adds nothing. An endpoint whose answer is \
                    UNIFORM by design (forgot-password, resend-verification) does not, and \
                    mounting this door there publishes, to anybody on the internet, that somebody \
                    asked for mail at an address in the last minute. Use allowAnonymousSend there \
                    and drop the mail.

                      IS REFUSING THE REQUEST BETTER THAN DROPPING THE MAIL? On register it is, \
                    because a dropped verification mail leaves an account nobody — its owner \
                    included — can ever activate, and refusing above the users INSERT strands \
                    nothing at all. If your endpoint has already written something by the time it \
                    calls this, that argument does not carry over.

                    If both answers are yes, add the METHOD here in the same commit. If either is \
                    no, this is the wrong door.""")
                .containsExactly("AuthService.register");
    }

    // ------------------------------------------------------------------ helpers

    private RecipientMailThrottle throttleWith(MailThrottlePolicy policy) {
        return new RecipientMailThrottle(List.of(policy), repository, entityManager, lockTimeout,
                mock(RateLimitProperties.class), metrics);
    }

    private static MailThrottlePolicy policy(EmailType type, MailThrottlePolicy.Refusal refusal,
                                             MailThrottleWording wording) {
        return new MailThrottlePolicy(type, Duration.ofMinutes(1), Duration.ofMinutes(15), 5,
                RateLimitKind.PASSWORD_RESET_RECIPIENT_COOLDOWN,
                RateLimitKind.PASSWORD_RESET_RECIPIENT_WINDOW, refusal, wording);
    }

    private static MailThrottleWording wording() {
        return new MailThrottleWording() {
            @Override
            public String cooldown(String recipient, String wait, String addendum) {
                return "wait " + wait;
            }

            @Override
            public String recipientVolume(String wait) {
                return "wait " + wait;
            }
        };
    }

    /**
     * {@code Class.method} for every place under {@code src/main/java} that CALLS {@code needle},
     * excluding the declaring class itself.
     *
     * <p><strong>Method granularity, not file.</strong> The seal used to compare file names, and
     * all three anonymous auth flows are methods of one file — so moving the disclosing call out of
     * {@code register} and into {@code forgotPassword}, which is the exact leak this seal exists to
     * refuse, produced the identical list and passed. A file is not the unit at which this decision
     * is made; an endpoint is, and the method is the closest thing to an endpoint that is visible
     * in source text.
     *
     * <p>Read out of the source tree rather than by reflection for the reason the test above gives:
     * a call site is a fact about code, not about bytecode this test can enumerate. The parser is
     * deliberately crude — the last line before the call that looks like a method declaration —
     * because it only has to be right about the shape this codebase actually writes, and it is
     * wrapped in an assertion that fails loudly if it finds nothing at all.
     */
    private static List<String> enclosingMethodsCalling(String needle) throws IOException {
        // Exactly four spaces of indentation, which in this codebase is method level: a control
        // statement is nested deeper, and without that anchor "if (...)" parsed as a declaration
        // named "if" and the seal reported AuthService.if. The keyword guard below is the belt to
        // that brace - a method may legitimately sit at four spaces inside a nested class.
        var declaration = Pattern.compile(
                "^ {4}(?:public|protected|private)?\\s*(?:static\\s+)?"
                + "(?:final\\s+)?[\\w.<>\\[\\],?\\s]+\\s(\\w+)\\s*\\([^;]*$");
        var notAMethodName = Set.of("if", "for", "while", "switch", "catch", "return", "new",
                "synchronized", "assert", "throw", "else", "do", "try");
        var hits = new ArrayList<String>();
        try (Stream<Path> paths = Files.walk(Path.of("src", "main", "java"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                var file = path.getFileName().toString();
                if (file.equals("RecipientMailThrottle.java")) {
                    continue;  // the declaring class; its own javadoc and guards mention the name
                }
                var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                var type = file.substring(0, file.length() - ".java".length());
                var enclosing = "<file scope>";
                for (var line : lines) {
                    var matcher = declaration.matcher(line);
                    if (matcher.find() && !line.trim().startsWith("*")
                        && !notAMethodName.contains(matcher.group(1))) {
                        enclosing = matcher.group(1);
                    }
                    // A javadoc/comment mention is not a call site. Anything else that carries the
                    // name is treated as one - over-reporting here fails the seal, which is the
                    // safe direction.
                    var code = line.trim();
                    if (code.contains(needle) && !code.startsWith("*") && !code.startsWith("//")) {
                        hits.add(type + "." + enclosing);
                    }
                }
            }
        }
        assertThat(hits)
                .as("no source file calls %s at all, so this seal is guarding an empty set — "
                    + "the method was renamed and this test did not move with it", needle)
                .isNotEmpty();
        return hits;
    }
}
