package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.issue.repository.FieldSetItemRepository;
import com.hamstrack.issue.repository.FieldSetRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.entity.WorkspaceRole;
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

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration coverage for HD-52 — querying custom fields (M2 {@code field_defs},
 * stored in {@code issue_field_values} JSONB) through the HQL engine. Seeds a project
 * bound to a field set carrying a NUMBER, SELECT, MULTI_SELECT and USER custom field,
 * plus issues with and without values, and asserts the per-type predicates, EMPTY
 * semantics, the absent-row {@code !=} rule, {@code /schema} exposure, and — the #1
 * bug class — tenant isolation (a field only in workspace B is unknown in an A search).
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class CustomFieldSearchApiTest {

    @Autowired MockMvc mockMvc;
    // HD-123: memberships carry a roles row now; reference() resolves a built-in with no query.
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

    // ============================================================ per-type predicates

    @Test
    void numberComparisons() throws Exception {
        var ctx = newProjectWithCustomFields();
        createIssue(ctx, "big", Map.of(ctx.storyPoints, "8"));
        createIssue(ctx, "mid", Map.of(ctx.storyPoints, "5"));
        createIssue(ctx, "small", Map.of(ctx.storyPoints, "2"));
        createIssue(ctx, "none", Map.of());

        search(ctx, ctx.spKey + " >= 5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].issue.title", hasItems("big", "mid")))
                .andExpect(jsonPath("$.content[*].issue.title", not(hasItem("small"))));

        search(ctx, ctx.spKey + " = 8")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("big"));

        search(ctx, ctx.spKey + " < 5")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("small"));
    }

    /**
     * Regression (HD-52 Medium): a NUMBER comparison must not 500 when the SAME issue also
     * carries a non-numeric TEXT custom field. The predicate is an EXISTS correlated on
     * {@code issue_id}, so the {@code ::numeric} cast can be evaluated against sibling rows of
     * OTHER fields (here a TEXT "prod") before the {@code field_id} filter prunes them. The total
     * {@code jsonb_scalar_numeric} cast returns NULL for the text sibling instead of raising.
     */
    @Test
    void numberComparisonWithNonNumericSiblingDoesNotError() throws Exception {
        var ctx = newProjectWithCustomFields();
        // envKey is a MULTI_SELECT above; use a dedicated TEXT field on the same set as the sibling.
        // The MULTI_SELECT already stores a JSON array (also non-numeric) on the same issue, but we
        // add an explicit TEXT value to exercise the string-sibling case the review flagged ("prod").
        createIssue(ctx, "big", Map.of(
                ctx.storyPoints, "8",
                ctx.text, quoted("prod")));
        // second issue has ONLY the text field set (no story_points) → mixed table, no cast trip
        createIssue(ctx, "textonly", Map.of(ctx.text, quoted("staging")));

        search(ctx, ctx.spKey + " >= 5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("big"));
    }

    /**
     * Regression (HD-52 Medium): a DATE comparison must not 500 when the same issue also carries a
     * non-date TEXT custom field (a JSON string indistinguishable from a DATE by {@code jsonb_typeof}).
     * The ISO-date-shape guard in {@code jsonb_scalar_date} yields NULL for the arbitrary text sibling.
     */
    @Test
    void dateComparisonWithNonDateSiblingDoesNotError() throws Exception {
        var ctx = newProjectWithCustomFields();
        createIssue(ctx, "due", Map.of(
                ctx.dueOn, quoted("2026-06-15"),
                ctx.text, quoted("not-a-date at all")));
        createIssue(ctx, "textonly", Map.of(ctx.text, quoted("also not a date")));

        search(ctx, ctx.dueKey + " >= \"2026-01-01\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("due"));
    }

    @Test
    void selectByLabelAndById() throws Exception {
        var ctx = newProjectWithCustomFields();
        createIssue(ctx, "crit", Map.of(ctx.severity, quoted("critical")));
        createIssue(ctx, "minor", Map.of(ctx.severity, quoted("minor")));

        // by option label ("Critical") and by option id ("critical") both resolve
        search(ctx, ctx.sevKey + " = \"Critical\"")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("crit"));
        search(ctx, ctx.sevKey + " = \"critical\"")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("crit"));

        // unknown option → 422 semantic (not empty)
        search(ctx, ctx.sevKey + " = \"nope\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value(ctx.sevKey));
    }

    @Test
    void multiSelectContainsAndIn() throws Exception {
        var ctx = newProjectWithCustomFields();
        createIssue(ctx, "prodstaging", Map.of(ctx.environment, arr("production", "staging")));
        createIssue(ctx, "devonly", Map.of(ctx.environment, arr("dev")));

        // = means "contains"
        search(ctx, ctx.envKey + " = \"Production\"")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("prodstaging"));

        // IN means "any of"
        search(ctx, ctx.envKey + " IN (\"dev\", \"staging\")")
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].issue.title", hasItems("prodstaging", "devonly")));

        search(ctx, ctx.envKey + " = \"Dev\"")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("devonly"));
    }

    @Test
    void userFieldByCurrentUser() throws Exception {
        var ctx = newProjectWithCustomFields();
        createIssue(ctx, "mine", Map.of(ctx.owner, quoted(ctx.ownerUser.getId().toString())));
        createIssue(ctx, "unset", Map.of());

        search(ctx, ctx.ownerKey + " = currentUser()")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("mine"));

        // by email also resolves
        search(ctx, ctx.ownerKey + " = \"" + ctx.ownerUser.getEmail() + "\"")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("mine"));
    }

    // ============================================================ empty / not-equal

    @Test
    void isEmptyAndIsNotEmpty() throws Exception {
        var ctx = newProjectWithCustomFields();
        createIssue(ctx, "filled", Map.of(ctx.storyPoints, "3"));
        createIssue(ctx, "empty", Map.of());

        search(ctx, ctx.spKey + " IS EMPTY")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("empty"));
        search(ctx, ctx.spKey + " IS NOT EMPTY")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("filled"));
    }

    @Test
    void notEqualMatchesAbsentRowsToo() throws Exception {
        var ctx = newProjectWithCustomFields();
        createIssue(ctx, "eight", Map.of(ctx.storyPoints, "8"));
        createIssue(ctx, "five", Map.of(ctx.storyPoints, "5"));
        createIssue(ctx, "absent", Map.of());

        // != 8 must match "five" AND "absent" (an absent field is "not equal")
        search(ctx, ctx.spKey + " != 8")
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].issue.title", hasItems("five", "absent")))
                .andExpect(jsonPath("$.content[*].issue.title", not(hasItem("eight"))));
    }

    // ============================================================ tenancy / schema

    @Test
    void aCustomFieldOnlyInAnotherWorkspaceIsUnknownHere() throws Exception {
        var a = newProjectWithCustomFields();
        var b = newProjectWithCustomFields();
        createIssue(a, "x", Map.of(a.storyPoints, "1"));

        // b.spKey exists but is scoped to b's field set; a's search must not see it if
        // its key differs. Keys are unique per test run, so a query for b's key in a's
        // workspace is an unknown field (never a leak).
        search(a, b.spKey + " = 1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.detail", containsString("Unknown field")));
    }

    @Test
    void schemaIncludesCustomFieldsWithOptions() throws Exception {
        var ctx = newProjectWithCustomFields();
        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId + "/search/schema")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[*].name", hasItem(ctx.spKey)))
                .andExpect(jsonPath("$.fields[*].name", hasItem(ctx.sevKey)))
                .andExpect(jsonPath("$.fields[*].name", hasItem(ctx.envKey)))
                .andExpect(jsonPath("$.fields[*].name", hasItem(ctx.ownerKey)))
                .andReturn().getResponse().getContentAsString();
        var tree = json.readTree(body);
        // SELECT field publishes its options under a per-field value key.
        var sevSuggest = fieldValueSuggest(tree, ctx.sevKey);
        var opts = tree.get("values").get(sevSuggest);
        boolean hasCritical = false;
        for (var o : opts) if (o.get("label").asText().equals("Critical")) hasCritical = true;
        org.junit.jupiter.api.Assertions.assertTrue(hasCritical, "severity options missing in schema");
    }

    @Test
    void suggestResolvesUserCustomField() throws Exception {
        var ctx = newProjectWithCustomFields();
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId + "/search/suggest")
                        .param("field", ctx.ownerKey)
                        .param("q", ctx.ownerUser.getEmail().substring(0, 3))
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions[*].value", hasItem(ctx.ownerUser.getEmail())));
    }

    @Test
    void illegalOperatorOnCustomFieldReturns422() throws Exception {
        var ctx = newProjectWithCustomFields();
        // > is illegal on a SELECT field
        search(ctx, ctx.sevKey + " > \"Critical\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value(ctx.sevKey));
    }

    // ============================================================ helpers

    private String fieldValueSuggest(JsonNode schema, String fieldName) {
        for (var f : schema.get("fields")) {
            if (f.get("name").asText().equals(fieldName)) return f.get("valueSuggest").asText();
        }
        throw new AssertionError("field not in schema: " + fieldName);
    }

    private static String quoted(String s) {
        return "\"" + s + "\"";
    }

    private static String arr(String... ids) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(ids[i]).append("\"");
        }
        return sb.append("]").toString();
    }

    private record Ctx(UUID wsId, UUID projectId, User ownerUser, String token, JsonNode config,
                       UUID storyPoints, String spKey,
                       UUID severity, String sevKey,
                       UUID environment, String envKey,
                       UUID owner, String ownerKey,
                       UUID dueOn, String dueKey,
                       UUID text, String textKey) {
        UUID typeId(String name) {
            for (var t : config.get("issueTypes")) {
                if (t.get("name").asText().equals(name)) return UUID.fromString(t.get("id").asText());
            }
            throw new AssertionError("type not offered: " + name);
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

    private Ctx newProjectWithCustomFields() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);

        // Unique per run so cross-workspace key isolation is testable.
        String suffix = "_" + Math.abs(UUID.randomUUID().hashCode());
        var sp = fieldDef("sp" + suffix, "Story points" + suffix, FieldType.NUMBER, "{\"min\":0,\"max\":100}");
        var sev = fieldDef("sev" + suffix, "Severity" + suffix, FieldType.SELECT,
                "{\"options\":[{\"id\":\"critical\",\"label\":\"Critical\"},"
                        + "{\"id\":\"minor\",\"label\":\"Minor\"}]}");
        var env = fieldDef("env" + suffix, "Environment" + suffix, FieldType.MULTI_SELECT,
                "{\"options\":[{\"id\":\"production\",\"label\":\"Production\"},"
                        + "{\"id\":\"staging\",\"label\":\"Staging\"},{\"id\":\"dev\",\"label\":\"Dev\"}]}");
        var ownerField = fieldDef("own" + suffix, "Owner" + suffix, FieldType.USER, null);
        var due = fieldDef("due" + suffix, "Due on" + suffix, FieldType.DATE, null);
        var text = fieldDef("txt" + suffix, "Free text" + suffix, FieldType.TEXT, null);

        var set = new FieldSet();
        set.setName("Test set" + suffix);
        set = fieldSetRepository.save(set);
        item(set, sp, (short) 0);
        item(set, sev, (short) 1);
        item(set, env, (short) 2);
        item(set, ownerField, (short) 3);
        item(set, due, (short) 4);
        item(set, text, (short) 5);

        project.setFieldSet(set);
        project = projectRepository.save(project);

        var token = login(owner);
        var configBase = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config";
        var body = mockMvc.perform(get(configBase).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        return new Ctx(ws.getId(), project.getId(), owner, token, json.readTree(body),
                sp.getId(), sp.getKey(),
                sev.getId(), sev.getKey(),
                env.getId(), env.getKey(),
                ownerField.getId(), ownerField.getKey(),
                due.getId(), due.getKey(),
                text.getId(), text.getKey());
    }

    private FieldDef fieldDef(String key, String name, FieldType type, String configJson) throws Exception {
        var f = new FieldDef();
        f.setKey(key);
        f.setName(name);
        f.setType(type);
        if (configJson != null) f.setConfig(json.readTree(configJson));
        return fieldDefRepository.save(f);
    }

    private void item(FieldSet set, FieldDef field, short position) {
        var it = new FieldSetItem();
        it.setSet(set);
        it.setField(field);
        it.setPosition(position);
        it.setRequired(false);
        it.setShowOnCreate(true);
        fieldSetItemRepository.save(it);
    }

    /** Create an issue with an optional custom-field value map (raw JSON fragments keyed by field id). */
    private void createIssue(Ctx ctx, String title, Map<UUID, String> fields) throws Exception {
        var sb = new StringBuilder("{");
        sb.append("\"title\":\"").append(title).append("\",");
        sb.append("\"typeId\":\"").append(ctx.typeId("Task")).append("\",");
        sb.append("\"statusId\":\"").append(ctx.todoStatusId()).append("\"");
        if (!fields.isEmpty()) {
            sb.append(",\"fields\":{");
            boolean first = true;
            for (var e : fields.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            }
            sb.append("}");
        }
        sb.append("}");
        mockMvc.perform(post(ctx.issuesBase())
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.toString()))
                .andExpect(status().isCreated());
    }

    private ResultActions search(Ctx ctx, String query) throws Exception {
        var body = "{\"query\":" + json.writeValueAsString(query) + "}";
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId + "/search")
                .header("Authorization", "Bearer " + ctx.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ---- entity bootstrap ----

    private User user() {
        var u = new User();
        u.setEmail(("cf-" + System.nanoTime() + "@example.com").toLowerCase());
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

    private void member(Workspace ws, User u, WorkspaceRole role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(u);
        m.setRole(roleCatalog.reference(role));
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

    private void projectMember(Project project, User u, ProjectRole role) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(u);
        m.setRole(roleCatalog.reference(role));
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
