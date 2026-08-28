package com.hamstrack.issue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.entity.IssueHistory;
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
import com.hamstrack.workspace.service.RoleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-171 §4.2 / AC 6 — {@code issue_history.field} and the three things that keep it
 * honest.</strong>
 *
 * <p>{@code issue_history.field} holds <em>the name of the thing that changed</em>, and for a
 * custom field that name is copied straight out of {@code field_defs.name VARCHAR(100)}. The
 * column was {@code VARCHAR(50)} — half its own widest declared source — so a custom field named
 * 51–100 characters made <strong>every value change to that field</strong> answer 500 on
 * {@code PATCH …/issues/{number}}. {@code V24} widened the column and {@code IssueHistory.setField}
 * clips to the same number.
 *
 * <p><strong>Nothing mechanical keeps the column and the constant equal, which is why this file
 * exists.</strong> {@code ddl-auto=validate} does <em>not</em> catch a width drift: Hibernate's
 * schema validator compares JDBC <em>type codes</em> ({@code ColumnDefinitions.hasMatchingType});
 * {@code hasMatchingLength} is reached only from {@code ddl-auto=update}. A {@code VARCHAR(50)}
 * column against {@code length = 100} boots perfectly clean and 500s at INSERT — the exact bug.
 * So the guard is behavioural, and it takes three assertions that fail in three different
 * directions:
 *
 * <ol>
 *   <li><strong>the endpoint</strong> — a PATCH that changes a 100-character-named custom field
 *       answers 200 and leaves a history row. Fails if the column is <em>narrower</em> than the
 *       constant. Note it must be an <em>update</em>: create passes a no-op history listener, so a
 *       create-only test proves nothing at all;</li>
 *   <li><strong>the belt</strong>, asserted on the entity rather than through one service, because
 *       the rule is about every writer of that column and not about the one writer that found the
 *       bug;</li>
 *   <li><strong>width parity, both directions.</strong> Assertion 1 only catches a column narrower
 *       than the constant. Widen the column and forget the constant and assertion 1 stays green
 *       while the belt silently clips names the column could hold — so the width is read out of
 *       {@code information_schema} and compared to {@link IssueHistory#MAX_FIELD_LENGTH}.</li>
 * </ol>
 *
 * <p>There is deliberately no {@code V24MigrationTest}: {@code V24} transforms no data, and every
 * {@code @SpringBootTest} in this suite already boots Flyway from {@code V1}, so "the migration
 * applies" (AC 7) is asserted by the existence of this context. What a migration test could not
 * have told us is whether the two numbers still agree — that is assertion 3.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class IssueHistoryFieldWidthTest {

    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    @Autowired RoleCatalog roleCatalog;
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

    // ------------------------------------------------------------------ 1. the endpoint (AC 6)

    /**
     * The bug, end to end: a custom field whose name is exactly as long as
     * {@code UpsertFieldRequest.name} allows, and a PATCH that <em>changes its value</em>.
     *
     * <p>Both halves of the setup matter. A shorter name fits the old {@code VARCHAR(50)} and would
     * pass against the un-migrated schema; a <em>create</em> rather than an update writes no history
     * row at all ({@code IssueService} passes a no-op listener there), which is why the original
     * defect presented as "create works, update crashes".
     */
    @Test
    void changingA100CharacterNamedCustomFieldWritesHistoryInsteadOf500() throws Exception {
        var ctx = fixture();

        var number = createIssue(ctx, "first value");

        mockMvc.perform(patch(ctx.issues() + "/" + number)
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":{\"" + ctx.fieldId() + "\":\"second value\"}}"))
                .andExpect(status().isOk());

        assertThat(historyNewValue(ctx.fieldName()))
                .as("the history row for a field named %d characters must exist. This is the only"
                    + " assertion in the suite that fails if V24 is missing or the column has"
                    + " drifted narrower than IssueHistory.MAX_FIELD_LENGTH — ddl-auto=validate"
                    + " compares type codes, not widths, so the boot is clean either way.",
                        ctx.fieldName().length())
                .isNotNull()
                .contains("second value");
    }

    /** The stored history row for one field name, read past JPA so no cache can answer for it. */
    private String historyNewValue(String field) throws SQLException {
        try (var conn = dataSource.getConnection();
             var st = conn.prepareStatement(
                     "SELECT new_value FROM issue_history WHERE field = ? ORDER BY created_at DESC LIMIT 1")) {
            st.setString(1, field);
            try (var rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ------------------------------------------------------------------ 2. the belt, on the entity

    /**
     * The truncation belt lives on {@code IssueHistory.setField} — the column — rather than on the
     * one writer that found the bug, so every writer inherits it. Asserted directly on the entity
     * for the same reason: a test that drove it through {@code IssueService} would be a claim about
     * that service.
     */
    @Test
    void setFieldClipsAnOverLongNameToTheColumnWidth() {
        var history = new IssueHistory();

        history.setField("x".repeat(120));

        assertThat(history.getField()).hasSize(IssueHistory.MAX_FIELD_LENGTH);
    }

    /**
     * <strong>And it steps back off a surrogate pair rather than splitting one.</strong> A naive
     * {@code substring(0, 100)} is already safe in the direction that matters (Java counts UTF-16
     * units, PostgreSQL counts code points, so the clip can never overshoot the column) — the
     * residual is a <em>lone high surrogate</em> at the last index, which pgjdbc's encoder replaces
     * with {@code ?}: the last character is silently mangled and nothing raises. Unreachable
     * through today's DTO, which is precisely what a belt is for.
     */
    @Test
    void setFieldNeverStoresALoneHighSurrogate() {
        // 99 plain units, then an astral character whose two halves land on units 99 and 100.
        var name = "x".repeat(99) + "😀" + "tail";

        var history = new IssueHistory();
        history.setField(name);

        assertThat(history.getField())
                .as("clipping at 100 would cut the pair in half and store a lone high surrogate, "
                    + "which the driver turns into '?' with no error anywhere")
                .hasSize(IssueHistory.MAX_FIELD_LENGTH - 1);
        assertThat(Character.isHighSurrogate(history.getField().charAt(history.getField().length() - 1)))
                .as("the stored value must not end in half a code point")
                .isFalse();
    }

    // ------------------------------------------------------------------ 3. width parity

    /**
     * <strong>Both directions, in two lines.</strong> AC 6 above catches a column narrower than the
     * constant. This catches the other drift — a column widened without the constant, which leaves
     * AC 6 green while the belt quietly clips names the column would have held — and it is the
     * assertion the migration review asked for in place of a {@code V24MigrationTest}.
     */
    @Test
    void theColumnIsExactlyAsWideAsTheConstantSaysItIs() throws SQLException {
        assertThat(columnWidth("issue_history", "field"))
                .as("""
                        issue_history.field and IssueHistory.MAX_FIELD_LENGTH have drifted apart. \
                        NOTHING ELSE CATCHES THIS: ddl-auto=validate compares JDBC type codes and \
                        never lengths (hasMatchingLength is reached only from ddl-auto=update), so \
                        a mismatch in either direction boots perfectly clean. Narrower than the \
                        constant is a 500 at INSERT; wider is a silent clip of names the column \
                        could have stored. Change both, in one commit, with a migration.""")
                .isEqualTo(IssueHistory.MAX_FIELD_LENGTH);
    }

    private int columnWidth(String table, String column) throws SQLException {
        try (var conn = dataSource.getConnection();
             var st = conn.prepareStatement("""
                     SELECT character_maximum_length FROM information_schema.columns
                     WHERE table_name = ? AND column_name = ?""")) {
            st.setString(1, table);
            st.setString(2, column);
            try (var rs = st.executeQuery()) {
                assertThat(rs.next()).as("no such column: %s.%s", table, column).isTrue();
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------------------- fixture

    private record Ctx(UUID wsId, UUID projectId, String token, UUID fieldId, String fieldName,
                       UUID typeId, UUID statusId) {
        String issues() {
            return "/api/workspaces/" + wsId + "/projects/" + projectId + "/issues";
        }
    }

    private Ctx fixture() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner);
        var project = project(ws, owner);
        projectMember(project, owner);

        var suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // Exactly 100 characters: what UpsertFieldRequest.name accepts and field_defs.name holds.
        var name = ("Custom field " + suffix + " ").repeat(10);
        name = name.substring(0, 100);

        var field = new FieldDef();
        field.setKey("hd171_" + suffix);
        field.setName(name);
        field.setType(FieldType.TEXT);
        field = fieldDefRepository.save(field);

        var set = new FieldSet();
        set.setName("HD-171 set " + suffix);
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
        var config = json.readTree(mockMvc.perform(get("/api/workspaces/" + ws.getId()
                                + "/projects/" + project.getId() + "/config")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        UUID typeId = null;
        for (var t : config.get("issueTypes")) {
            if (t.get("name").asText().equals("Task")) typeId = UUID.fromString(t.get("id").asText());
        }
        UUID statusId = null;
        for (var s : config.get("statuses")) {
            if (s.get("category").asText().equals("TODO") && statusId == null) {
                statusId = UUID.fromString(s.get("id").asText());
            }
        }

        assertThat(name).hasSize(100);
        return new Ctx(ws.getId(), project.getId(), token, field.getId(), name, typeId, statusId);
    }

    private int createIssue(Ctx ctx, String value) throws Exception {
        var body = mockMvc.perform(post(ctx.issues())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"HD-171\",\"typeId\":\"" + ctx.typeId()
                                 + "\",\"statusId\":\"" + ctx.statusId()
                                 + "\",\"fields\":{\"" + ctx.fieldId() + "\":\"" + value + "\"}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("number").asInt();
    }

    private User user() {
        var u = new User();
        u.setEmail(("hist-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("History Test");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("WS");
        w.setSlug("hist-" + UUID.randomUUID().toString().substring(0, 8) + "-" + (System.nanoTime() % 100000));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    private void member(Workspace ws, User u) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(u);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "OWNER"));
        workspaceMemberRepository.save(m);
    }

    private Project project(Workspace ws, User creator) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("Proj");
        p.setKey("H" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(creator);
        return projectRepository.save(p);
    }

    private void projectMember(Project project, User u) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(u);
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
