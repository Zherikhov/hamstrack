package com.hamstrack.issue.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class CommentNotFoundException extends AppException {
    public CommentNotFoundException() {
        super("Comment not found", HttpStatus.NOT_FOUND);
    }
}
