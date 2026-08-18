package com.hamstrack.workspace.dto;

import com.hamstrack.project.dto.ProjectRef;

import java.util.List;

/**
 * What {@code DELETE /api/workspaces/{ws}/members/{userId}} answers when the caller passed
 * {@code adoptStrandedProjects=true} and something was actually adopted: <strong>200</strong>
 * with the projects the caller now administers. An ordinary removal, which grants nothing,
 * stays <strong>204</strong> with no body.
 *
 * <p><strong>Why the status changes at all</strong> (HD-136 review round 3): the flag is
 * accepted on a first attempt with no prior 409, so a client that always sets it — an SPA
 * control wired once, a script that retries on any conflict — could accumulate project roles
 * for its user with nothing on the wire ever saying so. The adoption was visible only in a
 * server log the actor cannot read. A body they cannot miss is the difference between
 * consent and a side effect, and it costs one status code on a path that is by definition
 * the unusual one.
 *
 * <p>The list is the one {@code ProjectAdminGuard.adoptAll} returns: server-derived, ordered
 * by project key, and exactly the projects written to — never an echo of what the client
 * asked for, because the client cannot ask for any.
 */
public record MemberRemovalResponse(List<ProjectRef> adoptedProjects) {
}
