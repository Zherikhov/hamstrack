package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class AlreadyWorkspaceMemberException extends AppException {
    public AlreadyWorkspaceMemberException() {
        super("User is already a member of this workspace", HttpStatus.CONFLICT);
    }
}
