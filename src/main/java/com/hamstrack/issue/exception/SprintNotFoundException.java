package com.hamstrack.issue.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * 404 for a sprint id that does not exist <em>within the caller's project</em>. An id
 * from another project or another tenant resolves to empty in
 * {@code findByIdAndProject} and lands here too — indistinguishable from "never
 * existed", which is the point (HD-22 §3.1).
 *
 * <p>Note the deliberate asymmetry: a foreign sprint id in an <em>issue payload</em>
 * ({@code sprintId} on create/update/rank) is a <strong>422 "Unknown sprint"</strong>
 * instead — an invalid field value in a request the caller is entitled to make, which
 * leaks nothing. Only a direct {@code GET /sprints/{id}} yields this 404.
 */
public class SprintNotFoundException extends AppException {
    public SprintNotFoundException() {
        super("Sprint not found", HttpStatus.NOT_FOUND);
    }
}
