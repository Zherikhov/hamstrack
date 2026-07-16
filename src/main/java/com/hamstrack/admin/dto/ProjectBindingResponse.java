package com.hamstrack.admin.dto;

import java.util.UUID;

/** One row of the admin assignment matrix. NULL binding ids = system default. */
public record ProjectBindingResponse(
        UUID projectId, String key, String name, boolean archived,
        UUID workspaceId, String workspaceName,
        UUID workflowId, UUID prioritySetId, UUID fieldSetId, UUID issueTypeSetId
) {}
