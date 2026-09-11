package com.ablsoft.inventory.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProductPage(
        List<Row> content,
        int size,
        String sortBy,
        String direction,
        String nextCursor,
        boolean hasMore) {

    public record Row(
            int rowNumber,
            String productSku,
            String productName,
            String category,
            LocalDate purchaseDate,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineValue,
            long stockAgeDays) {
    }
}
