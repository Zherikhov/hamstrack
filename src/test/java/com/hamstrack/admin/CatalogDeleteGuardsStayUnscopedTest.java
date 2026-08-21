package com.hamstrack.admin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-13 / AC-10 — <strong>the delete-with-remap guards must keep counting and remapping across
 * ALL tenants. Narrowing them to the caller's scope strands issues and turns every catalog
 * delete into a {@code 23503}.</strong>
 *
 * <p>This test exists because there is a live, reasonable-looking recommendation to do exactly
 * the wrong thing. {@code docs/design/cross-tenant-data-exposure-audit.md} §4.4 rates the guard
 * messages HIGH and names {@code issueRepository.countByStatus} specifically as counting "all
 * tenants" — which it does, and which before {@code V19__issues_taxonomy_fk.sql} was only an
 * information-disclosure question. It is now a correctness one.
 *
 * <h2>Why narrowing the query breaks the delete</h2>
 * {@code AdminCatalogService.deleteStatus} runs, in one transaction and in this order: count →
 * (refuse if in use and no replacement) → bulk remap → {@code delete}. The count and the remap
 * are the same population. Narrow the <em>count</em> and a delete that should have been refused
 * proceeds; narrow the <em>remap</em> and rows outside the caller's scope survive it. Either
 * way the {@code DELETE} then meets rows still pointing at the catalog entry, and PostgreSQL
 * refuses it — which the new handler renders as a 409, so the operation simply stops working
 * for anyone whose entry is used outside their own scope.
 *
 * <p><strong>The disclosure concern is real and has a different fix: two counts, not one.</strong>
 * The unscoped count decides — narrow it and issues are stranded. Its {@code …Scoped} sibling
 * supplies the number the refusal quotes, so a delegated admin learns only their own figure while
 * a system admin still sees the true total. Both calls are asserted below, because asserting only
 * the unscoped one would let the disclosure back in and asserting only the scoped one would let
 * the {@code 23503} back in. Recorded in §4.4 of the audit, so this test and that document agree.
 *
 * <h2>Why this is a source scan and not a behavioural test</h2>
 * Because a behavioural one would pass either way, today. The scoped and unscoped counts
 * currently return the same number — a scope-stamped catalog row can only be referenced by
 * issues inside its own scope, since bindings are scope-checked and (from this ticket) so is
 * {@code replaceWithId}. They coincide by construction rather than by definition, and the day
 * they stop coinciding is precisely the day the delete starts failing. So what has to be
 * pinned is the <em>call</em>, which is what a reader would change.
 *
 * <h2>What this does NOT seal, stated so nobody mistakes it for stronger than it is</h2>
 * It asserts that both counts are <strong>present in the method body</strong>. It does not
 * assert <em>which one feeds the {@code if}</em> — swapping the two locals would leave the
 * scoped count deciding and the unscoped one merely displayed, and this test would still pass.
 * That is a deliberate limit rather than an oversight: pinning the data flow means either
 * parsing the source properly or building a genuinely cross-tenant fixture for a state that is
 * unreachable by construction, and both cost more than they protect against a mistake nobody has
 * made. The mitigation is the failure message, which carries the rule in full — a reader who
 * trips this test is told which count decides and which is displayed, and why, before they can
 * finish the change that tripped it.
 */
class CatalogDeleteGuardsStayUnscopedTest {

    private static final Path CATALOG_SERVICE = Path.of("src", "main", "java", "com", "hamstrack",
            "admin", "service", "AdminCatalogService.java");

    private static final Path FIELD_SERVICE = Path.of("src", "main", "java", "com", "hamstrack",
            "admin", "service", "AdminFieldService.java");

    /**
     * Each entry is: the source file, the delete method, the repository the counts live on, and
     * the unscoped count it must keep calling. Both the unscoped count and its {@code …Scoped}
     * sibling are asserted for every entry.
     *
     * <p>{@code deleteField} is in this list even though it runs no bulk remap, and that is the
     * point of the entry. It was briefly the one guard where the decision was scoped, on the
     * reasoning that nothing it deletes can be stranded — true, and silent about the other thing
     * the guard does. Its degraded state is <em>worse</em> than the catalog three's: where they
     * refuse, it proceeds and cascades another tenant's values away with {@code dropValues} never
     * asked for. A documented asymmetry between one method and three neighbours is exactly what a
     * later reader normalises away "for consistency", so there is no asymmetry to normalise.
     */
    private record Guard(Path source, String method, String repository, String count) {}

