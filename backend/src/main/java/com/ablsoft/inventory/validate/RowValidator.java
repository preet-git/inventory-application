package com.ablsoft.inventory.validate;

import com.ablsoft.inventory.model.FileFormat;
import com.ablsoft.inventory.model.Product;
import com.ablsoft.inventory.model.RejectedRow;
import com.ablsoft.inventory.model.RowData;
import com.ablsoft.inventory.model.RowOutcome;
import com.ablsoft.inventory.model.SupportedDateFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Applies the business rules to one parsed row: the "Business Logic Rules" stage of the brief's
 * lifecycle, sitting between formula evaluation and the atomic in-memory commit.
 *
 * <p>Instances are immutable and stateless beyond the two settings fixed for the whole import,
 * so one instance is shared across every validation worker with no synchronisation. Nothing here
 * touches Apache POI, the accepted map or the rejection list; a worker receives an immutable
 * {@link RowData} and returns an immutable {@link RowOutcome}, which is what makes running this
 * in parallel safe.
 *
 * <p>Duplicate detection is deliberately absent. It depends on rows already seen, so it belongs
 * to the single-threaded coordinator; a worker has no consistent view of what came before it.
 *
 * <p>Rules are applied independently and their failures accumulated, so one upload surfaces
 * every problem in a row rather than only the first.
 */
public final class RowValidator {

    static final String CSV_FORMULA_REASON = "Formula expressions are supported only in Excel files";

    private static final int MAX_SKU_LENGTH = 64;
    private static final String CURRENCY_SYMBOLS = "$£€₹¥";

    private final SupportedDateFormat dateFormat;
    private final FileFormat sourceFormat;

    public RowValidator(SupportedDateFormat dateFormat, FileFormat sourceFormat) {
        this.dateFormat = dateFormat;
        this.sourceFormat = sourceFormat;
    }

    public RowOutcome validate(RowData row) {
        // A row the reader could not evaluate never had usable cell values, so the formula
        // failure is the whole story and running the field rules would only add noise.
        if (row.hasFormulaError()) {
            return new RowOutcome.Rejected(
                    RejectedRow.single(row.rowNumber(), row.productSku(), row.formulaError()));
        }

        List<String> reasons = new ArrayList<>();

        if (!sourceFormat.supportsFormulas() && containsFormulaExpression(row)) {
            reasons.add(CSV_FORMULA_REASON);
        }

        String sku = validateSku(row.productSku(), reasons);
        LocalDate purchaseDate = validatePurchaseDate(row.purchaseDate(), reasons);
        BigDecimal unitPrice = validateUnitPrice(row.unitPrice(), reasons);
        int quantity = validateQuantity(row.quantity(), reasons);

        if (!reasons.isEmpty()) {
            // The original SKU, untouched, so the reader of the report can find the row.
            return new RowOutcome.Rejected(
                    RejectedRow.of(row.rowNumber(), row.productSku(), reasons));
        }

        return new RowOutcome.Accepted(new Product(
                row.rowNumber(),
                sku,
                row.productName().trim(),
                row.category().trim(),
                purchaseDate,
                unitPrice,
                quantity));
    }

    /**
     * Checks only the four required columns. Product Name and Category are free text that may be
     * blank, so a name legitimately beginning with {@code =} is stored rather than rejected.
     */
    private boolean containsFormulaExpression(RowData row) {
        return startsWithEquals(row.productSku())
                || startsWithEquals(row.purchaseDate())
                || startsWithEquals(row.unitPrice())
                || startsWithEquals(row.quantity());
    }

    private static boolean startsWithEquals(String value) {
        return value.trim().startsWith("=");
    }

    private String validateSku(String raw, List<String> reasons) {
        String sku = raw.trim().toUpperCase(Locale.ROOT);
        if (sku.isEmpty()) {
            reasons.add("Product SKU is required");
            return null;
        }
        if (sku.length() > MAX_SKU_LENGTH) {
            reasons.add("Product SKU exceeds " + MAX_SKU_LENGTH + " characters");
            return null;
        }
        return sku;
    }

    private LocalDate validatePurchaseDate(String raw, List<String> reasons) {
        if (raw.isBlank()) {
            reasons.add("Purchase Date is required");
            return null;
        }
        Optional<LocalDate> parsed = dateFormat.tryParse(raw);
        if (parsed.isEmpty()) {
            reasons.add("Purchase Date '" + raw.trim() + "' does not match the selected format "
                    + dateFormat.pattern());
            return null;
        }
        return parsed.get();
    }

    /**
     * Zero is valid, per the brief's promotional-inventory case. Values are normalised to two
     * decimal places with HALF_UP; a price carrying more precision than a currency can express
     * is rounded rather than rejected, since spreadsheets routinely produce such values from
     * formulas. Say the word if you would rather reject them outright.
     */
    private BigDecimal validateUnitPrice(String raw, List<String> reasons) {
        if (raw.isBlank()) {
            reasons.add("Unit Price is required");
            return null;
        }
        String cleaned = stripCurrencySymbol(raw.trim());
        try {
            BigDecimal price = new BigDecimal(cleaned);
            if (price.signum() < 0) {
                reasons.add("Unit Price must be zero or greater, but was " + price.toPlainString());
                return null;
            }
            return price.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            reasons.add("Unit Price '" + raw.trim() + "' is not a valid decimal value");
            return null;
        }
    }

    /**
     * Excel hands back whole numbers as "2" or "2.0" depending on cell formatting, so an integral
     * decimal is accepted while a genuinely fractional quantity such as "2.5" is not.
     */
    private int validateQuantity(String raw, List<String> reasons) {
        if (raw.isBlank()) {
            reasons.add("Quantity is required");
            return 0;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim()).stripTrailingZeros();
            if (value.scale() > 0) {
                reasons.add("Quantity '" + raw.trim() + "' must be a whole number");
                return 0;
            }
            int quantity = value.intValueExact();
            if (quantity <= 0) {
                reasons.add("Quantity must be greater than zero, but was " + quantity);
                return 0;
            }
            return quantity;
        } catch (ArithmeticException | NumberFormatException e) {
            reasons.add("Quantity '" + raw.trim() + "' is not a valid whole number");
            return 0;
        }
    }

    private static String stripCurrencySymbol(String value) {
        if (!value.isEmpty() && CURRENCY_SYMBOLS.indexOf(value.charAt(0)) >= 0) {
            return value.substring(1).trim();
        }
        return value;
    }
}