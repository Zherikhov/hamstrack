package com.hamstrack.search;

import com.hamstrack.issue.VersionTestBase;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.issue.repository.FieldSetItemRepository;
import com.hamstrack.issue.repository.FieldSetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-90 — <strong>one canonical key function on BOTH sides of every name→id lookup</strong>
 * ({@link SearchNames}).
 *
 * <p>The reported bug was a single symptom of an epic-wide split: names are normalized on
 * the WRITE path ({@code ClassificationNames}) but the HQL read path compared them with a
 * bare {@code toLowerCase()}, so a copy-pasted {@code fixVersion = "0.13.0 "} died with
 * {@code 422 No version named '0.13.0 '} while the identical name was sitting in the
 * catalog. Version was merely the field the user happened to hit — {@code label},
 * {@code component}, {@code affectsVersion} and custom-field SELECT labels shared the
 * defect, which is why this suite lives next to the other search tests rather than inside
 * a version-specific one.
 *
 * <p>Three things here are more than a happy-path re-run:
 * <ul>
 *   <li><strong>The non-breaking-space variants.</strong> {@code U+00A0} and friends are
 *       {@code \p{Z}} but NOT {@code \s}, so {@code String.strip()} and {@code trim()}
 *       leave them in place. A fix shaped like "trim the operand" passes every ASCII test
 *       and still fails the character a browser/word-processor copy actually produces —
 *       so the ASCII case alone would not have caught the class of bug.</li>
 *   <li><strong>The double-space catalog regression</strong>
 *       ({@link #aStoredDoubleSpaceCatalogNameStaysReachableByBothSpellings()}). Statuses,
 *       types and priorities come from the admin catalog and are <em>not</em> normalized
 *       on write, so a stored {@code "Deep  Freeze"} is a real, legal name. Normalizing
 *       only the HQL operand — the obvious one-line fix — would have folded the query to
 *       {@code "deep freeze"}, found nothing, and turned a working saved filter into a
 *       422: a regression traded for a fix. This test fails against that shortcut and
 *       passes only when the map keys are built with the same function.</li>
 *   <li><strong>The blank-operand guard</strong>
 *       ({@link #anOperandThatIsBlankAfterNormalizationIs422AnchoredOnTheField()}). The
 *       empty string is a REAL bucket in those maps (a catalog row whose whole name is
 *       whitespace folds into it), so "canonicalize both sides" without a guard would let
 *       {@code fixVersion = "   "} silently match. It must be a field-anchored 422 —
 *       neither a 500 nor a quiet hit.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SearchNameCanonicalizationTest extends VersionTestBase {

    @Autowired FieldDefRepository fieldDefRepository;
    @Autowired FieldSetRepository fieldSetRepository;
    @Autowired FieldSetItemRepository fieldSetItemRepository;

    /**
     * U+00A0 NO-BREAK SPACE — {@code \p{Z}} but NOT {@code \s}, so it survives both
     * {@code trim()} and {@code strip()}. Written as an escape (here and at every use
     * below) on purpose: an invisible character pasted literally into the source is
     * unreviewable, and one "tidy up whitespace" commit away from silently becoming a
     * plain space — which would quietly gut the only assertions that tell the real fix
     * apart from a {@code trim()}.
     */
    private static final String NBSP = "\u00A0";

    // ==================================================== the reported bug (versions)

    /**
     * The literal repro of HD-90, generalized over both version roles: a version created
     * as {@code "2.4.0"} must resolve from a trailing plain space AND from a trailing
     * non-breaking space, on {@code fixVersion} and on {@code affectsVersion} alike.
     */
    @Test
    void versionNamesResolveThroughTrailingPlainAndNonBreakingSpace() throws Exception {
        var ctx = newProject();
        var shipped = createVersion(ctx, "2.4.0");
        var broken = createVersion(ctx, "2.3.9");
        createIssue(ctx, "ships-in-240", fixVersionIdsJson(shipped));
        createIssue(ctx, "breaks-in-239", affectsVersionIdsJson(broken));
        createIssue(ctx, "unversioned");

        // the exact name still works (the fix must not move the baseline)
        assert found(ctx, "fixVersion = \"2.4.0\"").equals(Set.of("ships-in-240"));
        // …and now the copy-pasted spellings resolve to the same row
        assert found(ctx, "fixVersion = \"2.4.0 \"").equals(Set.of("ships-in-240"))
                : "a trailing ASCII space must not change which version this names";
        assert found(ctx, "fixVersion = \"2.4.0" + NBSP + "\"").equals(Set.of("ships-in-240"))
                : "U+00A0 is invisible and is NOT \\s — trim()-shaped fixes miss exactly this";
        assert found(ctx, "fixVersion = \"" + NBSP + " 2.4.0\"").equals(Set.of("ships-in-240"))
                : "a LEADING separator run folds away too";

        // affectsVersion is a second field over the same join table — prove it, don't assume it
        assert found(ctx, "affectsVersion = \"2.3.9 \"").equals(Set.of("breaks-in-239"));
        assert found(ctx, "affectsVersion = \"2.3.9" + NBSP + "\"").equals(Set.of("breaks-in-239"));

        // the tolerance is about NAMING, not about matching more rows: != and IN keep
        // their meaning when the operand arrives with stray separators.
        assert found(ctx, "fixVersion != \"2.4.0" + NBSP + "\"")
                .equals(Set.of("breaks-in-239", "unversioned"));
        assert found(ctx, "fixVersion IN (\"2.4.0 \", \"2.3.9 \")").equals(Set.of("ships-in-240"));

        // an operand that is not merely differently-spaced is still an honest 422
        search(ctx, "fixVersion = \"2.4.0.1\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.field").value("fixVersion"))
                .andExpect(jsonPath("$.detail", containsString("No version named '2.4.0.1'")));
    }

    // ==================================================== the rest of the epic

    /**
     * "One fix, four fields" — {@code label} and {@code component} run through the same
     * resolver helper and must behave identically. Both are normalized on WRITE, so the
     * only thing that was ever broken is the read side.
     */
    @Test
    void labelAndComponentNamesResolveThroughTrailingPlainAndNonBreakingSpace() throws Exception {
        var ctx = newProject();
        var hotfix = createLabel(ctx, "hot fix");
        var billing = createComponent(ctx, "Billing Core");
        createIssue(ctx, "labelled", labelIdsJson(hotfix));
        createIssue(ctx, "componented", "\"componentId\":\"" + billing + "\"");
        createIssue(ctx, "bare");

        assert found(ctx, "label = \"hot fix\"").equals(Set.of("labelled"));
        assert found(ctx, "label = \"hot fix \"").equals(Set.of("labelled"));
        assert found(ctx, "label = \"hot fix" + NBSP + "\"").equals(Set.of("labelled"));
        // an INNER separator that differs only in width is the same name, too
        assert found(ctx, "label = \"hot" + NBSP + "fix\"").equals(Set.of("labelled"))
                : "U+00A0 between the words is still one separator, so this is 'hot fix'";
        // the plural alias shares the descriptor and therefore the tolerance
        assert found(ctx, "labels = \"HOT FIX \"").equals(Set.of("labelled"))
                : "case-insensitivity and separator folding compose";

        assert found(ctx, "component = \"Billing Core\"").equals(Set.of("componented"));
        assert found(ctx, "component = \"Billing Core \"").equals(Set.of("componented"));
        assert found(ctx, "component = \"Billing" + NBSP + "Core\"").equals(Set.of("componented"));
        assert found(ctx, "components = \"billing core" + NBSP + "\"").equals(Set.of("componented"));

        search(ctx, "label = \"hot fixes\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.field").value("label"));
    }

    /**
     * <strong>The regression guard.</strong> The admin catalog does NOT normalize names,
     * so {@code "Deep  Freeze"} (two spaces) is a legitimately stored status — and a
     * saved filter quoting it verbatim works today. Canonicalizing only the operand would
     * break that filter. Both sides go through {@link SearchNames}, so the stored row
     * lands under {@code "deep freeze"} and BOTH spellings reach it.
     *
     * <p>Asserted for all three catalog primitives (status / type / priority), including
     * the priority {@code position} comparison path, which resolves the name through a
     * different method than {@code =} does.
     */
    @Test
    void aStoredDoubleSpaceCatalogNameStaysReachableByBothSpellings() throws Exception {
        var ctx = newProject();
        String tag = Long.toString(Math.abs(System.nanoTime() % 1_000_000));
        // Unique names so nothing in the shared global catalog can answer instead.
        String statusName = "Deep  Freeze " + tag;       // NB: two spaces after "Deep"
        String typeName = "Spike  Task " + tag;
        String priorityName = "Very  High " + tag;

        var statusId = createScopedStatus(ctx, statusName);
        var typeId = createScopedIssueType(ctx, typeName);
        var priorityId = createScopedPriority(ctx, priorityName);
        bindProject(ctx,
                createScopedWorkflow(ctx, statusId),
                createScopedPrioritySet(ctx, priorityId),
                createScopedIssueTypeSet(ctx, typeId));

        createIssueRaw(ctx, "frozen", typeId, statusId, priorityId);

        for (var field : new String[]{"status", "type", "priority"}) {
            String stored = switch (field) {
                case "status" -> statusName;
                case "type" -> typeName;
                default -> priorityName;
            };
            // 1) the EXACT stored spelling — this is what used to work and must keep working
            assert found(ctx, field + " = " + lit(stored)).equals(Set.of("frozen"))
                    : "operand-only normalization would have broken the stored spelling of " + field;
            // 2) the collapsed spelling — the new tolerance
            assert found(ctx, field + " = " + lit(stored.replace("  ", " "))).equals(Set.of("frozen"))
                    : "the single-space spelling must reach the same catalog row for " + field;
            // 3) …and the copy-paste variants, same as every other name
            assert found(ctx, field + " = " + lit(stored + " ")).equals(Set.of("frozen"));
            assert found(ctx, field + " = " + lit(stored + NBSP)).equals(Set.of("frozen"));
        }

        // priority comparisons resolve the name through resolvePriorityPosition(), a
        // SECOND code path — the set has exactly one priority, so >= must find it by
        // either spelling.
        assert found(ctx, "priority >= " + lit(priorityName)).equals(Set.of("frozen"));
        assert found(ctx, "priority >= " + lit(priorityName.replace("  ", " "))).equals(Set.of("frozen"))
                : "the ordered-comparison path needs the same canonical key as '='";

        // and an unknown name is still a 422 quoting what the user actually typed
        search(ctx, "status = \"Deep Frozen\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("status"))
                .andExpect(jsonPath("$.detail", containsString("No status named 'Deep Frozen'")));
    }

    /**
     * The empty key is a real bucket, so a blank-after-normalization operand must be
     * refused BEFORE the map lookup: a field-anchored 422, never a 500 and never a silent
     * match. Covered for a plain-whitespace operand and for one built only out of
     * invisible {@code \p{Cf}} format characters, which {@code isBlank()} does not catch.
     */
    @Test
    void anOperandThatIsBlankAfterNormalizationIs422AnchoredOnTheField() throws Exception {
        var ctx = newProject();
        var shipped = createVersion(ctx, "2.4.0");
        createIssue(ctx, "ships-in-240", fixVersionIdsJson(shipped));
        createLabel(ctx, "hot fix");
        createComponent(ctx, "Billing Core");

        // "   " → key "" → refused with its own message, not "No version named '   '"
        blankOperandIsRejected(ctx, "fixVersion", "   ");
        blankOperandIsRejected(ctx, "fixVersion", NBSP + NBSP);
        // U+200B/U+FEFF/U+202A are \p{Cf}: invisible, NOT whitespace, and dropped outright
        blankOperandIsRejected(ctx, "fixVersion", "\u200B\uFEFF\u202A");
        blankOperandIsRejected(ctx, "affectsVersion", " \t ");
        blankOperandIsRejected(ctx, "label", "  ");
        blankOperandIsRejected(ctx, "component", "\u200B");
        blankOperandIsRejected(ctx, "status", "   ");
        blankOperandIsRejected(ctx, "type", "   ");
        blankOperandIsRejected(ctx, "priority", "   ");

        // the ordered priority path guards the same way
        search(ctx, "priority >= \"   \"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.field").value("priority"));

        // and the blank operand did NOT quietly match anything on the way
        assert found(ctx, "fixVersion IS NOT EMPTY").equals(Set.of("ships-in-240"));
    }

    /**
     * {@code CustomFieldMeta.resolveOption} reads the very same map, so a SELECT /
     * MULTI_SELECT option LABEL — user-authored display text quoted in HQL, the same bug
     * class as a version name — gets the same tolerance. The option <em>id</em> is a slug
     * and keeps working unchanged; a blank operand is a 422, not a match on some option
     * whose label folded to the empty key.
     */
    @Test
    void customFieldSelectOptionLabelsResolveWithTheSameTolerance() throws Exception {
        var ctx = newProject();
        String suffix = "_" + Math.abs(UUID.randomUUID().hashCode());
        var severity = fieldDef("sev" + suffix, "Severity" + suffix, FieldType.SELECT,
                "{\"options\":[{\"id\":\"critical\",\"label\":\"Blocks  Release\"},"
                        + "{\"id\":\"minor\",\"label\":\"Cosmetic Only\"}]}");
        var environment = fieldDef("env" + suffix, "Environment" + suffix, FieldType.MULTI_SELECT,
                "{\"options\":[{\"id\":\"prod\",\"label\":\"Production EU\"},"
                        + "{\"id\":\"dev\",\"label\":\"Dev Box\"}]}");
        attachFieldSet(ctx, severity, environment);

        createIssue(ctx, "critical", "\"fields\":{\"" + severity.getId() + "\":\"critical\","
                + "\"" + environment.getId() + "\":[\"prod\"]}");
        createIssue(ctx, "cosmetic", "\"fields\":{\"" + severity.getId() + "\":\"minor\"}");

        String sev = severity.getKey();
        String env = environment.getKey();

        // the option ID keeps resolving (slug path, unchanged by HD-90)
        assert found(ctx, sev + " = \"critical\"").equals(Set.of("critical"));
        // the stored double-space LABEL resolves by its exact spelling…
        assert found(ctx, sev + " = \"Blocks  Release\"").equals(Set.of("critical"));
        // …and by the collapsed one, and with stray/invisible separators
        assert found(ctx, sev + " = \"Blocks Release\"").equals(Set.of("critical"));
        assert found(ctx, sev + " = \"Blocks  Release \"").equals(Set.of("critical"));
        assert found(ctx, sev + " = \"blocks release" + NBSP + "\"").equals(Set.of("critical"));

        assert found(ctx, env + " = \"Production EU \"").equals(Set.of("critical"));
        assert found(ctx, env + " = \"Production" + NBSP + "EU\"").equals(Set.of("critical"));
        assert found(ctx, env + " IN (\"Dev Box \", \"Production EU \")").equals(Set.of("critical"));

        // unknown option → 422 anchored on the custom-field key (not a silent empty page)
        search(ctx, sev + " = \"Blocks Everything\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value(sev));
        // a blank operand must not reach the empty-key bucket either
        search(ctx, sev + " = \"  \"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.field").value(sev));
        search(ctx, sev + " = \"\u200B\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.field").value(sev));
    }

    // ==================================================== helpers

    private void blankOperandIsRejected(Ctx ctx, String field, String operand) throws Exception {
        search(ctx, field + " = " + lit(operand))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value(field))
                .andExpect(jsonPath("$.detail", containsString("non-empty name")));
    }

    /** A JSON string literal — for REQUEST BODIES (admin payloads, issue titles). */
    private String q(String raw) throws Exception {
        return json.writeValueAsString(raw);
    }

    /**
     * An <strong>HQL</strong> string literal, which is deliberately NOT the same encoder
     * as {@link #q}. HQL allows only {@code \" \' \\} inside a quoted string, so reusing
     * JSON escaping turns a tab operand into the two characters {@code \t} and earns a
     * {@code PARSE_ERROR} about an illegal escape — a test that then proves nothing about
     * name resolution. Here a tab travels as a real tab: {@link #search} JSON-encodes the
     * whole query once, and the server decodes it back before the lexer sees it.
     */
    private String lit(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Set<String> found(Ctx ctx, String hql) throws Exception {
        var body = search(ctx, hql)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.HashSet<String>();
        for (var row : json.readTree(body).get("content")) {
            out.add(row.get("issue").get("title").asText());
        }
        return out;
    }

    /**
     * The charset is stated explicitly. {@code MockMvc} encodes a String body as UTF-8,
     * and the whole suite hinges on U+00A0/U+200B surviving the round trip intact — a
     * default-charset mismatch would mangle them into something that "fails" for a
     * reason unrelated to name resolution.
     */
    private ResultActions search(Ctx ctx, String hql) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/search")
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(java.nio.charset.StandardCharsets.UTF_8)
                .content("{\"query\":" + json.writeValueAsString(hql) + ",\"size\":100}"));
    }

    // ---- project-scoped admin catalog (names stored VERBATIM — that is the point) ----

    private String projectAdminBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/admin";
    }

    private UUID createdId(ResultActions actions) throws Exception {
        var body = actions.andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    private UUID postAdmin(Ctx ctx, String path, String body) throws Exception {
        return createdId(mockMvc.perform(post(projectAdminBase(ctx) + path)
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)));
    }

    private UUID createScopedStatus(Ctx ctx, String name) throws Exception {
        return postAdmin(ctx, "/statuses",
                "{\"name\":" + q(name) + ",\"category\":\"IN_PROGRESS\"}");
    }

    private UUID createScopedIssueType(Ctx ctx, String name) throws Exception {
        return postAdmin(ctx, "/issue-types", "{\"name\":" + q(name) + "}");
    }

    private UUID createScopedPriority(Ctx ctx, String name) throws Exception {
        return postAdmin(ctx, "/priorities", "{\"name\":" + q(name) + "}");
    }

    private UUID createScopedWorkflow(Ctx ctx, UUID statusId) throws Exception {
        return postAdmin(ctx, "/workflows", "{\"name\":\"WF-" + System.nanoTime()
                + "\",\"statusIds\":[\"" + statusId + "\"]}");
    }

    private UUID createScopedPrioritySet(Ctx ctx, UUID priorityId) throws Exception {
        return postAdmin(ctx, "/priority-sets", "{\"name\":\"PS-" + System.nanoTime()
                + "\",\"items\":[{\"priorityId\":\"" + priorityId + "\",\"isDefault\":true}]}");
    }

    private UUID createScopedIssueTypeSet(Ctx ctx, UUID typeId) throws Exception {
        return postAdmin(ctx, "/issue-type-sets", "{\"name\":\"TS-" + System.nanoTime()
                + "\",\"typeIds\":[\"" + typeId + "\"]}");
    }

    private void bindProject(Ctx ctx, UUID workflowId, UUID prioritySetId, UUID issueTypeSetId)
            throws Exception {
        mockMvc.perform(patch(projectAdminBase(ctx) + "/bindings")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowId\":\"" + workflowId + "\","
                                + "\"prioritySetId\":\"" + prioritySetId + "\","
                                + "\"issueTypeSetId\":\"" + issueTypeSetId + "\"}"))
                .andExpect(status().isOk());
    }

    /** Create an issue with explicit catalog ids (the ctx config predates the rebinding). */
    private void createIssueRaw(Ctx ctx, String title, UUID typeId, UUID statusId, UUID priorityId)
            throws Exception {
        // The URL is spelled out rather than taken from Ctx.issuesBase(), which is
        // package-private to com.hamstrack.issue — widening it for one test would be a
        // change to a base class four suites share.
        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                        + "/issues")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":" + q(title) + ",\"typeId\":\"" + typeId
                                + "\",\"statusId\":\"" + statusId
                                + "\",\"priorityId\":\"" + priorityId + "\"}"))
                .andExpect(status().isCreated());
    }

    // ---- custom fields ----

    private FieldDef fieldDef(String key, String name, FieldType type, String configJson)
            throws Exception {
        var f = new FieldDef();
        f.setKey(key);
        f.setName(name);
        f.setType(type);
        if (configJson != null) f.setConfig(json.readTree(configJson));
        return fieldDefRepository.save(f);
    }

    private void attachFieldSet(Ctx ctx, FieldDef... fields) throws Exception {
        var set = new FieldSet();
        set.setName("Set-" + System.nanoTime());
        var saved = fieldSetRepository.save(set);
        short position = 0;
        for (var field : fields) {
            var item = new FieldSetItem();
            item.setSet(saved);
            item.setField(field);
            item.setPosition(position++);
            item.setRequired(false);
            item.setShowOnCreate(true);
            fieldSetItemRepository.save(item);
        }
        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        project.setFieldSet(saved);
        projectRepository.save(project);
        // Re-read the config so a later assertion failure cannot be blamed on a stale
        // binding rather than on resolution.
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                        + "/config").header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk());
    }
}
