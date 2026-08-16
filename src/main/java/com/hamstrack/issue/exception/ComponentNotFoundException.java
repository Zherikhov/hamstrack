package com.hamstrack.issue.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * 404 for a component id that does not exist <em>within the caller's project</em>.
 * An id from another project or another tenant resolves to empty in
 * {@code findByIdAndProject} and lands here too — indistinguishable from "never
 * existed", which is the point.
 */
public class ComponentNotFoundException extends AppException {
    public ComponentNotFoundException() {
        super("Component not found", HttpStatus.NOT_FOUND);
    }
}
