package com.hamstrack.project.dto;

import com.hamstrack.project.entity.BoardMode;
import com.hamstrack.project.entity.Project;

/**
 * How a project's team delivers (HD-102, delivery-paths-proposal §11.3) — three
 * stored capabilities plus the label derived from them. Rides
 * {@link ProjectResponse}, which every surface already fetches, so
 * {@code useProjectDelivery} costs no extra request.
 *
 * <p><strong>Rule A (§5.1): this object is presentation, never API.</strong> It tells
 * a client what vocabulary to render; it does not tell it what will be accepted. Every
 * endpoint behaves identically whatever these values are — there is no
 * capability-conditional 4xx anywhere in the codebase, and no capability is ever a
 * reason for a field to be absent from a response.
 *
 * @param board      KANBAN | SCRUM — reuses the existing {@link BoardMode} enum; the
 *                   deprecated {@code ProjectResponse.boardMode} mirror carries the
 *                   same value
 * @param releases   versions: the Releases page and rail item, fix/affects pickers
 * @param estimation story points: the points input and every point sum
 * @param preset     DERIVED, read-only — see {@link DeliveryPreset#of}
 */
public record DeliveryResponse(
        BoardMode board,
        boolean releases,
        boolean estimation,
        DeliveryPreset preset
) {
    public static DeliveryResponse of(Project p) {
        return new DeliveryResponse(
                p.getBoardMode(), p.isReleasesEnabled(), p.isEstimationEnabled(),
                DeliveryPreset.of(p.getBoardMode(), p.isReleasesEnabled(), p.isEstimationEnabled()));
    }
}
