package com.hamstrack.project.dto;

import com.hamstrack.project.entity.BoardMode;
import jakarta.validation.constraints.Null;

/**
 * The write side of {@link DeliveryResponse} (HD-102, delivery-paths-proposal §11.3).
 * Optional wherever it appears and <strong>partial in every member</strong>: a null
 * member leaves that capability alone on PATCH, and falls back to the lean
 * new-project default on POST (Kanban, releases off, estimation off — §7 / open
 * question 2).
 *
 * <p>The two flags are <strong>boxed</strong> on purpose. Boot 4 / Jackson 3 enable
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so a primitive {@code boolean} here would make
 * every partial body that omits it fail deserialization with
 * {@code 400 "Failed to read request"} — the trap that already bit
 * {@code UpdateIssueRequest}'s clear-flags. There is deliberately <em>no</em>
 * canonical constructor coalescing null → false: null must keep meaning "absent",
 * which is the whole partial-update contract.
 *
 * <p>{@code board} is a closed set, so an unknown value is a 400 from Jackson rather
 * than a silent fall-through. Nothing here can ever change a status code on another
 * endpoint — capabilities gate the UI, never the API (Rule A, §5.1).
 *
 * @param preset accepted ONLY so it can be REJECTED. The preset is derived and never
 *               settable (open question 5): the three capabilities are the single
 *               source of truth. A client that echoes back the whole {@code delivery}
 *               object it read from a GET gets a <strong>400</strong> naming the
 *               field, which is a far better failure than silently ignoring a value
 *               the caller believed it was setting. Declared as {@code String} (not
 *               as {@link DeliveryPreset}) so the rejection is ours and legible rather
 *               than a Jackson "unknown enum value" error.
 *               <p>Enforced <strong>twice, on purpose</strong>. The {@code @Null}
 *               constraint puts the rule on the <em>type</em>, so it holds for every
 *               web caller that ever embeds this record — a future project-template,
 *               bulk-import or "apply preset" endpoint cannot silently reintroduce the
 *               ignore-the-field behaviour this {@code String} exists to prevent. It
 *               only fires where the enclosing component is {@code @Valid}-cascaded
 *               (see {@code CreateProjectRequest}/{@code UpdateProjectRequest}) — bean
 *               validation does not descend into a nested object otherwise. The
 *               belt-and-braces check in {@code ProjectService.applyDelivery} stays for
 *               non-web callers such as {@code DemoDataService}, which never pass
 *               through the validation boundary at all.
 */
public record DeliveryRequest(
        BoardMode board,
        Boolean releases,
        Boolean estimation,
        @Null(message = "delivery.preset is derived from board/releases/estimation and cannot be set")
        String preset
) {}
