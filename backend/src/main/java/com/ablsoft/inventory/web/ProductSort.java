package com.ablsoft.inventory.web;

import com.ablsoft.inventory.model.Product;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The sortable columns of {@code GET /api/products}, each paired with its comparator.
 *
 * <p>An allow-list, not a reflective property lookup. A client cannot ask to sort by an
 * arbitrary field name, and adding a sortable column is a deliberate act with a comparator
 * written for it — which matters for {@code stockAgeDays}, a derived value with no backing field
 * for reflection to find.
 */
public final class ProductSort {

    public enum Field {

        PRODUCT_SKU("productSku"),
        PRODUCT_NAME("productName"),
        CATEGORY("category"),
        PURCHASE_DATE("purchaseDate"),
        UNIT_PRICE("unitPrice"),
        QUANTITY("quantity"),
        STOCK_AGE_DAYS("stockAgeDays");

        private final String parameter;

        Field(String parameter) {
            this.parameter = parameter;
        }

        public String parameter() {
            return parameter;
        }

        /**
         * Names sort case-insensitively, because a table ordered with every capitalised name
         * ahead of every lowercase one looks broken to the person reading it.
         *
         * <p>Every comparator breaks ties on row number, so two products with the same category
         * always appear in the same order across requests. Without that, which of them lands on
         * page 1 versus page 2 would depend on sort implementation details.
         */
        Comparator<Product> comparator(LocalDate asOf) {
            Comparator<Product> comparator = switch (this) {
                case PRODUCT_SKU -> Comparator.comparing(Product::productSku, String.CASE_INSENSITIVE_ORDER);
                case PRODUCT_NAME -> Comparator.comparing(Product::productName, String.CASE_INSENSITIVE_ORDER);
                case CATEGORY -> Comparator.comparing(Product::category, String.CASE_INSENSITIVE_ORDER);
                case PURCHASE_DATE -> Comparator.comparing(Product::purchaseDate);
                case UNIT_PRICE -> Comparator.comparing(Product::unitPrice);
                case QUANTITY -> Comparator.comparingInt(Product::quantity);
                case STOCK_AGE_DAYS -> Comparator.comparingLong(product -> product.stockAgeDays(asOf));
            };
            return comparator.thenComparingInt(Product::rowNumber);
        }

        static Field fromParameter(String requested) {
            if (requested == null || requested.isBlank()) {
                return PRODUCT_SKU;   // the brief's default
            }
            String trimmed = requested.trim();
            return Arrays.stream(values())
                    .filter(field -> field.parameter.equalsIgnoreCase(trimmed))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unsupported sort field '" + trimmed + "'. Supported fields: " + supportedFields()));
        }

        static String supportedFields() {
            return Arrays.stream(values()).map(Field::parameter).collect(Collectors.joining(", "));
        }
    }

    public enum Direction {
        ASC, DESC;

        static Direction fromParameter(String requested) {
            if (requested == null || requested.isBlank()) {
                return ASC;   // the brief's default
            }
            String normalised = requested.trim().toUpperCase(Locale.ROOT);
            return switch (normalised) {
                case "ASC", "ASCENDING" -> ASC;
                case "DESC", "DESCENDING" -> DESC;
                default -> throw new IllegalArgumentException(
                        "Unsupported sort direction '" + requested.trim() + "'. Supported: ASC, DESC");
            };
        }
    }

    private ProductSort() {
    }

    /** Resolves the request's sort parameters into a comparator, applying the defaults. */
    public static Comparator<Product> comparator(Field field, Direction direction, LocalDate asOf) {
        Comparator<Product> comparator = field.comparator(asOf);
        return direction == Direction.DESC ? comparator.reversed() : comparator;
    }

    public static Field field(String requested) {
        return Field.fromParameter(requested);
    }

    public static Direction direction(String requested) {
        return Direction.fromParameter(requested);
    }
}