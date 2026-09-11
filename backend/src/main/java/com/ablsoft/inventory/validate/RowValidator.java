package com.ablsoft.inventory.validate;

import com.ablsoft.inventory.model.Product;
import com.ablsoft.inventory.model.RawRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class RowValidator {

    private static final int MAX_SKU_LENGTH = 64;
    private static final int MAX_TEXT_LENGTH = 255;
    private static final String CURRENCY_SYMBOLS = "$£€₹¥";

    public record Result(Product product, List<String> reasons) {

        public boolean accepted() {
            return product != null;
        }
    }

    public Result validate(RawRow row) {
        // A row whose formula could not be evaluated never had usable values, so the read failure
        // is the whole story and the field rules would only add noise.
        if (row.isUnreadable()) {
            return new Result(null, List.of(row.readError()));
        }

        List<String> reasons = new ArrayList<>();
        String sku = validateSku(row.productSku(), reasons);
        String name = requireText(row.productName(), "Product Name", reasons);
        String category = requireText(row.category(), "Category", reasons);
        LocalDate purchaseDate = validatePurchaseDate(row.purchaseDate(), reasons);
        BigDecimal unitPrice = validateUnitPrice(row.unitPrice(), reasons);
        int quantity = validateQuantity(row.quantity(), reasons);

        if (!reasons.isEmpty()) {
            return new Result(null, List.copyOf(reasons));
        }
        return new Result(
                new Product(row.rowNumber(), sku, name, category, purchaseDate, unitPrice, quantity),
                List.of());
    }

    private static String validateSku(String raw, List<String> reasons) {
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

    private static String requireText(String raw, String field, List<String> reasons) {
        String text = raw.trim();
        if (text.isEmpty()) {
            reasons.add(field + " is required");
            return null;
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            reasons.add(field + " exceeds " + MAX_TEXT_LENGTH + " characters");
            return null;
        }
        return text;
    }

    private static LocalDate validatePurchaseDate(String raw, List<String> reasons) {
        if (raw.isBlank()) {
            reasons.add("Purchase Date is required");
            return null;
        }
        Optional<LocalDate> parsed = DateNormalizer.parse(raw);
        if (parsed.isEmpty()) {
            reasons.add("Purchase Date '" + raw.trim() + "' is not a recognisable date");
            return null;
        }
        return parsed.get();
    }

    private static BigDecimal validateUnitPrice(String raw, List<String> reasons) {
        if (raw.isBlank()) {
            reasons.add("Unit Price is required");
            return null;
        }
        try {
            BigDecimal price = new BigDecimal(cleanNumber(raw));
            if (price.signum() < 0) {
                reasons.add("Unit Price must not be negative, but was " + price.toPlainString());
                return null;
            }
            return price.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            reasons.add("Unit Price '" + raw.trim() + "' is not a valid amount");
            return null;
        }
    }

    private static int validateQuantity(String raw, List<String> reasons) {
        if (raw.isBlank()) {
            reasons.add("Quantity is required");
            return 0;
        }
        try {
            BigDecimal value = new BigDecimal(cleanNumber(raw)).stripTrailingZeros();
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

    private static String cleanNumber(String raw) {
        String value = raw.trim()
                .replaceAll("^[" + CURRENCY_SYMBOLS + "]\\s*", "")
                .replaceAll("\\s*[" + CURRENCY_SYMBOLS + "]$", "");
        return value.matches("\\d{1,3}(,\\d{3})+(\\.\\d+)?") ? value.replace(",", "") : value;
    }
}
