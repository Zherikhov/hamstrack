package com.hamstrack.issue;

import com.hamstrack.issue.entity.Component;
import com.hamstrack.issue.repository.ComponentRepository;
import com.hamstrack.workspace.entity.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-31 component catalog CRUD and its edge-case table (proposal §5.3, §5.4, §5.6):
 * naming/uniqueness, archive semantics, the archived-project freeze, the auto-assign
 * configuration rules, delete-with-usage, history, and {@code ?withUsage=true}.
 *
 * <p>Everything is driven through the real API so every service invariant is exercised
 * on the way in; the only repository-level shortcut is the &gt;500-component volume
 * fixture, which is about batch mechanics rather than behavior.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ComponentApiTest extends ComponentTestBase {

    @Autowired ComponentRepository componentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    /** A non-breaking space — the lookalike that must NOT create a second component. */
    private static final String NBSP = " ";
    /** A zero-width joiner: a format character — stripped, never a distinguishing byte. */
    private static final String ZWJ = "‍";
    /** A zero-width space: invisible, and on its own not a name at all. */
    private static final String ZWSP = "​";

    // ==================================================== create / read / list

    @Test
    void createReturnsTheFullRepresentationAndTheListIsOrderedByLowercasedName() throws Exception {
        var ctx = newProject();
        var lead = addMember(ctx, WorkspaceRole.MEMBER);

        var body = json.readTree(postComponent(ctx, ctx.token(),
                        "{\"name\":\"Billing\",\"description\":\"invoices\",\"leadId\":\""
                                + lead.user().getId() + "\",\"autoAssign\":true}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Billing"))
                .andExpect(jsonPath("$.description").value("invoices"))
                .andExpect(jsonPath("$.leadId").value(lead.user().getId().toString()))
                .andExpect(jsonPath("$.leadName").value(lead.user().getDisplayName()))
                .andExpect(jsonPath("$.autoAssign").value(true))
                .andExpect(jsonPath("$.archived").value(false))
                .andReturn().getResponse().getContentAsString());
        assert body.get("issueCount").isNull() : "issueCount stays null unless withUsage was asked for";
        assert !body.get("createdAt").isNull() && !body.get("updatedAt").isNull()
                : "audited timestamps must be populated immediately after save (@CreatedDate)";

        // lower(name) ordering, not insertion order and not the ASCII order ("apple" > "Zebra").
        createComponent(ctx, "Zebra");
        createComponent(ctx, "apple");
        assert names(listComponents(ctx, ctx.token(), null)).equals(List.of("apple", "Billing", "Zebra"))
                : names(listComponents(ctx, ctx.token(), null));

        getComponent(ctx, ctx.token(), UUID.fromString(body.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Billing"));
    }

    // ==================================================== naming rules

    @Test
    void duplicateNamesAreRejectedCaseInsensitivelyWithinTheProjectButNotAcrossProjects()
            throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        createComponent(ctx, "Billing");

        postComponent(ctx, ctx.token(), "{\"name\":\"Billing\"}").andExpect(status().isConflict());
        postComponent(ctx, ctx.token(), "{\"name\":\"BILLING\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already exists in this project")));
        // Components are project-owned: the same name in a sibling project is fine.
        postComponent(sibling, sibling.token(), "{\"name\":\"Billing\"}").andExpect(status().isCreated());
        // A rename into an occupied slot is the same 409…
        var apple = createComponent(ctx, "apple");
        patchComponent(ctx, ctx.token(), apple, "{\"name\":\"billing\"}").andExpect(status().isConflict());
        // …while a pure casing change of the row's OWN name keeps its slot and succeeds.
        patchComponent(ctx, ctx.token(), apple, "{\"name\":\"APPLE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("APPLE"));
    }

    @Test
    void anArchivedComponentStillHoldsItsNameSlot() throws Exception {
        var ctx = newProject();
        var id = createComponent(ctx, "Billing");
        archiveComponent(ctx, ctx.token(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        postComponent(ctx, ctx.token(), "{\"name\":\"billing\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("archived")));
        // Unarchiving cannot conflict — the row already owns the slot.
        unarchiveComponent(ctx, ctx.token(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
    }

    /**
     * Name normalization is shared with labels through {@code ClassificationNames}
     * (§4.1): NFC, control/format characters dropped, every whitespace/separator run
     * collapsed to one ASCII space. The load-bearing case is the NON-ASCII whitespace
     * spoof — {@code "plat<NBSP>form"} and {@code "plat form"} must be the same name.
     * A regression here reopens the spoofing hole for labels too, since both features
     * share the helper.
     */
    @Test
    void nonAsciiWhitespaceIsNormalizedSoLookalikeNamesCollide() throws Exception {
        var ctx = newProject();
        createComponent(ctx, "plat form");

        postComponent(ctx, ctx.token(), "{\"name\":\"plat" + NBSP + "form\"}")
                .andExpect(status().isConflict());

        // …and the reverse direction: the NBSP spelling normalizes to the plain one, so
        // the plain one then collides with it.
        var other = newProject();
        createComponent(other, "plat" + NBSP + "form");
        assert names(listComponents(other, other.token(), null)).equals(List.of("plat form"))
                : "the stored display name must be the normalized, single-spaced one: "
                  + names(listComponents(other, other.token(), null));
        postComponent(other, other.token(), "{\"name\":\"plat form\"}").andExpect(status().isConflict());
        // Other spoof shapes collapse the same way: padding, tabs, doubled spaces…
        postComponent(other, other.token(), "{\"name\":\"  plat \\t  form  \"}")
                .andExpect(status().isConflict());
        // …and a zero-width joiner is a format character — stripped, not kept as a
        // distinguishing byte that would let a second "plat form" exist.
        postComponent(other, other.token(), "{\"name\":\"plat" + ZWJ + " form\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void blankAndOversizedNamesAreRejected() throws Exception {
        var ctx = newProject();
        // @NotBlank at the DTO boundary…
        postComponent(ctx, ctx.token(), "{\"name\":\"   \"}").andExpect(status().isBadRequest());
        // …and a name made only of invisible characters, which normalizes to nothing.
        postComponent(ctx, ctx.token(), "{\"name\":\"" + ZWSP + NBSP + "\"}")
                .andExpect(status().isBadRequest());
        // 80 characters is the limit AFTER normalization (components.name is VARCHAR(80)).
        postComponent(ctx, ctx.token(), "{\"name\":\"" + "a".repeat(80) + "\"}")
                .andExpect(status().isCreated());
        postComponent(ctx, ctx.token(), "{\"name\":\"" + "b".repeat(81) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("at most 80")));
    }

    // ==================================================== archive semantics on issues

    @Test
    void anArchivedComponentCannotBeAttachedButAnIssueAlreadyCarryingOneStaysEditable()
            throws Exception {
        var ctx = newProject();
        var stale = createComponent(ctx, "stale");
        var fresh = createComponent(ctx, "fresh");
        var carrier = createIssue(ctx, "carrier", "\"componentId\":\"" + stale + "\"");
        var bare = createIssue(ctx, "bare");
        archiveComponent(ctx, ctx.token(), stale).andExpect(status().isOk());

        // Attaching it is a 422 on create…
        postIssue(ctx, ctx.token(), "nope", "\"componentId\":\"" + stale + "\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("archived")));
        // …and on update, when it is an actual CHANGE.
        patchIssue(ctx, ctx.token(), bare.get("number").asLong(), "{\"componentId\":\"" + stale + "\"}")
                .andExpect(status().isUnprocessableContent());

        // The issue that already carries it keeps it, reports it as archived (so the UI
        // can dim it) and stays fully editable — including re-sending the same id, which
        // is not a change.
        long n = carrier.get("number").asLong();
        assert "stale".equals(componentName(getIssue(ctx, n)));
        assert getIssue(ctx, n).get("component").get("archived").asBoolean();
        patchIssue(ctx, ctx.token(), n, "{\"title\":\"still editable\"}").andExpect(status().isOk());
        patchIssue(ctx, ctx.token(), n, "{\"componentId\":\"" + stale + "\"}").andExpect(status().isOk());
        // Moving OFF an archived component is allowed — that is not "attaching" it.
        patchIssue(ctx, ctx.token(), n, "{\"componentId\":\"" + fresh + "\"}").andExpect(status().isOk());
        assert "fresh".equals(componentName(getIssue(ctx, n)));
        patchIssue(ctx, ctx.token(), n, "{\"clearComponent\":true}").andExpect(status().isOk());
        assert componentName(getIssue(ctx, n)) == null;

        // Archived rows leave the default catalog read but stay reachable explicitly.
        assert names(listComponents(ctx, ctx.token(), null)).equals(List.of("fresh"));
        assert names(listComponents(ctx, ctx.token(), "?includeArchived=true"))
                .equals(List.of("fresh", "stale"));
    }

    // ==================================================== archived project freeze

    @Test
    void everyComponentMutationIs409WhileTheProjectIsArchived() throws Exception {
        var ctx = newProject();
        var id = createComponent(ctx, "billing");
        var archived = createComponent(ctx, "old");
        archiveComponent(ctx, ctx.token(), archived).andExpect(status().isOk());
        archiveProject(ctx);

        postComponent(ctx, ctx.token(), "{\"name\":\"new\"}").andExpect(status().isConflict());
        patchComponent(ctx, ctx.token(), id, "{\"name\":\"renamed\"}").andExpect(status().isConflict());
        archiveComponent(ctx, ctx.token(), id).andExpect(status().isConflict());
        unarchiveComponent(ctx, ctx.token(), archived).andExpect(status().isConflict());
        deleteComponent(ctx, ctx.token(), id, true).andExpect(status().isConflict());

        // Reads are NOT frozen — an archived project's catalog stays inspectable.
        assert names(listComponents(ctx, ctx.token(), null)).equals(List.of("billing"));
        getComponent(ctx, ctx.token(), id).andExpect(status().isOk());

        // Unarchiving the project thaws curation again.
        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                        + "/unarchive").header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().is2xxSuccessful());
        patchComponent(ctx, ctx.token(), id, "{\"name\":\"renamed\"}").andExpect(status().isOk());
    }

    // ==================================================== auto-assign configuration

    @Test
    void autoAssignWithoutALeadIs422OnCreate() throws Exception {
        var ctx = newProject();
        postComponent(ctx, ctx.token(), "{\"name\":\"billing\",\"autoAssign\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Auto-assign needs a lead")));
        assert listComponents(ctx, ctx.token(), null).isEmpty() : "nothing may have been created";

        var lead = addMember(ctx, WorkspaceRole.MEMBER);
        postComponent(ctx, ctx.token(),
                        "{\"name\":\"billing\",\"autoAssign\":true,\"leadId\":\"" + lead.user().getId() + "\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void clearingTheLeadWhileAutoAssignStaysOnIs422() throws Exception {
        var ctx = newProject();
        var lead = addMember(ctx, WorkspaceRole.MEMBER);
        var id = createComponent(ctx, ctx.token(),
                "{\"name\":\"billing\",\"autoAssign\":true,\"leadId\":\"" + lead.user().getId() + "\"}");

        patchComponent(ctx, ctx.token(), id, "{\"clearLead\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Auto-assign needs a lead")));
        // Turning the switch off in the same request is the documented way out.
        var body = json.readTree(patchComponent(ctx, ctx.token(), id,
                        "{\"clearLead\":true,\"autoAssign\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoAssign").value(false))
                .andReturn().getResponse().getContentAsString());
        assert body.get("leadId").isNull() : "the lead must actually be cleared, got " + body;
    }

    /**
     * The narrow rule the builder chose (§5.4): {@code PATCH { autoAssign: true }} is a
     * 422 when it leaves {@code leadId} untouched and the STORED lead has left the
     * workspace — but a plain rename of that same component must still succeed. Both
     * halves are locked in, because "tighten it to every update" is the obvious
     * follow-up refactor and it would break renaming a component whose lead left months
     * ago.
     */
    @Test
    void assertingAutoAssignOverADepartedLeadIs422WhileAPlainRenameStillSucceeds() throws Exception {
        var ctx = newProject();
        var lead = addMember(ctx, WorkspaceRole.MEMBER);
        var id = createComponent(ctx, ctx.token(),
                "{\"name\":\"billing\",\"autoAssign\":false,\"leadId\":\"" + lead.user().getId() + "\"}");
        removeFromWorkspace(ctx, lead.user());

        // (a) the request ASSERTS autoAssign and does not re-pick a lead → 422, worded
        //     exactly like the "no lead at all" case (no membership oracle).
        patchComponent(ctx, ctx.token(), id, "{\"autoAssign\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Auto-assign needs a lead")));

        // (b) a plain rename on the same component still works, lead untouched.
        patchComponent(ctx, ctx.token(), id, "{\"name\":\"billing v2\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("billing v2"))
                .andExpect(jsonPath("$.leadId").value(lead.user().getId().toString()));
        // …as does a description-only edit.
        patchComponent(ctx, ctx.token(), id, "{\"description\":\"still curated\"}")
                .andExpect(status().isOk());

        // (c) supplying a lead who IS a member alongside the switch is accepted.
        var current = addMember(ctx, WorkspaceRole.MEMBER);
        patchComponent(ctx, ctx.token(), id,
                        "{\"autoAssign\":true,\"leadId\":\"" + current.user().getId() + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoAssign").value(true))
                .andExpect(jsonPath("$.leadId").value(current.user().getId().toString()));
    }

    @Test
    void aDepartedLeadIsStillReportedOnTheComponentSoTheUiCanFlagThem() throws Exception {
        var ctx = newProject();
        var lead = addMember(ctx, WorkspaceRole.MEMBER);
        createComponent(ctx, ctx.token(),
                "{\"name\":\"billing\",\"leadId\":\"" + lead.user().getId() + "\"}");
        removeFromWorkspace(ctx, lead.user());

        var row = listComponents(ctx, ctx.token(), null).get(0);
        assert row.get("leadId").asText().equals(lead.user().getId().toString())
                : "the row survives its lead leaving the workspace (§5.4), got " + row;
    }

    // ==================================================== delete

    @Test
    void deleteIs409WhileInUseAndForceNullsTheComponentOnEveryAffectedIssue() throws Exception {
        var ctx = newProject();
        var id = createComponent(ctx, "billing");
        var keep = createComponent(ctx, "keep");
        var one = createIssue(ctx, "one", "\"componentId\":\"" + id + "\"");
        var two = createIssue(ctx, "two", "\"componentId\":\"" + id + "\"");
        var untouched = createIssue(ctx, "untouched", "\"componentId\":\"" + keep + "\"");

        componentUsage(ctx, ctx.token(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueCount").value(2));
        deleteComponent(ctx, ctx.token(), id, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("used on 2 issue")));
        // Nothing happened: still catalogued, still attached.
        assert "billing".equals(componentName(getIssue(ctx, one.get("number").asLong())));

        deleteComponent(ctx, ctx.token(), id, true).andExpect(status().isNoContent());

        // The API says null…
        assert componentName(getIssue(ctx, one.get("number").asLong())) == null;
        assert componentName(getIssue(ctx, two.get("number").asLong())) == null;
        // …and so does the DB: no dangling id survived the delete (the §5.2 trap — a
        // stale managed Issue flushed afterwards would write the old id back).
        assert rawComponentId(one.get("id").asText()) == null
                : "issues.component_id must be NULL in the DB after a force delete";
        assert rawComponentId(two.get("id").asText()) == null;
        // An unrelated issue kept its own component.
        assert "keep".equals(componentName(getIssue(ctx, untouched.get("number").asLong())));
        // The catalog row is gone and its name slot is free again.
        assert names(listComponents(ctx, ctx.token(), "?includeArchived=true")).equals(List.of("keep"));
        postComponent(ctx, ctx.token(), "{\"name\":\"billing\"}").andExpect(status().isCreated());
        // Deleting an unused component needs no force at all.
        var unused = createComponent(ctx, "unused");
        deleteComponent(ctx, ctx.token(), unused, false).andExpect(status().isNoContent());
    }

    /**
     * The FK itself, independent of the service (§5.2, §5.6 first bullet):
     * {@code issues_component_fk} is declared
     * {@code ON DELETE SET NULL (component_id)}. This test drops the component row
     * straight through the repository — no {@code clearComponent} bulk update, no
     * service — so what it pins is the migration: the issues carrying it must be
     * <strong>nulled, not deleted</strong>. A migration typo of {@code ON DELETE
     * CASCADE} would quietly destroy issues, and every service-level test would still
     * pass because the service nulls the column first.
     *
     * <p>The column list in {@code SET NULL (component_id)} matters too: a bare
     * {@code SET NULL} would also try to null the {@code NOT NULL issues.workspace_id}
     * (the FK is composite), and this delete would fail outright.
     */
    @Test
    void deletingTheComponentRowDirectlyNullsTheColumnAndKeepsTheIssues() throws Exception {
        var ctx = newProject();
        var id = createComponent(ctx, "billing");
        var carrier = createIssue(ctx, "carrier", "\"componentId\":\"" + id + "\"");

        componentRepository.deleteById(id);
        componentRepository.flush();

        // The issue is still there…
        assert jdbcTemplate.queryForObject("SELECT count(*) FROM issues WHERE id = ?",
                Integer.class, UUID.fromString(carrier.get("id").asText())) == 1
                : "ON DELETE SET NULL, not CASCADE — the issues must survive";
        // …with a NULL component and no dangling id.
        assert rawComponentId(carrier.get("id").asText()) == null;
        assert componentName(getIssue(ctx, carrier.get("number").asLong())) == null;
    }

    /**
     * §5.4: a force delete writes NO per-issue history (one request must stay bounded),
     * while an ordinary component change on an issue DOES write exactly one
     * {@code component} entry carrying the old and new display names.
     */
    @Test
    void anOrdinaryComponentChangeWritesOneHistoryRowAndAForceDeleteWritesNone() throws Exception {
        var ctx = newProject();
        var billing = createComponent(ctx, "Billing");
        var ingest = createComponent(ctx, "Ingest");
        var issue = createIssue(ctx, "moves around");
        long n = issue.get("number").asLong();

        patchIssue(ctx, ctx.token(), n, "{\"componentId\":\"" + billing + "\"}").andExpect(status().isOk());
        patchIssue(ctx, ctx.token(), n, "{\"componentId\":\"" + ingest + "\"}").andExpect(status().isOk());
        // a no-op re-send must NOT write a row
        patchIssue(ctx, ctx.token(), n, "{\"componentId\":\"" + ingest + "\"}").andExpect(status().isOk());
        patchIssue(ctx, ctx.token(), n, "{\"clearComponent\":true}").andExpect(status().isOk());

        var transitions = componentTransitions(ctx, n);
        assert transitions.size() == 3 : "expected 3 component history rows, got " + transitions;
        assert transitions.contains("null->Billing") : transitions;
        assert transitions.contains("Billing->Ingest") : transitions;
        assert transitions.contains("Ingest->null") : transitions;

        // Now the force delete: the component vanishes from the issue with no new row.
        patchIssue(ctx, ctx.token(), n, "{\"componentId\":\"" + billing + "\"}").andExpect(status().isOk());
        int before = componentTransitions(ctx, n).size();
        deleteComponent(ctx, ctx.token(), billing, true).andExpect(status().isNoContent());
        assert componentName(getIssue(ctx, n)) == null;
        assert componentTransitions(ctx, n).size() == before
                : "a force delete must not write per-issue history (§5.4)";
    }

    // ==================================================== usage counts

    @Test
    void withUsageCountsOnlyThisProjectsIssues() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var billing = createComponent(ctx, "billing");
        var idle = createComponent(ctx, "idle");
        var siblingBilling = createComponent(sibling, "billing");   // same name, other project
        createIssue(ctx, "a", "\"componentId\":\"" + billing + "\"");
        createIssue(ctx, "b", "\"componentId\":\"" + billing + "\"");
        createIssue(sibling, "c", "\"componentId\":\"" + siblingBilling + "\"");

        var rows = listComponents(ctx, ctx.token(), "?withUsage=true");
        assert names(rows).equals(List.of("billing", "idle"));
        assert rows.get(0).get("issueCount").asInt() == 2 : "got " + rows.get(0);
        assert rows.get(1).get("issueCount").asInt() == 0 : "an unused component reports 0, not null";
        // The sibling's identically-named component counts only its own issue.
        assert listComponents(sibling, sibling.token(), "?withUsage=true").get(0)
                .get("issueCount").asInt() == 1;
        // Without the flag the field is null, not zero.
        assert listComponents(ctx, ctx.token(), null).get(0).get("issueCount").isNull();

        componentUsage(ctx, ctx.token(), billing)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueCount").value(2));
        componentUsage(ctx, ctx.token(), idle)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueCount").value(0));
    }

    /**
     * The usage-count query binds one JDBC parameter per component, so it is chunked at
     * {@code UsageCounts.BATCH_SIZE} (500) — PostgreSQL rejects a statement above 65 535
     * parameters outright. A catalog LARGER than one batch must therefore still work and
     * still be correct across the batch seam. The 501 rows go in through the repository
     * on purpose: this is volume, not behavior (and the API path is capped at
     * {@code max-components-per-project} anyway).
     */
    @Test
    void aCatalogLargerThanOneUsageBatchStillResolvesCorrectly() throws Exception {
        var ctx = newProject();
        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        var project = projectRepository.findById(ctx.projectId()).orElseThrow();

        var bulk = new java.util.ArrayList<Component>();
        for (int i = 0; i < 501; i++) {
            var c = new Component();
            c.setWorkspace(ws);
            c.setProject(project);
            c.setName(String.format("bulk-%04d", i));
            bulk.add(c);
        }
        componentRepository.saveAll(bulk);
        // One issue on the FIRST component and one on the LAST: the last only lands in
        // the second batch, so a broken chunk loop drops its count silently.
        var first = componentRepository.findByProjectAndNameIgnoreCase(project, "bulk-0000").orElseThrow();
        var last = componentRepository.findByProjectAndNameIgnoreCase(project, "bulk-0500").orElseThrow();
        createIssue(ctx, "on first", "\"componentId\":\"" + first.getId() + "\"");
        createIssue(ctx, "on last", "\"componentId\":\"" + last.getId() + "\"");

        var rows = listComponents(ctx, ctx.token(), "?withUsage=true");
        assert rows.size() == 501 : "expected the whole catalog, got " + rows.size();
        int counted = 0;
        for (var row : rows) {
            String name = row.get("name").asText();
            int count = row.get("issueCount").asInt();
            if (name.equals("bulk-0000") || name.equals("bulk-0500")) {
                assert count == 1 : name + " should count 1, got " + count;
                counted++;
            } else {
                assert count == 0 : name + " should count 0, got " + count;
            }
        }
        assert counted == 2 : "both seam components must have been found";
    }

    // ==================================================== helpers

    private void archiveProject(Ctx ctx) throws Exception {
        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/archive")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().is2xxSuccessful());
    }

    /**
     * The {@code component} history rows of an issue as {@code "old->new"} strings.
     * Deliberately unordered (a set-style assertion): several patches inside one test
     * can share a timestamp, and the transition pairs are what the acceptance criterion
     * is about.
     */
    private List<String> componentTransitions(Ctx ctx, long number) throws Exception {
        var out = new java.util.ArrayList<String>();
        for (var row : issueHistory(ctx, number).get("content")) {
            if (!row.get("field").asText().equals("component")) continue;
            out.add((row.get("oldValue").isNull() ? "null" : row.get("oldValue").asText())
                    + "->" + (row.get("newValue").isNull() ? "null" : row.get("newValue").asText()));
        }
        return out;
    }

    /** {@code issues.component_id} straight from the DB, bypassing JPA entirely. */
    private UUID rawComponentId(String issueId) {
        return jdbcTemplate.queryForObject(
                "SELECT component_id FROM issues WHERE id = ?", UUID.class, UUID.fromString(issueId));
    }
}
