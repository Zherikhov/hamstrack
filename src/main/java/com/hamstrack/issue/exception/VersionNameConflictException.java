package com.hamstrack.issue.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * 409 — a version with this name (case-insensitively) already exists in the
 * project, possibly an <em>archived</em> one (archived rows keep their unique slot,
 * §6.4). The same name in a sibling project is fine — versions are project-owned.
 *
 * <p>Like {@code ComponentNameConflictException} (and unlike
 * {@code LabelNameConflictException}) this carries no {@code existingId}: versions
 * are curated from the Releases page, which already holds the full list, so there
 * is no optimistic on-the-fly create to recover.
 */
public class VersionNameConflictException extends AppException {
    public VersionNameConflictException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
