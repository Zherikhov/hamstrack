package com.hamstrack.issue.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateStatusTransitionRequest(
        @NotNull UUID fromStatusId,
        @NotNull UUID toStatusId
) {}
