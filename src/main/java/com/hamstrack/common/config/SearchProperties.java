package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The bound on the HQL search surface (HD-140 R6 round 2, security item 12).
 *
 * <p><strong>Why search needs a budget of its own.</strong> Until R6 the application had exactly
 * two limiters: six literal auth URLs, and the reports interceptor. {@code POST …/search},
 * {@code GET …/search/schema} and {@code GET …/search/suggest} had none — nor, until round 3 of
 * the same epic, did saved-filter CRUD, which validates HQL on every write — and search is the
 * cheaper target only in appearance. A query may carry up to {@code HqlParser.MAX_PREDICATES}
 * leaves; a {@code text ~ "…"} leaf compiles to two unanchored, unindexable {@code LIKE}s over a
 * TEXT column, and the search endpoint runs the whole predicate <strong>twice</strong> per request
 * (count + page). That is strictly more expensive than the most expensive Insights shape, which
 * <em>was</em> throttled. A throttle covering only the narrow door while the wide one stands open
 * is worse than knowing you have none, because it reads as protection.
 *
 * <p><strong>Its own budget, not the reports budget.</strong> Somebody typing in the search box
 * legitimately fires several requests a minute — schema on mount, a debounced typeahead per
 * keystroke-burst, a search per submit — and starving that to protect reports would be the wrong
 * trade in both directions. Two pots, sized for two behaviours.
 *
 * <p><strong>Identical in {@code dc} and {@code cloud}</strong>, with no profile override, for
 * {@link ReportProperties}' reason: this is a product property. If it were ever limited per
 * deployment it would be by lowering a number, never by a second code path. {@code @Validated} so
 * an out-of-range value fails startup instead of being clamped behind the operator's back.
 */
@Validated
@ConfigurationProperties(prefix = "app.search")
public record SearchProperties(
        /*
         * How many search-surface requests ONE PRINCIPAL may make per minute, across
         * POST …/search, GET …/search/schema, GET …/search/suggest, POST
         * …/search/insights (which spends this budget AND the reports budget — see
         * ReportRateLimitConfig.INSIGHTS_PATH for why it is deliberately on both) and
         * every saved-filter endpoint under …/filters, which is not on the /search path
         * and is on this budget anyway: creating or editing a filter validates its HQL,
         * and that builds the same ResolutionContext GET …/search/schema pays for — a
         * workspace-wide label projection and a full member scan, ~8 statements, which
         * an invalid query can 422 in a loop. See SearchRateLimitConfig.FILTERS_PATH.
         *
         * KEEP THIS ENUMERATION FIRST. It is the source of truth the redundancy round
         * reconciled the other artefacts TO — and it is the one that was left stale when
         * filters joined the pot, because being the original is not the same as being
         * maintained. A path added to SearchRateLimitConfig is added here in the same
         * edit, then propagated to every artefact that enumerates the throttled paths.
         * THAT LIST LIVES IN ONE PLACE AND IT IS NOT THIS COMMENT: it is the failure
         * message of ThrottleCoverageTest.theThrottledPathSetIsSealed, which fires the
         * moment a third pattern is registered. The list was written out here in round 3
         * and was already incomplete when round 4 read it back — it named five targets
         * and the change had touched eight, omitting .env.prod.example, the very artefact
         * that failed. A comment is a DIRECTION mechanism; the failure was DETECTION, so
         * the enumeration stays here (it is what the number means) and the checklist went
         * to the test (it is what a maintainer must not be trusted to remember).
         *
         * 120 is two per second sustained, roughly ten times what the SPA asks for in
         * ordinary use: the results page fires schema + search + panel on load, and the
         * HQL input's typeahead is debounced at 180 ms and only fires while a user-valued
         * field is being typed. It is deliberately looser than the reports budget because
         * a person typing IS the normal traffic here, while nobody types a report.
         *
         * There is no "unlimited": 0 is out of range and fails startup. The off switch is
         * app.rate-limit.enabled — the master switch shared by every limiter that has
         * one (the backlog-rebalance cooldown does not, and does not answer to it).
         */
        @DefaultValue("120") @Min(1) @Max(10_000) int requestsPerMinute
) {}
