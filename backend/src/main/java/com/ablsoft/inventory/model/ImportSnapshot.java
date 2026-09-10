package com.ablsoft.inventory.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable result of one successful import: the "Atomic In-Memory Commit" stage.
 *
 * <p>A snapshot is built entirely in the importing thread's local state and published in one
 * reference assignment. Read endpoints therefore never observe a half-built import, and a failed
 * import simply never produces a snapshot, leaving the previous one serving traffic untouched.
 *
 * <p>The accepted products live in a {@link LinkedHashMap} keyed by
 * {@link Product#duplicateKey()}, exactly as the brief specifies, wrapped unmodifiable. Insertion
 * order is source row order, because the coordinator inserts in that order. A plain map is
 * correct here precisely because there is a single writer: a {@code ConcurrentHashMap} would
 * prevent structural corruption but would let whichever worker finished first define which
 * duplicate wins, which is the wrong guarantee.
 *
 * <p>The summary figures are computed once, at commit, rather than on each request. They are
 * derived from immutable data, so recomputing them per request would burn CPU to reach an
 * identical answer.
 *
 * <p>{@code referenceDate} is the "today" used for every stock-age figure in this import. Fixing
 * it at commit time keeps one import internally consistent; the products endpoint recomputes
 * stock age against the current date so a long-lived snapshot does not report stale ages.
 */
public final class ImportSnapshot {

    private static final ImportSnapshot EMPTY = new ImportSnapshot(
            "", null, null, null, Map.of(), List.of());

    private final String sourceFileName;
    private final SupportedDateFormat dateFormat;
    private final Instant importedAt;
    private final LocalDate referenceDate;
    private final Map<String, Product> productsByKey;
    private final List<RejectedRow> rejectedRows;

    private final BigDecimal totalInventoryValue;
    private final BigDecimal averageStockAgeDays;

    private ImportSnapshot(
            String sourceFileName,
            SupportedDateFormat dateFormat,
            Instant importedAt,
            LocalDate referenceDate,
            Map<String, Product> productsByKey,
            List<RejectedRow> rejectedRows) {

        this.sourceFileName = sourceFileName;
        this.dateFormat = dateFormat;
        this.importedAt = importedAt;
        this.referenceDate = referenceDate;
        this.productsByKey = Collections.unmodifiableMap(new LinkedHashMap<>(productsByKey));
        this.rejectedRows = List.copyOf(rejectedRows);
        this.totalInventoryValue = sumInventoryValue(this.productsByKey.values());
        this.averageStockAgeDays = averageStockAge(this.productsByKey.values(), referenceDate);
    }

    /**
     * Commits a completed import. The caller passes collections it has finished building; both
     * are defensively copied, so no reference the caller still holds can mutate the snapshot.
     */
    public static ImportSnapshot commit(
            String sourceFileName,
            SupportedDateFormat dateFormat,
            Instant importedAt,
            LocalDate referenceDate,
            Map<String, Product> acceptedProducts,
            List<RejectedRow> rejectedRows) {

        return new ImportSnapshot(
                sourceFileName, dateFormat, importedAt, referenceDate, acceptedProducts, rejectedRows);
    }

    /** The state before any import has succeeded. Endpoints serve this rather than 404 or null. */
    public static ImportSnapshot empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return importedAt == null;
    }

    /** Accepted products in original file row order. */
    public List<Product> products() {
        return List.copyOf(productsByKey.values());
    }

    /** The internal key map. Kept package-visible in spirit: never serialised to the API. */
    public Map<String, Product> productsByKey() {
        return productsByKey;
    }

    public List<RejectedRow> rejectedRows() {
        return rejectedRows;
    }

    public int successfulImportCount() {
        return productsByKey.size();
    }

    public int rejectedRowCount() {
        return rejectedRows.size();
    }

    public BigDecimal totalInventoryValue() {
        return totalInventoryValue;
    }

    public BigDecimal averageStockAgeDays() {
        return averageStockAgeDays;
    }

    public String sourceFileName() {
        return sourceFileName;
    }

    public SupportedDateFormat dateFormat() {
        return dateFormat;
    }

    public Instant importedAt() {
        return importedAt;
    }

    public LocalDate referenceDate() {
        return referenceDate;
    }

    private static BigDecimal sumInventoryValue(Iterable<Product> products) {
        BigDecimal total = BigDecimal.ZERO;
        for (Product product : products) {
            total = total.add(product.lineValue());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Mean stock age across accepted products, to two decimal places. An empty import averages
     * zero rather than dividing by zero or reporting null.
     */
    private static BigDecimal averageStockAge(java.util.Collection<Product> products, LocalDate referenceDate) {
        if (products.isEmpty() || referenceDate == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal totalDays = BigDecimal.ZERO;
        for (Product product : products) {
            totalDays = totalDays.add(BigDecimal.valueOf(product.stockAgeDays(referenceDate)));
        }
        return totalDays.divide(BigDecimal.valueOf(products.size()), 2, RoundingMode.HALF_UP);
    }
}