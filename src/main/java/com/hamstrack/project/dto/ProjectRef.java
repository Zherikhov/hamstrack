package com.hamstrack.project.dto;

import com.hamstrack.project.entity.Project;

import java.util.UUID;

/**
 * The smallest useful reference to a project: enough to link to it and enough for a human
 * to recognise it, and nothing else.
 *
 * <p>Introduced by HD-136 for the body of the <strong>409</strong> a workspace member
 * removal answers when it would leave projects without an administrator. A bare message
 * ("some projects would be stranded") was explicitly rejected: the admin's next action is
 * to go and give each of those projects another administrator, and a response that does
 * not name them turns a one-minute fix into a hunt through every project's member list.
 *
 * <p><strong>Tenancy.</strong> Publishing ids/keys/names here discloses nothing: every
 * project named is a project of the workspace the caller is already administering, and
 * they can enumerate all of them with {@code GET /api/workspaces/{ws}/projects}. It must
 * stay that way — do not reuse this record to name a resource the caller could not
 * already list.
 */
public record ProjectRef(UUID id, String key, String name) {

    public static ProjectRef of(Project project) {
        return new ProjectRef(project.getId(), project.getKey(), project.getName());
    }
}
