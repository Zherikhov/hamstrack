package com.hamstrack.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
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

    /**
     * The tripwire under the row table. Do <strong>not</strong> lower it to make a run pass: a row
     * that stopped executing is an endpoint with no guarantee, reported as success.
     */
    private static final int MIN_ROWS = 45;

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
    @Autowired PasswordEncoder passwordEncoder;

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
     * {@link #REFUSED_AFTER_CANONICALISATION} — the payload is within the raw bound and over it once
     * canonical, so the answer must be a 400/422 <em>from the bound</em>: naming the limit, and not the
     * 22001 backstop's {@link #BACKSTOP_ERROR_TYPE}. A 404/409 fails too — the row never reached the
     * bound and proves nothing about it.
     */
    private enum Expect { REFUSED, ACCEPTED_OR_REFUSED, REFUSED_AFTER_CANONICALISATION }

    /**
     * @param door  for a {@link Expect#REFUSED_AFTER_CANONICALISATION} row, the service whose
     *              post-canonicalisation bound the row exercises — the member of the caller-set claim
     * @param limit that door's limit; the row sends exactly this many {@link #GROWS_UNDER_NFC}
     */
    private record Row(String id, String method, Function<Fixture, String> path,
                       Function<Fixture, String> body, As as, Expect expect, Class<?> door, int limit) {
        Row(String id, String method, Function<Fixture, String> path, Function<Fixture, String> body, As as) {
            this(id, method, path, body, as, Expect.REFUSED);
        }

        Row(String id, String method, Function<Fixture, String> path, Function<Fixture, String> body, As as,
            Expect expect) {
            this(id, method, path, body, as, expect, null, 0);
        }

        /** A POST of {@code {"name": limit × U+0958}} as the member, against {@code door}'s bound. */
        static Row growsUnderNfc(String id, Function<Fixture, String> path, Class<?> door, int limit) {
            return new Row(id, "POST", path, f -> "{\"name\":\"" + GROWS_UNDER_NFC.repeat(limit) + "\"}",
                    As.MEMBER, Expect.REFUSED_AFTER_CANONICALISATION, door, limit);
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
                        As.ADMIN));
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
            if (row.expect() == Expect.REFUSED_AFTER_CANONICALISATION) {
                judgeCanonicalisationRow(row, response).ifPresent(offenders::add);
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
     * The verdict on a {@link Expect#REFUSED_AFTER_CANONICALISATION} row, or empty when the door held.
     * Three things can be wrong and each is named: the status is not a validation refusal (a 2xx
     * accepted a name the column cannot hold; a 404/409 never reached the bound; a 5xx is the column
     * refusing); the body carries the 22001 backstop's {@code errorType} (the column refused it and
     * the log has an ERROR line — the exact defect, wearing a 400); or the detail does not name the
     * limit (a refusal that prescribes nothing).
     */
    private java.util.Optional<String> judgeCanonicalisationRow(Row row, MockHttpServletResponse response)
            throws Exception {
        int status = response.getStatus();
        if (status != 400 && status != 422) {
            return java.util.Optional.of(row.id() + " → " + status + " (expected 400/422 from " + row.door().getSimpleName()
                    + "'s post-canonicalisation bound: " + row.limit() + " × U+0958 is within @Size raw and "
                    + 2 * row.limit() + " characters canonical — a 2xx means no bound after canonicalisation, a "
                    + "404/409 means the row never reached it, a 5xx means the column refused it)");
        }
        String content = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode body = content.isBlank() ? json.nullNode() : json.readTree(content);
        String errorType = body.path("errorType").asText(null);
        if (BACKSTOP_ERROR_TYPE.equals(errorType)) {
            return java.util.Optional.of(row.id() + " → " + status + " errorType=" + errorType + " (the 22001 backstop answered: "
                    + row.door().getSimpleName() + " let " + 2 * row.limit() + " canonical characters reach a "
                    + row.limit() + "-wide column, and GlobalExceptionHandler logged it at ERROR — measure the "
                    + "length AFTER ClassificationNames.normalize, i.e. call ClassificationNames.requireValidName)");
        }
        String detail = body.path("detail").asText("");
        if (!detail.contains(String.valueOf(row.limit()))) {
            return java.util.Optional.of(row.id() + " → " + status + " detail=\"" + detail + "\" (refused, but without naming "
                    + "the limit " + row.limit() + " — a refusal must prescribe an action its reader can perform)");
        }
        return java.util.Optional.empty();
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
