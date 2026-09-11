package com.ablsoft.inventory.exception;

import org.springframework.http.HttpStatus;

public class ImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    private ImportException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public static ImportException invalidFile(String message) {
        return new ImportException(HttpStatus.BAD_REQUEST, "invalid_file", message, null);
    }

    public static ImportException invalidFile(String message, Throwable cause) {
        return new ImportException(HttpStatus.BAD_REQUEST, "invalid_file", message, cause);
    }

    public static ImportException unsupportedType(String message) {
        return new ImportException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_file_type", message, null);
    }

    public static ImportException busy(String message) {
        return new ImportException(HttpStatus.TOO_MANY_REQUESTS, "import_queue_full", message, null);
    }

    public static ImportException notFound(String message) {
        return new ImportException(HttpStatus.NOT_FOUND, "not_found", message, null);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
