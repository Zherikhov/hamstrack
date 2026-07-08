package com.hamstrack.project.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class ProjectNotFoundException extends AppException {
    public ProjectNotFoundException() {
        super("Project not found", HttpStatus.NOT_FOUND);
    }
}
