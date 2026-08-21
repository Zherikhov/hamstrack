package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.SprintTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-119 — {@code closed}, the HQL name for {@code issues.closed_at}. The column has been on
 * the issue response since HD-57 and documented since HD-91; until this ticket the language
 * had no word for it, so "what did we close last week" could not be written down at all.
 *
 * <p>Three things here are more than smoke tests:
 * <ul>
 *   <li><strong>{@code IS EMPTY} means CURRENTLY OPEN, not "never finished".</strong>
 *       {@code IssueService} clears the stamp when an issue leaves a DONE-category status, so
 *       a reopened issue is empty again and the date it carried is gone. That is the one
 *       reading of this field an integrator can get wrong while every query still answers
 *       200, so it is pinned with a reopen rather than merely described;</li>
 *   <li><strong>two nullable date fields must place their empty rows identically.</strong>
 *       Null placement is the database's default for the direction, reached through the one
 *       ORDER BY path every sort key shares — so the assertion is written about the pair; a
 *       field that answered differently would be the bug, not a different answer;</li>
 *   <li><strong>{@code !=} also matches the issues carrying no date at all</strong>, the
 *       reading {@code due !=}, {@code storyPoints !=} and the id-set fields already use.
 *       That widening is asked of the descriptor, so it stays dead on a NOT NULL field —
 *       the other arm of the same branch, and the audit stamps that take it, are pinned in
 *       {@link AuditDateSearchTest}.</li>
 * </ul>
 *
 * <p><strong>Why the stamps are written with SQL.</strong> The API can only close an issue
 * <em>now</em>, and a query about the past needs a past. Every fixture therefore closes the
 * issue through the real endpoint — so {@code closed_at} is always a value the application
 * itself wrote — and only then moves the stamp, exactly as the report suites do. The dates
 * are absolute and long past: a fixture expressed relative to "today" would make the
 * assertions depend on the day the suite runs.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ClosedDateSearchTest extends SprintTestBase {

    @Autowired JdbcTemplate jdbcTemplate;

    private static final String JUNE = "2025-06-03T12:00:00Z";
    private static final String JULY = "2025-07-15T09:00:00Z";

    // ==================================================== ordered comparisons

    @Test
    void orderedComparisonsSelectByTheDayAnIssueWasClosed() throws Exception {
        var ctx = fixture();

        assertThat(found(ctx, "closed >= \"2025-07-01\"")).containsExactly("july");
        assertThat(found(ctx, "closed <= \"2025-06-30\"")).containsExactly("june");
        // A literal names a whole UTC day: `=` is that day, `<=` runs to its end and `>` starts
        // after it — the window created/updated already use.
        assertThat(found(ctx, "closed = \"2025-06-03\"")).containsExactly("june");
        assertThat(found(ctx, "closed <= \"2025-07-15\"")).containsExactlyInAnyOrder("june", "july");
        assertThat(found(ctx, "closed > \"2025-07-15\"")).isEmpty();
        assertThat(found(ctx, "closed < \"2025-06-03\"")).isEmpty();

        // The functions mean here what they mean on every other date field.
        assertThat(found(ctx, "closed < now()")).containsExactlyInAnyOrder("june", "july");
        assertThat(found(ctx, "closed >= startOfWeek()")).isEmpty();

        // The response-payload spelling is an alias for the same descriptor, case-insensitively.
        assertThat(found(ctx, "closedAt >= \"2025-07-01\"")).containsExactly("july");
        assertThat(found(ctx, "CLOSED >= \"2025-07-01\"")).containsExactly("july");

        // `!=` also matches the issues that carry no close date: three-valued logic would drop
        // them silently, and "not closed on the 3rd" plainly includes "never closed".
        assertThat(found(ctx, "closed != \"2025-06-03\""))
                .containsExactlyInAnyOrder("july", "still-open", "also-open");

        // …and it composes with the rest of the language.
        assertThat(found(ctx, "closed >= \"2025-01-01\" AND text ~ \"ju\""))
                .containsExactlyInAnyOrder("june", "july");
    }

    // ==================================================== IS [NOT] EMPTY

    @Test
    void isEmptySplitsTheOpenIssuesFromTheClosedOnes() throws Exception {
        var ctx = fixture();

        assertThat(found(ctx, "closed IS EMPTY")).containsExactlyInAnyOrder("still-open", "also-open");
        assertThat(found(ctx, "closed IS NOT EMPTY")).containsExactlyInAnyOrder("june", "july");
        assertThat(found(ctx, "closedAt IS EMPTY")).containsExactlyInAnyOrder("still-open", "also-open");

        // The two halves partition the project: no issue is in both, none is in neither.
        var empty = found(ctx, "closed IS EMPTY");
        var notEmpty = found(ctx, "closed IS NOT EMPTY");
        assertThat(empty).doesNotContainAnyElementsOf(notEmpty);
        assertThat(concat(empty, notEmpty))
                .as("IS EMPTY and IS NOT EMPTY must together account for every issue")
                .containsExactlyInAnyOrderElementsOf(found(ctx, ""));
    }

    /**
     * The subtlety worth a test of its own: {@code closed IS EMPTY} asks about the issue's
     * <em>current state</em>. An issue closed in June and reopened later is empty again, and
     * the June date is gone — nothing in the row remembers it — so "never finished" cannot be
     * expressed with this field, and a filter written as though it could answers a different
     * question on exactly the issues that were reopened.
     */
    @Test
    void reopeningAnIssueMakesItEmptyAgainAndForgetsTheDate() throws Exception {
        var ctx = fixture();
        var reopened = createIssue(ctx, "shipped-then-reopened");
        closeAt(ctx, reopened, "2025-06-20T08:00:00Z");

        assertThat(found(ctx, "closed IS NOT EMPTY"))
                .as("precondition: it is closed")
                .contains("shipped-then-reopened");
        assertThat(found(ctx, "closed = \"2025-06-20\"")).containsExactly("shipped-then-reopened");

        reopen(ctx, reopened);

        assertThat(found(ctx, "closed IS EMPTY"))
                .as("a reopened issue is open, so it is empty again")
                .contains("shipped-then-reopened");
        assertThat(found(ctx, "closed IS NOT EMPTY")).doesNotContain("shipped-then-reopened");
        assertThat(found(ctx, "closed = \"2025-06-20\""))
                .as("the stamp is cleared, not archived — the June date is unrecoverable")
                .isEmpty();
        assertThat(found(ctx, "closed >= \"2025-01-01\"")).containsExactlyInAnyOrder("june", "july");
    }

    // ==================================================== ORDER BY

    /**
     * {@code closed} is sortable, and the interesting half is where the issues with no date go.
     * The assertion is about the <em>pair</em>: every nullable sort key runs through one ORDER
     * BY path that leaves null placement to the database's default for the direction, so two
     * nullable date fields agreeing is the property being pinned. The concrete placement is
     * asserted too, so a silent flip is still caught.
     */
    @Test
    void twoNullableDateFieldsPlaceTheirEmptiesInTheSamePlace() throws Exception {
        var ctx = fixture(true);
        var empties = Set.of("still-open", "also-open");

        assertThat(emptiesEnd(orderedTitles(ctx, "ORDER BY closed ASC"), empties))
                .as("ascending: the dated rows first, the empty ones after")
                .isEqualTo(End.LAST);
        assertThat(emptiesEnd(orderedTitles(ctx, "ORDER BY closed DESC"), empties))
                .as("descending: the empty ones first")
                .isEqualTo(End.FIRST);

        for (String direction : List.of("ASC", "DESC")) {
            assertThat(emptiesEnd(orderedTitles(ctx, "ORDER BY closed " + direction), empties))
                    .as("closed and due are both nullable date fields, so they must place their "
                        + "empty rows at the same end (" + direction + ")")
                    .isEqualTo(emptiesEnd(orderedTitles(ctx, "ORDER BY due " + direction), empties));
        }

        // The dated rows themselves come back in date order, which is the point of the sort.
        assertThat(datedPart(orderedTitles(ctx, "ORDER BY closed ASC"), empties))
                .containsExactly("june", "july");
        assertThat(datedPart(orderedTitles(ctx, "ORDER BY closedAt DESC"), empties))
                .containsExactly("july", "june");
        // A filtered sort is the shape the acceptance query actually has.
        assertThat(orderedTitles(ctx, "closed IS NOT EMPTY ORDER BY closed DESC"))
                .containsExactly("july", "june");
    }

    // ==================================================== /schema + saved filters

    @Test
    void schemaAdvertisesClosedOnceUnderItsCanonicalName() throws Exception {
        var ctx = fixture();

        var schema = json.readTree(mockMvc.perform(
                        get("/api/workspaces/" + ctx.wsId() + "/search/schema")
                                .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                // listed once, under the name people copy out of it; the alias shares the
                // descriptor instance and availableFields() de-duplicates by identity
                .andExpect(jsonPath("$.fields[?(@.name == 'closed')]", hasSize(1)))
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("closedAt"))))
                // it joins the other date fields rather than replacing any of them
                .andExpect(jsonPath("$.fields[?(@.name == 'created')]", hasSize(1)))
                .andExpect(jsonPath("$.fields[?(@.name == 'due')]", hasSize(1)))
                .andReturn().getResponse().getContentAsString());

        var closed = fieldNamed(schema, "closed");
        assertThat(closed.get("type").asText()).isEqualTo("TIMESTAMP");
        assertThat(closed.get("nullable").asBoolean())
                .as("closed IS EMPTY is a legitimate query: %s", closed).isTrue();
        assertThat(closed.get("sortable").asBoolean())
                .as("closed IS sortable: %s", closed).isTrue();
        assertThat(closed.get("valueSuggest").asText()).isEqualTo("DATE");
        assertThat(textValues(closed.get("functions"))).contains("now()", "startOfWeek()");
        assertThat(textValues(closed.get("operators"))).contains("=", "!=", ">", "<", ">=", "<=");
    }

    @Test
    void aSavedFilterUsingClosedSavesLoadsAndRuns() throws Exception {
        var ctx = fixture();

        var created = json.readTree(mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/filters")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"closed in July\","
                                 + "\"hql\":\"closed >= \\\"2025-07-01\\\" ORDER BY closed DESC\","
                                 + "\"shared\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/filters/" + created.get("id").asText())
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hql", containsString("closed")));

        assertThat(found(ctx, created.get("hql").asText())).containsExactly("july");
    }

    // ==================================================== helpers

    private enum End { FIRST, LAST }

    /**
     * Which end of a result page the empty rows occupy. Throws when they are interleaved:
     * "some at each end" is not a null placement any database produces, and it would otherwise
     * satisfy an equality check against another field that had done the same thing.
     */
    private static End emptiesEnd(List<String> titles, Set<String> empties) {
        int leading = 0;
        while (leading < titles.size() && empties.contains(titles.get(leading))) leading++;
        if (leading == empties.size()) return End.FIRST;
        int trailing = 0;
        while (trailing < titles.size()
               && empties.contains(titles.get(titles.size() - 1 - trailing))) trailing++;
        if (trailing == empties.size()) return End.LAST;
        throw new AssertionError("the empty rows are interleaved with the dated ones: " + titles);
    }

    /** The dated rows of a sorted page, in the order they came back. */
    private static List<String> datedPart(List<String> titles, Set<String> empties) {
        var out = new ArrayList<String>();
        for (String t : titles) if (!empties.contains(t)) out.add(t);
        return out;
    }

    private static List<String> textValues(JsonNode array) {
        var out = new ArrayList<String>();
        for (var n : array) out.add(n.asText());
        return out;
    }

    private JsonNode fieldNamed(JsonNode schema, String name) {
        for (var f : schema.get("fields")) {
            if (f.get("name").asText().equals(name)) return f;
        }
        throw new AssertionError("/schema does not advertise '" + name + "'");
    }

    /** June-closed, July-closed and two open issues, in a fresh workspace. */
    private Ctx fixture() throws Exception {
        return fixture(false);
    }

    /**
     * The same four issues, optionally also carrying DUE dates on the two that have close
     * dates — the fixture the null-placement comparison needs, so that the two fields are
     * empty on exactly the same rows and nothing but the field name differs.
     */
    private Ctx fixture(boolean withDueDates) throws Exception {
        var ctx = newProject();
        JsonNode june;
        JsonNode july;
        if (withDueDates) {
            june = createIssue(ctx, "june", "\"dueDate\":\"2025-06-03\"");
            july = createIssue(ctx, "july", "\"dueDate\":\"2025-07-15\"");
        } else {
            june = createIssue(ctx, "june");
            july = createIssue(ctx, "july");
        }
        createIssue(ctx, "still-open");
        createIssue(ctx, "also-open");
        closeAt(ctx, june, JUNE);
        closeAt(ctx, july, JULY);
        return ctx;
    }

    /**
     * Close an issue <em>through the API</em> — so {@code closed_at} is stamped by
     * {@code IssueService} and not by the test — and then move that stamp into the past.
     */
    private void closeAt(Ctx ctx, JsonNode issue, String instant) throws Exception {
        markDone(ctx, numberOf(issue));
        int rows = jdbcTemplate.update(
                "UPDATE issues SET closed_at = CAST(? AS timestamptz) WHERE id = CAST(? AS uuid)",
                instant, idOf(issue).toString());
        if (rows != 1) throw new AssertionError("backdating closed_at hit " + rows + " rows");
    }

    /** Move an issue back to a TODO-category status — which is what clears {@code closed_at}. */
    private void reopen(Ctx ctx, JsonNode issue) throws Exception {
        patchIssue(ctx, ctx.token(), numberOf(issue),
                        "{\"statusId\":\"" + todoStatusId(ctx) + "\"}")
                .andExpect(status().isOk());
    }

    /**
     * The first TODO-category status of the ctx project's workflow. {@code Ctx.todoStatusId}
     * itself is package-private to {@code com.hamstrack.issue}; the config node it reads is not.
     */
    private static UUID todoStatusId(Ctx ctx) {
        for (var s : ctx.config().get("statuses")) {
            if (s.get("category").asText().equals("TODO")) return UUID.fromString(s.get("id").asText());
        }
        throw new AssertionError("no TODO-category status in the workflow");
    }

    private static List<String> concat(List<String> a, List<String> b) {
        var out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private List<String> found(Ctx ctx, String hql) throws Exception {
        return orderedTitles(ctx, hql);
    }

    private List<String> orderedTitles(Ctx ctx, String hql) throws Exception {
        var body = search(ctx.wsId(), ctx.token(), hql)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var out = new ArrayList<String>();
        for (var row : json.readTree(body).get("content")) {
            out.add(row.get("issue").get("title").asText());
        }
        return out;
    }

    private ResultActions search(UUID wsId, String token, String hql) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + wsId + "/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":" + json.writeValueAsString(hql) + ",\"size\":100}"));
    }
}
