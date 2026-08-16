package com.hamstrack.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.workspace.entity.WorkspaceRole;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared HTTP + bootstrap helpers for the HD-31 (Components) integration suites.
 *
 * <p>Deliberately <em>extends</em> {@link LabelTestBase} rather than duplicating it:
 * the workspace/project/user scaffolding, the login helper and the whole issue-side
 * HTTP surface (create / patch / board / history) are identical for both slices of the
 * epic, and the component tests need the label helpers too (the two filters have to be
 * shown composing). Only the component-specific endpoints and the richer role/sibling
 * fixtures live here.
 */
public abstract class ComponentTestBase extends LabelTestBase {

    // ============================================================ component HTTP

    protected String componentsBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/components";
    }

    protected ResultActions postComponent(Ctx ctx, String token, String bodyJson) throws Exception {
        return mockMvc.perform(post(componentsBase(ctx))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    /** Create a component as the ctx owner and return its id. */
    protected UUID createComponent(Ctx ctx, String name) throws Exception {
        return createComponent(ctx, ctx.token(), "{\"name\":" + json.writeValueAsString(name) + "}");
    }

    protected UUID createComponent(Ctx ctx, String token, String bodyJson) throws Exception {
        var resp = postComponent(ctx, token, bodyJson)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asText());
    }

    protected ResultActions patchComponent(Ctx ctx, String token, UUID componentId, String bodyJson)
            throws Exception {
        return mockMvc.perform(patch(componentsBase(ctx) + "/" + componentId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    protected ResultActions getComponent(Ctx ctx, String token, UUID componentId) throws Exception {
        return mockMvc.perform(get(componentsBase(ctx) + "/" + componentId)
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions archiveComponent(Ctx ctx, String token, UUID componentId) throws Exception {
        return mockMvc.perform(post(componentsBase(ctx) + "/" + componentId + "/archive")
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions unarchiveComponent(Ctx ctx, String token, UUID componentId) throws Exception {
        return mockMvc.perform(post(componentsBase(ctx) + "/" + componentId + "/unarchive")
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions deleteComponent(Ctx ctx, String token, UUID componentId, boolean force)
            throws Exception {
        return mockMvc.perform(delete(componentsBase(ctx) + "/" + componentId + (force ? "?force=true" : ""))
                .header("Authorization", "Bearer " + token));
    }

    protected JsonNode listComponents(Ctx ctx, String token, String query) throws Exception {
        var body = mockMvc.perform(get(componentsBase(ctx) + (query == null ? "" : query))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected ResultActions componentUsage(Ctx ctx, String token, UUID componentId) throws Exception {
        return mockMvc.perform(get(componentsBase(ctx) + "/" + componentId + "/usage")
                .header("Authorization", "Bearer " + token));
    }

    /** The component names of a list response, in the order the server returned them. */
    protected static java.util.List<String> names(JsonNode listNode) {
        var out = new java.util.ArrayList<String>();
        for (var c : listNode) out.add(c.get("name").asText());
        return out;
    }

    /** {@code component.name} of an issue response node, or null. */
    protected static String componentName(JsonNode issueNode) {
        var c = issueNode.get("component");
        return c == null || c.isNull() ? null : c.get("name").asText();
    }

    protected static String assigneeName(JsonNode issueNode) {
        var a = issueNode.get("assignee");
        return a == null || a.isNull() ? null : a.get("displayName").asText();
    }

    // ============================================================ fixtures

    /**
     * A second project inside the SAME workspace as {@code ctx}, owned by the same
     * user — the fixture behind "same name in a sibling project → 201" and behind the
     * project-level (as opposed to tenant-level) isolation assertions.
     */
    protected Ctx siblingProject(Ctx ctx) throws Exception {
        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        var project = project(ws, ctx.owner());
        projectMember(project, ctx.owner(), ProjectRole.MANAGER);
        var configUrl = "/api/workspaces/" + ctx.wsId() + "/projects/" + project.getId() + "/config";
        var body = mockMvc.perform(get(configUrl).header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(ctx.wsId(), project.getId(), ctx.owner(), ctx.token(), json.readTree(body));
    }

    /**
     * A further user of {@code ctx}'s workspace with an explicit workspace role and an
     * explicit project role — {@code projectRole == null} means "member of the
     * workspace, not of the project", the case that must yield 403 rather than 404.
     */
    protected Actor actorWith(Ctx ctx, WorkspaceRole wsRole, ProjectRole projectRole) throws Exception {
        var u = user();
        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        member(ws, u, wsRole);
        if (projectRole != null) {
            projectMember(projectRepository.findById(ctx.projectId()).orElseThrow(), u, projectRole);
        }
        return new Actor(u, login(u));
    }

    /** Remove a user's workspace membership (the "lead left the workspace" fixture). */
    protected void removeFromWorkspace(Ctx ctx, com.hamstrack.auth.entity.User u) {
        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        workspaceMemberRepository.findByWorkspaceAndUser(ws, u)
                .ifPresent(workspaceMemberRepository::delete);
    }

    /** The paged (backlog) issue read — {@code size} is what switches the endpoint's shape. */
    protected JsonNode backlog(Ctx ctx, String query) throws Exception {
        var body = mockMvc.perform(get(ctx.issuesBase() + "?size=50" + (query == null ? "" : query))
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** Titles of a paged response, for set assertions. */
    protected static java.util.Set<String> pageTitles(JsonNode pageNode) {
        var out = new java.util.HashSet<String>();
        for (var i : pageNode.get("content")) out.add(i.get("title").asText());
        return out;
    }
}
