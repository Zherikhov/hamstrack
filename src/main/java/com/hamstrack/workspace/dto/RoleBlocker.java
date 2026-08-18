package com.hamstrack.workspace.dto;

import java.util.UUID;

/**
 * A role this one <strong>cannot</strong> assign, and the first permission that is why.
 *
 * <p>{@code missing} is the offending permission <em>key</em>, straight from
 * {@link com.hamstrack.common.security.PermissionSet#firstNotCovered} — the same call the
 * runtime ceiling makes, so the editor's "cannot assign Contributor — this role is missing
 * {@code issue.rank}" and the 403 a real assignment would answer quote the identical
 * string. That is the point of publishing it: discovering the ceiling as a 403 six weeks
 * later is the Jira complaint this epic was built against.
 *
 * @param missing the permission key; never a rendered sentence, so the client owns the copy
 */
public record RoleBlocker(UUID roleId, String name, String missing) {
}
