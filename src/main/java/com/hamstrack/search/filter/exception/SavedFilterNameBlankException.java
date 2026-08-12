package com.hamstrack.search.filter.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * A saved-filter update tried to set a blank {@code name} (whitespace-only). On
 * create this is caught by {@code @NotBlank} (400); on a partial update the field
 * is optional (null = no-op), so the non-blank check lives in the service — but it
 * is still a plain input-validation failure, so it maps to 400, not the HQL 422
 * envelope used for query errors.
 */
public class SavedFilterNameBlankException extends AppException {
    public SavedFilterNameBlankException() {
        super("Filter name must not be blank", HttpStatus.BAD_REQUEST);
    }
}
