package com.hamstrack.auth.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class RegistrationDisabledException extends AppException {
    public RegistrationDisabledException() {
        super("Public registration is disabled", HttpStatus.FORBIDDEN);
    }
}
