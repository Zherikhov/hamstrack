package com.hamstrack.issue.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateIssueRequest(
        @NotBlank @Size(max = 500) String title,
        String description,
        @NotNull UUID typeId,
        @NotNull UUID statusId,
        // Null = the default priority of the project's priority set
        UUID priorityId,
        UUID assigneeId,
        UUID parentId,
        LocalDate dueDate,
        // Workspace labels to attach (HD-30). Absent/null = none; the whole set is
        // taken as given (duplicates de-duped). Every id is resolved through the
        // issue's OWN workspace — a foreign/unknown/archived id is a 422, never a 404.
        // @Size is a payload guard, deliberately looser than
        // app.classification.max-labels-per-issue (which produces the real 422 after
        // de-duplication): it stops a multi-megabyte UUID array from being fully
        // materialized before that check ever runs.
        @Size(max = 100) List<UUID> labelIds,
        // The project component to file this issue under (HD-31). Resolved through the
        // issue's OWN project — a foreign/unknown/archived id is a 422, never a 404.
        // When the component has auto-assign AND a lead AND no assigneeId was given,
        // the lead becomes the assignee (§5.1); an explicit assigneeId always wins.
        UUID componentId,
        // Versions this change SHIPS IN (HD-32). Absent/null = none; the whole set is
        // taken as given (duplicates de-duped). Every id is resolved through the
        // issue's OWN project — a foreign/unknown/archived id is a 422, never a 404.
        // Linking a RELEASED version is allowed on purpose (back-porting and
        // post-release bookkeeping are legitimate, §6.4).
        // @Size is a payload guard, deliberately looser than
        // app.classification.max-version-links-per-issue (which produces the real 422
        // after de-duplication, per link type).
        @Size(max = 100) List<UUID> fixVersionIds,
        // Versions this defect EXISTS IN (HD-32). Same rules, independent budget — the
        // same version may legitimately be both fix and affects on one issue.
        @Size(max = 100) List<UUID> affectsVersionIds,
        // Custom field values keyed by field id; shapes per field type
        Map<UUID, JsonNode> fields
) {}
