package com.ablsoft.inventory.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An accepted product row, held in the in-memory snapshot.
 *
 * <p>This is the internal model. It is never returned from a controller: the API contract lives
 * in a separate {@code ProductResponse} DTO, so the shape we store can change without breaking
 * clients, and so a future persistence layer has somewhere to attach without leaking into JSON.
 *
 * <p>A record because the snapshot is shared, unsynchronised, across the coordinator that builds
 * it and the request threads that read it. Immutability is what makes that safe. It also makes
 * the atomic commit meaningful: publishing a reference to immutable data cannot expose a
 * half-updated object.
 *
 * @param rowNumber     1-based row in the source file, header being row 1. Retained so duplicate
 *                      rejections can name the row that won, and so a caller can trace any
 *                      accepted product back to the spreadsheet.
 * @param productSku    normalised: trimmed and uppercased. Duplicate detection compares this.
 * @param productName   free text; may be blank per the brief
 * @param category      free text; may be blank per the brief
 * @param purchaseDate  parsed under the selected date format
 * @param unitPrice     scaled to 2 decimal places; zero is valid for promotional stock
 * @param quantity      strictly greater than zero
 */
public record Product(
        int rowNumber,
        String productSku,
        String productName,
        String category,
        LocalDate purchaseDate,
        BigDecimal unitPrice,
        int quantity) {

    /**
     * Unit separator (U+001F), a control character no spreadsheet cell can contain.
     *
     * <p>The brief calls for a deterministic, unambiguous key. Naive concatenation is ambiguous:
     * with a plain dash, SKU {@code A-2026} on {@code 01-01-2026} and SKU {@code A} on
     * {@code 2026-01-01} could collide. A separator that cannot appear in either component makes
     * collisions impossible rather than merely unlikely.
     */
    private static final char KEY_SEPARATOR = '\u001F';

    public Product {
        if (rowNumber < 2) {
            throw new IllegalArgumentException("Row number must be 2 or greater; row 1 is the header");
        }
        if (productSku == null || productSku.isBlank()) {
            throw new IllegalArgumentException("Product SKU is required");
        }
        if (purchaseDate == null) {
            throw new IllegalArgumentException("Purchase date is required");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("Unit price must be zero or greater");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }
        productName = productName == null ? "" : productName;
        category = category == null ? "" : category;
    }

    /**
     * The internal map key: normalised SKU, unit separator, ISO date.
     *
     * <p>{@link LocalDate#toString()} is ISO-8601 and fixed width, so the key is canonical
     * regardless of which date format the upload used. Never exposed through the API.
     */
    public String duplicateKey() {
        return productSku + KEY_SEPARATOR + purchaseDate;
    }

    /**
     * Line value: unit price times quantity.
     *
     * <p>{@code BigDecimal} throughout, never {@code double}. Summed across 50,000 rows, binary
     * floating point drifts in a way that shows up in a total someone is expected to reconcile.
     */
    public BigDecimal lineValue() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * Days from purchase date to today, per the brief's stock-age definition.
     *
     * <p>Today is supplied by the caller rather than read from the clock here, so the value is a
     * pure function of its inputs and every row in one import shares a single "today" — a
     * midnight rollover mid-import cannot make row 40,000 a day older than row 2.
     *
     * <p>Future purchase dates are allowed and yield a negative age, as the brief specifies.
     */
    public long stockAgeDays(LocalDate today) {
        return java.time.temporal.ChronoUnit.DAYS.between(purchaseDate, today);
    }
}