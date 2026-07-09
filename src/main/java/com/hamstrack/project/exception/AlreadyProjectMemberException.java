package com.hamstrack.project.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class AlreadyProjectMemberException extends AppException {
    public AlreadyProjectMemberException() {
        super("User is already a member of this project", HttpStatus.CONFLICT);
    }
}
