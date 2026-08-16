package com.hamstrack.issue.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Complete a sprint (HD-22, agile-sprints-proposal §4.4). Unlike {@code start}, the
 * body is <strong>required</strong>: the disposition of the unfinished work is a real
 * decision the caller has to make, and defaulting it silently would move issues
 * nobody asked to move.
 *
 * @param targetSprintId required exactly when {@code moveUnfinishedTo == SPRINT};
 *                       must be a FUTURE sprint of this project (422 otherwise)
 */
public record CompleteSprintRequest(
        @NotNull UnfinishedDisposition moveUnfinishedTo,
        UUID targetSprintId
) {}
