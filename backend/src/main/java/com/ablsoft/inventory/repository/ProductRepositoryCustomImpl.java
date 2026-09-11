package com.ablsoft.inventory.repository;

import com.ablsoft.inventory.model.InventorySummary;
import com.ablsoft.inventory.model.ProductPage;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepositoryCustomImpl implements ProductRepositoryCustom {

    private static final String SELECT_COLUMNS = """
            SELECT id, source_row_number, product_sku, product_name, category,
                   purchase_date, unit_price, quantity, (unit_price * quantity) AS line_value
            FROM product
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProductRepositoryCustomImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ProductPage page(String cursor, int size, String sortBy, String direction, LocalDate asOf) {
        SortColumn column = SortColumn.of(sortBy);
        boolean descending = parseDirection(direction);
        // Stock age runs opposite to purchase date, so the underlying column may sort the other way.
        boolean columnDescending = column.inverted() != descending;

        String comparison = columnDescending ? "<" : ">";
        String order = columnDescending ? "DESC" : "ASC";
        int pageSize = Math.clamp(size, 1, 200);

        StringBuilder sql = new StringBuilder(SELECT_COLUMNS);
        List<Object> arguments = new ArrayList<>();

        if (cursor != null && !cursor.isBlank()) {
            Cursor position = Cursor.decode(cursor);
            Object sortValue = column.parseCursorValue(position.sortValue());
            sql.append("WHERE (").append(column.sql()).append(' ').append(comparison).append(" ?")
                    .append(" OR (").append(column.sql()).append(" = ? AND id ")
                    .append(comparison).append(" ?))\n");
            arguments.add(sortValue);
            arguments.add(sortValue);
            arguments.add(position.id());
        }

        sql.append("ORDER BY ").append(column.sql()).append(' ').append(order)
                .append(", id ").append(order).append('\n')
                .append("LIMIT ?");
        arguments.add(pageSize + 1);

        List<Loaded> loaded = jdbcTemplate.query(
                sql.toString(), (rs, index) -> mapRow(rs, asOf), arguments.toArray());

        boolean hasMore = loaded.size() > pageSize;
        List<Loaded> visible = hasMore ? loaded.subList(0, pageSize) : loaded;

        String nextCursor = visible.isEmpty() || !hasMore
                ? null
                : new Cursor(sortValueOf(visible.getLast(), column), visible.getLast().id()).encode();

        return new ProductPage(
                visible.stream().map(Loaded::row).toList(),
                pageSize,
                column.parameter(),
                descending ? "DESC" : "ASC",
                nextCursor,
                hasMore);
    }

    @Override
    public InventorySummary summary(LocalDate asOf) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*)                                          AS total_products,
                       coalesce(sum(unit_price * quantity), 0)           AS total_value,
                       coalesce(avg(CAST(? AS date) - purchase_date), 0) AS average_age
                FROM product
                """,
                (rs, index) -> new InventorySummary(
                        rs.getLong("total_products"),
                        rs.getBigDecimal("total_value").setScale(2, RoundingMode.HALF_UP),
                        rs.getBigDecimal("average_age").setScale(2, RoundingMode.HALF_UP)),
                Date.valueOf(asOf));
    }

    private record Loaded(long id, ProductPage.Row row) {
    }

    private static Loaded mapRow(ResultSet rs, LocalDate asOf) throws SQLException {
        LocalDate purchaseDate = rs.getObject("purchase_date", LocalDate.class);
        return new Loaded(rs.getLong("id"), new ProductPage.Row(
                rs.getInt("source_row_number"),
                rs.getString("product_sku"),
                rs.getString("product_name"),
                rs.getString("category"),
                purchaseDate,
                rs.getBigDecimal("unit_price"),
                rs.getInt("quantity"),
                rs.getBigDecimal("line_value"),
                ChronoUnit.DAYS.between(purchaseDate, asOf)));
    }

    private static String sortValueOf(Loaded loaded, SortColumn column) {
        ProductPage.Row row = loaded.row();
        return switch (column) {
            case ROW_NUMBER -> String.valueOf(row.rowNumber());
            case PRODUCT_SKU -> row.productSku();
            case PRODUCT_NAME -> row.productName();
            case CATEGORY -> row.category();
            case PURCHASE_DATE, STOCK_AGE_DAYS -> row.purchaseDate().toString();
            case UNIT_PRICE -> row.unitPrice().toPlainString();
            case QUANTITY -> String.valueOf(row.quantity());
            case LINE_VALUE -> row.lineValue().toPlainString();
        };
    }

    private static boolean parseDirection(String direction) {
        if ("DESC".equalsIgnoreCase(direction)) {
            return true;
        }
        if ("ASC".equalsIgnoreCase(direction)) {
            return false;
        }
        throw new IllegalArgumentException(
                "Unknown direction '" + direction + "'. Valid values: ASC, DESC");
    }
}
