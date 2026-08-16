package com.hamstrack.issue.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * 409 — a sprint with this name (case-insensitively) already exists in the project,
 * possibly a <em>COMPLETED</em> one (completed sprints keep their unique name slot,
 * exactly like archived labels/components/versions). The same name in a sibling
 * project is fine — sprints are project-owned.
 *
 * <p>Sequence-based default names ("Sprint 7") can never collide, so this is a
 * hand-typed-name case only.
 */
public class SprintNameConflictException extends AppException {
    public SprintNameConflictException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
