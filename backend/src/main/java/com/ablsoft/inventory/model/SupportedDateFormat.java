package com.ablsoft.inventory.model;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The three purchase-date formats the import accepts, each paired with a strict parser.
 *
 * <p>An enum rather than a free-text pattern because every supported format needs a parser that
 * has actually been thought about. Accepting an arbitrary pattern string from a request would
 * let a caller hand us something we cannot honour, and would move the failure from configuration
 * time to the middle of a 50,000-row file.
 *
 * <p>Each constant carries two patterns. The public one is what a caller sends and what error
 * messages quote. The internal one substitutes {@code uuuu} for {@code yyyy}: under
 * {@link ResolverStyle#STRICT}, {@code yyyy} means year-of-era and requires an era field to
 * resolve, so a strict {@code yyyy} parser rejects every date it is given. {@code uuuu} is the
 * proleptic year and behaves the way the public pattern implies.
 *
 * <p>{@link DateTimeFormatter} is immutable and thread safe, so these constants are shared
 * freely across the validation workers with no synchronisation.
 */
public enum SupportedDateFormat {

    US_SLASH("MM/dd/yyyy", "MM/dd/uuuu"),
    EU_SLASH("dd/MM/yyyy", "dd/MM/uuuu"),
    ISO_DASH("yyyy-MM-dd", "uuuu-MM-dd");

    private final String pattern;
    private final DateTimeFormatter formatter;

    SupportedDateFormat(String pattern, String strictPattern) {
        this.pattern = pattern;
        // Locale.ROOT keeps parsing on ASCII digits regardless of the host's default locale.
        this.formatter = DateTimeFormatter.ofPattern(strictPattern, Locale.ROOT)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    /** The caller-facing pattern, e.g. {@code MM/dd/yyyy}. */
    public String pattern() {
        return pattern;
    }

    /**
     * Resolves a requested pattern, matching exactly after trimming.
     *
     * @throws IllegalArgumentException naming the supported patterns, so the message is
     *                                  actionable whether it surfaces at startup or on a request
     */
    public static SupportedDateFormat fromPattern(String requested) {
        String trimmed = requested == null ? "" : requested.trim();
        return Arrays.stream(values())
                .filter(format -> format.pattern.equals(trimmed))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported date format '" + trimmed + "'. Supported formats: " + supportedPatterns()));
    }

    /** Comma-separated list of supported patterns, for error messages. */
    public static String supportedPatterns() {
        return Arrays.stream(values())
                .map(SupportedDateFormat::pattern)
                .collect(Collectors.joining(", "));
    }

    /**
     * Parses a cell value, returning empty when it does not match this format exactly.
     *
     * <p>Returns {@link Optional} rather than throwing because a bad date is an expected outcome
     * on this path, not an exceptional one: the brief requires invalid rows to be rejected
     * individually. {@link DateTimeParseException} captures a stack trace on construction, which
     * is real cost to pay tens of thousands of times for something we already know how to handle.
     *
     * <p>Callers check for a missing value first; empty here means "present but unparseable".
     */
    public Optional<LocalDate> tryParse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            // LocalDate.parse fails on trailing characters, so "09/06/2026 " with junk appended
            // is rejected rather than silently truncated.
            return Optional.of(LocalDate.parse(text.trim(), formatter));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
