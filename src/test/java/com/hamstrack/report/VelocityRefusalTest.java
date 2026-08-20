package com.hamstrack.report;

import com.hamstrack.report.csv.ReportCsv;
import com.hamstrack.report.csv.ReportSubject;
import com.hamstrack.report.dto.ReportMeasure;
import com.hamstrack.report.dto.ReportMeta;
import com.hamstrack.report.dto.VelocityForecast;
import com.hamstrack.report.dto.VelocityReportResponse;
import com.hamstrack.report.dto.VelocitySprint;
import com.hamstrack.report.repository.VelocityReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>The refusals of §1.4, as tests — because a refusal that lives only in a comment is one
 * helpful pull request away from being undone.</strong>
 *
 * <p>Velocity is the only report in this epic whose design is mostly a list of things it will not
 * do, and the reason is evidence rather than taste: velocity <em>"was never intended to be used to
 * compare two teams"</em>, and when it is, <em>"leaders misinterpret higher story point averages to
 * mean one team is more productive"</em>, whereupon <em>"teams begin optimizing for points instead
 * of outcomes … developers inflate estimates, avoid refactoring, and resist taking on complex
 * items"</em>. Every one of those harms is reached by an addition that looks like an improvement:
 * one more column in the SELECT, one more field on the DTO, one endpoint one level up.
 *
 * <p>So the three constraints are asserted structurally, where the addition would happen, rather
 * than behaviourally, where it cannot be seen. {@code VelocityApiTest} covers the two refusals that
 * ARE observable in a response (the suppressed band, the refused cap); these three are not.
 *
 * <h2>Every rule here is stated as an allow-list, and that is round 2's correction</h2>
 * The first version of this file was three deny-lists — forbidden <em>words</em> in field names, a
 * hardcoded controller class, an exact-match on the string {@code "velocity"} — and every one of
 * them was satisfiable by renaming. {@code Map<UUID, BigDecimal> breakdown} is a complete
 * per-person breakdown that trips no forbidden word; {@code List<String> topContributors} trips
 * none either; a {@code WorkspaceReportController} is not the class the mapping check looked at;
 * {@code averageVelocity} is not equal to {@code velocity}. A deny-list of names is the guard a
 * rename satisfies, so the rules below are inverted: the response's components must be
 * <em>exactly</em> a named set, the paths must be found by <em>scanning</em> rather than by naming
 * the class they are expected in, and the shapes that could carry a breakdown without naming one
 * ({@code Map}, {@code Object}, arrays) are refused outright.
 *
 * <p>A plain unit test — no Spring context. Reflection, a classpath scan and annotation values are
 * all it needs.
 */
class VelocityRefusalTest {

    /**
     * <strong>Every record component the velocity response may publish, transitively.</strong> Not
     * a list of forbidden fields — a list of permitted ones, which is the only form of this rule
     * that a rename cannot satisfy.
     *
     * <p>Adding an entry here is the conversation §1.4 asks for, and it is deliberately a
     * <em>separate edit from writing the field</em>: nobody adds
     * {@code Map<UUID, BigDecimal> breakdown} to a DTO and to this set without noticing what they
     * are doing. Removing or renaming one fails too, which is correct — the components below are
     * the report's published shape and the client is written against them.
     */
    private static final Set<String> PUBLISHED_COMPONENTS = Set.of(
            // VelocityReportResponse
            "measure", "sprints", "forecast", "meta",
            // VelocitySprint — one bar
            "sprintId", "name", "startAt", "completedAt", "committed", "completed",
            "addedAfterStart", "carriedOver", "unestimatedCount",
            // VelocityForecast — the band
            "p50", "p85", "sampleSize",
            // ReportMeta — the shared provenance block
            "computedAt", "basedOnIssues", "truncated", "cap", "firstIssueAt", "unmatchedFilters");

    /** Columns of the ledger and of {@code issues} that name a person. */
    private static final List<String> PERSON_COLUMNS =
            List.of("actor_id", "assignee_id", "reporter_id", "created_by", "user_id");

