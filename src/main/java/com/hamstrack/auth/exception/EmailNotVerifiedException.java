package com.hamstrack.auth.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class EmailNotVerifiedException extends AppException {
    public EmailNotVerifiedException() {
        super("Email address is not verified", HttpStatus.FORBIDDEN);
    }
}
