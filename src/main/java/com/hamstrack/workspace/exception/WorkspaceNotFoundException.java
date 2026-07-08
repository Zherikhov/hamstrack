package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class WorkspaceNotFoundException extends AppException {
    public WorkspaceNotFoundException() {
        // 404 whether workspace doesn't exist or caller isn't a member — never reveal existence
        super("Workspace not found", HttpStatus.NOT_FOUND);
    }
}