    /**
     * Names that turn the band back into a single quotable number. Matched as substrings, because
     * the field that undoes this report is not called {@code velocity} — it is called
     * {@code averageVelocity}, or {@code typical}, or {@code expected}.
     */
    private static final List<String> HEADLINE_WORDS =
            List.of("average", "avg", "mean", "median", "typical", "headline", "expected",
                    "velocity", "score", "productivity");

    /**
     * <strong>Refusal 1 — no per-person breakdown, anywhere in the response.</strong>
     *
     * <p>"Not as a filter, not as a tooltip, not in the CSV" (§2.5). Walked transitively, because
     * the way this gets added is not a top-level {@code assigneeId} — it is a nested
     * {@code breakdown} record, or an {@code AssigneeSlice} list hung off a bar, which reads as an
     * enhancement in a diff and is the exact thing §1.4 documents the harm of.
     *
     * <p>Asserted as an allow-list rather than as forbidden words. The words version passed
     * {@code Map<UUID, BigDecimal> breakdown} — a complete per-person breakdown whose name is
     * innocent and whose type arguments are not records, so the walk terminated green on it.
     */
    @Test
    void theVelocityResponseIsExactlyItsPublishedComponentsAndNothingIsKeyedByAPerson() {
        var names = componentNames(VelocityReportResponse.class);

        assertThat(names)
                .as("the velocity response grew a component that is not in this file's allow-list. "
                    + "If it is a per-person view, §1.4 refuses it on evidence — cross-comparison "
                    + "inflates estimates, discourages complex work and demoralises teams — and "
                    + "§4.2 says what it would cost to have one anyway: a new "
                    + "`report.view.people` permission IN THE SAME CHANGE. If it is something "
                    + "else entirely, add it to PUBLISHED_COMPONENTS in the same commit that adds "
                    + "the field. The list is an allow-list precisely because a deny-list of "
                    + "person-shaped words is satisfied by calling the field `breakdown`.")
                .isSubsetOf(PUBLISHED_COMPONENTS);
        assertThat(names)
                .as("a component named in PUBLISHED_COMPONENTS no longer exists on the response. "
                    + "That is a published shape the SPA reads, so this is either a breaking API "
                    + "change or a rename — either way the list moves with it, deliberately")
                .containsAll(PUBLISHED_COMPONENTS);
    }

    /**
     * <strong>Refusal 1, continued — no component may be an open shape.</strong>
     *
     * <p>The allow-list constrains component <em>names</em>; this constrains their
     * <em>types</em>, and it is what makes the allow-list mean anything. A permitted name can hold
     * an unbounded payload: {@code Map<UUID, BigDecimal> meta} is a per-person breakdown with an
     * approved name, an {@code Object} is anything at all, and an array of records is a nested
     * shape the reflective walk does not describe as clearly. None of the three is needed by a
     * report whose entire response is four numbers per sprint and a band.
     */
    @Test
    void noComponentOfTheVelocityResponseIsAnOpenShape() {
        var offenders = new ArrayList<String>();
        for (var component : componentsOf(VelocityReportResponse.class)) {
            for (var raw : rawTypesOf(component.type())) {
                if (Map.class.isAssignableFrom(raw) || raw == Object.class || raw.isArray()) {
                    offenders.add(component.owner() + "." + component.name() + " is a "
                                  + raw.getSimpleName());
                }
            }
        }
        assertThat(offenders)
                .as("a velocity response component is a Map, an Object or an array. A Map keyed by "
                    + "anything is the per-person breakdown §1.4 refuses, wearing whatever name "
                    + "was convenient; an Object is a breakdown the type system cannot see at all. "
                    + "This report's response is four numbers per sprint, a band and a meta block "
                    + "— every one of them a named component of a known type.")
                .isEmpty();
    }

