package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The bound on the PLANNING surface — {@code GET …/projects/{p}/backlog} and every section read
 * under {@code …/backlog/**} (HD-174).
 *
 * <p><strong>Why the largest read in the product had no budget until now.</strong> Not a decision
 * anybody made: {@code ThrottleCoverageTest}'s sealed path set covered reports, insights, the
 * storage breakdown, search, saved filters and the issue write surface, and none of those patterns
 * matches {@code …/projects/*}{@code /backlog}. The argument that spared it was that a report is
 * O(project history) while a planning response is bounded by
 * {@code AgileProperties.MAX_PLANNING_VIEW_ROWS}. <strong>"Bounded" is not "small"</strong>: that
 * bound is 20 000 assembled {@code IssueResponse}s in ONE unpaged response — the same number as
 * {@code app.reports.max-rows} — 6300 at the stock defaults, ~1.9 KB per shipped row, so ~12 MB of
 * transient heap at the defaults and ~38 MB at the configurable maximum, per request, against a
 * 512 MB reference heap.
 *
 * <p>The budget is earned by the term that does not shrink: {@code planningStats} is
 * <strong>unconditional and cap-blind</strong> — it reads and groups a whole section whatever the
 * filters say and whatever the cap is. That is the property {@code …/storage/projects} was put on
 * the reports pot for. Do not restate it as "everything else is bounded by what it returns": a
 * {@code LIMIT} bounds output, never selection, and {@code findSectionIssues} orders the whole
 * filtered set before its limit applies. That paraphrase has been wrong three times in this
 * repository.
 *
 * <h2>Its own pot, not the reports pot — and where 240 comes from</h2>
 *
 * <p>The reports pot is 60/min, and an ordinary grooming session would reach it in three minutes,
 * because a card dragged across sections costs two section refreshes. A 429 in the middle of a
 * drag-and-drop gesture reads as a product defect; a 429 on a report is a delay. So: two pots,
 * sized for two behaviours, exactly as search is not on the reports pot.
 *
 * <p><strong>The number is derived from what the SPA does, read off the code rather than
 * estimated</strong>, and it is kept here ONCE so a future reader can check it instead of
 * re-guessing it:
 *
 * <ul>
 *   <li>opening the Backlog page, or changing any filter → <strong>1</strong> aggregate. The
 *       page's other mount queries ({@code /sprints}, {@code /config}, {@code /project}) are not
 *       on this surface;</li>
 *   <li>a drag or keyboard move WITHIN one section → <strong>1</strong> section fetch
 *       ({@code refreshSections} deduplicates the two ids to one);</li>
 *   <li>a drag ACROSS sections → <strong>2</strong> section fetches, issued
 *       <strong>sequentially</strong> ({@code for (const id of unique) await refreshSection(id)});</li>
 *   <li>a sprint mutation, an issue create, "move every issue to →" → <strong>1</strong> aggregate,
 *       through the shared project-issues key prefix.</li>
 * </ul>
 *
 * <pre>
 * a fast facilitator sustains ~1 drag / 2 s          = 30 drags/min
 * mostly cross-section during grooming (x2 fetches)  = 60 section fetches/min
 * plus filter toggles and sprint mutations           ~ 10 aggregates/min
 *                                                      ----------------------
 * one very busy tab                                  ~ 70 planning requests/min
 * the same person with a second tab on a second
 * project (common in grooming)                       ~ 140
 * </pre>
 *
 * <p>Nobody sustains 30 drags a minute for a whole minute, so 70 is already the pessimistic
 * reading of one tab. <strong>240 is ~3.4x the busiest single tab and ~1.7x the two-tab
 * pathological case</strong> — the same posture the search budget documents ("120 is ~10x ordinary
 * SPA use"). It is deliberately generous, because this budget sits under an interactive gesture:
 * it only has to catch a client in a loop. <strong>What protects the connection pool is the
 * occupancy bound, not this number</strong> — see below.
 *
 * <h2>The other half of the answer, and why it is not a second number here</h2>
 *
 * <p>{@code BacklogService.view} is {@code @Transactional(readOnly = true)} over the whole method,
 * so ONE Hikari connection is held across every statement it issues: {@code 12 + N} for the
 * aggregate, i.e. <strong>32</strong> at {@code AGILE_MAX_OPEN_SPRINTS=20}, and 11–12 for a
 * section. {@code DB_STATEMENT_TIMEOUT_MS} bounds each of them and nothing bounds their sum, so the
 * worst-case connection hold for one planning aggregate is ~320 seconds against a default pool of
 * 10 and a 30 s {@code connectionTimeout} for everybody else. <strong>A rate budget provably
 * cannot bound that</strong>: it spends the same unit whether a request takes 8 ms or 8 s, so its
 * protection evaporates precisely as the instance slows down.
 *
 * <p><strong>What of that is measured, and what is not.</strong> {@code PlanningOccupancyCostTest}
 * asserts the shape against the running application: the aggregate's statement count grows by
 * <em>exactly one per open sprint</em>, and a section read repeats the constant block rather than
 * dividing it. The <em>constant</em> is not pinned — a warm request over empty sections measures
 * {@code 8 + N} against the design's cold, populated reading of {@code 12 + N} — because a cached
 * permission resolution and an empty batched loader both cost nothing, and that test can remove
 * neither. <strong>And the DURATION is not measured at all.</strong> Every occupancy estimate here
 * (a ~200 ms response, so ~0.23 permits for a busy planner) comes from the shape of the queries and
 * not from a run; if a 6300-row aggregate really takes 2–3 s, a handful of planners hold the whole
 * six-permit share and {@code EXPENSIVE_SURFACE_BUSY} becomes routine rather than a saturation
 * signal. That is the assumption to test first, it is the deliverable in
 * {@code ops/loadtest/RESULTS-TEMPLATE.md} §P1b, and the k6 browse ladder already exercises these
 * endpoints. Do not reason from the estimate again without replacing it.
 *
 * <p>So the planning surface also joins the <strong>existing</strong> occupancy share
 * ({@link ExpensiveReadProperties}, {@code ExpensiveReadConcurrencyLimit}) — the same permits, the
 * same two refusals, <strong>no new dial</strong>. That is ADR-0031, and the reason it is not a
 * second share is the one that matters to an operator: a second ceiling would turn
 * {@link ExpensiveReadShare}'s derive-from-the-pool default into a PARTITION, which is degenerate
 * on the small pools this project recommends (a pool of 4 derives a share of 2, split two ways =
 * 1 permit per surface, serialising a whole surface instance-wide). A literal that must sit below
 * the pool crash-looped every small self-host once already; a PAIR of them is that hazard squared.
 * Nothing in HD-174 has to sit below {@code DB_POOL_MAX_SIZE}, so nothing in it can stop a boot.
 *
 * <p><strong>Identical in {@code dc} and {@code cloud}</strong>, with no profile override, for
 * {@link ReportProperties}' reason: this is a product property. {@code @Validated} so an
 * out-of-range value fails startup instead of being clamped behind the operator's back.
 */
@Validated
@ConfigurationProperties(prefix = "app.planning")
public record PlanningProperties(
        /*
         * How many PLANNING-surface requests ONE PRINCIPAL may make per minute, across
         * GET .../projects/{p}/backlog and every section read under .../backlog/** —
         * one pattern, so a fourth planning read is budgeted the moment its @GetMapping
         * exists rather than when somebody remembers.
         *
         * 240, derived in the class javadoc above rather than chosen: ~3.4x the busiest
         * single tab and ~1.7x the two-tab grooming case. Read that derivation before
         * moving this number - in particular the SEQUENTIALITY of refreshSections, which
         * is load-bearing twice. It is why a cross-section drag is two requests and not a
         * doubled arrival rate, and it is why adding this surface to the occupancy share
         * did NOT have to re-argue app.expensive-read.max-in-flight-per-principal: the
         * Backlog page's mount puts ONE request on this surface and a drag never puts two
         * in flight at once, so the planning surface does not raise the largest concurrent
         * burst a correct client makes. If a future change parallelises those fetches,
         * both of those sentences stop being true in the same commit.
         *
         * Past the budget the answer is 429 + Retry-After. It is NEVER a narrowed section,
         * a smaller sectionCap or a truncated view: the honesty protocol
         * (sectionCap/truncated/totalAvailable) is a statement about the data and a
         * throttle must not become the thing that changes it.
         *
         * Valid range 1-10000, the same range as the reports and search budgets so a
         * reader comparing three operator rows does not have to hold three ranges in their
         * head. There is no "unlimited": 0 is out of range and fails startup. The off
         * switch is app.rate-limit.enabled, the master switch shared by every limiter that
         * HAS one — and it deliberately leaves the OCCUPANCY bound in force, which is the
         * direction application.properties already documents.
         *
         * THE LIST OF THROTTLED PATHS DOES NOT LIVE IN THIS COMMENT. It is the failure
         * message of ThrottleCoverageTest.theThrottledPathSetIsSealed, which fires the
         * moment a pattern is added — a comment is a DIRECTION mechanism and the one that
         * used to carry the list was stale one round after it was written.
         */
        @DefaultValue("240") @Min(1) @Max(10_000) int requestsPerMinute
) {}
