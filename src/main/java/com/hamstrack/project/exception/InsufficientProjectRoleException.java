package com.hamstrack.project.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class InsufficientProjectRoleException extends AppException {
    public InsufficientProjectRoleException() {
        super("Insufficient project permissions", HttpStatus.FORBIDDEN);
    }
}