    /**
     * The same refusal one layer down: the statement does not know who anybody is.
     *
     * <p>Asserted separately because the DTO check above can be satisfied while the data is loaded
     * anyway — and a report that already has the actor in memory is one field away from publishing
     * it, with the awkward part (the query) already written.
     *
     * <p>{@code SELECT *} is refused for the same reason and by the same rule: it selects
     * {@code actor_id} while containing none of the words this test looks for, which would make
     * the omission of a column a property of the schema rather than of the statement.
     */
    @Test
    void theVelocityStatementsSelectNoPersonColumnAndNeverSelectEverything() {
        var offenders = new LinkedHashSet<String>();
        for (var method : VelocityReportRepository.class.getDeclaredMethods()) {
            var query = method.getAnnotation(Query.class);
            if (query == null) {
                continue;
            }
            var sql = query.value().toLowerCase(Locale.ROOT);
            for (var column : PERSON_COLUMNS) {
                if (sql.contains(column)) {
                    offenders.add(method.getName() + " selects " + column);
                }
            }
            if (sql.matches("(?s).*\\bselect\\s+(\\w+\\.)?\\*.*")) {
                offenders.add(method.getName() + " selects * — which is every person column the "
                              + "table has, spelled so that no substring matches");
            }
        }
        assertThat(offenders)
                .as("the velocity sweep must not read a person column. `actor_id` is on every row "
                    + "of sprint_scope_events and `assignee_id` is one join column away — the "
                    + "single-sprint read does select the latter — so this is a deliberate "
                    + "omission and not an accident of the schema. Data that is not loaded cannot "
                    + "be published by mistake. Note that the sprint SAMPLE is held to the same "
                    + "rule by its type: SprintRepository.CompletedSprint is five scalars, not a "
                    + "Sprint entity with a lazy `createdBy` one dereference away.")
                .isEmpty();
    }

    /**
     * <strong>Refusal 2 — no cross-project or workspace-level velocity.</strong>
     *
     * <p>"Project-scoped endpoint, no aggregate above it. Comparing two teams must be done by hand
     * — that is the intended amount of friction" (§2.5). The friction IS the mitigation: the harm
     * in §1.4 is caused by comparisons cheap enough to make without thinking about them, so the
     * cost is deliberately left with the person making one.
     *
     * <p>Asserted as "<strong>every</strong> handler in the application that returns a velocity
     * shape sits under {@code /projects/{projectId}}", which is a property a rollup cannot have
     * however it is named and wherever it is declared. Round 1 asserted something much weaker — it
     * iterated a hardcoded {@code List.of(ReportController.class)} and matched the substring
     * "velocity" in the path — so a {@code WorkspaceReportController} at
     * {@code /api/workspaces/{id}/reports/velocity} (the exact rollup this refuses) passed by
     * being in a class the test never looked at, and a handler named {@code /team-output}
     * returning {@code VelocityReportResponse} was not matched at all. The tripwire below did not
     * fire either, because the project-scoped mapping was still there. So the search is by
     * <em>return type</em>, over every {@code @RestController} on the classpath — <em>and</em>, as
     * of round 3, by path as well, because a {@code velocity.csv} handler returns no velocity type
     * at all. See {@link #velocityMappings} for why it takes both nets.
     */
    @Test
    void everyHandlerThatServesVelocityIsServedFromBelowAProject() {
        var controllers = restControllers();
        assertThat(controllers)
                .as("the classpath scan found almost no @RestControllers, so this test is "
                    + "guarding an empty set — the package or the scanner changed")
                .hasSizeGreaterThan(10);

        var mappings = velocityMappings(controllers);
        assertThat(mappings)
                .as("no handler anywhere serves velocity, by type or by path — if the response "
                    + "records were renamed, move this test with them; until then this assertion "
                    + "is guarding nothing")
                .isNotEmpty();
        for (var mapping : mappings) {
            assertThat(mapping)
                    .as("a handler serving velocity is served from %s, which is not "
                        + "under a project. §1.4 refuses cross-team velocity on evidence, and the "
                        + "ONLY thing enforcing that is the absence of an aggregate above the "
                        + "project — a workspace rollup, an 'all projects' chart or a CSV that "
                        + "unions two projects deletes the mitigation and keeps the metric.",
                            mapping)
                    .contains("/projects/{projectId}");
        }
    }

