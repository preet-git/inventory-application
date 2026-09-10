package com.ablsoft.inventory.web;

import com.ablsoft.inventory.exception.ImportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Translates failures into HTTP responses. The only place in the application that decides a
 * status code, so the mapping can be read in one screen.
 *
 * <p>Every error body has the same two fields, so a client parses one shape regardless of what
 * went wrong.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Uniform error body. */
    public record ApiError(String code, String message) {
    }

    /**
     * The sealed hierarchy pays off here: this switch is exhaustive, so a new failure mode
     * cannot be added without the compiler demanding a status code for it.
     */
    @ExceptionHandler(ImportException.class)
    public ResponseEntity<ApiError> handleImportException(ImportException e) {
        HttpStatus status = switch (e) {
            case ImportException.UnsupportedFileType ignored -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;   // 415
            case ImportException.InvalidFile ignored -> HttpStatus.BAD_REQUEST;                      // 400
            case ImportException.ImportAlreadyRunning ignored -> HttpStatus.CONFLICT;                // 409
            case ImportException.ImportFailed ignored -> HttpStatus.UNPROCESSABLE_ENTITY;            // 422
        };

        if (status.is5xxServerError() || e instanceof ImportException.ImportFailed) {
            // The previous snapshot is still being served; log the cause for diagnosis.
            log.warn("Import failed [{}]: {}", e.code(), e.getMessage(), e);
        }
        return ResponseEntity.status(status).body(new ApiError(e.code(), e.getMessage()));
    }

    /**
     * Thrown by {@code SupportedDateFormat.fromPattern}, {@code ProductSort} and the page guard.
     * These are all "your parameter was wrong" cases, and each already carries a message naming
     * the valid values.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_request", e.getMessage()));
    }

    /**
     * Tomcat rejected the body before our size check could run. Reported as 400 with the same
     * shape as the service-level size error, so a caller sees one consistent answer to "the file
     * is too big" wherever it was caught. Switch to 413 if you prefer the more specific status.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(new ApiError(
                "invalid_file", "Uploaded file exceeds the maximum permitted size"));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleMissingPart(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError(
                "missing_parameter", "Required request part 'file' is missing"));
    }

    /** Constraint failures on controller parameters, such as a negative page index. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException e) {
        return ResponseEntity.badRequest().body(new ApiError(
                "invalid_request", "Invalid request parameter: page index must be zero or greater"));
    }

    /**
     * Last resort. The message is deliberately generic — an internal failure should not leak
     * class names or paths to a caller — while the stack trace goes to the log.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unexpected failure handling request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "An unexpected error occurred"));
    }
}