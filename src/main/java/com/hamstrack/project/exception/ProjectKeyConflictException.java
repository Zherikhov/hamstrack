package com.hamstrack.project.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class ProjectKeyConflictException extends AppException {
    public ProjectKeyConflictException() {
        super("A project with this key already exists in the workspace", HttpStatus.CONFLICT);
    }
}
