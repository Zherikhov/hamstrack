package com.hamstrack.project.dto;

import com.hamstrack.project.entity.BoardMode;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectRole;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String key,
        String description,
        boolean archived,
        // DEPRECATED (HD-102) — superseded by `delivery.board`, which carries the
        // identical value. Kept as a mirror for exactly one minor release so clients
        // mid-flight (and any script written against 0.13.0) keep working; it is still
        // ACCEPTED on PATCH too, where it must not disagree with `delivery.board`
        // (400 if it does). New readers use `delivery`.
        @Deprecated BoardMode boardMode,
        // HD-102 — how this team delivers: board (KANBAN|SCRUM), releases, estimation,
        // plus the DERIVED preset label. It rides this response because NavRail, both
        // settings areas and every board/backlog render already fetch it, so asking
        // "does this project do X?" costs no extra request on the hot path — and,
        // crucially, no surface has to infer it from the presence of data any more
        // (Rule C, §5.3). Presentation only: nothing in it changes what the API accepts
        // or which status code it returns (Rule A, §5.1).
        DeliveryResponse delivery,
        ProjectRole myRole,
        Instant createdAt
) {
    @SuppressWarnings("deprecation") // the boardMode mirror is populated on purpose
    public static ProjectResponse of(Project p, ProjectRole role) {
        return new ProjectResponse(
                p.getId(), p.getWorkspace().getId(),
                p.getName(), p.getKey(), p.getDescription(),
                p.isArchived(), p.getBoardMode(), DeliveryResponse.of(p), role, p.getCreatedAt());
    }
}
