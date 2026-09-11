package com.ablsoft.inventory.validate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.DateUtil;

public final class DateNormalizer {

    private static final List<DateTimeFormatter> PATTERNS = Stream.of(
                    "uuuu-M-d",        // ISO, and what the Excel reader emits for real date cells
                    "M/d/uuuu",        // month first: the preferred reading of an ambiguous value
                    "d/M/uuuu",
                    "uuuu/M/d",
                    "M-d-uuuu",
                    "d-M-uuuu",
                    "d MMMM uuuu",
                    "MMMM d, uuuu",
                    "MMMM d uuuu",
                    "d MMM uuuu",
                    "MMM d, uuuu",
                    "MMM d uuuu")
            .map(pattern -> DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                    .withResolverStyle(ResolverStyle.STRICT))
            .toList();

    private static final long MIN_EXCEL_SERIAL = 1;
    private static final long MAX_EXCEL_SERIAL = 2958465;

    private static final int ISO_DATE_LENGTH = 10;

    private DateNormalizer() {
    }

    public static Optional<LocalDate> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();

        // An ISO timestamp is a date with baggage; keep the date and drop the time.
        if (value.length() > ISO_DATE_LENGTH && value.charAt(ISO_DATE_LENGTH) == 'T') {
            value = value.substring(0, ISO_DATE_LENGTH);
        }

        for (DateTimeFormatter formatter : PATTERNS) {
            try {
                // LocalDate.parse rejects trailing characters, so "15/03/2024 (approx)" fails
                // rather than being silently truncated to a date.
                return Optional.of(LocalDate.parse(value, formatter));
            } catch (DateTimeParseException e) {
                // Not this pattern. A failure here is the normal path, not an error.
            }
        }
        return excelSerial(value);
    }

    private static Optional<LocalDate> excelSerial(String value) {
        if (!value.matches("\\d{1,7}")) {
            return Optional.empty();
        }
        long serial = Long.parseLong(value);
        if (serial < MIN_EXCEL_SERIAL || serial > MAX_EXCEL_SERIAL) {
            return Optional.empty();
        }
        return Optional.of(DateUtil.getLocalDateTime(serial).toLocalDate());
    }
}
