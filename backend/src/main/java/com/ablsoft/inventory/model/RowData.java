package com.ablsoft.inventory.model;

/**
 * One spreadsheet row after cell parsing and formula evaluation, before business validation.
 *
 * <p>This is the handoff between the reader thread and the validation workers, and its contents
 * are chosen for exactly that. Every field is a plain string, and no Apache POI object —
 * {@code Cell}, {@code Row}, {@code Workbook}, {@code FormulaEvaluator} — is reachable from here.
 * POI is not thread safe, and a shared {@code FormulaEvaluator} corrupts its internal
 * dependency cache silently, producing wrong numbers rather than an exception. Making POI types
 * unreachable from the DTO means a worker cannot touch them by accident; the confinement is
 * structural rather than a rule someone has to remember.
 *
 * <p>It sits at the boundary of the lifecycle the brief lays out:
 *
 * <pre>
 * Cell-by-Cell Parse -&gt; Formula Evaluation -&gt; [RowData] -&gt; Business Logic Rules
 * </pre>
 *
 * <p>Formula evaluation has already happened by the time a {@code RowData} exists. A worker sees
 * {@code "159.98"}, never {@code "=B2*C2"}, which is why the brief's rule that formula results
 * must still pass field validation falls out for free: the validator cannot tell the difference,
 * so it applies the same rules either way.
 *
 * <p>A record, so it is immutable and safe to publish across threads with no synchronisation.
 *
 * @param rowNumber      1-based row in the source file; header is row 1, first data row is 2
 * @param productSku     raw cell text, untrimmed and not yet uppercased
 * @param productName    raw cell text
 * @param category       raw cell text
 * @param purchaseDate   raw cell text, not yet parsed against the selected format
 * @param unitPrice      raw cell text, not yet parsed to {@code BigDecimal}
 * @param quantity       raw cell text, not yet parsed to {@code int}
 * @param formulaError   the reason evaluation failed, or {@code null} when it succeeded
 */
public record RowData(
        int rowNumber,
        String productSku,
        String productName,
        String category,
        String purchaseDate,
        String unitPrice,
        String quantity,
        String formulaError) {

    /**
     * Sentinel marking end of file on the parsed-row queue.
     *
     * <p>Compared by identity ({@code ==}), never by {@code equals}, so no real row can be
     * mistaken for it. Its row number is invalid by construction, which makes accidental
     * processing fail loudly.
     */
    private static final RowData END_OF_FILE =
            new RowData(-1, "", "", "", "", "", "", null);

    /** Values arrive from POI and CSV in inconsistent shapes; normalise nulls to empty here. */
    public RowData {
        productSku = productSku == null ? "" : productSku;
        productName = productName == null ? "" : productName;
        category = category == null ? "" : category;
        purchaseDate = purchaseDate == null ? "" : purchaseDate;
        unitPrice = unitPrice == null ? "" : unitPrice;
        quantity = quantity == null ? "" : quantity;
    }

    public static RowData endOfFile() {
        return END_OF_FILE;
    }

    public boolean isEndOfFile() {
        return this == END_OF_FILE;
    }

    /**
     * Convenience factory for rows whose cells all evaluated cleanly.
     */
    public static RowData of(
            int rowNumber,
            String productSku,
            String productName,
            String category,
            String purchaseDate,
            String unitPrice,
            String quantity) {
        return new RowData(rowNumber, productSku, productName, category,
                purchaseDate, unitPrice, quantity, null);
    }

    /**
     * A row the reader could not fully evaluate, carrying the reason forward instead of throwing.
     *
     * <p>The brief requires a failed formula to reject that row with its row number and a clear
     * reason, not to abort the import. Recording the failure and letting the row continue through
     * the pipeline keeps rejection handling in one place — the validator — rather than splitting
     * it between the reader and the validator.
     */
    public static RowData withFormulaError(int rowNumber, String reason) {
        return new RowData(rowNumber, "", "", "", "", "", "", reason);
    }

    public boolean hasFormulaError() {
        return formulaError != null;
    }
}