package com.hamstrack.search.filter.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * A saved-filter name collides with one the same owner already has in the workspace
 * (Advanced Search proposal §8.3 — {@code name} is unique per {@code (workspace,
 * owner)}). 409; different owners may reuse a name.
 */
public class SavedFilterNameConflictException extends AppException {
    public SavedFilterNameConflictException(String name) {
        super("A filter named '" + name + "' already exists", HttpStatus.CONFLICT);
    }
}
