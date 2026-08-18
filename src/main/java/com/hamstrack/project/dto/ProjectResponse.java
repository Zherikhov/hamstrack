package com.hamstrack.project.dto;

import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.project.entity.BoardMode;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.util.Assert;

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
        // HD-129 (S6) — the §5.2 default-role chain for this project: its own override and
        // the workspace default behind it. READ-ONLY; the picker and the grant ceiling that
        // must bound it are S7's (see DefaultRoleResponse). Like `delivery`, it rides this
        // response because every surface that needs it already fetches it, and like
        // `delivery` it changes nothing about what any endpoint accepts or returns.
        DefaultRoleResponse defaultRole,
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
        // NULL on the LIST endpoint only, when the explicit project_members.role_id fails
        // the scope/ownership assertion (HD-127 §3b): the project stays in the list, and the
        // role just refused is never rendered in its place.
        String myRole,
        // The caller's effective PROJECT-scoped permissions as flat keys — the only
        // permitted input to a UI gate, and never absent (an empty list is a real answer;
        // a missing field would make a client render permissively — §12). Includes the
        // implied grants of workspace-level `project.administer.all` (§17.2).
        List<String> myPermissions,
        Instant createdAt
) {
    @SuppressWarnings("deprecation") // the boardMode mirror is populated on purpose
    public static ProjectResponse of(Project p, Workspace w, String explicitRole, PermissionSet permissions) {
        // The invariant this signature cannot state. `w` supplies BOTH `workspaceId` and
        // `defaultRole.workspaceRoleId`, so a mismatched pair ships a response naming the
        // wrong workspace AND that workspace's default project role — a role id from a
        // tenant the caller may have no membership in. Every caller passes the matching pair
        // today and `projectContext` asserts it; this is what keeps that true for the next
        // one, and it is an assertion rather than `p.getWorkspace().getId()` because
        // deriving would fix only the first of the two fields: `workspaceRoleId` cannot come
        // off the project without initialising the lazy Workspace proxy, which is a query
        // per row on GET /projects. Costs nothing: reading a lazy proxy's IDENTIFIER does
        // not initialise it, and the message is a supplier so the happy path builds no
        // string.
        Assert.state(p.getWorkspace().getId().equals(w.getId()),
                () -> "project " + p.getId() + " is not in workspace " + w.getId());
        return new ProjectResponse(
                p.getId(), w.getId(),
                p.getName(), p.getKey(), p.getDescription(),
                p.isArchived(), p.getBoardMode(), DeliveryResponse.of(p),
                DefaultRoleResponse.of(p, w),
                explicitRole, permissions.asWireStrings(), p.getCreatedAt());
    }
}
