package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.common.testsupport.DefaultLocale;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.issue.repository.FieldSetItemRepository;
import com.hamstrack.issue.repository.FieldSetRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-120 — the {@code ~} operator must return the same rows whatever locale the container
 * happens to be configured with.
 *
 * <p>The compiler folds the operand in Java and asks PostgreSQL to fold the column with
 * {@code LOWER()}. Those are two different implementations of "lower case", and they agree only
 * while the Java side is told which locale to use: under {@code tr-TR} the argument-less
 * {@code String.toLowerCase()} turns {@code "Issue"} into a dotless {@code "ıssue"} that no
 * {@code LOWER('Issue about widgets')} will ever equal, so a perfectly ordinary query silently
 * returns nothing — same code, same data, same query, different deployment. Azeri and Lithuanian
 * belong to the same family of surprise; {@code tr-TR} is simply the cheapest one to write down.
 *
 * <p><strong>This class asserts nothing an {@code en}-locale machine could have caught.</strong>
 * Every assertion here passes trivially on a developer laptop and on CI, both of which run under a
 * dotted-i locale. The value is entirely in {@link DefaultLocale}, which borrows the hostile locale
 * for the duration of the method and hands it back in a callback — see that annotation for the
 * reason the restoration is not allowed to live in a test body.
 *
 * <p>The properties block is copied verbatim from the neighbouring search tests on purpose: a
 * differing set spawns a second cached Spring context with its own connection pool, and the whole
 * suite runs in one fork.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
@DefaultLocale("tr-TR")
class TextSearchLocaleIndependenceTest {

    @Autowired MockMvc mockMvc;
    @Autowired com.hamstrack.workspace.service.RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired FieldDefRepository fieldDefRepository;
    @Autowired FieldSetRepository fieldSetRepository;
    @Autowired FieldSetItemRepository fieldSetItemRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * The premise the rest of the class rests on. If this ever fails, the annotation stopped
     * taking effect and every other assertion below quietly became a tautology — which is exactly
     * how a locale regression test rots into one that proves nothing.
     */
    @Test
    void theHostileLocaleIsActuallyInEffect() {
        assertThat(Locale.getDefault().getLanguage()).isEqualTo("tr");
        assertThat("Issue".toLowerCase(Locale.getDefault()))
                .as("premise: a Turkish JVM folds 'I' to a dotless i, which is the whole defect")
                .isEqualTo("ıssue")
                .isNotEqualTo("Issue".toLowerCase(Locale.ROOT));
    }

    /** An operand whose 'I' the default locale would mangle still matches the stored title. */
    @Test
    void anUpperIInTheTermStillMatchesTitleText() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Issue about widgets", null);
        createIssue(ctx, "unrelated", null);

        search(ctx, "text ~ \"Issue\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("Issue about widgets"));
    }

    /** ...and in the other direction: an upper-case operand against lower-case stored text. */
    @Test
    void anUpperCaseTermStillMatchesLowerCaseStoredText() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "the issue list", null);

        search(ctx, "text ~ \"ISSUE\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * The description half of the same predicate. The 'I' has to be in the <em>operand</em> to
     * discriminate: a Turkish fold leaves a lower-case 'i' alone, so a term typed in lower case
     * passes with or without the fix and would have quietly been a tautology.
     */
    @Test
    void theSameHoldsForTheDescriptionHalfOfTheTextPredicate() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "widgets", "an important note");

        search(ctx, "text ~ \"IMPORTANT\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * {@code ~} on a custom TEXT field is a second, independently written fold inside the same
     * compiler, and a fix applied to only one of the two is not a fix — this is the other one.
     */
    @Test
    void aCustomTextFieldMatchIsFoldedTheSameWay() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "carrier", null, "\"Incident report filed\"");
        createIssue(ctx, "other", null, "\"nothing to see\"");

        search(ctx, ctx.fieldKey + " ~ \"INCIDENT\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("carrier"));
    }

    // ============================================================ fixture

    private record Ctx(UUID wsId, UUID projectId, String token, JsonNode config,
                       UUID fieldId, String fieldKey) {

        UUID taskTypeId() {
            for (var t : config.get("issueTypes")) {
                if (t.get("name").asText().equals("Task")) return UUID.fromString(t.get("id").asText());
            }
            throw new AssertionError("type not offered: Task");
        }

        UUID todoStatusId() {
            for (var s : config.get("statuses")) {
                if (s.get("category").asText().equals("TODO")) return UUID.fromString(s.get("id").asText());
            }
            throw new AssertionError("no TODO-category status");
        }

        String issuesBase() {
            return "/api/workspaces/" + wsId + "/projects/" + projectId + "/issues";
        }
    }

    private Ctx newProject() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner);
        var project = project(ws, owner);
        projectMember(project, owner);

        // Unique per run, and deliberately free of 'i': the key travels through the lexer and the
        // field registry, neither of which is the subject here.
        var key = "note" + Math.abs(UUID.randomUUID().hashCode());
        var field = new FieldDef();
        field.setKey(key);
        field.setName("Note " + key);
        field.setType(FieldType.TEXT);
        field = fieldDefRepository.save(field);

        var set = new FieldSet();
        set.setName("Locale set " + key);
        set = fieldSetRepository.save(set);
        var item = new FieldSetItem();
        item.setSet(set);
        item.setField(field);
        item.setPosition((short) 0);
        item.setRequired(false);
        item.setShowOnCreate(true);
        fieldSetItemRepository.save(item);

        project.setFieldSet(set);
        project = projectRepository.save(project);

        var token = login(owner);
        var configBase = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config";
        var body = mockMvc.perform(get(configBase).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(ws.getId(), project.getId(), token, json.readTree(body), field.getId(), key);
    }

    private void createIssue(Ctx ctx, String title, String description) throws Exception {
        createIssue(ctx, title, description, null);
    }

    private void createIssue(Ctx ctx, String title, String description, String fieldValueJson)
            throws Exception {
        var sb = new StringBuilder("{");
        sb.append("\"title\":").append(json.writeValueAsString(title)).append(",");
        sb.append("\"typeId\":\"").append(ctx.taskTypeId()).append("\",");
        sb.append("\"statusId\":\"").append(ctx.todoStatusId()).append("\"");
        if (description != null) {
            sb.append(",\"description\":").append(json.writeValueAsString(description));
        }
        if (fieldValueJson != null) {
            sb.append(",\"fields\":{\"").append(ctx.fieldId()).append("\":").append(fieldValueJson).append("}");
        }
        sb.append("}");
        mockMvc.perform(post(ctx.issuesBase())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.toString()))
                .andExpect(status().isCreated());
    }

    private ResultActions search(Ctx ctx, String query) throws Exception {
        var body = "{\"query\":" + json.writeValueAsString(query) + "}";
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/search")
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ---- entity bootstrap ----

    private User user() {
        var u = new User();
        // No 'I' anywhere: the account is fixture, not subject. An address folded by the code under
        // test would leave a failure here ambiguous between two different defects.
        u.setEmail("loc-" + System.nanoTime() + "@example.com");
        u.setDisplayName("Test User");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("WS");
        w.setSlug("ws-" + UUID.randomUUID().toString().substring(0, 8) + "-" + (System.nanoTime() % 100000));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    private void member(Workspace ws, User user) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "OWNER"));
        workspaceMemberRepository.save(m);
    }

    private Project project(Workspace ws, User creator) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("Proj");
        p.setKey("P" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(creator);
        return projectRepository.save(p);
    }

    private void projectMember(Project project, User user) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.PROJECT, "MANAGER"));
        projectMemberRepository.save(m);
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
