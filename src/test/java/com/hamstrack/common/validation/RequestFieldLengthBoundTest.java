package com.hamstrack.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.admin.dto.UpsertFieldRequest;
import com.hamstrack.admin.service.AdminFieldService;
import com.hamstrack.admin.service.AdminUserService;
import com.hamstrack.admin.service.ScopedProjectAdminService;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.service.AuthService;
import com.hamstrack.auth.service.EmailUniqueness;
import com.hamstrack.common.mail.MailAddresses;
import com.hamstrack.common.mail.MailService;
import com.hamstrack.common.mail.MailTask;
import com.hamstrack.common.mail.UndeliverableMail;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.ratelimit.PrincipalThrottleInterceptor;
import com.hamstrack.common.ratelimit.RateLimitService;
import com.hamstrack.common.ratelimit.RecipientMailThrottle;
import com.hamstrack.common.security.ContentSecurityPolicy;
import com.hamstrack.common.seed.DataSeeder;
import com.hamstrack.issue.dto.LabelMatch;
import com.hamstrack.issue.service.CommentService;
import com.hamstrack.project.dto.CreateProjectRequest;
import com.hamstrack.project.service.ProjectService;
import com.hamstrack.report.dto.InsightsDimension;
import com.hamstrack.search.FieldRegistry;
import com.hamstrack.search.HqlCompiler;
import com.hamstrack.search.ResolutionContext;
import com.hamstrack.search.RetiredFieldAliases;
import com.hamstrack.search.parser.Lexer;
import com.hamstrack.workspace.service.RoleService;
import com.hamstrack.workspace.service.WorkspaceService;
import com.hamstrack.common.exception.GlobalExceptionHandler;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.common.testsupport.Doors;
import com.hamstrack.common.testsupport.Population;
import com.hamstrack.common.testsupport.ProductionBytecode;
import com.hamstrack.issue.service.ClassificationNames;
import com.hamstrack.issue.service.ComponentService;
import com.hamstrack.issue.service.LabelService;
import com.hamstrack.issue.service.SprintService;
import com.hamstrack.issue.service.VersionService;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.report.service.InsightsService;
import com.hamstrack.search.CustomFieldMeta;
import com.hamstrack.search.HqlParentResolver;
import com.hamstrack.search.HqlValueResolver;
import com.hamstrack.search.ResolutionContextFactory;
import com.hamstrack.search.SearchNames;
import com.hamstrack.search.SearchService;
import com.hamstrack.search.filter.service.SavedFilterService;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-171 §5.3 / AC 2 — the category harness: no request path may write an over-length value
 * into a column and answer 500.</strong>
 *
 * <p>The dispatching brief asked whether a category test over all DTO→column pairs generalises. It
 * does not as a <em>static</em> scan and it does as a <em>behavioural</em> one, and the reason is the
 * most important sentence in this ticket: <strong>both 500-class defects the sweep found had no
 * annotated field at all.</strong> {@code workspaces.slug} is derived from a bounded name;
 * {@code issue_history.field} is copied out of another column. A perfect DTO→column scanner would
 * have scored a clean pass over both.
 *
 * <h2>What is asserted</h2>
 * <strong>A status class, not a value.</strong> Every row must answer below 500; a row whose payload
 * is over every bound must additionally answer 4xx. It deliberately does not assert <em>which</em>
 * 4xx — a service answering 422 after normalisation and a DTO answering 400 at the edge are both
 * correct, and §3.3(c) makes silent truncation a legitimate mechanism too, which is why the
 * derived-value rows below accept a 2xx.
 *
 * <p><strong>The derived-value rows are the point.</strong> A 40 000-character workspace name is
 * refused by {@code @Size(max = 255)} at the edge and never reaches {@code generateSlug} — so the row
 * that would have caught the slug bug submits a <strong>101-character name</strong>: valid input,
 * invalid slug. Rows of that shape are written per §3.2 finding, not by filling everything with X.
 *
 * <p><strong>A canonicalised name is a derived value too (HD-297).</strong> Every door that stores a
 * display name runs it through {@code ClassificationNames.normalize} first, and NFC is not
 * length-preserving: a composition-exclusion character decomposes and is never recomposed, so
 * 120 × U+0958 passes {@code @Size(max = 120)} and canonicalises to 240 characters. The
 * {@code [nfc]} rows send exactly the door's limit in such characters — <em>within</em> the bound raw,
 * over it canonical — so only a bound measured <em>after</em> canonicalisation can refuse them; and
 * because the 22001 backstop in {@code GlobalExceptionHandler} also answers 400 (while logging at
 * ERROR), those rows additionally require that the refusal <strong>names the limit</strong> and does
 * <strong>not</strong> carry the backstop's {@code errorType}. The saved-filter door answered the
 * backstop's 400 to such a name until this row existed. The members are enumerated from bytecode, not
 * typed: {@link #everyDoorThatCanonicalisesANameMeasuresItsBoundAfterCanonicalisation()}.
 *
 * <p><strong>A CASE FOLD is a derived value too (HD-306), and it is the same statement about a second
 * transform.</strong> {@code String.toLowerCase(Locale.ROOT)} lengthens U+0130 into two code points, so
 * 64 of them plus a 190-character domain is 255 characters with zero constraint violations and 319
 * characters once stored. The {@code [fold]} rows carry exactly that, and the judge is
 * <em>shared</em> with the {@code [nfc]} rows rather than copied ({@link #judgeDerivedValueRow}) — the
 * three verdicts are identical and only the transform's name differs. The members are enumerated from
 * bytecode as well: {@link #everyDoorThatCaseFoldsAValueItStoresMeasuresItsBoundAfterTheFold()}. Which
 * mechanism is correct is decided by what the value IS rather than by which door writes it: an identity
 * is REFUSED, a derived key or forensic copy is TRUNCATED at the site that produces it.
 *
 * <h2>The two tripwires, because every assertion here is "nothing offends"</h2>
 * <ol>
 *   <li>{@link #MIN_ROWS} — a row that stops running is a door with no guarantee while the suite
 *       stays green.</li>
 *   <li><strong>the category claim</strong>: every write handler the runtime routes —
 *       {@code Doors.writeHandlers()}, POST/PUT/PATCH/DELETE, held equal to the handler mapping by
 *       {@code DoorsHandlerMappingParityTest} — reduced to those that actually accept free text
 *       (decided by reflection over the handler's {@code @RequestBody} type and
 *       {@code @RequestParam String}s, never by a list), asserting each is covered by a row, by a row
 *       on the <em>same request DTO</em>, or by a declared exclusion. That is what makes a new write
 *       endpoint a deliberate edit rather than an omission.</li>
 * </ol>
 *
 * <p><strong>Coverage is by DTO, and that is a decision rather than a shortcut.</strong> A length
 * bound lives on the request record, so a second mount of the same record — the same
 * {@code UpsertStatusRequest} answering at {@code /api/admin}, {@code /api/workspaces/{ws}/admin} and
 * {@code …/projects/{p}/admin} — differs only in scope resolution, which is a tenancy question owned
 * by other tests. Twenty-five endpoints per delegated mount are covered by one row each and the
 * claim stays true of all of them.
 *
 * <h2>The limit, so the boundary is not mistaken for a guarantee</h2>
 * This proves a status class, not a bound. An endpoint that truncates rather than refuses passes,
 * correctly. It says nothing about columns reached by any route other than an HTTP write, and
 * multipart bodies are out of scope for v1 (§14.3) — the only text a multipart request contributes
 * to a column is the filename, which is bounded by truncation.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "app.legal.terms-acceptance-required=false",
        "app.registration.public-signup-enabled=true",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class RequestFieldLengthBoundTest {

    /** Long enough that nothing plausibly bounds it, short enough to keep the suite quick. */
    private static final String LONG = "x".repeat(40_000);

    /** Valid input whose <em>derived</em> value overflows: 101 slug characters into a VARCHAR(100). */
    private static final String NAME_101 = "n".repeat(101);

    /**
     * U+0958 DEVANAGARI LETTER QA — on the Unicode composition-exclusion list, so NFC decomposes it to
     * U+0915 U+093C and never recomposes it: every one of these becomes two characters after
     * {@code ClassificationNames.normalize}. {@code limit} of them are within a {@code @Size(max = limit)}
     * and {@code 2 × limit} once canonical (measured: 120 → 240; U+FB2C → 360, U+2ADC → 240).
     */
    private static final String GROWS_UNDER_NFC = "क़";

    /**
     * The 22001 backstop's signature ({@code GlobalExceptionHandler.VALUE_TOO_LONG_ERROR_TYPE}): a 400
     * carrying it means the value REACHED THE COLUMN and the database refused it — an ERROR line in
     * the log and a bound that does not exist. A row that expects a post-canonicalisation bound
     * fails on it, however clean the status looks.
     */
    private static final String BACKSTOP_ERROR_TYPE = "VALUE_TOO_LONG";

    /**
     * U+0130 LATIN CAPITAL LETTER I WITH DOT ABOVE — <strong>the one unconditional LOWERCASE mapping
     * in the Unicode character database that lengthens</strong> ({@code SpecialCasing.txt}:
     * {@code 0130; 0069 0307}), so each of these becomes two characters under
     * {@code toLowerCase(Locale.ROOT)}. Roughly twenty UPPERCASE mappings lengthen as well
     * (U+00DF → {@code SS}, the U+FB00 ligature block, several Greek diacritic forms), which is why a
     * {@code toUpperCase} door is a member of the fold category even where a pattern keeps it safe.
     */
    private static final String GROWS_UNDER_FOLD = "İ";

    /** 190 ASCII characters in labels of at most 63, so the domain is inside every limit {@code @Email} has. */
    private static final String ASCII_DOMAIN_190 =
            "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(62);

    /**
     * <strong>255 characters raw, 319 folded, ZERO constraint violations</strong> — measured
     * 2026-09-13 against Hibernate Validator 9.1.0 on JDK 21, through {@code RegisterRequest} and
     * {@code CreateUserRequest} (both {@code @Email @NotBlank @Size(max = 255)} with no pattern).
     * Inside the raw bound, over {@code users.email VARCHAR(255)} once stored.
     */
    private static final String FOLDS_TO_319 = GROWS_UNDER_FOLD.repeat(64) + "@" + ASCII_DOMAIN_190;

    /**
     * <strong>255 characters raw, 324 folded, zero violations</strong> — the fixture for the column no
     * door bound protects (HD-306 fix loop). {@code mail_send_events.recipient_email VARCHAR(320)} is
     * written from the address the SERVICE folded, and on the two anonymous doors nothing measures that
     * fold: {@link #FOLDS_TO_319} is not wide enough to reach 320, so the growth is spent in the local
     * part AND in five characters of the domain. Measured 2026-09-13 through
     * {@code ForgotPasswordRequest} / {@code ResendVerificationRequest}; the punycode form of this
     * domain is 202 characters, inside {@code @Email}'s 255.
     */
    private static final String FOLDS_TO_324 = GROWS_UNDER_FOLD.repeat(64) + "@"
            + GROWS_UNDER_FOLD.repeat(5) + "." + "z".repeat(60) + "." + "z".repeat(60) + "."
            + "z".repeat(60) + "." + "z";

    /**
     * The invite door's fixture, and the reason it needs a different one: {@code InviteMemberRequest}
     * carries {@code @Pattern("\\p{ASCII}*@[^@]*")}, so the growth has to come from the DOMAIN — where
     * exactly <strong>one</strong> U+0130 is enough. 60 ASCII characters, {@code "@"} and a
     * 194-character domain give 255 raw, <strong>256</strong> folded, a punycode domain of 202 (inside
     * {@code @Email}'s 255) and zero violations. Measured 2026-09-13: the pattern narrows this door's
     * exposure to a single character and does not close it.
     */
    private static final String ASCII_LOCAL_FOLDS_TO_256 = "a".repeat(60) + "@"
            + "d".repeat(40) + GROWS_UNDER_FOLD + "." + "e".repeat(63) + "." + "f".repeat(63) + "."
            + "g".repeat(24);

    /**
     * Production classes that call the canonicalisation family ({@code ClassificationNames},
     * {@code SearchNames}, {@code java.text.Normalizer}) on 2026-09-09: 13 — the two helpers, six
     * read-side users and the five writing doors. Under 10 the bytecode walk is not seeing the callers.
     */
    private static final int CANONICALISER_CALLER_FLOOR = 10;

    /** Writing doors among those callers on 2026-09-09: 5 (label, component, version, sprint, saved filter). */
    private static final int CANONICALISING_DOOR_FLOOR = 4;

    /**
     * The canonicalisation helpers: classes whose whole job is to normalise on behalf of a caller.
     * <strong>One constant, two uses, on purpose</strong> — it is what {@link ProductionBytecode#callersOf}
     * walks <em>through</em> (a caller of a helper is a member exactly as a caller of
     * {@code ClassificationNames} is) and what the claim then excludes as "the helper itself". A future
     * {@code XNames.clean()} that merely delegates to {@code normalize} is therefore never a hiding
     * place: left out of this set it is a writing member with no row; put into it, the walk continues to
     * whatever calls it. Real class references, so a rename fails to compile rather than leaving a
     * stale name behind.
     */
    private static final Set<String> CANONICALISATION_HELPERS = Set.of(
            ClassificationNames.class.getName(),
            SearchNames.class.getName());

    /**
     * Callers of the canonicalisation family that STORE nothing — each canonicalises an HQL operand, a
     * typeahead prefix or a lookup key and compares it. {@link Population#excluding} refuses an entry
     * that is no longer a caller, so a reader that stops canonicalising is removed from here in the
     * same change.
     */
    private static final Set<String> READ_SIDE_CANONICALISERS = Set.of(
            HqlParentResolver.class.getName(),
            HqlValueResolver.class.getName(),
            CustomFieldMeta.class.getName(),
            ResolutionContextFactory.class.getName(),
            SearchService.class.getName(),
            InsightsService.class.getName());

    // ------------------------------------------------------------------ the fold category (HD-306)

    /**
     * <strong>The unit of the fold claim is a SITE — {@code Owner#method} — and not a class</strong>
     * (HD-306 fix loop, and the reason that loop happened).
     *
     * <p>Round 1 keyed this category on class names, and {@code AuthService} folds and stores an address
     * in <em>three</em> methods: {@code register}, {@code resendVerification} and
     * {@code forgotPassword}. One {@code [fold]} row about {@code register} therefore satisfied the
     * whole class, and the two unauthenticated doors — whose stored value overflows a column nothing
     * else bounds — were inside the population, inside the floor, and covered by nothing, while the
     * seal printed green. {@code WorkspaceService} (invite + {@code generateSlug}) and
     * {@code AdminUserService} have the same shape. So every set below names sites, {@code excluding}
     * holds each named site to being live, and a second fold inside a class that already has a row is a
     * new member rather than a free ride.
     */
    private static String foldSite(Class<?> owner, String method) {
        return ProductionBytecode.site(owner.getName(), method);
    }

    /**
     * <strong>The fold helpers</strong>, used twice for {@link #CANONICALISATION_HELPERS}' reason: what
     * {@link ProductionBytecode#callSitesOfMethods} walks <em>through</em>, and (through
     * {@link #FOLD_HELPER_SITES}) what the claim then excludes as "the helper itself".
     * {@code MailAddresses} owns the address fold and the throttle key; {@code SearchNames} is here
     * because a caller that reaches a fold only through it must still be a member — a delegator declared
     * a helper is what makes the graph continue, never a hiding place.
     */
    private static final Set<String> FOLD_HELPERS = Set.of(
            MailAddresses.class.getName(),
            SearchNames.class.getName());

    /**
     * The helpers' own fold sites. Site-granular like every other exclusion here, so a helper that grows
     * a method which folds <em>and stores</em> is a member rather than something the word "helper"
     * covers.
     */
    private static final Set<String> FOLD_HELPER_SITES = Set.of(
            foldSite(MailAddresses.class, "storageFold"),
            foldSite(MailAddresses.class, "exceedsStorableLength"),
            foldSite(MailAddresses.class, "requireStorableAddress"),
            foldSite(MailAddresses.class, "fitStoredRecipient"),
            foldSite(MailAddresses.class, "throttleKey"),
            foldSite(MailAddresses.class, "foldedThrottleKey"),
            foldSite(MailAddresses.class, "asciiDomain"),
            foldSite(SearchNames.class, "key"));

    /**
     * Callers of the fold family that STORE nothing folded — each compares, looks up, sorts, counts or
     * prints the folded value, so there is no column to overflow and the raw {@code @Size} already caps
     * the key space ({@code EmailLengthBoundTest}'s settled "bound the field, not the itinerary"). Read
     * off the code on 2026-09-13: a duplicate-name lookup key ({@code LabelService},
     * {@code ComponentService}, {@code VersionService}), a typeahead prefix ({@code SearchService},
     * {@code FieldRegistry}, {@code RetiredFieldAliases}, {@code ResolutionContext(Factory)},
     * {@code CustomFieldMeta}), an HQL operand or keyword ({@code HqlCompiler},
     * {@code HqlValueResolver}, {@code HqlParentResolver}, {@code Lexer}), an enum token
     * ({@code InsightsService}, {@code InsightsDimension}, {@code LabelMatch}), a mention scan
     * ({@code CommentService}), a metric or header tag ({@code ProductMetrics.CspDirective},
     * {@code ContentSecurityPolicy}, {@code PrincipalThrottleInterceptor}), a login-backoff key
     * ({@code RateLimitService}), a constraint name ({@code EmailUniqueness}), a refusal's noun
     * ({@code ScopedProjectAdminService}), and <strong>every mail site that reaches the family only
     * for {@code MailAddresses.domainOf} in a log or WARN line</strong> — a property rather than a
     * count, because the last count here went stale in the change that wrote it: the fix loop moved
     * two {@code UndeliverableMail} sites out of this set and one {@code MailService} site, and the
     * sentence still said "the three mail classes ({@code MailService}, {@code MailTask},
     * {@code UndeliverableMail})" while {@code UndeliverableMail} had zero entries and
     * {@code MailService} had one of its two. {@code excluding} checks the SET and never the prose,
     * so the prose is now phrased over the shape. {@link Population#excluding} refuses an entry that
     * is no longer a caller, so a site that stops folding leaves here in the same change.
     */
    private static final Set<String> READ_SIDE_FOLDERS = Set.of(
            foldSite(ScopedProjectAdminService.class, "requireBindable"),
            foldSite(AuthService.class, "login"),
            foldSite(AuthService.class, "sendVerificationEmail"),
            foldSite(WorkspaceService.class, "revokeInvite"),
            foldSite(EmailUniqueness.class, "isDuplicateEmail"),
            foldSite(MailService.class, "sendWithDurability"),
            foldSite(MailTask.class, "toString"),
            foldSite(UndeliverableMail.class, "record"),
            foldSite(UndeliverableMail.class, "recordAll"),
            foldSite(ProductMetrics.CspDirective.class, "of"),
            foldSite(PrincipalThrottleInterceptor.class, "appliesTo"),
            foldSite(RateLimitService.class, "key"),
            foldSite(RecipientMailThrottle.class, "storedAddressTruncated"),
            foldSite(ContentSecurityPolicy.class, "aimedAtThisInstance"),
            foldSite(ContentSecurityPolicy.class, "hostOf"),
            foldSite(ContentSecurityPolicy.class, "requireThisInstanceToServeEveryReportUriAimedAtIt"),
            foldSite(LabelMatch.class, "parse"),
            foldSite(CommentService.class, "parseMentions"),
            foldSite(ComponentService.class, "suggestNames"),
            foldSite(LabelService.class, "colorForName"),
            foldSite(LabelService.class, "suggestNames"),
            foldSite(LabelService.class, "toRefs"),
            foldSite(VersionService.class, "suggestNames"),
            foldSite(VersionService.class, "toRefs"),
            foldSite(InsightsDimension.class, "parse"),
            foldSite(InsightsService.class, "fragment"),
            foldSite(InsightsService.class, "measure"),
            foldSite(CustomFieldMeta.class, "resolveOption"),
            foldSite(FieldRegistry.class, "find"),
            foldSite(FieldRegistry.class, "register"),
            foldSite(FieldRegistry.class, "suggest"),
            foldSite(HqlCompiler.class, "textMatch"),
            foldSite(HqlCompiler.class, "valuePredOn"),
            foldSite(HqlParentResolver.class, "resolve"),
            foldSite(HqlValueResolver.class, "keyHint"),
            foldSite(HqlValueResolver.class, "requireName"),
            foldSite(HqlValueResolver.class, "resolveDate"),
            foldSite(HqlValueResolver.class, "resolveEnum"),
            foldSite(HqlValueResolver.class, "resolveLabel"),
            foldSite(HqlValueResolver.class, "resolveNumber"),
            foldSite(HqlValueResolver.class, "resolvePriorityPosition"),
            foldSite(HqlValueResolver.class, "resolveProject"),
            foldSite(HqlValueResolver.class, "resolveVersion"),
            foldSite(ResolutionContext.class, "customField"),
            foldSite(ResolutionContextFactory.class, "addCustomField"),
            foldSite(ResolutionContextFactory.class, "addId"),
            foldSite(RetiredFieldAliases.class, "canonicalName"),
            foldSite(SearchService.class, "members"),
            foldSite(SearchService.class, "projects"),
            foldSite(Lexer.class, "identifierOrKeyword"));

    /**
     * Doors that DERIVE a slug or a key from a folded name and truncate it <em>inside</em> the target
     * width at the derivation site, with the collision suffix carved out of the width rather than added
     * on top (HD-171). {@code RoleService.generateKey} → {@code roles.key VARCHAR(40)};
     * {@code AdminFieldService.slugify} → {@code field_defs.key VARCHAR(50)}, substituting to
     * {@code [a-z0-9_]} first; {@code WorkspaceService.generateSlug} → {@code workspaces.slug
     * VARCHAR(100)} (HD-171's original member). A refusal would be wrong for all three: the value is a
     * machine key the caller never typed, so there is nothing for the caller to shorten.
     */
    private static final Set<String> TRUNCATING_DERIVERS = Set.of(
            foldSite(RoleService.class, "generateKey"),
            foldSite(AdminFieldService.class, "slugify"),
            foldSite(WorkspaceService.class, "generateSlug"));

    /**
     * <strong>Sites that store a folded address they did not derive from a name, TRUNCATED at the site
     * that writes it — or that ARE that cut</strong> — the shape that is right when the stored value is
     * a derived key or a forensic copy rather than an identity, and whose members round 1 of HD-306 got
     * wrong by naming a CLASS: {@code RecipientMailThrottle} stores two such values a line apart, the
     * prose here excused it for one of them, and the other could still take a {@code 22001} out of its
     * INSERT. {@code MailService.fitStoredRecipient} is a member for the second reason — it folds
     * nothing and stores nothing, it is the shared counted cut two writers reach through, and it is
     * inside this population because calling {@code MailAddresses} at all puts a site there.
     *
     * <p>Three columns, <strong>one cut and one counter</strong>:
     * {@code mail_send_events.recipient_key}, {@code .recipient_email} and
     * {@code failed_email.recipient} — all three through {@code MailAddresses.fitStoredMailValue},
     * all three counted on {@code hamstrack.mail.stored_address_truncated{column}}. Truncation rather
     * than refusal because a {@code 22001} <em>inside</em> the throttle rolls back the ceiling row the
     * caller has already been counted on, and because none of the three is matched on, counted or
     * mailed to.
     *
     * <p><strong>The third column joined that mechanism in the fix loop, because this reason claimed
     * it already had.</strong> The words "and counted" were true of the two in
     * {@code RecipientMailThrottle} and false of {@code failed_email.recipient}, whose cut was
     * {@code MailService.truncate} — a bare {@code substring}, no counter, no log line, and not
     * surrogate-safe. Two reviewers found it independently. Routing it through the shared cut
     * ({@code MailService.fitStoredRecipient}, which adds that column's witness) was chosen over
     * weakening the sentence: a category sentence that implies a parity the code lacks is this
     * project's own named failure mode, and one mechanism for one category is cheaper than
     * documenting an asymmetry for ever.
     *
     * <p><strong>This exclusion is not prose</strong> ({@link #everyTruncatingStoreReallyTruncates()}):
     * every SITE named here must call the cut <em>from its own body</em> in the bytecode, so deleting
     * the truncation reds the build instead of quietly leaving a written excuse behind.
     */
    private static final Set<String> TRUNCATED_STORED_ADDRESS = Set.of(
            foldSite(RecipientMailThrottle.class, "spend"),
            foldSite(RecipientMailThrottle.class, "record"),
            foldSite(MailService.class, "deadLetter"),
            foldSite(MailService.class, "fitStoredRecipient"));

    /**
     * <strong>The two doors that must NOT refuse</strong>: {@code POST /api/auth/forgot-password} and
     * {@code /api/auth/resend-verification} fold the submitted address and hand it to
     * {@code RecipientMailThrottle}, which is where it is stored and where it is bounded
     * ({@link #TRUNCATED_STORED_ADDRESS}). A 400 here would be byte-derived and so not an oracle, but it
     * would change the shape of an endpoint whose entire contract is one uniform response — and the
     * address on this path is not an identity. So the guarantee for these two is behavioural and lives
     * at the write site instead: {@link #theAnonymousUniformDoorRecordsAnOverLongFoldedAddressWithoutChangingItsAnswer()}
     * proves the answer does not change and the row survives, and
     * {@code MailAddressesThrottleKeyTest#everyProductionWriteOfTheForensicAddressGoesThroughTheFit}
     * proves the write goes through the fit.
     *
     * <p>Live-checked like the set above, and per SITE: each of these two METHODS must still call a
     * throttle door in the bytecode. A class-level version of that check would stay green if
     * {@code forgotPassword} stopped calling the throttle while {@code resendVerification} kept doing
     * so — which is the defect this whole loop is about, one set over.
     */
    private static final Set<String> DELEGATES_TO_THE_THROTTLE = Set.of(
            foldSite(AuthService.class, "forgotPassword"),
            foldSite(AuthService.class, "resendVerification"));

    /**
     * Not request-reachable: {@code seed.admin.email} is a {@code @Value} field with no DTO to annotate,
     * so it refuses at BOOT instead — {@code DataSeeder.rejectOverLongEmail}, held by
     * {@code SeedGuardStartupOrderingTest}. A startup crash and a request refusal are different failure
     * shapes and the operator gets the one they can act on. Every site in that class is here: the guards
     * fold the configured address to NAME an account in a refusal, and {@code run} is the one that
     * stores it — behind the guard that runs at {@code @PostConstruct}, i.e. before it.
     */
    private static final Set<String> SEED_GUARDED = Set.of(
            foldSite(DataSeeder.class, "run"),
            foldSite(DataSeeder.class, "rejectOverLongEmail"),
            foldSite(DataSeeder.class, "rejectOverLongPassword"),
            foldSite(DataSeeder.class, "rejectPublishedPassword"),
            foldSite(DataSeeder.class, "rejectPublishedAdminHash"));

    /**
     * A door excused because its request record forces an already-folded ASCII character class, so the
     * fold is the identity. <strong>This exclusion is not prose:</strong> the test reads the live
     * {@code @Pattern} off the named component and requires it to still be the literal below, so
     * relaxing either pattern reds the build and forces that door to get a row. That is the only honest
     * way to exclude something for being ASCII-only by construction.
     *
     * <p>{@code AdminFieldService.renameTo} is here while {@code AdminFieldService.slugify} is a
     * truncating deriver — the same class, two sites, two different reasons, which is the whole argument
     * for a site-keyed population: the class-keyed version of this table could not have said that.
     */
    private record PatternGuard(Class<?> record, String component, String regexp) {}

    private static final Map<String, PatternGuard> ASCII_KEY_PATTERNS = Map.of(
            foldSite(ProjectService.class, "create"),
            new PatternGuard(CreateProjectRequest.class, "key", "[A-Z0-9]+"),
            foldSite(AdminFieldService.class, "renameTo"),
            new PatternGuard(UpsertFieldRequest.class, "key", "[a-z0-9_]*"));

    /**
     * Excluded by a live {@code @Pattern}: {@code ProjectService.create}'s {@code toUpperCase} →
     * {@code projects.key VARCHAR(10)}, and {@code AdminFieldService.renameTo}'s →
     * {@code field_defs.key VARCHAR(50)}. Held equal to {@link #ASCII_KEY_PATTERNS}' keys by the claim
     * itself, so an entry cannot be excused without a live literal behind it.
     */
    private static final Set<String> PATTERN_BOUNDED = ASCII_KEY_PATTERNS.keySet();

    /**
     * <strong>The anti-vacuity floor under the fold-site population</strong>: production SITES that fold
     * a case ({@code String.toLowerCase}/{@code toUpperCase}, directly or through
     * {@link #FOLD_HELPERS}). Every claim in
     * {@link #everyDoorThatCaseFoldsAValueItStoresMeasuresItsBoundAfterTheFold()} is of the form
     * "nothing offends", so a walk that stopped seeing sites reports clean for ever — under this floor
     * the bytecode walk is not seeing them and the whole claim is vacuous.
     *
     * <p><strong>A floor is not a copy of the population</strong>: it sits deliberately well below it,
     * because it exists to catch a collapsed or narrowed scan and not the arrival or departure of one
     * site. Raise it deliberately, in the commit that widened the scan; never lower it to make a run
     * pass.
     *
     * <p><strong>The live size is deliberately not written here.</strong> It is printed, computed from
     * the walk, by {@code Population.describe()} in this test's failure messages — the only place it
     * cannot go stale. This javadoc carried "MEASURED 76, across 36 classes" for exactly as long as it
     * took the same fix loop to add one more folding site, so the number was wrong one site before any
     * of the prose around it was (HD-306; the trap this file quotes at itself). To read today's figure,
     * raise this constant, run the test, and put it back.
     *
     * <p>History, and why the unit is a SITE rather than a class: a class can hold several fold sites
     * with different fates — {@code AdminFieldService} folds in two places that are excused for two
     * different reasons — and while the claim was class-keyed every such class was covered by whichever
     * of its sites got a row first. See {@link #foldSite}.
     */
    private static final int FOLD_CALLER_FLOOR = 65;

    /**
     * Sites that must each carry their own {@code [fold]} row on 2026-09-13: 3 — register's shared gate
     * ({@code AuthService.requireStorableEmail}), {@code AdminUserService.create} and
     * {@code WorkspaceService.inviteMember}.
     */
    private static final int FOLD_DOOR_FLOOR = 3;

    /**
     * The tripwire under the row table. Do <strong>not</strong> lower it to make a run pass: a row
     * that stopped executing is an endpoint with no guarantee, reported as success.
     */
    private static final int MIN_ROWS = 48;

    /**
     * The verbs the scan covered before it read {@code Doors}. The category is now all four write
     * verbs; this filter keeps the old floor as proof that the migration is no narrower than the
     * source scan it replaced.
     */
    private static final Set<RequestMethod> BODY_VERBS =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH);

    /**
     * Free-text write endpoints deliberately <strong>not</strong> exercised, each with the reason it
     * is safe to leave out. An entry here is a decision; an endpoint missing from both this map and
     * the row table is a build failure.
     */
    private static final Map<String, String> EXCLUSIONS = Map.of(
            "IssueController#uploadAttachment",
            "multipart, out of scope for v1 (§14.3): the only text a multipart request contributes "
            + "to a column is the filename, and AttachmentService.sanitizeFilename truncates it to "
            + "the column width — a legitimate mechanism this harness cannot distinguish from a bound");

    @Autowired MockMvc mockMvc;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * A spy and not a mock: the fixture below really does encode passwords, and
     * {@link #theUnauthenticatedFoldRefusalCostsNothingAndLeavesNoErrorLine()} has to prove that a
     * refused registration does <em>not</em> — a bcrypt-12 an unauthenticated caller can demand is the
     * cost this ticket exists to remove.
     */
    @MockitoSpyBean PasswordEncoder passwordEncoder;

    @MockitoBean JavaMailSender mailSender;

    private final ObjectMapper json = new ObjectMapper();

    private Fixture fixture;

    // =================================================================== the table

    /** Which credentials a row travels with. */
    private enum As { ANONYMOUS, MEMBER, ADMIN }

    /**
     * {@link #REFUSED} — the payload is over every bound this door could have, so a 4xx is required.
     * {@link #ACCEPTED_OR_REFUSED} — valid input whose derived value is the thing at risk; refusing
     * and truncating are both correct, and only a 5xx is a failure.
     * {@link #REFUSED_AFTER_CANONICALISATION} / {@link #REFUSED_AFTER_FOLD} — the payload is within the
     * raw bound and over it once the server has DERIVED the value it stores, so the answer must be a
     * 400/422 <em>from the bound</em>: naming the limit, and not the 22001 backstop's
     * {@link #BACKSTOP_ERROR_TYPE}. A 404/409 fails too — the row never reached the bound and proves
     * nothing about it. The two share one judge ({@link #judgeDerivedValueRow}) because the verdict is
     * the same three questions; only the transform's name and its remedy differ.
     */
    private enum Expect {
        REFUSED, ACCEPTED_OR_REFUSED, REFUSED_AFTER_CANONICALISATION, REFUSED_AFTER_FOLD;

        /** The two that measure a value the SERVER derived from the caller's text. */
        boolean isDerivedValue() {
            return this == REFUSED_AFTER_CANONICALISATION || this == REFUSED_AFTER_FOLD;
        }
    }

    /**
     * @param door       for a derived-value row, the service whose post-transform bound the row
     *                   exercises — the member of the caller-set claim
     * @param limit      that door's limit, which the refusal must name
     * @param derived    the length the submitted value reaches once the server has transformed it, so
     *                   the verdict can state the arithmetic instead of recomputing it per transform
     * @param doorMethod the METHOD inside {@code door} that performs the transform, for a
     *                   {@code [fold]} row — the fold claim's member is a site and not a class
     *                   ({@link #foldSite}), because a class with three folds is a class where one row
     *                   used to cover all three. {@code null} for every other row shape; the
     *                   {@code [nfc]} claim is still class-keyed and says so at its own method
     */
    private record Row(String id, String method, Function<Fixture, String> path,
                       Function<Fixture, String> body, As as, Expect expect, Class<?> door, int limit,
                       int derived, String doorMethod) {
        Row(String id, String method, Function<Fixture, String> path, Function<Fixture, String> body, As as) {
            this(id, method, path, body, as, Expect.REFUSED);
        }

        Row(String id, String method, Function<Fixture, String> path, Function<Fixture, String> body, As as,
            Expect expect) {
            this(id, method, path, body, as, expect, null, 0, 0, null);
        }

        /** A POST of {@code {"name": limit × U+0958}} as the member, against {@code door}'s bound. */
        static Row growsUnderNfc(String id, Function<Fixture, String> path, Class<?> door, int limit) {
            return new Row(id, "POST", path, f -> "{\"name\":\"" + GROWS_UNDER_NFC.repeat(limit) + "\"}",
                    As.MEMBER, Expect.REFUSED_AFTER_CANONICALISATION, door, limit, 2 * limit, null);
        }

        /**
         * A POST of an address that is within {@code limit} as typed and {@code derived} characters once
         * lower-cased, against the post-fold bound of {@code door}{@code #doorMethod} (HD-306). The body
         * is given rather than built because the fold doors take different records, and the METHOD is
         * given because that is the unit of the claim.
         */
        static Row growsUnderFold(String id, Function<Fixture, String> path,
                                  Function<Fixture, String> body, As as, Class<?> door,
                                  String doorMethod, int limit, int derived) {
            return new Row(id, "POST", path, body, as, Expect.REFUSED_AFTER_FOLD, door, limit, derived,
                    doorMethod);
        }
    }

    private List<Row> rows() {
        return List.of(
                // ---- unauthenticated auth doors
                new Row("AuthController#register", "POST", f -> "/api/auth/register",
                        f -> "{\"email\":\"" + LONG + "@example.com\",\"password\":\"" + LONG
                             + "\",\"displayName\":\"" + LONG + "\",\"termsAccepted\":true}", As.ANONYMOUS),
                new Row("AuthController#login", "POST", f -> "/api/auth/login",
                        f -> "{\"email\":\"" + LONG + "@example.com\",\"password\":\"" + LONG + "\"}",
                        As.ANONYMOUS),
                new Row("AuthController#forgotPassword", "POST", f -> "/api/auth/forgot-password",
                        f -> "{\"email\":\"" + LONG + "@example.com\"}", As.ANONYMOUS),
                new Row("AuthController#resendVerification", "POST", f -> "/api/auth/resend-verification",
                        f -> "{\"email\":\"" + LONG + "@example.com\"}", As.ANONYMOUS),
                new Row("AuthController#resetPassword", "POST", f -> "/api/auth/reset-password",
                        f -> "{\"token\":\"" + LONG + "\",\"newPassword\":\"" + LONG + "\"}", As.ANONYMOUS),
                new Row("AuthController#verifyEmail", "POST", f -> "/api/auth/verify-email",
                        f -> "{\"token\":\"" + LONG + "\"}", As.ANONYMOUS),
                // THE FOLD ROWS (HD-306). Within @Size(max = 255) as typed, over users.email /
                // workspace_invites.email once lower-cased — so only a bound measured AFTER the fold
                // can refuse them, and the 22001 backstop's 400 does not count (it means the value
                // reached the column, and GlobalExceptionHandler logged an ERROR line saying so).
                // Register is the member that matters: it is UNAUTHENTICATED, and before this row the
                // refusal cost a bcrypt-12 and a recipient-ceiling spend inside an advisory lock.
                Row.growsUnderFold("AuthController#register[fold]", f -> "/api/auth/register",
                        f -> "{\"email\":\"" + FOLDS_TO_319 + "\",\"password\":\"password-1234\","
                             + "\"displayName\":\"Fold Test\",\"termsAccepted\":true}",
                        // The site is register's SHARED GATE and not register itself, because that is
                        // where the fold happens: requireStorableEmail refuses, so every caller of it is
                        // measured by construction — which is what makes attributing a row to a gate
                        // legitimate where attributing one to a CLASS was not (the two anonymous doors
                        // fold inline and reach no gate, so they are separate members with their own
                        // written decision).
                        As.ANONYMOUS, AuthService.class, "requireStorableEmail", 255, 319),

                // ---- workspace
                new Row("WorkspaceController#create", "POST", f -> "/api/workspaces",
                        f -> "{\"name\":\"" + LONG + "\"}", As.MEMBER),
                // THE DERIVED-VALUE ROW. 101 slug-safe characters pass @Size(max = 255) and used to
                // produce a 101-character slug for a VARCHAR(100) column — a 500 for any signed-in
                // user, on the first-run "Create a team" path.
                new Row("WorkspaceController#create[slug]", "POST", f -> "/api/workspaces",
                        f -> "{\"name\":\"" + NAME_101 + "\"}", As.MEMBER, Expect.ACCEPTED_OR_REFUSED),
                new Row("WorkspaceController#update", "PATCH", f -> "/api/workspaces/" + f.wsId,
                        f -> "{\"name\":\"" + LONG + "\"}", As.MEMBER),
                new Row("WorkspaceController#invite", "POST", f -> "/api/workspaces/" + f.wsId + "/invites",
                        f -> "{\"email\":\"" + LONG + "@example.com\",\"role\":\"" + LONG + "\"}", As.MEMBER),
                // The invite door's own fixture: its @Pattern keeps the local part ASCII, so the
                // growth is one U+0130 in the DOMAIN. MEASURED reachable (255 raw → 256 folded, zero
                // violations), so this is a row rather than a pattern exclusion.
                Row.growsUnderFold("WorkspaceController#invite[fold]",
                        f -> "/api/workspaces/" + f.wsId + "/invites",
                        f -> "{\"email\":\"" + ASCII_LOCAL_FOLDS_TO_256 + "\",\"role\":\"MEMBER\"}",
                        As.MEMBER, WorkspaceService.class, "inviteMember", 255, 256),
                new Row("WorkspaceController#updateMember", "PATCH",
                        f -> "/api/workspaces/" + f.wsId + "/members/" + UUID.randomUUID(),
                        f -> "{\"role\":\"" + LONG + "\"}", As.MEMBER),
                new Row("WorkspaceController#acceptInvite", "POST",
                        f -> "/api/workspaces/accept-invite?token=" + LONG, f -> null, As.MEMBER),

                // ---- project
                new Row("ProjectController#create", "POST",
                        f -> "/api/workspaces/" + f.wsId + "/projects",
                        f -> "{\"name\":\"" + LONG + "\",\"key\":\"KEY\",\"description\":\"" + LONG + "\"}",
                        As.MEMBER),
                new Row("ProjectController#update", "PATCH",
                        f -> "/api/workspaces/" + f.wsId + "/projects/" + f.projectId,
                        f -> "{\"name\":\"" + LONG + "\",\"description\":\"" + LONG + "\"}", As.MEMBER),
                new Row("ProjectController#addMember", "POST",
                        f -> "/api/workspaces/" + f.wsId + "/projects/" + f.projectId + "/members",
                        f -> "{\"userId\":\"" + UUID.randomUUID() + "\",\"role\":\"" + LONG + "\"}",
                        As.MEMBER),

                // ---- issues
                new Row("IssueController#create", "POST", Fixture::issues,
                        f -> "{\"title\":\"" + LONG + "\",\"description\":\"" + LONG + "\",\"typeId\":\""
                             + f.typeId + "\",\"statusId\":\"" + f.statusId + "\"}", As.MEMBER),
                new Row("IssueController#update", "PATCH", f -> f.issues() + "/" + f.issueNumber,
                        f -> "{\"title\":\"" + LONG + "\",\"description\":\"" + LONG + "\"}", As.MEMBER),
                new Row("IssueController#createComment", "POST",
                        f -> f.issues() + "/" + f.issueNumber + "/comments",
                        f -> "{\"body\":\"" + LONG + "\"}", As.MEMBER),
                new Row("IssueController#updateComment", "PATCH",
                        f -> f.issues() + "/" + f.issueNumber + "/comments/" + UUID.randomUUID(),
                        f -> "{\"body\":\"" + LONG + "\"}", As.MEMBER),

                // ---- classification
                new Row("LabelController#create", "POST", f -> "/api/workspaces/" + f.wsId + "/labels",
                        f -> "{\"name\":\"" + LONG + "\",\"description\":\"" + LONG + "\"}", As.MEMBER),
                new Row("LabelController#update", "PATCH",
                        f -> "/api/workspaces/" + f.wsId + "/labels/" + UUID.randomUUID(),
                        f -> "{\"name\":\"" + LONG + "\"}", As.MEMBER),
                new Row("ComponentController#create", "POST", f -> f.project() + "/components",
                        f -> "{\"name\":\"" + LONG + "\",\"description\":\"" + LONG + "\"}", As.MEMBER),
                new Row("ComponentController#update", "PATCH",
                        f -> f.project() + "/components/" + UUID.randomUUID(),
                        f -> "{\"name\":\"" + LONG + "\"}", As.MEMBER),
                new Row("VersionController#create", "POST", f -> f.project() + "/versions",
                        f -> "{\"name\":\"" + LONG + "\",\"description\":\"" + LONG + "\"}", As.MEMBER),
                new Row("VersionController#update", "PATCH",
                        f -> f.project() + "/versions/" + UUID.randomUUID(),
                        f -> "{\"name\":\"" + LONG + "\"}", As.MEMBER),
                // THE CANONICALISATION ROWS (HD-297): exactly the door's limit in a character NFC
                // doubles — within @Size raw, over the column canonical. Each literal is the door's
                // MAX_NAME_LENGTH (ADR-0017: repeated, never imported — a row that reads the constant
                // it is testing agrees with any value it takes).
                Row.growsUnderNfc("LabelController#create[nfc]",
                        f -> "/api/workspaces/" + f.wsId + "/labels", LabelService.class, 60),
                Row.growsUnderNfc("ComponentController#create[nfc]",
                        f -> f.project() + "/components", ComponentService.class, 80),
                Row.growsUnderNfc("VersionController#create[nfc]",
                        f -> f.project() + "/versions", VersionService.class, 60),

                // ---- sprints
                new Row("SprintController#create", "POST", f -> f.project() + "/sprints",
                        f -> "{\"name\":\"" + LONG + "\",\"goal\":\"" + LONG + "\"}", As.MEMBER),
                new Row("SprintController#update", "PATCH",
                        f -> f.project() + "/sprints/" + UUID.randomUUID(),
                        f -> "{\"name\":\"" + LONG + "\"}", As.MEMBER),
                new Row("SprintController#start", "POST",
                        f -> f.project() + "/sprints/" + UUID.randomUUID() + "/start",
                        f -> "{\"goal\":\"" + LONG + "\"}", As.MEMBER),
                Row.growsUnderNfc("SprintController#create[nfc]",
                        f -> f.project() + "/sprints", SprintService.class, 60),

                // ---- search, filters, roles
                new Row("SavedFilterController#create", "POST", f -> "/api/workspaces/" + f.wsId + "/filters",
                        f -> "{\"name\":\"" + LONG + "\",\"hql\":\"" + LONG + "\"}", As.MEMBER),
                new Row("SavedFilterController#update", "PATCH",
                        f -> "/api/workspaces/" + f.wsId + "/filters/" + UUID.randomUUID(),
                        f -> "{\"name\":\"" + LONG + "\"}", As.MEMBER),
                // The door that had no post-canonicalisation bound: 120 × U+0958 passed @Size(120),
                // canonicalised to 240 and reached saved_filters.name VARCHAR(120) — the backstop's 400.
                Row.growsUnderNfc("SavedFilterController#create[nfc]",
                        f -> "/api/workspaces/" + f.wsId + "/filters", SavedFilterService.class, 120),
                new Row("SearchController#search[row]", "POST", f -> "/api/workspaces/" + f.wsId + "/search",
                        f -> "{\"query\":\"" + LONG + "\"}", As.MEMBER),
                new Row("InsightsController#insights", "POST",
                        f -> "/api/workspaces/" + f.wsId + "/search/insights",
                        f -> "{\"measure\":\"" + LONG + "\",\"slice\":\"" + LONG + "\"}", As.MEMBER),
                new Row("RoleController#duplicate", "POST",
                        f -> "/api/workspaces/" + f.wsId + "/roles/" + f.roleId + "/duplicate",
                        f -> "{\"name\":\"" + LONG + "\",\"description\":\"" + LONG + "\"}", As.MEMBER),
                // The second derived-value row: roles.key VARCHAR(40) is generated from this name,
                // which may legitimately be 80 characters — the generator truncates AND reserves
                // room for its collision suffix inside the 40.
                new Row("RoleController#duplicate[key]", "POST",
                        f -> "/api/workspaces/" + f.wsId + "/roles/" + f.roleId + "/duplicate",
                        f -> "{\"name\":\"" + "k".repeat(80) + "\"}", As.MEMBER,
                        Expect.ACCEPTED_OR_REFUSED),
                new Row("RoleController#update", "PATCH",
                        f -> "/api/workspaces/" + f.wsId + "/roles/" + UUID.randomUUID(),
                        f -> "{\"name\":\"" + LONG + "\"}", As.MEMBER),
                new Row("RoleController#preview", "POST",
                        f -> "/api/workspaces/" + f.wsId + "/roles/preview",
                        f -> "{\"scope\":\"WORKSPACE\",\"permissions\":[{\"key\":\"" + LONG + "\"}]}",
                        As.MEMBER),

                // ---- the admin catalogue (one row per DTO; the two delegated mounts share them)
                new Row("AdminCatalogController#createStatus", "POST", f -> "/api/admin/statuses",
                        f -> "{\"name\":\"" + LONG + "\",\"category\":\"TODO\"}", As.ADMIN),
                new Row("AdminCatalogController#createPriority", "POST", f -> "/api/admin/priorities",
                        f -> "{\"name\":\"" + LONG + "\",\"icon\":\"" + LONG + "\"}", As.ADMIN),
                new Row("AdminCatalogController#createIssueType", "POST", f -> "/api/admin/issue-types",
                        f -> "{\"name\":\"" + LONG + "\",\"icon\":\"" + LONG + "\"}", As.ADMIN),
                new Row("AdminFieldController#createField", "POST", f -> "/api/admin/fields",
                        f -> "{\"name\":\"" + LONG + "\",\"key\":\"k\",\"type\":\"TEXT\",\"description\":\""
                             + LONG + "\"}", As.ADMIN),
                new Row("AdminFieldController#createSet", "POST", f -> "/api/admin/field-sets",
                        f -> "{\"name\":\"" + LONG + "\",\"items\":[]}", As.ADMIN),
                new Row("AdminWorkflowController#createWorkflow", "POST", f -> "/api/admin/workflows",
                        f -> "{\"name\":\"" + LONG + "\",\"description\":\"" + LONG
                             + "\",\"statusIds\":[\"" + f.statusId + "\"]}", As.ADMIN),
                new Row("AdminWorkflowController#createPrioritySet", "POST", f -> "/api/admin/priority-sets",
                        f -> "{\"name\":\"" + LONG + "\",\"items\":[]}", As.ADMIN),
                new Row("AdminWorkflowController#createIssueTypeSet", "POST",
                        f -> "/api/admin/issue-type-sets",
                        f -> "{\"name\":\"" + LONG + "\",\"typeIds\":[\"" + f.typeId + "\"]}", As.ADMIN),
                new Row("AdminUserController#create", "POST", f -> "/api/admin/users",
                        f -> "{\"email\":\"" + LONG + "@example.com\",\"displayName\":\"" + LONG + "\"}",
                        As.ADMIN),
                // Same address as register's row, because the two records carry the same constraint
                // set — which is why one of them having the bound was never the guarantee.
                Row.growsUnderFold("AdminUserController#create[fold]", f -> "/api/admin/users",
                        f -> "{\"email\":\"" + FOLDS_TO_319 + "\",\"displayName\":\"Fold Test\"}",
                        As.ADMIN, AdminUserService.class, "create", 255, 319));
    }

    // =================================================================== the behaviour

    @BeforeEach
    void setUp() throws Exception {
        if (fixture == null) {
            fixture = buildFixture();
        }
    }

    @Test
    void noWriteEndpointAnswersAServerErrorToAnOverLongValue() throws Exception {
        var rows = rows();
        var offenders = new ArrayList<String>();

        for (var row : rows) {
            var response = perform(row);
            var status = response.getStatus();
            if (row.expect().isDerivedValue()) {
                judgeDerivedValueRow(row, response).ifPresent(offenders::add);
                continue;
            }
            if (status >= 500) {
                offenders.add(row.id() + " → " + status + " (a server error)");
            } else if (row.expect() == Expect.REFUSED && status < 400) {
                offenders.add(row.id() + " → " + status
                              + " (accepted a value no bound on this door could allow)");
            } else if (status == 401 || status == 403) {
                // Not a pass in disguise: a row refused at the door never reached validation, so
                // it proves nothing about any bound. Every row is written to travel with
                // credentials that get it as far as the request body.
                offenders.add(row.id() + " → " + status
                              + " (refused before validation — this row exercises no bound; fix its"
                              + " fixture context rather than accepting the green)");
            }
        }

        assertThat(rows)
                .as("""
                        The row table has shrunk below its tripwire. Every assertion in this class \
                        is of the form "nothing offends", so a row that stops running is an \
                        endpoint with NO guarantee, reported as a pass. Do not lower MIN_ROWS — \
                        find the row that left.""")
                .hasSizeGreaterThanOrEqualTo(MIN_ROWS);

        assertThat(offenders)
                .as("""
                        A write endpoint answered 5xx to a value a client chose, or accepted one \
                        that no bound could allow.

                        A 5xx here means a request path is missing a length bound: validation \
                        accepted a value the column refused, and the caller was told the server \
                        broke. Bound the field at the door (@Size names the field) or, when the \
                        offending value is DERIVED from request input rather than submitted, \
                        truncate it at the write site — that is the class of defect this harness \
                        exists for, and no annotation scan can find it.""")
                .isEmpty();
    }

    /**
     * The verdict on a row whose stored value the server DERIVES from the caller's text — canonicalises
     * (HD-297) or case-folds (HD-306) — or empty when the door held. <strong>One judge for both, because
     * the three things that can be wrong are identical and only the sentence differs:</strong> the
     * status is not a validation refusal (a 2xx accepted a value the column cannot hold; a 404/409 never
     * reached the bound; a 5xx is the column refusing); the body carries the 22001 backstop's
     * {@code errorType} (the column refused it and the log has an ERROR line — the exact defect, wearing
     * a 400); or the detail does not name the limit (a refusal that prescribes nothing). Two copies of
     * this would drift, which is the defect class this whole file exists for.
     */
    private java.util.Optional<String> judgeDerivedValueRow(Row row, MockHttpServletResponse response)
            throws Exception {
        boolean nfc = row.expect() == Expect.REFUSED_AFTER_CANONICALISATION;
        String transform = nfc ? "canonicalisation" : "fold";
        String payload = nfc
                ? row.limit() + " × U+0958"
                : "an address of " + row.limit() + " characters carrying U+0130";
        String derived = nfc ? "canonical" : "folded";
        String remedy = nfc
                ? "measure the length AFTER ClassificationNames.normalize, i.e. call "
                  + "ClassificationNames.requireValidName(raw, MAX, noun)"
                : "measure the length AFTER toLowerCase, i.e. call "
                  + "MailAddresses.requireStorableAddress(raw, MAX)";

        int status = response.getStatus();
        if (status != 400 && status != 422) {
            return java.util.Optional.of(row.id() + " → " + status + " (expected 400/422 from "
                    + row.door().getSimpleName() + "'s post-" + transform + " bound: " + payload
                    + " is within @Size raw and " + row.derived() + " characters " + derived
                    + " — a 2xx means no bound after the " + transform + ", a 404/409 means the row never "
                    + "reached it, a 5xx means the column refused it)");
        }
        String content = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode body = content.isBlank() ? json.nullNode() : json.readTree(content);
        String errorType = body.path("errorType").asText(null);
        if (BACKSTOP_ERROR_TYPE.equals(errorType)) {
            return java.util.Optional.of(row.id() + " → " + status + " errorType=" + errorType
                    + " (the 22001 backstop answered: " + row.door().getSimpleName() + " let "
                    + row.derived() + " " + derived + " characters reach a " + row.limit()
                    + "-wide column, and GlobalExceptionHandler logged it at ERROR — " + remedy + ")");
        }
        String detail = body.path("detail").asText("");
        if (!detail.contains(String.valueOf(row.limit()))) {
            return java.util.Optional.of(row.id() + " → " + status + " detail=\"" + detail + "\" (refused, but without naming "
                    + "the limit " + row.limit() + " — a refusal must prescribe an action its reader can perform)");
        }
        return java.util.Optional.empty();
    }

    /**
     * <strong>The half of the defect a status class cannot see</strong> (HD-306 AC 2/3/6), asserted on
     * the member that matters — the unauthenticated door.
     *
     * <p>Four things at once, because they are one claim about one request: the refusal is the bound's
     * and not the 22001 backstop's; <strong>no {@code ERROR} line is logged</strong> (the backstop logs
     * one by design, so its absence is what says the value never reached the column); the caller is
     * charged <strong>no bcrypt-12 and no {@code mail_send_events} row</strong> — the ~370 ms hash and
     * the ceiling spent inside an advisory lock were the whole cost of this defect; and the refusal has
     * a <strong>witness</strong>, {@code signup_refused{reason="email_too_long"}}.
     *
     * <p>The counter is asserted as a delta rather than as a value: this context is shared and the
     * register rows run in whatever order JUnit picks.
     */
    @Test
    void theUnauthenticatedFoldRefusalCostsNothingAndLeavesNoErrorLine() throws Exception {
        var row = rows().stream().filter(r -> r.id().equals("AuthController#register[fold]"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "AuthController#register[fold] has left rows() — it is the row that holds the "
                        + "unauthenticated member of the fold category"));
        long mailEventsBefore = countMailSendEvents();
        double refusalsBefore = signupRefusedEmailTooLong();
        Mockito.clearInvocations(passwordEncoder);

        var responses = new MockHttpServletResponse[1];
        var errorLines = errorsWhile(() -> {
            try {
                responses[0] = perform(row);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(judgeDerivedValueRow(row, responses[0]))
                .as("the row itself must be held by the post-fold bound before anything below means "
                    + "anything")
                .isEmpty();
        assertThat(errorLines)
                .as("""
                        The register door answered 400 to an over-long folded address and STILL logged at \
                        ERROR, which means the 22001 backstop answered rather than a bound: the 319-character \
                        value reached users.email. A status class alone cannot see this - GlobalExceptionHandler \
                        answers 400 there too - so the ERROR line is half the assertion. Move the \
                        measurement to the fold (MailAddresses.requireStorableAddress) instead of letting the \
                        column refuse it.""")
                .isEmpty();
        Mockito.verify(passwordEncoder, Mockito.never()).encode(Mockito.any());
        assertThat(countMailSendEvents())
                .as("a refused registration must spend no recipient ceiling: the gate is above "
                    + "requireAndRecordWhereEndpointDiscloses, and a row here means it is below it - "
                    + "inside the advisory lock that is held to commit")
                .isEqualTo(mailEventsBefore);
        assertThat(userRepository.existsByFoldedEmail(FOLDS_TO_319.toLowerCase(java.util.Locale.ROOT)))
                .as("and no account may exist for an address the column cannot hold")
                .isFalse();
        assertThat(signupRefusedEmailTooLong())
                .as("every refusal on the unauthenticated door has a witness; this one is "
                    + "signup_refused{reason=\"email_too_long\"}, emitted at register's own call site "
                    + "and never inside the shared gate")
                .isEqualTo(refusalsBefore + 1);
    }

    /**
     * <strong>The other half of the fold category, and the half round 1 of HD-306 missed: a door that
     * must NOT refuse, whose stored value is bounded at the write site instead</strong> (fix loop).
     *
     * <p>{@code POST /api/auth/forgot-password} answers one uniform sentence to every input by
     * construction — that is its entire contract — so the remedy that is right for {@code register} is
     * wrong here: a 400 would change the shape of a deliberately shapeless endpoint, and the address on
     * this path is not an identity. What it writes is
     * {@code mail_send_events.recipient_email VARCHAR(320)}, from the address {@code AuthService}
     * folded, and {@link #FOLDS_TO_324} is 255 characters that {@code ForgotPasswordRequest} accepts
     * with zero violations and 324 once folded.
     *
     * <p><strong>Measured before the fit (2026-09-13):</strong> this request answered <strong>400</strong>
     * with an {@code ERROR} line and NO {@code mail_send_events} row — the {@code 22001} out of the
     * INSERT, raised inside the advisory lock, rolling back the ceiling row the caller had already been
     * counted on. Two distinguishable answers on an endpoint that has one, and a free probe of a
     * stranger's ceilings.
     *
     * <p>Asserted as five things about one request, because that is what the defect was: the answer is
     * the uniform one, no {@code ERROR} line was logged, the row EXISTS (the ceiling was really spent),
     * what it stored is exactly the column's width, and the cut has a witness on
     * {@code hamstrack.mail.stored_address_truncated{column="recipient_email"}}.
     */
    @Test
    void theAnonymousUniformDoorRecordsAnOverLongFoldedAddressWithoutChangingItsAnswer()
            throws Exception {
        assertThat(FOLDS_TO_324).as("inside @Size(max = 255) as typed").hasSize(255);
        assertThat(MailAddresses.storageFold(FOLDS_TO_324))
                .as("and over mail_send_events.recipient_email once the service has folded it")
                .hasSize(324);
        long eventsBefore = countMailSendEvents();
        double truncationsBefore = recipientEmailTruncations();

        var responses = new MockHttpServletResponse[1];
        var errorLines = errorsWhile(() -> {
            try {
                responses[0] = mockMvc.perform(post("/api/auth/forgot-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + FOLDS_TO_324 + "\"}"))
                        .andReturn().getResponse();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(responses[0].getStatus())
                .as("""
                        POST /api/auth/forgot-password answered %d to an address that is 255 \
                        characters as typed and 324 once the service folded it.

                        This endpoint's contract is that its answer NEVER varies -- a status that \
                        depends on the submitted bytes is a shape an attacker can read. The cause is \
                        mail_send_events.recipient_email: it holds the FOLDED address, and a value \
                        over 320 raises 22001 out of the INSERT inside the advisory lock, which also \
                        rolls back the ceiling row this caller was already counted on. Fit the value \
                        at the write site (MailAddresses.fitStoredRecipient in \
                        RecipientMailThrottle.record) -- do NOT add a refusal here.""",
                        responses[0].getStatus())
                .isEqualTo(200);
        assertThat(errorLines)
                .as("an ERROR line means the 22001 backstop answered, i.e. the folded address reached "
                    + "the column — the status class alone cannot see this, because "
                    + "GlobalExceptionHandler answers 400 there too")
                .isEmpty();
        assertThat(countMailSendEvents())
                .as("the ceiling row must SURVIVE: this door records before it looks the account up, "
                    + "and a rolled-back row is a ceiling the caller spent and got back")
                .isEqualTo(eventsBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                "select length(recipient_email) from mail_send_events order by created_at desc limit 1",
                Integer.class))
                .as("and what it stored is the column's width, cut at the site that writes it")
                .isEqualTo(320);
        assertThat(recipientEmailTruncations())
                .as("""
                        The cut is invisible to the caller by design -- the answer is the uniform one \
                        either way -- so this counter is the only thing that can say it happened. A \
                        drop with no witness is the shape behind five of this project's CRIT defects.""")
                .isEqualTo(truncationsBefore + 1);
    }

    private double recipientEmailTruncations() {
        var counter = meterRegistry.find("hamstrack.mail.stored_address_truncated")
                .tag("column", "recipient_email").counter();
        return counter == null ? 0 : counter.count();
    }

    private long countMailSendEvents() {
        Long count = jdbcTemplate.queryForObject("select count(*) from mail_send_events", Long.class);
        return count == null ? 0 : count;
    }

    private double signupRefusedEmailTooLong() {
        var counter = meterRegistry.find("hamstrack.auth.signup_refused")
                .tag("reason", "email_too_long").counter();
        return counter == null ? 0 : counter.count();
    }

    /** The ERROR lines {@code GlobalExceptionHandler} emitted while {@code body} ran. */
    private List<String> errorsWhile(Runnable body) {
        var logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private MockHttpServletResponse perform(Row row) throws Exception {
        var path = row.path().apply(fixture);
        MockHttpServletRequestBuilder request = switch (row.method()) {
            case "POST" -> post(path);
            case "PATCH" -> patch(path);
            default -> throw new IllegalStateException("unsupported method: " + row.method());
        };
        var body = row.body().apply(fixture);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        var token = switch (row.as()) {
            case ANONYMOUS -> null;
            case MEMBER -> fixture.memberToken;
            case ADMIN -> fixture.adminToken;
        };
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    // =================================================================== the caller-set claim

    /**
     * <strong>Every production class that canonicalises a name and stores it has an {@code [nfc]}
     * row, and every {@code [nfc]} row names a class that still canonicalises.</strong> The members
     * come from bytecode — {@link ProductionBytecode#callersOf} over {@code java.text.Normalizer} and
     * the two helpers, walked <em>through</em> the helpers ({@link #CANONICALISATION_HELPERS}) — so a
     * sixth door is a member the day its call compiles, whether it reaches for {@code Normalizer},
     * {@code ClassificationNames}, {@code SearchNames} or a future delegator declared a helper, and
     * cannot be left out by not being listed. The helpers and the read-side callers are declared out
     * with a reason and checked live ({@link Population#excluding}); the writing doors that remain
     * must each be the {@code door} of a row whose payload only grows under NFC. The blind spot,
     * stated: a door that normalises with a regex of its own and no NFC call is outside this graph —
     * inside {@code com.hamstrack.search..} {@code ArchitectureRulesTest} refuses a bare trimmer,
     * elsewhere that is what review is for.
     */
    @Test
    void everyDoorThatCanonicalisesANameMeasuresItsBoundAfterCanonicalisation() {
        var callers = Population.of("production classes calling java.text.Normalizer / ClassificationNames / SearchNames "
                                    + "(walked through the helpers)",
                        ProductionBytecode.callersOf(CANONICALISATION_HELPERS,
                                        Normalizer.class, ClassificationNames.class, SearchNames.class)
                                .stream().map(c -> c.getName()).toList())
                .floor(CANONICALISER_CALLER_FLOOR);
        var doors = callers
                .excluding("the helper itself — its callers were followed, so nothing behind it is lost",
                        CANONICALISATION_HELPERS)
                .excluding("reads only: canonicalises an operand, a typeahead prefix or a map key and stores nothing",
                        READ_SIDE_CANONICALISERS)
                .floor(CANONICALISING_DOOR_FLOOR);

        var rowsByDoor = new LinkedHashMap<String, List<String>>();
        for (var row : rows()) {
            if (row.expect() == Expect.REFUSED_AFTER_CANONICALISATION) {
                rowsByDoor.computeIfAbsent(row.door().getName(), k -> new ArrayList<>()).add(row.id());
            }
        }

        var offenders = new ArrayList<String>();
        for (var door : doors) {
            if (!rowsByDoor.containsKey(door)) {
                offenders.add(door + ": canonicalises a name and has no [nfc] row");
            }
        }
        for (var entry : rowsByDoor.entrySet()) {
            if (!callers.members().contains(entry.getKey())) {
                offenders.add(entry.getKey() + ": named by " + entry.getValue()
                              + " but no longer calls the canonicalisation family — the row is stale");
            }
        }

        assertThat(offenders)
                .as("""
                        A DOOR CANONICALISES A DISPLAY NAME AND NOTHING PROVES ITS LENGTH IS MEASURED AFTERWARDS.

                        %s

                        NFC lengthens composition-exclusion characters (120 x U+0958 -> 240), so a @Size on the \
                        request record bounds the raw text and not what is stored; the column then refuses the \
                        commit with a 22001 that the global handler answers 400 and LOGS AT ERROR. For each class \
                        above, do ONE of these:

                        1. it stores the name: route it through ClassificationNames.requireValidName(raw, MAX, noun) \
                           and add Row.growsUnderNfc(...) to rows() with its MAX as the literal;
                        2. it only reads (an operand, a prefix, a map key): add it to READ_SIDE_CANONICALISERS \
                           with that reason — the entry is checked live and must go when the call goes; \
                           if it is a new normalising helper other doors call, add it to \
                           CANONICALISATION_HELPERS instead — the walk then continues to its callers;
                        3. a stale row: the class stopped canonicalising — remove the row, or the change that \
                           stopped it is the defect.

                        %s / %s — do not lower either floor to pass.""",
                        String.join("\n", offenders), callers.describe(), doors.describe())
                .isEmpty();
    }

    /**
     * <strong>Every production SITE that case-folds a value it stores measures its bound after the fold,
     * and every {@code [fold]} row names a site that still folds</strong> (HD-306). The sibling of the
     * canonicalisation claim above, in the same shape and over the same machinery: members from bytecode
     * ({@link ProductionBytecode#callSitesOfMethods} over {@code String.toLowerCase} /
     * {@code toUpperCase}, walked <em>through</em> {@link #FOLD_HELPERS}), a floor on the population, a
     * floor on the doors, and a written decision per excluded site. A new folding-and-storing site is a
     * member the day its call compiles.
     *
     * <p><strong>The unit is a site, {@code Owner#method}</strong> — see {@link #foldSite} for the
     * defect that changed it. A class-keyed version of this claim is satisfiable by one row about one of
     * its folds, and a class that folds in several places usually folds for several reasons, so the row
     * it already has says nothing about the fold next to it.
     *
     * <p><strong>Every exclusion is either checked or is a statement about the site's own code, and
     * the two that name a mechanism elsewhere are checked</strong> ({@link #everyTruncatingStoreReallyTruncates()},
     * {@link #ASCII_KEY_PATTERNS}); {@code Population.excluding} additionally refuses any named site
     * that has stopped folding, so no exclusion can outlive its subject. Counting them here would go
     * stale one entry before the list does.
     *
     * <p><strong>Why a separate root from {@code callersOf}:</strong> {@code callersOf(helpers,
     * String.class)} would match every class that calls any method on {@code String}, i.e. the whole
     * tree — see {@code callSitesOfMethods}' javadoc. Narrowing to the two fold methods is what makes
     * the population mean something.
     *
     * <p>The blind spot, stated: a class that lower-cases with a regex, a {@code Collator} or a
     * character loop of its own calls neither fold method and is outside this graph. Inside
     * {@code com.hamstrack.search..} {@code ArchitectureRulesTest} refuses a bare trimmer; elsewhere
     * that is what review is for.
     */
    @Test
    void everyDoorThatCaseFoldsAValueItStoresMeasuresItsBoundAfterTheFold() {
        var callers = Population.of("production SITES (Owner#method) calling String.toLowerCase / "
                                    + "toUpperCase (walked through MailAddresses / SearchNames)",
                        ProductionBytecode.callSitesOfMethods(FOLD_HELPERS, String.class,
                                Set.of("toLowerCase", "toUpperCase")))
                .floor(FOLD_CALLER_FLOOR);
        var doors = callers
                .excluding("the fold helper itself — its callers were followed, so nothing behind it is lost",
                        FOLD_HELPER_SITES)
                .excluding("reads only: folds a lookup key, an operand, a sort key, an enum token or a "
                           + "log/metric tag and stores nothing folded", READ_SIDE_FOLDERS)
                .excluding("derives a slug or key: substitutes, then truncates INSIDE the target width at "
                           + "the derivation site, with the collision suffix carved out of it",
                        TRUNCATING_DERIVERS)
                .excluding("stores a derived key or a stored copy of an address — or IS the shared cut "
                           + "they reach through — TRUNCATED at the site that writes it and counted on "
                           + "hamstrack.mail.stored_address_truncated{column}; each site's own body must "
                           + "still call the cut, checked live by everyTruncatingStoreReallyTruncates",
                        TRUNCATED_STORED_ADDRESS)
                .excluding("hands the folded address to RecipientMailThrottle, which stores and bounds it; "
                           + "this door must not refuse (uniform response) — checked live by "
                           + "everyTruncatingStoreReallyTruncates", DELEGATES_TO_THE_THROTTLE)
                .excluding("the request record forces an already-folded ASCII character class, so the fold "
                           + "is the identity — the literal is asserted live below", PATTERN_BOUNDED)
                .excluding("not request-reachable: refuses at BOOT instead (DataSeeder.rejectOverLongEmail, "
                           + "held by SeedGuardStartupOrderingTest)", SEED_GUARDED)
                .floor(FOLD_DOOR_FLOOR);

        var offenders = new ArrayList<String>();

        // The pattern exclusions are not prose: the live annotation must still read the literal the
        // exclusion was written about, so relaxing it reds the build and forces that door to get a row.
        ASCII_KEY_PATTERNS.forEach((door, guard) -> {
            String live = patternOf(guard);
            if (!guard.regexp().equals(live)) {
                offenders.add(door + ": excused as ASCII-only by construction, but "
                              + guard.record().getSimpleName() + "." + guard.component()
                              + "'s @Pattern now reads " + (live == null ? "(none)" : "\"" + live + "\"")
                              + " instead of \"" + guard.regexp() + "\" — the fold may lengthen again, so "
                              + "this door needs a [fold] row (or the literal restored)");
            }
        });

        var rowsBySite = new LinkedHashMap<String, List<String>>();
        for (var row : rows()) {
            if (row.expect() == Expect.REFUSED_AFTER_FOLD) {
                rowsBySite.computeIfAbsent(foldSite(row.door(), row.doorMethod()),
                        k -> new ArrayList<>()).add(row.id());
            }
        }
        for (var site : doors) {
            if (!rowsBySite.containsKey(site)) {
                offenders.add(site + ": case-folds a value it stores and has no [fold] row");
            }
        }
        for (var entry : rowsBySite.entrySet()) {
            if (!callers.members().contains(entry.getKey())) {
                offenders.add(entry.getKey() + ": named by " + entry.getValue()
                              + " but is no longer a fold site — the row is stale");
            }
        }

        assertThat(offenders)
                .as("""
                        A SITE CASE-FOLDS A VALUE IT STORES AND NOTHING PROVES ITS LENGTH IS MEASURED AFTERWARDS.

                        %s

                        Every member is one Owner#method, never a class: AuthService folds and stores in \
                        three methods, and while this claim was keyed on classes ONE row about register \
                        satisfied all three -- which is how the two unauthenticated doors stayed \
                        uncovered inside a green seal.

                        toLowerCase is not length-preserving (U+0130 -> i + U+0307, so 64 of them fold to \
                        128), and roughly twenty uppercase mappings lengthen too. A @Size on the request \
                        record bounds the RAW text and not what is stored; the column then refuses the \
                        commit with a 22001 that the global handler answers 400 and LOGS AT ERROR - on \
                        POST /api/auth/register that costs an unauthenticated caller's bcrypt-12 and a \
                        mail ceiling first. For each site above, do ONE of these:

                        1. it stores the folded value as an IDENTITY: route it through \
                           MailAddresses.requireStorableAddress(raw, MAX) at the existing fold, above \
                           every spend, and add Row.growsUnderFold(...) to rows() naming this METHOD;
                        2. it only reads (a lookup key, an operand, a sort key, a tag): add the site to \
                           READ_SIDE_FOLDERS - the entry is checked live and must go when the call goes; \
                           if it is a new folding helper other sites call, add its CLASS to FOLD_HELPERS \
                           and its own sites to FOLD_HELPER_SITES, and the walk continues to its callers;
                        3. it stores a DERIVED key or a forensic copy rather than an identity: truncate at \
                           the site that WRITES it, count the truncation, and add the site to \
                           TRUNCATING_DERIVERS or TRUNCATED_STORED_ADDRESS - the second is checked live \
                           by everyTruncatingStoreReallyTruncates, so the mechanism must really be there;
                        4. its record forces an already-folded ASCII class: add the site to \
                           PATTERN_BOUNDED and to ASCII_KEY_PATTERNS, which asserts the literal is \
                           still live;
                        5. a stale row: the fold moved or went - remove the row, or the change that \
                           moved it is the defect.

                        %s / %s - do not lower either floor to pass.""",
                        String.join("\n", offenders), callers.describe(), doors.describe())
                .isEmpty();
    }

    /**
     * <strong>The two "it is handled elsewhere" exclusions are CHECKED, not written — per SITE</strong>
     * (HD-306 fix loop, round 3). {@link #TRUNCATED_STORED_ADDRESS} and
     * {@link #DELEGATES_TO_THE_THROTTLE} are the only exclusions here whose reason is a mechanism
     * somewhere else, and a mechanism named in prose is exactly what round 1 left behind:
     * {@code RecipientMailThrottle} was excused for "a derived throttle key that
     * {@code MailAddresses.throttleKey} truncates", which was true of one of the two values it stored.
     *
     * <p><strong>Round 2 of this test could not fail, and the defect it was written against survived
     * inside it.</strong> It asked whether the excused site's CLASS reached the mechanism, over a
     * population whose unit is a SITE. With the round-1 defect planted back — {@code record} storing
     * the unfitted address — {@code RecipientMailThrottle} still called {@code throttleKey} one method
     * above, so this printed green: *the class excused for a mechanism it holds for one of the values
     * it stores*, in the guard written to kill that shape. A guard whose failure message cannot fire
     * is worse than no guard, because it reads as coverage.
     *
     * <p>So the match is on the ORIGIN method as well as the target: the truncation, or the throttle
     * call, must be made <em>from the body of the named site</em>. That is what {@link #callsFromSite}
     * does, and the spelling of a site comes from {@link ProductionBytecode#site} on both sides, so the
     * two halves cannot be formatted differently. Still deliberately weaker than "this column is
     * bounded" — the seals named on those two constants are what say that — and now genuinely stronger
     * than a sentence: move the cut one method away, or take a door off the throttle, and this reds.
     *
     * <p>The last assertion is the granularity's own control: the two cuts in
     * {@code RecipientMailThrottle} live in two different methods and neither reaches the other's, so a
     * green here cannot be the class-level accident round 2 shipped.
     */
    @Test
    void everyTruncatingStoreReallyTruncates() {
        var truncators = Set.of(
                MailAddresses.class.getName() + "#throttleKey",
                MailAddresses.class.getName() + "#fitStoredRecipient",
                // The shared counted cut for failed_email.recipient. MailService#truncate is NOT here
                // on purpose: deadLetter still calls it for the subject and the error text, so keeping
                // it would let that site pass on a truncation of a value that is not the address —
                // round 1's shape again, one column over.
                MailService.class.getName() + "#fitStoredRecipient");
        var throttleDoors = Set.of(
                RecipientMailThrottle.class.getName() + "#allowAnonymousSend",
                RecipientMailThrottle.class.getName() + "#requireAndRecord",
                RecipientMailThrottle.class.getName() + "#requireAndRecordWhereEndpointDiscloses");
        var offenders = new ArrayList<String>();
        for (var site : TRUNCATED_STORED_ADDRESS) {
            if (!callsFromSite(site, truncators)) {
                offenders.add(site + ": excused as \"truncated at the site that writes it\", but that "
                              + "METHOD calls none of " + truncators + " — either the truncation went "
                              + "(the defect) or it moved to another method, where it does not bound "
                              + "what this one stores");
            }
        }
        for (var site : DELEGATES_TO_THE_THROTTLE) {
            if (!callsFromSite(site, throttleDoors)) {
                offenders.add(site + ": excused as \"hands the address to RecipientMailThrottle\", but "
                              + "that METHOD calls no throttle door — so nothing bounds what it "
                              + "stores, and this door needs a decision of its own");
            }
        }

        assertThat(offenders)
                .as("""
                        An exclusion from the fold claim says the value is bounded somewhere else, and \
                        that somewhere else is not in this method.

                        %s

                        This is the round-1 defect in its general form: a site excused in prose for a \
                        mechanism its CLASS holds for one of the values it stores, or for a mechanism \
                        nobody holds any more. Restore the call in this method, or move the site out \
                        of the exclusion and give it a [fold] row.""", String.join("\n", offenders))
                .isEmpty();

        assertThat(callsFromSite(foldSite(RecipientMailThrottle.class, "spend"),
                Set.of(MailAddresses.class.getName() + "#fitStoredRecipient")))
                .as("""
                        The granularity control, and the reason this test exists in this shape: spend \
                        cuts the KEY and record cuts the stored ADDRESS, in two different methods. If \
                        spend reaches record's cut too, this check has become class-level again and \
                        would pass for a site that lost its own truncation -- which is exactly what \
                        round 2 shipped. Split the cuts back apart, or re-derive this control.""")
                .isFalse();
    }

    private static String owner(String site) {
        return site.substring(0, site.indexOf('#'));
    }

    /**
     * Does the METHOD named by {@code site} ({@code Owner#method}) itself call any of {@code targets}
     * (also {@code Owner#method}) in production bytecode?
     *
     * <p>The origin filter is the whole point — see {@link #everyTruncatingStoreReallyTruncates()} for
     * the green this method's class-level predecessor printed over a planted defect.
     */
    private static boolean callsFromSite(String site, Set<String> targets) {
        var owner = owner(site);
        var method = site.substring(site.indexOf('#') + 1);
        for (var clazz : ProductionBytecode.main()) {
            if (!clazz.getName().equals(owner)) {
                continue;
            }
            return clazz.getMethodCallsFromSelf().stream()
                    .filter(call -> call.getOrigin().getName().equals(method))
                    .anyMatch(call -> targets.contains(
                            call.getTargetOwner().getName() + "#" + call.getName()));
        }
        throw new AssertionError(owner + " is not in the production import — an exclusion names a "
                                + "class that no longer exists");
    }

    /**
     * The live {@code @Pattern} regexp on a request record component, or {@code null} when it has none.
     *
     * <p>Read off the <strong>accessor</strong>, not off the {@code RecordComponent}:
     * {@code jakarta.validation.constraints.Pattern} does not declare {@code ElementType.RECORD_COMPONENT}
     * in its {@code @Target}, so {@code RecordComponent.getAnnotation} answers {@code null} for every
     * record in this codebase — a reflection that would have excused both doors for ever while printing a
     * clean green. The compiler propagates the annotation to the accessor (its target includes
     * {@code METHOD}), with the backing field as a fallback for a future constraint whose target differs.
     */
    private static String patternOf(PatternGuard guard) {
        for (var component : guard.record().getRecordComponents()) {
            if (!component.getName().equals(guard.component())) {
                continue;
            }
            var onAccessor = component.getAccessor()
                    .getAnnotation(jakarta.validation.constraints.Pattern.class);
            if (onAccessor != null) {
                return onAccessor.regexp();
            }
            try {
                var onField = guard.record().getDeclaredField(guard.component())
                        .getAnnotation(jakarta.validation.constraints.Pattern.class);
                return onField == null ? null : onField.regexp();
            } catch (NoSuchFieldException e) {
                return null;
            }
        }
        throw new AssertionError(guard.record().getSimpleName() + " no longer has a component named '"
                + guard.component() + "' — ASCII_KEY_PATTERNS names it as the reason a door is excused "
                + "from the fold category, so the exclusion has outlived its subject");
    }

    // =================================================================== the category claim

    /**
     * <strong>The tripwire that makes this a category and not a list.</strong> It takes every write
     * handler from {@code Doors} (all four verbs — a {@code DELETE} with a {@code @RequestParam String}
     * is a write door that accepts caller text), asks (by reflection, not by list) whether the handler
     * accepts free text at all, and requires each one that does to be covered by a row, by a row on
     * the same request DTO, or by a declared exclusion.
     */
    @Test
    void everyWriteEndpointThatAcceptsFreeTextIsCoveredOrDeclaredAnException() {
        var writeHandlers = Doors.writeHandlers().floor(150);
        // The floor this test carried when it scanned POST/PUT/PATCH itself, kept on the same subset:
        // a migration that made the population smaller would fail here, not pass quietly. writeVerbs(),
        // not verbs(): an unconditioned mapping answers every verb and counts here exactly as it
        // counts on the population (none exists today, so the two read the same number).
        writeHandlers.filter("POST/PUT/PATCH only", h -> h.writeVerbs().stream().anyMatch(BODY_VERBS::contains))
                .floor(130);

        var scanned = new LinkedHashMap<String, Method>();
        for (var handler : writeHandlers) {
            scanned.put(handler.id(), handler.method());
        }

        var covered = new HashSet<String>();
        var coveredDtos = new HashSet<Type>();
        for (var row : rows()) {
            var id = row.id().replaceAll("\\[.*]$", "");
            covered.add(id);
            var handler = scanned.get(id);
            if (handler != null) {
                requestBodyType(handler).ifPresent(coveredDtos::add);
            }
        }

        var uncovered = new LinkedHashSet<String>();
        var freeText = 0;
        for (var entry : scanned.entrySet()) {
            if (!acceptsFreeText(entry.getValue())) {
                continue;
            }
            freeText++;
            var id = entry.getKey();
            if (covered.contains(id) || EXCLUSIONS.containsKey(id)) {
                continue;
            }
            var dto = requestBodyType(entry.getValue());
            if (dto.isPresent() && coveredDtos.contains(dto.get())) {
                continue;
            }
            uncovered.add(id + (dto.map(t -> "  (body: " + typeName(t) + ")").orElse("  (params only)")));
        }

        assertThat(freeText)
                .as("the scan classified %d endpoints as accepting free text (84 today) "
                    + "— if that has collapsed, the reflection below is failing to resolve handlers and every "
                    + "claim in this test is vacuous", freeText)
                .isGreaterThanOrEqualTo(80);

        assertThat(uncovered)
                .as("""
                        A WRITE ENDPOINT ACCEPTS FREE TEXT AND NOTHING PROVES IT CANNOT ANSWER 500.

                        For each endpoint listed above, do ONE of these — and note that the second \
                        is usually the right one:

                        1. add a Row to rows() below: method, path, a body filling its free-text \
                           fields with an over-long value, and the fixture context it needs \
                           (ANONYMOUS / MEMBER / ADMIN). If the endpoint DERIVES a stored value \
                           from request input (a slug, a key, a history label), add a SECOND row \
                           carrying input that is VALID at the edge and over-long once derived — \
                           that is the class of bug this file exists for, and the fill-everything \
                           row cannot reach it;

                        2. if it is another mount of a request DTO already covered, nothing is \
                           needed: coverage is by DTO. If you are reading this message, it is not \
                           one — the DTO is named above;

                        3. if it genuinely cannot reach a column with caller-supplied text, add it \
                           to EXCLUSIONS with the reason, which is a decision somebody will read.

                        DO NOT LOWER MIN_ROWS OR EITHER SCAN FLOOR TO MAKE THIS PASS. Those \
                        numbers exist because every assertion here is "nothing offends", and a \
                        harness that has stopped running rows is green and worthless.""")
                .isEmpty();
    }

    // ------------------------------------------------------------------ reflection

    /** A handler accepts free text if any body it binds, or any String param, can carry one. */
    private static boolean acceptsFreeText(Method method) {
        for (var parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(RequestBody.class)
                && carriesText(parameter.getParameterizedType(), 0)) {
                return true;
            }
            if (parameter.isAnnotationPresent(RequestParam.class)
                && parameter.getType() == String.class) {
                return true;
            }
        }
        return false;
    }

    /** {@code String} or a JSON document anywhere inside the type, to a bounded depth. */
    private static boolean carriesText(Type type, int depth) {
        if (depth > 4) {
            return false;
        }
        if (type instanceof ParameterizedType parameterized) {
            for (var argument : parameterized.getActualTypeArguments()) {
                if (carriesText(argument, depth + 1)) {
                    return true;
                }
            }
            return carriesText(parameterized.getRawType(), depth + 1);
        }
        if (!(type instanceof Class<?> raw)) {
            return false;
        }
        if (raw == String.class || JsonNode.class.isAssignableFrom(raw)) {
            return true;
        }
        if (raw.isRecord()) {
            for (var component : raw.getRecordComponents()) {
                if (carriesText(component.getGenericType(), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static java.util.Optional<Type> requestBodyType(Method method) {
        for (var parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(RequestBody.class)) {
                return java.util.Optional.of(parameter.getParameterizedType());
            }
        }
        return java.util.Optional.empty();
    }

    private static String typeName(Type type) {
        return type instanceof Class<?> raw ? raw.getSimpleName() : type.getTypeName();
    }

    // =================================================================== fixture

    private static final class Fixture {
        UUID wsId;
        UUID projectId;
        UUID typeId;
        UUID statusId;
        UUID roleId;
        int issueNumber;
        String memberToken;
        String adminToken;

        String project() {
            return "/api/workspaces/" + wsId + "/projects/" + projectId;
        }

        String issues() {
            return project() + "/issues";
        }
    }

    private Fixture buildFixture() throws Exception {
        var f = new Fixture();
        var owner = user(SystemRole.USER);

        var ws = new Workspace();
        ws.setName("WS");
        ws.setSlug("bound-" + UUID.randomUUID().toString().substring(0, 8) + "-"
                   + (System.nanoTime() % 100000));
        ws.setCreatedBy(owner);
        ws = workspaceRepository.save(ws);
        var wm = new WorkspaceMember();
        wm.setWorkspace(ws);
        wm.setUser(owner);
        wm.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "OWNER"));
        workspaceMemberRepository.save(wm);

        var project = new Project();
        project.setWorkspace(ws);
        project.setName("Proj");
        project.setKey("B" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        project.setCreatedBy(owner);
        project = projectRepository.save(project);
        var pm = new ProjectMember();
        pm.setProject(project);
        pm.setUser(owner);
        pm.setRole(roleCatalog.reference(RoleScope.PROJECT, "MANAGER"));
        projectMemberRepository.save(pm);

        f.wsId = ws.getId();
        f.projectId = project.getId();
        f.memberToken = login(owner);
        f.adminToken = login(user(SystemRole.ADMIN));

        var config = json.readTree(mockMvc.perform(get(f.project() + "/config")
                        .header("Authorization", "Bearer " + f.memberToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        f.typeId = UUID.fromString(config.get("issueTypes").get(0).get("id").asText());
        for (var s : config.get("statuses")) {
            if (f.statusId == null && s.get("category").asText().equals("TODO")) {
                f.statusId = UUID.fromString(s.get("id").asText());
            }
        }

        var issue = json.readTree(mockMvc.perform(post(f.issues())
                        .header("Authorization", "Bearer " + f.memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"bound\",\"typeId\":\"" + f.typeId + "\",\"statusId\":\""
                                 + f.statusId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        f.issueNumber = issue.get("number").asInt();

        var roles = json.readTree(mockMvc.perform(get("/api/workspaces/" + f.wsId + "/roles")
                        .header("Authorization", "Bearer " + f.memberToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        f.roleId = UUID.fromString(roles.get(0).get("id").asText());
        return f;
    }

    private User user(SystemRole role) {
        var u = new User();
        u.setEmail(("bound-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 6)
                    + "@example.com").toLowerCase());
        u.setDisplayName("Bound Test");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(role);
        return userRepository.save(u);
    }

    private String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
