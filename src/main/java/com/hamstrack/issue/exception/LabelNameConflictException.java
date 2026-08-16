package com.hamstrack.issue.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * 409 — a label with this name (case-insensitively) already exists in the workspace,
 * possibly an <em>archived</em> one (archived rows keep their unique slot, §4.4).
 *
 * <p>Carries the winner's id so the picker's optimistic "Create label 'X'" can recover
 * in one round-trip instead of re-listing; {@code GlobalExceptionHandler} publishes it
 * as the ProblemDetail extension {@code existingId}.
 */
public class LabelNameConflictException extends AppException {

    private final UUID existingId;

    public LabelNameConflictException(String message, UUID existingId) {
        super(message, HttpStatus.CONFLICT);
        this.existingId = existingId;
    }

    public UUID getExistingId() {
        return existingId;
    }
}
