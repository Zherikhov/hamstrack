package com.hamstrack.issue.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * 409 — a component with this name (case-insensitively) already exists in the
 * project, possibly an <em>archived</em> one (archived rows keep their unique slot,
 * §5.4). The same name in a sibling project is fine — components are project-owned.
 *
 * <p>Unlike {@code LabelNameConflictException} this carries no {@code existingId}:
 * components are curated from a settings table that already holds the full list, so
 * there is no optimistic on-the-fly create to recover.
 */
public class ComponentNameConflictException extends AppException {
    public ComponentNameConflictException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
