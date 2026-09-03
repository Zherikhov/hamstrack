package com.hamstrack.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The two per-principal write budgets, behaviourally</strong> (HD-191, AC-12/13).
 *
 * <p>{@code WriteThrottleCoverageTest} asks whether every mutating handler is behind a budget;
 * this asks what the budget actually does. Three properties, and the second and third are the ones
 * that make it a budget rather than a global switch:
 *
 * <ul>
 *   <li>the {@code (limit+1)}-th mutating request in a minute is 429 with {@code Retry-After};</li>
 *   <li>the same principal's {@code GET} on the same path is unaffected — the binding is
 *       method-conditioned, and a budget that also refused reads would have broken every board on
 *       the instance;</li>
 *   <li>a second principal is unaffected — it is keyed on the caller, not on the resource.</li>
 * </ul>
 *
 * <p>The byte budget is separate and denominated differently: it refuses on VOLUME while the
 * request count is still well inside its own budget, which is precisely why one does not imply the
 * other.
 *
 * <p>Both windows are fixed epoch-minute, so a run that straddles a minute boundary would see the
 * count reset. The limits here are set to <strong>2</strong> and the assertions spend three
 * requests, which is far inside a minute on any machine that can run the suite at all.
 */
@SpringBootTest(properties = {
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        // The master switch ON — this is the one context in the storage set that exercises it.
        "app.rate-limit.enabled=true",
        "app.write.requests-per-minute=2",
        "app.write.upload-bytes-per-minute=5000B",
        "app.attachments.max-file-size=4000B",
        "app.storage.quota.enabled=true",
        "app.storage.quota.workspace-bytes=10000000B",
        "app.storage.quota.reconcile-cron=",
        // Not the subject here, and a shared per-IP window would refuse the logins this test needs.
        "app.rate-limit.auth-ip-requests-per-minute=10000"
})
@AutoConfigureMockMvc
class WriteBudgetTest extends StorageTestBase {

    static final Path STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("hamstrack-write-budget-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("app.storage.type", () -> "local");
        registry.add("app.storage.local.base-dir", STORAGE_DIR::toString);
    }

    /** AC-12, all three halves of it. */
    @Test
    void mutatingRequestsAreBudgetedPerPrincipalAndReadsAreNot() throws Exception {
        var ctx = newProject();
        var other = newProject();
        // createIssue spends one unit of this principal's budget; everything below counts from
        // there, which is why the assertions are on "the next mutation" rather than on a count.
        var issue = createIssue(ctx);
        var otherIssue = createIssue(other);

        var url = "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                  + "/issues/" + issue;

        mockMvc.perform(patch(url).header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"two\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch(url).header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"three\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers
                        .containsString("Too many write requests")));

        // A GET on the very same path is untouched: the interceptor is in the chain and does not
        // apply to the verb. If this ever fails, every board and issue page on the instance is
        // being charged to the write budget.
        mockMvc.perform(get(url).header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk());

        // ...and a different principal has their own window.
        mockMvc.perform(patch("/api/workspaces/" + other.wsId() + "/projects/" + other.projectId()
                              + "/issues/" + otherIssue)
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"mine\"}"))
                .andExpect(status().isOk());
    }

    /**
     * AC-13: uploads whose sizes sum past the byte budget are refused even though the REQUEST count
     * is inside the request budget.
     *
     * <p>Two uploads of 4 000 bytes against a 5 000-byte budget, and the request budget is 2 — so
     * the second upload is the second mutating request of the window and would be admitted by the
     * request budget alone. It is the denomination that refuses it, which is the whole argument for
     * having two.
     */
    @Test
    void uploadedBytesAreBudgetedSeparatelyFromRequestCount() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx);

        // createIssue was request 1; this upload is request 2 — the last one the REQUEST budget
        // allows — and it spends 4 000 of the 5 000-byte window.
        uploadRaw(ctx, issue, "a.txt", bytesOf(4000)).andExpect(status().isCreated());

        // A fresh principal in the same workspace, so the request budget cannot be what refuses:
        // this caller has spent nothing.
        var second = user();
        member(workspaceRepository.findById(ctx.wsId()).orElseThrow(), second);
        projectMember(projectRepository.findById(ctx.projectId()).orElseThrow(), second);
        var token = login(second);
        var fresh = new Ctx(ctx.wsId(), ctx.projectId(), token, ctx.config());

        uploadRaw(fresh, issue, "b.txt", bytesOf(4000)).andExpect(status().isCreated());
        uploadRaw(fresh, issue, "c.txt", bytesOf(4000))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers
                        .containsString("Too many uploaded bytes")));
    }

    /**
     * <strong>The byte budget is spent BEFORE tenancy, and that is why its position in the
     * refusal order is safe rather than merely convenient</strong> (the ordering
     * {@code AttachmentService.upload}'s javadoc states — which was written backwards once, with
     * the 429 seventh while the code spent it fourth, hence this test).
     *
     * <p>Both requests below name a workspace that does not exist. The first is refused
     * <strong>404</strong> and still spends its bytes — the budget is charged on the parsed size,
     * above every DB read. The second, identical in every way, is refused <strong>429</strong>:
     * a throttle now precedes the tenancy answer.
     *
     * <p>That would be a leak if the budget were keyed on anything about the target, and it is not
     * — it is keyed on the CALLER, so this 429 is byte-for-byte what the same caller gets against
     * their own workspace, against somebody else's, and against nothing at all. A refusal that
     * cannot vary with the target discloses nothing about it, which is the whole argument for
     * letting it run first: the request is refused having taken no lock and read no row.
     */
    @Test
    void theByteBudgetIsSpentBeforeTenancyAndItsRefusalNamesNoTarget() throws Exception {
        var ctx = newProject();
        var stranger = user();
        var ghost = new Ctx(UUID.randomUUID(), UUID.randomUUID(), login(stranger), ctx.config());

        // Request 1 of this principal's window (the request budget is 2, so neither call meets it).
        // 4 000 of the 5 000-byte window are spent by an upload that is then refused 404.
        uploadRaw(ghost, 1, "a.txt", bytesOf(4000))
                .andExpect(status().isNotFound());

        uploadRaw(ghost, 1, "b.txt", bytesOf(4000))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers
                        .containsString("Too many uploaded bytes")));
    }
}
