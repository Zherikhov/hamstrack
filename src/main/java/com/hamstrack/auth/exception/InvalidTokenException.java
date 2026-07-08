package com.hamstrack.auth.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class InvalidTokenException extends AppException {
    public InvalidTokenException() {
        super("Token is invalid or has expired", HttpStatus.BAD_REQUEST);
    }
}
