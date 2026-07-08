package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class InsufficientWorkspaceRoleException extends AppException {
    public InsufficientWorkspaceRoleException() {
        super("Insufficient workspace permissions", HttpStatus.FORBIDDEN);
    }
}
