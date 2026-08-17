package com.hamstrack.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.SprintTestBase;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.workspace.entity.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-102 slice S1 — the delivery-capability model on the project API
 * ({@code docs/design/delivery-paths-proposal.md} §5.1, §7, §11, §13, §14.2).
 *
 * <p>The load-bearing test here is {@link #ruleA_noStatusCodeAnywhereDependsOnACapability()}.
 * Everything else in this class guards a shape; that one guards the <em>rule</em> the
 * whole model rests on — <strong>a capability is presentation, never API</strong> (§5.1).
 * Every other assertion could be re-derived from the spec if it were lost; that one
 * cannot, because its failure mode is a change that looks entirely reasonable in review
 * ("a Kanban project shouldn't be creating sprints — 409 it") and silently converts a
 * reversible UI preference into a data-entry trap. That is the exact class of harm the
 * epic exists to remove: an integration, an import or a script written before somebody
 * flipped a toggle must keep working, and §13's "switching never destroys anything"
 * invariant is only true because nothing downstream reads these flags.
 *
 * <p>It extends {@code SprintTestBase} for the whole HD-22/HD-30/HD-31/HD-32 fixture
 * chain (workspace/project/role bootstrap, {@code actorWith}, and the sprint, version and
 * issue HTTP surfaces) — Rule A can only be proved by driving the endpoints a capability
 * would plausibly gate, so the test needs all of them.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class DeliveryCapabilitiesApiTest extends SprintTestBase {

    // ==================================================================== defaults

    /**
     * §7 / open question 2 — a project created without a {@code delivery} block comes out
     * <strong>lean</strong>: Kanban, releases off, estimation off. This is the highest-risk
     * assumption in the design (§17) and a one-line flip, so it is pinned explicitly rather
     * than left to whatever the entity happens to default to.
     */
    @Test
    void aProjectCreatedWithoutDeliveryIsLean() throws Exception {
        var ctx = newProject();

        var created = createProject(ctx, "{\"name\":\"Lean\",\"key\":\"" + freshKey() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.delivery.board").value("KANBAN"))
                .andExpect(jsonPath("$.delivery.releases").value(false))
                .andExpect(jsonPath("$.delivery.estimation").value(false))
                .andExpect(jsonPath("$.delivery.preset").value("KANBAN"))
                // …and the deprecated top-level mirror still carries the same value, so a
                // client written against 0.13.0 reads the identical answer (§11.3).
                .andExpect(jsonPath("$.boardMode").value("KANBAN"))
                .andReturn().getResponse().getContentAsString();
        var projectId = UUID.fromString(json.readTree(created).get("id").asText());

        // The capabilities are STORED, not a response-time invention: they survive a
        // round trip through a fresh GET and through the project list.
        getProject(ctx, ctx.token(), projectId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.preset").value("KANBAN"))
                .andExpect(jsonPath("$.delivery.releases").value(false));
        assert deliveryOf(listProjects(ctx), projectId).get("preset").asText().equals("KANBAN")
                : "the project list does not carry delivery — every surface reads it from there";
    }

    /**
     * §14.2 "Creation": choosing Scrum in the picker sends board and estimation in ONE
     * request. There is no server-side "Scrum implies estimation" rule — the capabilities
     * are independent — so the client's whole answer must be honoured verbatim.
     */
    @Test
    void theCreationPickersAnswerIsHonouredVerbatim() throws Exception {
        var ctx = newProject();

        createProject(ctx, "{\"name\":\"Scrum\",\"key\":\"" + freshKey() + "\","
                + "\"delivery\":{\"board\":\"SCRUM\",\"estimation\":true}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.delivery.board").value("SCRUM"))
                .andExpect(jsonPath("$.delivery.estimation").value(true))
                // releases was omitted → the lean default, NOT "unset"
                .andExpect(jsonPath("$.delivery.releases").value(false))
                .andExpect(jsonPath("$.delivery.preset").value("SCRUM"))
                .andExpect(jsonPath("$.boardMode").value("SCRUM"));

        // A closed set: an unknown board value is a 400 from Jackson, never a silent
        // fall-through to KANBAN.
        createProject(ctx, "{\"name\":\"Bad\",\"key\":\"" + freshKey() + "\","
                + "\"delivery\":{\"board\":\"KANBANN\"}}")
                .andExpect(status().isBadRequest());
    }

    // ============================================================ preset derivation

    /**
     * §2.3 — the preset table, all eight combinations. Three are named; the other five
     * are {@code CUSTOM}, which is a first-class answer and never an error ("Scrum +
     * Releases" is an ordinary way to work, and the whole reason this is not a three-way
     * enum — D1).
     *
     * <p>Driven through PATCH on one project so the derivation is exercised where it
     * actually runs (the response mapper), not against {@code DeliveryPreset.of} in
     * isolation — a unit test of the enum would still pass if {@code DeliveryResponse}
     * stopped calling it.
     */
    @Test
    void everyCapabilityCombinationDerivesItsDocumentedPreset() throws Exception {
        var ctx = newProject();

        assertPreset(ctx, "KANBAN", false, false, "KANBAN");
        assertPreset(ctx, "SCRUM", false, true, "SCRUM");
        assertPreset(ctx, "KANBAN", true, false, "RELEASES");
        // …and the five that read as CUSTOM:
        assertPreset(ctx, "KANBAN", false, true, "CUSTOM");   // Kanban + flow sizing (§8)
        assertPreset(ctx, "KANBAN", true, true, "CUSTOM");
        assertPreset(ctx, "SCRUM", false, false, "CUSTOM");
        assertPreset(ctx, "SCRUM", true, false, "CUSTOM");
        assertPreset(ctx, "SCRUM", true, true, "CUSTOM");     // Scrum + Releases — legal
    }

    /**
     * Open question 5 — {@code preset} is derived, never settable. A client that echoes
     * back the whole {@code delivery} object it read from a GET must get a
     * <strong>400 naming the field</strong>, not a silent ignore: silently dropping a
     * value the caller believed it was setting is how two sources of truth start
     * disagreeing.
     */
    @Test
    void presetIsRejectedInRequestsRatherThanIgnored() throws Exception {
        var ctx = newProject();
        patchDelivery(ctx, "\"board\":\"SCRUM\",\"releases\":true,\"estimation\":true")
                .andExpect(status().isOk());

        // …on PATCH — including the exact round-trip a naive client performs.
        patchProject(ctx, ctx.token(), "{\"delivery\":{\"board\":\"KANBAN\",\"releases\":false,"
                + "\"estimation\":false,\"preset\":\"KANBAN\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("preset")));

        // The rejection is total: nothing in the same body was applied on the way to it.
        var delivery = deliveryOf(ctx);
        assert delivery.get("board").asText().equals("SCRUM")
                && delivery.get("releases").asBoolean()
                && delivery.get("estimation").asBoolean()
                : "a body rejected for carrying `preset` still applied its other members: " + delivery;

        // …and on POST, where the same mistake would silently create the wrong project.
        createProject(ctx, "{\"name\":\"Echoed back\",\"key\":\"" + freshKey() + "\","
                + "\"delivery\":{\"board\":\"SCRUM\",\"preset\":\"SCRUM\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("preset")));
    }

    // ========================================================== the boardMode mirror

    /**
     * §11.3 — the deprecated top-level {@code boardMode} is still accepted so a client
     * written against 0.13.0 keeps working, and it must stay a true mirror of
     * {@code delivery.board} in both directions.
     *
     * <p>Sending both is fine while they <em>agree</em> (an SPA mid-migration will do
     * exactly that). Sending both with different values is a <strong>400</strong> rather
     * than a silent winner-takes-all: either winner would discard half of what an
     * out-of-date client asked for, and the caller would never learn which half.
     * {@code CreateProjectRequest} has no {@code boardMode} at all, so the disagreement
     * case is PATCH-only by construction.
     */
    @Test
    void theLegacyBoardModeMirrorStillWorksAndMayNotDisagree() throws Exception {
        var ctx = newProject();

        // 1) legacy alone — the 0.13.0 client, untouched
        patchProject(ctx, ctx.token(), "{\"boardMode\":\"SCRUM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardMode").value("SCRUM"))
                .andExpect(jsonPath("$.delivery.board").value("SCRUM"));

        // 2) both, agreeing — the mid-migration client
        patchProject(ctx, ctx.token(), "{\"boardMode\":\"KANBAN\",\"delivery\":{\"board\":\"KANBAN\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardMode").value("KANBAN"))
                .andExpect(jsonPath("$.delivery.board").value("KANBAN"));

        // 3) both, disagreeing — 400, and nothing is applied
        patchProject(ctx, ctx.token(),
                "{\"boardMode\":\"SCRUM\",\"delivery\":{\"board\":\"KANBAN\",\"releases\":true}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("disagree")));
        var delivery = deliveryOf(ctx);
        assert delivery.get("board").asText().equals("KANBAN") : "the rejected board was applied anyway";
        assert !delivery.get("releases").asBoolean()
                : "a body rejected for a boardMode/delivery.board disagreement still applied `releases`";

        // 4) the legacy field never touches the OTHER two capabilities
        patchDelivery(ctx, "\"releases\":true,\"estimation\":true").andExpect(status().isOk());
        patchProject(ctx, ctx.token(), "{\"boardMode\":\"SCRUM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.releases").value(true))
                .andExpect(jsonPath("$.delivery.estimation").value(true))
                .andExpect(jsonPath("$.delivery.preset").value("CUSTOM"));
    }

    // ============================================================ partial semantics

    /**
     * §11.3 — every {@code delivery} member is independently nullable, and an absent
     * {@code delivery} leaves all three alone. The trap this guards is the Jackson-3
     * {@code FAIL_ON_NULL_FOR_PRIMITIVES} one (CLAUDE.md): a primitive {@code boolean} in
     * {@code DeliveryRequest} would make every partial body 400, and coalescing null→false
     * in a canonical constructor would make every partial body silently turn capabilities
     * OFF. Both mistakes are invisible until a user loses a setting they never touched.
     */
    @Test
    void oneDeliveryMemberChangesOnlyItself() throws Exception {
        var ctx = newProject();
        patchDelivery(ctx, "\"board\":\"SCRUM\",\"releases\":true,\"estimation\":true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.preset").value("CUSTOM"));

        // …one member at a time, each leaving the other two exactly as they were.
        patchDelivery(ctx, "\"releases\":false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.board").value("SCRUM"))
                .andExpect(jsonPath("$.delivery.releases").value(false))
                .andExpect(jsonPath("$.delivery.estimation").value(true))
                .andExpect(jsonPath("$.delivery.preset").value("SCRUM"));

        patchDelivery(ctx, "\"estimation\":false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.board").value("SCRUM"))
                .andExpect(jsonPath("$.delivery.releases").value(false))
                .andExpect(jsonPath("$.delivery.estimation").value(false));

        // An EMPTY delivery object is a no-op, not a reset to the lean defaults.
        patchProject(ctx, ctx.token(), "{\"delivery\":{}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.board").value("SCRUM"));

        // …and a PATCH with no `delivery` at all touches none of the three (§13
        // idempotency): renaming a project must never quietly re-lean it.
        patchDelivery(ctx, "\"releases\":true,\"estimation\":true").andExpect(status().isOk());
        patchProject(ctx, ctx.token(), "{\"name\":\"Renamed, nothing else\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed, nothing else"))
                .andExpect(jsonPath("$.delivery.board").value("SCRUM"))
                .andExpect(jsonPath("$.delivery.releases").value(true))
                .andExpect(jsonPath("$.delivery.estimation").value(true));

        // Setting a capability to the value it already has is a plain 200 (§13).
        patchDelivery(ctx, "\"releases\":true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.releases").value(true));
    }

    // ================================================================= RULE A (§5.1)

    /**
     * <strong>Rule A (§5.1) — the invariant this whole slice rests on: a capability gates
     * the UI, never the API.</strong>
     *
     * <p>On a project whose capabilities are ALL off — {@code board = KANBAN},
     * {@code releases = false}, {@code estimation = false} — creating a sprint, creating a
     * version and creating an issue that carries {@code storyPoints}, {@code fixVersionIds}
     * and {@code sprintId} must all succeed and round-trip, with the <em>same</em> status
     * codes a fully-enabled project gives. Nothing is absent from the API, nothing 4xxs,
     * and no value is dropped on the way in.
     *
     * <p>This is pinned because the failure it prevents is a <em>plausible-looking</em>
     * change: gating {@code POST /sprints} on {@code board = SCRUM} reads like tidiness in
     * review. It would (a) break every integration and import written before a toggle was
     * flipped, (b) create a second code path the tenancy and permission logic must reason
     * about, and (c) turn a reversible preference into a trap — the production dead end
     * this epic closes, moved from the UI into the API where no Rule C affordance can
     * rescue it. A capability is never an authorization boundary; reviewers must not read
     * a hidden control as a protected one (§17 risk 1).
     */
    @Test
    void ruleA_noStatusCodeAnywhereDependsOnACapability() throws Exception {
        var lean = newProject();     // repository-bootstrapped → the entity defaults
        var full = newProject();

        // Establish the two ends of the matrix explicitly rather than trusting a default.
        patchDelivery(lean, "\"board\":\"KANBAN\",\"releases\":false,\"estimation\":false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.preset").value("KANBAN"));
        patchDelivery(full, "\"board\":\"SCRUM\",\"releases\":true,\"estimation\":true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.preset").value("CUSTOM"));

        // ---- the two path-specific CREATE endpoints, on the capability-off project ----
        // 201 on a Kanban project with sprint planning off (§5.1's own worked example).
        postSprint(lean, lean.token(), "{\"name\":\"Sprint 1\"}").andExpect(status().isCreated());
        // 201 with releases off — the Releases page is hidden, the endpoint is not.
        postVersion(lean, lean.token(), "{\"name\":\"1.0.0\"}").andExpect(status().isCreated());

        var sprintId = createSprint(lean, "Sprint 2");
        var versionId = createVersion(lean, "2.0.0");

        // ---- the three path-specific ISSUE fields, on the capability-off project ----
        var issue = createIssue(lean, "carries every gated field",
                "\"storyPoints\":8",
                "\"sprintId\":\"" + sprintId + "\"",
                "\"fixVersionIds\":[\"" + versionId + "\"]");

        // …and they ROUND-TRIP. "Accepted then quietly dropped" would be the same bug
        // wearing a 201.
        assert issue.get("storyPoints").decimalValue().intValue() == 8
                : "storyPoints was dropped on a project with estimation off: " + issue.get("storyPoints");
        assert sprintId.toString().equals(sprintId(issue))
                : "sprintId was dropped on a Kanban project: " + issue.get("sprint");
        assert issue.get("fixVersions").size() == 1
                : "fixVersionIds was dropped on a project with releases off: " + issue.get("fixVersions");

        var reread = getIssue(lean, numberOf(issue));
        assert reread.get("storyPoints").decimalValue().intValue() == 8 : reread.toString();
        assert reread.get("fixVersions").size() == 1 : reread.toString();
        assert sprintId.toString().equals(sprintId(reread)) : reread.toString();

        // ---- the same calls on the fully-enabled project answer identically ----
        // The point is not that they work; it is that the capability made NO difference.
        postSprint(full, full.token(), "{\"name\":\"Sprint 1\"}").andExpect(status().isCreated());
        postVersion(full, full.token(), "{\"name\":\"1.0.0\"}").andExpect(status().isCreated());
        createIssue(full, "the same payload",
                "\"storyPoints\":8",
                "\"sprintId\":\"" + createSprint(full, "Sprint 2") + "\"",
                "\"fixVersionIds\":[\"" + createVersion(full, "2.0.0") + "\"]");

        // ---- the writes did not flip anything back on ----
        // A "helpful" auto-enable would be Rule A from the other side: the API deciding a
        // presentation preference on the user's behalf.
        var delivery = deliveryOf(lean);
        assert delivery.get("board").asText().equals("KANBAN")
                && !delivery.get("releases").asBoolean()
                && !delivery.get("estimation").asBoolean()
                : "creating a sprint/version/estimate silently changed the project's declared "
                  + "capabilities: " + delivery;
    }

    /**
     * §13 — switching a capability off never destroys, clears or moves data, and the API
     * keeps answering identically afterwards. This is Rule A's consequence, and it is what
     * makes "you can change this any time" an honest promise rather than a warning label:
     * the SPA's Rule B read-only rendering has something to render because nothing was
     * deleted.
     */
    @Test
    void turningCapabilitiesOffDestroysNothingAndBlocksNothing() throws Exception {
        var ctx = newProject();
        patchDelivery(ctx, "\"board\":\"SCRUM\",\"releases\":true,\"estimation\":true")
                .andExpect(status().isOk());

        var sprintId = startedSprint(ctx, "Sprint 1");
        var versionId = createVersion(ctx, "1.0.0");
        var issue = createIssue(ctx, "fully decorated",
                "\"storyPoints\":5",
                "\"sprintId\":\"" + sprintId + "\"",
                "\"fixVersionIds\":[\"" + versionId + "\"]");

        // …turn everything off, with a RUNNING sprint (§13's hardest row).
        patchDelivery(ctx, "\"board\":\"KANBAN\",\"releases\":false,\"estimation\":false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.preset").value("KANBAN"));

        // Nothing moved: the issue still carries all three values.
        var after = getIssue(ctx, numberOf(issue));
        assert after.get("storyPoints").decimalValue().intValue() == 5
                : "turning estimation off cleared a story point value";
        assert sprintId.toString().equals(sprintId(after))
                : "turning sprint planning off detached the issue from its sprint";
        assert after.get("fixVersions").size() == 1
                : "turning releases off removed an issue↔version link";

        // The sprint is still ACTIVE and still completable by a curator (§6): a running
        // sprint must never become invisible OR unfinishable because of a UI preference.
        assert sprintNode(ctx, sprintId).get("state").asText().equals("ACTIVE")
                : "the running sprint was auto-completed by a capability change";
        assert versionsCount(ctx) >= 1 : "a version row disappeared when releases went off";
        completeToBacklog(ctx, ctx.token(), sprintId).andExpect(status().isOk());

        // Re-enabling restores full function with no user action, because nothing was lost.
        patchDelivery(ctx, "\"board\":\"SCRUM\",\"releases\":true,\"estimation\":true")
                .andExpect(status().isOk());
        var restored = getIssue(ctx, numberOf(issue));
        assert restored.get("storyPoints").decimalValue().intValue() == 5 : restored.toString();
        assert restored.get("fixVersions").size() == 1 : restored.toString();
    }

    // ======================================================================= gates

    /**
     * §4 / §14.2 — who may change delivery, and the tenancy answers around it.
     *
     * <p>The one that matters most is the last: a non-member gets <strong>404</strong>,
     * never a 403. A 403 would confirm that this workspace/project exists to somebody who
     * has no business knowing — the top bug class in a multi-tenant Cloud install. 403 is
     * reserved for a member who lacks the curation role, where existence is already known.
     */
    @Test
    void changingDeliveryIsCuratorOnlyAndLeaksNothing() throws Exception {
        var ctx = newProject();

        // project MANAGER (the ctx owner) → 200
        patchDelivery(ctx, "\"releases\":true").andExpect(status().isOk());

        // workspace ADMIN who is NOT a project member → 200, with their REAL role echoed
        var admin = actorWith(ctx, WorkspaceRole.ADMIN, null);
        patchProject(ctx, admin.token(), "{\"delivery\":{\"estimation\":true}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.estimation").value(true))
                .andExpect(jsonPath("$.myRole").value("VIEWER"));

        // a plain project MEMBER → 403: they know the project exists, they just may not
        // change how the whole team's board reads.
        var plain = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.MEMBER);
        patchProject(ctx, plain.token(), "{\"delivery\":{\"releases\":false}}")
                .andExpect(status().isForbidden());
        // …and a plain member may still READ the capabilities (§4: any project member).
        getProject(ctx, plain.token(), ctx.projectId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.releases").value(true));

        // a non-member of the workspace → 404, NEVER 403
        var stranger = newProject();
        patchProject(ctx, stranger.token(), "{\"delivery\":{\"releases\":false}}")
                .andExpect(status().isNotFound());
        getProject(ctx, stranger.token(), ctx.projectId()).andExpect(status().isNotFound());

        // an unknown project in a workspace the caller CAN see → 404
        patchProject(ctx.wsId(), UUID.randomUUID(), ctx.token(), "{\"delivery\":{\"releases\":false}}")
                .andExpect(status().isNotFound());
        // an unknown workspace → 404, and the same for a workspace that exists but is
        // somebody else's (indistinguishable on purpose).
        patchProject(UUID.randomUUID(), ctx.projectId(), ctx.token(), "{\"delivery\":{\"releases\":false}}")
                .andExpect(status().isNotFound());
        patchProject(stranger.wsId(), stranger.projectId(), ctx.token(),
                "{\"delivery\":{\"releases\":false}}")
                .andExpect(status().isNotFound());

        // Nothing above changed anything.
        var delivery = deliveryOf(ctx);
        assert delivery.get("releases").asBoolean() && delivery.get("estimation").asBoolean()
                : "a rejected caller still moved the needle: " + delivery;
    }

    /**
     * §4 / §13 — an archived project is frozen, and its delivery capabilities are part of
     * "its settings". They change how the board, the backlog, the rail and the issue
     * detail render, so leaving them quietly writable while everything they govern is 409
     * would be an odd hole. {@code unarchive} is the way back; reads keep working.
     */
    @Test
    void anArchivedProjectFreezesItsDeliveryCapabilities() throws Exception {
        var ctx = newProject();
        patchDelivery(ctx, "\"board\":\"SCRUM\",\"estimation\":true").andExpect(status().isOk());

        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                        + "/archive").header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());

        patchProject(ctx, ctx.token(), "{\"delivery\":{\"releases\":true}}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("archived")));
        patchProject(ctx, ctx.token(), "{\"boardMode\":\"KANBAN\"}")
                .andExpect(status().isConflict());

        // Reads still work, and the frozen state is the one the curator left behind.
        getProject(ctx, ctx.token(), ctx.projectId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.board").value("SCRUM"))
                .andExpect(jsonPath("$.delivery.releases").value(false));

        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                        + "/unarchive").header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());
        patchDelivery(ctx, "\"releases\":true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.releases").value(true));
    }

    // ==================================================================== plumbing

    private void assertPreset(Ctx ctx, String board, boolean releases, boolean estimation,
                              String expected) throws Exception {
        patchDelivery(ctx, "\"board\":\"" + board + "\",\"releases\":" + releases
                + ",\"estimation\":" + estimation)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.board").value(board))
                .andExpect(jsonPath("$.delivery.releases").value(releases))
                .andExpect(jsonPath("$.delivery.estimation").value(estimation))
                .andExpect(jsonPath("$.delivery.preset").value(expected));
    }

    /** PATCH the ctx project's delivery with a raw JSON fragment of members. */
    private ResultActions patchDelivery(Ctx ctx, String membersJson) throws Exception {
        return patchProject(ctx, ctx.token(), "{\"delivery\":{" + membersJson + "}}");
    }

    private ResultActions patchProject(Ctx ctx, String token, String bodyJson) throws Exception {
        return patchProject(ctx.wsId(), ctx.projectId(), token, bodyJson);
    }

    private ResultActions patchProject(UUID wsId, UUID projectId, String token, String bodyJson)
            throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + wsId + "/projects/" + projectId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    private ResultActions createProject(Ctx ctx, String bodyJson) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects")
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    private ResultActions getProject(Ctx ctx, String token, UUID projectId) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/projects/" + projectId)
                .header("Authorization", "Bearer " + token));
    }

    private JsonNode listProjects(Ctx ctx) throws Exception {
        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/projects")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode deliveryOf(JsonNode projectList, UUID projectId) {
        for (var p : projectList) {
            if (p.get("id").asText().equals(projectId.toString())) return p.get("delivery");
        }
        throw new AssertionError("project " + projectId + " not in the list");
    }

    /** The ctx project's stored delivery, read back through a fresh GET. */
    private JsonNode deliveryOf(Ctx ctx) throws Exception {
        var body = getProject(ctx, ctx.token(), ctx.projectId())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("delivery");
    }

    private int versionsCount(Ctx ctx) throws Exception {
        var body = mockMvc.perform(get(versionsBase(ctx))
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(body);
        return node.has("content") ? node.get("content").size() : node.size();
    }

    /** A project key that cannot collide with a concurrently-running test's. */
    private static String freshKey() {
        return "D" + Math.abs(UUID.randomUUID().hashCode() % 100000);
    }
}
