package com.hamstrack.notification;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.sse.SseRegistry;
import com.hamstrack.issue.ComponentTestBase;
import com.hamstrack.notification.entity.Notification;
import com.hamstrack.workspace.entity.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-135 — a notification is readable only while its reader is a member of the
 * workspace it quotes</strong> (docs/design/notification-workspace-scoping-proposal.md).
 *
 * <p>What is under test is a <em>disclosure</em>, not a listing rule. A notification's
 * {@code title} and {@code body} are denormalised copies of workspace content — a display
 * name and up to 120 characters of a comment — written into the row at delivery time, so
 * once a row reaches a caller there is nothing left to redact. Hence the fixture plants a
 * distinctive string in the comment and the assertions look for <em>that string</em> in the
 * response rather than counting array elements: a filter that drops the row from one surface
 * while another hands back the excerpt has fixed nothing, and {@code POST /{id}/read} is
 * exactly such a surface — it answers with the full DTO.
 *
 * <p>Two workspaces throughout, because a fixture with one workspace cannot tell "hides rows
 * from workspaces you left" apart from "hides everything".
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class NotificationWorkspaceScopeTest extends ComponentTestBase {

    /** A string only workspace A's comment contains — what a leak would carry. */
    private static final String A_SECRET = "the vault passphrase is quokka-77";
    private static final String B_TEXT = "the build is green";
    /** Distinctive, so "the mentioning user's name leaked" is a findable assertion. */
    private static final String A_MENTIONER = "Ada Ampere";
    private static final String RECIPIENT = "Zoe Quartz";

    /**
     * Mocked so {@link #theProducerWritesTheWorkspaceTheContentCameFrom()} can read the SSE
     * envelope's workspace id. Nothing else here touches SSE, and no live emitter exists in
     * a MockMvc test anyway.
     */
    @MockitoBean SseRegistry sseRegistry;

    // ============================================================ AC-4, AC-5, AC-15

    /**
     * AC-4 and AC-5 — <strong>the assertions that fail against the code this ticket
     * replaces</strong>, where every finder was keyed on the user alone.
     *
     * <p>All four surfaces in one test on purpose: they are one promise ("we stopped showing
     * you that workspace's notifications"), and a suite that checks the list but not the read
     * receipt has verified the half a reviewer already believed. The receipt is the half
     * worth writing down — it is a content read wearing a write's clothes, and a removed
     * member holding an id from a still-open tab is precisely who calls it.
     *
     * <p>{@code read-all} is checked in the database rather than in a response because its
     * entire observable effect is a column: marking hidden rows would pre-clear an inbox the
     * reader may get back, which is hiding a row by mutating it.
     *
     * <p>Every refusal here is a <strong>404</strong> and none is a 403 (AC-15): a row in a
     * workspace the caller has left is indistinguishable from a row that does not exist.
     */
    @Test
    void aRemovedMembersInboxForThatWorkspaceIsGoneFromEverySurface() throws Exception {
        var f = twoWorkspaceInbox();

        removeFromWorkspaceViaApi(f.a(), f.recipient());

        // --- list: B only, and not one character of A's content --------------------
        var listed = bodyOf(inbox(f.token(), get("/api/notifications")).andExpect(status().isOk()));
        assertThat(listed)
                .as("GET /api/notifications handed a removed member the comment excerpt from the "
                  + "workspace they were removed from. The body IS the disclosure — it was copied "
                  + "into the row at delivery time and cannot be redacted at render time.")
                .doesNotContain(A_SECRET);
        assertThat(listed)
                .as("the list leaked the mentioning user's display name from a workspace the caller "
                  + "has left — the title is denormalised workspace content too")
                .doesNotContain(A_MENTIONER);
        assertThat(listed)
                .as("the notification from the workspace the caller is STILL a member of vanished: "
                  + "this hides everything rather than hiding what was revoked")
                .contains(B_TEXT);

        // --- unread count: B's one row --------------------------------------------
        var counted = bodyOf(inbox(f.token(), get("/api/notifications/unread-count"))
                .andExpect(status().isOk()));
        // Equality, not containment: "{\"count\":10}" contains "\"count\":1", and the AC-9
        // fixture next door puts 36 rows in play, so a substring match would pass on exactly
        // the number that proves the filter is missing.
        assertThat(counted)
                .as("unread-count still counts rows from a workspace the caller has left: " + counted)
                .isEqualTo("{\"count\":1}");

        // --- read receipt: 404, and no content in the refusal ----------------------
        var refused = bodyOf(inbox(f.token(), post("/api/notifications/" + f.aRowId() + "/read"))
                .andExpect(status().isNotFound()));
        assertThat(refused)
                .as("POST /{id}/read answered 404 but its body still carried the notification's "
                  + "content: " + refused)
                .doesNotContain(A_SECRET);
        assertThat(refused)
                .as("POST /{id}/read answered 404 but its body still carried the notification's "
                  + "content: " + refused)
                .doesNotContain(A_MENTIONER);

        // --- read-all: leaves the invisible row alone ------------------------------
        inbox(f.token(), post("/api/notifications/read-all")).andExpect(status().isNoContent());
        assertThat(readAt(f.aRowId()))
                .as("read-all marked a row the caller cannot see. \"All\" means everything visible; "
                  + "mutating a hidden row pre-clears an inbox a re-added member gets back.")
                .isNull();
        assertThat(readAt(f.bRowId())).as("read-all skipped the visible row — the filter is too wide").isNotNull();

        // --- and the receipt still WORKS for the workspace they are still in -------
        // Every assertion above measures a refusal, and a predicate that refused
        // everything would satisfy all of them. This is the one that fails if the filter
        // is too narrow rather than too wide.
        //
        // It sits at the END rather than beside the 404 on purpose: marking B read here
        // would have pre-satisfied read-all's `readAt(bRowId) != null` above and quietly
        // retired that assertion. The order is the only thing keeping both honest.
        var receipt = bodyOf(inbox(f.token(), post("/api/notifications/" + f.bRowId() + "/read"))
                .andExpect(status().isOk()));
        assertThat(receipt)
                .as("POST /{id}/read answered 200 for the surviving workspace but not with that "
                  + "row's content: " + receipt)
                .contains(B_TEXT);
    }

    // ============================================================ AC-6

    /**
     * AC-6. Leave-and-filter promises <em>hidden</em>, not <em>deleted</em> — two different
     * claims, and a member re-added the next day notices which one was true. Read state is
     * part of "as it was": the row went away read and must come back read.
     */
    @Test
    void rejoiningRestoresTheInboxWithItsReadStateIntact() throws Exception {
        var f = twoWorkspaceInbox();

        inbox(f.token(), post("/api/notifications/" + f.aRowId() + "/read"))
                .andExpect(status().isOk());
        removeFromWorkspaceViaApi(f.a(), f.recipient());
        rejoin(f.a(), f.recipient());

        var listed = bodyOf(inbox(f.token(), get("/api/notifications")).andExpect(status().isOk()));
        assertThat(listed)
                .as("a re-added member did not get their old notifications back. Nothing deletes "
                  + "these rows (§4.1) — if they are gone, something started purging them.")
                .contains(f.aRowId().toString());
        assertThat(listed).as("the row came back without its content").contains(A_SECRET);
        assertThat(readAt(f.aRowId()))
                .as("the restored row came back UNREAD although it was read before the removal — "
                  + "hiding a row must not mutate it")
                .isNotNull();
    }

    // ============================================================ AC-7

    /**
     * AC-7. The rule is "rows in workspaces you belong to", not "rows you used to own": a
     * stranger who was never in either workspace gets an empty feed. This is the assertion
     * that would still pass if the predicate had been written as "hide the rows whose
     * workspace you were removed from", which is why it is written separately.
     */
    @Test
    void aUserWhoWasNeverAMemberSeesNothing() throws Exception {
        twoWorkspaceInbox();
        var stranger = user();

        var listed = bodyOf(inbox(login(stranger), get("/api/notifications"))
                .andExpect(status().isOk()));
        assertThat(listed).as("a user with no membership anywhere got a non-empty feed: " + listed).isEqualTo("[]");
    }

    // ============================================================ AC-9

    /**
     * AC-9 — <strong>the filter is inside the {@code LIMIT}, not applied to the page that
     * comes back.</strong> The feed caps at 30 rows. Filtering the fetched page in Java would
     * show a reader with 30 hidden rows an empty bell while their visible rows sat on page 2,
     * and every other test in this class would still pass.
     *
     * <p>The bulk rows are written through the entity manager rather than by posting 35
     * comments: what is under test here is the shape of one statement, and the producer's
     * link format has a test of its own.
     */
    @Test
    void thirtyHiddenRowsDoNotPushTheVisibleOneOffTheFeed() throws Exception {
        var f = twoWorkspaceInbox();
        seedNotifications(f.a().wsId(), f.recipient(), 35);

        removeFromWorkspaceViaApi(f.a(), f.recipient());

        var listed = bodyOf(inbox(f.token(), get("/api/notifications")).andExpect(status().isOk()));
        assertThat(listed)
                .as("with 35 hidden rows newer than it, the visible notification fell off the feed. "
                  + "That is the signature of a filter applied AFTER the 30-row page was fetched — "
                  + "the predicate has to be in the statement that applies the limit.")
                .contains(B_TEXT);
        assertThat(listed).as("the hidden rows are still being returned").doesNotContain(A_SECRET);
    }

    // ============================================================ AC-10, AC-11

    /**
     * AC-10 and AC-11. The producer takes a {@code Workspace}, so a row's tenant is the tenant
     * of the issue its comment sits on — a property of the type rather than of a resolution
     * two classes away — and the SSE envelope still names that same workspace, so the wire
     * contract the live stream depends on is unchanged.
     */
    @Test
    void theProducerWritesTheWorkspaceTheContentCameFrom() throws Exception {
        var f = twoWorkspaceInbox();

        assertThat(workspaceIdOf(f.aRowId()))
                .as("the mention notification was not written into the workspace of the issue its "
                  + "comment is on")
                .isEqualTo(f.a().wsId());
        assertThat(workspaceIdOf(f.bRowId()))
                .as("two workspaces, and a row is attributed to the wrong one")
                .isEqualTo(f.b().wsId());

        verify(sseRegistry, atLeastOnce()).sendToUser(
                eq(f.a().wsId()), eq(f.recipient().getId()), eq("NOTIFICATION"), any());
        verify(sseRegistry, atLeastOnce()).sendToUser(
                eq(f.b().wsId()), eq(f.recipient().getId()), eq("NOTIFICATION"), any());
    }

    // ============================================================ fixture

    /** Workspace A and B, one mention notification for the same recipient in each. */
    private record Inbox(Ctx a, Ctx b, User recipient, String token, UUID aRowId, UUID bRowId) {}

    private Inbox twoWorkspaceInbox() throws Exception {
        var a = newProject();
        var b = newProject();
        rename(a.owner(), A_MENTIONER);

        var recipient = rename(user(), RECIPIENT);
        join(a, recipient);
        join(b, recipient);

        mention(a, "@" + RECIPIENT + " " + A_SECRET);
        mention(b, "@" + RECIPIENT + " " + B_TEXT);

        var rows = notificationsOf(recipient);
        assertThat(rows)
                .as(() -> "the fixture produced " + rows.size() + " notifications instead of one per "
                  + "workspace — the mention parser or the comment endpoint changed, and every "
                  + "assertion in this class rests on it")
                .hasSize(2);
        return new Inbox(a, b, recipient, login(recipient),
                rowIn(rows, a.wsId()), rowIn(rows, b.wsId()));
    }

    private static UUID rowIn(List<Row> rows, UUID workspaceId) {
        return rows.stream().filter(r -> r.workspaceId().equals(workspaceId)).findFirst()
                .orElseThrow(() -> new AssertionError("no notification in workspace " + workspaceId))
                .id();
    }

    /** Post a comment, as the project owner, whose body mentions the recipient. */
    private void mention(Ctx ctx, String body) throws Exception {
        long number = createIssue(ctx, "Something to discuss").get("number").asLong();
        mockMvc.perform(post(commentsBase(ctx, number))
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + json.writeValueAsString(body) + "}"))
                .andExpect(status().isCreated());
    }

    private User rename(User u, String displayName) {
        u.setDisplayName(displayName);
        return userRepository.save(u);
    }

    private void join(Ctx ctx, User u) {
        member(workspaceRepository.findById(ctx.wsId()).orElseThrow(), u, "MEMBER");
        projectMember(projectRepository.findById(ctx.projectId()).orElseThrow(), u, "MEMBER");
    }

    /** The real HD-132 removal endpoint — the event this whole ticket exists to observe. */
    private void removeFromWorkspaceViaApi(Ctx ctx, User u) throws Exception {
        mockMvc.perform(delete("/api/workspaces/" + ctx.wsId() + "/members/" + u.getId())
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());
    }

    private void rejoin(Ctx ctx, User u) {
        member(workspaceRepository.findById(ctx.wsId()).orElseThrow(), u, "MEMBER");
    }

    private ResultActions inbox(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    // ============================================================ database reads

    private record Row(UUID id, UUID workspaceId) {}

    private List<Row> notificationsOf(User u) {
        return txTemplate.execute(s -> entityManager.createQuery(
                        "SELECT n FROM Notification n WHERE n.user.id = :uid", Notification.class)
                .setParameter("uid", u.getId())
                .getResultList().stream()
                .map(n -> new Row(n.getId(), n.getWorkspace().getId()))
                .toList());
    }

    private UUID workspaceIdOf(UUID notificationId) {
        return txTemplate.execute(s -> entityManager.find(Notification.class, notificationId)
                .getWorkspace().getId());
    }

    private Instant readAt(UUID notificationId) {
        return txTemplate.execute(s -> entityManager.find(Notification.class, notificationId)
                .getReadAt());
    }

    /** {@code count} further unread notifications for {@code u} in {@code workspaceId}. */
    private void seedNotifications(UUID workspaceId, User u, int count) {
        txTemplate.execute(s -> {
            var ws = entityManager.getReference(Workspace.class, workspaceId);
            var user = entityManager.getReference(User.class, u.getId());
            for (int i = 0; i < count; i++) {
                var n = new Notification();
                n.setUser(user);
                n.setWorkspace(ws);
                n.setType("MENTIONED");
                n.setTitle(A_MENTIONER + " mentioned you");
                n.setBody(A_SECRET);
                n.setLink("/w/" + workspaceId + "/p/" + UUID.randomUUID() + "?issue=" + i);
                entityManager.persist(n);
            }
            return null;
        });
    }

    private static String commentsBase(Ctx ctx, long number) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
               + "/issues/" + number + "/comments";
    }
}