    private static final Guard[] GUARDS = {
            new Guard(CATALOG_SERVICE, "deleteStatus", "issueRepository", "countByStatus"),
            new Guard(CATALOG_SERVICE, "deleteIssueType", "issueRepository", "countByType"),
            new Guard(CATALOG_SERVICE, "deletePriority", "issueRepository", "countByPriority"),
            new Guard(FIELD_SERVICE, "deleteField", "valueRepository", "countByField"),
    };

    /** Only the three catalog deletes remap; {@code deleteField}'s children cascade via FK. */
    private static final Map<String, String> REMAPS = Map.of(
            "deleteStatus", "remapStatus",
            "deleteIssueType", "remapType",
            "deletePriority", "remapPriority");

    @Test
    void everyDeleteGuardDecidesUnscopedAndDisplaysScoped() throws IOException {
        for (var guard : GUARDS) {
            var source = Files.readString(guard.source(), StandardCharsets.UTF_8);

            // A mis-rooted run must fail rather than pass over nothing.
            assertThat(source)
                    .as("read %s — if this is empty the working directory is not the project root",
                            guard.source().toAbsolutePath())
                    .contains("class ");

            var body = methodBody(source, guard.method());
            var call = guard.repository() + "." + guard.count();

            assertThat(body)
                    .as(explain(guard, "the unscoped " + guard.count()))
                    .contains(call + "(");

            assertThat(body)
                    .as("""
                            %s no longer runs a SCOPED count beside the unscoped one. The scoped \
                            count is what the refusal QUOTES, so a delegated admin never learns \
                            how much OTHER tenants use a shared entry \
                            (cross-tenant-data-exposure-audit.md 4.4). Do not resolve that by \
                            scoping the DECISION instead — that is what this class refuses. \
                            Two counts: %s decides, %sScoped is displayed.""",
                            guard.method(), guard.count(), guard.count())
                    .contains(call + "Scoped(");

            var remap = REMAPS.get(guard.method());
            if (remap != null) {
                assertThat(body)
                        .as(explain(guard, "the unscoped bulk remap " + remap))
                        .contains(guard.repository() + "." + remap + "(");
            }
        }
    }

    /**
     * The failure message is the whole point of this test: whoever trips it is, by definition,
     * partway through the change the audit recommends, and needs to be told why not before
     * they finish it.
     */
    private static String explain(Guard guard, String call) {
        return """
                %s no longer calls %s.

                THE RULE: the decision must cover the population the delete affects — remapped \
                or cascaded — and only the MESSAGE may be narrower. Do not "fix" this by scoping \
                the decision. Two ways it goes wrong, and both are live:

                  * where the delete is followed by a bulk REMAP (deleteStatus/IssueType/Priority), \
                    a scoped decision leaves rows outside the caller's scope still pointing at the \
                    row, and the DELETE is then refused by PostgreSQL with 23503 — the constraints \
                    V19__issues_taxonomy_fk.sql added;
                  * where the children CASCADE (deleteField), a scoped decision does not refuse at \
                    all: it proceeds and destroys another tenant's rows with the caller's consent \
                    flag never asked for. That is worse, not better, which is why deleteField is \
                    in this list despite running no remap.

                If you are here because of docs/design/cross-tenant-data-exposure-audit.md 4.4 \
                — that finding is about the NUMBER the refusal QUOTES, not about the query that \
                decides. The resolution is two counts, not one: the unscoped count decides, and \
                its ...Scoped sibling supplies the number in the message. Both are asserted here. \
                That correction is written into 4.4; if you cannot see it there, read this \
                test's javadoc instead.""".formatted(guard.method(), call);
    }

    /**
     * The text of one method, from its signature to its closing brace, by brace depth. Crude
     * on purpose: the alternative is a parser, and what is being asserted is "this call appears
     * inside this method", which brace matching answers exactly. It throws rather than returns
     * empty on a miss, so a renamed method fails loudly instead of vacuously passing.
     */
    private static String methodBody(String source, String method) {
        int start = source.indexOf("public void " + method + "(");
        if (start < 0) {
            throw new AssertionError("""
                    %s no longer exists with that name/signature. It is one of the delete guards \
                    whose decision must cover the whole population the delete affects — see this \
                    class's javadoc. If it was renamed, rename it here too; do not delete the \
                    entry, because the guard it seals is still there.""".formatted(method));
        }
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced braces while reading " + method);
    }
}
