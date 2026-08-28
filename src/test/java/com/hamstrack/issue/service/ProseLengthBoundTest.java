package com.hamstrack.issue.service;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-171 §4.3 / AC 8 and AC 14 — the five prose fields, and the one number they share.</strong>
 *
 * <p>An issue description, a comment body, a project description and a workflow description all
 * land in {@code TEXT} columns, so none of them could ever 500 — they were simply stored, whatever
 * their size, and then copied verbatim into {@code issue_history.old_value}/{@code new_value} on
 * every edit. The bound is 10 000 characters, and the number was not chosen to be independently
 * optimal: it is {@code FieldValueService}'s existing TEXTAREA bound, because a TEXTAREA custom
 * field and an issue description are the same kind of value, stored in the same database and
 * rendered in the same panel, and two numbers for one idea is the anti-pattern this ticket is named
 * after.
 *
 * <p>This class lives in {@code com.hamstrack.issue.service} for exactly one reason: it reads
 * {@link FieldValueService#MAX_TEXTAREA_LENGTH} directly rather than transcribing it. A test that
 * copied the number would agree with itself forever.
 *
 * <p><strong>Both edges are asserted, not just the refusal.</strong> 10 001 is a 400 naming the
 * field; 10 000 is accepted. Without the second half, a bound accidentally tightened to 100 would
 * pass every "over-long input is refused" test in the suite.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ProseLengthBoundTest {

    /** The bound §4.3 gives every prose field, read from the constant rather than transcribed. */
    private static final int MAX = FieldValueService.MAX_TEXTAREA_LENGTH;

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /**
     * The five prose fields of §4.3 and where each is declared. Named individually because a
     * blanket "every {@code String description} on a request DTO" claim would be false: a label's
     * description is bounded at 200 and a component's at 500, each matching its own {@code VARCHAR}
     * column. What these six declarations have in common is a {@code TEXT} column behind them — a
     * fact no source scan can see, which is why the list is written out and the tripwire below
     * checks that every entry still resolves.
     */
    private static final Map<String, String> PROSE_FIELDS = Map.of(
            "issue/dto/CreateIssueRequest.java", "description",
            "issue/dto/UpdateIssueRequest.java", "description",
            "issue/dto/CreateCommentRequest.java", "body",
            "project/dto/CreateProjectRequest.java", "description",
            "project/dto/UpdateProjectRequest.java", "description",
            "admin/dto/UpsertWorkflowRequest.java", "description");

    @Autowired MockMvc mockMvc;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    // ------------------------------------------------------------------ AC 14: one number, not two

    /**
     * <strong>Raising one raises both.</strong> {@code @Size(max = …)} takes a bare numeral in this
     * codebase (a symbolic constant would read as "no {@code @Size} at all" to a source scanner), so
     * nothing in the compiler keeps these six literals equal to
     * {@code FieldValueService.MAX_TEXTAREA_LENGTH}. This is what does.
     */
    @Test
    void everyProseFieldCarriesTheSameBoundAsTheTextareaCustomField() throws IOException {
        var offenders = new ArrayList<String>();
        var resolved = 0;

        for (var entry : PROSE_FIELDS.entrySet()) {
            var file = MAIN_SOURCES.resolve("com/hamstrack").resolve(entry.getKey());
            if (!Files.isRegularFile(file)) {
                offenders.add(entry.getKey() + " — no such file; the field may have moved");
                continue;
            }
            var source = Files.readString(file, StandardCharsets.UTF_8);
            var declaration = Pattern.compile(
                            "@Size\\s*\\([^)]*\\bmax\\s*=\\s*(\\d+)[^)]*\\)[^;,)]*?\\bString\\s+"
                            + entry.getValue() + "\\b")
                    .matcher(source);
            if (!declaration.find()) {
                offenders.add(entry.getKey() + "#" + entry.getValue()
                              + " — no @Size(max = …) resolves to this field");
                continue;
            }
            resolved++;
            int bound = Integer.parseInt(declaration.group(1));
            if (bound != MAX) {
                offenders.add(entry.getKey() + "#" + entry.getValue() + " — @Size(max = " + bound
                              + ") but FieldValueService.MAX_TEXTAREA_LENGTH is " + MAX);
            }
        }

        // The tripwire under a "nothing offends" assertion: a declaration that stops matching
        // leaves the offender list empty and green while guarding nothing at all.
        assertThat(resolved)
                .as("only %d of %d prose declarations resolved. A field whose annotation layout "
                    + "changed has silently left this check — find it rather than lowering this "
                    + "number", resolved, PROSE_FIELDS.size())
                .isEqualTo(PROSE_FIELDS.size());

        assertThat(offenders)
                .as("""
                        A prose bound has drifted away from FieldValueService.MAX_TEXTAREA_LENGTH.

                        These are all the same kind of value — a block of text this product stores \
                        in a TEXT column and renders in the same panel — so they carry ONE number, \
                        not several. If the product needs a bigger one, raise the constant AND \
                        every literal here in the same commit. Do not add an exception.""")
                .isEmpty();
    }

    // --------------------------------------------------------------------- AC 8: each door, both edges

    @Test
    void issueDescriptionRefusesOnePastTheBoundAndAcceptsTheBound() throws Exception {
        var ctx = fixture();

        mockMvc.perform(post(ctx.issues())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody(ctx, "x".repeat(MAX + 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.description").exists());

        mockMvc.perform(post(ctx.issues())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody(ctx, "x".repeat(MAX))))
                .andExpect(status().isCreated());
    }

    @Test
    void commentBodyRefusesOnePastTheBoundAndAcceptsTheBound() throws Exception {
        var ctx = fixture();
        var number = createIssue(ctx);
        var comments = ctx.issues() + "/" + number + "/comments";

        mockMvc.perform(post(comments)
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + "x".repeat(MAX + 1) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.body").exists());

        mockMvc.perform(post(comments)
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + "x".repeat(MAX) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void projectDescriptionRefusesOnePastTheBoundAndAcceptsTheBound() throws Exception {
        var ctx = fixture();

        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectBody("x".repeat(MAX + 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.description").exists());

        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectBody("x".repeat(MAX))))
                .andExpect(status().isCreated());
    }

    /**
     * Admin-only, and bounded all the same: {@code /api/admin/**} needs system role ADMIN, which
     * lowers the blast radius and changes nothing about the defect — the delegated-admin tiers
     * reach the same DTO through two more mounts.
     */
    @Test
    void workflowDescriptionRefusesOnePastTheBoundAndAcceptsTheBound() throws Exception {
        var token = loginAs(SystemRole.ADMIN);
        var statusId = anyStatusId(token);

        mockMvc.perform(post("/api/admin/workflows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workflowBody(statusId, "x".repeat(MAX + 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.description").exists());

        mockMvc.perform(post("/api/admin/workflows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workflowBody(statusId, "x".repeat(MAX))))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------------------- fixtures

    private record Ctx(UUID wsId, UUID projectId, String token, UUID typeId, UUID statusId) {
        String issues() {
            return "/api/workspaces/" + wsId + "/projects/" + projectId + "/issues";
        }
    }

    private static String issueBody(Ctx ctx, String description) {
        return "{\"title\":\"prose\",\"typeId\":\"" + ctx.typeId() + "\",\"statusId\":\""
               + ctx.statusId() + "\",\"description\":\"" + description + "\"}";
    }

    private static String projectBody(String description) {
        return "{\"name\":\"Prose\",\"key\":\"P" + (Math.abs(UUID.randomUUID().hashCode()) % 100000)
               + "\",\"description\":\"" + description + "\"}";
    }

    private static String workflowBody(String statusId, String description) {
        return "{\"name\":\"WF-" + (System.nanoTime() % 1000000) + "\",\"statusIds\":[\"" + statusId
               + "\"],\"description\":\"" + description + "\"}";
    }

    private String anyStatusId(String token) throws Exception {
        var body = mockMvc.perform(get("/api/admin/statuses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get(0).get("id").asText();
    }

    private int createIssue(Ctx ctx) throws Exception {
        var body = mockMvc.perform(post(ctx.issues())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody(ctx, "short")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("number").asInt();
    }

    private Ctx fixture() throws Exception {
        var owner = user(SystemRole.USER);
        var ws = new Workspace();
        ws.setName("WS");
        ws.setSlug("prose-" + UUID.randomUUID().toString().substring(0, 8) + "-"
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
        project.setKey("R" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        project.setCreatedBy(owner);
        project = projectRepository.save(project);
        var pm = new ProjectMember();
        pm.setProject(project);
        pm.setUser(owner);
        pm.setRole(roleCatalog.reference(RoleScope.PROJECT, "MANAGER"));
        projectMemberRepository.save(pm);

        var token = login(owner);
        var config = json.readTree(mockMvc.perform(get("/api/workspaces/" + ws.getId()
                                + "/projects/" + project.getId() + "/config")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        UUID typeId = UUID.fromString(config.get("issueTypes").get(0).get("id").asText());
        UUID statusId = null;
        for (var s : config.get("statuses")) {
            if (statusId == null && s.get("category").asText().equals("TODO")) {
                statusId = UUID.fromString(s.get("id").asText());
            }
        }
        return new Ctx(ws.getId(), project.getId(), token, typeId, statusId);
    }

    private User user(SystemRole role) {
        var u = new User();
        u.setEmail(("prose-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 6)
                    + "@example.com").toLowerCase());
        u.setDisplayName("Prose Test");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(role);
        return userRepository.save(u);
    }

    private String loginAs(SystemRole role) throws Exception {
        return login(user(role));
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