    /**
     * <strong>Refusal 3 — no single headline number.</strong>
     *
     * <p>The band is a range with a sample size precisely so that there is nothing to quote in a
     * status report. An {@code averageVelocity} or {@code velocity} scalar would undo the whole
     * redesign in one field, and it is the most natural-looking field anybody could add here.
     *
     * <p>Checked over the <strong>whole response</strong> and by substring. Round 1 inspected only
     * {@link VelocityForecast} and compared names with {@code equals("velocity")}, so
     * {@code averageVelocity} on the response, {@code averageVelocity} on a bar, and {@code avg},
     * {@code median}, {@code typical}, {@code headline} or {@code expected} on the band itself all
     * passed a test whose name says they cannot.
     */
    @Test
    void theBandIsARangeWithItsSampleSizeAndTheResponseHasNoQuotableNumber() {
        var band = componentNames(VelocityForecast.class);
        assertThat(band)
                .as("the band must state how little it is based on")
                .contains("sampleSize");
        assertThat(band)
                .as("p50 and p85 are the band; a mean is refused because one washed-out sprint "
                    + "drags it somewhere no sprint ever was")
                .contains("p50", "p85");

        var offenders = new ArrayList<String>();
        for (var component : componentsOf(VelocityReportResponse.class)) {
            var lower = component.name().toLowerCase(Locale.ROOT);
            if (HEADLINE_WORDS.stream().anyMatch(lower::contains)) {
                offenders.add(component.owner() + "." + component.name());
            }
        }
        assertThat(offenders)
                .as("a single number labelled velocity — anywhere in this response, on the band or "
                    + "on a bar — is the thing §1.4 documents the harm of. It is what gets quoted, "
                    + "compared between teams and answered by inflating estimates. The band has no "
                    + "single value on purpose, and the response has no headline on purpose.")
                .isEmpty();
    }

    /**
     * <strong>Refusal 2's guard, proving it fails</strong> — the two rollups that used to pass it.
     *
     * <p>The assertion above says "no violations", which means nothing unless something is a
     * violation; round 1 and round 2 of this file both shipped a mapping check that was satisfiable
     * by a rename, and each time it was review rather than the suite that noticed. These fixtures
     * are the other half. They are deliberately NOT annotated {@code @RestController} — the scan
     * would find them and fail the real test — so they are handed to {@link #velocityMappings}
     * directly, which is the part being tested.
     *
     * <p>Both are refused for being outside {@code /projects/{projectId}}, which is what
     * {@link #everyHandlerThatServesVelocityIsServedFromBelowAProject()} asserts of every mapping
     * it finds; the property being proved here is that it <em>finds</em> them at all.
     */
    @Test
    void theMappingGuardFindsARollupThatNoReturnTypeAndNoDeclaredMethodWouldReveal()
            throws Exception {
        var csv = velocityMappings(List.of(WorkspaceVelocityCsv.class));
        assertThat(csv)
                .as("a workspace-level velocity.csv returns ResponseEntity<String> — no velocity "
                    + "record is reachable from that type, so the type net cannot see it. It is "
                    + "the union-two-projects rollup §2.5 refuses, and ReportController's javadoc "
                    + "names the .csv variants as the next slice, so this is the route most likely "
                    + "to be written next")
                .isNotEmpty();

        var inherited = velocityMappings(List.of(InheritedRollup.class));
        assertThat(inherited)
                .as("a handler inherited from an abstract base is a handler; getDeclaredMethods() "
                    + "does not return it, so a rollup could be declared one class up and sit "
                    + "outside this guard entirely")
                .isNotEmpty();

        for (var mapping : csv) {
            assertThat(mapping)
                    .as("found, but not refused — the fixture must fail the real assertion")
                    .doesNotContain("/projects/{projectId}");
        }
        for (var mapping : inherited) {
            assertThat(mapping)
                    .as("found, but not refused — the fixture must fail the real assertion")
                    .doesNotContain("/projects/{projectId}");
        }

        // And each fixture is caught by ONE net, which is the argument for keeping both. Asserted
        // rather than described: this is what "the type net cannot see a CSV" means, and it is the
        // sentence that stops somebody deleting the half that looks redundant.
        assertThat(servesVelocity(WorkspaceVelocityCsv.class
                .getDeclaredMethod("download").getGenericReturnType()))
                .as("no velocity record is reachable from ResponseEntity<String>, so the CSV "
                    + "rollup is found by its PATH alone — delete the path net and it is invisible "
                    + "again. (If this ever goes true, the type net covers downloads after all.)")
                .isFalse();
        assertThat(inherited).allSatisfy(mapping -> assertThat(mapping.toLowerCase(Locale.ROOT))
                .as("the inherited rollup is named /team-output, so nothing in its path says "
                    + "velocity — it is found by its TYPE alone, and by getMethods() rather than "
                    + "getDeclaredMethods(). Delete either and it is invisible again.")
                .doesNotContain("velocity"));
        assertThat(InheritedRollup.class.getDeclaredMethods())
                .as("the fixture stops being about inheritance the moment it declares a handler "
                    + "of its own")
                .isEmpty();
    }

