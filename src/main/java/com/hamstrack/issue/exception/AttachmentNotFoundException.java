package com.hamstrack.issue.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class AttachmentNotFoundException extends AppException {
    public AttachmentNotFoundException() {
        super("Attachment not found", HttpStatus.NOT_FOUND);
    }
}
