package com.ablsoft.inventory.model;

import java.math.BigDecimal;

public record InventorySummary(
        long totalProducts,
        BigDecimal totalInventoryValue,
        BigDecimal averageStockAgeDays) {
}
