package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
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

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration coverage for HD-21 (Advanced Search / HQL execution engine),
 * mirroring the acceptance checklist in docs/design/advanced-search-hql-proposal.md
 * §14. Data is bootstrapped through the repository layer (like the other integration
 * tests) and every status/type/priority id is read from the public project-config
 * endpoint — nothing about the catalog is hardcoded.
 *
 * <p>Covers: the canonical epic query (workspace-scoped, priority-sorted),
 * {@code currentUser()} resolution, tenant isolation (non-member 404, cross-workspace
 * cannot be widened by a crafted query), parse vs semantic 422 shapes, injection-safe
 * parameter binding, pagination/size-cap, {@code IS EMPTY}, and {@code ~} text match.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SearchApiTest {

    @Autowired MockMvc mockMvc;
    // HD-123: memberships carry a roles row now; reference() resolves a built-in with no query.
    @Autowired com.hamstrack.workspace.service.RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    // ============================================================ canonical / sort

    @Test
    void canonicalEpicQueryReturnsInProgressAssignedToCallerSortedByPriority() throws Exception {
        var ctx = newProject();
        // In Progress + assigned to caller, two priorities (Urgent should sort first DESC).
        var urgent = createIssue(ctx, "Task", "urgent one",
                assignee(ctx.owner.getId()), withStatus(ctx, "In Progress"), priority(ctx, "Urgent"));
        var low = createIssue(ctx, "Task", "low one",
                assignee(ctx.owner.getId()), withStatus(ctx, "In Progress"), priority(ctx, "Low"));
        // Noise that must NOT match: unassigned, and assigned-but-not-in-progress.
        createIssue(ctx, "Task", "unassigned in progress", withStatus(ctx, "In Progress"));
        createIssue(ctx, "Task", "assigned todo", assignee(ctx.owner.getId()));

        search(ctx.wsId, ctx.token,
                "status = \"In Progress\" AND assignee = currentUser() ORDER BY priority DESC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].issue.title").value("urgent one"))
                .andExpect(jsonPath("$.content[1].issue.title").value("low one"))
                // rows self-identify their project
                .andExpect(jsonPath("$.content[0].projectId").value(ctx.projectId.toString()))
                .andExpect(jsonPath("$.content[0].projectName").exists());
    }

    @Test
    void emptyQueryReturnsAllVisibleIssuesDefaultSort() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "a");
        createIssue(ctx, "Task", "b");

        search(ctx.wsId, ctx.token, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    // ============================================================ tenancy

    @Test
    void nonMemberGets404NotForbidden() throws Exception {
        var ctx = newProject();
        var outsider = login(user());
        search(ctx.wsId, outsider, "")
                .andExpect(status().isNotFound());
    }

    @Test
    void crossWorkspaceIssuesAreNeverReturnedEvenWithCraftedQuery() throws Exception {
        // Two independent workspaces, each with the SAME caller as a member of A only.
        var a = newProject();
        var b = newProject();
        createIssue(a, "Task", "A-only issue");
        createIssue(b, "Task", "B-only issue");

        // A member of A searching A must see only A's issue, never B's — regardless of
        // any predicate. The scope predicate is the hard boundary.
        search(a.wsId, a.token, "text ~ \"issue\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].issue.title", hasItem("A-only issue")))
                .andExpect(jsonPath("$.content[*].issue.title", not(hasItem("B-only issue"))));

        // An OR that tries to widen scope cannot escape the outermost AND(scope).
        search(a.wsId, a.token, "text ~ \"issue\" OR text ~ \"issue\"")
                .andExpect(jsonPath("$.content[*].issue.title", not(hasItem("B-only issue"))));
    }

    @Test
    void injectionShapedInputIsBoundAsLiteralAndMatchesNothingHarmful() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "normal issue");

        // Injection-shaped text term: quotes, %, and a SQL comment. Must be bound as a
        // literal ILIKE term (matches nothing), never break out — 200, empty content.
        search(ctx.wsId, ctx.token, "text ~ \"'; DROP TABLE issues; --\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        // % must be escaped, so it matches literally (no rows contain a literal %).
        search(ctx.wsId, ctx.token, "text ~ \"%\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        // The table still exists — a normal search still works.
        search(ctx.wsId, ctx.token, "text ~ \"normal\"")
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ============================================================ errors

    @Test
    void parseErrorReturns422WithPosition() throws Exception {
        var ctx = newProject();
        // Unquoted bare value → parse error with a position.
        search(ctx.wsId, ctx.token, "status = In Progress")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("PARSE_ERROR"))
                .andExpect(jsonPath("$.position").exists());
    }

    @Test
    void oversizedQueryReturns400AtBinding() throws Exception {
        var ctx = newProject();
        // > 2000 chars → rejected by @Size at binding (clean 400), never parsed.
        search(ctx.wsId, ctx.token, "\"" + "a".repeat(2100) + "\"")
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownFieldReturns422SemanticWithField() throws Exception {
        var ctx = newProject();
        search(ctx.wsId, ctx.token, "statuss = \"To Do\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("statuss"))
                .andExpect(jsonPath("$.detail", containsString("Unknown field")));
    }

    @Test
    void unknownStatusNameReturns422NotEmpty() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "x");
        search(ctx.wsId, ctx.token, "status = \"Nonexistent\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("status"));
    }

    @Test
    void illegalOperatorForFieldReturns422Semantic() throws Exception {
        var ctx = newProject();
        // ~ is only legal on text.
        search(ctx.wsId, ctx.token, "status ~ \"To Do\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("status"));
    }

    @Test
    void isEmptyOnNonNullableFieldReturns422() throws Exception {
        var ctx = newProject();
        search(ctx.wsId, ctx.token, "status IS EMPTY")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("status"));
    }

    /**
     * HD-30 activated {@code label} (the last remaining not-available stub in
     * {@link FieldRegistry} — every descriptor is now {@code available = true}), so the
     * former "not yet queryable" assertion has no field left to point at. What must
     * still hold is the distinction the old test protected: a KNOWN field whose value
     * cannot be resolved is a value-level semantic error anchored on the field, not
     * "Unknown field" (which is what a typo gets, see
     * {@link #unknownFieldReturns422SemanticWithField()}).
     *
     * <p>If a not-available stub is ever registered again, add a case here for it.
     */
    @Test
    void liveLabelFieldWithUnresolvableValueReturns422SemanticNotUnknownField() throws Exception {
        var ctx = newProject();
        search(ctx.wsId, ctx.token, "label = \"x\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("label"))
                .andExpect(jsonPath("$.detail", containsString("No label named 'x'")))
                .andExpect(jsonPath("$.detail", not(containsString("not yet queryable"))))
                .andExpect(jsonPath("$.detail", not(containsString("Unknown field"))));
    }

    // ============================================================ semantics

    @Test
    void isEmptyAndIsNotEmptyOnAssignee() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "assigned", assignee(ctx.owner.getId()));
        createIssue(ctx, "Task", "unassigned");

        search(ctx.wsId, ctx.token, "assignee IS EMPTY")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("unassigned"));
        search(ctx.wsId, ctx.token, "assignee IS NOT EMPTY")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("assigned"));
    }

    @Test
    void textMatchIsCaseInsensitiveContains() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "Login Bug on checkout");
        createIssue(ctx, "Task", "unrelated");

        search(ctx.wsId, ctx.token, "text ~ \"login bug\"")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("Login Bug on checkout"));
    }

    @Test
    void validButNoMatchReturns200EmptyContent() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "x", withStatus(ctx, "In Progress"));
        // Valid, resolvable, but nothing is Done.
        search(ctx.wsId, ctx.token, "status = \"Done\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ============================================================ pagination

    @Test
    void paginationPagesAndCapsSize() throws Exception {
        var ctx = newProject();
        for (int i = 0; i < 5; i++) createIssue(ctx, "Task", "issue " + i);

        // size 2 → 3 pages, first page has 2, hasNext true
        search(ctx.wsId, ctx.token, "", 0, 2)
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
        // last page
        search(ctx.wsId, ctx.token, "", 2, 2)
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
        // size over the cap clamps to 100 (still one page for 5 rows)
        search(ctx.wsId, ctx.token, "", 0, 9999)
                .andExpect(jsonPath("$.size").value(100));
    }

    // ============================================================ schema / suggest

    @Test
    void schemaListsFieldsOperatorsAndValuePicklists() throws Exception {
        var ctx = newProject();
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId + "/search/schema")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[*].name", hasItem("status")))
                // HD-30: label is live now, so /schema DOES advertise it …
                .andExpect(jsonPath("$.fields[*].name", hasItem("label")))
                // … exactly once: `labels` is an ALIAS pointing at the same descriptor
                // instance and availableFields() de-duplicates by identity, so neither
                // a second "label" entry nor a "labels" entry may appear.
                .andExpect(jsonPath("$.fields[?(@.name == 'label')]", hasSize(1)))
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("labels"))))
                // and the LABEL value picklist exists (empty here — no labels yet)
                .andExpect(jsonPath("$.values.LABEL").exists())
                .andExpect(jsonPath("$.values.STATUS", not(empty())))
                .andExpect(jsonPath("$.keywords", hasItem("ORDER BY")));
    }

    @Test
    void schema404ForNonMember() throws Exception {
        var ctx = newProject();
        var outsider = login(user());
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId + "/search/schema")
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isNotFound());
    }

    @Test
    void suggestReturnsWorkspaceMembersForAssignee() throws Exception {
        var ctx = newProject();
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId + "/search/suggest")
                        .param("field", "assignee")
                        .param("q", ctx.owner.getEmail().substring(0, 3))
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.field").value("assignee"))
                .andExpect(jsonPath("$.suggestions[*].value", hasItem(ctx.owner.getEmail())));
    }

    // ============================================================ helpers

    private record Ctx(UUID wsId, UUID projectId, String projectKey, User owner, String token, JsonNode config) {
        UUID typeId(String name) {
            for (var t : config.get("issueTypes")) {
                if (t.get("name").asText().equals(name)) return UUID.fromString(t.get("id").asText());
            }
            throw new AssertionError("type not offered: " + name);
        }
        UUID statusId(String name) {
            for (var s : config.get("statuses")) {
                if (s.get("name").asText().equals(name)) return UUID.fromString(s.get("id").asText());
            }
            throw new AssertionError("status not in workflow: " + name);
        }
        UUID priorityId(String name) {
            for (var p : config.get("priorities")) {
                if (p.get("name").asText().equals(name)) return UUID.fromString(p.get("id").asText());
            }
            throw new AssertionError("priority not offered: " + name);
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

    private record IssueRef(String id, long number, String key) {}

    private Ctx newProject() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);
        var configBase = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config";
        var body = mockMvc.perform(get(configBase).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(ws.getId(), project.getId(), project.getKey(), owner, token, json.readTree(body));
    }

    // ---- issue creation ----

    private String assignee(UUID id) {
        return "\"assigneeId\":\"" + id + "\"";
    }

    private String withStatus(Ctx ctx, String name) {
        return "\"statusId\":\"" + ctx.statusId(name) + "\"";
    }

    private String priority(Ctx ctx, String name) {
        return "\"priorityId\":\"" + ctx.priorityId(name) + "\"";
    }

    private IssueRef createIssue(Ctx ctx, String typeName, String title, String... extra) throws Exception {
        var parts = new java.util.ArrayList<String>();
        parts.add("\"title\":\"" + title + "\"");
        parts.add("\"typeId\":\"" + ctx.typeId(typeName) + "\"");
        // Default the status to TO DO unless an extra fragment already sets statusId.
        boolean hasStatus = false;
        for (var e : extra) if (e.contains("statusId")) hasStatus = true;
        if (!hasStatus) parts.add("\"statusId\":\"" + ctx.todoStatusId() + "\"");
        for (var e : extra) parts.add(e);
        var body = "{" + String.join(",", parts) + "}";
        var resp = mockMvc.perform(post(ctx.issuesBase())
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(resp);
        return new IssueRef(node.get("id").asText(), node.get("number").asLong(), node.get("key").asText());
    }

    // ---- search ----

    private ResultActions search(UUID wsId, String token, String query) throws Exception {
        return search(wsId, token, query, null, null);
    }

    private ResultActions search(UUID wsId, String token, String query, Integer page, Integer size) throws Exception {
        var body = new StringBuilder("{\"query\":").append(json.writeValueAsString(query));
        if (page != null) body.append(",\"page\":").append(page);
        if (size != null) body.append(",\"size\":").append(size);
        body.append("}");
        return mockMvc.perform(post("/api/workspaces/" + wsId + "/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()));
    }

    // ---- entity bootstrap ----

    private User user() {
        var u = new User();
        u.setEmail(("u-" + System.nanoTime() + "@example.com").toLowerCase());
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

    private void member(Workspace ws, User user, WorkspaceRole role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
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

    private void projectMember(Project project, User user, ProjectRole role) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
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
