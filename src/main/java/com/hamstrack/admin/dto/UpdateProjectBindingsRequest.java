package com.hamstrack.admin.dto;

import java.util.UUID;

/**
 * Full replacement of all bindings (the matrix UI always sends all);
 * NULL = use the system default.
 */
public record UpdateProjectBindingsRequest(UUID workflowId, UUID prioritySetId, UUID fieldSetId,
                                           UUID issueTypeSetId) {}