    /** Named velocity, untyped body: the shape only the path net catches. */
    @RequestMapping("/api/workspaces/{workspaceId}/reports")
    private static class WorkspaceVelocityCsv {
        @GetMapping("/velocity.csv")
        public ResponseEntity<String> download() {
            throw new UnsupportedOperationException("a fixture, never invoked");
        }
    }

    /** Typed velocity under an innocent name, declared one class up: the shape only the type net catches. */
    private abstract static class RollupBase {
        @GetMapping("/team-output")
        public VelocityReportResponse teamOutput() {
            throw new UnsupportedOperationException("a fixture, never invoked");
        }
    }

    @RequestMapping("/api/workspaces/{workspaceId}/reports")
    private static class InheritedRollup extends RollupBase {
    }

    /** Referenced so the transitive walk provably reaches the bar record. */
    @Test
    void theWalkReachesTheBarRecordItIsMeantToGuard() {
        assertThat(componentNames(VelocityReportResponse.class))
                .as("if the walk cannot see %s, the refusals above are asserted over an empty set",
                        VelocitySprint.class.getSimpleName())
                .contains("committed", "completed", "carriedOver", "addedAfterStart");
    }

    /**
     * <strong>Refusal 1, in the export — the CSV's columns are the record's components and
     * nothing else</strong> (HD-141 R7).
     *
     * <p>Every refusal above is keyed on a <em>record</em>: the allow-list walks
     * {@link VelocityReportResponse}, the open-shape test walks its component types, and the
     * headline-word test walks its names. R7 added a handler that returns none of them —
     * {@code GET …/reports/velocity.csv} returns {@code ResponseEntity<String>} — so a column named
     * {@code assigneeId} in that file would be a per-person velocity breakdown, published, with
     * every test in this class still green. "Not as a filter, not as a tooltip, not in the CSV"
     * (§2.5) says the CSV in so many words, and until this test existed the CSV was the one place
     * the sentence was unenforced.
     *
     * <p>Rather than duplicate the allow-list for a second surface — two lists that agree today and
     * diverge at the first edit — the export is held to the record itself: its columns are
     * {@code VelocitySprint}'s components, in order, exactly. That makes every guard above apply to
     * the CSV transitively, and it means a new column is not reachable without first adding a
     * component to the record, which is the edit {@code PUBLISHED_COMPONENTS} already refuses in
     * silence.
     *
     * <p>Equality rather than {@code isSubsetOf} on purpose: a dropped column is also a change to a
     * published shape, and the person dropping it should say so here.
     */
    @Test
    void theVelocityCsvColumnsAreExactlyTheBarRecordsComponents() {
        var components = Arrays.stream(VelocitySprint.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(ReportCsv.VELOCITY_COLUMNS)
                .as("the velocity CSV exports a column that is not a component of %s. A .csv "
                    + "handler returns a String, so NOTHING else in this file can see what it "
                    + "writes — the type net resolves no velocity record from it and the path net "
                    + "only checks where it is mounted. Keeping the columns equal to the record is "
                    + "what puts the export back inside the refusals: a per-person column has to "
                    + "be added to VelocitySprint first, where PUBLISHED_COMPONENTS refuses it.",
                        VelocitySprint.class.getSimpleName())
                .isEqualTo(components);
    }

    /**
     * <strong>The export is visible to the path net — which is the only net that can see it at
     * all</strong> (HD-141 R7).
     *
     * <p>{@link #everyHandlerThatServesVelocityIsServedFromBelowAProject} would pass whether or not
     * the {@code .csv} handler existed, because {@code ReportController}'s typed {@code /velocity}
     * keeps its set non-empty. So "the CSV is covered" is asserted here rather than assumed: the
     * mapping list must actually contain a {@code .csv} path.
     *
     * <p>The failure it guards is specific and was a live design option. A single generic
     * {@code @GetMapping("/{report}.csv")} serving all six exports is shorter code and is invisible
     * to <em>both</em> nets at once — its return type is {@code ResponseEntity<String>}, which
     * resolves to no velocity record, and its path string contains no "velocity". A workspace-level
     * copy of that handler could then union two projects with every test in this file green, which
     * is exactly the artefact §2.5 names. Six literal paths cost nothing and keep the export inside
     * a guard that already exists.
     */
    @Test
    void theVelocityCsvExportIsFoundByThePathNet() {
        assertThat(velocityMappings(restControllers()))
                .as("no handler with a .csv path serves velocity. Either the export was removed — "
                    + "in which case delete this test — or it was folded into a generic "
                    + "'{report}.csv' mapping, which is invisible to BOTH nets here: a CSV handler "
                    + "returns a String (so the type net resolves no velocity record) and a "
                    + "templated path contains no 'velocity' (so the path net does not match). "
                    + "That combination is how a workspace-level velocity rollup ships silently.")
                .anyMatch(mapping -> mapping.endsWith("/velocity.csv"));
    }

    /**
     * The same refusal over the <strong>rendered file</strong>, comment header included.
     *
     * <p>The column check above constrains the table; this constrains everything else in the
     * document, which is where the next per-person field would actually go. A
     * {@code # Top contributor: …} line, or a {@code # Completed per assignee: …} summary, is not a
     * column and would pass every other test here — and it is exactly the kind of "helpful" header
     * addition §1.4 documents the harm of, because a quotable name in a status report is the whole
     * failure mode.
     *
     * <p>Rendered from a hand-built response rather than over HTTP so this stays a plain unit test
     * with no context, and so the assertion is about the renderer rather than about one project's
     * data.
     */
    @Test
    void theRenderedVelocityCsvNamesNobodyAndQuotesNoHeadlineNumber() {
        var bar = new VelocitySprint(UUID.randomUUID(), "Sprint 7",
                OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                OffsetDateTime.parse("2026-08-14T00:00:00Z"),
                BigDecimal.valueOf(21), BigDecimal.valueOf(18), BigDecimal.valueOf(4),
                BigDecimal.valueOf(3), 0);
        var report = new VelocityReportResponse(ReportMeasure.COUNT, List.of(bar),
                new VelocityForecast(18.0, 23.0, 6),
                new ReportMeta(OffsetDateTime.parse("2026-08-20T09:14:22Z"), 42, false, 20000,
                        null, List.of()));

        var rendered = ReportCsv.velocity(
                new ReportSubject(UUID.randomUUID(), "DEMO", "Demo project"), report)
                .toLowerCase(Locale.ROOT);

        for (var column : PERSON_COLUMNS) {
            assertThat(rendered)
                    .as("the rendered velocity CSV mentions %s. §1.4 refuses a per-person view on "
                        + "evidence and §2.5 says 'not in the CSV' explicitly; a comment line is "
                        + "still the CSV.", column)
                    .doesNotContain(column);
        }
        assertThat(rendered)
                .as("the rendered velocity CSV names a person-shaped field. The header block is "
                    + "prose, which is precisely why it is the easiest place to add one.")
                .doesNotContain("assignee").doesNotContain("actor").doesNotContain("owner")
                .doesNotContain("per person").doesNotContain("contributor");
        for (var word : HEADLINE_WORDS) {
            if (word.equals("velocity")) {
                continue;   // the report is named velocity; the file says so in its first line
            }
            assertThat(rendered)
                    .as("the rendered velocity CSV offers '%s' — the band is a range with a sample "
                        + "size precisely so that there is nothing to quote in a status report, "
                        + "and a header line is as quotable as a field", word)
                    .doesNotContain(word);
        }
    }

    // ------------------------------------------------------------------ mappings

    /** Every concrete {@code @RestController} in the application, found by scanning. */
    private static List<Class<?>> restControllers() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var found = new ArrayList<Class<?>>();
        for (var candidate : scanner.findCandidateComponents("com.hamstrack")) {
            try {
                found.add(Class.forName(candidate.getBeanClassName(), false,
                        VelocityRefusalTest.class.getClassLoader()));
            } catch (ClassNotFoundException e) {
                throw new AssertionError("scanned a controller it cannot load: " + candidate, e);
            }
        }
        return found;
    }

