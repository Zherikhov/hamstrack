package com.hamstrack.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 * <h2>The two tripwires, because every assertion here is "nothing offends"</h2>
 * <ol>
 *   <li>{@link #MIN_ROWS} — a row that stops running is a door with no guarantee while the suite
 *       stays green.</li>
 *   <li><strong>the category claim</strong>: a source scan of every {@code @PostMapping},
 *       {@code @PutMapping} and {@code @PatchMapping} under {@code src/main/java}, reduced to those
 *       that actually accept free text (decided by reflection over the handler's
 *       {@code @RequestBody} type and {@code @RequestParam String}s, never by a list), asserting each
 *       is covered by a row, by a row on the <em>same request DTO</em>, or by a declared exclusion.
 *       That is what makes a new write endpoint a deliberate edit rather than an omission.</li>
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
     * The tripwire under the row table. Do <strong>not</strong> lower it to make a run pass: a row
     * that stopped executing is an endpoint with no guarantee, reported as success.
     */
    private static final int MIN_ROWS = 45;

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    private static final Pattern WRITE_MAPPING =
            Pattern.compile("@(Post|Put|Patch)Mapping\\b");

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

    /** Cached across cases: the scan reads every production source file. */
    private Map<String, Method> writeMappings;

    // =================================================================== the table

    /** Which credentials a row travels with. */
    private enum As { ANONYMOUS, MEMBER, ADMIN }

    /**
     * {@link #REFUSED} — the payload is over every bound this door could have, so a 4xx is required.
     * {@link #ACCEPTED_OR_REFUSED} — valid input whose derived value is the thing at risk; refusing
     * and truncating are both correct, and only a 5xx is a failure.
     */
    private enum Expect { REFUSED, ACCEPTED_OR_REFUSED }

    private record Row(String id, String method, Function<Fixture, String> path,
                       Function<Fixture, String> body, As as, Expect expect) {
        Row(String id, String method, Function<Fixture, String> path, Function<Fixture, String> body, As as) {
            this(id, method, path, body, as, Expect.REFUSED);
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

                // ---- sprints
                new Row("SprintController#create", "POST", f -> f.project() + "/sprints",
                        f -> "{\"name\":\"" + LONG + "\",\"goal\":\"" + LONG + "\"}", As.MEMBER),
                new Row("SprintController#update", "PATCH",
                        f -> f.project() + "/sprints/" + UUID.randomUUID(),
                        f -> "{\"name\":\"" + LONG + "\"}", As.MEMBER),
                new Row("SprintController#start", "POST",
                        f -> f.project() + "/sprints/" + UUID.randomUUID() + "/start",
                        f -> "{\"goal\":\"" + LONG + "\"}", As.MEMBER),

                // ---- search, filters, roles
                new Row("SavedFilterController#create", "POST", f -> "/api/workspaces/" + f.wsId + "/filters",
                        f -> "{\"name\":\"" + LONG + "\",\"hql\":\"" + LONG + "\"}", As.MEMBER),
                new Row("SavedFilterController#update", "PATCH",
                        f -> "/api/workspaces/" + f.wsId + "/filters/" + UUID.randomUUID(),
                        f -> "{\"name\":\"" + LONG + "\"}", As.MEMBER),
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
            var status = perform(row);
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

    private int perform(Row row) throws Exception {
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
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    // =================================================================== the category claim

    /**
     * <strong>The tripwire that makes this a category and not a list.</strong> It reads every write
     * mapping in production source, asks (by reflection, not by list) whether the handler accepts
     * free text at all, and requires each one that does to be covered by a row, by a row on the same
     * request DTO, or by a declared exclusion.
     */
    @Test
    void everyWriteEndpointThatAcceptsFreeTextIsCoveredOrDeclaredAnException() throws Exception {
        var files = javaSources();
        assertThat(files)
                .as("scanned %s — if this is empty the working directory is not the project root",
                        MAIN_SOURCES.toAbsolutePath())
                .hasSizeGreaterThan(100);

        var covered = new HashSet<String>();
        var coveredDtos = new HashSet<Type>();
        for (var row : rows()) {
            var id = row.id().replaceAll("\\[.*]$", "");
            covered.add(id);
            var handler = writeMappings().get(id);
            if (handler != null) {
                requestBodyType(handler).ifPresent(coveredDtos::add);
            }
        }

        var scanned = writeMappings();

        assertThat(scanned)
                .as("""
                        the scan found %d write mappings, far fewer than this application has. A \
                        scanner that has stopped seeing declarations certifies nothing while \
                        staying green — find out what changed rather than lowering this.""",
                        scanned.size())
                .hasSizeGreaterThanOrEqualTo(130);

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

    // ------------------------------------------------------------------ scanning & reflection

    private void scanWriteMappings(Path file, Map<String, Method> into) {
        String source;
        try {
            source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("cannot read " + file, e);
        }
        if (!WRITE_MAPPING.matcher(source).find()) {
            return;
        }
        var className = classNameOf(file);
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("scanned " + file + " but could not load " + className
                                     + " — the scan and the classpath disagree", e);
        }
        for (var method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)) {
                into.put(type.getSimpleName() + "#" + method.getName(), method);
            }
        }
    }

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

    /** Scanned once: the walk reads every production source file, and every row asks for it. */
    private Map<String, Method> writeMappings() throws IOException {
        if (writeMappings == null) {
            var scanned = new LinkedHashMap<String, Method>();
            for (var file : javaSources()) {
                scanWriteMappings(file, scanned);
            }
            writeMappings = scanned;
        }
        return writeMappings;
    }

    private static String typeName(Type type) {
        return type instanceof Class<?> raw ? raw.getSimpleName() : type.getTypeName();
    }

    private static String classNameOf(Path file) {
        var relative = MAIN_SOURCES.relativize(file).toString();
        return relative.replace(".java", "").replace('\\', '.').replace('/', '.');
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }
    }

    /** Blanks comment content so prose about {@code @PostMapping} is not read as a mapping. */
    private static String stripComments(String src) {
        var out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
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
