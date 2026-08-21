package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.LabelTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-121 — the {@code parent} operand is a paste target too.
 *
 * <p>{@link HqlParentResolver} cleaned its operand with {@code String.trim()}, which cuts
 * only at or below U+0020. A key copied out of a chat client, a wiki or any rendered page
 * arrives wrapped in U+00A0, so {@code parent = "DEMO-12"} failed to resolve while the
 * value on screen looked exactly right — the same class of bug HD-90 fixed for
 * name-resolved fields and deliberately left out of that ticket's scope, on the grounds
 * that a key is a machine format. It is; that simply turned out not to be the property
 * that decides whether cleaning is needed. What decides it is arriving as a string
 * <em>literal</em>, which a machine format does just as readily as a display name.
 *
 * <p>Four things here are more than a happy-path re-run:
 * <ul>
 *   <li><strong>The fix is not {@code strip()}.</strong>
 *       {@link #stripIsNotTheFixAndCanonicalDoesNotFoldCase()} pins the premise: U+00A0,
 *       U+2007 and U+202F are {@code \p{Z}} but NOT {@link Character#isWhitespace}, so
 *       {@code strip()} — the obvious "Unicode-aware trim" — removes U+3000 and leaves
 *       exactly the three a paste produces. A {@code strip()}-shaped fix passes every
 *       ASCII assertion and still ships the reported bug.</li>
 *   <li><strong>The fix is not {@code SearchNames.key} either.</strong> The same test pins
 *       that {@code canonical} preserves case, and
 *       {@link #aLowerCasedKeyResolvesWithoutTheOperandBeingFolded()} shows why the fold
 *       would be redundant: case-insensitivity is settled in SQL by
 *       {@code upper(i.project.key) = upper(:projectKey)}, so folding the operand could
 *       only change the spelling a 422 echoes back.</li>
 *   <li><strong>A blank-after-cleaning operand</strong> is a field-anchored 422 of its own
 *       rather than a confusing {@code Invalid issue key ''} — and the values that reach
 *       it are mostly ones {@code String.isBlank()} calls non-blank.</li>
 *   <li><strong>An interior separator</strong> is a format error rather than a database
 *       round trip that then reports the key as missing.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ParentKeyCanonicalizationTest extends LabelTestBase {

    /**
     * U+00A0 NO-BREAK SPACE — {@code \p{Z}} but NOT {@code \s}, so it survives both
     * {@code trim()} and {@code strip()}. Written as an escape (here and at every use
     * below) on purpose: an invisible character pasted literally into the source is
     * unreviewable, and one "tidy up whitespace" commit away from silently becoming a
     * plain space — which would turn every assertion below into a duplicate of the ASCII
     * case it exists to be told apart from.
     */
    private static final String NBSP = "\u00A0";

    /** U+2007 FIGURE SPACE and U+202F NARROW NO-BREAK SPACE — same class as {@link #NBSP}. */
    private static final String FIGURE_SPACE = "\u2007";
    private static final String NARROW_NBSP = "\u202F";

    /**
     * U+3000 IDEOGRAPHIC SPACE — the one separator in this family that
     * {@link Character#isWhitespace} <em>does</em> accept, so {@code strip()} removes it.
     * It is here to keep "strip() is Unicode-aware" from being read as "strip() is enough".
     */
    private static final String IDEOGRAPHIC_SPACE = "\u3000";

    /** U+200B ZERO WIDTH SPACE — {@code \p{Cf}}: neither whitespace nor a separator. */
    private static final String ZWSP = "\u200B";

    /** U+202E RIGHT-TO-LEFT OVERRIDE — {@code \p{Cf}}, and the reason an echo is cleaned. */
    private static final String RLO = "\u202E";

    // ==================================================== the reported bug

    /**
     * The literal repro: a key that resolves must keep resolving when the paste brought a
     * non-breaking space with it, at either end, and the tolerance must not change which
     * issues the filter names.
     */
    @Test
    void aParentKeyResolvesThroughLeadingAndTrailingNonBreakingSpace() throws Exception {
        var ctx = newProject();
        var epic = createEpic(ctx, "the-epic");
        String key = epic.get("key").asText();
        createIssue(ctx, "the-child", "\"parentId\":\"" + epic.get("id").asText() + "\"");
        createIssue(ctx, "unrelated");

        // the exact key still works (the fix must not move the baseline)
        assertThat(found(ctx, "parent = " + lit(key))).containsExactly("the-child");
        // …and now the copy-pasted spellings resolve to the same issue
        assertThat(found(ctx, "parent = " + lit(key + " ")))
                .as("a trailing ASCII space must not change which issue this names")
                .containsExactly("the-child");
        assertThat(found(ctx, "parent = " + lit(key + NBSP)))
                .as("U+00A0 is invisible and is NOT \\s — trim()/strip()-shaped fixes miss exactly this")
                .containsExactly("the-child");
        assertThat(found(ctx, "parent = " + lit(NBSP + key)))
                .as("a LEADING separator folds away too")
                .containsExactly("the-child");
        assertThat(found(ctx, "parent = " + lit(key + FIGURE_SPACE))).containsExactly("the-child");
        assertThat(found(ctx, "parent = " + lit(key + NARROW_NBSP))).containsExactly("the-child");
        assertThat(found(ctx, "parent = " + lit(key + IDEOGRAPHIC_SPACE))).containsExactly("the-child");
        assertThat(found(ctx, "parent = " + lit(NBSP + " " + key + ZWSP)))
                .as("a run of mixed separators plus a zero-width character is still one key")
                .containsExactly("the-child");

        // The tolerance is about NAMING the parent, not about matching more rows: the
        // negation keeps its meaning when the operand arrives with stray separators.
        assertThat(found(ctx, "parent != " + lit(key + NBSP)))
                .containsExactlyInAnyOrder("the-epic", "unrelated");
    }

    /**
     * The operand is NOT case-folded, and does not need to be:
     * {@code IssueRepository.findIdByWorkspaceAndKey} compares
     * {@code upper(i.project.key) = upper(:projectKey)}, so a lower-cased key resolves
     * either way. That is the whole argument against reusing {@code SearchNames.key} here
     * — the fold cannot change which issue is found, only the spelling a refusal would
     * quote back at the caller.
     */
    @Test
    void aLowerCasedKeyResolvesWithoutTheOperandBeingFolded() throws Exception {
        var ctx = newProject();
        var epic = createEpic(ctx, "the-epic");
        String key = epic.get("key").asText();
        createIssue(ctx, "the-child", "\"parentId\":\"" + epic.get("id").asText() + "\"");

        assertThat(found(ctx, "parent = " + lit(key.toLowerCase(Locale.ROOT))))
                .containsExactly("the-child");
        assertThat(found(ctx, "parent = " + lit(key.toLowerCase(Locale.ROOT) + NBSP)))
                .containsExactly("the-child");
    }

    // ==================================================== the refusals

    /**
     * An operand that is blank once cleaned is a field-anchored 422 with its own message —
     * the same shape {@code HqlValueResolver.requireName} gives a blank name — and not an
     * NPE, not a confusing {@code Invalid issue key ''}, and above all not a silent empty
     * page. Only the first two below are "blank" to Java: {@code String.isBlank()} is
     * false for U+00A0 and for the format characters, which is why the guard has to test
     * the CLEANED value rather than the raw one.
     */
    @Test
    void anOperandThatIsBlankAfterCleaningIs422AnchoredOnTheField() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "any-issue");

        assertThat(NBSP.isBlank()).as("premise: U+00A0 is not 'blank' to Java").isFalse();
        assertThat(ZWSP.isBlank()).as("premise: U+200B is not 'blank' to Java").isFalse();

        for (String operand : List.of("", "   ", NBSP, NBSP + " " + NARROW_NBSP, ZWSP, RLO)) {
            search(ctx, "parent = " + lit(operand))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                    .andExpect(jsonPath("$.field").value("parent"))
                    .andExpect(jsonPath("$.detail", containsString("non-empty issue key")));
        }
    }

    /**
     * A separator that survives cleaning is <em>inside</em> the operand, and neither half
     * of an issue key can hold one — so it is a malformed key, answered as such before any
     * lookup. Both spellings of the mistake now agree: before HD-121,
     * {@code "ABC - 12"} was a format error (its number half fails to parse) while
     * {@code "ABC<NBSP>-12"} reached the database with a project key of {@code "ABC "} and
     * came back as "no issue matching", implying such a key could exist.
     *
     * <p>The echoed key is also asserted to carry none of the invisible characters the
     * caller sent, which is the "quote the cleaned key" half of the decision: everything
     * that renders survives, and what is dropped is what would have corrupted the message
     * it landed in.
     */
    @Test
    void anInteriorSeparatorIsAMalformedKeyNotAMissingIssue() throws Exception {
        var ctx = newProject();
        var epic = createEpic(ctx, "the-epic");
        String key = epic.get("key").asText();
        int dash = key.lastIndexOf('-');
        String project = key.substring(0, dash);
        String number = key.substring(dash + 1);

        for (String operand : List.of(project + NBSP + "-" + number,
                                      project + " - " + number,
                                      project + "-" + NBSP + number)) {
            search(ctx, "parent = " + lit(operand))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                    .andExpect(jsonPath("$.field").value("parent"))
                    .andExpect(jsonPath("$.detail", containsString("expected PROJECT-NUMBER")))
                    .andExpect(jsonPath("$.detail", not(containsString(NBSP))));
        }

        // A well-formed key that simply resolves to nothing keeps its own, different
        // refusal — the interior-separator guard must not swallow the "not found" case.
        search(ctx, "parent = " + lit(project + "-999999" + NBSP))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.field").value("parent"))
                .andExpect(jsonPath("$.detail", containsString("No issue matching")));
    }

    // ==================================================== the premise, in one unit test

    /**
     * Why the fix is {@link SearchNames#canonical(String)} and not either of the two
     * things that look like it. No Spring, no DB — this pins the contract a future
     * "simplify this" change would break.
     */
    @Test
    void stripIsNotTheFixAndCanonicalDoesNotFoldCase() {
        // strip() is Unicode-aware about Character.isWhitespace — and the non-breaking
        // separators are precisely what that predicate excludes.
        assertThat(("ABC-12" + NBSP).strip())
                .as("premise: strip() does NOT remove U+00A0, so a strip()-shaped fix ships the bug")
                .isNotEqualTo("ABC-12");
        assertThat(("ABC-12" + FIGURE_SPACE).strip()).isNotEqualTo("ABC-12");
        assertThat(("ABC-12" + NARROW_NBSP).strip()).isNotEqualTo("ABC-12");
        assertThat(("ABC-12" + ZWSP).strip()).isNotEqualTo("ABC-12");
        // …while U+3000 IS whitespace by that definition, which is what makes "strip() is
        // Unicode-aware" a true sentence answering the wrong question.
        assertThat(("ABC-12" + IDEOGRAPHIC_SPACE).strip()).isEqualTo("ABC-12");

        // canonical() removes all of them, and folds no case, so an error echo shows the
        // caller their own key rather than a lower-cased one.
        assertThat(SearchNames.canonical("ABC-12" + NBSP)).isEqualTo("ABC-12");
        assertThat(SearchNames.canonical(NBSP + "ABC-12")).isEqualTo("ABC-12");
        assertThat(SearchNames.canonical("ABC-12" + FIGURE_SPACE)).isEqualTo("ABC-12");
        assertThat(SearchNames.canonical("ABC-12" + NARROW_NBSP)).isEqualTo("ABC-12");
        assertThat(SearchNames.canonical("ABC-12" + IDEOGRAPHIC_SPACE)).isEqualTo("ABC-12");
        assertThat(SearchNames.canonical("ABC-12" + ZWSP)).isEqualTo("ABC-12");
        assertThat(SearchNames.canonical(RLO + "ABC-12")).isEqualTo("ABC-12");
        assertThat(SearchNames.canonical(null)).isEmpty();

        // the one step that separates the two functions
        assertThat(SearchNames.key("ABC-12" + NBSP)).isEqualTo("abc-12");
        assertThat(SearchNames.canonical("ABC-12" + NBSP)).isEqualTo("ABC-12");

        // an interior separator survives as a single U+0020 — which is what lets the
        // resolver reject every separator class with one indexOf(' ').
        assertThat(SearchNames.canonical("ABC" + NBSP + "-12")).isEqualTo("ABC -12");
        assertThat(SearchNames.canonical("ABC " + IDEOGRAPHIC_SPACE + " -12")).isEqualTo("ABC -12");
    }

    // ==================================================== helpers

    /**
     * An issue of a type one tier ABOVE the default Task, so it can legally parent the
     * issues {@code createIssue} makes (strict adjacency: Epic 2 → Task 1). Spelled out
     * here rather than through the base helper because that one hard-codes {@code typeId}
     * to Task, and because {@code Ctx.issuesBase()} is package-private to
     * {@code com.hamstrack.issue} — widening it for one test would change a base class
     * several suites share.
     */
    private JsonNode createEpic(Ctx ctx, String title) throws Exception {
        var parts = new ArrayList<String>();
        parts.add("\"title\":" + json.writeValueAsString(title));
        parts.add("\"typeId\":\"" + configId(ctx, "issueTypes", "Epic") + "\"");
        parts.add("\"statusId\":\"" + todoStatusId(ctx) + "\"");
        var body = mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/"
                        + ctx.projectId() + "/issues")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + String.join(",", parts) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private UUID configId(Ctx ctx, String collection, String name) {
        for (var node : ctx.config().get(collection)) {
            if (node.get("name").asText().equals(name)) {
                return UUID.fromString(node.get("id").asText());
            }
        }
        throw new AssertionError("not offered by the project config: " + collection + "/" + name);
    }

    private UUID todoStatusId(Ctx ctx) {
        for (var s : ctx.config().get("statuses")) {
            if (s.get("category").asText().equals("TODO")) {
                return UUID.fromString(s.get("id").asText());
            }
        }
        throw new AssertionError("no TODO-category status in the project config");
    }

    /**
     * An <strong>HQL</strong> string literal, which is deliberately NOT a JSON encoder:
     * HQL allows only {@code \" \' \\} inside a quoted string, so JSON escaping would turn
     * a U+00A0 operand into the six characters of its escape and earn a
     * {@code PARSE_ERROR} about an illegal escape — a test that then proves nothing about
     * key resolution. Here the character travels intact: {@link #search} JSON-encodes the
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
     * The charset is stated explicitly. {@code MockMvc} encodes a String body as UTF-8 and
     * this whole suite hinges on U+00A0/U+200B surviving the round trip intact — a
     * default-charset mismatch would mangle them into something that "fails" for a reason
     * unrelated to key resolution.
     */
    private ResultActions search(Ctx ctx, String hql) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/search")
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"query\":" + json.writeValueAsString(hql) + ",\"size\":100}"));
    }
}
