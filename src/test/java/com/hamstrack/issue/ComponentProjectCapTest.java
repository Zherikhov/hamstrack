package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code app.classification.max-components-per-project} (proposal §3.9, §5.4) — the
 * catalog bound that makes curator-gated component creation safe. The gate is not a
 * volume barrier: any authenticated user can create their own workspace, is its OWNER
 * and is therefore curator of every project in it, while
 * {@code ResolutionContextFactory} loads each visible project's WHOLE component catalog
 * on every {@code /search}, {@code /schema} and {@code /suggest} call.
 *
 * <p>Overridden to <strong>2</strong> here (default 500), mirroring
 * {@code LabelWorkspaceCapTest}.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.classification.max-components-per-project=2"
})
@AutoConfigureMockMvc
class ComponentProjectCapTest extends ComponentTestBase {

    @Test
    void theComponentAfterTheCapIs422AndTheCapIsPerProject() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        createComponent(ctx, "one");
        createComponent(ctx, "two");

        postComponent(ctx, ctx.token(), "{\"name\":\"three\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("maximum of 2 components")));

        // A sibling PROJECT has its own budget (components are project-owned)…
        postComponent(sibling, sibling.token(), "{\"name\":\"three\"}").andExpect(status().isCreated());
        // …and so does another workspace entirely.
        var other = newProject();
        postComponent(other, other.token(), "{\"name\":\"three\"}").andExpect(status().isCreated());
    }

    /**
     * The bypass the builder deliberately closed: archived rows KEEP their unique name
     * slot and are still loaded by {@code findAllByProject(includeArchived = true)}, so
     * counting only live rows would make the ceiling defeatable by
     * create → archive → repeat, forever. Deleting is what frees a slot.
     */
    @Test
    void archivedComponentsStillCountTowardsTheCeiling() throws Exception {
        var ctx = newProject();
        var one = createComponent(ctx, "one");
        createComponent(ctx, "two");

        archiveComponent(ctx, ctx.token(), one).andExpect(status().isOk());
        postComponent(ctx, ctx.token(), "{\"name\":\"three\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("maximum of 2 components")));
        // The archived row is still there (nothing was silently reclaimed)…
        assertThat(names(listComponents(ctx, ctx.token(), "?includeArchived=true")))
                .as("the archived row is still there — nothing was silently reclaimed to make room")
                .isEqualTo(List.of("one", "two"));

        // …and deleting it — not archiving it — is what frees the slot.
        deleteComponent(ctx, ctx.token(), one, true).andExpect(status().isNoContent());
        postComponent(ctx, ctx.token(), "{\"name\":\"three\"}").andExpect(status().isCreated());
    }
}
