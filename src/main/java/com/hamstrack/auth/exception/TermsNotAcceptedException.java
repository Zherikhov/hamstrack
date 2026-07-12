package com.hamstrack.auth.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class TermsNotAcceptedException extends AppException {
    public TermsNotAcceptedException() {
        super("You must accept the Terms of Service to register", HttpStatus.BAD_REQUEST);
    }
}
