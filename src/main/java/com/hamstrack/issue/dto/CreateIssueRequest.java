package com.hamstrack.issue.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateIssueRequest(
        @NotBlank @Size(max = 500) String title,
        // A payload guard, not a column guard: issues.description is TEXT and overflows
        // nothing. 10000 is FieldValueService.MAX_TEXTAREA_LENGTH — one number for a block of
        // prose, deliberately, so a TEXTAREA custom field and a description refuse alike
        // (HD-171 §4.3). Written as a bare literal so that a FUTURE column-width scanner CAN
        // read it — no scanner reads this field today. The one that exists,
        // EmailLengthBoundTest, applies its `max\s*=\s*(\d+)` regex only to declarations it
        // reaches from an @Email, so it never looks here. Keeping the form it reads costs
        // nothing and is what makes the field scannable later; 10_000 would read as 10 and a
        // constant reference as no bound at all. What is meant to police the VALUE is a
        // behavioural test (§5.3), not a source scan.
        // What makes the absence expensive here is amplification
        // — every edit copies the old and new description verbatim into issue_history, so one
        // unbounded field is stored three times after a single edit.
        @Size(max = 10000) String description,
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
        // The sprint to file this issue into (HD-22). Absent/null = the backlog, which
        // is the normal case. Resolved through the issue's OWN project — a
        // foreign/unknown id is a 422 "Unknown sprint", never a 404; a COMPLETED sprint
        // is a 422 too (its membership is a delivered fact).
        UUID sprintId,
        // Native story-point estimate (HD-22 §3.4). Absent/null = unestimated, which is
        // deliberately NOT the same as 0. Range 0…999 with at most 2 decimals — 422
        // otherwise (the DB backs it with issues_story_points_ck).
        BigDecimal storyPoints,
        // Accepted for payload symmetry with UpdateIssueRequest (spec §7 lists all four
        // additions for both), and INERT here: a brand-new issue has nothing to clear, so
        // "no sprint"/"unestimated" is already what omitting sprintId/storyPoints means.
        // They exist so a client that shares one payload builder between create and
        // update cannot be broken by an operator enabling FAIL_ON_UNKNOWN_PROPERTIES.
        // Boxed Boolean for the Jackson-3 primitive trap, coalesced below.
        Boolean clearSprint,
        Boolean clearStoryPoints,
        // Custom field values keyed by field id; shapes per field type
        Map<UUID, JsonNode> fields
) {
    public CreateIssueRequest {
        clearSprint = clearSprint != null && clearSprint;
        clearStoryPoints = clearStoryPoints != null && clearStoryPoints;
    }
}
