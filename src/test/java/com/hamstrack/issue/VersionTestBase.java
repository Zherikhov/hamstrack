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
 * Shared HTTP + fixture helpers for the HD-32 (Versions) integration suites.
 *
 * <p>Third link in the epic's base chain — {@code LabelTestBase} →
 * {@link ComponentTestBase} → here — rather than a fourth copy of the scaffolding.
 * Everything HD-32 needs from the earlier slices is already there: the
 * user/workspace/project bootstrap, {@code login}, the whole issue-side HTTP surface
 * (create / patch / board / backlog / history), {@code siblingProject},
 * {@code actorWith} for the role matrix, and the label + component helpers the
 * "composes with the other filters" assertions need. Only the version endpoints and
 * the release-lifecycle fixtures live here.
 */
public abstract class VersionTestBase extends ComponentTestBase {

    // ============================================================ version HTTP

    protected String versionsBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/versions";
    }

    protected ResultActions postVersion(Ctx ctx, String token, String bodyJson) throws Exception {
        return mockMvc.perform(post(versionsBase(ctx))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    /** Create a version as the ctx owner and return its id. */
    protected UUID createVersion(Ctx ctx, String name) throws Exception {
        return createVersion(ctx, ctx.token(), "{\"name\":" + json.writeValueAsString(name) + "}");
    }

    /**
     * Create a version with a planned release date. Deliberately NOT an overload of
     * {@link #createVersion(Ctx, String, String)} — that one's second parameter is a
     * token, and two three-arg {@code (Ctx, String, String)} methods have the same
     * erasure.
     */
    protected UUID createPlannedVersion(Ctx ctx, String name, String releaseDate) throws Exception {
        return createVersion(ctx, ctx.token(), "{\"name\":" + json.writeValueAsString(name)
                + ",\"releaseDate\":\"" + releaseDate + "\"}");
    }

    protected UUID createVersion(Ctx ctx, String token, String bodyJson) throws Exception {
        var resp = postVersion(ctx, token, bodyJson)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asText());
    }

    protected ResultActions patchVersion(Ctx ctx, String token, UUID versionId, String bodyJson)
            throws Exception {
        return mockMvc.perform(patch(versionsBase(ctx) + "/" + versionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    protected ResultActions getVersion(Ctx ctx, String token, UUID versionId) throws Exception {
        return mockMvc.perform(get(versionsBase(ctx) + "/" + versionId)
                .header("Authorization", "Bearer " + token));
    }

    /** {@code POST …/release} with no body — "release it now, keep the planned date". */
    protected ResultActions releaseVersion(Ctx ctx, String token, UUID versionId) throws Exception {
        return mockMvc.perform(post(versionsBase(ctx) + "/" + versionId + "/release")
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions releaseVersion(Ctx ctx, String token, UUID versionId, String bodyJson)
            throws Exception {
        return mockMvc.perform(post(versionsBase(ctx) + "/" + versionId + "/release")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    protected ResultActions unreleaseVersion(Ctx ctx, String token, UUID versionId) throws Exception {
        return mockMvc.perform(post(versionsBase(ctx) + "/" + versionId + "/unrelease")
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions archiveVersion(Ctx ctx, String token, UUID versionId) throws Exception {
        return mockMvc.perform(post(versionsBase(ctx) + "/" + versionId + "/archive")
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions unarchiveVersion(Ctx ctx, String token, UUID versionId) throws Exception {
        return mockMvc.perform(post(versionsBase(ctx) + "/" + versionId + "/unarchive")
                .header("Authorization", "Bearer " + token));
    }

    /** {@code DELETE …/versions/{id}} with an arbitrary query string (force / remapToId). */
    protected ResultActions deleteVersion(Ctx ctx, String token, UUID versionId, String query)
            throws Exception {
        return mockMvc.perform(delete(versionsBase(ctx) + "/" + versionId
                + (query == null ? "" : query))
                .header("Authorization", "Bearer " + token));
    }

    protected JsonNode listVersions(Ctx ctx, String token, String query) throws Exception {
        var body = mockMvc.perform(get(versionsBase(ctx) + (query == null ? "" : query))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected ResultActions versionUsage(Ctx ctx, String token, UUID versionId) throws Exception {
        return mockMvc.perform(get(versionsBase(ctx) + "/" + versionId + "/usage")
                .header("Authorization", "Bearer " + token));
    }

    /** One version's list row, by id — the progress counters live only on the list/GET. */
    protected JsonNode versionNode(Ctx ctx, UUID versionId) throws Exception {
        return json.readTree(getVersion(ctx, ctx.token(), versionId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    // ============================================================ issue-side helpers

    protected static String fixVersionIdsJson(UUID... ids) {
        return versionIdsJson("fixVersionIds", ids);
    }

    protected static String affectsVersionIdsJson(UUID... ids) {
        return versionIdsJson("affectsVersionIds", ids);
    }

    private static String versionIdsJson(String field, UUID... ids) {
        var parts = new ArrayList<String>();
        for (var id : ids) parts.add("\"" + id + "\"");
        return "\"" + field + "\":[" + String.join(",", parts) + "]";
    }

    /** The {@code fixVersions} names carried by an issue response node. */
    protected static List<String> fixVersionNames(JsonNode issueNode) {
        return refNames(issueNode, "fixVersions");
    }

    /** The {@code affectsVersions} names carried by an issue response node. */
    protected static List<String> affectsVersionNames(JsonNode issueNode) {
        return refNames(issueNode, "affectsVersions");
    }

    private static List<String> refNames(JsonNode issueNode, String field) {
        var out = new ArrayList<String>();
        for (var v : issueNode.get(field)) out.add(v.get("name").asText());
        return out;
    }

    /** Names of a version LIST response, in the order the server returned them. */
    protected static List<String> versionNames(JsonNode listNode) {
        var out = new ArrayList<String>();
        for (var v : listNode) out.add(v.get("name").asText());
        return out;
    }

    /**
     * Move an issue into a DONE-category status — the fixture behind every
     * "done issues keep the released version" / {@code doneIssueCount} assertion.
     */
    protected void markDone(Ctx ctx, long number) throws Exception {
        patchIssue(ctx, ctx.token(), number, "{\"statusId\":\"" + doneStatusId(ctx) + "\"}")
                .andExpect(status().isOk());
    }

    /** The first DONE-category status of the ctx project's workflow. */
    protected UUID doneStatusId(Ctx ctx) {
        for (var s : ctx.config().get("statuses")) {
            if (s.get("category").asText().equals("DONE")) {
                return UUID.fromString(s.get("id").asText());
            }
        }
        throw new AssertionError("no DONE-category status in the workflow");
    }
}
