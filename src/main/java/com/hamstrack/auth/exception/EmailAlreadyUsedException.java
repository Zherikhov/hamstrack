package com.hamstrack.auth.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class EmailAlreadyUsedException extends AppException {
    public EmailAlreadyUsedException() {
        super("Email is already registered", HttpStatus.CONFLICT);
    }
}