    /**
     * The composed path of every handler that serves velocity — <strong>by return type or by
     * path, because neither net alone covers a CSV</strong> (round 3, item 5).
     *
     * <p>The type net resolves through {@code ResponseEntity<…>}, a collection or a wrapper
     * record, and it is what catches <em>typed velocity under an innocent name</em> — a
     * {@code /team-output} returning {@code VelocityReportResponse}. What it cannot see is a
     * download: a {@code .csv} handler returns {@code ResponseEntity<String>},
     * {@code ResponseEntity<byte[]>}, a {@code Resource}, a {@code StreamingResponseBody}, or
     * {@code void} while writing to the response, and not one of those mentions a velocity record.
     * So a workspace-level {@code GET /api/workspaces/{wsId}/reports/velocity.csv} unioning two
     * projects passed this test silently — and that is not a hypothetical route. {@code
     * ReportController}'s own javadoc names the {@code .csv} variants as the next slice, and both
     * §2.5's refusal and {@code VelocitySprint} say the breakdown must not appear "in a future
     * CSV". The one shape the guard did not cover was the one the refusal explicitly calls out.
     *
     * <p>Hence the second net: <em>named velocity with an untyped body</em>. Both are kept,
     * because each catches what the other cannot.
     */
    private static List<String> velocityMappings(List<Class<?>> controllers) {
        var mappings = new ArrayList<String>();
        for (var type : controllers) {
            var base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            for (var prefix : paths(base)) {
                for (var method : handlersOf(type)) {
                    var mapping =
                            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                    if (mapping == null) {
                        continue;
                    }
                    var typed = servesVelocity(method.getGenericReturnType());
                    for (var path : paths(mapping)) {
                        var composed = prefix + path;
                        if (typed || composed.toLowerCase(Locale.ROOT).contains("velocity")) {
                            mappings.add(type.getSimpleName() + " " + composed);
                        }
                    }
                }
            }
        }
        return mappings;
    }

