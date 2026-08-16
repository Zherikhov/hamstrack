package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds on the classification primitives of a workspace — how many labels one issue
 * may carry, how large the workspace's label catalog may grow, how large a project's
 * component and version catalogs may grow, and how many versions one issue may link
 * per role — labels-components-versions-proposal §3.9.
 *
 * <p>These are <strong>DoS guards, not mode switches</strong>: identical base-level
 * defaults in DC and Cloud, no profile override, no feature flag. Exceeding a cap is
 * a business-rule rejection → {@code 422 UNPROCESSABLE_CONTENT}, mirroring
 * {@link BoardProperties}' server-side cap philosophy.
 *
 * <p><strong>Fail fast, never clamp</strong> ({@code @Validated}): a bogus
 * {@code MAX_LABELS_PER_ISSUE=0} used to bind silently and brick label attachment for
 * the whole install (every payload 422s "At most 0 labels per issue") with nothing in
 * the logs. An out-of-range value now aborts startup, the same posture
 * {@code JwtService} takes on a too-short secret.
 */
@Validated
@ConfigurationProperties(prefix = "app.classification")
public record ClassificationProperties(
        @DefaultValue("20") @Min(1) @Max(100) int maxLabelsPerIssue,
        @DefaultValue("1000") @Min(1) @Max(100_000) int maxLabelsPerWorkspace,
        /*
         * Component creation is curator-gated, which is NOT a barrier: any authenticated
         * user can create their own workspace, is its OWNER, and is therefore curator of
         * every project in it — so growing a component catalog is self-serve after all.
         * That matters beyond self-harm because ResolutionContextFactory.build loads the
         * WHOLE component catalog of every visible project on every /search,
         * /search/schema and /search/suggest request; on shared Cloud infrastructure an
         * unbounded catalog is a noisy-neighbour CPU/heap cost paid by co-resident
         * tenants. (The /schema COMPONENT_PICKLIST_LIMIT truncates only the response,
         * after the full load.)
         */
        @DefaultValue("500") @Min(1) @Max(100_000) int maxComponentsPerProject,
        /*
         * Version links per issue, enforced PER LINK TYPE (HD-32): an issue may carry up
         * to this many fix versions AND up to this many affects versions — the two roles
         * are independent budgets, because "ships in 2.4.0 and 2.3.1" and "affects three
         * releases" are unrelated statements about the same issue.
         *
         * Paired with maxVersionsPerProject below: batching the progress query only
         * converts a hard failure into unbounded work, which is an argument FOR a
         * ceiling, not a substitute for one.
         */
        @DefaultValue("20") @Min(1) @Max(100) int maxVersionLinksPerIssue,
        /*
         * Size of a project's version catalog (HD-32). "A release plan is hand-curated
         * and has a natural ceiling" describes intended usage, not a bound: version
         * creation is curator-gated, and curator is self-serve — any authenticated user
         * creates their own workspace, is its OWNER, and is therefore curator of every
         * project in it. The threat model is byte-for-byte the component one:
         * ResolutionContextFactory.build loads every version NAME of every visible
         * project on every /search, /search/schema and /search/suggest, one line below
         * the component load. GET .../versions amplifies it a second way — it has no
         * Pageable, and respondAll batches the grouped progress query at 500, so an
         * unbounded catalog is hundreds of SQL round-trips and as many serialized objects
         * out of ONE GET, holding a connection out of a small pool.
         *
         * ARCHIVED (and released) versions COUNT toward this: they keep their unique name
         * slot and are still loaded, so excluding them would leave the ceiling bypassable
         * by create → archive → repeat.
         */
        @DefaultValue("500") @Min(1) @Max(100_000) int maxVersionsPerProject
) {}
