package com.hamstrack.issue.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class IssueNotFoundException extends AppException {
    public IssueNotFoundException() {
        super("Issue not found", HttpStatus.NOT_FOUND);
    }
}
