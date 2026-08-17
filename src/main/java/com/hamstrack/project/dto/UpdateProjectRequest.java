package com.hamstrack.project.dto;

import com.hamstrack.project.entity.BoardMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Partial project update — an absent field is left alone.
 *
 * <p>{@code delivery} (HD-102 §11.3) carries the three delivery capabilities, each of
 * them independently nullable: {@code {"delivery":{"releases":true}}} turns releases on
 * and touches nothing else. Setting a capability to the value it already has is a no-op
 * that still returns 200 with the current state (§13). Switching a capability
 * <strong>never</strong> destroys, clears or moves data — no {@code sprint_id} is
 * nulled, no version link removed, no point value erased — and confirmations are a UI
 * affordance only: the API answers 200 with no confirmation and no 409 (§13, Rule A).
 * It is {@code @Valid}-cascaded so the nested constraints (notably {@code preset}'s
 * {@code @Null}) actually run — bean validation validates the outer record only unless
 * the nested component is annotated.
 *
 * <p>{@code boardMode} (HD-22 §3.5) is the <strong>deprecated</strong> top-level mirror
 * of {@code delivery.board}, still accepted so clients written against 0.13.0 keep
 * working. Sending both is fine when they <em>agree</em>; sending both with different
 * values is a <strong>400</strong> rather than a silent winner-takes-all, because
 * either choice would be a guess about which half of the client is stale.
 *
 * <p>Both are closed sets, so an unknown value is a 400 from Jackson rather than a
 * silent fall-through; {@code null} leaves the current mode. Adding {@code boardMode}
 * here is what widened this endpoint's gate from project MANAGER to project
 * <em>curator</em> (§3.2) — see {@code ProjectService.update}.
 */
public record UpdateProjectRequest(
        @Size(min = 2, max = 255) String name,
        String description,
        @Deprecated BoardMode boardMode,
        @Valid DeliveryRequest delivery
) {}
