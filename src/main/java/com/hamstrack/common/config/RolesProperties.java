package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds on the custom-role editor (roles-permissions-s4-spec §9).
 *
 * <p>One knob, and it is a <strong>sprawl guard, never a licence check</strong>. Custom
 * roles are a product feature, not a plan feature: Plane gates them to Enterprise and we
 * deliberately do not, so this value is identical in {@code dc} and {@code cloud}, has no
 * profile override and must never grow one. If custom roles were ever limited per
 * deployment it would be by lowering this number, never by a second code path (§13).
 *
 * <p><strong>There is deliberately no cache knob here.</strong> {@code roleView}'s TTL is
 * a security window — how long a revoked permission survives on a node that did not serve
 * the edit — and a configurable TTL on an authorization cache is a per-deployment way to
 * lengthen that window. It is pinned in {@code CacheConfig} at a value that is small,
 * uniform and free; see {@code RolePermissionCache}'s javadoc for the residual.
 *
 * <p><strong>Fail fast, never clamp</strong> ({@code @Validated}, mirroring
 * {@link AgileProperties}): an out-of-range value aborts startup rather than being
 * silently corrected behind the operator's back.
 */
@Validated
@ConfigurationProperties(prefix = "app.roles")
public record RolesProperties(
        /*
         * Custom roles per workspace, counted across BOTH scopes with built_in = false.
         * Built-in templates belong to no workspace and never count. Creating past it is
         * 409 ROLE_LIMIT_REACHED.
         *
         * 50 because the built-ins cover the four motivating cases with one duplication
         * each, a workspace needing more than a dozen custom roles has an organisational
         * problem the product should not silently absorb, and the value is high enough
         * that no honest user meets it.
         *
         * The count IS taken under a row lock on the workspace (RoleService.duplicate ->
         * WorkspaceRepository.lockForRoleCount), so this is an exact bound rather than an
         * advisory one. The spec's "two concurrent creates can both pass; the failure mode
         * is 51 roles and it is not worth a lock" was wrong on both counts and is corrected
         * here: every concurrent request observing a count below the cap commits, so the
         * overshoot is the caller's achievable concurrency (~200 on a default Tomcat pool),
         * and what it inflates is a cost somebody else pays — GET /roles compares every
         * ordered pair of same-scope roles and is open to every workspace member.
         *
         * That is also why the ceiling of 500 is not free: the assignment block is
         * quadratic in the number of same-scope roles, so raising this towards 500 buys a
         * hundredfold more work on an endpoint the SPA hits on page load. It is bounded,
         * query-free bit testing, but it is not nothing.
         */
        @DefaultValue("50") @Min(1) @Max(500) int maxCustomPerWorkspace
) {
}
