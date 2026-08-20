package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>The CSV surface is sealed, and it is keyed on the one property an export cannot
 * hide</strong> (HD-141 R7 round 2, item 2).
 *
 * <p>{@code VelocityRefusalTest} carries two nets over the velocity refusals of §1.4: a type net
 * (a handler whose return type resolves to a velocity record) and a path net (a handler whose path
 * says "velocity"). Between them they close the door this slice opened — folding the six literal
 * {@code .csv} mappings back into one generic {@code /{report}.csv}, which is invisible to both at
 * once. They do <em>not</em> close the next one:
 *
 * <blockquote>Add {@code GET /api/workspaces/{wsId}/reports/{report}.csv} — workspace-level,
 * generic — tomorrow. The type net resolves no record. The path net finds no "velocity" in
 * {@code {report}}. {@code theVelocityCsvExportIsFoundByThePathNet} still passes, because the six
 * project-level literals still exist. That is the artefact §2.5 refuses, shipping
 * green.</blockquote>
 *
 * <p>So this asks a question that has no false negative of that shape: <strong>which handlers in
 * the whole application produce {@code text/csv}?</strong> A download can hide its return type
 * (it is a {@code String}), it can hide its subject (the path is templated) and it can hide its
 * controller (a new package), but it cannot hide that it hands back a CSV — that is the
 * {@code produces} the browser needs. The set is compared for equality against the six known
 * paths, in the shape of {@code ThrottleCoverageTest.theThrottledPathSetIsSealed}, so a seventh
 * export fails here on the day it lands rather than on the review round after it. HD-155's issue
 * export is the next one due, and its author does not have to have read a javadoc in a report
 * controller.
 *
 * <p><strong>{@link #theExportListTheOtherTestsLoopOverIsTheRealSurface()} closes the same gap in
 * the tests themselves.</strong> {@code ReportCsvTenancyTest} loops over
 * {@link ReportCsvTestBase#REPORTS} and its javadoc claimed a seventh export "cannot be added
 * without either appearing here or failing the list's own size assertion". It could: a seventh
 * handler that nobody adds to {@code REPORTS} leaves the list at six, the size assertion passes,
 * and the new export is covered by none of the five tenancy loops. The expected set is therefore
 * derived from the same handler mapping — which is exactly the mechanism above, so the two share
 * one source of truth.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ReportCsvSurfaceTest extends ReportCsvTestBase {

    /**
     * The rule a new CSV endpoint has to satisfy, printed by every failure below because that is
     * the moment somebody can act on it.
     */
    private static final String RULE = """


            A CSV export is a tenant's data leaving the product as a file, and this set is what \
            every guard around that surface is keyed to. A new one must:

              1. sit under /api/workspaces/{workspaceId}/projects/{projectId}/reports/ — a
                 workspace-level or cross-project export is what §2.5 refuses by name: it is the
                 aggregate that deletes the velocity mitigation and keeps the metric, and it is
                 invisible to BOTH nets in VelocityRefusalTest (a String return type resolves to
                 no record, and a templated path contains no report name);
              2. have a LITERAL final segment (…/velocity.csv, not …/{report}.csv) — a generic
                 mapping serves six reports from one handler that no path-based guard can see,
                 and the parameters of each report stop being its own;
              3. be added to the list in this test AND to ReportCsvTestBase.REPORTS, deliberately,
                 in the same change — the tenancy loops, the throttle test and this seal all read
                 one of those two.

            If the export is genuinely not a project report, it still needs its own tenancy test \
            before it is added here.""";

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    /**
     * <strong>Exactly these six paths produce {@code text/csv}, application-wide.</strong>
     *
     * <p>Spelled out rather than derived from the controller, so that moving one, adding one or
     * renaming one fails here — the same reason {@code theThrottledPathSetIsSealed} spells out its
     * patterns instead of importing the constants they came from.
     */
    @Test
    void theCsvSurfaceIsExactlyTheSixProjectReportExports() {
        assertThat(csvPatterns())
                .as("the set of handlers producing text/csv has changed." + RULE)
                .containsExactlyInAnyOrder(
                        "/api/workspaces/{workspaceId}/projects/{projectId}/reports/flow.csv",
                        "/api/workspaces/{workspaceId}/projects/{projectId}/reports/cycle-time.csv",
                        "/api/workspaces/{workspaceId}/projects/{projectId}/reports/aging.csv",
                        "/api/workspaces/{workspaceId}/projects/{projectId}/reports/sprint-burnup.csv",
                        "/api/workspaces/{workspaceId}/projects/{projectId}/reports/sprint-review.csv",
                        "/api/workspaces/{workspaceId}/projects/{projectId}/reports/velocity.csv");
    }

    /**
     * The same rule stated as a property rather than as a list, so that updating the list above
     * without thinking does not buy a workspace-level export a green build.
     */
    @Test
    void everyCsvExportIsProjectScopedAndNamesItsReportInThePath() {
        var patterns = csvPatterns();
        assertThat(patterns)
                .as("no handler produces text/csv at all — if the exports were removed, delete "
                    + "this file; until then it is guarding an empty set")
                .isNotEmpty();

        for (var pattern : patterns) {
            assertThat(pattern)
                    .as("a CSV export is served from %s." + RULE, pattern)
                    .startsWith("/api/workspaces/{workspaceId}/projects/{projectId}/reports/")
                    .endsWith(".csv")
                    .doesNotContain("/reports/{");
        }
    }

    /**
     * The list the tenancy and throttle tests loop over IS the surface — derived, not counted.
     *
     * <p>This replaces a {@code hasSize(6)} that could not fire for the case it named: a seventh
     * handler nobody adds to {@code REPORTS} leaves the list at six.
     */
    @Test
    void theExportListTheOtherTestsLoopOverIsTheRealSurface() {
        var mounted = csvPatterns().stream()
                .map(pattern -> pattern.substring(pattern.lastIndexOf('/') + 1))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var looped = REPORTS.stream().map(report -> report[0]).collect(Collectors.toSet());

        assertThat(looped)
                .as("ReportCsvTestBase.REPORTS is not the set of exports the application actually "
                    + "serves, so the tenancy loops cover something other than the surface — the "
                    + "five checks in ReportCsvTenancyTest (foreign workspace, foreign project "
                    + "through my own workspace id, non-existent ids, anonymous, sprint from "
                    + "another project) silently skip whatever is missing." + RULE)
                .containsExactlyInAnyOrderElementsOf(mounted);
    }

    /** Every mapped path pattern whose handler declares it produces {@code text/csv}. */
    private Set<String> csvPatterns() {
        var patterns = new LinkedHashSet<String>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            var produces = entry.getKey().getProducesCondition().getProducibleMediaTypes();
            if (produces.stream().noneMatch(MediaType.valueOf("text/csv")::isCompatibleWith)) {
                continue;
            }
            var condition = entry.getKey().getPathPatternsCondition();
            assertThat(condition)
                    .as("%s produces text/csv but has no path patterns to seal", entry.getValue())
                    .isNotNull();
            condition.getPatterns().forEach(pattern -> patterns.add(pattern.getPatternString()));
        }
        return patterns;
    }
}
