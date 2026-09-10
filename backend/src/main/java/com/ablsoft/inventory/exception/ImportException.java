package com.ablsoft.inventory.exception;

/**
 * Every way an import can fail, as one sealed hierarchy.
 *
 * <p>Sealed and nested in a single file on purpose: the exception handler switches over this
 * type exhaustively, so adding a failure mode becomes a compile error at the place that must
 * decide its HTTP status, rather than a new exception that quietly falls through to a 500.
 *
 * <p>No Spring types here. Each variant carries a stable machine-readable {@code code} and a
 * human-readable message; the web layer owns the mapping to status codes.
 */
public abstract sealed class ImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    private ImportException(String code, String message) {
        super(message);
        this.code = code;
    }

    private ImportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** File type we cannot parse at all. Maps to 415. */
    public static final class UnsupportedFileType extends ImportException {
        private static final long serialVersionUID = 1L;

        public UnsupportedFileType(String message) {
            super("unsupported_file_type", message);
        }
    }

    /**
     * Right type, unusable content: missing headers, bad encoding, over a limit, empty.
     * Maps to 400. Distinct from {@link UnsupportedFileType} because the caller's corrective
     * action differs — fix the file, rather than send a different kind of file.
     */
    public static final class InvalidFile extends ImportException {
        private static final long serialVersionUID = 1L;

        public InvalidFile(String message) {
            super("invalid_file", message);
        }

        public InvalidFile(String message, Throwable cause) {
            super("invalid_file", message, cause);
        }
    }

    /** Another import holds the single permit. Maps to 409. */
    public static final class ImportAlreadyRunning extends ImportException {
        private static final long serialVersionUID = 1L;

        public static final String MESSAGE = "An import is already in progress";

        public ImportAlreadyRunning() {
            super("import_in_progress", MESSAGE);
        }
    }

    /**
     * The import started but could not finish. Maps to 422. The previously committed snapshot
     * is left serving traffic untouched.
     */
    public static final class ImportFailed extends ImportException {
        private static final long serialVersionUID = 1L;

        public ImportFailed(String message) {
            super("import_failed", message);
        }

        public ImportFailed(String message, Throwable cause) {
            super("import_failed", message, cause);
        }
    }
}
