package com.hamstrack.issue;

import com.hamstrack.common.security.RoleScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.storage.FileStorage;
import com.hamstrack.issue.repository.IssueAttachmentRepository;
import com.hamstrack.issue.repository.IssueRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-83 (audit Q-1) — attachment CRUD happy paths + the tenancy invariant, using
 * the REAL {@link com.hamstrack.common.storage.LocalFileStorage} against a temp
 * base-dir (unlike {@code AttachmentUploadFailureTest}, which mocks storage to drive
 * a store failure). Covers download bytes/headers, list, delete-removes-blob, and the
 * top bug class: a non-member of the workspace gets 404 on every attachment operation
 * against another tenant's issue.
 *
 * <p>The storage base-dir is a per-JVM temp directory wired via
 * {@link DynamicPropertySource} so blobs are asserted directly on disk and never touch
 * the developer's {@code ./data/attachments}. Bootstrapped through the repository layer
 * like {@code IssueClosedAtTest} / {@code AttachmentUploadFailureTest}.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AttachmentCrudTenancyTest {

    // Temp storage root for this test JVM; LocalFileStorage writes blobs under it.
    static final Path STORAGE_DIR;
    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("hamstrack-attach-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("app.storage.type", () -> "local");
        registry.add("app.storage.local.base-dir", () -> STORAGE_DIR.toString());
    }

    @Autowired MockMvc mockMvc;
    // HD-123: memberships carry a roles row now; reference() resolves a built-in with no query.
    @Autowired com.hamstrack.workspace.service.RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired IssueRepository issueRepository;
    @Autowired IssueAttachmentRepository attachmentRepository;
    @Autowired FileStorage fileStorage; // real LocalFileStorage
    @Autowired PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    // ============================================================ happy paths

    @Test
    void downloadReturnsBytesFilenameServerContentTypeAndDisposition() throws Exception {
        var ctx = newProject();
        createIssue(ctx);
        byte[] bytes = "the quick brown fox".getBytes();
        // Deliberately send a bogus client Content-Type — the server must derive its
        // own from the .txt filename, never echo the client header.
        var file = new MockMultipartFile("file", "report.txt", "application/x-evil", bytes);
        var uploaded = json.readTree(mockMvc.perform(multipart(attachBase(ctx))
                        .file(file)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        var attachmentId = uploaded.get("id").asText();

        var res = mockMvc.perform(get(attachBase(ctx) + "/" + attachmentId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                // server-derived Content-Type from the filename, not the client's
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("report.txt")))
                .andReturn();

        assertThat(java.util.Arrays.equals(res.getResponse().getContentAsByteArray(), bytes))
                .withFailMessage("download must return the exact stored bytes")
                .isTrue();
    }

    @Test
    void listReturnsUploadedAttachments() throws Exception {
        var ctx = newProject();
        createIssue(ctx);
        upload(ctx, "a.txt", "alpha".getBytes());
        upload(ctx, "b.pdf", "%PDF-1.4 body".getBytes());

        mockMvc.perform(get(attachBase(ctx))
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.filename=='a.txt')]").exists())
                .andExpect(jsonPath("$[?(@.filename=='b.pdf')]").exists());
    }

    @Test
    void deleteRemovesDbRowAndStoredBlob() throws Exception {
        var ctx = newProject();
        var issueId = createIssue(ctx);
        var uploaded = upload(ctx, "gone.txt", "delete me".getBytes());
        var attachmentId = UUID.fromString(uploaded.get("id").asText());

        // Blob exists on disk before delete.
        var storageKey = attachmentRepository.findById(attachmentId).orElseThrow().getStorageKey();
        assertThat(blobExists(storageKey))
                .withFailMessage("precondition: the blob must exist on disk after upload")
                .isTrue();

        mockMvc.perform(delete(attachBase(ctx) + "/" + attachmentId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNoContent());

        // DB row gone.
        assertThat(attachmentRepository.findById(attachmentId)).as("delete must remove the attachment DB row").isEmpty();
        // Blob gone from LocalFileStorage (deletion is registered afterCommit; the tx
        // has committed by the time the response returns).
        assertThat(blobExists(storageKey))
                .withFailMessage("delete must remove the stored blob from LocalFileStorage")
                .isFalse();
        // sanity: issue still exists
        assertThat(issueRepository.findById(issueId))
                .as("the issue survived: deleting an attachment removes its row and blob, not its issue")
                .isPresent();
    }

    /**
     * <strong>Deleting an issue that HAS an attachment must still be a 204</strong> — the
     * one step of the delete path nothing in the suite covered (HD-137 R4 item 2). Every
     * other issue-delete test uses an attachment-free issue, and this class deleted
     * attachments but never their issue, so the whole neighbourhood of
     * {@code IssueService.delete}'s {@code removeStoredFilesForIssue} call was untested.
     *
     * <p><strong>It found a live bug, and it settled a disagreement about why.</strong>
     * {@code removeStoredFilesForIssue} loaded managed {@code IssueAttachment} entities
     * whose {@code issue} points at the entity about to be removed and did NOT detach them
     * — while {@code SprintScopeLedger.recordDepartureBeforeDelete}, running immediately
     * before it, carefully does. Two readings of the mechanism disagreed about whether the
     * detach is what saves it or merely belt-and-braces (the DELETE has not executed yet,
     * so "does the parent row exist" would say yes). This test answered: written first, it
     * failed at {@code checkForTransientReferences} with
     * {@code TransientPropertyValueException} — Hibernate treats an entity whose entry is
     * deleted-or-gone as transient without asking the database — which means <em>deleting
     * any issue that had at least one attachment was a 500 and a rollback</em>, on a path
     * nothing in the suite covered. The detach is load-bearing; the attachment path now
     * reads storage keys as SCALARS so it has nothing to detach at all.
     */
    @Test
    void deletingAnIssueThatHasAnAttachmentSucceeds() throws Exception {
        var ctx = newProject();
        var issueId = createIssue(ctx);
        var uploaded = upload(ctx, "goes-with-it.txt", "attached bytes".getBytes());
        var attachmentId = UUID.fromString(uploaded.get("id").asText());
        var storageKey = attachmentRepository.findById(attachmentId).orElseThrow().getStorageKey();
        assertThat(blobExists(storageKey)).withFailMessage("precondition: the blob is on disk before the delete").isTrue();

        mockMvc.perform(delete("/api/workspaces/" + ctx.wsId + "/projects/" + ctx.projectId
                                + "/issues/1")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNoContent());

        assertThat(issueRepository.findById(issueId)).as("the issue must be gone").isEmpty();
        assertThat(attachmentRepository.findById(attachmentId)).as("its attachment row must go with it").isEmpty();
        assertThat(blobExists(storageKey))
                .withFailMessage("and so must the blob — removeStoredFilesForIssue is what the issue "
                  + "delete path calls instead of the per-attachment delete")
                .isFalse();
    }

    // ============================================================ tenancy (top bug class)

    @Test
    void nonMemberCannotUploadListDownloadOrDeleteAnotherTenantsAttachment() throws Exception {
        // Tenant A owns an issue with an attachment.
        var a = newProject();
        createIssue(a);
        var uploaded = upload(a, "secret.txt", "tenant A data".getBytes());
        var attachmentId = uploaded.get("id").asText();

        // User B: authenticated, member of nothing in A's tenant.
        var bToken = login(user());
        var base = attachBase(a);

        // upload -> 404 (workspace "not found" from B's vantage, never 403)
        mockMvc.perform(multipart(base)
                        .file(new MockMultipartFile("file", "evil.txt", "text/plain", "x".getBytes()))
                        .header("Authorization", "Bearer " + bToken))
                .andExpect(status().isNotFound());

        // list -> 404
        mockMvc.perform(get(base).header("Authorization", "Bearer " + bToken))
                .andExpect(status().isNotFound());

        // download -> 404
        mockMvc.perform(get(base + "/" + attachmentId).header("Authorization", "Bearer " + bToken))
                .andExpect(status().isNotFound());

        // delete -> 404, and A's row must survive
        mockMvc.perform(delete(base + "/" + attachmentId).header("Authorization", "Bearer " + bToken))
                .andExpect(status().isNotFound());
        assertThat(attachmentRepository.findById(UUID.fromString(attachmentId)))
                .as("a non-member's delete must not remove another tenant's attachment row")
                .isPresent();
    }

    /**
     * The other half of {@link #deletingAnIssueThatHasAnAttachmentSucceeds()} (HD-137 R5
     * item 3). That test proves the deleted issue's own blob <em>goes</em>; nothing proved
     * that everyone else's <em>stays</em> — and the two failures are not symmetric here.
     *
     * <p>{@code AttachmentService.removeStoredFilesForIssue} takes a bare {@code Issue} and
     * authorizes nothing, so its scoping lives entirely in
     * {@code IssueAttachmentRepository.findStorageKeysByIssue}'s predicate. Widen that
     * predicate — an accidental cross join, a {@code findAll}, a "cleanup" query keyed on
     * something coarser than the issue — and the ordinary consequence of a tenancy bug does
     * not apply: this path does not leak another tenant's data, it <strong>destroys</strong>
     * it, after commit, inside a handler that swallows storage failures with a warn. There
     * is no recovery and no error, which is why the guard is a blob assertion on disk rather
     * than a row count.
     */
    @Test
    void deletingOneTenantsIssueLeavesAnotherTenantsBlobOnDisk() throws Exception {
        var a = newProject();
        createIssue(a);
        var aKey = attachmentRepository
                .findById(UUID.fromString(upload(a, "a.txt", "tenant A bytes".getBytes()).get("id").asText()))
                .orElseThrow().getStorageKey();

        var b = newProject();
        var bIssueId = createIssue(b);
        var bAttachmentId = UUID.fromString(upload(b, "b.txt", "tenant B bytes".getBytes()).get("id").asText());
        var bKey = attachmentRepository.findById(bAttachmentId).orElseThrow().getStorageKey();
        assertThat(blobExists(aKey)).withFailMessage("precondition: both tenants' blobs are on disk").isTrue();
        assertThat(blobExists(bKey)).withFailMessage("precondition: both tenants' blobs are on disk").isTrue();

        mockMvc.perform(delete("/api/workspaces/" + a.wsId + "/projects/" + a.projectId + "/issues/1")
                        .header("Authorization", "Bearer " + a.token))
                .andExpect(status().isNoContent());

        assertThat(blobExists(aKey)).withFailMessage("precondition on the delete itself: A's own blob is gone").isFalse();
        assertThat(blobExists(bKey))
                .withFailMessage("workspace B's blob must survive workspace A's issue delete — a widened "
                  + "findStorageKeysByIssue predicate destroys files rather than leaking them")
                .isTrue();
        assertThat(attachmentRepository.findById(bAttachmentId)).as("and B's attachment row must survive too").isPresent();
        assertThat(issueRepository.findById(bIssueId)).as("as must B's issue").isPresent();
    }

    // ============================================================ helpers

    private boolean blobExists(String storageKey) {
        return Files.exists(STORAGE_DIR.resolve(storageKey));
    }

    private JsonNode upload(Ctx ctx, String filename, byte[] bytes) throws Exception {
        var body = mockMvc.perform(multipart(attachBase(ctx))
                        .file(new MockMultipartFile("file", filename, "application/octet-stream", bytes))
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String attachBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId + "/projects/" + ctx.projectId + "/issues/1/attachments";
    }

    private UUID createIssue(Ctx ctx) throws Exception {
        var body = "{\"title\":\"issue\",\"typeId\":\"" + ctx.typeId("Task")
                + "\",\"statusId\":\"" + ctx.todoStatusId() + "\"}";
        var resp = mockMvc.perform(post("/api/workspaces/" + ctx.wsId + "/projects/" + ctx.projectId + "/issues")
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asText());
    }

    private record Ctx(UUID wsId, UUID projectId, String token, JsonNode config) {
        UUID typeId(String name) {
            return find(config.get("issueTypes"), "name", name);
        }
        UUID todoStatusId() {
            for (var s : config.get("statuses")) {
                if (s.get("category").asText().equals("TODO")) {
                    return UUID.fromString(s.get("id").asText());
                }
            }
            throw new AssertionError("no TODO-category status in workflow");
        }
        private static UUID find(JsonNode arr, String field, String value) {
            for (var n : arr) {
                if (n.get(field).asText().equals(value)) return UUID.fromString(n.get("id").asText());
            }
            throw new AssertionError(field + "=" + value + " not offered by project");
        }
    }

    private Ctx newProject() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner);
        var project = project(ws, owner);
        projectMember(project, owner);
        var token = login(owner);
        var configBase = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config";
        var body = mockMvc.perform(get(configBase).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(ws.getId(), project.getId(), token, json.readTree(body));
    }

    // ---- entity bootstrap (mirrors IssueClosedAtTest) ----

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
