package com.hamstrack.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>Tenancy and authorization on the two storage reads</strong> (HD-191, AC-15/16/17).
 *
 * <p>This is the project's top bug class, so the assertions are about what a caller is told rather
 * than about what they get back:
 *
 * <ul>
 *   <li>an unknown workspace and a workspace the caller is not a member of answer the <strong>same
 *       404</strong>, byte for byte — a difference of any kind is an existence oracle across
 *       tenants;</li>
 *   <li>the breakdown answers <strong>403 only for a proven member</strong> who lacks
 *       {@code workspace.edit}, and 404 for everybody else — the order is what keeps a 403 from
 *       ever being reachable by a stranger;</li>
 *   <li>the breakdown never names a project of another workspace, whatever it is asked.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.storage.quota.enabled=true",
        "app.storage.quota.workspace-bytes=100000B",
        "app.attachments.max-file-size=9000B",
        "app.write.upload-bytes-per-minute=90000B",
        "app.storage.quota.reconcile-cron="
})
@AutoConfigureMockMvc
class WorkspaceStorageApiTest extends StorageTestBase {

    static final Path STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("hamstrack-storage-api-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("app.storage.type", () -> "local");
        registry.add("app.storage.local.base-dir", STORAGE_DIR::toString);
    }

    /**
     * AC-15. The two responses are compared as STRINGS rather than only by status, because "both
     * are 404" is the weaker half of the claim: a body that named the workspace in one case and not
     * the other would satisfy a status assertion and still publish which ids exist.
     */
    @Test
    void aNonMemberAndAnUnknownWorkspaceGetTheSame404() throws Exception {
        var mine = newProject();
        var theirs = newProject();

        var nonMember = mockMvc.perform(get("/api/workspaces/" + theirs.wsId() + "/storage")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        var unknown = mockMvc.perform(get("/api/workspaces/" + UUID.randomUUID() + "/storage")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // `instance` is the request URI, so it necessarily echoes the id the caller themselves
        // sent — it distinguishes the two requests, never the two workspaces. Everything else in
        // the body must be identical.
        assertThat(withoutInstance(nonMember))
                .as("""
                    THE TWO 404s DIFFER, WHICH MAKES THIS ENDPOINT AN EXISTENCE ORACLE.

                    "Workspace not found" and "you are not a member of this workspace" must be \
                    indistinguishable from outside. Any difference — a different detail, a \
                    different extension, an echoed id — lets a caller enumerate which workspace \
                    ids exist on the instance, which is the whole reason this project answers 404 \
                    where a naive design answers 403.""")
                .isEqualTo(withoutInstance(unknown));
    }

    private static String withoutInstance(String problemJson) {
        return problemJson.replaceAll("\"instance\":\"[^\"]*\",?", "");
    }

    /** AC-16, with the same 404-before-403 ordering on the gated sibling. */
    @Test
    void theBreakdownIs403ForAMemberWithoutWorkspaceEditAnd404ForAStranger() throws Exception {
        var ctx = newProject();
        var stranger = newProject();

        // A proven member of this workspace who holds no workspace-wide settings grant.
        var plainMember = user();
        member(workspaceRepository.findById(ctx.wsId()).orElseThrow(), plainMember, "MEMBER");
        var memberToken = login(plainMember);

        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/storage/projects")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());

        // ...and the summary is open to exactly that person, which is the asymmetry the design
        // rests on: the figures a refusal would hand them are not a secret from them.
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/storage")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/storage/projects")
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isNotFound());
    }

    /** AC-16's second half and AC-17: the breakdown is grouped from rows the tenant predicate already restricted. */
    @Test
    void theBreakdownNamesOnlyProjectsOfTheWorkspaceAsked() throws Exception {
        var a = newProject();
        var second = secondProject(a);
        var b = newProject();

        upload(a, createIssue(a), "a.txt", bytesOf(4000));
        upload(second, createIssue(second), "s.txt", bytesOf(1000));
        upload(b, createIssue(b), "b.txt", bytesOf(7000));

        var body = mockMvc.perform(get("/api/workspaces/" + a.wsId() + "/storage/projects")
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBytes").value(5000))
                // The counter total minus what the rows attribute — published, never normalised
                // away, because a non-zero value is the drift this page exists to show.
                .andExpect(jsonPath("$.unattributedBytes").value(0))
                .andExpect(jsonPath("$.projects.length()").value(2))
                // Descending by bytes.
                .andExpect(jsonPath("$.projects[0].bytes").value(4000))
                .andExpect(jsonPath("$.projects[1].bytes").value(1000))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("a project of another workspace appeared in this workspace's breakdown")
                .doesNotContain(b.projectId().toString());
    }
}
