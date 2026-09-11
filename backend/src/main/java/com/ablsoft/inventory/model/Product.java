package com.ablsoft.inventory.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record Product(
        int rowNumber,
        String productSku,
        String productName,
        String category,
        LocalDate purchaseDate,
        BigDecimal unitPrice,
        int quantity) {

    public String uniqueKey() {
        return uniqueKey(productSku, purchaseDate);
    }

    public static String uniqueKey(String productSku, LocalDate purchaseDate) {
        return productSku + "|" + purchaseDate;
    }

    public BigDecimal lineValue() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public long stockAgeDays(LocalDate asOf) {
        return ChronoUnit.DAYS.between(purchaseDate, asOf);
    }
}
