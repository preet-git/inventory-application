package com.ablsoft.inventory.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The three upload formats the application accepts, and what each one implies.
 *
 * <p>The brief requires validating the extension, the parser that will handle the file, and the
 * MIME type when one is available, while explicitly not trusting the browser's MIME type alone.
 * This enum covers the first and third of those and declares which parser applies; the
 * byte-level check that a file really is what it claims lives in the format validator, using
 * POI's {@code FileMagic}, because that requires reading the file rather than its name.
 *
 * <p>Anything not listed here is rejected with HTTP 415.
 */
public enum FileFormat {

    /**
     * CSV has no formula concept, which is why {@code supportsFormulas} is false: a cell whose
     * text begins with {@code =} is a spreadsheet expression the file cannot express, and the
     * brief requires rejecting that row rather than storing the text.
     *
     * <p>The MIME list is deliberately generous. Browsers and operating systems disagree wildly
     * about CSV: Windows with Excel installed commonly reports {@code application/vnd.ms-excel}
     * for a .csv file, and many clients send {@code text/plain} or nothing at all. Being strict
     * here would reject valid uploads for reasons that have nothing to do with the file.
     */
    CSV(".csv", false, List.of(
            "text/csv",
            "application/csv",
            "text/plain",
            "text/comma-separated-values",
            "application/vnd.ms-excel",
            "application/octet-stream")),

    XLSX(".xlsx", true, List.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream")),

    XLS(".xls", true, List.of(
            "application/vnd.ms-excel",
            "application/excel",
            "application/octet-stream"));

    private final String extension;
    private final boolean supportsFormulas;
    private final List<String> acceptableMimeTypes;

    FileFormat(String extension, boolean supportsFormulas, List<String> acceptableMimeTypes) {
        this.extension = extension;
        this.supportsFormulas = supportsFormulas;
        this.acceptableMimeTypes = List.copyOf(acceptableMimeTypes);
    }

    public String extension() {
        return extension;
    }

    /** True for the Excel formats, whose formulas POI evaluates before validation. */
    public boolean supportsFormulas() {
        return supportsFormulas;
    }

    public boolean isExcel() {
        return this != CSV;
    }

    /**
     * Resolves the format from a file name, or empty when the extension is not one we accept.
     *
     * <p>Empty is the signal for a 415. The caller decides the HTTP status; this enum only
     * reports whether it recognises the name.
     */
    public static Optional<FileFormat> fromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(format -> lower.endsWith(format.extension))
                .findFirst();
    }

    /**
     * Whether a declared MIME type is consistent with this format.
     *
     * <p>Corroboration, not proof. A blank or missing type passes, because many clients send
     * none and absence is not evidence of a wrong file. The authoritative check is the magic-byte
     * inspection in the format validator; this only catches an obvious contradiction, such as a
     * file named {@code .xlsx} that the client declares as {@code application/pdf}.
     */
    public boolean acceptsMimeType(String declaredMimeType) {
        if (declaredMimeType == null || declaredMimeType.isBlank()) {
            return true;
        }
        String normalised = declaredMimeType.trim().toLowerCase(Locale.ROOT);
        int parameterStart = normalised.indexOf(';');   // strip "; charset=UTF-8"
        if (parameterStart >= 0) {
            normalised = normalised.substring(0, parameterStart).trim();
        }
        return acceptableMimeTypes.contains(normalised);
    }

    /** Comma-separated extension list, for 415 error messages. */
    public static String supportedExtensions() {
        return Arrays.stream(values())
                .map(FileFormat::extension)
                .collect(Collectors.joining(", "));
    }
}