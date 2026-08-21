package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.SprintTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-119 — the NON-nullable half of the date branch: {@code created} and {@code updated}.
 *
 * <p>This class exists because of an absence rather than a change. HD-119 taught {@code !=}
 * on a TIMESTAMP field to also match the rows carrying no value, and that widening is asked
 * of {@link FieldDescriptor#nullable()} so it stays dead on a NOT NULL field
 * ({@code HqlCompiler.timestampWindow}). The claim that the two audit stamps were therefore
 * unaffected was true — and rested on a single boolean in a single registry line, with
 * nothing in the suite standing behind it: before this class, no test put any {@code created}
 * or {@code updated} predicate to the database at all. The only two hits in the whole suite
 * were parser-only and never reached a compiler or a column.
 *
 * <p><strong>What makes these two fields safe is a PAIR of facts that must agree.</strong>
 * The registry says the field is not nullable, so {@code !=} omits the {@code IS NULL}
 * disjunct; the schema says the column is {@code NOT NULL}, so there are no null rows for
 * that disjunct to have rescued. Either fact alone is not an argument: a descriptor marked
 * non-nullable over a column that admits NULL would make {@code created != "<day>"} silently
 * drop rows, and the results here would still look right, because a fixture with no null
 * stamps has none to lose. So the correspondence itself is asserted, for every date field the
 * language has and in BOTH truth values — a check that cannot fail is not a check.
 *
 * <p><strong>Where the {@code nullable} flag is observable.</strong> Not in these result
 * sets: over a NOT NULL column, {@code col IS NULL OR outside} and {@code outside} select
 * exactly the same rows, so flipping the flag would not move a single title below. Its one
 * caller-visible effect is that {@code IS [NOT] EMPTY} becomes legal — which is what
 * {@link #theAuditStampsAreDeclaredNonNullableAndTheColumnsAgree()} refuses, and what goes
 * red if the flag is flipped. The result assertions and the flag assertion are therefore not
 * redundant: one pins the meaning, the other pins the declaration that meaning rests on.
 *
 * <p><strong>Why the stamps are written with SQL.</strong> Every issue a test can create is
 * created now, so a question about days needs days. Each issue is created through the real
 * API and only then moved, under V11's {@code hamstrack.skip_updated_at} guard — without it
 * the BEFORE UPDATE trigger overwrites {@code updated_at} with {@code NOW()}, every issue
 * ends up sharing one {@code updated} day, and that half of this class quietly tests nothing
 * (so {@link #backdate} reads the stamp back rather than trusting the guard). The dates are
 * absolute and long past: a fixture expressed relative to "today" would make the assertions
 * depend on the day the suite runs.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AuditDateSearchTest extends SprintTestBase {

    @Autowired JdbcTemplate jdbcTemplate;

    // Two issues share a created day and are fifteen hours apart inside it; two more sit
    // either side of a UTC midnight. The `updated` days deliberately group the SAME four
    // issues DIFFERENTLY, so an assertion that read the wrong column would not still pass.
    //
    //   title  | created_at            | updated_at
    //   alpha  | 2025-03-10T08:00:00Z  | 2025-05-20T08:00:00Z
    //   beta   | 2025-03-10T23:30:00Z  | 2025-05-21T08:00:00Z
    //   gamma  | 2025-03-11T00:30:00Z  | 2025-05-20T23:59:00Z
    //   delta  | 2025-04-01T12:00:00Z  | 2025-05-22T12:00:00Z
    private static final String CREATED_DAY = "2025-03-10";      // alpha, beta
    private static final String NEXT_DAY    = "2025-03-11";      // gamma
    private static final String UPDATED_DAY = "2025-05-20";      // alpha, gamma

    // ==================================================== created

    /**
     * {@code created != "<day>"} is exactly the complement of {@code created = "<day>"} — not
     * a subset (three-valued logic dropping rows), and — the direction this ticket could have
     * broken — not a superset either.
     */
    @Test
    void createdNotEqualsIsExactlyTheIssuesCreatedOnOtherDays() throws Exception {
        var ctx = fixture();

        assertThat(found(ctx, "created = \"" + CREATED_DAY + "\""))
                .as("a literal names a whole UTC day, so 08:00 and 23:30 are both inside it")
                .containsExactlyInAnyOrder("alpha", "beta");
        assertThat(found(ctx, "created != \"" + CREATED_DAY + "\""))
                .as("exactly the issues created on another day — no more")
                .containsExactlyInAnyOrder("gamma", "delta");

        // 23:30 and 00:30 are sixty minutes apart and on opposite sides of the answer.
        assertThat(found(ctx, "created = \"" + NEXT_DAY + "\"")).containsExactly("gamma");
        assertThat(found(ctx, "created != \"" + NEXT_DAY + "\""))
                .containsExactlyInAnyOrder("alpha", "beta", "delta");

        // A day nothing was created on excludes nobody: `!=` on a NOT NULL field is total.
        assertThat(found(ctx, "created != \"2025-01-01\""))
                .containsExactlyInAnyOrderElementsOf(found(ctx, ""));

        assertPartitions(ctx, "created", CREATED_DAY);
        assertPartitions(ctx, "created", NEXT_DAY);
    }

    // ==================================================== updated

    @Test
    void updatedNotEqualsIsExactlyTheIssuesUpdatedOnOtherDays() throws Exception {
        var ctx = fixture();

        assertThat(found(ctx, "updated = \"" + UPDATED_DAY + "\""))
                .as("the `updated` days group the four issues differently from the `created` "
                    + "days, so this cannot pass by reading the wrong column")
                .containsExactlyInAnyOrder("alpha", "gamma");
        assertThat(found(ctx, "updated != \"" + UPDATED_DAY + "\""))
                .containsExactlyInAnyOrder("beta", "delta");

        assertThat(found(ctx, "updated != \"2025-05-22\""))
                .containsExactlyInAnyOrder("alpha", "beta", "gamma");
        assertThat(found(ctx, "updated != \"2025-01-01\""))
                .containsExactlyInAnyOrderElementsOf(found(ctx, ""));

        assertPartitions(ctx, "updated", UPDATED_DAY);
        assertPartitions(ctx, "updated", "2025-05-22");
    }

    /**
     * The ordered operators over the same fixture, so the {@code !=} results above are known
     * to complement a window that is itself right: "everything outside the day" is only the
     * correct answer if "the day" is the window {@code =} uses.
     */
    @Test
    void theOrderedOperatorsUseTheSameUtcDayWindow() throws Exception {
        var ctx = fixture();

        assertThat(found(ctx, "created < \"" + NEXT_DAY + "\"")).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(found(ctx, "created >= \"" + NEXT_DAY + "\"")).containsExactlyInAnyOrder("gamma", "delta");
        assertThat(found(ctx, "created <= \"" + CREATED_DAY + "\""))
                .as("<= runs to the END of the named day, so 23:30 is included")
                .containsExactlyInAnyOrder("alpha", "beta");
        assertThat(found(ctx, "created > \"" + CREATED_DAY + "\"")).containsExactlyInAnyOrder("gamma", "delta");
        assertThat(found(ctx, "created < now()")).containsExactlyInAnyOrderElementsOf(found(ctx, ""));

        assertThat(found(ctx, "updated >= \"2025-05-21\"")).containsExactlyInAnyOrder("beta", "delta");
        assertThat(found(ctx, "updated <= \"" + UPDATED_DAY + "\"")).containsExactlyInAnyOrder("alpha", "gamma");
        assertThat(found(ctx, "created = \"" + CREATED_DAY + "\" AND updated = \"" + UPDATED_DAY + "\""))
                .as("the two stamps are independent columns, not one date wearing two names")
                .containsExactly("alpha");
    }

    // ==================================================== the declaration behind the semantics

    /**
     * The assertion that goes red if {@code FieldDescriptor.nullable} is flipped on either
     * audit stamp. Over a NOT NULL column the flag changes no result row, so nothing above
     * would notice; what it changes is whether {@code IS [NOT] EMPTY} is a legal question.
     *
     * <p>The schema half is checked in the same test on purpose. The descriptor and the column
     * are two independent declarations of one fact, and it is their AGREEMENT that licenses
     * {@code !=} to leave out the {@code IS NULL} disjunct. Both truth values are represented
     * ({@code closed} and {@code due} are nullable, and their columns admit NULL), so this
     * cannot degrade into a check that passes by construction.
     */
    @Test
    void theAuditStampsAreDeclaredNonNullableAndTheColumnsAgree() throws Exception {
        var ctx = fixture();

        // 1) the registry's declaration, as the /schema endpoint publishes it
        var schema = dateFieldNullability(ctx);
        assertThat(schema.get("created")).as("created is NOT nullable: %s", schema).isFalse();
        assertThat(schema.get("updated")).as("updated is NOT nullable: %s", schema).isFalse();
        assertThat(schema.get("closed")).as("closed IS nullable: %s", schema).isTrue();
        assertThat(schema.get("due")).as("due IS nullable: %s", schema).isTrue();

        // 2) the only place that declaration is visible to a caller
        for (String field : List.of("created", "updated")) {
            for (String form : List.of("IS EMPTY", "IS NOT EMPTY")) {
                search(ctx.wsId(), ctx.token(), field + " " + form)
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                        .andExpect(jsonPath("$.field").value(field))
                        .andExpect(jsonPath("$.detail")
                                .value("Field '" + field + "' cannot be empty"));
            }
        }
        // …and the nullable siblings answer it instead of refusing it.
        search(ctx.wsId(), ctx.token(), "closed IS EMPTY").andExpect(status().isOk());
        search(ctx.wsId(), ctx.token(), "due IS EMPTY").andExpect(status().isOk());

        // 3) the column, which is what makes the omitted IS NULL disjunct harmless
        var columns = Map.of(
                "created", "created_at",
                "updated", "updated_at",
                "closed", "closed_at",
                "due", "due_date");
        for (var e : columns.entrySet()) {
            assertThat(columnAdmitsNull(e.getValue()))
                    .as("""
                        issues.%s and the HQL field '%s' disagree about NULL. \
                        FieldDescriptor.nullable decides whether `%s !=` emits the IS NULL \
                        disjunct that keeps value-less rows in the answer, and whether \
                        `%s IS [NOT] EMPTY` is a legal question at all — so either \
                        direction of this disagreement is a bug, not a redundancy. A \
                        descriptor narrower than its column drops the value-less rows from \
                        `!=` and refuses a question the data can answer; a descriptor wider \
                        than its column offers IS EMPTY as a query that can only ever return \
                        nothing. Change the registry and the migration together, or \
                        neither.""",
                        e.getValue(), e.getKey(), e.getKey(), e.getKey())
                    .isEqualTo(schema.get(e.getKey()));
        }
        assertThat(columnAdmitsNull("created_at")).isFalse();
        assertThat(columnAdmitsNull("updated_at")).isFalse();
        assertThat(columnAdmitsNull("closed_at"))
                .as("the correspondence check must be able to fail: at least one date column "
                    + "on this table does admit NULL")
                .isTrue();
    }

    // ==================================================== helpers

    /**
     * {@code field = day} and {@code field != day} split the workspace in two: nothing in
     * both, nothing in neither. The "never a superset" property stated about the operator
     * rather than about one expected list, so it survives a change to the fixture.
     */
    private void assertPartitions(Ctx ctx, String field, String day) throws Exception {
        var equal = found(ctx, field + " = \"" + day + "\"");
        var notEqual = found(ctx, field + " != \"" + day + "\"");
        assertThat(equal)
                .as("%s = and != overlap on %s", field, day)
                .doesNotContainAnyElementsOf(notEqual);
        assertThat(concat(equal, notEqual))
                .as("%s = and != must together account for every issue; over a NOT NULL column "
                    + "neither an unmatched row nor a doubly-matched one can exist", field)
                .containsExactlyInAnyOrderElementsOf(found(ctx, ""));
    }

    /** Every date field {@code /schema} advertises, mapped to its published nullability. */
    private Map<String, Boolean> dateFieldNullability(Ctx ctx) throws Exception {
        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/schema")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var out = new LinkedHashMap<String, Boolean>();
        for (var f : json.readTree(body).get("fields")) {
            var type = f.get("type").asText();
            if (type.equals("DATE") || type.equals("TIMESTAMP")) {
                out.put(f.get("name").asText(), f.get("nullable").asBoolean());
            }
        }
        for (String required : List.of("created", "updated", "closed", "due")) {
            if (!out.containsKey(required)) {
                throw new AssertionError("/schema does not advertise the date field '"
                                         + required + "': " + out);
            }
        }
        return out;
    }

    private boolean columnAdmitsNull(String column) {
        var isNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_schema = current_schema() AND table_name = 'issues'"
                + " AND column_name = ?", String.class, column);
        if (isNullable == null) throw new AssertionError("no issues." + column + " column");
        return "YES".equals(isNullable);
    }

    /** Four issues in a fresh workspace, carrying the stamps of the table above. */
    private Ctx fixture() throws Exception {
        var ctx = newProject();
        backdate(createIssue(ctx, "alpha"), "2025-03-10T08:00:00Z", "2025-05-20T08:00:00Z");
        backdate(createIssue(ctx, "beta"), "2025-03-10T23:30:00Z", "2025-05-21T08:00:00Z");
        backdate(createIssue(ctx, "gamma"), "2025-03-11T00:30:00Z", "2025-05-20T23:59:00Z");
        backdate(createIssue(ctx, "delta"), "2025-04-01T12:00:00Z", "2025-05-22T12:00:00Z");
        return ctx;
    }

    /**
     * Move an issue's audit stamps into the past. {@code updated_at} needs V11's
     * {@code hamstrack.skip_updated_at} guard, set with {@code is_local = true} inside an
     * explicit transaction so it dies at COMMIT and cannot ride the pooled connection into
     * another test. The stamp is then read back: a guard that failed to take would leave
     * every issue on one {@code updated} day, which no assertion in this class would report
     * as anything but a wrong expectation.
     */
    private void backdate(JsonNode issue, String createdAt, String updatedAt) {
        var id = idOf(issue).toString();
        Integer rows = jdbcTemplate.execute((ConnectionCallback<Integer>) con -> {
            boolean autoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try (var guc = con.prepareStatement(
                         "SELECT set_config('hamstrack.skip_updated_at', 'on', true)");
                 var update = con.prepareStatement(
                         "UPDATE issues SET created_at = CAST(? AS timestamptz),"
                         + " updated_at = CAST(? AS timestamptz) WHERE id = CAST(? AS uuid)")) {
                guc.execute();
                update.setString(1, createdAt);
                update.setString(2, updatedAt);
                update.setString(3, id);
                int n = update.executeUpdate();
                con.commit();
                return n;
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(autoCommit);
            }
        });
        if (rows == null || rows != 1) {
            throw new AssertionError("backdating the audit stamps hit " + rows + " rows");
        }
        var written = jdbcTemplate.queryForObject(
                "SELECT to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')"
                + " FROM issues WHERE id = CAST(? AS uuid)", String.class, id);
        if (!updatedAt.equals(written)) {
            throw new AssertionError(
                    "updated_at came back as " + written + " instead of " + updatedAt
                    + " — set_updated_at() overwrote it, so the hamstrack.skip_updated_at "
                    + "guard is not in force and every issue shares one `updated` day");
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        var out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private List<String> found(Ctx ctx, String hql) throws Exception {
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
