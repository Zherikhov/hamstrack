package com.hamstrack.issue;

import com.hamstrack.issue.repository.ComponentRepository;
import com.hamstrack.issue.repository.IssueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-31 isolation — the project's top bug class (proposal §3.1, §5.6 "Cross-tenant").
 *
 * <p>Components are <strong>project</strong>-scoped, so there are two boundaries to
 * defend, not one: another <em>workspace</em> (the tenant boundary) and another
 * <em>project of the same workspace</em> (the ownership boundary). Both must behave
 * identically to a caller — a foreign id is a 404 on a direct read and a 422
 * "Unknown component" inside an issue payload, and neither may disclose existence.
 *
 * <ul>
 *   <li>GET / PATCH / DELETE / archive / unarchive / usage on a foreign id →
 *       <strong>404</strong>, for both flavours of "foreign";</li>
 *   <li>an issue payload carrying a foreign {@code componentId} →
 *       <strong>422</strong> "Unknown component" (an invalid field value — never a 404,
 *       which would disclose existence);</li>
 *   <li>the board filter with a foreign {@code componentId} → an empty result, never
 *       an error;</li>
 *   <li>{@code leadId} outside the workspace → <strong>422</strong> "Unknown lead",
 *       byte-identical to the response for a user id that never existed (no
 *       enumeration oracle);</li>
 *   <li>and the <strong>DB-level</strong> guard (§5.2): the composite FK
 *       {@code issues_component_fk (component_id, workspace_id)} makes a cross-tenant
 *       {@code issues.component_id} unrepresentable, so even a service bug that skipped
 *       every scoped lookup could not persist one.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ComponentTenancyTest extends ComponentTestBase {

    @Autowired IssueRepository issueRepository;
    @Autowired ComponentRepository componentRepository;

    // ==================================================== (a) foreign id → 404

    @Test
    void aComponentIdFromAnotherProjectOfTheSameWorkspaceIs404OnEveryIdEndpoint() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var foreign = createComponent(sibling, "sibling-only");
        var mine = createComponent(ctx, "mine");

        assert404OnEveryIdEndpoint(ctx, foreign);
        // The sibling's component survived every attempt, under its original name.
        assertThat(names(listComponents(sibling, sibling.token(), null)))
                .as("the sibling project's component survived every cross-project attempt, under its original name")
                .isEqualTo(List.of("sibling-only"));
        // …and this project's own component still resolves, so the 404s above are
        // about scoping, not about a broken endpoint.
        getComponent(ctx, ctx.token(), mine).andExpect(status().isOk());
    }

    @Test
    void aComponentIdFromAnotherWorkspaceIs404OnEveryIdEndpoint() throws Exception {
        var a = newProject();
        var b = newProject();
        var foreign = createComponent(b, "b-only");

        assert404OnEveryIdEndpoint(a, foreign);
        // A never-existed id is indistinguishable from B's.
        assert404OnEveryIdEndpoint(a, UUID.randomUUID());
        assertThat(names(listComponents(b, b.token(), null)))
                .as("the other workspace's component survived every cross-tenant attempt, under its original name")
                .isEqualTo(List.of("b-only"));
    }

    private void assert404OnEveryIdEndpoint(Ctx ctx, UUID componentId) throws Exception {
        getComponent(ctx, ctx.token(), componentId).andExpect(status().isNotFound());
        patchComponent(ctx, ctx.token(), componentId, "{\"name\":\"hijacked\"}")
                .andExpect(status().isNotFound());
        archiveComponent(ctx, ctx.token(), componentId).andExpect(status().isNotFound());
        unarchiveComponent(ctx, ctx.token(), componentId).andExpect(status().isNotFound());
        deleteComponent(ctx, ctx.token(), componentId, true).andExpect(status().isNotFound());
        componentUsage(ctx, ctx.token(), componentId).andExpect(status().isNotFound());
    }

    @Test
    void aComponentListNeverContainsAForeignRow() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var other = newProject();
        createComponent(ctx, "alpha");
        createComponent(sibling, "beta");
        createComponent(other, "gamma");

        assertThat(names(listComponents(ctx, ctx.token(), "?includeArchived=true&withUsage=true")))
                .as("the widest listing a caller can ask for still shows only its own project's rows")
                .isEqualTo(List.of("alpha"));
        assertThat(names(listComponents(sibling, sibling.token(), "?includeArchived=true&withUsage=true")))
                .as("the sibling project sees its own row and nothing else")
                .isEqualTo(List.of("beta"));
        assertThat(names(listComponents(other, other.token(), "?includeArchived=true&withUsage=true")))
                .as("the other workspace sees its own row and nothing else")
                .isEqualTo(List.of("gamma"));
    }

    // ==================================================== (b) foreign componentId on an issue

    @Test
    void anIssuePayloadWithAComponentFromAnotherProjectIs422UnknownComponentNot404() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var other = newProject();
        var siblingComponent = createComponent(sibling, "sibling-only");
        var foreignTenantComponent = createComponent(other, "tenant-b");
        var issue = createIssue(ctx, "mine");

        for (var foreign : List.of(siblingComponent, foreignTenantComponent, UUID.randomUUID())) {
            // PATCH — 422, the "invalid field value" answer, never a 404/403 that would
            // let the caller tell "exists elsewhere" from "does not exist".
            patchIssue(ctx, ctx.token(), issue.get("number").asLong(),
                    "{\"componentId\":\"" + foreign + "\"}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail", containsString("Unknown component")));
            // CREATE rejects it the same way, before anything is written.
            postIssue(ctx, ctx.token(), "new one", "\"componentId\":\"" + foreign + "\"")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail", containsString("Unknown component")));
        }
        assertThat(componentName(getIssue(ctx, issue.get("number").asLong())))
                .as("a rejected PATCH must not have attached anything")
                .isNull();
    }

    // ==================================================== (c) foreign componentId filter

    @Test
    void boardAndBacklogFilteredByAForeignComponentIdReturnEmptyNotAnError() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var foreign = createComponent(sibling, "sibling-only");
        var mine = createComponent(ctx, "mine");
        createIssue(ctx, "tagged", "\"componentId\":\"" + mine + "\"");
        createIssue(ctx, "plain");
        createIssue(sibling, "sibling issue", "\"componentId\":\"" + foreign + "\"");

        // A foreign id is just an id that matches nothing here — 200 + empty, both shapes.
        assertThat(board(ctx, "?componentId=" + foreign).get("issues"))
                .as("a foreign componentId must match nothing on the board")
                .isEmpty();
        assertThat(backlog(ctx, "&componentId=" + foreign).get("content"))
                .as("a foreign componentId must match nothing on the backlog")
                .isEmpty();
        // A never-existed id behaves identically.
        assertThat(board(ctx, "?componentId=" + UUID.randomUUID()).get("issues"))
                .as("a never-existed componentId behaves identically to a foreign one: empty, not an error and not a leak")
                .isEmpty();
        // And the local id still works, so the empties above are the filter, not a break.
        assertThat(titles(board(ctx, "?componentId=" + mine)))
                .as("the local id still filters, so the empty results above are the filter working and not the endpoint broken")
                .isEqualTo(Set.of("tagged"));
    }

    // ==================================================== (d) leadId enumeration

    @Test
    void aLeadOutsideTheWorkspaceIs422AndIndistinguishableFromAUserThatNeverExisted()
            throws Exception {
        var ctx = newProject();
        var other = newProject();

        // (i) a real user who is simply not a member of this workspace
        var stranger = other.owner();
        var strangerBody = postComponent(ctx, ctx.token(),
                        "{\"name\":\"billing\",\"leadId\":\"" + stranger.getId() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Unknown lead")))
                .andReturn().getResponse().getContentAsString();

        // (ii) a user id that never existed — must be byte-identical, otherwise the
        // endpoint is a membership oracle for accounts of other tenants.
        var ghostBody = postComponent(ctx, ctx.token(),
                        "{\"name\":\"billing\",\"leadId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(strangerBody).get("detail").asText())
                .as("a non-member lead and an unknown user id must be indistinguishable")
                .isEqualTo(json.readTree(ghostBody).get("detail").asText());

        // Same rule on update.
        var id = createComponent(ctx, "billing");
        patchComponent(ctx, ctx.token(), id, "{\"leadId\":\"" + stranger.getId() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Unknown lead")));
        // Nothing was created/changed by the rejected calls.
        assertThat(names(listComponents(ctx, ctx.token(), null)))
                .as("nothing was created or changed by the rejected calls")
                .isEqualTo(List.of("billing"));
        assertThat(listComponents(ctx, ctx.token(), null).get(0).get("leadId").isNull())
                .withFailMessage("…and the existing component did not acquire the foreign lead it refused")
                .isTrue();
    }

    // ==================================================== (e) the DB-level guard

    /**
     * §5.2: {@code issues_component_fk} is COMPOSITE over
     * {@code (component_id, workspace_id)} referencing
     * {@code components (id, workspace_id)}. This test bypasses every service check and
     * writes the column straight through the repository, so what it locks in is the
     * <em>schema</em>: an issue of tenant A carrying a component of tenant B is not
     * "rejected", it is unrepresentable.
     */
    @Test
    void aCrossTenantComponentReferenceCannotBePersistedAtAll() throws Exception {
        var a = newProject();
        var b = newProject();
        var aIssue = issueRepository.findById(
                UUID.fromString(createIssue(a, "a issue").get("id").asText())).orElseThrow();
        var bComponent = componentRepository.findById(createComponent(b, "b component")).orElseThrow();

        aIssue.setComponent(bComponent);
        assertThrows(DataIntegrityViolationException.class,
                () -> issueRepository.saveAndFlush(aIssue),
                "issues_component_fk must reject a component from another workspace");
    }

    /** Sanity anchor: the same write with both rows in ONE project persists fine. */
    @Test
    void aSameProjectComponentReferencePersistsFine() throws Exception {
        var ctx = newProject();
        var issue = issueRepository.findById(
                UUID.fromString(createIssue(ctx, "mine").get("id").asText())).orElseThrow();
        var component = componentRepository.findById(createComponent(ctx, "ok")).orElseThrow();

        issue.setComponent(component);
        issueRepository.saveAndFlush(issue);

        assertThat(componentName(getIssue(ctx, issue.getNumber())))
                .as("a same-project component reference persists and reads back — the tenancy guard refuses foreigners, not neighbours")
                .isEqualTo("ok");
    }

    /**
     * The boundary the composite FK does <em>not</em> draw, stated explicitly so nobody
     * mistakes the schema for the whole guard: the FK is keyed on the WORKSPACE, so a
     * component of a sibling project in the SAME workspace is representable at the DB
     * level. Keeping {@code issues.component_id} inside the issue's own project is
     * {@code ComponentService.resolveForIssue}'s job — the 422 asserted above. If this
     * test ever starts failing because the FK grew a project column, that is an
     * improvement: delete it and keep the stronger constraint.
     */
    @Test
    void theCompositeFkGuardsTheTenantBoundaryAndTheServiceGuardsTheProjectBoundary()
            throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var issue = issueRepository.findById(
                UUID.fromString(createIssue(ctx, "mine").get("id").asText())).orElseThrow();
        var siblingComponent = componentRepository.findById(
                createComponent(sibling, "sibling-only")).orElseThrow();

        issue.setComponent(siblingComponent);
        issueRepository.saveAndFlush(issue);   // same workspace → the FK is satisfied

        // …which is exactly why the service-level 422 (asserted above) is load-bearing.
        patchIssue(ctx, ctx.token(), issue.getNumber(),
                "{\"componentId\":\"" + siblingComponent.getId() + "\"}")
                .andExpect(status().isUnprocessableContent());
    }
}