    /**
     * Every method that could be a handler on this controller, <strong>inherited ones
     * included</strong> — {@code getDeclaredMethods()} misses a handler pulled in from an abstract
     * base or a default interface method, which is a real way to declare one and a free way to sit
     * outside a guard that only reads the class in front of it.
     */
    private static List<java.lang.reflect.Method> handlersOf(Class<?> type) {
        var methods = new ArrayList<java.lang.reflect.Method>();
        for (var method : type.getMethods()) {
            if (method.getDeclaringClass() != Object.class) {
                methods.add(method);
            }
        }
        return methods;
    }

    /** A mapping's paths, or one empty path when it names none (or when there is no annotation). */
    private static String[] paths(RequestMapping mapping) {
        if (mapping == null || mapping.value().length == 0) {
            return new String[]{""};
        }
        return mapping.value();
    }

    /** Whether a velocity record is reachable from this (possibly generic) return type. */
    private static boolean servesVelocity(Type type) {
        var seen = new LinkedHashSet<Class<?>>();
        for (var raw : rawTypesOf(type)) {
            if (servesVelocity(raw, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean servesVelocity(Class<?> raw, Set<Class<?>> seen) {
        if (raw == VelocityReportResponse.class || raw == VelocitySprint.class) {
            return true;
        }
        if (!raw.isRecord() || !seen.add(raw)) {
            return false;
        }
        for (var component : raw.getRecordComponents()) {
            for (var nested : rawTypesOf(component.getGenericType())) {
                if (servesVelocity(nested, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the walk

    /**
     * One record component, and which record declares it.
     *
     * @param type the GENERIC type, so {@code Map<UUID, BigDecimal>} is distinguishable from a
     *             raw {@code Map} and {@code List<Slice>} can be followed into {@code Slice}
     */
    private record Component(String owner, String name, Type type) {}

    private static Set<String> componentNames(Class<?> root) {
        var names = new LinkedHashSet<String>();
        componentsOf(root).forEach(component -> names.add(component.name()));
        return names;
    }

    /**
     * Every record component reachable from {@code root}, transitively — through nested records,
     * through the type arguments of any generic component (at any depth, not only the first), and
     * through array component types.
     */
    private static List<Component> componentsOf(Class<?> root) {
        var found = new ArrayList<Component>();
        collect(root, found, new LinkedHashSet<>());
        return found;
    }

    private static void collect(Type type, List<Component> found, Set<Class<?>> seen) {
        for (var raw : rawTypesOf(type)) {
            if (!raw.isRecord() || !seen.add(raw)) {
                continue;
            }
            for (var component : raw.getRecordComponents()) {
                found.add(new Component(raw.getSimpleName(), component.getName(),
                        component.getGenericType()));
                collect(component.getGenericType(), found, seen);
            }
        }
    }

    /**
     * Every raw class a type mentions: itself, its type arguments, its bounds and its array
     * component type, transitively.
     *
     * <p>The round-1 walk looked only at {@code getType()} and at type arguments that were
     * themselves {@code Class<?>}, so {@code List<List<Slice>>} ended the walk (the argument is a
     * {@code ParameterizedType}) and arrays were never followed at all. Both are shapes a nested
     * breakdown can hide behind, which is the only reason this method is more thorough than it
     * looks like it needs to be.
     */
    private static Set<Class<?>> rawTypesOf(Type type) {
        var raw = new LinkedHashSet<Class<?>>();
        collectRaw(type, raw, new LinkedHashSet<>());
        return raw;
    }

    private static void collectRaw(Type type, Set<Class<?>> raw, Set<Type> seen) {
        if (type == null || !seen.add(type)) {
            return;
        }
        switch (type) {
            case Class<?> clazz -> {
                raw.add(clazz);
                if (clazz.isArray()) {
                    collectRaw(clazz.getComponentType(), raw, seen);
                }
            }
            case ParameterizedType parameterized -> {
                collectRaw(parameterized.getRawType(), raw, seen);
                for (var argument : parameterized.getActualTypeArguments()) {
                    collectRaw(argument, raw, seen);
                }
            }
            case GenericArrayType array -> collectRaw(array.getGenericComponentType(), raw, seen);
            case WildcardType wildcard -> {
                for (var bound : wildcard.getUpperBounds()) {
                    collectRaw(bound, raw, seen);
                }
                for (var bound : wildcard.getLowerBounds()) {
                    collectRaw(bound, raw, seen);
                }
            }
            case TypeVariable<?> variable -> {
                for (var bound : variable.getBounds()) {
                    collectRaw(bound, raw, seen);
                }
            }
            default -> { }
        }
    }
}
