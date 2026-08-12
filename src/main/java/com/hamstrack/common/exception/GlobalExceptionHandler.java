package com.hamstrack.common.exception;

import com.hamstrack.common.ratelimit.RateLimitedException;
import com.hamstrack.search.HqlSemanticException;
import com.hamstrack.search.parser.HqlParseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ProblemDetail> handleAppException(AppException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    // More specific than the AppException handler — adds the Retry-After hint
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitedException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    // HQL parse error (Advanced Search §7.1): 422 with a highlight span. The custom
    // ProblemDetail properties (position/length/token/errorType) drive the SPA's
    // inline underline. errorType is always "PARSE_ERROR".
    @ExceptionHandler(HqlParseException.class)
    public ResponseEntity<ProblemDetail> handleHqlParse(HqlParseException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
        problem.setProperty("position", ex.getPosition());
        problem.setProperty("length", ex.getLength());
        if (ex.getToken() != null) {
            problem.setProperty("token", ex.getToken());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem);
    }

    // HQL semantic error (Advanced Search §7.2): 422, field-anchored. errorType is
    // always "SEMANTIC_ERROR"; field/position are included when known.
    @ExceptionHandler(HqlSemanticException.class)
    public ResponseEntity<ProblemDetail> handleHqlSemantic(HqlSemanticException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
        if (ex.getField() != null) {
            problem.setProperty("field", ex.getField());
        }
        if (ex.getPosition() >= 0) {
            problem.setProperty("position", ex.getPosition());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "File is too large");
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        // Coalesce a null default message (a constraint without one) to "" —
        // Collectors.toMap NPEs on a null value, turning a 400 into a 500
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "",
                        (a, b) -> a));
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }
}
