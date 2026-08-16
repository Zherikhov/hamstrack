package com.hamstrack.issue;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared HTTP + fixture helpers for the HD-22 (Agile sprints) integration suites.
 *
 * <p>Fourth link in the epic's base chain — {@code LabelTestBase} →
 * {@code ComponentTestBase} → {@code VersionTestBase} → here — rather than a fifth copy
 * of the scaffolding. Everything HD-22 needs from the earlier slices already exists
 * there: the user/workspace/project bootstrap, {@code login}, the whole issue-side HTTP
 * surface (create / patch / board / backlog / history), {@code siblingProject},
 * {@code actorWith} for the role matrix and {@code markDone}/{@code doneStatusId} for
 * the done-vs-carried-over assertions. Only the sprint endpoints, the rank endpoint and
 * the planning-view reader live here.
 */
public abstract class SprintTestBase extends VersionTestBase {

    // ============================================================ sprint HTTP

    protected String sprintsBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/sprints";
    }

    protected ResultActions postSprint(Ctx ctx, String token, String bodyJson) throws Exception {
        return mockMvc.perform(post(sprintsBase(ctx))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    /** Create a FUTURE sprint as the ctx owner (a curator) and return its id. */
    protected UUID createSprint(Ctx ctx, String name) throws Exception {
        return createSprint(ctx, ctx.token(), "{\"name\":" + json.writeValueAsString(name) + "}");
    }

    protected UUID createSprint(Ctx ctx, String token, String bodyJson) throws Exception {
        var resp = postSprint(ctx, token, bodyJson)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asText());
    }

    /** Create AND start a sprint — the fixture behind every "the sprint is running" case. */
    protected UUID startedSprint(Ctx ctx, String name) throws Exception {
        var id = createSprint(ctx, name);
        startSprint(ctx, ctx.token(), id).andExpect(status().isOk());
        return id;
    }

    protected ResultActions patchSprint(Ctx ctx, String token, UUID sprintId, String bodyJson)
            throws Exception {
        return mockMvc.perform(patch(sprintsBase(ctx) + "/" + sprintId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    protected ResultActions getSprint(Ctx ctx, String token, UUID sprintId) throws Exception {
        return mockMvc.perform(get(sprintsBase(ctx) + "/" + sprintId)
                .header("Authorization", "Bearer " + token));
    }

    /** {@code POST …/start} with NO body — "start it now for the default length". */
    protected ResultActions startSprint(Ctx ctx, String token, UUID sprintId) throws Exception {
        return mockMvc.perform(post(sprintsBase(ctx) + "/" + sprintId + "/start")
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions startSprint(Ctx ctx, String token, UUID sprintId, String bodyJson)
            throws Exception {
        return mockMvc.perform(post(sprintsBase(ctx) + "/" + sprintId + "/start")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    protected ResultActions completionPreview(Ctx ctx, String token, UUID sprintId) throws Exception {
        return mockMvc.perform(get(sprintsBase(ctx) + "/" + sprintId + "/completion-preview")
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions completeSprint(Ctx ctx, String token, UUID sprintId, String bodyJson)
            throws Exception {
        return mockMvc.perform(post(sprintsBase(ctx) + "/" + sprintId + "/complete")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    /** The common completion: everything unfinished goes back to the backlog. */
    protected ResultActions completeToBacklog(Ctx ctx, String token, UUID sprintId) throws Exception {
        return completeSprint(ctx, token, sprintId, "{\"moveUnfinishedTo\":\"BACKLOG\"}");
    }

    protected ResultActions addIssuesToSprint(Ctx ctx, String token, UUID sprintId, String bodyJson)
            throws Exception {
        return mockMvc.perform(post(sprintsBase(ctx) + "/" + sprintId + "/issues")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    /** {@code POST …/sprints/{id}/issues} with the default BOTTOM placement. */
    protected ResultActions addIssuesToSprint(Ctx ctx, String token, UUID sprintId, UUID... issueIds)
            throws Exception {
        return addIssuesToSprint(ctx, token, sprintId, "{\"issueIds\":" + idArray(issueIds) + "}");
    }

    protected ResultActions removeIssueFromSprint(Ctx ctx, String token, UUID sprintId, UUID issueId)
            throws Exception {
        return mockMvc.perform(delete(sprintsBase(ctx) + "/" + sprintId + "/issues/" + issueId)
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions deleteSprint(Ctx ctx, String token, UUID sprintId, boolean force)
            throws Exception {
        return mockMvc.perform(delete(sprintsBase(ctx) + "/" + sprintId + (force ? "?force=true" : ""))
                .header("Authorization", "Bearer " + token));
    }

    /** The sprint list — a {@code PageResponse}, so the rows live under {@code content}. */
    protected JsonNode listSprints(Ctx ctx, String token, String query) throws Exception {
        var body = mockMvc.perform(get(sprintsBase(ctx) + (query == null ? "" : query))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** One sprint's response node, read back as the ctx owner. */
    protected JsonNode sprintNode(Ctx ctx, UUID sprintId) throws Exception {
        return json.readTree(getSprint(ctx, ctx.token(), sprintId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    // ============================================================ rank + planning view

    protected ResultActions rank(Ctx ctx, String token, long number, String bodyJson) throws Exception {
        return mockMvc.perform(post(ctx.issuesBase() + "/" + number + "/rank")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    /** Move {@code number} directly after {@code afterId} (and, optionally, nothing else). */
    protected ResultActions rankAfter(Ctx ctx, long number, UUID afterId) throws Exception {
        return rank(ctx, ctx.token(), number, "{\"afterIssueId\":\"" + afterId + "\"}");
    }

    protected ResultActions rankBefore(Ctx ctx, long number, UUID beforeId) throws Exception {
        return rank(ctx, ctx.token(), number, "{\"beforeIssueId\":\"" + beforeId + "\"}");
    }

    protected ResultActions rankBetween(Ctx ctx, long number, UUID afterId, UUID beforeId)
            throws Exception {
        return rank(ctx, ctx.token(), number,
                "{\"afterIssueId\":\"" + afterId + "\",\"beforeIssueId\":\"" + beforeId + "\"}");
    }

    /** {@code GET …/backlog} — the whole planning view in one request. */
    protected JsonNode backlogView(Ctx ctx, String token, String query) throws Exception {
        var url = "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/backlog"
                + (query == null ? "" : query);
        var body = mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected JsonNode backlogView(Ctx ctx) throws Exception {
        return backlogView(ctx, ctx.token(), null);
    }

    /** Issue keys of the planning view's BACKLOG section, in rank order. */
    protected static List<String> backlogKeys(JsonNode view) {
        return keysOf(view.get("backlog").get("issues"));
    }

    /** Issue keys of the sprint section whose sprint id matches, in rank order. */
    protected static List<String> sprintSectionKeys(JsonNode view, UUID sprintId) {
        for (var section : view.get("sprints")) {
            if (section.get("sprint").get("id").asText().equals(sprintId.toString())) {
                return keysOf(section.get("issues"));
            }
        }
        throw new AssertionError("no section for sprint " + sprintId);
    }

    protected static JsonNode sprintSection(JsonNode view, UUID sprintId) {
        for (var section : view.get("sprints")) {
            if (section.get("sprint").get("id").asText().equals(sprintId.toString())) {
                return section;
            }
        }
        throw new AssertionError("no section for sprint " + sprintId);
    }

    protected static List<String> keysOf(JsonNode issueArray) {
        var out = new ArrayList<String>();
        for (var i : issueArray) out.add(i.get("key").asText());
        return out;
    }

    // ============================================================ issue-side helpers

    protected static UUID idOf(JsonNode issueNode) {
        return UUID.fromString(issueNode.get("id").asText());
    }

    protected static long numberOf(JsonNode issueNode) {
        return issueNode.get("number").asLong();
    }

    /** {@code sprint.name} of an issue response node, or null when it sits in the backlog. */
    protected static String sprintName(JsonNode issueNode) {
        var s = issueNode.get("sprint");
        return s == null || s.isNull() ? null : s.get("name").asText();
    }

    protected static String sprintId(JsonNode issueNode) {
        var s = issueNode.get("sprint");
        return s == null || s.isNull() ? null : s.get("id").asText();
    }

    protected static String idArray(UUID... ids) {
        var parts = new ArrayList<String>();
        for (var id : ids) parts.add("\"" + id + "\"");
        return "[" + String.join(",", parts) + "]";
    }
}
