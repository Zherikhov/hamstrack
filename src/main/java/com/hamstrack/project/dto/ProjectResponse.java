package com.hamstrack.project.dto;

import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.project.entity.BoardMode;
import com.hamstrack.project.entity.Project;

import java.time.Instant;
import java.util.List;
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
        // The caller's EXPLICIT project role KEY, or "VIEWER" when they have no
        // project_members row. Byte-identical to pre-HD-123 on purpose (S3 deleted the
        // ordinal enum but not its wire values): this deliberately does NOT report the
        // effective role a member inherits from the project-access default (§5.2) —
        // `myPermissions` below carries that truth instead. The two therefore disagree for
        // a member with no explicit row (myRole VIEWER, but a full Contributor permission
        // set), which is exactly today's reality: VIEWER has always been the
        // everybody-fallback that grants everything (§2.2). DISPLAY ONLY:
        // `myRole === 'MANAGER'` cannot express a custom role, so a component that reads it
        // for a decision is wrong by construction (§5.3) — S5 removed the last one.
        String myRole,
        // The caller's effective PROJECT-scoped permissions as flat keys — the only
        // permitted input to a UI gate, and never absent (an empty list is a real answer;
        // a missing field would make a client render permissively — §12). Includes the
        // implied grants of workspace-level `project.administer.all` (§17.2).
        List<String> myPermissions,
        Instant createdAt
) {
    @SuppressWarnings("deprecation") // the boardMode mirror is populated on purpose
    public static ProjectResponse of(Project p, String explicitRole, PermissionSet permissions) {
        return new ProjectResponse(
                p.getId(), p.getWorkspace().getId(),
                p.getName(), p.getKey(), p.getDescription(),
                p.isArchived(), p.getBoardMode(), DeliveryResponse.of(p),
                explicitRole, permissions.asWireStrings(), p.getCreatedAt());
    }
}
