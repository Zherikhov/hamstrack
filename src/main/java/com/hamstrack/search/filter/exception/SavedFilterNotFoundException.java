package com.hamstrack.search.filter.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * A saved filter is not visible to (or not mutable by) the caller. Deliberately
 * <strong>404, never 403</strong> — a non-member, a filter in another workspace, a
 * non-owner reaching a private filter, or a non-owner trying to edit/delete even a
 * <em>shared</em> filter all get the same "not found" so existence is never leaked
 * (Advanced Search proposal §3/§8.3/§16.5 — the project's #1 bug class).
 */
public class SavedFilterNotFoundException extends AppException {
    public SavedFilterNotFoundException() {
        super("Saved filter not found", HttpStatus.NOT_FOUND);
    }
}
