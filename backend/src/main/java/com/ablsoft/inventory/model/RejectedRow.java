package com.ablsoft.inventory.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A row that did not make it into the accepted map, with every reason it failed.
 *
 * <p>The brief requires three things of a rejection: the original row number, the original SKU
 * when one is available, and one or more clear reasons. All three are mandatory here by
 * construction, so a rejection cannot be created in a state that fails that contract.
 *
 * <p>Reasons is a list rather than a single string because a row can fail several rules at once.
 * Reporting only the first would force someone to fix a spreadsheet one error per upload.
 *
 * @param rowNumber   1-based row in the source file; the header is row 1
 * @param productSku  the SKU exactly as it appeared in the file, or empty when the cell was
 *                    blank or the row could not be read far enough to have one
 * @param reasons     at least one human-readable reason, in the order the rules were applied
 */
public record RejectedRow(int rowNumber, String productSku, List<String> reasons) {

    public RejectedRow {
        if (rowNumber < 2) {
            throw new IllegalArgumentException("Row number must be 2 or greater; row 1 is the header");
        }
        if (reasons == null || reasons.isEmpty()) {
            throw new IllegalArgumentException("A rejected row must carry at least one reason");
        }
        productSku = productSku == null ? "" : productSku.trim();
        // Defensive immutable copy: the validator builds this from a mutable ArrayList.
        reasons = List.copyOf(reasons);
    }

    public static RejectedRow of(int rowNumber, String productSku, List<String> reasons) {
        return new RejectedRow(rowNumber, productSku, reasons);
    }

    public static RejectedRow single(int rowNumber, String productSku, String reason) {
        return new RejectedRow(rowNumber, productSku, List.of(reason));
    }

    /**
     * A later occurrence of an already-accepted SKU and purchase date.
     *
     * <p>The message names the winning row so the person fixing the file can see which of the
     * two rows was kept, rather than being told only that a duplicate exists somewhere.
     */
    public static RejectedRow duplicate(int rowNumber, String productSku, LocalDate purchaseDate, int winningRow) {
        return single(rowNumber, productSku,
                "Duplicate of row " + winningRow + ": SKU " + productSku
                        + " already imported for purchase date " + purchaseDate);
    }
}
