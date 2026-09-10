package com.ablsoft.inventory.web.dto;

import com.ablsoft.inventory.model.Product;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One accepted product, as the API returns it.
 *
 * <p>A DTO distinct from {@link Product} so the wire contract is decoupled from the internal
 * model: the duplicate key never leaks, and a future persistence layer can reshape {@code Product}
 * without breaking the Angular table.
 *
 * <p>{@code purchaseDate} is a {@link LocalDate}, which Jackson writes as ISO {@code yyyy-MM-dd} —
 * the output format the brief requires — regardless of which format the upload used.
 *
 * @param stockAgeDays days between the purchase date and the import's reference date; negative
 *                     for a future purchase date, which the brief permits
 * @param lineValue    unit price times quantity, so the table can show a per-row total without
 *                     doing money arithmetic in JavaScript
 */
public record ProductResponse(
        int rowNumber,
        String productSku,
        String productName,
        String category,
        LocalDate purchaseDate,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineValue,
        long stockAgeDays) {

    public static ProductResponse from(Product product, LocalDate asOf) {
        return new ProductResponse(
                product.rowNumber(),
                product.productSku(),
                product.productName(),
                product.category(),
                product.purchaseDate(),
                product.unitPrice(),
                product.quantity(),
                product.lineValue(),
                product.stockAgeDays(asOf));
    }
}