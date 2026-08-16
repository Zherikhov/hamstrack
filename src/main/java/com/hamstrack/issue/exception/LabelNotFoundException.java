package com.hamstrack.issue.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * 404 for a label id that does not exist <em>within the caller's workspace</em>.
 * A foreign-tenant id resolves to empty in {@code findByIdAndWorkspace} and lands
 * here too — indistinguishable from "never existed", which is the point.
 */
public class LabelNotFoundException extends AppException {
    public LabelNotFoundException() {
        super("Label not found", HttpStatus.NOT_FOUND);
    }
}
