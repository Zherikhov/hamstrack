package com.hamstrack.admin.dto;

import com.hamstrack.issue.dto.IssueTypeResponse;

import java.util.List;
import java.util.UUID;

public record AdminIssueTypeSetResponse(
        UUID id, String name, boolean systemDefault,
        List<IssueTypeResponse> types,
        long projectsUsing
) {}
