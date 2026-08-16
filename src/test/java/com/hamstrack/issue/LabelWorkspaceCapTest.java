package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code app.classification.max-labels-per-workspace} (proposal §3.9) — the catalog
 * bound that makes self-serve label creation safe: any member may create a label, so
 * without a cap one member could grow an unbounded catalog that every {@code /search},
 * {@code /schema} and picker load then has to resolve. Overridden to <strong>2</strong>
 * here (default 1000).
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.classification.max-labels-per-workspace=2"
})
@AutoConfigureMockMvc
class LabelWorkspaceCapTest extends LabelTestBase {

    @Test
    void theLabelAfterTheCapIs422AndTheCapIsPerWorkspace() throws Exception {
        var ctx = newProject();
        var other = newProject();
        createLabel(ctx, "one");
        createLabel(ctx, "two");

        postLabel(ctx, ctx.token(), "{\"name\":\"three\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("maximum of 2 labels")));

        // A different workspace has its own budget — the cap is per tenant.
        postLabel(other, other.token(), "{\"name\":\"three\"}").andExpect(status().isCreated());

        // Archived rows still occupy the catalog (they keep their unique name slot),
        // so archiving does NOT free a slot — deleting does.
        var one = java.util.UUID.fromString(
                listLabels(ctx, ctx.token(), null).get(0).get("id").asText());
        archiveLabel(ctx, ctx.token(), one).andExpect(status().isOk());
        postLabel(ctx, ctx.token(), "{\"name\":\"three\"}")
                .andExpect(status().isUnprocessableContent());
        deleteLabel(ctx, ctx.token(), one, true).andExpect(status().isNoContent());
        postLabel(ctx, ctx.token(), "{\"name\":\"three\"}").andExpect(status().isCreated());
    }
}
