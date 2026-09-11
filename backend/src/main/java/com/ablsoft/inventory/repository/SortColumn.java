package com.ablsoft.inventory.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.stream.Collectors;

public enum SortColumn {

    ROW_NUMBER("rowNumber", "source_row_number", Kind.INTEGER, false),
    PRODUCT_SKU("productSku", "product_sku", Kind.TEXT, false),
    PRODUCT_NAME("productName", "product_name", Kind.TEXT, false),
    CATEGORY("category", "category", Kind.TEXT, false),
    PURCHASE_DATE("purchaseDate", "purchase_date", Kind.DATE, false),
    UNIT_PRICE("unitPrice", "unit_price", Kind.DECIMAL, false),
    QUANTITY("quantity", "quantity", Kind.INTEGER, false),
    LINE_VALUE("lineValue", "(unit_price * quantity)", Kind.DECIMAL, false),
    STOCK_AGE_DAYS("stockAgeDays", "purchase_date", Kind.DATE, true);

    private enum Kind { TEXT, INTEGER, DECIMAL, DATE }

    private final String parameter;
    private final String sql;
    private final Kind kind;
    private final boolean inverted;

    SortColumn(String parameter, String sql, Kind kind, boolean inverted) {
        this.parameter = parameter;
        this.sql = sql;
        this.kind = kind;
        this.inverted = inverted;
    }

    public String parameter() {
        return parameter;
    }

    public String sql() {
        return sql;
    }

    public boolean inverted() {
        return inverted;
    }

    public static SortColumn of(String parameter) {
        return Arrays.stream(values())
                .filter(column -> column.parameter.equals(parameter))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown sortBy '" + parameter + "'. Valid values: " + supported()));
    }

    public static String supported() {
        return Arrays.stream(values()).map(SortColumn::parameter).collect(Collectors.joining(", "));
    }

    public Object parseCursorValue(String text) {
        try {
            return switch (kind) {
                case TEXT -> text;
                case INTEGER -> Integer.valueOf(text);
                case DECIMAL -> new BigDecimal(text);
                case DATE -> LocalDate.parse(text);
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cursor does not match sortBy '" + parameter + "'", e);
        }
    }
}
